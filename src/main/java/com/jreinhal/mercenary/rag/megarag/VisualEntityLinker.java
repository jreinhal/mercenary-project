package com.jreinhal.mercenary.rag.megarag;

import com.jreinhal.mercenary.rag.megarag.MegaRagService.CrossModalEdge;
import com.jreinhal.mercenary.rag.megarag.MegaRagService.VisualEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Visual Entity Linker for MegaRAG cross-modal knowledge graph.
 *
 * Links entities extracted from visual content (images, charts, diagrams)
 * to entities mentioned in surrounding text context. This enables:
 * - Cross-modal retrieval (find images by text queries)
 * - Context enrichment (augment text with visual data)
 * - Entity disambiguation (same entity in different modalities)
 *
 * Linking strategies:
 * - Exact match: Direct string matching
 * - Fuzzy match: Levenshtein distance for typos/OCR errors
 * - Semantic match: LLM-based similarity (optional)
 * - Co-reference: Pronouns and references resolution
 */
@Component
public class VisualEntityLinker {

    private static final Logger log = LoggerFactory.getLogger(VisualEntityLinker.class);

    // Threshold for fuzzy matching (Levenshtein distance ratio)
    private static final double FUZZY_THRESHOLD = 0.85;

    // Pattern for extracting potential entities from text
    private static final Pattern ENTITY_PATTERN = Pattern.compile(
            "\\b([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*|" +  // Capitalized phrases
            "[A-Z]{2,}(?:-?\\d+)?|" +                   // Acronyms
            "\\d+(?:\\.\\d+)?\\s*(?:%|percent|million|billion|thousand))\\b" // Metrics
    );

    /**
     * Link visual entities to text entities and create cross-modal edges.
     *
     * @param visualEntities Entities extracted from the image
     * @param textContext Surrounding text content
     * @param visualNodeId ID of the visual node in the KG
     * @return List of cross-modal edges representing links
     */
    public List<CrossModalEdge> linkEntities(List<VisualEntity> visualEntities,
                                              String textContext,
                                              String visualNodeId) {
        if (visualEntities.isEmpty() || textContext == null || textContext.isBlank()) {
            return List.of();
        }

        // Extract entities from text
        Set<String> textEntities = extractTextEntities(textContext);

        List<CrossModalEdge> edges = new ArrayList<>();

        for (VisualEntity visualEntity : visualEntities) {
            String visualName = visualEntity.name();

            for (String textEntity : textEntities) {
                // Calculate similarity
                double similarity = calculateSimilarity(visualName, textEntity);

                if (similarity >= FUZZY_THRESHOLD) {
                    String relationshipType = determineRelationshipType(visualEntity, textEntity, similarity);

                    CrossModalEdge edge = new CrossModalEdge(
                            UUID.randomUUID().toString(),
                            visualNodeId,
                            textEntity,
                            visualName,
                            similarity,
                            relationshipType
                    );
                    edges.add(edge);

                    log.debug("Created cross-modal edge: '{}' <-> '{}' (sim={:.2f}, rel={})",
                            visualName, textEntity, similarity, relationshipType);
                }
            }
        }

        log.info("VisualEntityLinker: Created {} cross-modal edges from {} visual + {} text entities",
                edges.size(), visualEntities.size(), textEntities.size());

        return edges;
    }

    /**
     * Extract potential entities from text using patterns.
     */
    private Set<String> extractTextEntities(String text) {
        Set<String> entities = new LinkedHashSet<>();

        Matcher matcher = ENTITY_PATTERN.matcher(text);
        while (matcher.find()) {
            String entity = matcher.group(1).trim();
            if (entity.length() > 1 && !isStopWord(entity)) {
                entities.add(entity);
            }
        }

        return entities;
    }

    /**
     * Calculate similarity between two entity names.
     * Combines exact match, case-insensitive match, and fuzzy matching.
     */
    private double calculateSimilarity(String a, String b) {
        if (a == null || b == null) {
            return 0.0;
        }

        // Exact match
        if (a.equals(b)) {
            return 1.0;
        }

        // Case-insensitive match
        if (a.equalsIgnoreCase(b)) {
            return 0.98;
        }

        // Normalize for comparison
        String normA = normalize(a);
        String normB = normalize(b);

        if (normA.equals(normB)) {
            return 0.95;
        }

        // Containment check
        if (normA.contains(normB) || normB.contains(normA)) {
            return 0.90;
        }

        // Levenshtein distance
        double levenshteinSim = levenshteinSimilarity(normA, normB);

        // Jaccard similarity on tokens
        double jaccardSim = jaccardSimilarity(normA, normB);

        // Weighted combination
        return Math.max(levenshteinSim, jaccardSim);
    }

    /**
     * Normalize string for comparison.
     */
    private String normalize(String s) {
        return s.toLowerCase()
                .replaceAll("[^a-z0-9]", "")
                .trim();
    }

    /**
     * Calculate Levenshtein similarity (1 - normalized distance).
     */
    private double levenshteinSimilarity(String a, String b) {
        int distance = levenshteinDistance(a, b);
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) return 1.0;
        return 1.0 - ((double) distance / maxLen);
    }

    /**
     * Calculate Levenshtein edit distance.
     */
    private int levenshteinDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];

        for (int i = 0; i <= a.length(); i++) {
            for (int j = 0; j <= b.length(); j++) {
                if (i == 0) {
                    dp[i][j] = j;
                } else if (j == 0) {
                    dp[i][j] = i;
                } else {
                    int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                    dp[i][j] = Math.min(
                            Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                            dp[i - 1][j - 1] + cost
                    );
                }
            }
        }

        return dp[a.length()][b.length()];
    }

    /**
     * Calculate Jaccard similarity on word tokens.
     */
    private double jaccardSimilarity(String a, String b) {
        Set<String> tokensA = new HashSet<>(Arrays.asList(a.split("\\s+")));
        Set<String> tokensB = new HashSet<>(Arrays.asList(b.split("\\s+")));

        if (tokensA.isEmpty() && tokensB.isEmpty()) {
            return 1.0;
        }

        Set<String> intersection = new HashSet<>(tokensA);
        intersection.retainAll(tokensB);

        Set<String> union = new HashSet<>(tokensA);
        union.addAll(tokensB);

        if (union.isEmpty()) {
            return 0.0;
        }

        return (double) intersection.size() / union.size();
    }

    /**
     * Determine the relationship type between linked entities.
     */
    private String determineRelationshipType(VisualEntity visualEntity,
                                              String textEntity,
                                              double similarity) {
        if (similarity >= 0.98) {
            return "EXACT_MATCH";
        } else if (similarity >= 0.90) {
            return "ALIAS";
        } else if (similarity >= 0.85) {
            return "SIMILAR";
        } else {
            return "RELATED";
        }
    }

    /**
     * Check if word is a common stop word.
     */
    private boolean isStopWord(String word) {
        Set<String> stopWords = Set.of(
                "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
                "of", "with", "by", "from", "as", "is", "was", "are", "were", "been",
                "be", "have", "has", "had", "do", "does", "did", "will", "would",
                "could", "should", "may", "might", "must", "shall", "can", "this",
                "that", "these", "those", "it", "its", "they", "them", "their"
        );
        return stopWords.contains(word.toLowerCase());
    }
}
