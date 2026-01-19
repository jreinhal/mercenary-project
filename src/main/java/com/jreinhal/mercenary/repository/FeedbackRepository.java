/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.jreinhal.mercenary.model.Feedback
 *  com.jreinhal.mercenary.model.Feedback$FeedbackCategory
 *  com.jreinhal.mercenary.model.Feedback$FeedbackType
 *  com.jreinhal.mercenary.model.Feedback$ResolutionStatus
 *  com.jreinhal.mercenary.repository.FeedbackRepository
 *  org.springframework.data.domain.Page
 *  org.springframework.data.domain.Pageable
 *  org.springframework.data.mongodb.repository.MongoRepository
 */
package com.jreinhal.mercenary.repository;

import com.jreinhal.mercenary.model.Feedback;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface FeedbackRepository
extends MongoRepository<Feedback, String> {
    public List<Feedback> findByUserIdOrderByTimestampDesc(String var1);

    public List<Feedback> findBySectorOrderByTimestampDesc(String var1);

    public List<Feedback> findByFeedbackTypeOrderByTimestampDesc(Feedback.FeedbackType var1);

    public List<Feedback> findByCategoryOrderByTimestampDesc(Feedback.FeedbackCategory var1);

    public Page<Feedback> findByResolutionStatus(Feedback.ResolutionStatus var1, Pageable var2);

    public Page<Feedback> findBySectorAndFeedbackType(String var1, Feedback.FeedbackType var2, Pageable var3);

    public Page<Feedback> findByFeedbackTypeAndResolutionStatus(Feedback.FeedbackType var1, Feedback.ResolutionStatus var2, Pageable var3);

    public List<Feedback> findByTimestampBetween(Instant var1, Instant var2);

    public List<Feedback> findBySectorAndTimestampBetween(String var1, Instant var2, Instant var3);

    public long countByFeedbackType(Feedback.FeedbackType var1);

    public long countBySectorAndFeedbackType(String var1, Feedback.FeedbackType var2);

    public long countByCategory(Feedback.FeedbackCategory var1);

    public long countByResolutionStatus(Feedback.ResolutionStatus var1);

    public long countByTimestampAfter(Instant var1);

    public List<Feedback> findByCategoryAndResolutionStatusOrderByTimestampDesc(Feedback.FeedbackCategory var1, Feedback.ResolutionStatus var2);

    public List<Feedback> findByFeedbackTypeAndSector(Feedback.FeedbackType var1, String var2);

    public boolean existsByUserIdAndMessageId(String var1, String var2);

    public Feedback findByUserIdAndMessageId(String var1, String var2);
}

