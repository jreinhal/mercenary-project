/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.jreinhal.mercenary.model.Feedback
 *  com.jreinhal.mercenary.model.Feedback$FeedbackCategory
 *  com.jreinhal.mercenary.model.Feedback$FeedbackType
 *  com.jreinhal.mercenary.model.Feedback$ResolutionStatus
 *  org.springframework.data.annotation.Id
 *  org.springframework.data.mongodb.core.index.CompoundIndex
 *  org.springframework.data.mongodb.core.index.CompoundIndexes
 *  org.springframework.data.mongodb.core.index.Indexed
 *  org.springframework.data.mongodb.core.mapping.Document
 */
package com.jreinhal.mercenary.model;

import com.jreinhal.mercenary.model.Feedback;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/*
 * Exception performing whole class analysis ignored.
 */
@Document(collection="feedback")
@CompoundIndexes(value={@CompoundIndex(name="sector_type_idx", def="{'sector': 1, 'feedbackType': 1}"), @CompoundIndex(name="user_time_idx", def="{'userId': 1, 'timestamp': -1}")})
public class Feedback {
    @Id
    private String id;
    @Indexed
    private Instant timestamp = Instant.now();
    @Indexed
    private String userId;
    private String username;
    @Indexed
    private String sector;
    private String sessionId;
    @Indexed
    private FeedbackType feedbackType;
    @Indexed
    private FeedbackCategory category;
    private String additionalComments;
    private String messageId;
    private String query;
    private String response;
    private int responseLength;
    private String routingDecision;
    private List<String> sourceDocuments;
    private double similarityThreshold;
    private int topK;
    private Map<String, Object> ragSignals;
    private long responseTimeMs;
    private int reasoningSteps;
    private Double hallucinationScore;
    @Indexed
    private ResolutionStatus resolutionStatus = ResolutionStatus.OPEN;
    private String resolvedBy;
    private Instant resolvedAt;
    private String resolutionNotes;

    public static Feedback positive(String userId, String messageId, String query, String response) {
        Feedback fb = new Feedback();
        fb.userId = userId;
        fb.messageId = messageId;
        fb.query = Feedback.truncate((String)query, (int)1000);
        fb.response = Feedback.truncate((String)response, (int)5000);
        fb.responseLength = response != null ? response.length() : 0;
        fb.feedbackType = FeedbackType.POSITIVE;
        fb.resolutionStatus = ResolutionStatus.NOT_APPLICABLE;
        return fb;
    }

    public static Feedback negative(String userId, String messageId, String query, String response, FeedbackCategory category) {
        Feedback fb = new Feedback();
        fb.userId = userId;
        fb.messageId = messageId;
        fb.query = Feedback.truncate((String)query, (int)1000);
        fb.response = Feedback.truncate((String)response, (int)5000);
        fb.responseLength = response != null ? response.length() : 0;
        fb.feedbackType = FeedbackType.NEGATIVE;
        fb.category = category;
        fb.resolutionStatus = ResolutionStatus.OPEN;
        return fb;
    }

    public Feedback withUserContext(String username, String sector, String sessionId) {
        this.username = username;
        this.sector = sector;
        this.sessionId = sessionId;
        return this;
    }

    public Feedback withRagMetadata(String routingDecision, List<String> sourceDocuments, double similarityThreshold, int topK, Map<String, Object> ragSignals) {
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
        this.additionalComments = Feedback.truncate((String)comments, (int)2000);
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
        if (s == null) {
            return null;
        }
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    public String getId() {
        return this.id;
    }

    public Instant getTimestamp() {
        return this.timestamp;
    }

    public String getUserId() {
        return this.userId;
    }

    public String getUsername() {
        return this.username;
    }

    public String getSector() {
        return this.sector;
    }

    public String getSessionId() {
        return this.sessionId;
    }

    public FeedbackType getFeedbackType() {
        return this.feedbackType;
    }

    public FeedbackCategory getCategory() {
        return this.category;
    }

    public String getAdditionalComments() {
        return this.additionalComments;
    }

    public String getMessageId() {
        return this.messageId;
    }

    public String getQuery() {
        return this.query;
    }

    public String getResponse() {
        return this.response;
    }

    public int getResponseLength() {
        return this.responseLength;
    }

    public String getRoutingDecision() {
        return this.routingDecision;
    }

    public List<String> getSourceDocuments() {
        return this.sourceDocuments;
    }

    public double getSimilarityThreshold() {
        return this.similarityThreshold;
    }

    public int getTopK() {
        return this.topK;
    }

    public Map<String, Object> getRagSignals() {
        return this.ragSignals;
    }

    public long getResponseTimeMs() {
        return this.responseTimeMs;
    }

    public int getReasoningSteps() {
        return this.reasoningSteps;
    }

    public Double getHallucinationScore() {
        return this.hallucinationScore;
    }

    public ResolutionStatus getResolutionStatus() {
        return this.resolutionStatus;
    }

    public String getResolvedBy() {
        return this.resolvedBy;
    }

    public Instant getResolvedAt() {
        return this.resolvedAt;
    }

    public String getResolutionNotes() {
        return this.resolutionNotes;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void setSector(String sector) {
        this.sector = sector;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public void setFeedbackType(FeedbackType feedbackType) {
        this.feedbackType = feedbackType;
    }

    public void setCategory(FeedbackCategory category) {
        this.category = category;
    }

    public void setAdditionalComments(String additionalComments) {
        this.additionalComments = additionalComments;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public void setResponse(String response) {
        this.response = response;
    }

    public void setResponseLength(int responseLength) {
        this.responseLength = responseLength;
    }

    public void setRoutingDecision(String routingDecision) {
        this.routingDecision = routingDecision;
    }

    public void setSourceDocuments(List<String> sourceDocuments) {
        this.sourceDocuments = sourceDocuments;
    }

    public void setSimilarityThreshold(double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public void setRagSignals(Map<String, Object> ragSignals) {
        this.ragSignals = ragSignals;
    }

    public void setResponseTimeMs(long responseTimeMs) {
        this.responseTimeMs = responseTimeMs;
    }

    public void setReasoningSteps(int reasoningSteps) {
        this.reasoningSteps = reasoningSteps;
    }

    public void setHallucinationScore(Double hallucinationScore) {
        this.hallucinationScore = hallucinationScore;
    }
}

