/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  jakarta.servlet.http.HttpServletRequest
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.beans.factory.annotation.Value
 *  org.springframework.data.mongodb.core.MongoTemplate
 *  org.springframework.stereotype.Service
 */
package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.model.AuditEvent;
import com.jreinhal.mercenary.model.User;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private final MongoTemplate mongoTemplate;
    @Value(value="${app.audit.fail-closed:false}")
    private boolean failClosed;

    public AuditService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public void log(AuditEvent event) {
        block2: {
            try {
                this.mongoTemplate.save(event, "audit_log");
                log.debug("Audit event logged: {} - {} - {}", new Object[]{event.getEventType(), event.getUserId(), event.getAction()});
            }
            catch (Exception e) {
                log.error("CRITICAL: Failed to persist audit event: {} - {}", event.getEventType(), e.getMessage());
                if (!this.failClosed) break block2;
                throw new AuditFailureException("Audit logging failed - operation halted for compliance. Event: " + String.valueOf(event.getEventType()) + ", Error: " + e.getMessage(), e);
            }
        }
    }

    public void logAuthSuccess(User user, HttpServletRequest request) {
        AuditEvent event = AuditEvent.create(AuditEvent.EventType.AUTH_SUCCESS, user.getId(), "User authenticated").withUser(user).withRequest(this.getClientIp(request), request.getHeader("User-Agent"), request.getSession().getId()).withMetadata("authProvider", user.getAuthProvider().name());
        this.log(event);
    }

    public void logAuthFailure(String attemptedUser, String reason, HttpServletRequest request) {
        AuditEvent event = AuditEvent.create(AuditEvent.EventType.AUTH_FAILURE, attemptedUser, "Authentication failed").withRequest(this.getClientIp(request), request.getHeader("User-Agent"), null).withOutcome(AuditEvent.Outcome.FAILURE, reason);
        this.log(event);
    }

    public void logQuery(User user, String query, Department sector, String responseSummary, HttpServletRequest request) {
        AuditEvent event = AuditEvent.create(AuditEvent.EventType.QUERY_EXECUTED, user.getId(), "Intelligence query executed").withUser(user).withRequest(this.getClientIp(request), request.getHeader("User-Agent"), request.getSession().getId()).withResource("QUERY", null).withResponseSummary(responseSummary).withMetadata("sector", sector.name()).withMetadata("queryLength", query.length());
        this.log(event);
    }

    public void logIngestion(User user, String filename, Department sector, HttpServletRequest request) {
        AuditEvent event = AuditEvent.create(AuditEvent.EventType.DOCUMENT_INGESTED, user.getId(), "Document ingested: " + filename).withUser(user).withRequest(this.getClientIp(request), request.getHeader("User-Agent"), request.getSession().getId()).withResource("DOCUMENT", filename).withMetadata("sector", sector.name());
        this.log(event);
    }

    public void logAccessDenied(User user, String resource, String reason, HttpServletRequest request) {
        AuditEvent event = AuditEvent.create(AuditEvent.EventType.ACCESS_DENIED, user != null ? user.getId() : "ANONYMOUS", "Access denied to: " + resource).withUser(user).withRequest(this.getClientIp(request), request.getHeader("User-Agent"), request.getSession() != null ? request.getSession().getId() : null).withResource("ENDPOINT", resource).withOutcome(AuditEvent.Outcome.DENIED, reason);
        this.log(event);
    }

    public void logPromptInjection(User user, String query, HttpServletRequest request) {
        AuditEvent event = AuditEvent.create(AuditEvent.EventType.PROMPT_INJECTION_DETECTED, user != null ? user.getId() : "ANONYMOUS", "Prompt injection attempt blocked").withUser(user).withRequest(this.getClientIp(request), request.getHeader("User-Agent"), request.getSession().getId()).withOutcome(AuditEvent.Outcome.DENIED, "Security filter triggered").withMetadata("queryPreview", query.substring(0, Math.min(50, query.length())));
        this.log(event);
    }

    public List<AuditEvent> getRecentEvents(int limit) {
        return this.mongoTemplate.findAll(AuditEvent.class, "audit_log").stream().sorted((a, b) -> b.getTimestamp().compareTo(a.getTimestamp())).limit(limit).toList();
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    public static class AuditFailureException
    extends RuntimeException {
        public AuditFailureException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
