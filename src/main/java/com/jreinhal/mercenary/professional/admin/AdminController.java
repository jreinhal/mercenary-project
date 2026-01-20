package com.jreinhal.mercenary.professional.admin;

import com.jreinhal.mercenary.model.UserRole;
import com.jreinhal.mercenary.professional.admin.AdminDashboardService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value={"/api/admin"})
@PreAuthorize(value="hasRole('ADMIN')")
public class AdminController {
    private final AdminDashboardService dashboardService;

    public AdminController(AdminDashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping(value={"/users"})
    public ResponseEntity<List<AdminDashboardService.UserSummary>> getAllUsers() {
        return ResponseEntity.ok(this.dashboardService.getAllUsers());
    }

    @GetMapping(value={"/users/pending"})
    public ResponseEntity<List<AdminDashboardService.UserSummary>> getPendingUsers() {
        return ResponseEntity.ok(this.dashboardService.getPendingApprovals());
    }

    @PostMapping(value={"/users/{userId}/approve"})
    public ResponseEntity<Map<String, Object>> approveUser(@PathVariable String userId) {
        boolean success = this.dashboardService.approveUser(userId);
        return ResponseEntity.ok(Map.of("success", success, "message", success ? "User approved" : "User not found"));
    }

    @PostMapping(value={"/users/{userId}/activate"})
    public ResponseEntity<Map<String, Object>> activateUser(@PathVariable String userId) {
        boolean success = this.dashboardService.activateUser(userId);
        return ResponseEntity.ok(Map.of("success", success, "message", success ? "User activated" : "User not found"));
    }

    @PostMapping(value={"/users/{userId}/deactivate"})
    public ResponseEntity<Map<String, Object>> deactivateUser(@PathVariable String userId) {
        boolean success = this.dashboardService.deactivateUser(userId);
        return ResponseEntity.ok(Map.of("success", success, "message", success ? "User deactivated" : "User not found"));
    }

    @PutMapping(value={"/users/{userId}/roles"})
    public ResponseEntity<Map<String, Object>> updateUserRoles(@PathVariable String userId, @RequestBody RoleUpdateRequest request) {
        boolean success = this.dashboardService.updateUserRoles(userId, request.roles());
        return ResponseEntity.ok(Map.of("success", success, "message", success ? "Roles updated" : "User not found"));
    }

    @GetMapping(value={"/stats/usage"})
    public ResponseEntity<AdminDashboardService.UsageStats> getUsageStats() {
        return ResponseEntity.ok(this.dashboardService.getUsageStats());
    }

    @GetMapping(value={"/stats/documents"})
    public ResponseEntity<AdminDashboardService.DocumentStats> getDocumentStats() {
        return ResponseEntity.ok(this.dashboardService.getDocumentStats());
    }

    @GetMapping(value={"/health"})
    public ResponseEntity<AdminDashboardService.HealthStatus> getHealthStatus() {
        return ResponseEntity.ok(this.dashboardService.getHealthStatus());
    }

    @GetMapping(value={"/dashboard"})
    public ResponseEntity<DashboardSummary> getDashboardSummary() {
        return ResponseEntity.ok(new DashboardSummary(this.dashboardService.getUsageStats(), this.dashboardService.getHealthStatus(), this.dashboardService.getDocumentStats(), this.dashboardService.getPendingApprovals().size()));
    }

    public record RoleUpdateRequest(Set<UserRole> roles) {
    }

    public record DashboardSummary(AdminDashboardService.UsageStats usage, AdminDashboardService.HealthStatus health, AdminDashboardService.DocumentStats documents, int pendingApprovals) {
    }
}
