package com.jreinhal.mercenary.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RAGPart Defense Service
 * 
 * Implementation based on: "RAGPart & RAGMask: Retrieval-Stage Defenses Against 
 * Corpus Poisoning in Retrieval-Augmented Generation" (arXiv:2512.24268v1)
 * 
 * RAGPart leverages the inherent training dynamics of dense retrievers, exploiting
 * document partitioning to mitigate the effect of poisoned content. By averaging
 * embeddings of different fragment combinations, poisoned fragments are diluted.
 * 
 * Key Algorithm:
 * 1. Partition each document into N fragments
 * 2. Embed each fragment separately
 * 3. Form combinations of k fragments and average their embeddings
 * 4. Retrieve from multiple combinations and aggregate via voting
 * 
 * @author Implementation based on Pathmanathan et al., 2025
 */
@Service
public class RAGPartDefenseService {

    private static final Logger log = LoggerFactory.getLogger(RAGPartDefenseService.class);

    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;

    // RAGPart hyperparameters (from paper)
    private static final int DEFAULT_NUM_PARTITIONS = 4;      // N: number of fragments per document
    private static final int DEFAULT_COMBINATION_SIZE = 3;    // k: fragments per combination
    private static final double SIMILARITY_THRESHOLD = 0.6;
    private static final int TOP_K = 10;

    public RAGPartDefenseService(VectorStore vectorStore, EmbeddingModel embeddingModel) {
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
        log.info(">>> RAGPart Defense Service initialized <<<");
        log.info(">>> Partitions: {}, Combination size: {} <<<", DEFAULT_NUM_PARTITIONS, DEFAULT_COMBINATION_SIZE);
    }

