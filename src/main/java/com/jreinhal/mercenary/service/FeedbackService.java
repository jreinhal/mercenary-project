/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.jreinhal.mercenary.model.Feedback
 *  com.jreinhal.mercenary.model.Feedback$FeedbackCategory
 *  com.jreinhal.mercenary.model.Feedback$FeedbackType
 *  com.jreinhal.mercenary.model.Feedback$ResolutionStatus
 *  com.jreinhal.mercenary.repository.FeedbackRepository
 *  com.jreinhal.mercenary.service.AuditService
 *  com.jreinhal.mercenary.service.FeedbackService
 *  com.jreinhal.mercenary.service.FeedbackService$FeedbackAnalytics
 *  com.jreinhal.mercenary.service.FeedbackService$FeedbackResult
 *  com.jreinhal.mercenary.service.FeedbackService$TrainingExample
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.data.domain.Page
 *  org.springframework.data.domain.PageRequest
 *  org.springframework.data.domain.Pageable
 *  org.springframework.data.domain.Sort
 *  org.springframework.data.domain.Sort$Direction
 *  org.springframework.stereotype.Service
 */
package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.model.Feedback;
import com.jreinhal.mercenary.repository.FeedbackRepository;
import com.jreinhal.mercenary.service.AuditService;
import com.jreinhal.mercenary.service.FeedbackService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
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

