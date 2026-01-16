package com.jreinhal.mercenary.professional.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Hybrid search service combining BM25 keyword search with vector similarity.
 *
 * PROFESSIONAL EDITION - Available in professional, medical, and government builds.
 *
 * Implements Reciprocal Rank Fusion (RRF) to combine:
 * - BM25/TF-IDF keyword matching (good for exact terms, acronyms, names)
 * - Vector similarity search (good for semantic meaning, paraphrases)
 *
 * This addresses the weakness of pure vector search with:
 * - Technical terminology
 * - Acronyms and abbreviations
 * - Proper nouns and specific identifiers
 * - Exact phrase matching
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    private final VectorStore vectorStore;

    // RRF constant (typically 60)
    private static final int RRF_K = 60;

    // Default weights for fusion
    private static final double DEFAULT_VECTOR_WEIGHT = 0.6;
    private static final double DEFAULT_BM25_WEIGHT = 0.4;

    public HybridSearchService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * Search result with combined score.
     */
    public record HybridResult(
        Document document,
        double combinedScore,
        double vectorScore,
        double bm25Score,
        int vectorRank,
        int bm25Rank
    ) {}

    /**
     * Perform hybrid search with default weights.
     */
    public List<HybridResult> search(String query, int topK) {
        return search(query, topK, DEFAULT_VECTOR_WEIGHT, DEFAULT_BM25_WEIGHT);
    }

    /**
     * Perform hybrid search with custom weights.
     *
     * @param query The search query
     * @param topK Number of results to return
     * @param vectorWeight Weight for vector search results (0-1)
     * @param bm25Weight Weight for BM25 results (0-1)
     */
    public List<HybridResult> search(String query, int topK, double vectorWeight, double bm25Weight) {
        log.debug("Hybrid search: query='{}', topK={}, weights=[vector={}, bm25={}]",
            truncate(query, 50), topK, vectorWeight, bm25Weight);

        // Perform vector search
        List<Document> vectorResults = performVectorSearch(query, topK * 2);

        // Perform BM25 search
        List<Document> bm25Results = performBm25Search(query, topK * 2);

        // Fuse results using RRF
        List<HybridResult> fusedResults = fuseResults(vectorResults, bm25Results, vectorWeight, bm25Weight);

        // Return top K
        return fusedResults.stream()
            .limit(topK)
            .toList();
    }

    /**
     * Perform vector similarity search.
     */
    private List<Document> performVectorSearch(String query, int limit) {
        try {
            SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(limit)
                .build();

            return vectorStore.similaritySearch(request);
        } catch (Exception e) {
            log.error("Vector search failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Perform BM25 keyword search.
     * Since Spring AI doesn't have built-in BM25, we implement a simple version.
     */
    private List<Document> performBm25Search(String query, int limit) {
        try {
            // Get all documents (in a real implementation, this would use an index)
            // For now, we'll use vector search as a base and re-rank with BM25
            List<Document> candidates = performVectorSearch(query, limit * 3);

            // Score each document with BM25
            List<Map.Entry<Document, Double>> scored = new ArrayList<>();
            for (Document doc : candidates) {
                double score = calculateBm25Score(query, doc.getContent());
                scored.add(Map.entry(doc, score));
            }

            // Sort by BM25 score
            scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

            return scored.stream()
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
        } catch (Exception e) {
            log.error("BM25 search failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Calculate BM25 score for a document.
     * Simplified implementation - production would use pre-computed IDF values.
     */
    private double calculateBm25Score(String query, String document) {
        // BM25 parameters
        double k1 = 1.2;  // Term frequency saturation
        double b = 0.75;  // Document length normalization

        // Average document length (estimate)
        double avgDocLength = 500.0;

        // Tokenize
        Set<String> queryTerms = tokenize(query);
        List<String> docTerms = new ArrayList<>(tokenize(document));
        int docLength = docTerms.size();

        double score = 0.0;

        for (String term : queryTerms) {
            // Term frequency in document
            long tf = docTerms.stream().filter(t -> t.equals(term)).count();

            if (tf == 0) continue;

            // Simplified IDF (would be pre-computed in production)
            double idf = Math.log(1 + 1); // Simplified - assume term is rare

            // BM25 formula
            double numerator = tf * (k1 + 1);
            double denominator = tf + k1 * (1 - b + b * (docLength / avgDocLength));
            score += idf * (numerator / denominator);
        }

        return score;
    }

    /**
     * Tokenize text into terms.
     */
    private Set<String> tokenize(String text) {
        if (text == null) return Set.of();

        return Arrays.stream(text.toLowerCase()
            .replaceAll("[^a-z0-9\\s]", " ")
            .split("\\s+"))
            .filter(t -> t.length() > 2)
            .collect(Collectors.toSet());
    }

    /**
     * Fuse vector and BM25 results using Reciprocal Rank Fusion.
     */
    private List<HybridResult> fuseResults(List<Document> vectorResults, List<Document> bm25Results,
                                          double vectorWeight, double bm25Weight) {

        // Create document ID to rank maps
        Map<String, Integer> vectorRanks = new HashMap<>();
        Map<String, Double> vectorScores = new HashMap<>();
        for (int i = 0; i < vectorResults.size(); i++) {
            String docId = getDocumentId(vectorResults.get(i));
            vectorRanks.put(docId, i + 1);
            // Estimate score based on rank (actual score may not be available)
            vectorScores.put(docId, 1.0 / (i + 1));
        }

        Map<String, Integer> bm25Ranks = new HashMap<>();
        Map<String, Double> bm25Scores = new HashMap<>();
        for (int i = 0; i < bm25Results.size(); i++) {
            String docId = getDocumentId(bm25Results.get(i));
            bm25Ranks.put(docId, i + 1);
            bm25Scores.put(docId, 1.0 / (i + 1));
        }

        // Get all unique documents
        Map<String, Document> allDocs = new HashMap<>();
        for (Document doc : vectorResults) {
            allDocs.put(getDocumentId(doc), doc);
        }
        for (Document doc : bm25Results) {
            allDocs.put(getDocumentId(doc), doc);
        }

        // Calculate RRF scores
        List<HybridResult> results = new ArrayList<>();
        for (Map.Entry<String, Document> entry : allDocs.entrySet()) {
            String docId = entry.getKey();
            Document doc = entry.getValue();

            int vRank = vectorRanks.getOrDefault(docId, Integer.MAX_VALUE);
            int bRank = bm25Ranks.getOrDefault(docId, Integer.MAX_VALUE);

            double vScore = vectorScores.getOrDefault(docId, 0.0);
            double bScore = bm25Scores.getOrDefault(docId, 0.0);

            // RRF formula: score = sum(1 / (k + rank))
            double rrfVector = vRank < Integer.MAX_VALUE ? 1.0 / (RRF_K + vRank) : 0.0;
            double rrfBm25 = bRank < Integer.MAX_VALUE ? 1.0 / (RRF_K + bRank) : 0.0;

            double combinedScore = (vectorWeight * rrfVector) + (bm25Weight * rrfBm25);

            results.add(new HybridResult(doc, combinedScore, vScore, bScore, vRank, bRank));
        }

        // Sort by combined score
        results.sort((a, b) -> Double.compare(b.combinedScore(), a.combinedScore()));

        return results;
    }

    /**
     * Get unique identifier for a document.
     */
    private String getDocumentId(Document doc) {
        // Try to get ID from metadata
        if (doc.getMetadata().containsKey("id")) {
            return doc.getMetadata().get("id").toString();
        }
        if (doc.getMetadata().containsKey("source")) {
            return doc.getMetadata().get("source").toString();
        }
        // Fallback to content hash
        return String.valueOf(doc.getContent().hashCode());
    }

    /**
     * Adaptive weight adjustment based on query characteristics.
     */
    public double[] suggestWeights(String query) {
        double vectorWeight = DEFAULT_VECTOR_WEIGHT;
        double bm25Weight = DEFAULT_BM25_WEIGHT;

        // Increase BM25 weight for:
        // - Short queries (likely keyword searches)
        // - Queries with acronyms or technical terms
        // - Queries with quotes (exact phrase)

        if (query.length() < 30) {
            bm25Weight += 0.1;
            vectorWeight -= 0.1;
        }

        if (query.matches(".*\\b[A-Z]{2,}\\b.*")) { // Contains acronyms
            bm25Weight += 0.15;
            vectorWeight -= 0.15;
        }

        if (query.contains("\"")) { // Contains quoted phrases
            bm25Weight += 0.2;
            vectorWeight -= 0.2;
        }

        // Increase vector weight for:
        // - Long, natural language queries
        // - Questions (semantic understanding needed)

        if (query.length() > 100) {
            vectorWeight += 0.1;
            bm25Weight -= 0.1;
        }

        if (query.contains("?") || query.toLowerCase().startsWith("how") ||
            query.toLowerCase().startsWith("what") || query.toLowerCase().startsWith("why")) {
            vectorWeight += 0.1;
            bm25Weight -= 0.1;
        }

        // Normalize to ensure they sum to 1
        double total = vectorWeight + bm25Weight;
        return new double[]{vectorWeight / total, bm25Weight / total};
    }

    private String truncate(String str, int maxLen) {
        if (str == null) return "";
        return str.length() <= maxLen ? str : str.substring(0, maxLen) + "...";
    }
}