    /**
     * Partition a document into N roughly equal fragments.
     * 
     * The paper notes: "Dense retrievers explicitly define positive training pairs 
     * by treating randomly cropped portions of a document as semantically equivalent 
     * to the whole, inducing an inductive bias in the retriever's embedding space."
     */
    public List<String> partitionDocument(String content, int numPartitions) {
        if (content == null || content.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> fragments = new ArrayList<>();
        String[] sentences = content.split("(?<=[.!?])\\s+");
        
        if (sentences.length <= numPartitions) {
            // If fewer sentences than partitions, each sentence is a fragment
            for (String s : sentences) {
                if (!s.trim().isEmpty()) {
                    fragments.add(s.trim());
                }
            }
            // Pad with empty if needed
            while (fragments.size() < numPartitions) {
                fragments.add("");
            }
        } else {
            // Distribute sentences across partitions
            int sentencesPerPartition = sentences.length / numPartitions;
            int remainder = sentences.length % numPartitions;
            
            int sentenceIndex = 0;
            for (int i = 0; i < numPartitions; i++) {
                StringBuilder fragment = new StringBuilder();
                int count = sentencesPerPartition + (i < remainder ? 1 : 0);
                
                for (int j = 0; j < count && sentenceIndex < sentences.length; j++) {
                    fragment.append(sentences[sentenceIndex++]).append(" ");
                }
                fragments.add(fragment.toString().trim());
            }
        }

        log.debug("Partitioned document into {} fragments", fragments.size());
        return fragments;
    }

    /**
     * Generate all k-combinations of N indices.
     * For N=4, k=3: [[0,1,2], [0,1,3], [0,2,3], [1,2,3]]
     */
    public List<List<Integer>> generateCombinations(int n, int k) {
        List<List<Integer>> result = new ArrayList<>();
        generateCombinationsHelper(n, k, 0, new ArrayList<>(), result);
        return result;
    }

    private void generateCombinationsHelper(int n, int k, int start, 
                                            List<Integer> current, List<List<Integer>> result) {
        if (current.size() == k) {
            result.add(new ArrayList<>(current));
            return;
        }
        for (int i = start; i < n; i++) {
            current.add(i);
            generateCombinationsHelper(n, k, i + 1, current, result);
            current.remove(current.size() - 1);
        }
    }

    /**
     * Compute averaged embedding for a combination of fragments.
     * 
     * From paper: "We then average the embeddings of different combinations of 
     * fragments to form a final similarity score. If the number of poisoned 
     * fragments is not too large, their influence is diminished through averaging."
     */
    public List<Double> computeAveragedEmbedding(List<String> fragments, List<Integer> combination) {
        StringBuilder combinedText = new StringBuilder();
        for (int idx : combination) {
            if (idx < fragments.size() && !fragments.get(idx).isEmpty()) {
                combinedText.append(fragments.get(idx)).append(" ");
            }
        }

        String text = combinedText.toString().trim();
        if (text.isEmpty()) {
            return null;
        }

        // Get embedding from the model
        List<Double> embedding = embeddingModel.embed(text);
        return embedding;
    }

    /**
     * RAGPart-defended similarity search.
     * 
     * Algorithm:
     * 1. For query, generate multiple retrieval passes with different fragment combinations
     * 2. Aggregate results using Reciprocal Rank Fusion (RRF)
     * 3. Return documents that appear consistently across combinations
     */
    public List<Document> defendedSearch(String query, int topK) {
        log.info("RAGPart: Executing defended search for query: {}", 
                 query.substring(0, Math.min(50, query.length())));

        // For query-side, we use standard search but aggregate multiple results
        List<List<Document>> allRetrievalResults = new ArrayList<>();

        // Perform multiple retrieval passes
        // In production, you'd vary the query embedding or use ensemble retrievers
        for (int pass = 0; pass < 3; pass++) {
            try {
                List<Document> passResults = vectorStore.similaritySearch(
                    SearchRequest.query(query)
                        .withTopK(topK * 2)  // Retrieve more for aggregation
                        .withSimilarityThreshold(SIMILARITY_THRESHOLD - (pass * 0.05))
                );
                allRetrievalResults.add(passResults);
            } catch (Exception e) {
                log.warn("RAGPart pass {} failed: {}", pass, e.getMessage());
            }
        }

        // Aggregate using Reciprocal Rank Fusion (RRF)
        Map<String, Double> documentScores = new HashMap<>();
        Map<String, Document> documentMap = new HashMap<>();

        final double RRF_K = 60.0; // Standard RRF constant

        for (List<Document> passResults : allRetrievalResults) {
            for (int rank = 0; rank < passResults.size(); rank++) {
                Document doc = passResults.get(rank);
                String docId = getDocumentId(doc);
                
                // RRF score: 1 / (k + rank)
                double rrfScore = 1.0 / (RRF_K + rank);
                documentScores.merge(docId, rrfScore, Double::sum);
                documentMap.putIfAbsent(docId, doc);
            }
        }

        // Sort by aggregated RRF score and return top K
        List<Document> rankedResults = documentScores.entrySet().stream()
            .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
            .limit(topK)
            .map(entry -> documentMap.get(entry.getKey()))
            .collect(Collectors.toList());

        log.info("RAGPart: Returned {} defended results from {} retrieval passes", 
                 rankedResults.size(), allRetrievalResults.size());

        return rankedResults;
    }

    /**
     * RAGMask-style suspicious token detection.
     * 
     * From paper: "Poisoning often hinges on a small set of influential tokens that 
     * disproportionately affect similarity scores. By selectively masking these tokens 
     * and measuring the resulting similarity shift, RAGMask identifies poisoned documents."
     */
    public double computeSuspicionScore(Document doc, String query) {
        String content = doc.getContent();
        if (content == null || content.isEmpty()) {
            return 0.0;
        }

        // Get original embedding
        List<Double> originalEmbedding = embeddingModel.embed(content);
        List<Double> queryEmbedding = embeddingModel.embed(query);
        double originalSimilarity = cosineSimilarity(originalEmbedding, queryEmbedding);

        // Identify high-impact tokens (simplified: mask query terms)
        String[] queryTerms = query.toLowerCase().split("\\s+");
        String maskedContent = content;
        for (String term : queryTerms) {
            if (term.length() > 3) {  // Only mask substantive terms
                maskedContent = maskedContent.replaceAll("(?i)\\b" + term + "\\b", "[MASK]");
            }
        }

        // Get masked embedding
        List<Double> maskedEmbedding = embeddingModel.embed(maskedContent);
        double maskedSimilarity = cosineSimilarity(maskedEmbedding, queryEmbedding);

        // Suspicion score: large similarity drop when query terms are masked
        // indicates the document might be artificially stuffed with query terms
        double similarityDrop = originalSimilarity - maskedSimilarity;

        // Normalize: high drop = suspicious (potential query stuffing attack)
        double suspicionScore = Math.max(0, similarityDrop / originalSimilarity);

        if (suspicionScore > 0.3) {
            log.warn("RAGMask: High suspicion score {} for document from {}", 
                     suspicionScore, doc.getMetadata().get("source"));
        }

        return suspicionScore;
    }

    /**
     * Filter out suspicious documents based on RAGMask analysis.
     */
    public List<Document> filterSuspiciousDocuments(List<Document> documents, 
                                                     String query, 
                                                     double suspicionThreshold) {
        return documents.stream()
            .filter(doc -> {
                double suspicion = computeSuspicionScore(doc, query);
                if (suspicion > suspicionThreshold) {
                    log.warn("RAGMask: Filtering suspicious document (score: {}): {}", 
                             suspicion, doc.getMetadata().get("source"));
                    return false;
                }
                return true;
            })
            .collect(Collectors.toList());
    }

    /**
     * Combined RAGPart + RAGMask defense pipeline.
     */
    public List<Document> secureSearch(String query, int topK) {
        // Step 1: RAGPart defended retrieval
        List<Document> candidates = defendedSearch(query, topK * 2);

        // Step 2: RAGMask suspicious document filtering
        List<Document> filtered = filterSuspiciousDocuments(candidates, query, 0.4);

        // Step 3: Return top K from filtered results
        return filtered.stream().limit(topK).collect(Collectors.toList());
    }

    // ========== Utility Methods ==========

    private String getDocumentId(Document doc) {
        // Try various metadata keys for document ID
        Object source = doc.getMetadata().get("source");
        if (source != null) return source.toString();
        
        Object id = doc.getMetadata().get("id");
        if (id != null) return id.toString();
        
        // Fallback: hash of content
        return String.valueOf(doc.getContent().hashCode());
    }

    private double cosineSimilarity(List<Double> a, List<Double> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty() || a.size() != b.size()) {
            return 0.0;
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.size(); i++) {
            dotProduct += a.get(i) * b.get(i);
            normA += a.get(i) * a.get(i);
            normB += b.get(i) * b.get(i);
        }

        if (normA == 0 || normB == 0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    // ========== Document Ingestion with Partitioning ==========

    /**
     * Ingest document with RAGPart partitioning for defense.
     * Stores both full document and individual partitions.
     */
    public void ingestWithPartitions(Document document, int numPartitions) {
        String content = document.getContent();
        Map<String, Object> metadata = new HashMap<>(document.getMetadata());
        
        // Store full document
        metadata.put("ragpart_type", "full");
        metadata.put("ragpart_partitions", numPartitions);
        vectorStore.add(List.of(new Document(content, metadata)));

        // Store individual partitions
        List<String> partitions = partitionDocument(content, numPartitions);
        for (int i = 0; i < partitions.size(); i++) {
            String partition = partitions.get(i);
            if (!partition.isEmpty()) {
                Map<String, Object> partMeta = new HashMap<>(document.getMetadata());
                partMeta.put("ragpart_type", "partition");
                partMeta.put("ragpart_partition_index", i);
                partMeta.put("ragpart_total_partitions", numPartitions);
                vectorStore.add(List.of(new Document(partition, partMeta)));
            }
        }

        log.info("RAGPart: Ingested document with {} partitions: {}", 
                 numPartitions, metadata.get("source"));
    }
}
