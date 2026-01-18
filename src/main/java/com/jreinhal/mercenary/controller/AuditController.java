package com.jreinhal.mercenary.controller;

import com.jreinhal.mercenary.model.AuditEvent;
import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.UserRole;
import com.jreinhal.mercenary.service.AuditService;
import com.jreinhal.mercenary.filter.SecurityContext;

import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * Controller for audit log access and compliance reporting.
 * 
 * Access restricted to users with VIEW_AUDIT permission.
 */
@RestController
@RequestMapping("/api/audit")
@CrossOrigin
public class AuditController {

    private static final Logger log = LoggerFactory.getLogger(AuditController.class);

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * Get recent audit events.
     * 
     * Requires VIEW_AUDIT permission.
     */
    @GetMapping("/events")
    public Object getRecentEvents(
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            HttpServletRequest request) {

        User user = SecurityContext.getCurrentUser();

        // RBAC Check: Require VIEW_AUDIT permission
        if (user == null || !user.hasPermission(UserRole.Permission.VIEW_AUDIT)) {
            log.warn("Unauthorized audit log access attempt from: {}",
                    user != null ? user.getUsername() : "ANONYMOUS");
            auditService.logAccessDenied(user, "/api/audit/events", "Missing VIEW_AUDIT permission", request);
            return Map.of("error", "ACCESS DENIED: Audit log access requires AUDITOR role.");
        }

        // Limit to prevent DoS
        if (limit > 1000) {
            limit = 1000;
        }

        List<AuditEvent> events = auditService.getRecentEvents(limit);

        Map<String, Object> response = new HashMap<>();
        response.put("count", events.size());
        response.put("events", events);
        response.put("requestedBy", user.getUsername());

        return response;
    }

    /**
     * Get audit statistics for compliance dashboard.
     */
    @GetMapping("/stats")
    public Object getAuditStats(HttpServletRequest request) {
        User user = SecurityContext.getCurrentUser();

        if (user == null || !user.hasPermission(UserRole.Permission.VIEW_AUDIT)) {
            auditService.logAccessDenied(user, "/api/audit/stats", "Missing VIEW_AUDIT permission", request);
            return Map.of("error", "ACCESS DENIED: Audit statistics require AUDITOR role.");
        }

        List<AuditEvent> recentEvents = auditService.getRecentEvents(500);

        long authSuccessCount = recentEvents.stream()
                .filter(e -> e.getEventType() == AuditEvent.EventType.AUTH_SUCCESS)
                .count();
        long authFailCount = recentEvents.stream()
                .filter(e -> e.getEventType() == AuditEvent.EventType.AUTH_FAILURE)
                .count();
        long queryCount = recentEvents.stream()
                .filter(e -> e.getEventType() == AuditEvent.EventType.QUERY_EXECUTED)
                .count();
        long accessDeniedCount = recentEvents.stream()
                .filter(e -> e.getEventType() == AuditEvent.EventType.ACCESS_DENIED)
                .count();
        long securityAlerts = recentEvents.stream()
                .filter(e -> e.getEventType() == AuditEvent.EventType.PROMPT_INJECTION_DETECTED
                        || e.getEventType() == AuditEvent.EventType.SECURITY_ALERT)
                .count();

        return Map.of(
                "totalEvents", recentEvents.size(),
                "authSuccess", authSuccessCount,
                "authFailure", authFailCount,
                "queries", queryCount,
                "accessDenied", accessDeniedCount,
                "securityAlerts", securityAlerts);
    }
}
