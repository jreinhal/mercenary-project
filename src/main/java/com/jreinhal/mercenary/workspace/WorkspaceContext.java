package com.jreinhal.mercenary.workspace;

public final class WorkspaceContext {
    private static final ThreadLocal<String> currentWorkspace = new ThreadLocal<>();
    private static volatile String defaultWorkspaceId = "workspace_default";

    private WorkspaceContext() {
    }

    public static void setDefaultWorkspaceId(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return;
        }
        defaultWorkspaceId = workspaceId.trim();
    }

    public static String getDefaultWorkspaceId() {
        return defaultWorkspaceId;
    }

    public static void setCurrentWorkspaceId(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            currentWorkspace.set(defaultWorkspaceId);
            return;
        }
        currentWorkspace.set(workspaceId.trim());
    }

    public static String getCurrentWorkspaceId() {
        String workspaceId = currentWorkspace.get();
        if (workspaceId == null || workspaceId.isBlank()) {
            return defaultWorkspaceId;
        }
        return workspaceId;
    }

    public static void clear() {
        currentWorkspace.remove();
    }
}
