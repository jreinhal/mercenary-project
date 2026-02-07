package com.jreinhal.mercenary.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Core interface for conversation memory operations.
 * Implementations live in edition-specific packages (e.g., professional.memory).
 * Controllers and services in core/ depend on this interface, not the concrete class,
 * to preserve edition isolation.
 */
public interface ConversationMemoryProvider {

    enum MessageRole {
        USER,
        ASSISTANT,
        SYSTEM
    }

    record ConversationMessage(
            String id,
            String userId,
            String sessionId,
            String workspaceId,
            MessageRole role,
            String content,
            Instant timestamp,
            Map<String, Object> metadata
    ) {}

    record ConversationContext(
            List<ConversationMessage> recentMessages,
            List<String> activeTopics,
            Map<String, Object> sessionMetadata,
            String formattedContext
    ) {}

    void saveUserMessage(String userId, String sessionId, String content);

    void saveAssistantMessage(String userId, String sessionId, String content, List<String> sourceDocs);

    ConversationContext getContext(String userId, String sessionId);

    boolean isFollowUp(String query);

    String expandFollowUp(String query, ConversationContext context);

    void clearSession(String userId, String sessionId);

    long purgeOldConversations(int retentionDays);
}
