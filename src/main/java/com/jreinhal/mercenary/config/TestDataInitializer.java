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
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Test data initializer that creates users with different clearance levels
 * and roles for testing authorization scenarios.
 *
 * Only active when 'test-users' profile is enabled.
 */
@Configuration
@Profile("test-users")
public class TestDataInitializer {
    private static final Logger log = LoggerFactory.getLogger(TestDataInitializer.class);

    // Test password for all test users (only for testing!)
    private static final String TEST_PASSWORD = "TestPass123!";

    @Bean
    public CommandLineRunner initTestUsers(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        return args -> {
            log.warn("==========================================================");
            log.warn(">>> TEST USERS PROFILE ACTIVE <<<");
            log.warn(">>> Creating test users for authorization testing <<<");
            log.warn("==========================================================");

            // 1. Unclassified user - can only access ENTERPRISE
            createTestUser(userRepository, passwordEncoder,
                "viewer_unclass",
                "Unclassified Viewer",
                ClearanceLevel.UNCLASSIFIED,
                Set.of(UserRole.VIEWER),
                Set.of(Department.ENTERPRISE)
            );

            // 2. CUI user - can access ENTERPRISE
            createTestUser(userRepository, passwordEncoder,
                "analyst_cui",
                "CUI Analyst",
                ClearanceLevel.CUI,
                Set.of(UserRole.ANALYST),
                Set.of(Department.ENTERPRISE)
            );

            // 3. SECRET user - can access MEDICAL and GOVERNMENT
            createTestUser(userRepository, passwordEncoder,
                "analyst_secret",
                "Secret Analyst",
                ClearanceLevel.SECRET,
                Set.of(UserRole.ANALYST),
                Set.of(Department.values())
            );

            // 4. Auditor with UNCLASSIFIED - limited sector access
            createTestUser(userRepository, passwordEncoder,
                "auditor_unclass",
                "Unclassified Auditor",
                ClearanceLevel.UNCLASSIFIED,
                Set.of(UserRole.AUDITOR),
                Set.of(Department.ENTERPRISE)
            );

            log.info("==========================================================");
            log.info("  TEST USERS CREATED");
            log.info("  Password for all test users: {}", TEST_PASSWORD);
            log.info("  ");
            log.info("  Available test accounts:");
            log.info("  - viewer_unclass  (UNCLASSIFIED, VIEWER)");
            log.info("  - analyst_cui     (CUI, ANALYST)");
            log.info("  - analyst_secret  (SECRET, ANALYST)");
            log.info("  - auditor_unclass (UNCLASSIFIED, AUDITOR)");
            log.info("==========================================================");
        };
    }

    private void createTestUser(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            String username,
            String displayName,
            ClearanceLevel clearance,
            Set<UserRole> roles,
            Set<Department> allowedSectors) {

        if (userRepository.findByUsername(username).isPresent()) {
            log.info("Test user '{}' already exists, skipping", username);
            return;
        }

        User user = new User();
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setEmail(username + "@test.sentinel.local");
        user.setPasswordHash(passwordEncoder.encode(TEST_PASSWORD));
        user.setRoles(roles);
        user.setClearance(clearance);
        user.setAllowedSectors(allowedSectors);
        user.setAuthProvider(User.AuthProvider.LOCAL);
        user.setCreatedAt(Instant.now());
        user.setActive(true);
        user.setPendingApproval(false);

        userRepository.save(user);
        log.info("Created test user: {} ({}, {})", username, clearance, roles);
    }
}
