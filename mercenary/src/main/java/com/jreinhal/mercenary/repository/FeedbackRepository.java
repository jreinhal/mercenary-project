package com.jreinhal.mercenary.repository;

import com.jreinhal.mercenary.model.Feedback;
import com.jreinhal.mercenary.model.Feedback.FeedbackType;
import com.jreinhal.mercenary.model.Feedback.FeedbackCategory;
import com.jreinhal.mercenary.model.Feedback.ResolutionStatus;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;

/**
 * Repository for feedback data access and analytics queries.
 */
public interface FeedbackRepository extends MongoRepository<Feedback, String> {

    // Basic queries
    List<Feedback> findByUserIdOrderByTimestampDesc(String userId);

    List<Feedback> findBySectorOrderByTimestampDesc(String sector);

    List<Feedback> findByFeedbackTypeOrderByTimestampDesc(FeedbackType type);

    List<Feedback> findByCategoryOrderByTimestampDesc(FeedbackCategory category);

    // Paginated queries for admin dashboard
    Page<Feedback> findByResolutionStatus(ResolutionStatus status, Pageable pageable);

    Page<Feedback> findBySectorAndFeedbackType(String sector, FeedbackType type, Pageable pageable);

    Page<Feedback> findByFeedbackTypeAndResolutionStatus(FeedbackType type,
                                                          ResolutionStatus status,
                                                          Pageable pageable);

    // Time-bounded queries for metrics
    List<Feedback> findByTimestampBetween(Instant start, Instant end);

    List<Feedback> findBySectorAndTimestampBetween(String sector, Instant start, Instant end);

    // Count queries for quick stats
    long countByFeedbackType(FeedbackType type);

    long countBySectorAndFeedbackType(String sector, FeedbackType type);

    long countByCategory(FeedbackCategory category);

    long countByResolutionStatus(ResolutionStatus status);

    long countByTimestampAfter(Instant timestamp);

    // Hallucination-specific queries (high priority issues)
    List<Feedback> findByCategoryAndResolutionStatusOrderByTimestampDesc(
            FeedbackCategory category, ResolutionStatus status);

    // Query for training data export (positive examples)
    List<Feedback> findByFeedbackTypeAndSector(FeedbackType type, String sector);

    // Check if user already gave feedback on a message
    boolean existsByUserIdAndMessageId(String userId, String messageId);

    // Get existing feedback for a message (to toggle/update)
    Feedback findByUserIdAndMessageId(String userId, String messageId);
}
