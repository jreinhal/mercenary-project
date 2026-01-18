package com.jreinhal.mercenary.professional.admin;

import com.jreinhal.mercenary.model.UserRole;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST controller for admin dashboard endpoints.
 *
 * PROFESSIONAL EDITION - Available in professional, medical, and government builds.
 *
 * All endpoints require ADMIN role.
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminDashboardService dashboardService;

    public AdminController(AdminDashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    // ==================== User Management ====================

    /**
     * Get all users.
     */
    @GetMapping("/users")
    public ResponseEntity<List<AdminDashboardService.UserSummary>> getAllUsers() {
        return ResponseEntity.ok(dashboardService.getAllUsers());
    }

    /**
     * Get users pending approval.
     */
    @GetMapping("/users/pending")
    public ResponseEntity<List<AdminDashboardService.UserSummary>> getPendingUsers() {
        return ResponseEntity.ok(dashboardService.getPendingApprovals());
    }

    /**
     * Approve a pending user.
     */
    @PostMapping("/users/{userId}/approve")
    public ResponseEntity<Map<String, Object>> approveUser(@PathVariable String userId) {
        boolean success = dashboardService.approveUser(userId);
        return ResponseEntity.ok(Map.of(
            "success", success,
            "message", success ? "User approved" : "User not found"
        ));
    }

    /**
     * Activate a user.
     */
    @PostMapping("/users/{userId}/activate")
    public ResponseEntity<Map<String, Object>> activateUser(@PathVariable String userId) {
        boolean success = dashboardService.activateUser(userId);
        return ResponseEntity.ok(Map.of(
            "success", success,
            "message", success ? "User activated" : "User not found"
        ));
    }

    /**
     * Deactivate a user.
     */
    @PostMapping("/users/{userId}/deactivate")
    public ResponseEntity<Map<String, Object>> deactivateUser(@PathVariable String userId) {
        boolean success = dashboardService.deactivateUser(userId);
        return ResponseEntity.ok(Map.of(
            "success", success,
            "message", success ? "User deactivated" : "User not found"
        ));
    }

    /**
     * Update user roles.
     */
    @PutMapping("/users/{userId}/roles")
    public ResponseEntity<Map<String, Object>> updateUserRoles(
            @PathVariable String userId,
            @RequestBody RoleUpdateRequest request) {
        boolean success = dashboardService.updateUserRoles(userId, request.roles());
        return ResponseEntity.ok(Map.of(
            "success", success,
            "message", success ? "Roles updated" : "User not found"
        ));
    }

    public record RoleUpdateRequest(Set<UserRole> roles) {}

    // ==================== Statistics ====================

    /**
     * Get usage statistics.
     */
    @GetMapping("/stats/usage")
    public ResponseEntity<AdminDashboardService.UsageStats> getUsageStats() {
        return ResponseEntity.ok(dashboardService.getUsageStats());
    }

    /**
     * Get document statistics.
     */
    @GetMapping("/stats/documents")
    public ResponseEntity<AdminDashboardService.DocumentStats> getDocumentStats() {
        return ResponseEntity.ok(dashboardService.getDocumentStats());
    }

    // ==================== System Health ====================

    /**
     * Get system health status.
     */
    @GetMapping("/health")
    public ResponseEntity<AdminDashboardService.HealthStatus> getHealthStatus() {
        return ResponseEntity.ok(dashboardService.getHealthStatus());
    }

    // ==================== Dashboard Summary ====================

    /**
     * Get complete dashboard summary.
     */
    @GetMapping("/dashboard")
    public ResponseEntity<DashboardSummary> getDashboardSummary() {
        return ResponseEntity.ok(new DashboardSummary(
            dashboardService.getUsageStats(),
            dashboardService.getHealthStatus(),
            dashboardService.getDocumentStats(),
            dashboardService.getPendingApprovals().size()
        ));
    }

    public record DashboardSummary(
        AdminDashboardService.UsageStats usage,
        AdminDashboardService.HealthStatus health,
        AdminDashboardService.DocumentStats documents,
        int pendingApprovals
    ) {}
}
