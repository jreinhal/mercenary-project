package com.jreinhal.mercenary.workspace;

import com.jreinhal.mercenary.core.license.LicenseService;
import com.jreinhal.mercenary.model.User;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WorkspacePolicy {
    private final LicenseService licenseService;

    @Value("${sentinel.workspace.enabled:true}")
    private boolean workspaceEnabled;

    @Value("${sentinel.workspace.allow-regulated:false}")
    private boolean allowRegulated;

    @Value("${sentinel.workspace.default-id:workspace_default}")
    private String defaultWorkspaceId;

    public WorkspacePolicy(LicenseService licenseService) {
        this.licenseService = licenseService;
    }

    @PostConstruct
    public void init() {
        WorkspaceContext.setDefaultWorkspaceId(defaultWorkspaceId);
    }

    public String resolveWorkspace(User user, String requestedWorkspace) {
        if (!workspaceEnabled || (isRegulatedEdition() && !allowRegulated)) {
            return WorkspaceContext.getDefaultWorkspaceId();
        }
        String candidate = requestedWorkspace == null || requestedWorkspace.isBlank()
                ? WorkspaceContext.getDefaultWorkspaceId()
                : requestedWorkspace.trim();
        if (user == null) {
            return WorkspaceContext.getDefaultWorkspaceId();
        }
        if (user.canAccessWorkspace(candidate)) {
            return candidate;
        }
        return WorkspaceContext.getDefaultWorkspaceId();
    }

    public boolean allowWorkspaceSwitching() {
        if (!workspaceEnabled) return false;
        return allowRegulated || !isRegulatedEdition();
    }

    public boolean isRegulatedEdition() {
        LicenseService.Edition edition = licenseService.getEdition();
        return edition == LicenseService.Edition.MEDICAL || edition == LicenseService.Edition.GOVERNMENT;
    }
}
