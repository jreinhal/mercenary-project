/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.jreinhal.mercenary.Department
 *  com.jreinhal.mercenary.model.AuditEvent
 *  com.jreinhal.mercenary.model.AuditEvent$EventType
 *  com.jreinhal.mercenary.model.AuditEvent$Outcome
 *  com.jreinhal.mercenary.model.User
 *  com.jreinhal.mercenary.service.AuditService
 *  com.jreinhal.mercenary.service.AuditService$AuditFailureException
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
import com.jreinhal.mercenary.service.AuditService;
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
                this.mongoTemplate.save((Object)event, "audit_log");
                log.debug("Audit event logged: {} - {} - {}", new Object[]{event.getEventType(), event.getUserId(), event.getAction()});
            }
            catch (Exception e) {
                log.error("CRITICAL: Failed to persist audit event: {} - {}", (Object)event.getEventType(), (Object)e.getMessage());
                if (!this.failClosed) break block2;
                throw new AuditFailureException("Audit logging failed - operation halted for compliance. Event: " + String.valueOf(event.getEventType()) + ", Error: " + e.getMessage(), (Throwable)e);
            }
        }
    }

    public void logAuthSuccess(User user, HttpServletRequest request) {
        AuditEvent event = AuditEvent.create((AuditEvent.EventType)AuditEvent.EventType.AUTH_SUCCESS, (String)user.getId(), (String)"User authenticated").withUser(user).withRequest(this.getClientIp(request), request.getHeader("User-Agent"), request.getSession().getId()).withMetadata("authProvider", (Object)user.getAuthProvider().name());
        this.log(event);
    }

    public void logAuthFailure(String attemptedUser, String reason, HttpServletRequest request) {
        AuditEvent event = AuditEvent.create((AuditEvent.EventType)AuditEvent.EventType.AUTH_FAILURE, (String)attemptedUser, (String)"Authentication failed").withRequest(this.getClientIp(request), request.getHeader("User-Agent"), null).withOutcome(AuditEvent.Outcome.FAILURE, reason);
        this.log(event);
    }

    public void logQuery(User user, String query, Department sector, String responseSummary, HttpServletRequest request) {
        AuditEvent event = AuditEvent.create((AuditEvent.EventType)AuditEvent.EventType.QUERY_EXECUTED, (String)user.getId(), (String)"Intelligence query executed").withUser(user).withRequest(this.getClientIp(request), request.getHeader("User-Agent"), request.getSession().getId()).withResource("QUERY", null).withResponseSummary(responseSummary).withMetadata("sector", (Object)sector.name()).withMetadata("queryLength", (Object)query.length());
        this.log(event);
    }

    public void logIngestion(User user, String filename, Department sector, HttpServletRequest request) {
        AuditEvent event = AuditEvent.create((AuditEvent.EventType)AuditEvent.EventType.DOCUMENT_INGESTED, (String)user.getId(), (String)("Document ingested: " + filename)).withUser(user).withRequest(this.getClientIp(request), request.getHeader("User-Agent"), request.getSession().getId()).withResource("DOCUMENT", filename).withMetadata("sector", (Object)sector.name());
        this.log(event);
    }

    public void logAccessDenied(User user, String resource, String reason, HttpServletRequest request) {
        AuditEvent event = AuditEvent.create((AuditEvent.EventType)AuditEvent.EventType.ACCESS_DENIED, (String)(user != null ? user.getId() : "ANONYMOUS"), (String)("Access denied to: " + resource)).withUser(user).withRequest(this.getClientIp(request), request.getHeader("User-Agent"), request.getSession() != null ? request.getSession().getId() : null).withResource("ENDPOINT", resource).withOutcome(AuditEvent.Outcome.DENIED, reason);
        this.log(event);
    }

    public void logPromptInjection(User user, String query, HttpServletRequest request) {
        AuditEvent event = AuditEvent.create((AuditEvent.EventType)AuditEvent.EventType.PROMPT_INJECTION_DETECTED, (String)(user != null ? user.getId() : "ANONYMOUS"), (String)"Prompt injection attempt blocked").withUser(user).withRequest(this.getClientIp(request), request.getHeader("User-Agent"), request.getSession().getId()).withOutcome(AuditEvent.Outcome.DENIED, "Security filter triggered").withMetadata("queryPreview", (Object)query.substring(0, Math.min(50, query.length())));
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
}

