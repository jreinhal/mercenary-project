package com.jreinhal.mercenary.config;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.model.ClearanceLevel;
import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.UserRole;
import com.jreinhal.mercenary.repository.UserRepository;
import java.time.Instant;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class DataInitializer {
    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);
    @Value(value="${sentinel.bootstrap.admin-password:}")
    private String adminPassword;
    @Value(value="${sentinel.bootstrap.enabled:false}")
    private boolean bootstrapEnabled;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CommandLineRunner initDatabase(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        return args -> {
            if (!this.bootstrapEnabled) {
                log.info("Database bootstrap disabled via configuration");
                return;
            }
            if (this.adminPassword == null || this.adminPassword.isBlank()) {
                log.error("==========================================================");
                log.error("  SECURITY ERROR: Bootstrap enabled but no password set!");
                log.error("  Set SENTINEL_ADMIN_PASSWORD environment variable.");
                log.error("  Bootstrap aborted for security.");
                log.error("==========================================================");
                throw new IllegalStateException("SENTINEL_ADMIN_PASSWORD environment variable is REQUIRED when bootstrap is enabled");
            }
            if (userRepository.count() == 0L) {
                log.warn("==========================================================");
                log.warn(">>> EMPTY DATABASE DETECTED <<<");
                log.warn(">>> Initializing 'Recovery Admin' User <<<");
                log.warn("==========================================================");
                User admin = new User();
                admin.setUsername("admin");
                admin.setDisplayName("Recovery Administrator");
                admin.setEmail("admin@sentinel.local");
                admin.setPasswordHash(passwordEncoder.encode((CharSequence)this.adminPassword));
                admin.setRoles(Set.of(UserRole.ADMIN));
                admin.setClearance(ClearanceLevel.TOP_SECRET);
                admin.setAllowedSectors(Set.of(Department.values()));
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
