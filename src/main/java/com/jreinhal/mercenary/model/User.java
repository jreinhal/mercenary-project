package com.jreinhal.mercenary.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import com.jreinhal.mercenary.Department;

import java.time.Instant;
import java.util.Set;
import java.util.HashSet;

/**
 * User entity for authentication and authorization.
 * Supports multiple authentication providers for different deployment modes.
 */
@Document(collection = "users")
public class User {

    @Id
    private String id;

    private String username;
    private String displayName;
    private String email;

    private Set<UserRole> roles = new HashSet<>();
    private ClearanceLevel clearance = ClearanceLevel.UNCLASSIFIED;
    private Set<Department> allowedSectors = new HashSet<>();

    private AuthProvider authProvider = AuthProvider.LOCAL;
    private String externalId; // OIDC subject or CAC DN
    private String passwordHash; // BCrypt hash for STANDARD auth mode

    private Instant createdAt;
    private Instant lastLoginAt;
    private boolean active = true;
    private boolean pendingApproval = false; // For OIDC approval workflow

    // Default constructor for MongoDB
    public User() {
    }

    // Builder-style constructor for dev mode
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

    // Getters and Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Set<UserRole> getRoles() {
        return roles;
    }

    public void setRoles(Set<UserRole> roles) {
        this.roles = roles;
    }

    public ClearanceLevel getClearance() {
        return clearance;
    }

    public void setClearance(ClearanceLevel clearance) {
        this.clearance = clearance;
    }

    public Set<Department> getAllowedSectors() {
        return allowedSectors;
    }

    public void setAllowedSectors(Set<Department> allowedSectors) {
        this.allowedSectors = allowedSectors;
    }

    public AuthProvider getAuthProvider() {
        return authProvider;
    }

    public void setAuthProvider(AuthProvider authProvider) {
        this.authProvider = authProvider;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Instant lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isPendingApproval() {
        return pendingApproval;
    }

    public void setPendingApproval(boolean pendingApproval) {
        this.pendingApproval = pendingApproval;
    }

    // Authorization helpers
    public boolean hasRole(UserRole role) {
        return roles.contains(role);
    }

    public boolean hasPermission(UserRole.Permission permission) {
        return roles.stream().anyMatch(role -> role.hasPermission(permission));
    }

    public boolean canAccessSector(Department sector) {
        return allowedSectors.contains(sector) || hasRole(UserRole.ADMIN);
    }

    public boolean canAccessClassification(ClearanceLevel required) {
        return clearance.canAccess(required);
    }

    /**
     * Authentication provider types.
     */
    public enum AuthProvider {
        LOCAL, // Development/testing
        OIDC, // Enterprise (Azure AD, Okta, etc.)
        CAC // Government (X.509 certificate)
    }
}
