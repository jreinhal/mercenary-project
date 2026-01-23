package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.model.ClearanceLevel;
import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for sector isolation and clearance enforcement.
 * These tests verify that the system properly enforces:
 * - User can only access sectors they are assigned to
 * - User can only access data matching their clearance level
 * - Cross-sector data leakage is prevented
 */
class SectorIsolationTest {

    private User adminUser;
    private User viewerUser;
    private User governmentUser;
    private User medicalUser;

    @BeforeEach
    void setUp() {
        // Admin with full access
        adminUser = User.devUser("admin");
        adminUser.setRoles(Set.of(UserRole.ADMIN));
        adminUser.setClearance(ClearanceLevel.TOP_SECRET);
        adminUser.setAllowedSectors(Set.of(Department.values()));

        // Regular viewer with limited access
        viewerUser = new User();
        viewerUser.setId("viewer-1");
        viewerUser.setUsername("viewer");
        viewerUser.setRoles(Set.of(UserRole.VIEWER));
        viewerUser.setClearance(ClearanceLevel.UNCLASSIFIED);
        viewerUser.setAllowedSectors(Set.of(Department.ENTERPRISE));
        viewerUser.setActive(true);

        // Government sector user with SECRET clearance
        governmentUser = new User();
        governmentUser.setId("gov-1");
        governmentUser.setUsername("government_analyst");
        governmentUser.setRoles(Set.of(UserRole.ANALYST));
        governmentUser.setClearance(ClearanceLevel.SECRET);
        governmentUser.setAllowedSectors(Set.of(Department.GOVERNMENT));
        governmentUser.setActive(true);

        // Medical sector user
        medicalUser = new User();
        medicalUser.setId("med-1");
        medicalUser.setUsername("medical_analyst");
        medicalUser.setRoles(Set.of(UserRole.ANALYST));
        medicalUser.setClearance(ClearanceLevel.CUI);
        medicalUser.setAllowedSectors(Set.of(Department.MEDICAL));
        medicalUser.setActive(true);
    }

    @Test
    @DisplayName("Admin should access all sectors")
    void adminShouldAccessAllSectors() {
        for (Department dept : Department.values()) {
            assertTrue(adminUser.canAccessSector(dept),
                "Admin should access " + dept);
        }
    }

    @Test
    @DisplayName("Viewer should only access assigned sector")
    void viewerShouldOnlyAccessAssignedSector() {
        assertTrue(viewerUser.canAccessSector(Department.ENTERPRISE));
        assertFalse(viewerUser.canAccessSector(Department.GOVERNMENT));
        assertFalse(viewerUser.canAccessSector(Department.MEDICAL));
        assertFalse(viewerUser.canAccessSector(Department.FINANCE));
    }

    @Test
    @DisplayName("Government user cannot access Medical sector")
    void governmentUserCannotAccessMedical() {
        assertTrue(governmentUser.canAccessSector(Department.GOVERNMENT));
        assertFalse(governmentUser.canAccessSector(Department.MEDICAL));
        assertFalse(governmentUser.canAccessSector(Department.ENTERPRISE));
    }

    @Test
    @DisplayName("Medical user cannot access Government sector")
    void medicalUserCannotAccessGovernment() {
        assertTrue(medicalUser.canAccessSector(Department.MEDICAL));
        assertFalse(medicalUser.canAccessSector(Department.GOVERNMENT));
        assertFalse(medicalUser.canAccessSector(Department.ENTERPRISE));
    }

    @Test
    @DisplayName("Top Secret clearance can access all classification levels")
    void topSecretClearanceAccessesAll() {
        assertTrue(adminUser.canAccessClassification(ClearanceLevel.UNCLASSIFIED));
        assertTrue(adminUser.canAccessClassification(ClearanceLevel.CUI));
        assertTrue(adminUser.canAccessClassification(ClearanceLevel.SECRET));
        assertTrue(adminUser.canAccessClassification(ClearanceLevel.TOP_SECRET));
    }

    @Test
    @DisplayName("Secret clearance cannot access Top Secret")
    void secretClearanceCannotAccessTopSecret() {
        assertTrue(governmentUser.canAccessClassification(ClearanceLevel.UNCLASSIFIED));
        assertTrue(governmentUser.canAccessClassification(ClearanceLevel.CUI));
        assertTrue(governmentUser.canAccessClassification(ClearanceLevel.SECRET));
        assertFalse(governmentUser.canAccessClassification(ClearanceLevel.TOP_SECRET));
    }

    @Test
    @DisplayName("Unclassified clearance can only access Unclassified")
    void unclassifiedClearanceIsLimited() {
        assertTrue(viewerUser.canAccessClassification(ClearanceLevel.UNCLASSIFIED));
        assertFalse(viewerUser.canAccessClassification(ClearanceLevel.CUI));
        assertFalse(viewerUser.canAccessClassification(ClearanceLevel.SECRET));
        assertFalse(viewerUser.canAccessClassification(ClearanceLevel.TOP_SECRET));
    }

    @Test
    @DisplayName("User with QUERY permission can query")
    void userWithQueryPermissionCanQuery() {
        assertTrue(adminUser.hasPermission(UserRole.Permission.QUERY));
        assertTrue(governmentUser.hasPermission(UserRole.Permission.QUERY));
    }

    @Test
    @DisplayName("Viewer has QUERY permission but not INGEST")
    void viewerHasLimitedPermissions() {
        assertTrue(viewerUser.hasPermission(UserRole.Permission.QUERY));
        assertFalse(viewerUser.hasPermission(UserRole.Permission.INGEST));
        assertFalse(viewerUser.hasPermission(UserRole.Permission.MANAGE_USERS));
    }

    @Test
    @DisplayName("Admin has all permissions")
    void adminHasAllPermissions() {
        assertTrue(adminUser.hasPermission(UserRole.Permission.QUERY));
        assertTrue(adminUser.hasPermission(UserRole.Permission.INGEST));
        assertTrue(adminUser.hasPermission(UserRole.Permission.MANAGE_USERS));
        assertTrue(adminUser.hasPermission(UserRole.Permission.VIEW_AUDIT));
    }

    @Test
    @DisplayName("Inactive user should not be active")
    void inactiveUserShouldNotBeActive() {
        viewerUser.setActive(false);
        assertFalse(viewerUser.isActive());
    }

    @Test
    @DisplayName("Pending approval user is marked correctly")
    void pendingApprovalUserIsMarked() {
        viewerUser.setPendingApproval(true);
        assertTrue(viewerUser.isPendingApproval());
    }

    @Test
    @DisplayName("Department has required clearance level")
    void departmentHasRequiredClearance() {
        assertNotNull(Department.GOVERNMENT.getRequiredClearance());
        assertNotNull(Department.MEDICAL.getRequiredClearance());
        assertNotNull(Department.ENTERPRISE.getRequiredClearance());
    }
}
