package com.jreinhal.mercenary.rag.megarag;

import com.jreinhal.mercenary.rag.megarag.MegaRagService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class VisualEntityLinker {
    private static final Logger log = LoggerFactory.getLogger(VisualEntityLinker.class);
    private static final double FUZZY_THRESHOLD = 0.85;
    private static final Pattern ENTITY_PATTERN = Pattern.compile("\\b([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*|[A-Z]{2,}(?:-?\\d+)?|\\d+(?:\\.\\d+)?\\s*(?:%|percent|million|billion|thousand))\\b");

    public List<MegaRagService.CrossModalEdge> linkEntities(List<MegaRagService.VisualEntity> visualEntities, String textContext, String visualNodeId, String workspaceId) {
        if (visualEntities.isEmpty() || textContext == null || textContext.isBlank()) {
            return List.of();
        }
        Set<String> textEntities = this.extractTextEntities(textContext);
        ArrayList<MegaRagService.CrossModalEdge> edges = new ArrayList<MegaRagService.CrossModalEdge>();
        for (MegaRagService.VisualEntity visualEntity : visualEntities) {
            String visualName = visualEntity.name();
            for (String textEntity : textEntities) {
                double similarity = this.calculateSimilarity(visualName, textEntity);
                if (!(similarity >= 0.85)) continue;
                String relationshipType = this.determineRelationshipType(visualEntity, textEntity, similarity);
                MegaRagService.CrossModalEdge edge = new MegaRagService.CrossModalEdge(UUID.randomUUID().toString(), visualNodeId, textEntity, visualName, similarity, relationshipType, workspaceId);
                edges.add(edge);
                log.debug("Created cross-modal edge: '{}' <-> '{}' (sim={:.2f}, rel={})", new Object[]{visualName, textEntity, similarity, relationshipType});
            }
        }
        log.info("VisualEntityLinker: Created {} cross-modal edges from {} visual + {} text entities", new Object[]{edges.size(), visualEntities.size(), textEntities.size()});
        return edges;
    }

    private Set<String> extractTextEntities(String text) {
        LinkedHashSet<String> entities = new LinkedHashSet<String>();
        Matcher matcher = ENTITY_PATTERN.matcher(text);
        while (matcher.find()) {
            String entity = matcher.group(1).trim();
            if (entity.length() <= 1 || this.isStopWord(entity)) continue;
            entities.add(entity);
        }
        return entities;
    }

    private double calculateSimilarity(String a, String b) {
        String normB;
        if (a == null || b == null) {
            return 0.0;
        }
        if (a.equals(b)) {
            return 1.0;
        }
        if (a.equalsIgnoreCase(b)) {
            return 0.98;
        }
        String normA = this.normalize(a);
        if (normA.equals(normB = this.normalize(b))) {
            return 0.95;
        }
        if (normA.contains(normB) || normB.contains(normA)) {
            return 0.9;
        }
        double levenshteinSim = this.levenshteinSimilarity(normA, normB);
        double jaccardSim = this.jaccardSimilarity(normA, normB);
        return Math.max(levenshteinSim, jaccardSim);
    }

    private String normalize(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]", "").trim();
    }

    private double levenshteinSimilarity(String a, String b) {
        int distance = this.levenshteinDistance(a, b);
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) {
            return 1.0;
        }
        return 1.0 - (double)distance / (double)maxLen;
    }

    private int levenshteinDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); ++i) {
            for (int j = 0; j <= b.length(); ++j) {
                if (i == 0) {
                    dp[i][j] = j;
                    continue;
                }
                if (j == 0) {
                    dp[i][j] = i;
                    continue;
                }
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }

    private double jaccardSimilarity(String a, String b) {
        HashSet<String> tokensA = new HashSet<String>(Arrays.asList(a.split("\\s+")));
        HashSet<String> tokensB = new HashSet<String>(Arrays.asList(b.split("\\s+")));
        if (tokensA.isEmpty() && tokensB.isEmpty()) {
            return 1.0;
        }
        HashSet<String> intersection = new HashSet<String>(tokensA);
        intersection.retainAll(tokensB);
        HashSet<String> union = new HashSet<String>(tokensA);
        union.addAll(tokensB);
        if (union.isEmpty()) {
            return 0.0;
        }
        return (double)intersection.size() / (double)union.size();
    }

    private String determineRelationshipType(MegaRagService.VisualEntity visualEntity, String textEntity, double similarity) {
        if (similarity >= 0.98) {
            return "EXACT_MATCH";
        }
        if (similarity >= 0.9) {
            return "ALIAS";
        }
        if (similarity >= 0.85) {
            return "SIMILAR";
        }
        return "RELATED";
    }

    private boolean isStopWord(String word) {
        Set<String> stopWords = Set.of("the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for", "of", "with", "by", "from", "as", "is", "was", "are", "were", "been", "be", "have", "has", "had", "do", "does", "did", "will", "would", "could", "should", "may", "might", "must", "shall", "can", "this", "that", "these", "those", "it", "its", "they", "them", "their");
        return stopWords.contains(word.toLowerCase());
    }
}
