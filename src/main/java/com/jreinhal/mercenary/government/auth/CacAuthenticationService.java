package com.jreinhal.mercenary.government.auth;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.model.ClearanceLevel;
import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.UserRole;
import com.jreinhal.mercenary.repository.UserRepository;
import com.jreinhal.mercenary.service.AuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name={"app.auth-mode"}, havingValue="CAC")
public class CacAuthenticationService
implements AuthenticationService {
    private static final Logger log = LoggerFactory.getLogger(CacAuthenticationService.class);
    private final UserRepository userRepository;

    public CacAuthenticationService(UserRepository userRepository) {
        this.userRepository = userRepository;
        log.info(">>> CAC/PIV AUTHENTICATION MODE ACTIVE <<<");
        log.info(">>> Ensure mutual TLS is configured on the server <<<");
    }

    @Override
    public User authenticate(HttpServletRequest request) {
        X509Certificate[] certs = (X509Certificate[])request.getAttribute("jakarta.servlet.request.X509Certificate");
        String certHeader = request.getHeader("X-Client-Cert");
        String subjectDn = null;
        if (certs != null && certs.length > 0) {
            subjectDn = certs[0].getSubjectX500Principal().getName();
            log.debug("Client certificate DN: {}", subjectDn);
        } else if (certHeader != null && !certHeader.isEmpty()) {
            subjectDn = this.extractDnFromHeader(certHeader);
        }
        if (subjectDn == null) {
            log.warn("No client certificate provided");
            return null;
        }
        String commonName = this.extractCn(subjectDn);
        if (commonName == null) {
            log.warn("No CN in certificate DN: {}", subjectDn);
            return null;
        }
        User user = this.userRepository.findByExternalId(subjectDn).orElse(null);
        if (user == null) {
            user = new User();
            user.setExternalId(subjectDn);
            user.setUsername(commonName);
            user.setDisplayName(commonName);
            user.setAuthProvider(User.AuthProvider.CAC);
            user.setRoles(Set.of(UserRole.VIEWER));
            user.setClearance(ClearanceLevel.UNCLASSIFIED);
            user.setAllowedSectors(Set.of(Department.GOVERNMENT));
            user.setCreatedAt(Instant.now());
            user.setActive(true);
            user = (User)this.userRepository.save(user);
            log.info("Auto-provisioned new CAC user: {} (requires clearance verification)", commonName);
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

    private String extractCn(String dn) {
        for (String part : dn.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.startsWith("CN=") && !trimmed.startsWith("cn=")) continue;
            return trimmed.substring(3);
        }
        return null;
    }

    @Override
    public String getAuthMode() {
        return "CAC";
    }
}
