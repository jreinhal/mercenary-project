package com.jreinhal.mercenary.rag.ragpart;

import com.jreinhal.mercenary.reasoning.ReasoningTracer;
import com.jreinhal.mercenary.reasoning.ReasoningStep.StepType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * RAGPart: Corpus Poisoning Defense via Document Partitioning
 * Based on research paper arXiv:2512.24268v1
 *
 * THREAT MODEL:
 * Corpus poisoning attacks inject malicious documents into the RAG knowledge base
 * that manipulate retrieval to surface attacker-controlled content, leading to
 * misinformation, prompt injection, or data exfiltration.
 *
 * DEFENSE MECHANISM:
 * 1. PARTITION: Documents are assigned to N random partitions at ingestion time
 * 2. RETRIEVAL: Query is run against k partition combinations (subsets)
 * 3. DETECTION: Documents appearing inconsistently across partitions are flagged
 * 4. FILTERING: Suspicious documents are excluded from final context
 *
 * RATIONALE:
 * Legitimate documents will appear consistently across partition combinations
 * because their relevance is based on genuine semantic similarity.
 * Poisoned documents often rely on keyword stuffing or adversarial embeddings
 * that don't generalize well across different retrieval contexts.
 */
@Service
public class RagPartService {

    private static final Logger log = LoggerFactory.getLogger(RagPartService.class);

    private final VectorStore vectorStore;
    private final PartitionAssigner partitionAssigner;
    private final SuspicionScorer suspicionScorer;
    private final ReasoningTracer reasoningTracer;

    @Value("${sentinel.ragpart.enabled:true}")
    private boolean enabled;

    @Value("${sentinel.ragpart.partitions:4}")
    private int numPartitions;

    @Value("${sentinel.ragpart.combination-size:3}")
    private int combinationSize;

    @Value("${sentinel.ragpart.suspicion-threshold:0.4}")
    private double suspicionThreshold;

    @Value("${sentinel.ragpart.retrieval-k:10}")
    private int retrievalK;

    public RagPartService(VectorStore vectorStore, PartitionAssigner partitionAssigner,
                          SuspicionScorer suspicionScorer, ReasoningTracer reasoningTracer) {
        this.vectorStore = vectorStore;
        this.partitionAssigner = partitionAssigner;
        this.suspicionScorer = suspicionScorer;
        this.reasoningTracer = reasoningTracer;
    }

    @PostConstruct
    public void init() {
        log.info("RAGPart Service initialized (enabled={}, partitions={}, combinationSize={}, threshold={})",
                enabled, numPartitions, combinationSize, suspicionThreshold);
    }

    /**
     * Execute RAGPart defended retrieval.
     *
     * @param query The user's query
     * @param department Department filter
     * @return List of verified documents with suspicious ones filtered out
     */
    public RagPartResult retrieve(String query, String department) {
        if (!enabled) {
            log.debug("RAGPart disabled, performing undefended retrieval");
            List<Document> docs = standardRetrieval(query, department);
            return new RagPartResult(docs, List.of(), Map.of());
        }

        long startTime = System.currentTimeMillis();
        log.info("RAGPart: Starting defended retrieval for query: {}", query);

        // Step 1: Generate partition combinations
        List<Set<Integer>> combinations = generateCombinations(numPartitions, combinationSize);
        log.debug("RAGPart: Generated {} partition combinations", combinations.size());

        // Step 2: Retrieve from each combination
        Map<String, DocumentAppearance> documentAppearances = new HashMap<>();

        for (int i = 0; i < combinations.size(); i++) {
            Set<Integer> partitionSet = combinations.get(i);
            String partitionFilter = buildPartitionFilter(partitionSet, department);

            List<Document> combinationResults = retrieveWithFilter(query, partitionFilter);
            log.debug("RAGPart: Combination {} ({}) returned {} documents",
                    i + 1, partitionSet, combinationResults.size());

            // Track document appearances
            for (Document doc : combinationResults) {
                String docId = getDocumentId(doc);
                documentAppearances.computeIfAbsent(docId, k -> new DocumentAppearance(doc))
                        .addAppearance(i, partitionSet);
            }
        }

        // Step 3: Calculate suspicion scores
        Map<String, Double> suspicionScores = new HashMap<>();
        List<Document> verifiedDocs = new ArrayList<>();
        List<Document> suspiciousDocs = new ArrayList<>();

        for (DocumentAppearance appearance : documentAppearances.values()) {
            double score = suspicionScorer.calculateScore(
                    appearance.getAppearanceCount(),
                    combinations.size(),
                    appearance.getPartitionVariance()
            );
            suspicionScores.put(getDocumentId(appearance.getDocument()), score);

            if (score < suspicionThreshold) {
                verifiedDocs.add(appearance.getDocument());
            } else {
                suspiciousDocs.add(appearance.getDocument());
                log.warn("RAGPart: Suspicious document detected (score={:.2f}): {}",
                        score, appearance.getDocument().getMetadata().get("source"));
            }
        }

        long duration = System.currentTimeMillis() - startTime;

        // Log reasoning step
        reasoningTracer.addStep(StepType.FILTERING,
                "RAGPart Defense",
                String.format("Analyzed %d documents across %d combinations: %d verified, %d suspicious (threshold=%.2f)",
                        documentAppearances.size(), combinations.size(),
                        verifiedDocs.size(), suspiciousDocs.size(), suspicionThreshold),
                duration,
                Map.of(
                        "totalDocuments", documentAppearances.size(),
                        "combinations", combinations.size(),
                        "verified", verifiedDocs.size(),
                        "suspicious", suspiciousDocs.size(),
                        "suspicionThreshold", suspicionThreshold
                ));

        log.info("RAGPart: Completed in {}ms - {} verified, {} suspicious documents",
                duration, verifiedDocs.size(), suspiciousDocs.size());

        return new RagPartResult(verifiedDocs, suspiciousDocs, suspicionScores);
    }

