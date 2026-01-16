package com.jreinhal.mercenary.government.auth;

import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.User.AuthProvider;
import com.jreinhal.mercenary.model.UserRole;
import com.jreinhal.mercenary.model.ClearanceLevel;
import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.repository.UserRepository;
import com.jreinhal.mercenary.service.AuthenticationService;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.security.cert.X509Certificate;
import java.util.Set;
import java.time.Instant;

/**
 * CAC/PIV X.509 certificate authentication for government deployments.
 *
 * GOVERNMENT EDITION ONLY - This class is excluded from non-government builds.
 *
 * Validates client certificates and extracts user identity from the DN.
 *
 * Activated when: app.auth-mode=CAC
 *
 * Requires:
 * - TLS mutual authentication configured on the server/load balancer
 * - Client certificate passed via X-Client-Cert header or request attribute
 */
@Service
@ConditionalOnProperty(name = "app.auth-mode", havingValue = "CAC")
public class CacAuthenticationService implements AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(CacAuthenticationService.class);

    private final UserRepository userRepository;

    public CacAuthenticationService(UserRepository userRepository) {
        this.userRepository = userRepository;
        log.info(">>> CAC/PIV AUTHENTICATION MODE ACTIVE <<<");
        log.info(">>> Ensure mutual TLS is configured on the server <<<");
    }

    @Override
    public User authenticate(HttpServletRequest request) {
        // Try to get certificate from request attribute (standard Servlet)
        X509Certificate[] certs = (X509Certificate[]) request.getAttribute("jakarta.servlet.request.X509Certificate");

        // Fallback: Check for header from reverse proxy
        String certHeader = request.getHeader("X-Client-Cert");

        String subjectDn = null;

        if (certs != null && certs.length > 0) {
            subjectDn = certs[0].getSubjectX500Principal().getName();
            log.debug("Client certificate DN: {}", subjectDn);
        } else if (certHeader != null && !certHeader.isEmpty()) {
            // Certificate passed as header (URL-encoded PEM or DN)
            subjectDn = extractDnFromHeader(certHeader);
        }

        if (subjectDn == null) {
            log.warn("No client certificate provided");
            return null;
        }

        // Extract CN from DN for username
        String commonName = extractCn(subjectDn);
        if (commonName == null) {
            log.warn("No CN in certificate DN: {}", subjectDn);
            return null;
        }

        // Look up or create user by certificate DN
        User user = userRepository.findByExternalId(subjectDn).orElse(null);
        if (user == null) {
            // CAC users typically require pre-registration
            // For demo, auto-provision with restricted access
            user = new User();
            user.setExternalId(subjectDn);
            user.setUsername(commonName);
            user.setDisplayName(commonName);
            user.setAuthProvider(AuthProvider.CAC);
            user.setRoles(Set.of(UserRole.VIEWER)); // Require admin to upgrade
            user.setClearance(ClearanceLevel.UNCLASSIFIED); // Require verification
            user.setAllowedSectors(Set.of(Department.GOVERNMENT));
            user.setCreatedAt(Instant.now());
            user.setActive(true);
            user = userRepository.save(user);
            log.info("Auto-provisioned new CAC user: {} (requires clearance verification)", commonName);
        }

        // Verify user is active
        if (!user.isActive()) {
            log.warn("CAC user {} is deactivated", commonName);
            return null;
        }

        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        return user;
    }

    /**
     * Extract DN from proxy-forwarded certificate header.
     */
    private String extractDnFromHeader(String certHeader) {
        // Handle different formats:
        // 1. Direct DN: "CN=John Doe,OU=DoD,O=US Government"
        // 2. URL-encoded: CN%3DJohn%20Doe%2COU%3DDoD%2CO%3DUS%20Government
        try {
            return java.net.URLDecoder.decode(certHeader, "UTF-8");
        } catch (Exception e) {
            return certHeader;
        }
    }

    /**
     * Extract Common Name from X.500 DN.
     */
    private String extractCn(String dn) {
        // Parse DN format: CN=Name,OU=Org,...
        for (String part : dn.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("CN=") || trimmed.startsWith("cn=")) {
                return trimmed.substring(3);
            }
        }
        return null;
    }

    @Override
    public String getAuthMode() {
        return "CAC";
    }
}
