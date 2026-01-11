package com.jreinhal.mercenary.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;

import java.time.Instant;
import java.util.Map;
import java.util.HashMap;

/**
 * Audit event for STIG/NIST 800-53 AU-3 compliant logging.
 * 
 * Captures all security-relevant events for compliance auditing.
 */
@Document(collection = "audit_log")
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

    // For queries: sanitized summary (no sensitive content)
    private String responseSummary;

    // Additional context (sector, query type, etc.)
    private Map<String, Object> metadata = new HashMap<>();

    // Constructors
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

    // Fluent setters for builder pattern
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
        // Truncate to prevent sensitive data exposure
        this.responseSummary = summary != null && summary.length() > 200
                ? summary.substring(0, 200) + "..."
                : summary;
        return this;
    }

    public AuditEvent withMetadata(String key, Object value) {
        this.metadata.put(key, value);
        return this;
    }

    // Getters
    public String getId() {
        return id;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public EventType getEventType() {
        return eventType;
    }

    public String getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public ClearanceLevel getUserClearance() {
        return userClearance;
    }

    public String getSourceIp() {
        return sourceIp;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getAction() {
        return action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public String getOutcomeReason() {
        return outcomeReason;
    }

    public String getResponseSummary() {
        return responseSummary;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * Event types for audit logging (NIST 800-53 compliant categories).
     */
    public enum EventType {
        // Authentication events
        AUTH_SUCCESS,
        AUTH_FAILURE,
        AUTH_LOGOUT,

        // Authorization events
        ACCESS_GRANTED,
        ACCESS_DENIED,

        // Data operations
        QUERY_EXECUTED,
        DOCUMENT_INGESTED,
        DOCUMENT_DELETED,

        // Administrative events
        USER_CREATED,
        USER_MODIFIED,
        USER_DEACTIVATED,
        CONFIG_CHANGED,

        // Security events
        SECURITY_ALERT,
        PROMPT_INJECTION_DETECTED
    }

    /**
     * Outcome of the audited action.
     */
    public enum Outcome {
        SUCCESS,
        FAILURE,
        DENIED,
        ERROR
    }
}
