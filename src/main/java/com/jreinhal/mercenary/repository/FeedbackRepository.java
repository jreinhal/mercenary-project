package com.jreinhal.mercenary.repository;

import com.jreinhal.mercenary.model.Feedback;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface FeedbackRepository
extends MongoRepository<Feedback, String> {
    public Feedback findByUserIdAndMessageIdAndWorkspaceId(String var1, String var2, String var3);

    public List<Feedback> findByUserIdOrderByTimestampDesc(String var1);

    public List<Feedback> findBySectorOrderByTimestampDesc(String var1);

    public List<Feedback> findByFeedbackTypeOrderByTimestampDesc(Feedback.FeedbackType var1);

    public List<Feedback> findByFeedbackTypeAndWorkspaceIdOrderByTimestampDesc(Feedback.FeedbackType var1, String var2);

    public List<Feedback> findByCategoryOrderByTimestampDesc(Feedback.FeedbackCategory var1);

    public Page<Feedback> findByResolutionStatus(Feedback.ResolutionStatus var1, Pageable var2);

    public Page<Feedback> findBySectorAndFeedbackType(String var1, Feedback.FeedbackType var2, Pageable var3);

    public Page<Feedback> findByFeedbackTypeAndResolutionStatus(Feedback.FeedbackType var1, Feedback.ResolutionStatus var2, Pageable var3);

    public Page<Feedback> findByFeedbackTypeAndResolutionStatusAndWorkspaceId(Feedback.FeedbackType var1, Feedback.ResolutionStatus var2, String var3, Pageable var4);

    public List<Feedback> findByTimestampBetween(Instant var1, Instant var2);

    public List<Feedback> findBySectorAndTimestampBetween(String var1, Instant var2, Instant var3);

    public List<Feedback> findByTimestampBetweenAndWorkspaceId(Instant var1, Instant var2, String var3);

    public List<Feedback> findBySectorAndTimestampBetweenAndWorkspaceId(String var1, Instant var2, Instant var3, String var4);

    public long countByFeedbackType(Feedback.FeedbackType var1);

    public long countBySectorAndFeedbackType(String var1, Feedback.FeedbackType var2);

    public long countByCategory(Feedback.FeedbackCategory var1);

    public long countByResolutionStatus(Feedback.ResolutionStatus var1);

    public long countByTimestampAfter(Instant var1);

    public List<Feedback> findByCategoryAndResolutionStatusOrderByTimestampDesc(Feedback.FeedbackCategory var1, Feedback.ResolutionStatus var2);

    public List<Feedback> findByCategoryAndResolutionStatusAndWorkspaceIdOrderByTimestampDesc(Feedback.FeedbackCategory var1, Feedback.ResolutionStatus var2, String var3);

    public List<Feedback> findByFeedbackTypeAndSector(Feedback.FeedbackType var1, String var2);

    public List<Feedback> findByFeedbackTypeAndSectorAndWorkspaceId(Feedback.FeedbackType var1, String var2, String var3);

    public boolean existsByUserIdAndMessageId(String var1, String var2);

    public Feedback findByUserIdAndMessageId(String var1, String var2);
}
