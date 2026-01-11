package com.jreinhal.mercenary.rag.ragpart;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Suspicion Scorer for RAGPart Defense.
 *
 * Calculates a suspicion score for documents based on their appearance patterns
 * across partition combinations during retrieval.
 *
 * SCORING LOGIC:
 * - Documents appearing in most/all combinations = low suspicion (legitimate)
 * - Documents appearing in very few combinations = higher suspicion
 * - Documents with high variance in which partitions they appear = suspicious
 *
 * The rationale is that poisoned documents often use techniques like
 * keyword stuffing or adversarial embeddings that work in some contexts
 * but not others, leading to inconsistent retrieval patterns.
 */
@Component
public class SuspicionScorer {

    private static final Logger log = LoggerFactory.getLogger(SuspicionScorer.class);

    // Suspicion thresholds for assessment levels
    private static final double THRESHOLD_VERIFIED = 0.2;
    private static final double THRESHOLD_LOW_RISK = 0.4;
    private static final double THRESHOLD_MODERATE = 0.6;
    private static final double THRESHOLD_HIGH_RISK = 0.8;

    // Penalty for rare document appearances
    private static final double RARE_APPEARANCE_PENALTY = 0.2;

    // Alert thresholds for corpus health
    private static final double CORPUS_ALERT_RATIO = 0.2;
    private static final double CORPUS_WARNING_RATIO = 0.05;

    @Value("${sentinel.ragpart.scorer.consistency-weight:0.6}")
    private double consistencyWeight;

    @Value("${sentinel.ragpart.scorer.variance-weight:0.4}")
    private double varianceWeight;

    @Value("${sentinel.ragpart.scorer.min-appearances:2}")
    private int minAppearances;

    /**
     * Calculate suspicion score for a document.
     *
     * @param appearanceCount Number of combinations where document appeared
     * @param totalCombinations Total number of partition combinations queried
     * @param partitionVariance Variance in which partitions the document appeared
     * @return Suspicion score (0.0 = trusted, 1.0 = highly suspicious)
     */
    public double calculateScore(int appearanceCount, int totalCombinations, double partitionVariance) {
        // Component 1: Consistency score (how often did it appear?)
        // Documents appearing in all combinations get score of 0
        // Documents appearing rarely get score approaching 1
        double consistencyScore;
        if (totalCombinations == 0) {
            consistencyScore = 1.0;
        } else {
            double appearanceRatio = (double) appearanceCount / totalCombinations;
            // Invert: high appearance = low suspicion
            consistencyScore = 1.0 - appearanceRatio;

            // Penalize documents that appear very rarely
            if (appearanceCount < minAppearances) {
                consistencyScore = Math.min(1.0, consistencyScore + RARE_APPEARANCE_PENALTY);
            }
        }

        // Component 2: Variance score (how erratic are the appearances?)
        // Low variance = appears in consistent partitions = legitimate
        // High variance = appears erratically = suspicious
        double varianceScore = Math.min(1.0, partitionVariance);

        // Weighted combination
        double finalScore = (consistencyWeight * consistencyScore) + (varianceWeight * varianceScore);

        // Clamp to valid range
        finalScore = Math.max(0.0, Math.min(1.0, finalScore));

        log.debug("Suspicion score: {} (consistency={}, variance={}, appearances={}/{})",
                String.format("%.3f", finalScore),
                String.format("%.3f", consistencyScore),
                String.format("%.3f", varianceScore),
                appearanceCount, totalCombinations);

        return finalScore;
    }

    /**
     * Quick check if a document should be considered suspicious.
     *
     * @param score The calculated suspicion score
     * @param threshold The suspicion threshold
     * @return true if suspicious
     */
    public boolean isSuspicious(double score, double threshold) {
        return score >= threshold;
    }

    /**
     * Get a human-readable assessment of the suspicion level.
     *
     * @param score The suspicion score
     * @return Description of the suspicion level
     */
    public String getAssessment(double score) {
        if (score < THRESHOLD_VERIFIED) {
            return "VERIFIED - High consistency, trusted document";
        } else if (score < THRESHOLD_LOW_RISK) {
            return "LOW RISK - Moderate consistency";
        } else if (score < THRESHOLD_MODERATE) {
            return "MODERATE - Inconsistent appearance pattern";
        } else if (score < THRESHOLD_HIGH_RISK) {
            return "HIGH RISK - Potentially poisoned";
        } else {
            return "CRITICAL - Strong poisoning indicators";
        }
    }

    /**
     * Calculate an aggregate suspicion score for a set of documents.
     * Useful for assessing overall corpus health.
     *
     * @param scores Individual document scores
     * @return Aggregate assessment
     */
    public AggregateAssessment assessCorpus(Iterable<Double> scores) {
        int total = 0;
        int suspicious = 0;
        double maxScore = 0.0;
        double sumScore = 0.0;

        for (Double score : scores) {
            total++;
            sumScore += score;
            maxScore = Math.max(maxScore, score);
            if (score >= THRESHOLD_LOW_RISK) {
                suspicious++;
            }
        }

        double avgScore = total > 0 ? sumScore / total : 0.0;
        double suspiciousRatio = total > 0 ? (double) suspicious / total : 0.0;

        return new AggregateAssessment(total, suspicious, avgScore, maxScore, suspiciousRatio);
    }

    /**
     * Aggregate assessment of corpus health.
     */
    public record AggregateAssessment(
            int totalDocuments,
            int suspiciousDocuments,
            double averageScore,
            double maxScore,
            double suspiciousRatio
    ) {
        public String getSummary() {
            if (suspiciousRatio > CORPUS_ALERT_RATIO) {
                return "ALERT: High proportion of suspicious documents (" +
                       String.format("%.1f%%", suspiciousRatio * 100) + ")";
            } else if (suspiciousRatio > CORPUS_WARNING_RATIO) {
                return "WARNING: Some suspicious documents detected (" +
                       String.format("%.1f%%", suspiciousRatio * 100) + ")";
            } else {
                return "HEALTHY: Corpus appears clean (" + totalDocuments + " documents verified)";
            }
        }
    }
}
