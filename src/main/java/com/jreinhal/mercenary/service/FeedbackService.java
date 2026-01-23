package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.model.Feedback;
import com.jreinhal.mercenary.repository.FeedbackRepository;
import com.jreinhal.mercenary.service.AuditService;
import com.jreinhal.mercenary.util.LogSanitizer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
public class FeedbackService {
    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);
    private final FeedbackRepository feedbackRepository;
    private final AuditService auditService;

    public FeedbackService(FeedbackRepository feedbackRepository, AuditService auditService) {
        this.feedbackRepository = feedbackRepository;
        this.auditService = auditService;
    }

    public FeedbackResult submitPositiveFeedback(String userId, String username, String sector, String messageId, String query, String response, Map<String, Object> ragMetadata) {
        Feedback existing = this.feedbackRepository.findByUserIdAndMessageId(userId, messageId);
        if (existing != null) {
            if (existing.getFeedbackType() == Feedback.FeedbackType.POSITIVE) {
                this.feedbackRepository.delete(existing);
                log.info("User {} toggled off positive feedback for message {}", userId, messageId);
                return FeedbackResult.removed();
            }
            this.feedbackRepository.delete(existing);
            log.info("User {} switched from negative to positive feedback for message {}", userId, messageId);
        }
        Feedback feedback = Feedback.positive(userId, messageId, query, response).withUserContext(username, sector, null);
        if (ragMetadata != null) {
            feedback.withRagMetadata((String)ragMetadata.get("routingDecision"), this.castToStringList(ragMetadata.get("sourceDocuments")), this.getDoubleOrDefault(ragMetadata, "similarityThreshold", 0.0), this.getIntOrDefault(ragMetadata, "topK", 5), this.getMapOrEmpty(ragMetadata, "signals"));
            feedback.withMetrics(this.getLongOrDefault(ragMetadata, "responseTimeMs", 0L), this.getIntOrDefault(ragMetadata, "reasoningSteps", 0), this.getDoubleOrNull(ragMetadata, "hallucinationScore"));
        }
        Feedback saved = (Feedback)this.feedbackRepository.save(feedback);
        log.info("Positive feedback recorded: user={}, sector={}, messageId={}", new Object[]{userId, sector, messageId});
        return FeedbackResult.success(saved.getId(), Feedback.FeedbackType.POSITIVE);
    }

    public FeedbackResult submitNegativeFeedback(String userId, String username, String sector, String messageId, String query, String response, Feedback.FeedbackCategory category, String comments, Map<String, Object> ragMetadata) {
        Feedback existing = this.feedbackRepository.findByUserIdAndMessageId(userId, messageId);
        if (existing != null) {
            if (existing.getFeedbackType() == Feedback.FeedbackType.NEGATIVE && existing.getCategory() == category) {
                this.feedbackRepository.delete(existing);
                log.info("User {} toggled off negative feedback for message {}", userId, messageId);
                return FeedbackResult.removed();
            }
            this.feedbackRepository.delete(existing);
            log.info("User {} replacing previous feedback for message {}", userId, messageId);
        }
        Feedback feedback = Feedback.negative(userId, messageId, query, response, category).withUserContext(username, sector, null).withComments(comments);
        if (ragMetadata != null) {
            feedback.withRagMetadata((String)ragMetadata.get("routingDecision"), this.castToStringList(ragMetadata.get("sourceDocuments")), this.getDoubleOrDefault(ragMetadata, "similarityThreshold", 0.0), this.getIntOrDefault(ragMetadata, "topK", 5), this.getMapOrEmpty(ragMetadata, "signals"));
            feedback.withMetrics(this.getLongOrDefault(ragMetadata, "responseTimeMs", 0L), this.getIntOrDefault(ragMetadata, "reasoningSteps", 0), this.getDoubleOrNull(ragMetadata, "hallucinationScore"));
        }
        Feedback saved = (Feedback)this.feedbackRepository.save(feedback);
        log.warn("Negative feedback recorded: user={}, sector={}, category={}, messageId={}", new Object[]{userId, sector, category, messageId});
        if (category == Feedback.FeedbackCategory.HALLUCINATION) {
            log.error("HALLUCINATION REPORTED: messageId={}, query={}", messageId, LogSanitizer.querySummary(query));
        }
        return FeedbackResult.success(saved.getId(), Feedback.FeedbackType.NEGATIVE);
    }

    public FeedbackAnalytics getAnalytics(String sector, int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        List<Feedback> recentFeedback = sector != null ? this.feedbackRepository.findBySectorAndTimestampBetween(sector, since, Instant.now()) : this.feedbackRepository.findByTimestampBetween(since, Instant.now());
        long positive = recentFeedback.stream().filter(f -> f.getFeedbackType() == Feedback.FeedbackType.POSITIVE).count();
        long negative = recentFeedback.stream().filter(f -> f.getFeedbackType() == Feedback.FeedbackType.NEGATIVE).count();
        long total = positive + negative;
        double satisfactionRate = total > 0L ? (double)positive / (double)total * 100.0 : 0.0;
        Map<Feedback.FeedbackCategory, Long> categoryBreakdown = recentFeedback.stream().filter(f -> f.getFeedbackType() == Feedback.FeedbackType.NEGATIVE && f.getCategory() != null).collect(Collectors.groupingBy(Feedback::getCategory, Collectors.counting()));
        long openIssues = recentFeedback.stream().filter(f -> f.getResolutionStatus() == Feedback.ResolutionStatus.OPEN).count();
        double avgResponseTimePositive = recentFeedback.stream().filter(f -> f.getFeedbackType() == Feedback.FeedbackType.POSITIVE && f.getResponseTimeMs() > 0L).mapToLong(Feedback::getResponseTimeMs).average().orElse(0.0);
        double avgResponseTimeNegative = recentFeedback.stream().filter(f -> f.getFeedbackType() == Feedback.FeedbackType.NEGATIVE && f.getResponseTimeMs() > 0L).mapToLong(Feedback::getResponseTimeMs).average().orElse(0.0);
        List<String> topIssueQueries = recentFeedback.stream().filter(f -> f.getFeedbackType() == Feedback.FeedbackType.NEGATIVE).sorted((a, b) -> b.getTimestamp().compareTo(a.getTimestamp())).limit(10L).map(Feedback::getQuery).filter(Objects::nonNull).collect(Collectors.toList());
        return new FeedbackAnalytics(total, positive, negative, satisfactionRate, categoryBreakdown, openIssues, avgResponseTimePositive, avgResponseTimeNegative, topIssueQueries, days, sector);
    }

    public Page<Feedback> getOpenIssues(int page, int size) {
        return this.feedbackRepository.findByFeedbackTypeAndResolutionStatus(Feedback.FeedbackType.NEGATIVE, Feedback.ResolutionStatus.OPEN, (Pageable)PageRequest.of((int)page, (int)size, (Sort)Sort.by((Sort.Direction)Sort.Direction.DESC, (String[])new String[]{"timestamp"})));
    }

    public List<Feedback> getHallucinationReports() {
        return this.feedbackRepository.findByCategoryAndResolutionStatusOrderByTimestampDesc(Feedback.FeedbackCategory.HALLUCINATION, Feedback.ResolutionStatus.OPEN);
    }

    public Feedback resolveIssue(String feedbackId, String resolvedBy, String notes) {
        Feedback feedback = (Feedback)this.feedbackRepository.findById(feedbackId).orElseThrow(() -> new IllegalArgumentException("Feedback not found: " + feedbackId));
        feedback.resolve(resolvedBy, notes);
        return (Feedback)this.feedbackRepository.save(feedback);
    }

    public List<TrainingExample> exportTrainingData(String sector, Feedback.FeedbackType type) {
        List<Feedback> feedback = sector != null ? this.feedbackRepository.findByFeedbackTypeAndSector(type, sector) : this.feedbackRepository.findByFeedbackTypeOrderByTimestampDesc(type);
        return feedback.stream().filter(f -> f.getQuery() != null && f.getResponse() != null).map(f -> new TrainingExample(f.getQuery(), f.getResponse(), f.getSector(), f.getSourceDocuments(), f.getFeedbackType() == Feedback.FeedbackType.POSITIVE ? 1.0 : 0.0)).collect(Collectors.toList());
    }

    private List<String> castToStringList(Object obj) {
        if (obj instanceof List) {
            return ((List<?>)obj).stream().filter(String.class::isInstance).map(String.class::cast).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private Map<String, Object> getMapOrEmpty(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Map<?, ?> mapVal) {
            Map<String, Object> result = new HashMap<>();
            for (Map.Entry<?, ?> entry : mapVal.entrySet()) {
                if (entry.getKey() instanceof String) {
                    result.put((String)entry.getKey(), entry.getValue());
                }
            }
            return result;
        }
        return Collections.emptyMap();
    }

    private double getDoubleOrDefault(Map<String, Object> map, String key, double def) {
        Object val = map.get(key);
        if (val instanceof Number) {
            return ((Number)val).doubleValue();
        }
        return def;
    }

    private Double getDoubleOrNull(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number) {
            return ((Number)val).doubleValue();
        }
        return null;
    }

    private int getIntOrDefault(Map<String, Object> map, String key, int def) {
        Object val = map.get(key);
        if (val instanceof Number) {
            return ((Number)val).intValue();
        }
        return def;
    }

    private long getLongOrDefault(Map<String, Object> map, String key, long def) {
        Object val = map.get(key);
        if (val instanceof Number) {
            return ((Number)val).longValue();
        }
        return def;
    }

    public static class FeedbackResult {
        private final boolean success;
        private final String feedbackId;
        private final Feedback.FeedbackType type;
        private final boolean removed;

        private FeedbackResult(boolean success, String feedbackId, Feedback.FeedbackType type, boolean removed) {
            this.success = success;
            this.feedbackId = feedbackId;
            this.type = type;
            this.removed = removed;
        }

        public static FeedbackResult success(String id, Feedback.FeedbackType type) {
            return new FeedbackResult(true, id, type, false);
        }

        public static FeedbackResult removed() {
            return new FeedbackResult(true, null, null, true);
        }

        public boolean isSuccess() {
            return this.success;
        }

        public String getFeedbackId() {
            return this.feedbackId;
        }

        public Feedback.FeedbackType getType() {
            return this.type;
        }

        public boolean isRemoved() {
            return this.removed;
        }
    }

    public static class FeedbackAnalytics {
        private final long totalFeedback;
        private final long positiveFeedback;
        private final long negativeFeedback;
        private final double satisfactionRate;
        private final Map<Feedback.FeedbackCategory, Long> categoryBreakdown;
        private final long openIssues;
        private final double avgResponseTimePositive;
        private final double avgResponseTimeNegative;
        private final List<String> topIssueQueries;
        private final int periodDays;
        private final String sector;

        public FeedbackAnalytics(long total, long positive, long negative, double satisfactionRate, Map<Feedback.FeedbackCategory, Long> categoryBreakdown, long openIssues, double avgRtPositive, double avgRtNegative, List<String> topIssueQueries, int days, String sector) {
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

        public long getTotalFeedback() {
            return this.totalFeedback;
        }

        public long getPositiveFeedback() {
            return this.positiveFeedback;
        }

        public long getNegativeFeedback() {
            return this.negativeFeedback;
        }

        public double getSatisfactionRate() {
            return this.satisfactionRate;
        }

        public Map<Feedback.FeedbackCategory, Long> getCategoryBreakdown() {
            return this.categoryBreakdown;
        }

        public long getOpenIssues() {
            return this.openIssues;
        }

        public double getAvgResponseTimePositive() {
            return this.avgResponseTimePositive;
        }

        public double getAvgResponseTimeNegative() {
            return this.avgResponseTimeNegative;
        }

        public List<String> getTopIssueQueries() {
            return this.topIssueQueries;
        }

        public int getPeriodDays() {
            return this.periodDays;
        }

        public String getSector() {
            return this.sector;
        }
    }

    public static class TrainingExample {
        private final String query;
        private final String response;
        private final String sector;
        private final List<String> sources;
        private final double reward;

        public TrainingExample(String query, String response, String sector, List<String> sources, double reward) {
            this.query = query;
            this.response = response;
            this.sector = sector;
            this.sources = sources;
            this.reward = reward;
        }

        public String getQuery() {
            return this.query;
        }

        public String getResponse() {
            return this.response;
        }

        public String getSector() {
            return this.sector;
        }

        public List<String> getSources() {
            return this.sources;
        }

        public double getReward() {
            return this.reward;
        }
    }
}
