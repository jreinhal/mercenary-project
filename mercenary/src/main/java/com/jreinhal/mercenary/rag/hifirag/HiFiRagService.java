package com.jreinhal.mercenary.rag.hifirag;

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
 * HiFi-RAG: Hierarchical Filtering RAG Pipeline
 * Based on research paper arXiv:2512.22442v1
 *
 * Implements iterative two-pass retrieval:
 * 1. PASS 1: Broad retrieval (high recall) - cast a wide net
 * 2. FILTER: Cross-encoder scoring to identify truly relevant documents
 * 3. GAP DETECTION: Identify concepts not covered by initial results
 * 4. PASS 2: Targeted retrieval for gaps
 * 5. MERGE & RERANK: Final context assembly
 *
 * This provides significantly better precision than single-pass retrieval
 * while maintaining high recall through iterative gap-filling.
 */
@Service
public class HiFiRagService {

    private static final Logger log = LoggerFactory.getLogger(HiFiRagService.class);

    // Similarity thresholds for retrieval passes
    private static final double BROAD_RETRIEVAL_THRESHOLD = 0.1;    // Low threshold for high recall
    private static final double STANDARD_RETRIEVAL_THRESHOLD = 0.15; // Higher threshold for precision

    // Filter expression pattern for department-based filtering
    private static final String DEPT_FILTER_PATTERN = "dept == '%s'";

    private final VectorStore vectorStore;
    private final CrossEncoderReranker reranker;
    private final GapDetector gapDetector;
    private final ReasoningTracer reasoningTracer;

    @Value("${sentinel.hifirag.enabled:true}")
    private boolean enabled;

    @Value("${sentinel.hifirag.initial-retrieval-k:20}")
    private int initialK;

    @Value("${sentinel.hifirag.filtered-top-k:5}")
    private int filteredK;

    @Value("${sentinel.hifirag.relevance-threshold:0.5}")
    private double relevanceThreshold;

    @Value("${sentinel.hifirag.max-iterations:2}")
    private int maxIterations;

    public HiFiRagService(VectorStore vectorStore, CrossEncoderReranker reranker,
                          GapDetector gapDetector, ReasoningTracer reasoningTracer) {
        this.vectorStore = vectorStore;
        this.reranker = reranker;
        this.gapDetector = gapDetector;
        this.reasoningTracer = reasoningTracer;
    }

    @PostConstruct
    public void init() {
        log.info("HiFi-RAG Service initialized (enabled={}, initialK={}, filteredK={})",
                enabled, initialK, filteredK);
    }