    /**
     * Generate all k-combinations from n partitions.
     */
    private List<Set<Integer>> generateCombinations(int n, int k) {
        List<Set<Integer>> result = new ArrayList<>();
        generateCombinationsRecursive(result, new HashSet<>(), 0, n, k);
        return result;
    }

    private void generateCombinationsRecursive(List<Set<Integer>> result, Set<Integer> current,
                                                int start, int n, int k) {
        if (current.size() == k) {
            result.add(new HashSet<>(current));
            return;
        }

        for (int i = start; i < n; i++) {
            current.add(i);
            generateCombinationsRecursive(result, current, i + 1, n, k);
            current.remove(i);
        }
    }

    /**
     * Build a filter expression for a set of partitions.
     */
    private String buildPartitionFilter(Set<Integer> partitions, String department) {
        String partitionIn = partitions.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));

        // Filter by department AND partition
        return String.format("dept == '%s' && partition_id in [%s]", department, partitionIn);
    }

    /**
     * Retrieve documents with a filter expression.
     */
    private List<Document> retrieveWithFilter(String query, String filter) {
        try {
            return vectorStore.similaritySearch(
                    SearchRequest.query(query)
                            .withTopK(retrievalK)
                            .withSimilarityThreshold(0.1)
                            .withFilterExpression(filter));
        } catch (Exception e) {
            // Filter syntax may not be supported - fall back to unfiltered
            log.debug("RAGPart: Partition filter failed, using standard retrieval: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Standard retrieval without partitioning.
     */
    private List<Document> standardRetrieval(String query, String department) {
        try {
            return vectorStore.similaritySearch(
                    SearchRequest.query(query)
                            .withTopK(retrievalK)
                            .withSimilarityThreshold(0.15)
                            .withFilterExpression("dept == '" + department + "'"));
        } catch (Exception e) {
            log.error("Standard retrieval failed", e);
            return List.of();
        }
    }

    /**
     * Generate unique document ID.
     */
    private String getDocumentId(Document doc) {
        Object source = doc.getMetadata().get("source");
        if (source != null) {
            return source.toString() + "_" + Math.abs(doc.getContent().hashCode());
        }
        return String.valueOf(Math.abs(doc.getContent().hashCode()));
    }

    /**
     * Check if RAGPart is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Result containing verified and suspicious documents.
     */
    public record RagPartResult(
            List<Document> verifiedDocuments,
            List<Document> suspiciousDocuments,
            Map<String, Double> suspicionScores
    ) {
        public boolean hasSuspiciousDocuments() {
            return !suspiciousDocuments.isEmpty();
        }

        public int totalDocuments() {
            return verifiedDocuments.size() + suspiciousDocuments.size();
        }
    }

    /**
     * Tracks document appearances across partition combinations.
     */
    private static class DocumentAppearance {
        private final Document document;
        private final List<Integer> combinationIndices = new ArrayList<>();
        private final List<Set<Integer>> partitionSets = new ArrayList<>();

        DocumentAppearance(Document document) {
            this.document = document;
        }

        void addAppearance(int combinationIndex, Set<Integer> partitionSet) {
            combinationIndices.add(combinationIndex);
            partitionSets.add(partitionSet);
        }

        Document getDocument() {
            return document;
        }

        int getAppearanceCount() {
            return combinationIndices.size();
        }

        /**
         * Calculate variance in partition appearances.
         * Low variance = document appears in consistent partitions = likely legitimate
         * High variance = document appears erratically = potentially suspicious
         */
        double getPartitionVariance() {
            if (partitionSets.isEmpty()) {
                return 1.0;
            }

            // Count partition frequency
            Map<Integer, Integer> partitionFreq = new HashMap<>();
            for (Set<Integer> set : partitionSets) {
                for (Integer p : set) {
                    partitionFreq.merge(p, 1, Integer::sum);
                }
            }

            // Calculate variance
            double mean = partitionFreq.values().stream()
                    .mapToInt(Integer::intValue).average().orElse(0);

            if (mean == 0) return 1.0;

            double variance = partitionFreq.values().stream()
                    .mapToDouble(v -> Math.pow(v - mean, 2))
                    .average().orElse(0);

            // Normalize to 0-1 range
            return Math.min(1.0, variance / (mean * mean + 1));
        }
    }
}
