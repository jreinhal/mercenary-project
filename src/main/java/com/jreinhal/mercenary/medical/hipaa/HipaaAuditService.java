package com.jreinhal.mercenary.medical.hipaa;

import com.jreinhal.mercenary.model.User;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

@Service
public class HipaaAuditService {
    private static final Logger log = LoggerFactory.getLogger(HipaaAuditService.class);
    private static final String COLLECTION_NAME = "hipaa_audit_log";
    private final MongoTemplate mongoTemplate;

    public HipaaAuditService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
        log.info("HIPAA Audit Service initialized - PHI access logging enabled");
    }

    public void logPhiAccess(User user, String resourceId, String resourceType, String action) {
        this.logEvent(AuditEventType.PHI_ACCESS, user, Map.of("resourceId", resourceId, "resourceType", resourceType, "action", action));
    }

    public void logPhiQuery(User user, String query, int resultCount, List<String> documentIds) {
        this.logEvent(AuditEventType.PHI_QUERY, user, Map.of("query", this.sanitizeQuery(query), "resultCount", resultCount, "documentIds", documentIds.size() > 10 ? documentIds.subList(0, 10) : documentIds));
    }

    public void logPhiDisclosure(User user, String recipientOrg, String purpose, List<String> resourceIds) {
        this.logEvent(AuditEventType.PHI_DISCLOSURE, user, Map.of("recipientOrganization", recipientOrg, "purpose", purpose, "resourceIds", resourceIds, "disclosureTimestamp", Instant.now().toString()));
    }

    public void logAuthentication(String username, boolean success, String ipAddress, String reason) {
        AuditEventType type = success ? AuditEventType.AUTH_SUCCESS : AuditEventType.AUTH_FAILURE;
        HipaaAuditEvent event = new HipaaAuditEvent(type, username, null, ipAddress, Map.of("success", success, "reason", reason != null ? reason : ""));
        this.saveEvent(event);
    }

    public void logPermissionDenied(User user, String resource, String requiredPermission) {
        this.logEvent(AuditEventType.PERMISSION_DENIED, user, Map.of("resource", resource, "requiredPermission", requiredPermission, "userRoles", user.getRoles().toString()));
    }

    public void logPhiAccess(String userId, String action, String resourceId, String reason, boolean breakTheGlass) {
        HipaaAuditEvent event = new HipaaAuditEvent(AuditEventType.PHI_ACCESS, userId, null, null, Map.of("action", action, "resourceId", resourceId, "reason", reason, "breakTheGlass", breakTheGlass));
        this.saveEvent(event);
        if (breakTheGlass) {
            log.warn("BREAK-THE-GLASS: PHI access by {} for resource {} - Reason: {}", new Object[]{userId, resourceId, reason});
        }
    }

    public void logBreakTheGlass(String userId, String resourceId, String patientId, String emergencyReason) {
        HipaaAuditEvent event = new HipaaAuditEvent(AuditEventType.PHI_ACCESS, userId, null, null, Map.of("action", "BREAK_THE_GLASS", "resourceId", resourceId, "patientId", patientId, "emergencyReason", emergencyReason, "requiresReview", true));
        this.saveEvent(event);
        log.error("!!! BREAK-THE-GLASS EMERGENCY ACCESS !!!");
        log.error("User: {}, Patient: {}, Reason: {}", new Object[]{userId, patientId, emergencyReason});
    }

    private void logEvent(AuditEventType type, User user, Map<String, Object> details) {
        HipaaAuditEvent event = new HipaaAuditEvent(type, user.getUsername(), user.getId(), null, details);
        this.saveEvent(event);
    }

    private void saveEvent(HipaaAuditEvent event) {
        try {
            this.mongoTemplate.save(event, COLLECTION_NAME);
            log.debug("HIPAA audit: {} by {} - {}", new Object[]{event.eventType(), event.username(), event.details()});
        }
        catch (Exception e) {
            log.error("HIPAA AUDIT FAILURE - Event: {}, User: {}, Error: {}", new Object[]{event.eventType(), event.username(), e.getMessage()});
        }
    }

    private String sanitizeQuery(String query) {
        if (query == null) {
            return "";
        }
        String sanitized = query.length() > 200 ? query.substring(0, 200) + "..." : query;
        sanitized = sanitized.replaceAll("\\d{3}-\\d{2}-\\d{4}", "[SSN-REDACTED]");
        sanitized = sanitized.replaceAll("\\b[A-Z]{2,3}\\d{6,10}\\b", "[MRN-REDACTED]");
        return sanitized;
    }

    public static enum AuditEventType {
        PHI_ACCESS,
        PHI_CREATE,
        PHI_MODIFY,
        PHI_DELETE,
        PHI_DISCLOSURE,
        PHI_EXPORT,
        PHI_PRINT,
        PHI_QUERY,
        AUTH_SUCCESS,
        AUTH_FAILURE,
        PERMISSION_DENIED;

    }

    public record HipaaAuditEvent(AuditEventType eventType, String username, String userId, String ipAddress, Map<String, Object> details, Instant timestamp, String id) {
        public HipaaAuditEvent(AuditEventType eventType, String username, String userId, String ipAddress, Map<String, Object> details) {
            this(eventType, username, userId, ipAddress, details, Instant.now(), null);
        }
    }
}