/*
 * Exception performing whole class analysis ignored.
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

    public FeedbackResult submitPositiveFeedback(String userId, String username, String sector, String messageId, String query, String response, Map<String, Object> ragMetadata) {
        Feedback existing = this.feedbackRepository.findByUserIdAndMessageId(userId, messageId);
        if (existing != null) {
            if (existing.getFeedbackType() == Feedback.FeedbackType.POSITIVE) {
                this.feedbackRepository.delete((Object)existing);
                log.info("User {} toggled off positive feedback for message {}", (Object)userId, (Object)messageId);
                return FeedbackResult.removed();
            }
            this.feedbackRepository.delete((Object)existing);
            log.info("User {} switched from negative to positive feedback for message {}", (Object)userId, (Object)messageId);
        }
        Feedback feedback = Feedback.positive((String)userId, (String)messageId, (String)query, (String)response).withUserContext(username, sector, null);
        if (ragMetadata != null) {
            feedback.withRagMetadata((String)ragMetadata.get("routingDecision"), this.castToStringList(ragMetadata.get("sourceDocuments")), this.getDoubleOrDefault(ragMetadata, "similarityThreshold", 0.0), this.getIntOrDefault(ragMetadata, "topK", 5), this.getMapOrEmpty(ragMetadata, "signals"));
            feedback.withMetrics(this.getLongOrDefault(ragMetadata, "responseTimeMs", 0L), this.getIntOrDefault(ragMetadata, "reasoningSteps", 0), this.getDoubleOrNull(ragMetadata, "hallucinationScore"));
        }
        Feedback saved = (Feedback)this.feedbackRepository.save((Object)feedback);
        log.info("Positive feedback recorded: user={}, sector={}, messageId={}", new Object[]{userId, sector, messageId});
        return FeedbackResult.success((String)saved.getId(), (Feedback.FeedbackType)Feedback.FeedbackType.POSITIVE);
    }

    public FeedbackResult submitNegativeFeedback(String userId, String username, String sector, String messageId, String query, String response, Feedback.FeedbackCategory category, String comments, Map<String, Object> ragMetadata) {
        Feedback existing = this.feedbackRepository.findByUserIdAndMessageId(userId, messageId);
        if (existing != null) {
            if (existing.getFeedbackType() == Feedback.FeedbackType.NEGATIVE && existing.getCategory() == category) {
                this.feedbackRepository.delete((Object)existing);
                log.info("User {} toggled off negative feedback for message {}", (Object)userId, (Object)messageId);
                return FeedbackResult.removed();
            }
            this.feedbackRepository.delete((Object)existing);
            log.info("User {} replacing previous feedback for message {}", (Object)userId, (Object)messageId);
        }
        Feedback feedback = Feedback.negative((String)userId, (String)messageId, (String)query, (String)response, (Feedback.FeedbackCategory)category).withUserContext(username, sector, null).withComments(comments);
        if (ragMetadata != null) {
            feedback.withRagMetadata((String)ragMetadata.get("routingDecision"), this.castToStringList(ragMetadata.get("sourceDocuments")), this.getDoubleOrDefault(ragMetadata, "similarityThreshold", 0.0), this.getIntOrDefault(ragMetadata, "topK", 5), this.getMapOrEmpty(ragMetadata, "signals"));
            feedback.withMetrics(this.getLongOrDefault(ragMetadata, "responseTimeMs", 0L), this.getIntOrDefault(ragMetadata, "reasoningSteps", 0), this.getDoubleOrNull(ragMetadata, "hallucinationScore"));
        }
        Feedback saved = (Feedback)this.feedbackRepository.save((Object)feedback);
        log.warn("Negative feedback recorded: user={}, sector={}, category={}, messageId={}", new Object[]{userId, sector, category, messageId});
        if (category == Feedback.FeedbackCategory.HALLUCINATION) {
            log.error("HALLUCINATION REPORTED: messageId={}, query={}", (Object)messageId, (Object)this.truncate(query, 100));
        }
        return FeedbackResult.success((String)saved.getId(), (Feedback.FeedbackType)Feedback.FeedbackType.NEGATIVE);
    }

    public FeedbackAnalytics getAnalytics(String sector, int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        List recentFeedback = sector != null ? this.feedbackRepository.findBySectorAndTimestampBetween(sector, since, Instant.now()) : this.feedbackRepository.findByTimestampBetween(since, Instant.now());
        long positive = recentFeedback.stream().filter(f -> f.getFeedbackType() == Feedback.FeedbackType.POSITIVE).count();
        long negative = recentFeedback.stream().filter(f -> f.getFeedbackType() == Feedback.FeedbackType.NEGATIVE).count();
        long total = positive + negative;
        double satisfactionRate = total > 0L ? (double)positive / (double)total * 100.0 : 0.0;
        Map<Feedback.FeedbackCategory, Long> categoryBreakdown = recentFeedback.stream().filter(f -> f.getFeedbackType() == Feedback.FeedbackType.NEGATIVE && f.getCategory() != null).collect(Collectors.groupingBy(Feedback::getCategory, Collectors.counting()));
        long openIssues = recentFeedback.stream().filter(f -> f.getResolutionStatus() == Feedback.ResolutionStatus.OPEN).count();
        double avgResponseTimePositive = recentFeedback.stream().filter(f -> f.getFeedbackType() == Feedback.FeedbackType.POSITIVE && f.getResponseTimeMs() > 0L).mapToLong(Feedback::getResponseTimeMs).average().orElse(0.0);
        double avgResponseTimeNegative = recentFeedback.stream().filter(f -> f.getFeedbackType() == Feedback.FeedbackType.NEGATIVE && f.getResponseTimeMs() > 0L).mapToLong(Feedback::getResponseTimeMs).average().orElse(0.0);
        List topIssueQueries = recentFeedback.stream().filter(f -> f.getFeedbackType() == Feedback.FeedbackType.NEGATIVE).sorted((a, b) -> b.getTimestamp().compareTo(a.getTimestamp())).limit(10L).map(Feedback::getQuery).filter(Objects::nonNull).collect(Collectors.toList());
        return new FeedbackAnalytics(total, positive, negative, satisfactionRate, categoryBreakdown, openIssues, avgResponseTimePositive, avgResponseTimeNegative, topIssueQueries, days, sector);
    }

    public Page<Feedback> getOpenIssues(int page, int size) {
        return this.feedbackRepository.findByFeedbackTypeAndResolutionStatus(Feedback.FeedbackType.NEGATIVE, Feedback.ResolutionStatus.OPEN, (Pageable)PageRequest.of((int)page, (int)size, (Sort)Sort.by((Sort.Direction)Sort.Direction.DESC, (String[])new String[]{"timestamp"})));
    }

    public List<Feedback> getHallucinationReports() {
        return this.feedbackRepository.findByCategoryAndResolutionStatusOrderByTimestampDesc(Feedback.FeedbackCategory.HALLUCINATION, Feedback.ResolutionStatus.OPEN);
    }

    public Feedback resolveIssue(String feedbackId, String resolvedBy, String notes) {
        Feedback feedback = (Feedback)this.feedbackRepository.findById((Object)feedbackId).orElseThrow(() -> new IllegalArgumentException("Feedback not found: " + feedbackId));
        feedback.resolve(resolvedBy, notes);
        return (Feedback)this.feedbackRepository.save((Object)feedback);
    }

    public List<TrainingExample> exportTrainingData(String sector, Feedback.FeedbackType type) {
        List feedback = sector != null ? this.feedbackRepository.findByFeedbackTypeAndSector(type, sector) : this.feedbackRepository.findByFeedbackTypeOrderByTimestampDesc(type);
        return feedback.stream().filter(f -> f.getQuery() != null && f.getResponse() != null).map(f -> new TrainingExample(f.getQuery(), f.getResponse(), f.getSector(), f.getSourceDocuments(), f.getFeedbackType() == Feedback.FeedbackType.POSITIVE ? 1.0 : 0.0)).collect(Collectors.toList());
    }

    private List<String> castToStringList(Object obj) {
        if (obj instanceof List) {
            return ((List)obj).stream().filter(String.class::isInstance).map(String.class::cast).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private Map<String, Object> getMapOrEmpty(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Map) {
            return (Map)val;
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

    private String truncate(String s, int len) {
        if (s == null) {
            return null;
        }
        return s.length() > len ? s.substring(0, len) + "..." : s;
    }
}

