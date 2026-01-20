/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.mongodb.client.result.DeleteResult
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.data.domain.Sort
 *  org.springframework.data.domain.Sort$Direction
 *  org.springframework.data.mongodb.core.MongoTemplate
 *  org.springframework.data.mongodb.core.query.Criteria
 *  org.springframework.data.mongodb.core.query.CriteriaDefinition
 *  org.springframework.data.mongodb.core.query.Query
 *  org.springframework.stereotype.Service
 */
package com.jreinhal.mercenary.professional.memory;

import com.mongodb.client.result.DeleteResult;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.CriteriaDefinition;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

@Service
public class ConversationMemoryService {
    private static final Logger log = LoggerFactory.getLogger(ConversationMemoryService.class);
    private static final String COLLECTION_NAME = "conversation_memory";
    private final MongoTemplate mongoTemplate;
    private static final int MAX_CONTEXT_MESSAGES = 10;
    private static final long MAX_MESSAGE_AGE_HOURS = 24L;

    public ConversationMemoryService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public void saveUserMessage(String userId, String sessionId, String content) {
        this.saveMessage(userId, sessionId, MessageRole.USER, content, Map.of());
    }

    public void saveAssistantMessage(String userId, String sessionId, String content, List<String> sourceDocs) {
        HashMap<String, Object> metadata = new HashMap<String, Object>();
        if (sourceDocs != null && !sourceDocs.isEmpty()) {
            metadata.put("sourceDocs", sourceDocs);
        }
        this.saveMessage(userId, sessionId, MessageRole.ASSISTANT, content, metadata);
    }

    private void saveMessage(String userId, String sessionId, MessageRole role, String content, Map<String, Object> metadata) {
        ConversationMessage message = new ConversationMessage(UUID.randomUUID().toString(), userId, sessionId, role, content, Instant.now(), metadata);
        try {
            this.mongoTemplate.save(message, COLLECTION_NAME);
            log.debug("Saved {} message for user {} in session {}", new Object[]{role, userId, sessionId});
        }
        catch (Exception e) {
            log.error("Failed to save conversation message: {}", e.getMessage());
        }
    }

    public ConversationContext getContext(String userId, String sessionId) {
        List<ConversationMessage> messages = this.getRecentMessages(userId, sessionId);
        List<String> topics = this.extractTopics(messages);
        Map<String, Object> sessionMeta = this.getSessionMetadata(userId, sessionId);
        String formatted = this.formatContext(messages);
        return new ConversationContext(messages, topics, sessionMeta, formatted);
    }

    private List<ConversationMessage> getRecentMessages(String userId, String sessionId) {
        Instant cutoff = Instant.now().minusSeconds(86400L);
        Query query = new Query();
        query.addCriteria((CriteriaDefinition)Criteria.where((String)"userId").is(userId));
        query.addCriteria((CriteriaDefinition)Criteria.where((String)"sessionId").is(sessionId));
        query.addCriteria((CriteriaDefinition)Criteria.where((String)"timestamp").gte(cutoff));
        query.limit(10);
        query.with(Sort.by((Sort.Direction)Sort.Direction.DESC, (String[])new String[]{"timestamp"}));
        try {
            List messages = this.mongoTemplate.find(query, ConversationMessage.class, COLLECTION_NAME);
            Collections.reverse(messages);
            return messages;
        }
        catch (Exception e) {
            log.error("Failed to retrieve conversation history: {}", e.getMessage());
            return List.of();
        }
    }

    private List<String> extractTopics(List<ConversationMessage> messages) {
        HashMap<String, Integer> termFrequency = new HashMap<String, Integer>();
        for (ConversationMessage msg : messages) {
            String[] words;
            if (msg.role() != MessageRole.USER) continue;
            for (String word : words = msg.content().toLowerCase().replaceAll("[^a-z0-9\\s]", " ").split("\\s+")) {
                if (word.length() <= 3 || this.isStopWord(word)) continue;
                termFrequency.merge(word, 1, Integer::sum);
            }
        }
        return termFrequency.entrySet().stream().sorted((a, b) -> Integer.compare((Integer)b.getValue(), (Integer)a.getValue())).limit(5L).map(Map.Entry::getKey).toList();
    }

    private boolean isStopWord(String word) {
        Set<String> stopWords = Set.of("the", "and", "for", "are", "but", "not", "you", "all", "can", "had", "her", "was", "one", "our", "out", "has", "have", "been", "would", "could", "should", "what", "when", "where", "which", "their", "there", "this", "that", "with", "from", "they", "will", "about");
        return stopWords.contains(word);
    }

