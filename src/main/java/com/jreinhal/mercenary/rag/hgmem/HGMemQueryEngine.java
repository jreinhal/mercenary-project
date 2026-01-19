/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.jreinhal.mercenary.rag.hgmem.HGMemQueryEngine
 *  com.jreinhal.mercenary.rag.hgmem.HGMemQueryEngine$HGMemResult
 *  com.jreinhal.mercenary.rag.hgmem.HGMemQueryEngine$ScoredDocument
 *  com.jreinhal.mercenary.rag.hgmem.HyperGraphMemory
 *  com.jreinhal.mercenary.rag.hgmem.HyperGraphMemory$HGNode
 *  com.jreinhal.mercenary.rag.hgmem.HyperGraphMemory$HGQueryResult
 *  com.jreinhal.mercenary.rag.hgmem.HyperGraphMemory$HGStats
 *  com.jreinhal.mercenary.reasoning.ReasoningStep$StepType
 *  com.jreinhal.mercenary.reasoning.ReasoningTracer
 *  jakarta.annotation.PostConstruct
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.ai.document.Document
 *  org.springframework.ai.vectorstore.SearchRequest
 *  org.springframework.ai.vectorstore.VectorStore
 *  org.springframework.beans.factory.annotation.Value
 *  org.springframework.stereotype.Service
 */
package com.jreinhal.mercenary.rag.hgmem;

import com.jreinhal.mercenary.rag.hgmem.HGMemQueryEngine;
import com.jreinhal.mercenary.rag.hgmem.HyperGraphMemory;
import com.jreinhal.mercenary.reasoning.ReasoningStep;
import com.jreinhal.mercenary.reasoning.ReasoningTracer;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
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
public class HGMemQueryEngine {
    private static final Logger log = LoggerFactory.getLogger(HGMemQueryEngine.class);
    private final HyperGraphMemory hypergraph;
    private final VectorStore vectorStore;
    private final ReasoningTracer reasoningTracer;
    @Value(value="${sentinel.hgmem.enabled:true}")
    private boolean enabled;
    @Value(value="${sentinel.hgmem.max-hops:3}")
    private int maxHops;
    @Value(value="${sentinel.hgmem.vector-weight:0.6}")
    private double vectorWeight;
    @Value(value="${sentinel.hgmem.graph-weight:0.4}")
    private double graphWeight;
    @Value(value="${sentinel.hgmem.top-k:10}")
    private int topK;

    public HGMemQueryEngine(HyperGraphMemory hypergraph, VectorStore vectorStore, ReasoningTracer reasoningTracer) {
        this.hypergraph = hypergraph;
        this.vectorStore = vectorStore;
        this.reasoningTracer = reasoningTracer;
    }

    @PostConstruct
    public void init() {
        log.info("HGMem Query Engine initialized (enabled={}, maxHops={}, vectorWeight={}, graphWeight={})", new Object[]{this.enabled, this.maxHops, this.vectorWeight, this.graphWeight});
    }

    public HGMemResult query(String query, String department) {
        String docId;
        if (!this.enabled) {
            log.debug("HGMem disabled, using vector search only");
            List vectorResults = this.vectorSearch(query, department);
            return new HGMemResult(vectorResults, List.of(), List.of(), 0);
        }
        long startTime = System.currentTimeMillis();
        log.info("HGMem: Starting hybrid query for: {}", (Object)query);
        long vectorStart = System.currentTimeMillis();
        List vectorResults = this.vectorSearch(query, department);
        long vectorTime = System.currentTimeMillis() - vectorStart;
        log.debug("HGMem: Vector search returned {} results in {}ms", (Object)vectorResults.size(), (Object)vectorTime);
        long graphStart = System.currentTimeMillis();
        HyperGraphMemory.HGQueryResult graphResult = this.hypergraph.query(query, department, this.maxHops);
        long graphTime = System.currentTimeMillis() - graphStart;
        log.debug("HGMem: Graph traversal returned {} results in {}ms", (Object)graphResult.relatedChunks().size(), (Object)graphTime);
        LinkedHashMap<String, ScoredDocument> combinedDocs = new LinkedHashMap<String, ScoredDocument>();
        for (int i = 0; i < vectorResults.size(); ++i) {
            Document doc = (Document)vectorResults.get(i);
            docId = this.getDocumentId(doc);
            double score = this.vectorWeight * (1.0 - (double)i / (double)vectorResults.size());
            combinedDocs.put(docId, new ScoredDocument(doc, score, "vector"));
        }
        for (HyperGraphMemory.HGNode chunk : graphResult.relatedChunks()) {
            docId = chunk.getId();
            Document graphDoc = this.createDocumentFromChunk(chunk);
            if (combinedDocs.containsKey(docId)) {
                ScoredDocument existing = (ScoredDocument)combinedDocs.get(docId);
                double boostedScore = existing.score() + this.graphWeight * 0.5;
                combinedDocs.put(docId, new ScoredDocument(existing.document(), boostedScore, "hybrid"));
                continue;
            }
            double score = this.graphWeight * 0.8;
            combinedDocs.put(docId, new ScoredDocument(graphDoc, score, "graph"));
        }
        List finalResults = combinedDocs.values().stream().sorted((a, b) -> Double.compare(b.score(), a.score())).limit(this.topK).map(ScoredDocument::document).collect(Collectors.toList());
        long totalTime = System.currentTimeMillis() - startTime;
        this.reasoningTracer.addStep(ReasoningStep.StepType.RETRIEVAL, "HGMem Hybrid Query", String.format("Vector: %d docs (%dms), Graph: %d nodes (%dms), Final: %d docs", vectorResults.size(), vectorTime, graphResult.nodesTraversed(), graphTime, finalResults.size()), totalTime, Map.of("vectorResults", vectorResults.size(), "graphNodes", graphResult.nodesTraversed(), "matchedEntities", graphResult.matchedEntities(), "finalResults", finalResults.size(), "vectorTimeMs", vectorTime, "graphTimeMs", graphTime));
        log.info("HGMem: Hybrid query completed in {}ms, returning {} documents", (Object)totalTime, (Object)finalResults.size());
        return new HGMemResult(finalResults, graphResult.matchedEntities(), new ArrayList(graphResult.entityScores().keySet()), graphResult.nodesTraversed());
    }

    private List<Document> vectorSearch(String query, String department) {
        try {
            return this.vectorStore.similaritySearch(SearchRequest.query((String)query).withTopK(this.topK).withSimilarityThreshold(0.15).withFilterExpression("dept == '" + department + "'"));
        }
        catch (Exception e) {
            log.error("Vector search failed", (Throwable)e);
            return List.of();
        }
    }

    private Document createDocumentFromChunk(HyperGraphMemory.HGNode chunk) {
        Document doc = new Document(chunk.getValue());
        doc.getMetadata().put("source", chunk.getSourceDoc());
        doc.getMetadata().put("hgmem_node_id", chunk.getId());
        doc.getMetadata().put("dept", chunk.getDepartment());
        return doc;
    }

    private String getDocumentId(Document doc) {
        Object source = doc.getMetadata().get("source");
        Object nodeId = doc.getMetadata().get("hgmem_node_id");
        if (nodeId != null) {
            return nodeId.toString();
        }
        if (source != null) {
            return source.toString() + "_" + Math.abs(doc.getContent().hashCode());
        }
        return String.valueOf(Math.abs(doc.getContent().hashCode()));
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public HyperGraphMemory.HGStats getGraphStats() {
        return this.hypergraph.getStats();
    }
}

