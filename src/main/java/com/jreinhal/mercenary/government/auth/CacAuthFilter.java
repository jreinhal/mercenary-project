package com.jreinhal.mercenary.government.auth;

import com.jreinhal.mercenary.government.auth.CacCertificateParser.CacIdentity;
import com.jreinhal.mercenary.service.AuditService;
import com.jreinhal.mercenary.security.ClientIpResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.cert.X509Certificate;

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

    private static final String CERT_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";
    private static final String CAC_IDENTITY_ATTRIBUTE = "cac.identity";
    private static final String CAC_CERTIFICATE_ATTRIBUTE = "cac.certificate";

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

        X509Certificate clientCert = extractClientCertificate(request);

        if (clientCert != null) {
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
            if (trustedProxy && certDnHeader != null && !certDnHeader.isEmpty()) {
                CacIdentity identity = certificateParser.parseDn(certDnHeader);
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

    private X509Certificate extractClientCertificate(HttpServletRequest request) {
        X509Certificate[] certs = (X509Certificate[]) request.getAttribute(CERT_ATTRIBUTE);
        if (certs != null && certs.length > 0) {
            return certs[0];
        }
        return null;
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