    private Map<String, Object> getSessionMetadata(String userId, String sessionId) {
        HashMap<String, Object> metadata = new HashMap<String, Object>();
        Query countQuery = new Query();
        countQuery.addCriteria((CriteriaDefinition)Criteria.where((String)"userId").is(userId));
        countQuery.addCriteria((CriteriaDefinition)Criteria.where((String)"sessionId").is(sessionId));
        try {
            long count = this.mongoTemplate.count(countQuery, COLLECTION_NAME);
            metadata.put("messageCount", count);
        }
        catch (Exception e) {
            metadata.put("messageCount", 0);
        }
        return metadata;
    }

    private String formatContext(List<ConversationMessage> messages) {
        if (messages.isEmpty()) {
            return "";
        }
        StringBuilder context = new StringBuilder();
        context.append("Previous conversation:\n");
        for (ConversationMessage msg : messages) {
            String roleLabel = msg.role() == MessageRole.USER ? "User" : "Assistant";
            context.append(roleLabel).append(": ").append(this.truncate(msg.content(), 200)).append("\n");
        }
        return context.toString();
    }

    public boolean isFollowUp(String query) {
        String lower = query.toLowerCase();
        boolean hasPronouns = lower.matches(".*\\b(it|this|that|they|them|those|these)\\b.*");
        boolean hasContinuation = lower.startsWith("and ") || lower.startsWith("also ") || lower.startsWith("what about") || lower.startsWith("how about") || lower.startsWith("tell me more");
        boolean isShort = query.length() < 30;
        return hasPronouns || hasContinuation || isShort && !query.contains("?");
    }

    public String expandFollowUp(String query, ConversationContext context) {
        if (context.recentMessages().isEmpty()) {
            return query;
        }
        String lastUserQuery = context.recentMessages().stream().filter(m -> m.role() == MessageRole.USER).reduce((first, second) -> second).map(ConversationMessage::content).orElse("");
        String lastResponse = context.recentMessages().stream().filter(m -> m.role() == MessageRole.ASSISTANT).reduce((first, second) -> second).map(ConversationMessage::content).orElse("");
        StringBuilder expanded = new StringBuilder();
        expanded.append("Context from previous interaction:\n");
        if (!lastUserQuery.isEmpty()) {
            expanded.append("Previous question: ").append(this.truncate(lastUserQuery, 150)).append("\n");
        }
        if (!lastResponse.isEmpty()) {
            expanded.append("Previous answer summary: ").append(this.truncate(lastResponse, 200)).append("\n");
        }
        if (!context.activeTopics().isEmpty()) {
            expanded.append("Topics being discussed: ").append(String.join((CharSequence)", ", context.activeTopics())).append("\n");
        }
        expanded.append("\nFollow-up question: ").append(query);
        return expanded.toString();
    }

    public void clearSession(String userId, String sessionId) {
        Query query = new Query();
        query.addCriteria((CriteriaDefinition)Criteria.where((String)"userId").is(userId));
        query.addCriteria((CriteriaDefinition)Criteria.where((String)"sessionId").is(sessionId));
        try {
            this.mongoTemplate.remove(query, COLLECTION_NAME);
            log.info("Cleared conversation history for user {} session {}", userId, sessionId);
        }
        catch (Exception e) {
            log.error("Failed to clear conversation history: {}", e.getMessage());
        }
    }

    public long purgeOldConversations(int retentionDays) {
        Instant cutoff = Instant.now().minusSeconds((long)retentionDays * 24L * 3600L);
        Query query = new Query();
        query.addCriteria((CriteriaDefinition)Criteria.where((String)"timestamp").lt(cutoff));
        try {
            DeleteResult result = this.mongoTemplate.remove(query, COLLECTION_NAME);
            long deleted = result.getDeletedCount();
            log.info("Purged {} old conversation messages (older than {} days)", deleted, retentionDays);
            return deleted;
        }
        catch (Exception e) {
            log.error("Failed to purge old conversations: {}", e.getMessage());
            return 0L;
        }
    }

    private String truncate(String str, int maxLen) {
        if (str == null) {
            return "";
        }
        return str.length() <= maxLen ? str : str.substring(0, maxLen) + "...";
    }

    public static enum MessageRole {
        USER,
        ASSISTANT,
        SYSTEM;

    }

    public record ConversationMessage(String id, String userId, String sessionId, MessageRole role, String content, Instant timestamp, Map<String, Object> metadata) {
    }

    public record ConversationContext(List<ConversationMessage> recentMessages, List<String> activeTopics, Map<String, Object> sessionMetadata, String formattedContext) {
    }
}
