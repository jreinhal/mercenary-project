package com.jreinhal.mercenary.workspace;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.core.license.LicenseService;
import com.jreinhal.mercenary.model.ClearanceLevel;
import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for workspace resolution logic in {@link WorkspacePolicy}.
 */
class WorkspacePolicyTest {

    private WorkspacePolicy policy;
    private LicenseService licenseService;

    @BeforeEach
    void setUp() {
        licenseService = mock(LicenseService.class);
        when(licenseService.getEdition()).thenReturn(LicenseService.Edition.ENTERPRISE);

        policy = new WorkspacePolicy(licenseService);
        ReflectionTestUtils.setField(policy, "workspaceEnabled", true);
        ReflectionTestUtils.setField(policy, "allowRegulated", false);
        ReflectionTestUtils.setField(policy, "defaultWorkspaceId", "workspace_default");
        policy.init();
    }

    private User userWithWorkspaces(String... workspaceIds) {
        User user = User.devUser("testuser");
        // Remove ADMIN role so workspace access is enforced (ADMIN bypasses all checks)
        user.setRoles(Set.of(UserRole.ANALYST));
        user.setWorkspaceIds(Set.of(workspaceIds));
        return user;
    }

    @Nested
    @DisplayName("Basic Resolution")
    class BasicResolution {

        @Test
        @DisplayName("Should resolve to requested workspace when user has access")
        void shouldResolveToRequestedWorkspace() {
            User user = userWithWorkspaces("ws_alpha", "ws_beta");
            String resolved = policy.resolveWorkspace(user, "ws_alpha");
            assertEquals("ws_alpha", resolved);
        }

        @Test
        @DisplayName("Should fall back to default when user lacks access")
        void shouldFallBackWhenUserLacksAccess() {
            User user = userWithWorkspaces("ws_alpha");
            String resolved = policy.resolveWorkspace(user, "ws_beta");
            assertEquals("workspace_default", resolved);
        }

        @Test
        @DisplayName("Should fall back to default when requested workspace is null")
        void shouldFallBackForNullRequest() {
            User user = userWithWorkspaces("ws_alpha");
            String resolved = policy.resolveWorkspace(user, null);
            assertEquals("workspace_default", resolved);
        }

        @Test
        @DisplayName("Should fall back to default when requested workspace is blank")
        void shouldFallBackForBlankRequest() {
            User user = userWithWorkspaces("ws_alpha");
            String resolved = policy.resolveWorkspace(user, "   ");
            assertEquals("workspace_default", resolved);
        }

        @Test
        @DisplayName("Should fall back to default when user is null")
        void shouldFallBackForNullUser() {
            String resolved = policy.resolveWorkspace(null, "ws_alpha");
            assertEquals("workspace_default", resolved);
        }

        @Test
        @DisplayName("Should trim requested workspace ID")
        void shouldTrimRequestedWorkspaceId() {
            User user = userWithWorkspaces("ws_alpha");
            String resolved = policy.resolveWorkspace(user, "  ws_alpha  ");
            assertEquals("ws_alpha", resolved);
        }
    }

    @Nested
    @DisplayName("Workspace Disabled")
    class WorkspaceDisabled {

        @Test
        @DisplayName("Should always return default when workspace feature is disabled")
        void shouldAlwaysReturnDefaultWhenDisabled() {
            ReflectionTestUtils.setField(policy, "workspaceEnabled", false);
            User user = userWithWorkspaces("ws_alpha", "ws_beta");
            String resolved = policy.resolveWorkspace(user, "ws_alpha");
            assertEquals("workspace_default", resolved);
        }
    }

    @Nested
    @DisplayName("Regulated Edition Handling")
    class RegulatedEdition {

        @Test
        @DisplayName("Should return default for medical edition when allowRegulated=false")
        void shouldReturnDefaultForMedicalWhenNotAllowed() {
            when(licenseService.getEdition()).thenReturn(LicenseService.Edition.MEDICAL);
            User user = userWithWorkspaces("ws_alpha");
            String resolved = policy.resolveWorkspace(user, "ws_alpha");
            assertEquals("workspace_default", resolved,
                    "Medical edition should not allow workspace switching when allowRegulated=false");
        }

        @Test
        @DisplayName("Should return default for government edition when allowRegulated=false")
        void shouldReturnDefaultForGovernmentWhenNotAllowed() {
            when(licenseService.getEdition()).thenReturn(LicenseService.Edition.GOVERNMENT);
            User user = userWithWorkspaces("ws_alpha");
            String resolved = policy.resolveWorkspace(user, "ws_alpha");
            assertEquals("workspace_default", resolved,
                    "Government edition should not allow workspace switching when allowRegulated=false");
        }

        @Test
        @DisplayName("Should allow workspace switching for regulated edition when allowRegulated=true")
        void shouldAllowSwitchingWhenRegulatedAllowed() {
            ReflectionTestUtils.setField(policy, "allowRegulated", true);
            when(licenseService.getEdition()).thenReturn(LicenseService.Edition.MEDICAL);
            User user = userWithWorkspaces("ws_alpha");
            String resolved = policy.resolveWorkspace(user, "ws_alpha");
            assertEquals("ws_alpha", resolved);
        }
    }

    @Nested
    @DisplayName("Workspace Switching Capability")
    class WorkspaceSwitching {

        @Test
        @DisplayName("Should allow switching for enterprise edition")
        void shouldAllowSwitchingForEnterprise() {
            assertTrue(policy.allowWorkspaceSwitching());
        }

        @Test
        @DisplayName("Should not allow switching when disabled")
        void shouldNotAllowSwitchingWhenDisabled() {
            ReflectionTestUtils.setField(policy, "workspaceEnabled", false);
            assertFalse(policy.allowWorkspaceSwitching());
        }

        @Test
        @DisplayName("Should not allow switching for regulated edition unless allowRegulated")
        void shouldNotAllowSwitchingForRegulatedUnlessAllowed() {
            when(licenseService.getEdition()).thenReturn(LicenseService.Edition.GOVERNMENT);
            assertFalse(policy.allowWorkspaceSwitching());

            ReflectionTestUtils.setField(policy, "allowRegulated", true);
            assertTrue(policy.allowWorkspaceSwitching());
        }
    }
}
