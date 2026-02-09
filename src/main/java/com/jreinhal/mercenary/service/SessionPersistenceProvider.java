package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.reasoning.ReasoningTrace;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Core interface for session persistence operations.
 * Implementations live in edition-specific packages (e.g., enterprise.memory).
 * Controllers and services in core/ depend on this interface, not the concrete class,
 * to preserve edition isolation.
 */
public interface SessionPersistenceProvider {

    record ActiveSession(
            String sessionId,
            String userId,
            String workspaceId,
            String department,
            Instant createdAt,
            Instant lastActivityAt,
            int messageCount,
            int traceCount,
            List<String> traceIds,
            Map<String, Object> metadata
    ) {}

    record PersistedTrace(
            String traceId,
            String sessionId,
            String userId,
            String workspaceId,
            String department,
            String query,
            Instant timestamp,
            long durationMs,
            int stepCount,
            List<Map<String, Object>> steps,
            Map<String, Object> metrics,
            boolean completed,
            String integrityHash,
            String integrityKeyId
    ) {}

    record SessionExport(
            String sessionId,
            String userId,
            String workspaceId,
            String department,
            Instant startTime,
            Instant endTime,
            int totalMessages,
            int totalTraces,
            List<ConversationMemoryProvider.ConversationMessage> messages,
            List<PersistedTrace> traces,
            Map<String, Object> summary,
            String integrityHash,
            String integrityKeyId
    ) {}

    ActiveSession touchSession(String userId, String sessionId, String department);

    String generateSessionId();

    Optional<ActiveSession> getSession(String sessionId);

    List<ActiveSession> getUserSessions(String userId);

    void incrementMessageCount(String sessionId);

    void persistTrace(ReasoningTrace trace, String sessionId);

    Optional<PersistedTrace> getPersistedTrace(String traceId);

    List<PersistedTrace> getSessionTraces(String sessionId);

    Path exportSession(String sessionId, String userId) throws IOException;

    String exportSessionToJson(String sessionId, String userId) throws IOException;

    Map<String, Object> getStatistics();
}