    /**
     * Execute the full HiFi-RAG pipeline.
     *
     * @param query The user's query
     * @param department Department filter
     * @return Ranked list of relevant documents
     */
    public List<Document> retrieve(String query, String department) {
        if (!enabled) {
            log.debug("HiFi-RAG disabled, falling back to standard retrieval");
            return standardRetrieval(query, department);
        }

        long startTime = System.currentTimeMillis();
        log.info("HiFi-RAG: Starting iterative retrieval for query: {}", query);

        // Track all retrieved documents across iterations
        Map<String, ScoredDocument> allDocuments = new LinkedHashMap<>();
        Set<String> coveredConcepts = new HashSet<>();

        // Extract key concepts from the query
        List<String> queryConcepts = gapDetector.extractConcepts(query);
        log.debug("HiFi-RAG: Extracted {} concepts from query", queryConcepts.size());

        // ITERATION LOOP
        String currentQuery = query;
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            log.info("HiFi-RAG: Iteration {}/{}", iteration + 1, maxIterations);
            long iterStart = System.currentTimeMillis();

            // PASS 1: Broad retrieval
            List<Document> candidates = broadRetrieval(currentQuery, department, initialK);
            log.debug("HiFi-RAG: Pass 1 retrieved {} candidates", candidates.size());

            if (candidates.isEmpty()) {
                log.warn("HiFi-RAG: No candidates found in iteration {}", iteration + 1);
                break;
            }

            // FILTER: Cross-encoder scoring
            List<ScoredDocument> scoredDocs = reranker.rerank(currentQuery, candidates);
            log.debug("HiFi-RAG: Scored {} documents", scoredDocs.size());

            // Add high-scoring documents
            for (ScoredDocument sd : scoredDocs) {
                if (sd.score() >= relevanceThreshold) {
                    String docId = getDocumentId(sd.document());
                    if (!allDocuments.containsKey(docId) || allDocuments.get(docId).score() < sd.score()) {
                        allDocuments.put(docId, sd);
                    }
                }
            }

            // Track covered concepts
            for (ScoredDocument sd : scoredDocs) {
                if (sd.score() >= relevanceThreshold) {
                    coveredConcepts.addAll(gapDetector.extractConcepts(sd.document().getContent()));
                }
            }

            // GAP DETECTION: Find uncovered concepts
            List<String> gaps = gapDetector.findGaps(queryConcepts, coveredConcepts);

            // Log reasoning step
            reasoningTracer.addStep(StepType.RETRIEVAL,
                    "HiFi-RAG Pass " + (iteration + 1),
                    String.format("Retrieved %d candidates, %d passed threshold (%.2f), %d concepts covered, %d gaps remaining",
                            candidates.size(),
                            (int) scoredDocs.stream().filter(s -> s.score() >= relevanceThreshold).count(),
                            relevanceThreshold,
                            coveredConcepts.size(),
                            gaps.size()),
                    System.currentTimeMillis() - iterStart,
                    Map.of(
                            "iteration", iteration + 1,
                            "candidates", candidates.size(),
                            "passedThreshold", scoredDocs.stream().filter(s -> s.score() >= relevanceThreshold).count(),
                            "coveredConcepts", coveredConcepts.size(),
                            "gaps", gaps
                    ));

            if (gaps.isEmpty() || iteration == maxIterations - 1) {
                log.info("HiFi-RAG: No gaps remaining or max iterations reached");
                break;
            }

            // PASS 2 PREP: Generate targeted query for gaps
            currentQuery = gapDetector.generateGapQuery(query, gaps);
            log.debug("HiFi-RAG: Generated gap query: {}", currentQuery);
        }

        // FINAL: Sort by score and return top K
        List<Document> finalDocs = allDocuments.values().stream()
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(filteredK)
                .map(ScoredDocument::document)
                .collect(Collectors.toList());

        long totalTime = System.currentTimeMillis() - startTime;
        log.info("HiFi-RAG: Completed in {}ms, returning {} documents", totalTime, finalDocs.size());

        return finalDocs;
    }

    /**
     * Broad retrieval pass - prioritizes recall over precision.
     */
    private List<Document> broadRetrieval(String query, String department, int topK) {
        try {
            return vectorStore.similaritySearch(
                    SearchRequest.query(query)
                            .withTopK(topK)
                            .withSimilarityThreshold(BROAD_RETRIEVAL_THRESHOLD)
                            .withFilterExpression(String.format(DEPT_FILTER_PATTERN, department)));
        } catch (Exception e) {
            log.error("HiFi-RAG: Broad retrieval failed", e);
            return List.of();
        }
    }

    /**
     * Standard single-pass retrieval (fallback when HiFi-RAG disabled).
     */
    private List<Document> standardRetrieval(String query, String department) {
        try {
            return vectorStore.similaritySearch(
                    SearchRequest.query(query)
                            .withTopK(filteredK)
                            .withSimilarityThreshold(STANDARD_RETRIEVAL_THRESHOLD)
                            .withFilterExpression(String.format(DEPT_FILTER_PATTERN, department)));
        } catch (Exception e) {
            log.error("Standard retrieval failed", e);
            return List.of();
        }
    }

    /**
     * Generate a unique ID for deduplication.
     */
    private String getDocumentId(Document doc) {
        Object source = doc.getMetadata().get("source");
        if (source != null) {
            return source.toString() + "_" + doc.getContent().hashCode();
        }
        return String.valueOf(doc.getContent().hashCode());
    }

    /**
     * Check if HiFi-RAG is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Document with relevance score.
     */
    public record ScoredDocument(Document document, double score) {}
}
