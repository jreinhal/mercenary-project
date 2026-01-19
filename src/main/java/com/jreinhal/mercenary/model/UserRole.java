/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.jreinhal.mercenary.model.UserRole
 *  com.jreinhal.mercenary.model.UserRole$Permission
 */
package com.jreinhal.mercenary.model;

import com.jreinhal.mercenary.model.UserRole;
import java.util.Set;

public enum UserRole {
    ADMIN(Set.of(Permission.QUERY, Permission.INGEST, Permission.DELETE, Permission.MANAGE_USERS, Permission.VIEW_AUDIT, Permission.CONFIGURE)),
    ANALYST(Set.of(Permission.QUERY, Permission.INGEST)),
    VIEWER(Set.of(Permission.QUERY)),
    PHI_ACCESS(Set.of(Permission.QUERY)),
    AUDITOR(Set.of(Permission.QUERY, Permission.VIEW_AUDIT));

    private final Set<Permission> permissions;

    private UserRole(Set<Permission> permissions) {
        this.permissions = permissions;
    }

    public Set<Permission> getPermissions() {
        return this.permissions;
    }

    public boolean hasPermission(Permission permission) {
        return this.permissions.contains(permission);
    }
}

