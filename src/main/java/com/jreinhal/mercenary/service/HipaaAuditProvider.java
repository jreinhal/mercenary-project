package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.model.User;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Edition-safe abstraction over HIPAA audit logging.
 * <p>
 * The concrete implementation ({@code HipaaAuditService}) lives in the
 * {@code medical} package, which is excluded from Trial and Professional
 * builds.  Code in {@code core}, {@code service}, or {@code professional}
 * that needs HIPAA audit capabilities should depend on this interface
 * (injected with {@code @Autowired(required = false)}) so it compiles
 * cleanly in every edition.
 */
public interface HipaaAuditProvider {

    // ---- nested types mirrored from HipaaAuditService ----

    enum AuditEventType {
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
        PERMISSION_DENIED
    }

    record HipaaAuditEvent(
            AuditEventType eventType,
            String username,
            String userId,
            String ipAddress,
            String workspaceId,
            Map<String, Object> details,
            Instant timestamp,
            String id) {

        public HipaaAuditEvent(AuditEventType eventType, String username,
                               String userId, String ipAddress,
                               String workspaceId, Map<String, Object> details) {
            this(eventType, username, userId, ipAddress, workspaceId,
                    details, Instant.now(), null);
        }
    }

    // ---- methods used by RagOrchestrationService ----

    void logPhiQuery(User user, String query, int resultCount, List<String> documentIds);

    // ---- methods used by ReportingAdminController ----

    List<HipaaAuditEvent> queryEvents(Optional<Instant> since,
                                      Optional<Instant> until,
                                      Optional<AuditEventType> type,
                                      int limit);
}
