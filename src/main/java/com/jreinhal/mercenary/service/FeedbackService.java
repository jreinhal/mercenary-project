package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.model.Feedback;
import com.jreinhal.mercenary.model.Feedback.FeedbackType;
import com.jreinhal.mercenary.model.Feedback.FeedbackCategory;
import com.jreinhal.mercenary.model.Feedback.ResolutionStatus;
import com.jreinhal.mercenary.repository.FeedbackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for managing user feedback on RAG responses.
 *
 * Provides:
 * - Feedback submission and updates
 * - Analytics for quality metrics dashboards
 * - Training data export for model improvement
 * - Issue triage and resolution tracking
 */
@Service
public class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);

    private final FeedbackRepository feedbackRepository;
    private final AuditService auditService;

    public FeedbackService(FeedbackRepository feedbackRepository, AuditService auditService) {
        this.feedbackRepository = feedbackRepository;
        this.auditService = auditService;
    }

    /**
     * Submit positive feedback (thumbs up).
     * If user already gave feedback on this message, toggles it off or switches from negative.
     */
    public FeedbackResult submitPositiveFeedback(String userId, String username, String sector,
                                                  String messageId, String query, String response,
                                                  Map<String, Object> ragMetadata) {
        // Check for existing feedback
        Feedback existing = feedbackRepository.findByUserIdAndMessageId(userId, messageId);

        if (existing != null) {
            if (existing.getFeedbackType() == FeedbackType.POSITIVE) {
                // Toggle off - delete the feedback
                feedbackRepository.delete(existing);
                log.info("User {} toggled off positive feedback for message {}", userId, messageId);
                return FeedbackResult.removed();
            } else {
                // Switch from negative to positive
                feedbackRepository.delete(existing);
                log.info("User {} switched from negative to positive feedback for message {}", userId, messageId);
            }
        }

        Feedback feedback = Feedback.positive(userId, messageId, query, response)
                .withUserContext(username, sector, null);

        // Add RAG metadata if provided
        if (ragMetadata != null) {
            feedback.withRagMetadata(
                    (String) ragMetadata.get("routingDecision"),
                    castToStringList(ragMetadata.get("sourceDocuments")),
                    getDoubleOrDefault(ragMetadata, "similarityThreshold", 0.0),
                    getIntOrDefault(ragMetadata, "topK", 5),
                    getMapOrEmpty(ragMetadata, "signals")
            );
            feedback.withMetrics(
                    getLongOrDefault(ragMetadata, "responseTimeMs", 0L),
                    getIntOrDefault(ragMetadata, "reasoningSteps", 0),
                    getDoubleOrNull(ragMetadata, "hallucinationScore")
            );
        }

        Feedback saved = feedbackRepository.save(feedback);
        log.info("Positive feedback recorded: user={}, sector={}, messageId={}", userId, sector, messageId);

        return FeedbackResult.success(saved.getId(), FeedbackType.POSITIVE);
    }

    /**
     * Submit negative feedback (thumbs down) with category.
     */
    public FeedbackResult submitNegativeFeedback(String userId, String username, String sector,
                                                  String messageId, String query, String response,
                                                  FeedbackCategory category, String comments,
                                                  Map<String, Object> ragMetadata) {
        // Check for existing feedback
        Feedback existing = feedbackRepository.findByUserIdAndMessageId(userId, messageId);

        if (existing != null) {
            if (existing.getFeedbackType() == FeedbackType.NEGATIVE
                    && existing.getCategory() == category) {
                // Toggle off - delete the feedback
                feedbackRepository.delete(existing);
                log.info("User {} toggled off negative feedback for message {}", userId, messageId);
                return FeedbackResult.removed();
            } else {
                // Replace existing feedback
                feedbackRepository.delete(existing);
                log.info("User {} replacing previous feedback for message {}", userId, messageId);
            }
        }

        Feedback feedback = Feedback.negative(userId, messageId, query, response, category)
                .withUserContext(username, sector, null)
                .withComments(comments);

        // Add RAG metadata if provided
        if (ragMetadata != null) {
            feedback.withRagMetadata(
                    (String) ragMetadata.get("routingDecision"),
                    castToStringList(ragMetadata.get("sourceDocuments")),
                    getDoubleOrDefault(ragMetadata, "similarityThreshold", 0.0),
                    getIntOrDefault(ragMetadata, "topK", 5),
                    getMapOrEmpty(ragMetadata, "signals")
            );
            feedback.withMetrics(
                    getLongOrDefault(ragMetadata, "responseTimeMs", 0L),
                    getIntOrDefault(ragMetadata, "reasoningSteps", 0),
                    getDoubleOrNull(ragMetadata, "hallucinationScore")
            );
        }

        Feedback saved = feedbackRepository.save(feedback);
        log.warn("Negative feedback recorded: user={}, sector={}, category={}, messageId={}",
                userId, sector, category, messageId);

        // Log high-priority issues
        if (category == FeedbackCategory.HALLUCINATION) {
            log.error("HALLUCINATION REPORTED: messageId={}, query={}", messageId,
                    truncate(query, 100));
        }

        return FeedbackResult.success(saved.getId(), FeedbackType.NEGATIVE);
    }

    /**
     * Get analytics summary for dashboard.
     */
    public FeedbackAnalytics getAnalytics(String sector, int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);

        List<Feedback> recentFeedback = sector != null
                ? feedbackRepository.findBySectorAndTimestampBetween(sector, since, Instant.now())
                : feedbackRepository.findByTimestampBetween(since, Instant.now());

        long positive = recentFeedback.stream()
                .filter(f -> f.getFeedbackType() == FeedbackType.POSITIVE).count();
        long negative = recentFeedback.stream()
                .filter(f -> f.getFeedbackType() == FeedbackType.NEGATIVE).count();
        long total = positive + negative;

        // Calculate satisfaction rate
        double satisfactionRate = total > 0 ? (double) positive / total * 100 : 0;

        // Category breakdown for negative feedback
        Map<FeedbackCategory, Long> categoryBreakdown = recentFeedback.stream()
                .filter(f -> f.getFeedbackType() == FeedbackType.NEGATIVE && f.getCategory() != null)
                .collect(Collectors.groupingBy(Feedback::getCategory, Collectors.counting()));

        // Open issues count
        long openIssues = recentFeedback.stream()
                .filter(f -> f.getResolutionStatus() == ResolutionStatus.OPEN).count();

        // Average response time for negative vs positive
        double avgResponseTimePositive = recentFeedback.stream()
                .filter(f -> f.getFeedbackType() == FeedbackType.POSITIVE && f.getResponseTimeMs() > 0)
                .mapToLong(Feedback::getResponseTimeMs).average().orElse(0);

        double avgResponseTimeNegative = recentFeedback.stream()
                .filter(f -> f.getFeedbackType() == FeedbackType.NEGATIVE && f.getResponseTimeMs() > 0)
                .mapToLong(Feedback::getResponseTimeMs).average().orElse(0);

        // Top problematic queries (for review)
        List<String> topIssueQueries = recentFeedback.stream()
                .filter(f -> f.getFeedbackType() == FeedbackType.NEGATIVE)
                .sorted((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()))
                .limit(10)
                .map(Feedback::getQuery)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return new FeedbackAnalytics(
                total, positive, negative, satisfactionRate,
                categoryBreakdown, openIssues,
                avgResponseTimePositive, avgResponseTimeNegative,
                topIssueQueries, days, sector
        );
    }

    /**
     * Get open issues for triage (admin view).
     */
    public Page<Feedback> getOpenIssues(int page, int size) {
        return feedbackRepository.findByFeedbackTypeAndResolutionStatus(
                FeedbackType.NEGATIVE,
                ResolutionStatus.OPEN,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "timestamp"))
        );
    }

    /**
     * Get hallucination reports (high priority).
     */
    public List<Feedback> getHallucinationReports() {
        return feedbackRepository.findByCategoryAndResolutionStatusOrderByTimestampDesc(
                FeedbackCategory.HALLUCINATION, ResolutionStatus.OPEN);
    }

    /**
     * Resolve an issue.
     */
    public Feedback resolveIssue(String feedbackId, String resolvedBy, String notes) {
        Feedback feedback = feedbackRepository.findById(feedbackId)
                .orElseThrow(() -> new IllegalArgumentException("Feedback not found: " + feedbackId));

        feedback.resolve(resolvedBy, notes);
        return feedbackRepository.save(feedback);
    }

    /**
     * Export training data (positive examples for fine-tuning).
     */
    public List<TrainingExample> exportTrainingData(String sector, FeedbackType type) {
        List<Feedback> feedback = sector != null
                ? feedbackRepository.findByFeedbackTypeAndSector(type, sector)
                : feedbackRepository.findByFeedbackTypeOrderByTimestampDesc(type);

        return feedback.stream()
                .filter(f -> f.getQuery() != null && f.getResponse() != null)
                .map(f -> new TrainingExample(
                        f.getQuery(),
                        f.getResponse(),
                        f.getSector(),
                        f.getSourceDocuments(),
                        f.getFeedbackType() == FeedbackType.POSITIVE ? 1.0 : 0.0
                ))
                .collect(Collectors.toList());
    }

    // Helper methods
    @SuppressWarnings("unchecked")
    private List<String> castToStringList(Object obj) {
        if (obj instanceof List<?>) {
            return ((List<?>) obj).stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMapOrEmpty(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Map) {
            return (Map<String, Object>) val;
        }
        return Collections.emptyMap();
    }

    private double getDoubleOrDefault(Map<String, Object> map, String key, double def) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).doubleValue();
        return def;
    }

    private Double getDoubleOrNull(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).doubleValue();
        return null;
    }

    private int getIntOrDefault(Map<String, Object> map, String key, int def) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        return def;
    }

    private long getLongOrDefault(Map<String, Object> map, String key, long def) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).longValue();
        return def;
    }

    private String truncate(String s, int len) {
        if (s == null) return null;
        return s.length() > len ? s.substring(0, len) + "..." : s;
    }

    // Result classes
    public static class FeedbackResult {
        private final boolean success;
        private final String feedbackId;
        private final FeedbackType type;
        private final boolean removed;

        private FeedbackResult(boolean success, String feedbackId, FeedbackType type, boolean removed) {
            this.success = success;
            this.feedbackId = feedbackId;
            this.type = type;
            this.removed = removed;
        }

        public static FeedbackResult success(String id, FeedbackType type) {
            return new FeedbackResult(true, id, type, false);
        }

        public static FeedbackResult removed() {
            return new FeedbackResult(true, null, null, true);
        }

        public boolean isSuccess() { return success; }
        public String getFeedbackId() { return feedbackId; }
        public FeedbackType getType() { return type; }
        public boolean isRemoved() { return removed; }
    }

    public static class FeedbackAnalytics {
        private final long totalFeedback;
        private final long positiveFeedback;
        private final long negativeFeedback;
        private final double satisfactionRate;
        private final Map<FeedbackCategory, Long> categoryBreakdown;
        private final long openIssues;
        private final double avgResponseTimePositive;
        private final double avgResponseTimeNegative;
        private final List<String> topIssueQueries;
        private final int periodDays;
        private final String sector;

        public FeedbackAnalytics(long total, long positive, long negative, double satisfactionRate,
                                  Map<FeedbackCategory, Long> categoryBreakdown, long openIssues,
                                  double avgRtPositive, double avgRtNegative,
                                  List<String> topIssueQueries, int days, String sector) {
            this.totalFeedback = total;
            this.positiveFeedback = positive;
            this.negativeFeedback = negative;
            this.satisfactionRate = satisfactionRate;
            this.categoryBreakdown = categoryBreakdown;
            this.openIssues = openIssues;
            this.avgResponseTimePositive = avgRtPositive;
            this.avgResponseTimeNegative = avgRtNegative;
            this.topIssueQueries = topIssueQueries;
            this.periodDays = days;
            this.sector = sector;
        }

        // Getters
        public long getTotalFeedback() { return totalFeedback; }
        public long getPositiveFeedback() { return positiveFeedback; }
        public long getNegativeFeedback() { return negativeFeedback; }
        public double getSatisfactionRate() { return satisfactionRate; }
        public Map<FeedbackCategory, Long> getCategoryBreakdown() { return categoryBreakdown; }
        public long getOpenIssues() { return openIssues; }
        public double getAvgResponseTimePositive() { return avgResponseTimePositive; }
        public double getAvgResponseTimeNegative() { return avgResponseTimeNegative; }
        public List<String> getTopIssueQueries() { return topIssueQueries; }
        public int getPeriodDays() { return periodDays; }
        public String getSector() { return sector; }
    }

    public static class TrainingExample {
        private final String query;
        private final String response;
        private final String sector;
        private final List<String> sources;
        private final double reward;

        public TrainingExample(String query, String response, String sector,
                               List<String> sources, double reward) {
            this.query = query;
            this.response = response;
            this.sector = sector;
            this.sources = sources;
            this.reward = reward;
        }

        public String getQuery() { return query; }
        public String getResponse() { return response; }
        public String getSector() { return sector; }
        public List<String> getSources() { return sources; }
        public double getReward() { return reward; }
    }
}
