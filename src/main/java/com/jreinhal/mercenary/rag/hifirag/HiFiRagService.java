/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  jakarta.annotation.PostConstruct
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.ai.document.Document
 *  org.springframework.ai.vectorstore.SearchRequest
 *  org.springframework.ai.vectorstore.VectorStore
 *  org.springframework.beans.factory.annotation.Value
 *  org.springframework.stereotype.Service
 */
package com.jreinhal.mercenary.rag.hifirag;

import com.jreinhal.mercenary.rag.hifirag.CrossEncoderReranker;
import com.jreinhal.mercenary.rag.hifirag.GapDetector;
import com.jreinhal.mercenary.reasoning.ReasoningStep;
import com.jreinhal.mercenary.reasoning.ReasoningTracer;
import jakarta.annotation.PostConstruct;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class HiFiRagService {
    private static final Logger log = LoggerFactory.getLogger(HiFiRagService.class);
    private static final double BROAD_RETRIEVAL_THRESHOLD = 0.1;
    private static final double STANDARD_RETRIEVAL_THRESHOLD = 0.15;
    private static final String DEPT_FILTER_PATTERN = "dept == '%s'";
    private final VectorStore vectorStore;
    private final CrossEncoderReranker reranker;
    private final GapDetector gapDetector;
    private final ReasoningTracer reasoningTracer;
    @Value(value="${sentinel.hifirag.enabled:true}")
    private boolean enabled;
    @Value(value="${sentinel.hifirag.initial-retrieval-k:20}")
    private int initialK;
    @Value(value="${sentinel.hifirag.filtered-top-k:5}")
    private int filteredK;
    @Value(value="${sentinel.hifirag.relevance-threshold:0.5}")
    private double relevanceThreshold;
    @Value(value="${sentinel.hifirag.max-iterations:2}")
    private int maxIterations;

    public HiFiRagService(VectorStore vectorStore, CrossEncoderReranker reranker, GapDetector gapDetector, ReasoningTracer reasoningTracer) {
        this.vectorStore = vectorStore;
        this.reranker = reranker;
        this.gapDetector = gapDetector;
        this.reasoningTracer = reasoningTracer;
    }

    @PostConstruct
    public void init() {
        log.info("HiFi-RAG Service initialized (enabled={}, initialK={}, filteredK={})", new Object[]{this.enabled, this.initialK, this.filteredK});
    }

    public List<Document> retrieve(String query, String department) {
        if (!this.enabled) {
            log.debug("HiFi-RAG disabled, falling back to standard retrieval");
            return this.standardRetrieval(query, department);
        }
        long startTime = System.currentTimeMillis();
        log.info("HiFi-RAG: Starting iterative retrieval for query: {}", query);
        LinkedHashMap<String, ScoredDocument> allDocuments = new LinkedHashMap<String, ScoredDocument>();
        HashSet<String> coveredConcepts = new HashSet<String>();
        List<String> queryConcepts = this.gapDetector.extractConcepts(query);
        log.debug("HiFi-RAG: Extracted {} concepts from query", queryConcepts.size());
        String currentQuery = query;
        for (int iteration = 0; iteration < this.maxIterations; ++iteration) {
            log.info("HiFi-RAG: Iteration {}/{}", (iteration + 1), this.maxIterations);
            long iterStart = System.currentTimeMillis();
            List<Document> candidates = this.broadRetrieval(currentQuery, department, this.initialK);
            log.debug("HiFi-RAG: Pass 1 retrieved {} candidates", candidates.size());
            if (candidates.isEmpty()) {
                log.warn("HiFi-RAG: No candidates found in iteration {}", (iteration + 1));
                break;
            }
            List<ScoredDocument> scoredDocs = this.reranker.rerank(currentQuery, candidates);
            log.debug("HiFi-RAG: Scored {} documents", scoredDocs.size());
            for (ScoredDocument sd : scoredDocs) {
                String docId;
                if (!(sd.score() >= this.relevanceThreshold) || allDocuments.containsKey(docId = this.getDocumentId(sd.document())) && !(((ScoredDocument)allDocuments.get(docId)).score() < sd.score())) continue;
                allDocuments.put(docId, sd);
            }
            for (ScoredDocument sd : scoredDocs) {
                if (!(sd.score() >= this.relevanceThreshold)) continue;
                coveredConcepts.addAll(this.gapDetector.extractConcepts(sd.document().getContent()));
            }
            List<String> gaps = this.gapDetector.findGaps(queryConcepts, coveredConcepts);
            this.reasoningTracer.addStep(ReasoningStep.StepType.RETRIEVAL, "HiFi-RAG Pass " + (iteration + 1), String.format("Retrieved %d candidates, %d passed threshold (%.2f), %d concepts covered, %d gaps remaining", candidates.size(), (int)scoredDocs.stream().filter(s -> s.score() >= this.relevanceThreshold).count(), this.relevanceThreshold, coveredConcepts.size(), gaps.size()), System.currentTimeMillis() - iterStart, Map.of("iteration", iteration + 1, "candidates", candidates.size(), "passedThreshold", scoredDocs.stream().filter(s -> s.score() >= this.relevanceThreshold).count(), "coveredConcepts", coveredConcepts.size(), "gaps", gaps));
            if (gaps.isEmpty() || iteration == this.maxIterations - 1) {
                log.info("HiFi-RAG: No gaps remaining or max iterations reached");
                break;
            }
            currentQuery = this.gapDetector.generateGapQuery(query, gaps);
            log.debug("HiFi-RAG: Generated gap query: {}", currentQuery);
        }
        List<Document> finalDocs = allDocuments.values().stream().sorted((a, b) -> Double.compare(b.score(), a.score())).limit(this.filteredK).map(ScoredDocument::document).collect(Collectors.toList());
        long totalTime = System.currentTimeMillis() - startTime;
        log.info("HiFi-RAG: Completed in {}ms, returning {} documents", totalTime, finalDocs.size());
        return finalDocs;
    }

    private List<Document> broadRetrieval(String query, String department, int topK) {
        try {
            return this.vectorStore.similaritySearch(SearchRequest.query((String)query).withTopK(topK).withSimilarityThreshold(0.1).withFilterExpression(String.format(DEPT_FILTER_PATTERN, department)));
        }
        catch (Exception e) {
            log.error("HiFi-RAG: Broad retrieval failed", (Throwable)e);
            return List.of();
        }
    }

    private List<Document> standardRetrieval(String query, String department) {
        try {
            return this.vectorStore.similaritySearch(SearchRequest.query((String)query).withTopK(this.filteredK).withSimilarityThreshold(0.15).withFilterExpression(String.format(DEPT_FILTER_PATTERN, department)));
        }
        catch (Exception e) {
            log.error("Standard retrieval failed", (Throwable)e);
            return List.of();
        }
    }

    private String getDocumentId(Document doc) {
        Object source = doc.getMetadata().get("source");
        if (source != null) {
            return source.toString() + "_" + doc.getContent().hashCode();
        }
        return String.valueOf(doc.getContent().hashCode());
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public record ScoredDocument(Document document, double score) {
    }
}
