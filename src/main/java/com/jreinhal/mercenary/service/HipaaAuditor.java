package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.model.User;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Edition-safe interface for HIPAA audit logging.
 * The concrete implementation (HipaaAuditService) lives in the medical
 * package and is only available in medical and government editions.
 * Shared code should depend on this interface (injected as @Nullable)
 * so that trial and enterprise editions compile without the medical package.
 */
public interface HipaaAuditor {

    void logPhiQuery(User user, String query, int resultCount, List<String> documentIds);

    List<? extends AuditEvent> getRecentEvents(int limit);

    List<? extends AuditEvent> queryEvents(Optional<Instant> since, Optional<Instant> until,
                                           Optional<AuditEventType> type, int limit);

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

    interface AuditEvent {
        AuditEventType eventType();
        Instant timestamp();
        String username();
        String userId();
        String workspaceId();
        Map<String, Object> details();
    }
}
