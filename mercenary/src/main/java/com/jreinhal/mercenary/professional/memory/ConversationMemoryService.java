package com.jreinhal.mercenary.professional.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Conversation memory service for maintaining context across interactions.
 *
 * PROFESSIONAL EDITION - Available in professional, medical, and government builds.
 *
 * Features:
 * - Per-user conversation history
 * - Session-based context management
 * - Topic extraction and tracking
 * - Relevant context retrieval for follow-up questions
 * - Configurable retention policies
 */
@Service
public class ConversationMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemoryService.class);

    private static final String COLLECTION_NAME = "conversation_memory";

    private final MongoTemplate mongoTemplate;

    // Maximum messages to include in context
    private static final int MAX_CONTEXT_MESSAGES = 10;

    // Maximum age of messages to consider (24 hours default)
    private static final long MAX_MESSAGE_AGE_HOURS = 24;

    public ConversationMemoryService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Message role in conversation.
     */
    public enum MessageRole {
        USER,
        ASSISTANT,
        SYSTEM
    }

    /**
     * A single message in conversation history.
     */
    public record ConversationMessage(
        String id,
        String userId,
        String sessionId,
        MessageRole role,
        String content,
        Instant timestamp,
        Map<String, Object> metadata
    ) {}

    /**
     * Conversation context for RAG.
     */
    public record ConversationContext(
        List<ConversationMessage> recentMessages,
        List<String> activeTopics,
        Map<String, Object> sessionMetadata,
        String formattedContext
    ) {}

    /**
     * Save a user message to memory.
     */
    public void saveUserMessage(String userId, String sessionId, String content) {
        saveMessage(userId, sessionId, MessageRole.USER, content, Map.of());
    }

    /**
     * Save an assistant response to memory.
     */
    public void saveAssistantMessage(String userId, String sessionId, String content,
                                     List<String> sourceDocs) {
        Map<String, Object> metadata = new HashMap<>();
        if (sourceDocs != null && !sourceDocs.isEmpty()) {
            metadata.put("sourceDocs", sourceDocs);
        }
        saveMessage(userId, sessionId, MessageRole.ASSISTANT, content, metadata);
    }

    /**
     * Save a message to memory.
     */
    private void saveMessage(String userId, String sessionId, MessageRole role,
                            String content, Map<String, Object> metadata) {
        ConversationMessage message = new ConversationMessage(
            UUID.randomUUID().toString(),
            userId,
            sessionId,
            role,
            content,
            Instant.now(),
            metadata
        );

        try {
            mongoTemplate.save(message, COLLECTION_NAME);
            log.debug("Saved {} message for user {} in session {}", role, userId, sessionId);
        } catch (Exception e) {
            log.error("Failed to save conversation message: {}", e.getMessage());
        }
    }

    /**
     * Get conversation context for a user session.
     */
    public ConversationContext getContext(String userId, String sessionId) {
        List<ConversationMessage> messages = getRecentMessages(userId, sessionId);
        List<String> topics = extractTopics(messages);
        Map<String, Object> sessionMeta = getSessionMetadata(userId, sessionId);
        String formatted = formatContext(messages);

        return new ConversationContext(messages, topics, sessionMeta, formatted);
    }

    /**
     * Get recent messages for context.
     */
    private List<ConversationMessage> getRecentMessages(String userId, String sessionId) {
        Instant cutoff = Instant.now().minusSeconds(MAX_MESSAGE_AGE_HOURS * 3600);

        Query query = new Query();
        query.addCriteria(Criteria.where("userId").is(userId));
        query.addCriteria(Criteria.where("sessionId").is(sessionId));
        query.addCriteria(Criteria.where("timestamp").gte(cutoff));
        query.limit(MAX_CONTEXT_MESSAGES);
        query.with(org.springframework.data.domain.Sort.by(
            org.springframework.data.domain.Sort.Direction.DESC, "timestamp"
        ));

        try {
            List<ConversationMessage> messages = mongoTemplate.find(query, ConversationMessage.class, COLLECTION_NAME);
            // Reverse to get chronological order
            Collections.reverse(messages);
            return messages;
        } catch (Exception e) {
            log.error("Failed to retrieve conversation history: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Extract topics from conversation history.
     */
    private List<String> extractTopics(List<ConversationMessage> messages) {
        // Simple topic extraction based on noun phrases and repeated terms
        Map<String, Integer> termFrequency = new HashMap<>();

        for (ConversationMessage msg : messages) {
            if (msg.role() == MessageRole.USER) {
                // Extract potential topics from user messages
                String[] words = msg.content().toLowerCase()
                    .replaceAll("[^a-z0-9\\s]", " ")
                    .split("\\s+");

                for (String word : words) {
                    if (word.length() > 3 && !isStopWord(word)) {
                        termFrequency.merge(word, 1, Integer::sum);
                    }
                }
            }
        }

        // Return top terms as topics
        return termFrequency.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .limit(5)
            .map(Map.Entry::getKey)
            .toList();
    }

    /**
     * Check if word is a stop word.
     */
    private boolean isStopWord(String word) {
        Set<String> stopWords = Set.of(
            "the", "and", "for", "are", "but", "not", "you", "all", "can",
            "had", "her", "was", "one", "our", "out", "has", "have", "been",
            "would", "could", "should", "what", "when", "where", "which", "their",
            "there", "this", "that", "with", "from", "they", "will", "about"
        );
        return stopWords.contains(word);
    }

    /**
     * Get session metadata.
     */
    private Map<String, Object> getSessionMetadata(String userId, String sessionId) {
        Map<String, Object> metadata = new HashMap<>();

        // Count messages in session
        Query countQuery = new Query();
        countQuery.addCriteria(Criteria.where("userId").is(userId));
        countQuery.addCriteria(Criteria.where("sessionId").is(sessionId));

        try {
            long count = mongoTemplate.count(countQuery, COLLECTION_NAME);
            metadata.put("messageCount", count);
        } catch (Exception e) {
            metadata.put("messageCount", 0);
        }

        return metadata;
    }

    /**
     * Format conversation context for inclusion in prompts.
     */
    private String formatContext(List<ConversationMessage> messages) {
        if (messages.isEmpty()) {
            return "";
        }

        StringBuilder context = new StringBuilder();
        context.append("Previous conversation:\n");

        for (ConversationMessage msg : messages) {
            String roleLabel = msg.role() == MessageRole.USER ? "User" : "Assistant";
            context.append(roleLabel).append(": ").append(truncate(msg.content(), 200)).append("\n");
        }

        return context.toString();
    }

    /**
     * Detect if a query is a follow-up question.
     */
    public boolean isFollowUp(String query) {
        String lower = query.toLowerCase();

        // Pronouns referring to previous context
        boolean hasPronouns = lower.matches(".*\\b(it|this|that|they|them|those|these)\\b.*");

        // Continuation words
        boolean hasContinuation = lower.startsWith("and ") ||
                                  lower.startsWith("also ") ||
                                  lower.startsWith("what about") ||
                                  lower.startsWith("how about") ||
                                  lower.startsWith("tell me more");

        // Very short queries often depend on context
        boolean isShort = query.length() < 30;

        return hasPronouns || hasContinuation || (isShort && !query.contains("?"));
    }

    /**
     * Expand a follow-up query using conversation context.
     */
    public String expandFollowUp(String query, ConversationContext context) {
        if (context.recentMessages().isEmpty()) {
            return query;
        }

        // Find the most recent user query to provide context
        String lastUserQuery = context.recentMessages().stream()
            .filter(m -> m.role() == MessageRole.USER)
            .reduce((first, second) -> second)
            .map(ConversationMessage::content)
            .orElse("");

        // Find the most recent assistant response
        String lastResponse = context.recentMessages().stream()
            .filter(m -> m.role() == MessageRole.ASSISTANT)
            .reduce((first, second) -> second)
            .map(ConversationMessage::content)
            .orElse("");

        // Build expanded query
        StringBuilder expanded = new StringBuilder();
        expanded.append("Context from previous interaction:\n");
        if (!lastUserQuery.isEmpty()) {
            expanded.append("Previous question: ").append(truncate(lastUserQuery, 150)).append("\n");
        }
        if (!lastResponse.isEmpty()) {
            expanded.append("Previous answer summary: ").append(truncate(lastResponse, 200)).append("\n");
        }
        if (!context.activeTopics().isEmpty()) {
            expanded.append("Topics being discussed: ").append(String.join(", ", context.activeTopics())).append("\n");
        }
        expanded.append("\nFollow-up question: ").append(query);

        return expanded.toString();
    }

    /**
     * Clear conversation history for a session.
     */
    public void clearSession(String userId, String sessionId) {
        Query query = new Query();
        query.addCriteria(Criteria.where("userId").is(userId));
        query.addCriteria(Criteria.where("sessionId").is(sessionId));

        try {
            mongoTemplate.remove(query, COLLECTION_NAME);
            log.info("Cleared conversation history for user {} session {}", userId, sessionId);
        } catch (Exception e) {
            log.error("Failed to clear conversation history: {}", e.getMessage());
        }
    }

    /**
     * Delete old conversations based on retention policy.
     */
    public long purgeOldConversations(int retentionDays) {
        Instant cutoff = Instant.now().minusSeconds(retentionDays * 24L * 3600L);

        Query query = new Query();
        query.addCriteria(Criteria.where("timestamp").lt(cutoff));

        try {
            var result = mongoTemplate.remove(query, COLLECTION_NAME);
            long deleted = result.getDeletedCount();
            log.info("Purged {} old conversation messages (older than {} days)", deleted, retentionDays);
            return deleted;
        } catch (Exception e) {
            log.error("Failed to purge old conversations: {}", e.getMessage());
            return 0;
        }
    }

    private String truncate(String str, int maxLen) {
        if (str == null) return "";
        return str.length() <= maxLen ? str : str.substring(0, maxLen) + "...";
    }
}
