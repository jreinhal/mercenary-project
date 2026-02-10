package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.model.ClearanceLevel;
import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.UserRole;
import com.jreinhal.mercenary.repository.UserRepository;
import com.jreinhal.mercenary.security.JwtValidator;
import com.jreinhal.mercenary.service.AuthenticationService;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name={"app.auth-mode"}, havingValue="OIDC")
public class OidcAuthenticationService
implements AuthenticationService {
    private static final Logger log = LoggerFactory.getLogger(OidcAuthenticationService.class);
    private final UserRepository userRepository;
    private final JwtValidator jwtValidator;
    private final HipaaPolicy hipaaPolicy;
    @Value(value="${app.oidc.issuer:}")
    private String issuer;
    @Value(value="${app.oidc.client-id:}")
    private String clientId;
    @Value(value="${app.oidc.auto-provision:true}")
    private boolean autoProvision;
    @Value(value="${app.oidc.require-approval:false}")
    private boolean requireApproval;
    @Value(value="${app.oidc.default-role:VIEWER}")
    private String defaultRole;
    @Value(value="${app.oidc.default-clearance:UNCLASSIFIED}")
    private String defaultClearance;
    @Value(value="${app.oidc.max-default-clearance:CUI}")
    private String maxDefaultClearance;
    @Value(value="${app.oidc.max-default-role:ANALYST}")
    private String maxDefaultRole;

    public OidcAuthenticationService(UserRepository userRepository, JwtValidator jwtValidator, HipaaPolicy hipaaPolicy) {
        this.userRepository = userRepository;
        this.jwtValidator = jwtValidator;
        this.hipaaPolicy = hipaaPolicy;
        log.info("==========================================================");
        log.info(">>> OIDC AUTHENTICATION MODE ACTIVE <<<");
        log.info(">>> JWT signature validation: ENABLED                  <<<");
        log.info("==========================================================");
    }

    @PostConstruct
    public void validateConfiguration() {
        boolean hasErrors = false;
        if (this.issuer == null || this.issuer.isBlank()) {
            log.error("OIDC Configuration Error: 'app.oidc.issuer' is not set!");
            log.error("  Set OIDC_ISSUER environment variable or configure in application.yaml");
            hasErrors = true;
        }
        if (this.clientId == null || this.clientId.isBlank()) {
            log.error("OIDC Configuration Error: 'app.oidc.client-id' is not set!");
            log.error("  Set OIDC_CLIENT_ID environment variable or configure in application.yaml");
            hasErrors = true;
        }
        if (hasErrors) {
            log.error("==========================================================");
            log.error("  OIDC MODE ACTIVE BUT CONFIGURATION INCOMPLETE!");
            log.error("  Authentication will fail until configuration is fixed.");
            log.error("==========================================================");
        } else {
            log.info("OIDC Configuration validated:");
            log.info("  Issuer: {}", this.issuer);
            log.info("  Client ID: {}", this.clientId);
            log.info("  Auto-provision: {}", this.autoProvision);
            log.info("  Require approval: {}", this.requireApproval);
        }
        this.validateDefaultPermissions();
        this.enforceHipaaDefaults();
    }

    private void enforceHipaaDefaults() {
        if (!this.hipaaPolicy.isStrict(Department.MEDICAL)) {
            return;
        }
        if (this.autoProvision && !this.hipaaPolicy.shouldAllowOidcAutoProvision()) {
            log.warn("HIPAA strict: disabling OIDC auto-provisioning.");
            this.autoProvision = false;
        }
        if (!this.requireApproval && this.hipaaPolicy.shouldRequireOidcApproval()) {
            log.warn("HIPAA strict: enabling OIDC require-approval.");
            this.requireApproval = true;
        }
    }

    private void validateDefaultPermissions() {
        try {
            UserRole configuredRole = UserRole.valueOf(this.defaultRole.toUpperCase());
            UserRole maxRole = UserRole.valueOf(this.maxDefaultRole.toUpperCase());
            if (configuredRole == UserRole.ADMIN) {
                log.error("==========================================================");
                log.error("  SECURITY ERROR: Cannot auto-provision ADMIN role!");
                log.error("  Change app.oidc.default-role to VIEWER or ANALYST");
                log.error("==========================================================");
                this.defaultRole = "VIEWER";
            }
        }
        catch (IllegalArgumentException e) {
            log.warn("Invalid default-role '{}', falling back to VIEWER", this.defaultRole);
            this.defaultRole = "VIEWER";
        }
        try {
            ClearanceLevel configuredClearance = ClearanceLevel.valueOf(this.defaultClearance.toUpperCase());
            ClearanceLevel maxClearance = ClearanceLevel.valueOf(this.maxDefaultClearance.toUpperCase());
            if (configuredClearance.ordinal() > maxClearance.ordinal()) {
                log.warn("SECURITY: default-clearance {} exceeds max-default-clearance {}, capping", configuredClearance, maxClearance);
                this.defaultClearance = this.maxDefaultClearance;
            }
            if (configuredClearance == ClearanceLevel.TOP_SECRET || configuredClearance == ClearanceLevel.SCI) {
                log.error("==========================================================");
                log.error("  SECURITY ERROR: Cannot auto-provision {} clearance!", configuredClearance);
                log.error("  Change app.oidc.default-clearance to UNCLASSIFIED, CUI, or SECRET");
                log.error("==========================================================");
                this.defaultClearance = "UNCLASSIFIED";
            }
        }
        catch (IllegalArgumentException e) {
            log.warn("Invalid default-clearance '{}', falling back to UNCLASSIFIED", this.defaultClearance);
            this.defaultClearance = "UNCLASSIFIED";
        }
    }

    private static final String SESSION_USER_ID = "mercenary.auth.userId";

    @Override
    public User authenticate(HttpServletRequest request) {
        // Check session first (for browser-based OIDC flow)
        User sessionUser = authenticateSession(request);
        if (sessionUser != null) {
            return sessionUser;
        }

        // Fall back to Bearer token (for API clients)
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.debug("No Bearer token or session in request");
            return null;
        }
        String token = authHeader.substring(7);
        JwtValidator.ValidationResult result = this.jwtValidator.validate(token);
        if (!result.isValid()) {
            log.warn("JWT validation failed: {}", result.getError());
            return null;
        }
        try {
            String subject = result.getSubject();
            String email = result.getStringClaim("email");
            Object name = result.getStringClaim("name");
            if (name == null) {
                name = result.getStringClaim("preferred_username");
            }
            if (name == null) {
                name = result.getStringClaim("given_name");
                String familyName = result.getStringClaim("family_name");
                if (familyName != null) {
                    name = (String)(name != null ? (String)name + " " : "") + familyName;
                }
            }
            if (subject == null || subject.isEmpty()) {
                log.warn("No subject in validated JWT");
                return null;
            }
            log.debug("JWT validated for subject: {}, email: {}", subject, email);
            User user = this.userRepository.findByExternalId(subject).orElse(null);
            if (user == null) {
                ClearanceLevel clearance;
                UserRole role;
                if (!this.autoProvision) {
                    log.warn("User '{}' not found and auto-provisioning disabled", subject);
                    return null;
                }
                user = new User();
                user.setExternalId(subject);
                user.setUsername(email != null ? email : subject);
                user.setDisplayName((String)(name != null ? name : subject));
                user.setEmail(email);
                user.setAuthProvider(User.AuthProvider.OIDC);
                try {
                    role = UserRole.valueOf(this.defaultRole.toUpperCase());
                }
                catch (IllegalArgumentException e) {
                    role = UserRole.VIEWER;
                }
                user.setRoles(Set.of(role));
                try {
                    clearance = ClearanceLevel.valueOf(this.defaultClearance.toUpperCase());
                }
                catch (IllegalArgumentException e) {
                    clearance = ClearanceLevel.UNCLASSIFIED;
                }
                user.setClearance(clearance);
                user.setAllowedSectors(Set.of(Department.ENTERPRISE));
                user.setCreatedAt(Instant.now());
                if (this.requireApproval) {
                    user.setActive(false);
                    user.setPendingApproval(true);
                    user = (User)this.userRepository.save(user);
                    log.info("New OIDC user '{}' created PENDING APPROVAL (role: {}, clearance: {})", new Object[]{user.getUsername(), role, clearance});
                    return null;
                }
                user.setActive(true);
                user = (User)this.userRepository.save(user);
                log.info("Auto-provisioned new OIDC user: {} (role: {}, clearance: {})", new Object[]{user.getUsername(), role, clearance});
            }
            if (user.isPendingApproval()) {
                log.warn("OIDC user '{}' is pending admin approval", user.getUsername());
                return null;
            }
            if (!user.isActive()) {
                log.warn("OIDC user '{}' is deactivated", user.getUsername());
                return null;
            }
            user.setLastLoginAt(Instant.now());
            this.userRepository.save(user);
            log.debug("OIDC user '{}' authenticated successfully", user.getUsername());
            return user;
        }
        catch (Exception e) {
            log.error("OIDC authentication failed: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Authenticate via session cookie (for browser-based OIDC flow).
     * The OIDC callback stores the user ID in the session after a successful
     * Authorization Code + PKCE exchange.
     */
    private User authenticateSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object userIdObj = session.getAttribute(SESSION_USER_ID);
        if (!(userIdObj instanceof String userId) || userId.isBlank()) {
            return null;
        }
        Optional<User> userOpt = this.userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            session.removeAttribute(SESSION_USER_ID);
            return null;
        }
        User user = userOpt.get();
        if (!user.isActive() || user.isPendingApproval()) {
            return null;
        }
        return user;
    }

    @Override
    public String getAuthMode() {
        return "OIDC";
    }
}
