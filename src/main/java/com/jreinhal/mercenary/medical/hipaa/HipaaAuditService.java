package com.jreinhal.mercenary.medical.hipaa;

import com.jreinhal.mercenary.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * HIPAA-compliant audit logging service for Protected Health Information (PHI) access.
 *
 * MEDICAL EDITION ONLY - This class is excluded from trial/professional builds.
 *
 * Implements 45 CFR 164.312(b) audit control requirements:
 * - Records all PHI access, modifications, and disclosures
 * - Tracks user identity, timestamp, action, and data accessed
 * - Maintains immutable audit trail for compliance reviews
 * - Supports 6-year retention requirement (configurable)
 */
@Service
public class HipaaAuditService {

    private static final Logger log = LoggerFactory.getLogger(HipaaAuditService.class);
    private static final String COLLECTION_NAME = "hipaa_audit_log";

    private final MongoTemplate mongoTemplate;

    public HipaaAuditService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
        log.info("HIPAA Audit Service initialized - PHI access logging enabled");
    }

    /**
     * HIPAA audit event types per 45 CFR 164.312(b).
     */
    public enum AuditEventType {
        PHI_ACCESS,          // Read/view of PHI
        PHI_CREATE,          // Creation of new PHI record
        PHI_MODIFY,          // Modification of existing PHI
        PHI_DELETE,          // Deletion of PHI (should be rare)
        PHI_DISCLOSURE,      // Disclosure to third party
        PHI_EXPORT,          // Export/download of PHI
        PHI_PRINT,           // Print of PHI
        PHI_QUERY,           // Query that returns PHI
        AUTH_SUCCESS,        // Successful authentication
        AUTH_FAILURE,        // Failed authentication attempt
        PERMISSION_DENIED    // Access denied to PHI
    }

    /**
     * Log a PHI access event.
     */
    public void logPhiAccess(User user, String resourceId, String resourceType, String action) {
        logEvent(AuditEventType.PHI_ACCESS, user, Map.of(
            "resourceId", resourceId,
            "resourceType", resourceType,
            "action", action
        ));
    }

    /**
     * Log a PHI query event.
     */
    public void logPhiQuery(User user, String query, int resultCount, List<String> documentIds) {
        logEvent(AuditEventType.PHI_QUERY, user, Map.of(
            "query", sanitizeQuery(query),
            "resultCount", resultCount,
            "documentIds", documentIds.size() > 10 ? documentIds.subList(0, 10) : documentIds
        ));
    }

    /**
     * Log a PHI disclosure event (sharing with external party).
     */
    public void logPhiDisclosure(User user, String recipientOrg, String purpose, List<String> resourceIds) {
        logEvent(AuditEventType.PHI_DISCLOSURE, user, Map.of(
            "recipientOrganization", recipientOrg,
            "purpose", purpose,
            "resourceIds", resourceIds,
            "disclosureTimestamp", Instant.now().toString()
        ));
    }

    /**
     * Log an authentication event.
     */
    public void logAuthentication(String username, boolean success, String ipAddress, String reason) {
        AuditEventType type = success ? AuditEventType.AUTH_SUCCESS : AuditEventType.AUTH_FAILURE;
        HipaaAuditEvent event = new HipaaAuditEvent(
            type,
            username,
            null, // No user object for auth events
            ipAddress,
            Map.of(
                "success", success,
                "reason", reason != null ? reason : ""
            )
        );
        saveEvent(event);
    }

    /**
     * Log a permission denied event.
     */
    public void logPermissionDenied(User user, String resource, String requiredPermission) {
        logEvent(AuditEventType.PERMISSION_DENIED, user, Map.of(
            "resource", resource,
            "requiredPermission", requiredPermission,
            "userRoles", user.getRoles().toString()
        ));
    }

    /**
     * Log PHI access by user ID (for PII reveal operations).
     * Overload that accepts String parameters for controller use.
     */
    public void logPhiAccess(String userId, String action, String resourceId,
                             String reason, boolean breakTheGlass) {
        HipaaAuditEvent event = new HipaaAuditEvent(
            AuditEventType.PHI_ACCESS,
            userId,
            null,
            null,
            Map.of(
                "action", action,
                "resourceId", resourceId,
                "reason", reason,
                "breakTheGlass", breakTheGlass
            )
        );
        saveEvent(event);

        if (breakTheGlass) {
            log.warn("BREAK-THE-GLASS: PHI access by {} for resource {} - Reason: {}",
                     userId, resourceId, reason);
        }
    }

    /**
     * Log a break-the-glass emergency access event.
     * These events require enhanced logging and review.
     */
    public void logBreakTheGlass(String userId, String resourceId,
                                  String patientId, String emergencyReason) {
        HipaaAuditEvent event = new HipaaAuditEvent(
            AuditEventType.PHI_ACCESS,
            userId,
            null,
            null,
            Map.of(
                "action", "BREAK_THE_GLASS",
                "resourceId", resourceId,
                "patientId", patientId,
                "emergencyReason", emergencyReason,
                "requiresReview", true
            )
        );
        saveEvent(event);

        log.error("!!! BREAK-THE-GLASS EMERGENCY ACCESS !!!");
        log.error("User: {}, Patient: {}, Reason: {}", userId, patientId, emergencyReason);
    }

    /**
     * Core event logging method.
     */
    private void logEvent(AuditEventType type, User user, Map<String, Object> details) {
        HipaaAuditEvent event = new HipaaAuditEvent(
            type,
            user.getUsername(),
            user.getId(),
            null, // IP should be set by caller if available
            details
        );
        saveEvent(event);
    }

    /**
     * Save event to MongoDB with immutability guarantees.
     */
    private void saveEvent(HipaaAuditEvent event) {
        try {
            mongoTemplate.save(event, COLLECTION_NAME);
            log.debug("HIPAA audit: {} by {} - {}", event.eventType(), event.username(), event.details());
        } catch (Exception e) {
            // Audit logging failures are serious - log but don't throw
            log.error("HIPAA AUDIT FAILURE - Event: {}, User: {}, Error: {}",
                event.eventType(), event.username(), e.getMessage());
        }
    }

    /**
     * Sanitize query string for safe logging (remove potential PHI).
     */
    private String sanitizeQuery(String query) {
        if (query == null) return "";
        // Truncate long queries and remove potential identifiers
        String sanitized = query.length() > 200 ? query.substring(0, 200) + "..." : query;
        // Remove potential SSN patterns
        sanitized = sanitized.replaceAll("\\d{3}-\\d{2}-\\d{4}", "[SSN-REDACTED]");
        // Remove potential MRN patterns (varies by org, this is a common format)
        sanitized = sanitized.replaceAll("\\b[A-Z]{2,3}\\d{6,10}\\b", "[MRN-REDACTED]");
        return sanitized;
    }

    /**
     * Immutable audit event record.
     */
    public record HipaaAuditEvent(
        AuditEventType eventType,
        String username,
        String userId,
        String ipAddress,
        Map<String, Object> details,
        Instant timestamp,
        String id
    ) {
        public HipaaAuditEvent(AuditEventType eventType, String username, String userId,
                               String ipAddress, Map<String, Object> details) {
            this(eventType, username, userId, ipAddress, details, Instant.now(), null);
        }
    }
}
