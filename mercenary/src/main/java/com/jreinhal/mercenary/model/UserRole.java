package com.jreinhal.mercenary.model;

import java.util.Set;

/**
 * User roles with permissions applicable across all verticals.
 */
public enum UserRole {
    /**
     * Full system access - manage users, configure system, all operations.
     */
    ADMIN(Set.of(Permission.QUERY, Permission.INGEST, Permission.DELETE, Permission.MANAGE_USERS, Permission.VIEW_AUDIT,
            Permission.CONFIGURE)),

    /**
     * Primary operator - query and ingest documents, no admin functions.
     */
    ANALYST(Set.of(Permission.QUERY, Permission.INGEST)),

    /**
     * Read-only access - query only, no document modification.
     */
    VIEWER(Set.of(Permission.QUERY)),

    /**
     * Medical access role for PHI reveal workflows.
     */
    PHI_ACCESS(Set.of(Permission.QUERY)),

    /**
     * Compliance/security role - read-only access to audit logs.
     */
    AUDITOR(Set.of(Permission.QUERY, Permission.VIEW_AUDIT));

    private final Set<Permission> permissions;

    UserRole(Set<Permission> permissions) {
        this.permissions = permissions;
    }

    public Set<Permission> getPermissions() {
        return permissions;
    }

    public boolean hasPermission(Permission permission) {
        return permissions.contains(permission);
    }

    /**
     * Granular permissions for RBAC enforcement.
     */
    public enum Permission {
        QUERY, // Execute intelligence queries
        INGEST, // Upload and ingest documents
        DELETE, // Remove documents from the system
        MANAGE_USERS, // Create/modify user accounts
        VIEW_AUDIT, // Access audit logs
        CONFIGURE // Modify system configuration
    }
}
