package com.jreinhal.mercenary.security;

import com.jreinhal.mercenary.model.ClearanceLevel;
import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.UserRole;
import com.jreinhal.mercenary.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Spring Security UserDetailsService for CAC/PIV X.509 certificate authentication.
 *
 * This service:
 * 1. Extracts identity from X.509 certificate (via CacCertificateParser)
 * 2. Looks up or creates user in MongoDB
 * 3. Returns UserDetails for Spring Security
 *
 * For GovCloud/DoD environments where users authenticate via smart card.
 */
@Service
public class CacUserDetailsService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(CacUserDetailsService.class);

    private final UserRepository userRepository;
    private final CacCertificateParser certParser;

    @Value("${app.cac.auto-provision:true}")
    private boolean autoProvision;

    @Value("${app.cac.default-role:ANALYST}")
    private String defaultRole;

    @Value("${app.cac.default-clearance:SECRET}")
    private String defaultClearance;

    public CacUserDetailsService(UserRepository userRepository, CacCertificateParser certParser) {
        this.userRepository = userRepository;
        this.certParser = certParser;
        log.info("CAC UserDetailsService initialized (autoProvision={}, defaultRole={}, defaultClearance={})",
                autoProvision, defaultRole, defaultClearance);
    }

    /**
     * Load user by username (EDIPI or CN).
     * Called by Spring Security after X.509 extraction.
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        log.debug("Loading user by username: {}", username);

        Optional<User> userOpt = userRepository.findByUsername(username);

        if (userOpt.isEmpty()) {
            // Try by external ID (EDIPI)
            userOpt = userRepository.findByExternalId(username);
        }

        if (userOpt.isEmpty()) {
            throw new UsernameNotFoundException("User not found: " + username);
        }

        User user = userOpt.get();

        // Update last login
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        log.info("CAC user authenticated: {} ({})", user.getUsername(), user.getDisplayName());

        return toUserDetails(user);
    }

    /**
     * Authenticate using X.509 certificate.
     * This is the primary entry point for CAC authentication.
     *
     * @param certificate The client certificate from the TLS handshake
     * @return Authenticated User, or null if authentication fails
     */
    public User authenticateWithCertificate(X509Certificate certificate) {
        if (certificate == null) {
            log.warn("No certificate provided");
            return null;
        }

        // Validate certificate
        if (!certParser.isValid(certificate)) {
            log.warn("Certificate is expired or not yet valid");
            return null;
        }

        // Parse identity
        CacCertificateParser.CacIdentity identity = certParser.parse(certificate);
        if (identity == null) {
            log.warn("Failed to parse certificate identity");
            return null;
        }

        String username = identity.toUsername();
        log.debug("Certificate identity: username={}, displayName={}",
                username, identity.toDisplayName());

        // Look up user
        Optional<User> userOpt = userRepository.findByUsername(username);

        if (userOpt.isEmpty() && identity.edipi() != null) {
            userOpt = userRepository.findByExternalId(identity.edipi());
        }

        if (userOpt.isPresent()) {
            User user = userOpt.get();
            user.setLastLoginAt(Instant.now());
            userRepository.save(user);
            log.info("Existing CAC user authenticated: {}", user.getUsername());
            return user;
        }

        // Auto-provision new user if enabled
        if (autoProvision) {
            User newUser = provisionUser(identity, certificate);
            log.info("Auto-provisioned new CAC user: {}", newUser.getUsername());
            return newUser;
        }

        log.warn("User not found and auto-provision disabled: {}", username);
        return null;
    }

    /**
     * Auto-provision a new user from certificate identity.
     */
    private User provisionUser(CacCertificateParser.CacIdentity identity, X509Certificate certificate) {
        User user = new User();
        user.setUsername(identity.toUsername());
        user.setDisplayName(identity.toDisplayName());
        user.setExternalId(identity.edipi());
        user.setEmail(identity.email());
        user.setAuthProvider(User.AuthProvider.CAC);

        // Set default role
        try {
            user.setRoles(Set.of(UserRole.valueOf(defaultRole)));
        } catch (IllegalArgumentException e) {
            user.setRoles(Set.of(UserRole.ANALYST));
        }

        // Set default clearance
        try {
            user.setClearance(ClearanceLevel.valueOf(defaultClearance));
        } catch (IllegalArgumentException e) {
            user.setClearance(ClearanceLevel.SECRET);
        }

        user.setActive(true);
        user.setCreatedAt(Instant.now());
        user.setLastLoginAt(Instant.now());

        // Note: Certificate metadata could be stored in a separate CertificateRecord collection
        // For now, we log the cert info for audit purposes
        log.info("Provisioning CAC user from cert: issuer={}, serial={}, expires={}",
                certificate.getIssuerX500Principal().getName(),
                certificate.getSerialNumber(),
                certificate.getNotAfter());

        return userRepository.save(user);
    }

    /**
     * Convert User to Spring Security UserDetails.
     */
    private UserDetails toUserDetails(User user) {
        // Convert roles to authority strings
        String[] authorities = user.getRoles().stream()
                .map(UserRole::name)
                .toArray(String[]::new);

        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getUsername())
                .password("") // No password for cert auth
                .authorities(authorities.length > 0 ? authorities : new String[]{"ANALYST"})
                .accountExpired(false)
                .accountLocked(!user.isActive())
                .credentialsExpired(false)
                .disabled(!user.isActive())
                .build();
    }

    /**
     * Extract username from certificate for Spring Security X.509 config.
     * Used as the principalExtractor in Spring Security.
     */
    public String extractUsername(X509Certificate certificate) {
        CacCertificateParser.CacIdentity identity = certParser.parse(certificate);
        if (identity != null) {
            return identity.toUsername();
        }
        // Fallback to CN
        String cn = certificate.getSubjectX500Principal().getName();
        return cn;
    }
}
