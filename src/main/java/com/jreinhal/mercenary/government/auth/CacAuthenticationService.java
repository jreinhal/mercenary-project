package com.jreinhal.mercenary.government.auth;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.model.ClearanceLevel;
import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.UserRole;
import com.jreinhal.mercenary.repository.UserRepository;
import com.jreinhal.mercenary.security.ClientIpResolver;
import com.jreinhal.mercenary.service.AuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name={"app.auth-mode"}, havingValue="CAC")
public class CacAuthenticationService
implements AuthenticationService {
    private static final Logger log = LoggerFactory.getLogger(CacAuthenticationService.class);
    private final UserRepository userRepository;
    private final ClientIpResolver clientIpResolver;
    private final CacCertificateParser certificateParser;
    @Value("${app.cac.auto-provision:false}")
    private boolean autoProvision;
    @Value("${app.cac.require-approval:true}")
    private boolean requireApproval;

    public CacAuthenticationService(UserRepository userRepository, ClientIpResolver clientIpResolver, CacCertificateParser certificateParser) {
        this.userRepository = userRepository;
        this.clientIpResolver = clientIpResolver;
        this.certificateParser = certificateParser;
        log.info(">>> CAC/PIV AUTHENTICATION MODE ACTIVE <<<");
        log.info(">>> Ensure mutual TLS is configured on the server <<<");
    }

    @Override
    public User authenticate(HttpServletRequest request) {
        X509Certificate[] certs = (X509Certificate[])request.getAttribute("jakarta.servlet.request.X509Certificate");
        String certHeader = request.getHeader("X-Client-Cert");
        String subjectDn = null;
        if (certs != null && certs.length > 0) {
            try {
                // Defense-in-depth: validate certificate timing even if TLS stack validates it.
                certs[0].checkValidity();
            } catch (Exception e) {
                log.warn("Rejected invalid CAC certificate: {}", e.getMessage());
                return null;
            }
            subjectDn = certs[0].getSubjectX500Principal().getName("RFC2253");
        } else if (certHeader != null && !certHeader.isEmpty()) {
            boolean trustedProxy = this.clientIpResolver.isTrustedProxy(request.getRemoteAddr());
            if (trustedProxy) {
                subjectDn = this.extractDnFromHeader(certHeader);
            } else {
                log.warn("Ignoring X-Client-Cert from untrusted proxy: {}", request.getRemoteAddr());
            }
        }
        if (subjectDn == null) {
            log.warn("No client certificate provided");
            return null;
        }
        String commonName = this.certificateParser.extractCommonName(subjectDn);
        if (commonName == null || commonName.isBlank()) {
            log.warn("No CN in certificate DN");
            return null;
        }
        User user = this.userRepository.findByExternalId(subjectDn).orElse(null);
        if (user == null) {
            if (!this.autoProvision) {
                log.warn("CAC user {} not found and auto-provisioning disabled", commonName);
                return null;
            }
            user = new User();
            user.setExternalId(subjectDn);
            user.setUsername(commonName);
            user.setDisplayName(commonName);
            user.setAuthProvider(User.AuthProvider.CAC);
            user.setRoles(Set.of(UserRole.VIEWER));
            user.setClearance(ClearanceLevel.UNCLASSIFIED);
            user.setAllowedSectors(Set.of(Department.GOVERNMENT));
            user.setCreatedAt(Instant.now());
            if (this.requireApproval) {
                user.setActive(false);
                user.setPendingApproval(true);
                user = (User)this.userRepository.save(user);
                log.info("Auto-provisioned new CAC user pending approval: {}", commonName);
                return null;
            }
            user.setActive(true);
            user.setPendingApproval(false);
            user = (User)this.userRepository.save(user);
            log.info("Auto-provisioned new CAC user: {}", commonName);
        }
        if (user.isPendingApproval()) {
            log.warn("CAC user {} is pending approval", commonName);
            return null;
        }
        if (!user.isActive()) {
            log.warn("CAC user {} is deactivated", commonName);
            return null;
        }
        user.setLastLoginAt(Instant.now());
        this.userRepository.save(user);
        return user;
    }

    private String extractDnFromHeader(String certHeader) {
        try {
            return URLDecoder.decode(certHeader, "UTF-8");
        }
        catch (Exception e) {
            return certHeader;
        }
    }

    @Override
    public String getAuthMode() {
        return "CAC";
    }
}
