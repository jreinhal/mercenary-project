package com.jreinhal.mercenary.model;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.model.ClearanceLevel;
import com.jreinhal.mercenary.model.UserRole;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection="users")
public class User {
    @Id
    private String id;
    private String username;
    private String displayName;
    private String email;
    private Set<UserRole> roles = new HashSet<UserRole>();
    private ClearanceLevel clearance = ClearanceLevel.UNCLASSIFIED;
    private Set<Department> allowedSectors = new HashSet<Department>();
    private AuthProvider authProvider = AuthProvider.LOCAL;
    private String externalId;
    private String passwordHash;
    private Instant createdAt;
    private Instant lastLoginAt;
    private boolean active = true;
    private boolean pendingApproval = false;

    public static User devUser(String username) {
        User user = new User();
        user.id = "dev-" + username;
        user.username = username;
        user.displayName = username.toUpperCase();
        user.roles = Set.of(UserRole.ADMIN);
        user.clearance = ClearanceLevel.TOP_SECRET;
        user.allowedSectors = Set.of(Department.values());
        user.authProvider = AuthProvider.LOCAL;
        user.createdAt = Instant.now();
        user.active = true;
        return user;
    }

    public String getId() {
        return this.id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUsername() {
        return this.username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getEmail() {
        return this.email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Set<UserRole> getRoles() {
        return this.roles;
    }

    public void setRoles(Set<UserRole> roles) {
        this.roles = roles;
    }

    public ClearanceLevel getClearance() {
        return this.clearance;
    }

    public void setClearance(ClearanceLevel clearance) {
        this.clearance = clearance;
    }

    public Set<Department> getAllowedSectors() {
        return this.allowedSectors;
    }

    public void setAllowedSectors(Set<Department> allowedSectors) {
        this.allowedSectors = allowedSectors;
    }

    public AuthProvider getAuthProvider() {
        return this.authProvider;
    }

    public void setAuthProvider(AuthProvider authProvider) {
        this.authProvider = authProvider;
    }

    public String getExternalId() {
        return this.externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getPasswordHash() {
        return this.passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Instant getCreatedAt() {
        return this.createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastLoginAt() {
        return this.lastLoginAt;
    }

    public void setLastLoginAt(Instant lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public boolean isActive() {
        return this.active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isPendingApproval() {
        return this.pendingApproval;
    }

    public void setPendingApproval(boolean pendingApproval) {
        this.pendingApproval = pendingApproval;
    }

    public boolean hasRole(UserRole role) {
        return this.roles.contains(role);
    }

    public boolean hasPermission(UserRole.Permission permission) {
        return this.roles.stream().anyMatch(role -> role.hasPermission(permission));
    }

    public boolean canAccessSector(Department sector) {
        return this.allowedSectors.contains(sector) || this.hasRole(UserRole.ADMIN);
    }

    public boolean canAccessClassification(ClearanceLevel required) {
        return this.clearance.canAccess(required);
    }

    public static enum AuthProvider {
        LOCAL,
        OIDC,
        CAC;

    }
}
