/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.springframework.data.annotation.Id
 *  org.springframework.data.mongodb.core.index.Indexed
 *  org.springframework.data.mongodb.core.mapping.Document
 */
package com.jreinhal.mercenary.model;

import com.jreinhal.mercenary.model.ClearanceLevel;
import com.jreinhal.mercenary.model.User;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection="audit_log")
public class AuditEvent {
    @Id
    private String id;
    @Indexed
    private Instant timestamp;
    @Indexed
    private EventType eventType;
    @Indexed
    private String userId;
    private String username;
    private ClearanceLevel userClearance;
    private String sourceIp;
    private String userAgent;
    private String sessionId;
    private String action;
    private String resourceType;
    private String resourceId;
    @Indexed
    private Outcome outcome;
    private String outcomeReason;
    private String responseSummary;
    private Map<String, Object> metadata = new HashMap<String, Object>();

    public AuditEvent() {
        this.timestamp = Instant.now();
    }

    public static AuditEvent create(EventType type, String userId, String action) {
        AuditEvent event = new AuditEvent();
        event.eventType = type;
        event.userId = userId;
        event.action = action;
        event.outcome = Outcome.SUCCESS;
        return event;
    }

    public AuditEvent withUser(User user) {
        if (user != null) {
            this.userId = user.getId();
            this.username = user.getUsername();
            this.userClearance = user.getClearance();
        }
        return this;
    }

    public AuditEvent withRequest(String sourceIp, String userAgent, String sessionId) {
        this.sourceIp = sourceIp;
        this.userAgent = userAgent;
        this.sessionId = sessionId;
        return this;
    }

    public AuditEvent withResource(String type, String id) {
        this.resourceType = type;
        this.resourceId = id;
        return this;
    }

    public AuditEvent withOutcome(Outcome outcome, String reason) {
        this.outcome = outcome;
        this.outcomeReason = reason;
        return this;
    }

    public AuditEvent withResponseSummary(String summary) {
        this.responseSummary = summary != null && summary.length() > 200 ? summary.substring(0, 200) + "..." : summary;
        return this;
    }

    public AuditEvent withMetadata(String key, Object value) {
        this.metadata.put(key, value);
        return this;
    }

    public String getId() {
        return this.id;
    }

    public Instant getTimestamp() {
        return this.timestamp;
    }

    public EventType getEventType() {
        return this.eventType;
    }

    public String getUserId() {
        return this.userId;
    }

    public String getUsername() {
        return this.username;
    }

    public ClearanceLevel getUserClearance() {
        return this.userClearance;
    }

    public String getSourceIp() {
        return this.sourceIp;
    }

    public String getUserAgent() {
        return this.userAgent;
    }

    public String getSessionId() {
        return this.sessionId;
    }

    public String getAction() {
        return this.action;
    }

    public String getResourceType() {
        return this.resourceType;
    }

    public String getResourceId() {
        return this.resourceId;
    }

    public Outcome getOutcome() {
        return this.outcome;
    }

    public String getOutcomeReason() {
        return this.outcomeReason;
    }

    public String getResponseSummary() {
        return this.responseSummary;
    }

    public Map<String, Object> getMetadata() {
        return this.metadata;
    }

    public static enum EventType {
        AUTH_SUCCESS,
        AUTH_FAILURE,
        AUTH_LOGOUT,
        ACCESS_GRANTED,
        ACCESS_DENIED,
        QUERY_EXECUTED,
        DOCUMENT_INGESTED,
        DOCUMENT_DELETED,
        USER_CREATED,
        USER_MODIFIED,
        USER_DEACTIVATED,
        CONFIG_CHANGED,
        SECURITY_ALERT,
        PROMPT_INJECTION_DETECTED;

    }

    public static enum Outcome {
        SUCCESS,
        FAILURE,
        DENIED,
        ERROR;

    }
}
