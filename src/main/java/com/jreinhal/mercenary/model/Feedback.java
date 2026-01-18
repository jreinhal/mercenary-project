package com.jreinhal.mercenary.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * User feedback on RAG responses for quality improvement and compliance.
 *
 * This data enables:
 * - RLHF-style training signal collection
 * - Quality metrics dashboards
 * - Hallucination/accuracy issue triage
 * - Compliance evidence for auditors
 */
@Document(collection = "feedback")
@CompoundIndexes({
    @CompoundIndex(name = "sector_type_idx", def = "{'sector': 1, 'feedbackType': 1}"),
    @CompoundIndex(name = "user_time_idx", def = "{'userId': 1, 'timestamp': -1}")
})
public class Feedback {

    @Id
    private String id;

    @Indexed
    private Instant timestamp;

    // User context
    @Indexed
    private String userId;
    private String username;
    @Indexed
    private String sector;
    private String sessionId;

    // Feedback classification
    @Indexed
    private FeedbackType feedbackType;
    @Indexed
    private FeedbackCategory category;  // For negative feedback
    private String additionalComments;

    // Query/Response context (for training value)
    private String messageId;
    private String query;
    private String response;
    private int responseLength;

    // RAG pipeline metadata (for debugging issues)
    private String routingDecision;  // CHUNK, DOCUMENT, NO_RETRIEVAL
    private List<String> sourceDocuments;
    private double similarityThreshold;
    private int topK;
    private Map<String, Object> ragSignals;  // isHyde, isMultiHop, hasNamedEntity

    // Processing metrics
    private long responseTimeMs;
    private int reasoningSteps;
    private Double hallucinationScore;

    // Resolution tracking (for issue management)
    @Indexed
    private ResolutionStatus resolutionStatus;
    private String resolvedBy;
    private Instant resolvedAt;
    private String resolutionNotes;

    public Feedback() {
        this.timestamp = Instant.now();
        this.resolutionStatus = ResolutionStatus.OPEN;
    }

    // Static factory methods
    public static Feedback positive(String userId, String messageId, String query, String response) {
        Feedback fb = new Feedback();
        fb.userId = userId;
        fb.messageId = messageId;
        fb.query = truncate(query, 1000);
        fb.response = truncate(response, 5000);
        fb.responseLength = response != null ? response.length() : 0;
        fb.feedbackType = FeedbackType.POSITIVE;
        fb.resolutionStatus = ResolutionStatus.NOT_APPLICABLE;
        return fb;
    }

    public static Feedback negative(String userId, String messageId, String query, String response,
                                    FeedbackCategory category) {
        Feedback fb = new Feedback();
        fb.userId = userId;
        fb.messageId = messageId;
        fb.query = truncate(query, 1000);
        fb.response = truncate(response, 5000);
        fb.responseLength = response != null ? response.length() : 0;
        fb.feedbackType = FeedbackType.NEGATIVE;
        fb.category = category;
        fb.resolutionStatus = ResolutionStatus.OPEN;
        return fb;
    }

    // Fluent setters
    public Feedback withUserContext(String username, String sector, String sessionId) {
        this.username = username;
        this.sector = sector;
        this.sessionId = sessionId;
        return this;
    }

    public Feedback withRagMetadata(String routingDecision, List<String> sourceDocuments,
                                    double similarityThreshold, int topK,
                                    Map<String, Object> ragSignals) {
        this.routingDecision = routingDecision;
        this.sourceDocuments = sourceDocuments;
        this.similarityThreshold = similarityThreshold;
        this.topK = topK;
        this.ragSignals = ragSignals;
        return this;
    }

    public Feedback withMetrics(long responseTimeMs, int reasoningSteps, Double hallucinationScore) {
        this.responseTimeMs = responseTimeMs;
        this.reasoningSteps = reasoningSteps;
        this.hallucinationScore = hallucinationScore;
        return this;
    }

    public Feedback withComments(String comments) {
        this.additionalComments = truncate(comments, 2000);
        return this;
    }

    public void resolve(String resolvedBy, String notes) {
        this.resolutionStatus = ResolutionStatus.RESOLVED;
        this.resolvedBy = resolvedBy;
        this.resolvedAt = Instant.now();
        this.resolutionNotes = notes;
    }

