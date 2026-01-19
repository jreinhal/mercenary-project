/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.jreinhal.mercenary.rag.ragpart.SuspicionScorer
 *  com.jreinhal.mercenary.rag.ragpart.SuspicionScorer$AggregateAssessment
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.beans.factory.annotation.Value
 *  org.springframework.stereotype.Component
 */
package com.jreinhal.mercenary.rag.ragpart;

import com.jreinhal.mercenary.rag.ragpart.SuspicionScorer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SuspicionScorer {
    private static final Logger log = LoggerFactory.getLogger(SuspicionScorer.class);
    private static final double THRESHOLD_VERIFIED = 0.2;
    private static final double THRESHOLD_LOW_RISK = 0.4;
    private static final double THRESHOLD_MODERATE = 0.6;
    private static final double THRESHOLD_HIGH_RISK = 0.8;
    private static final double RARE_APPEARANCE_PENALTY = 0.2;
    private static final double CORPUS_ALERT_RATIO = 0.2;
    private static final double CORPUS_WARNING_RATIO = 0.05;
    @Value(value="${sentinel.ragpart.scorer.consistency-weight:0.6}")
    private double consistencyWeight;
    @Value(value="${sentinel.ragpart.scorer.variance-weight:0.4}")
    private double varianceWeight;
    @Value(value="${sentinel.ragpart.scorer.min-appearances:2}")
    private int minAppearances;

    public double calculateScore(int appearanceCount, int totalCombinations, double partitionVariance) {
        double consistencyScore;
        if (totalCombinations == 0) {
            consistencyScore = 1.0;
        } else {
            double appearanceRatio = (double)appearanceCount / (double)totalCombinations;
            consistencyScore = 1.0 - appearanceRatio;
            if (appearanceCount < this.minAppearances) {
                consistencyScore = Math.min(1.0, consistencyScore + 0.2);
            }
        }
        double varianceScore = Math.min(1.0, partitionVariance);
        double finalScore = this.consistencyWeight * consistencyScore + this.varianceWeight * varianceScore;
        finalScore = Math.max(0.0, Math.min(1.0, finalScore));
        log.debug("Suspicion score: {} (consistency={}, variance={}, appearances={}/{})", new Object[]{String.format("%.3f", finalScore), String.format("%.3f", consistencyScore), String.format("%.3f", varianceScore), appearanceCount, totalCombinations});
        return finalScore;
    }

    public boolean isSuspicious(double score, double threshold) {
        return score >= threshold;
    }

    public String getAssessment(double score) {
        if (score < 0.2) {
            return "VERIFIED - High consistency, trusted document";
        }
        if (score < 0.4) {
            return "LOW RISK - Moderate consistency";
        }
        if (score < 0.6) {
            return "MODERATE - Inconsistent appearance pattern";
        }
        if (score < 0.8) {
            return "HIGH RISK - Potentially poisoned";
        }
        return "CRITICAL - Strong poisoning indicators";
    }

    public AggregateAssessment assessCorpus(Iterable<Double> scores) {
        int total = 0;
        int suspicious = 0;
        double maxScore = 0.0;
        double sumScore = 0.0;
        for (Double score : scores) {
            ++total;
            sumScore += score.doubleValue();
            maxScore = Math.max(maxScore, score);
            if (!(score >= 0.4)) continue;
            ++suspicious;
        }
        double avgScore = total > 0 ? sumScore / (double)total : 0.0;
        double suspiciousRatio = total > 0 ? (double)suspicious / (double)total : 0.0;
        return new AggregateAssessment(total, suspicious, avgScore, maxScore, suspiciousRatio);
    }
}

