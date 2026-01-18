package com.jreinhal.mercenary.rag.hgmem;

import com.jreinhal.mercenary.reasoning.ReasoningTracer;
import com.jreinhal.mercenary.reasoning.ReasoningStep.StepType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * HGMem Query Engine - Combines hypergraph traversal with vector search.
 *
 * Provides multi-hop RAG by:
 * 1. Extracting entities from query
 * 2. Finding related content via hypergraph traversal
 * 3. Combining with vector search results
 * 4. Deduplicating and ranking final results
 */
@Service
public class HGMemQueryEngine {

    private static final Logger log = LoggerFactory.getLogger(HGMemQueryEngine.class);

    private final HyperGraphMemory hypergraph;
    private final VectorStore vectorStore;
    private final ReasoningTracer reasoningTracer;

    @Value("${sentinel.hgmem.enabled:true}")
    private boolean enabled;

    @Value("${sentinel.hgmem.max-hops:3}")
    private int maxHops;

    @Value("${sentinel.hgmem.vector-weight:0.6}")
    private double vectorWeight;

    @Value("${sentinel.hgmem.graph-weight:0.4}")
    private double graphWeight;

    @Value("${sentinel.hgmem.top-k:10}")
    private int topK;

    public HGMemQueryEngine(HyperGraphMemory hypergraph, VectorStore vectorStore,
                            ReasoningTracer reasoningTracer) {
        this.hypergraph = hypergraph;
        this.vectorStore = vectorStore;
        this.reasoningTracer = reasoningTracer;
    }

    @PostConstruct
    public void init() {
        log.info("HGMem Query Engine initialized (enabled={}, maxHops={}, vectorWeight={}, graphWeight={})",
                enabled, maxHops, vectorWeight, graphWeight);
    }

    /**
     * Execute a hybrid query combining vector search and hypergraph traversal.
     *
     * @param query The user's query
     * @param department Department filter
     * @return Combined and ranked documents
     */
    public HGMemResult query(String query, String department) {
        if (!enabled) {
            log.debug("HGMem disabled, using vector search only");
            List<Document> vectorResults = vectorSearch(query, department);
            return new HGMemResult(vectorResults, List.of(), List.of(), 0);
        }

        long startTime = System.currentTimeMillis();
        log.info("HGMem: Starting hybrid query for: {}", query);

        // Step 1: Vector search (traditional RAG)
        long vectorStart = System.currentTimeMillis();
        List<Document> vectorResults = vectorSearch(query, department);
        long vectorTime = System.currentTimeMillis() - vectorStart;
        log.debug("HGMem: Vector search returned {} results in {}ms", vectorResults.size(), vectorTime);

        // Step 2: Hypergraph traversal
        long graphStart = System.currentTimeMillis();
        HyperGraphMemory.HGQueryResult graphResult = hypergraph.query(query, department, maxHops);
        long graphTime = System.currentTimeMillis() - graphStart;
        log.debug("HGMem: Graph traversal returned {} results in {}ms",
                graphResult.relatedChunks().size(), graphTime);

        // Step 3: Combine and deduplicate
        Map<String, ScoredDocument> combinedDocs = new LinkedHashMap<>();

        // Add vector results with vector weight
        for (int i = 0; i < vectorResults.size(); i++) {
            Document doc = vectorResults.get(i);
            String docId = getDocumentId(doc);
            double score = vectorWeight * (1.0 - (double) i / vectorResults.size()); // Decay by position
            combinedDocs.put(docId, new ScoredDocument(doc, score, "vector"));
        }

        // Add graph results with graph weight
        for (HyperGraphMemory.HGNode chunk : graphResult.relatedChunks()) {
            String docId = chunk.getId();

            // Try to find corresponding vector document or create from chunk
            Document graphDoc = createDocumentFromChunk(chunk);

            if (combinedDocs.containsKey(docId)) {
                // Boost score for documents found by both methods
                ScoredDocument existing = combinedDocs.get(docId);
                double boostedScore = existing.score() + graphWeight * 0.5;
                combinedDocs.put(docId, new ScoredDocument(existing.document(), boostedScore, "hybrid"));
            } else {
                double score = graphWeight * 0.8; // Slightly lower for graph-only
                combinedDocs.put(docId, new ScoredDocument(graphDoc, score, "graph"));
            }
        }

        // Step 4: Sort by score and limit
        List<Document> finalResults = combinedDocs.values().stream()
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(topK)
                .map(ScoredDocument::document)
                .collect(Collectors.toList());

        long totalTime = System.currentTimeMillis() - startTime;

        // Log reasoning step
        reasoningTracer.addStep(StepType.RETRIEVAL,
                "HGMem Hybrid Query",
                String.format("Vector: %d docs (%dms), Graph: %d nodes (%dms), Final: %d docs",
                        vectorResults.size(), vectorTime,
                        graphResult.nodesTraversed(), graphTime,
                        finalResults.size()),
                totalTime,
                Map.of(
                        "vectorResults", vectorResults.size(),
                        "graphNodes", graphResult.nodesTraversed(),
                        "matchedEntities", graphResult.matchedEntities(),
                        "finalResults", finalResults.size(),
                        "vectorTimeMs", vectorTime,
                        "graphTimeMs", graphTime
                ));

        log.info("HGMem: Hybrid query completed in {}ms, returning {} documents", totalTime, finalResults.size());

        return new HGMemResult(
                finalResults,
                graphResult.matchedEntities(),
                new ArrayList<>(graphResult.entityScores().keySet()),
                graphResult.nodesTraversed()
        );
    }

    /**
     * Standard vector search.
     */
    private List<Document> vectorSearch(String query, String department) {
        try {
            return vectorStore.similaritySearch(
                    SearchRequest.query(query)
                            .withTopK(topK)
                            .withSimilarityThreshold(0.15)
                            .withFilterExpression("dept == '" + department + "'"));
        } catch (Exception e) {
            log.error("Vector search failed", e);
            return List.of();
        }
    }

    /**
     * Create a Document from a hypergraph chunk node.
     */
    private Document createDocumentFromChunk(HyperGraphMemory.HGNode chunk) {
        Document doc = new Document(chunk.getValue());
        doc.getMetadata().put("source", chunk.getSourceDoc());
        doc.getMetadata().put("hgmem_node_id", chunk.getId());
        doc.getMetadata().put("dept", chunk.getDepartment());
        return doc;
    }

    /**
     * Generate document ID for deduplication.
     */
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

    /**
     * Check if HGMem is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Get hypergraph statistics.
     */
    public HyperGraphMemory.HGStats getGraphStats() {
        return hypergraph.getStats();
    }

    /**
     * Result from HGMem query.
     */
    public record HGMemResult(
            List<Document> documents,
            List<String> matchedEntities,
            List<String> relatedEntities,
            int nodesTraversed
    ) {
        public boolean hasGraphEnhancement() {
            return nodesTraversed > 0;
        }
    }

    /**
     * Document with score and source tracking.
     */
    private record ScoredDocument(Document document, double score, String source) {}
}