    public void markInProgress(String assignee) {
        this.resolutionStatus = ResolutionStatus.IN_PROGRESS;
        this.resolvedBy = assignee;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    // Getters
    public String getId() { return id; }
    public Instant getTimestamp() { return timestamp; }
    public String getUserId() { return userId; }
    public String getUsername() { return username; }
    public String getSector() { return sector; }
    public String getSessionId() { return sessionId; }
    public FeedbackType getFeedbackType() { return feedbackType; }
    public FeedbackCategory getCategory() { return category; }
    public String getAdditionalComments() { return additionalComments; }
    public String getMessageId() { return messageId; }
    public String getQuery() { return query; }
    public String getResponse() { return response; }
    public int getResponseLength() { return responseLength; }
    public String getRoutingDecision() { return routingDecision; }
    public List<String> getSourceDocuments() { return sourceDocuments; }
    public double getSimilarityThreshold() { return similarityThreshold; }
    public int getTopK() { return topK; }
    public Map<String, Object> getRagSignals() { return ragSignals; }
    public long getResponseTimeMs() { return responseTimeMs; }
    public int getReasoningSteps() { return reasoningSteps; }
    public Double getHallucinationScore() { return hallucinationScore; }
    public ResolutionStatus getResolutionStatus() { return resolutionStatus; }
    public String getResolvedBy() { return resolvedBy; }
    public Instant getResolvedAt() { return resolvedAt; }
    public String getResolutionNotes() { return resolutionNotes; }

    // Setters for deserialization
    public void setUserId(String userId) { this.userId = userId; }
    public void setUsername(String username) { this.username = username; }
    public void setSector(String sector) { this.sector = sector; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public void setFeedbackType(FeedbackType feedbackType) { this.feedbackType = feedbackType; }
    public void setCategory(FeedbackCategory category) { this.category = category; }
    public void setAdditionalComments(String additionalComments) { this.additionalComments = additionalComments; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public void setQuery(String query) { this.query = query; }
    public void setResponse(String response) { this.response = response; }
    public void setResponseLength(int responseLength) { this.responseLength = responseLength; }
    public void setRoutingDecision(String routingDecision) { this.routingDecision = routingDecision; }
    public void setSourceDocuments(List<String> sourceDocuments) { this.sourceDocuments = sourceDocuments; }
    public void setSimilarityThreshold(double similarityThreshold) { this.similarityThreshold = similarityThreshold; }
    public void setTopK(int topK) { this.topK = topK; }
    public void setRagSignals(Map<String, Object> ragSignals) { this.ragSignals = ragSignals; }
    public void setResponseTimeMs(long responseTimeMs) { this.responseTimeMs = responseTimeMs; }
    public void setReasoningSteps(int reasoningSteps) { this.reasoningSteps = reasoningSteps; }
    public void setHallucinationScore(Double hallucinationScore) { this.hallucinationScore = hallucinationScore; }

    /**
     * Type of feedback (binary signal for RLHF).
     */
    public enum FeedbackType {
        POSITIVE,   // Thumbs up - response was helpful
        NEGATIVE    // Thumbs down - response had issues
    }

    /**
     * Categories for negative feedback (actionable classification).
     */
    public enum FeedbackCategory {
        INACCURATE_CITATION("Inaccurate Citation", "Citation doesn't match source content"),
        HALLUCINATION("Hallucination", "Made-up information not in source documents"),
        OUTDATED_INFO("Outdated Information", "Information is stale or superseded"),
        INCOMPLETE("Incomplete Response", "Missing relevant information from corpus"),
        WRONG_SOURCES("Wrong Sources", "Retrieved irrelevant documents"),
        FORMATTING("Formatting Issue", "Response poorly structured or hard to read"),
        TOO_SLOW("Too Slow", "Response time unacceptable"),
        OTHER("Other", "Other issue not categorized above");

        private final String displayName;
        private final String description;

        FeedbackCategory(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
    }

    /**
     * Resolution status for issue tracking.
     */
    public enum ResolutionStatus {
        OPEN,           // New issue, needs review
        IN_PROGRESS,    // Being investigated
        RESOLVED,       // Fixed or addressed
        WONT_FIX,       // Acknowledged but won't address
        NOT_APPLICABLE  // For positive feedback
    }
}
