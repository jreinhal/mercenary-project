package com.jreinhal.mercenary.government.auth;

import com.jreinhal.mercenary.government.auth.CacCertificateParser.CacIdentity;
import com.jreinhal.mercenary.service.AuditService;
import com.jreinhal.mercenary.security.ClientIpResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.FileInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * CAC/PIV Authentication Filter for Government edition.
 * This filter extracts and validates X.509 client certificates from mutual TLS connections
 * and makes them available for downstream authentication processing.
 *
 * Only active when app.auth-mode=CAC (government deployments).
 */
@Component
@Order(1)
@ConditionalOnProperty(name = "app.auth-mode", havingValue = "CAC")
public class CacAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CacAuthFilter.class);
    private static final String CLIENT_AUTH_OID = "1.3.6.1.5.5.7.3.2";

    private static final String CERT_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";
    private static final String CAC_IDENTITY_ATTRIBUTE = "cac.identity";
    private static final String CAC_CERTIFICATE_ATTRIBUTE = "cac.certificate";

    @Value("${app.cac.chain-validation.enabled:true}")
    private boolean chainValidationEnabled;
    @Value("${app.cac.chain-validation.require-truststore:false}")
    private boolean chainValidationRequireTrustStore;
    @Value("${app.cac.truststore.path:}")
    private String trustStorePath;
    @Value("${app.cac.truststore.password:}")
    private String trustStorePassword;

    // Prefer Spring Boot TLS settings when present (common for mTLS deployments).
    @Value("${server.ssl.trust-store:}")
    private String serverSslTrustStore;
    @Value("${server.ssl.trust-store-password:}")
    private String serverSslTrustStorePassword;

    @Value("${app.cac.crl-check-enabled:false}")
    private boolean crlCheckEnabled;
    @Value("${app.cac.crl-path:}")
    private String crlPath;

    private volatile KeyStore cachedTrustStore;
    private volatile X509CRL cachedCrl;
    private volatile long cachedCrlLastModified = -1L;

    private final CacCertificateParser certificateParser;
    private final AuditService auditService;
    private final ClientIpResolver clientIpResolver;

    public CacAuthFilter(CacCertificateParser certificateParser, AuditService auditService, ClientIpResolver clientIpResolver) {
        this.certificateParser = certificateParser;
        this.auditService = auditService;
        this.clientIpResolver = clientIpResolver;
        log.info(">>> CAC Authentication Filter initialized <<<");
        log.info(">>> Government edition - X.509 client certificate validation enabled <<<");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        X509Certificate[] certs = (X509Certificate[]) request.getAttribute(CERT_ATTRIBUTE);
        X509Certificate clientCert = (certs != null && certs.length > 0) ? certs[0] : null;

        if (clientCert != null) {
            // Validate basic timing and certificate intent (fail closed for invalid client certificates).
            if (!validateClientCertificate(clientCert, certs)) {
                auditService.logAuthFailure("CAC_INVALID", "Certificate invalid", request);
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Certificate invalid\"}");
                return;
            }

            if (!certificateParser.isValid(clientCert)) {
                log.warn("Invalid or expired client certificate from {}", request.getRemoteAddr());
                auditService.logAuthFailure("CAC_INVALID", "Certificate expired or invalid", request);
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Certificate invalid or expired\"}");
                return;
            }

            if (!certificateParser.isCacOrPiv(clientCert)) {
                log.warn("Non-CAC/PIV certificate presented from {}", request.getRemoteAddr());
                auditService.logAuthFailure("CAC_INVALID", "Certificate is not CAC/PIV", request);
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"CAC or PIV certificate required\"}");
                return;
            }

            CacIdentity identity = certificateParser.parse(clientCert);
            if (identity != null) {
                request.setAttribute(CAC_IDENTITY_ATTRIBUTE, identity);
                request.setAttribute(CAC_CERTIFICATE_ATTRIBUTE, clientCert);
                log.debug("CAC identity extracted: EDIPI={}, Name={}",
                         identity.edipi(), identity.toDisplayName());
            }
        } else {
            // Check for certificate passed via header (reverse proxy scenario)
            String certHeader = request.getHeader("X-Client-Cert");
            String certDnHeader = request.getHeader("X-Client-Cert-DN");

            boolean trustedProxy = clientIpResolver.isTrustedProxy(request.getRemoteAddr());
            String dnValue = null;
            if (trustedProxy) {
                if (certDnHeader != null && !certDnHeader.isBlank()) {
                    dnValue = certDnHeader.trim();
                } else if (certHeader != null && !certHeader.isBlank()) {
                    // Tests and some reverse proxies URL-encode the DN value.
                    try {
                        dnValue = URLDecoder.decode(certHeader, StandardCharsets.UTF_8);
                    } catch (IllegalArgumentException e) {
                        // Fail closed for malformed encodings but don't 500 the request pipeline.
                        log.warn("Malformed X-Client-Cert header encoding from trusted proxy {}", request.getRemoteAddr());
                        dnValue = null;
                    }
                }
            }

            if (trustedProxy && dnValue != null && !dnValue.isBlank()) {
                // Require a CN to be present to reduce ambiguity/spoofing in header-based identity.
                if (certificateParser.extractCommonName(dnValue) == null) {
                    log.warn("CAC header DN missing CN attribute from {}", request.getRemoteAddr());
                    filterChain.doFilter(request, response);
                    return;
                }

                CacIdentity identity = certificateParser.parseDn(dnValue);
                if (identity != null) {
                    request.setAttribute(CAC_IDENTITY_ATTRIBUTE, identity);
                    log.debug("CAC identity from header: EDIPI={}, Name={}",
                             identity.edipi(), identity.toDisplayName());
                }
            } else if ((certHeader != null && !certHeader.isEmpty()) || (certDnHeader != null && !certDnHeader.isEmpty())) {
                log.warn("Ignoring CAC headers from untrusted proxy: {}", request.getRemoteAddr());
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean validateClientCertificate(X509Certificate leaf, X509Certificate[] chain) {
        try {
            leaf.checkValidity(new Date());
        } catch (Exception e) {
            log.warn("Rejected invalid CAC certificate: {}", e.getMessage());
            return false;
        }

        if (!hasRequiredKeyUsage(leaf)) {
            log.warn("Rejected CAC certificate without required key usage");
            return false;
        }

        if (!hasClientAuthEku(leaf)) {
            log.warn("Rejected CAC certificate without clientAuth EKU");
            return false;
        }

        if (this.chainValidationEnabled) {
            // Only run PKIX validation when we can load a trust store. Mutual TLS already validates
            // trust at the transport layer; this is defense-in-depth and a safety net for misconfig.
            KeyStore trustStore = loadTrustStore();
            if (trustStore == null) {
                if (this.chainValidationRequireTrustStore) {
                    log.warn("CAC chain validation enabled but no trust store is configured/loaded");
                    return false;
                }
            } else if (chain != null && chain.length > 0) {
                if (!validateChainPkix(chain, trustStore)) {
                    return false;
                }
            }
        }

        if (this.crlCheckEnabled) {
            if (!checkRevocationCrl(leaf)) {
                return false;
            }
        }

        return true;
    }

    private boolean hasRequiredKeyUsage(X509Certificate cert) {
        boolean[] keyUsage = cert.getKeyUsage();
        // If keyUsage is not present, treat as unrestricted.
        if (keyUsage == null || keyUsage.length == 0) {
            return true;
        }
        // digitalSignature (bit 0) is commonly required for TLS client auth.
        return keyUsage[0];
    }

    private boolean hasClientAuthEku(X509Certificate cert) {
        try {
            List<String> eku = cert.getExtendedKeyUsage();
            // If EKU is not present, treat as unrestricted (some environments omit EKU).
            if (eku == null || eku.isEmpty()) {
                return true;
            }
            return eku.contains(CLIENT_AUTH_OID);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean validateChainPkix(X509Certificate[] chain, KeyStore trustStore) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            CertPath certPath = cf.generateCertPath(Arrays.asList(chain));
            PKIXParameters params = new PKIXParameters(trustStore);
            // Revocation checks are handled separately for air-gapped deployments.
            params.setRevocationEnabled(false);
            CertPathValidator validator = CertPathValidator.getInstance("PKIX");
            validator.validate(certPath, params);
            return true;
        } catch (Exception e) {
            log.warn("CAC certificate chain validation failed: {}", e.getMessage());
            return false;
        }
    }

    private KeyStore loadTrustStore() {
        if (this.cachedTrustStore != null) {
            return this.cachedTrustStore;
        }

        String path = null;
        if (this.trustStorePath != null && !this.trustStorePath.isBlank()) {
            path = this.trustStorePath.trim();
        } else if (this.serverSslTrustStore != null && !this.serverSslTrustStore.isBlank()) {
            path = this.serverSslTrustStore.trim();
        } else {
            path = System.getProperty("javax.net.ssl.trustStore");
        }

        if (path == null || path.isBlank()) {
            return null;
        }

        try (FileInputStream in = new FileInputStream(path)) {
            String type;
            String lower = path.toLowerCase();
            if (lower.endsWith(".p12") || lower.endsWith(".pfx")) {
                type = "PKCS12";
            } else {
                type = "JKS";
            }
            KeyStore ks = KeyStore.getInstance(type);
            String passString = null;
            if (this.trustStorePassword != null && !this.trustStorePassword.isBlank()) {
                passString = this.trustStorePassword;
            } else if (this.serverSslTrustStorePassword != null && !this.serverSslTrustStorePassword.isBlank()) {
                passString = this.serverSslTrustStorePassword;
            }
            char[] pass = passString != null ? passString.toCharArray() : null;
            ks.load(in, pass);
            this.cachedTrustStore = ks;
            return ks;
        } catch (Exception e) {
            log.warn("Unable to load CAC trust store for chain validation: {}", e.getMessage());
            return null;
        }
    }

    private boolean checkRevocationCrl(X509Certificate cert) {
        if (this.crlPath == null || this.crlPath.isBlank()) {
            log.warn("CRL check enabled but no CRL path configured");
            return false;
        }
        try {
            Path path = Path.of(this.crlPath.trim());
            long mtime = Files.getLastModifiedTime(path).toMillis();
            if (this.cachedCrl == null || mtime != this.cachedCrlLastModified) {
                try (FileInputStream in = new FileInputStream(path.toFile())) {
                    CertificateFactory cf = CertificateFactory.getInstance("X.509");
                    this.cachedCrl = (X509CRL) cf.generateCRL(in);
                    this.cachedCrlLastModified = mtime;
                }
            }

            if (this.cachedCrl != null && this.cachedCrl.getRevokedCertificate(cert) != null) {
                log.error("SECURITY: CAC certificate is revoked (serial {})", cert.getSerialNumber());
                return false;
            }
            return true;
        } catch (Exception e) {
            // Fail closed when CRL checking is explicitly enabled.
            log.error("CRL check failed - rejecting certificate: {}", e.getMessage());
            return false;
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Skip filter for health checks and static resources
        return path.equals("/api/health") ||
               path.equals("/api/status") ||
               path.startsWith("/css/") ||
               path.startsWith("/js/") ||
               path.equals("/favicon.ico");
    }
}
