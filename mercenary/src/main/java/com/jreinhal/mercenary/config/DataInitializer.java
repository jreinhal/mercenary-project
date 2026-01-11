package com.jreinhal.mercenary.config;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.model.ClearanceLevel;
import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.UserRole;
import com.jreinhal.mercenary.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Set;

/**
 * Database initialization for first-time deployment.
 *
 * Creates a "Recovery Admin" user if the database is empty,
 * ensuring the system is never locked out on fresh deployment.
 *
 * SECURITY NOTE: The default password MUST be changed immediately
 * after first login. This is logged as a warning on startup.
 */
@Configuration
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    /**
     * Default admin password for fresh deployments.
     * Can be overridden via environment variable for automated deployments.
     */
    @Value("${sentinel.bootstrap.admin-password:}")
    private String adminPassword;

    @Value("${sentinel.bootstrap.enabled:false}")
    private boolean bootstrapEnabled;

    /**
     * BCrypt password encoder bean for the application.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Bootstrap the database on first run.
     * Creates a default admin user if no users exist.
     */
    @Bean
    public CommandLineRunner initDatabase(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        return args -> {
            if (!bootstrapEnabled) {
                log.info("Database bootstrap disabled via configuration");
                return;
            }

            // SECURITY: Require explicit password when bootstrap is enabled
            if (adminPassword == null || adminPassword.isBlank()) {
                log.error("==========================================================");
                log.error("  SECURITY ERROR: Bootstrap enabled but no password set!");
                log.error("  Set SENTINEL_ADMIN_PASSWORD environment variable.");
                log.error("  Bootstrap aborted for security.");
                log.error("==========================================================");
                throw new IllegalStateException(
                    "SENTINEL_ADMIN_PASSWORD environment variable is REQUIRED when bootstrap is enabled"
                );
            }

            if (userRepository.count() == 0) {
                log.warn("==========================================================");
                log.warn(">>> EMPTY DATABASE DETECTED <<<");
                log.warn(">>> Initializing 'Recovery Admin' User <<<");
                log.warn("==========================================================");

                User admin = new User();
                admin.setUsername("admin");
                admin.setDisplayName("Recovery Administrator");
                admin.setEmail("admin@sentinel.local");

                // Hash the password using BCrypt
                admin.setPasswordHash(passwordEncoder.encode(adminPassword));

                // Full admin privileges
                admin.setRoles(Set.of(UserRole.ADMIN));
                admin.setClearance(ClearanceLevel.TOP_SECRET);
                admin.setAllowedSectors(Set.of(Department.values()));

                // Mark as local/standard auth
                admin.setAuthProvider(User.AuthProvider.LOCAL);
                admin.setCreatedAt(Instant.now());
                admin.setActive(true);

                userRepository.save(admin);

                log.info("==========================================================");
                log.info("  DEFAULT ADMIN USER CREATED");
                log.info("  Username: admin");
                log.info("  Password: [SET VIA SENTINEL_ADMIN_PASSWORD ENV VAR]");
                log.info("==========================================================");
                log.warn("  !!! SECURITY WARNING !!!");
                log.warn("  Change this password IMMEDIATELY after first login.");
                log.warn("  Set SENTINEL_ADMIN_PASSWORD environment variable for");
                log.warn("  automated deployments. Default password is insecure.");
                log.info("==========================================================");
            } else {
                log.info("Database already initialized ({} users found)", userRepository.count());
            }
        };
    }
}
