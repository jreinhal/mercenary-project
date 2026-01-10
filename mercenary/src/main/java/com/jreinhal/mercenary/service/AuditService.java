package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.model.AuditEvent;
import com.jreinhal.mercenary.model.AuditEvent.EventType;
import com.jreinhal.mercenary.model.AuditEvent.Outcome;
import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.Department;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * Service for STIG-compliant audit logging.
 * 
 * All security-relevant events are persisted to MongoDB for compliance
 * auditing.
 * Supports both synchronous logging and async batch operations.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private final MongoTemplate mongoTemplate;

    public AuditService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Log an audit event.
     */
    public void log(AuditEvent event) {
        try {
            mongoTemplate.save(event, "audit_log");
            log.debug("Audit event logged: {} - {} - {}",
                    event.getEventType(), event.getUserId(), event.getAction());
        } catch (Exception e) {
            // Audit logging failures should not break the application
            // but MUST be logged for security review
            log.error("CRITICAL: Failed to persist audit event: {} - {}",
                    event.getEventType(), e.getMessage());
        }
    }

    /**
     * Log a successful authentication.
     */
    public void logAuthSuccess(User user, HttpServletRequest request) {
        AuditEvent event = AuditEvent.create(EventType.AUTH_SUCCESS, user.getId(), "User authenticated")
                .withUser(user)
                .withRequest(getClientIp(request), request.getHeader("User-Agent"), request.getSession().getId())
                .withMetadata("authProvider", user.getAuthProvider().name());
        log(event);
    }

    /**
     * Log a failed authentication attempt.
     */
    public void logAuthFailure(String attemptedUser, String reason, HttpServletRequest request) {
        AuditEvent event = AuditEvent.create(EventType.AUTH_FAILURE, attemptedUser, "Authentication failed")
                .withRequest(getClientIp(request), request.getHeader("User-Agent"), null)
                .withOutcome(Outcome.FAILURE, reason);
        log(event);
    }

    /**
     * Log a query execution.
     */
    public void logQuery(User user, String query, Department sector, String responseSummary,
            HttpServletRequest request) {
        AuditEvent event = AuditEvent.create(EventType.QUERY_EXECUTED, user.getId(), "Intelligence query executed")
                .withUser(user)
                .withRequest(getClientIp(request), request.getHeader("User-Agent"), request.getSession().getId())
                .withResource("QUERY", null)
                .withResponseSummary(responseSummary)
                .withMetadata("sector", sector.name())
                .withMetadata("queryLength", query.length());
        log(event);
    }

    /**
     * Log a document ingestion.
     */
    public void logIngestion(User user, String filename, Department sector, HttpServletRequest request) {
        AuditEvent event = AuditEvent
                .create(EventType.DOCUMENT_INGESTED, user.getId(), "Document ingested: " + filename)
                .withUser(user)
                .withRequest(getClientIp(request), request.getHeader("User-Agent"), request.getSession().getId())
                .withResource("DOCUMENT", filename)
                .withMetadata("sector", sector.name());
        log(event);
    }

    /**
     * Log an access denial.
     */
    public void logAccessDenied(User user, String resource, String reason, HttpServletRequest request) {
        AuditEvent event = AuditEvent.create(EventType.ACCESS_DENIED,
                user != null ? user.getId() : "ANONYMOUS",
                "Access denied to: " + resource)
                .withUser(user)
                .withRequest(getClientIp(request), request.getHeader("User-Agent"),
                        request.getSession() != null ? request.getSession().getId() : null)
                .withResource("ENDPOINT", resource)
                .withOutcome(Outcome.DENIED, reason);
        log(event);
    }

    /**
     * Log a prompt injection detection.
     */
    public void logPromptInjection(User user, String query, HttpServletRequest request) {
        AuditEvent event = AuditEvent.create(EventType.PROMPT_INJECTION_DETECTED,
                user != null ? user.getId() : "ANONYMOUS",
                "Prompt injection attempt blocked")
                .withUser(user)
                .withRequest(getClientIp(request), request.getHeader("User-Agent"), request.getSession().getId())
                .withOutcome(Outcome.DENIED, "Security filter triggered")
                .withMetadata("queryPreview", query.substring(0, Math.min(50, query.length())));
        log(event);
    }

    /**
     * Retrieve audit events for compliance review.
     */
    public List<AuditEvent> getRecentEvents(int limit) {
        return mongoTemplate.findAll(AuditEvent.class, "audit_log")
                .stream()
                .sorted((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()))
                .limit(limit)
                .toList();
    }

    /**
     * Extract client IP, handling proxies.
     */
    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
