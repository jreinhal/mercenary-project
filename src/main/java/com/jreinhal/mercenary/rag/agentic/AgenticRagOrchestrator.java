package com.jreinhal.mercenary.rag.agentic;

import com.jreinhal.mercenary.rag.adaptiverag.AdaptiveRagService;
import com.jreinhal.mercenary.rag.adaptiverag.AdaptiveRagService.RoutingDecision;
import com.jreinhal.mercenary.rag.crag.CragGraderService;
import com.jreinhal.mercenary.rag.crag.CragGraderService.CragDecision;
import com.jreinhal.mercenary.rag.crag.CragGraderService.CragResult;
import com.jreinhal.mercenary.rag.crag.RewriteService;
import com.jreinhal.mercenary.rag.hyde.HydeService;
import com.jreinhal.mercenary.rag.selfrag.SelfRagService;
import com.jreinhal.mercenary.rag.selfrag.SelfRagService.SelfRagResult;
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

/**
 * Agentic RAG Orchestrator
 * Based on the Agentic RAG architecture from "9 RAG Architectures" article.
 *
 * This orchestrator coordinates multiple RAG strategies to handle complex queries:
 *
 * Pipeline:
 * 1. ANALYZE: Route query via AdaptiveRagService
 * 2. RETRIEVE: Select appropriate retrieval strategy (standard, HyDE, multi-hop)
 * 3. VALIDATE: Grade retrieved documents via CRAG
 * 4. ITERATE: Rewrite query if validation fails
 * 5. GENERATE: Produce response with optional Self-RAG reflection
 *
 * The orchestrator intelligently combines:
 * - Adaptive routing (skip retrieval for simple queries)
 * - HyDE for vague queries
 * - CRAG for document validation
 * - Self-RAG for high-stakes responses
 * - Query rewriting for failed retrievals
 *
 * Note: This is the most sophisticated RAG pipeline. Use for government
 * edition where query complexity varies widely and accuracy is critical.
 */
@Service
public class AgenticRagOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgenticRagOrchestrator.class);

    // Dependencies
    private final AdaptiveRagService adaptiveRag;
    private final CragGraderService cragGrader;
    private final RewriteService rewriteService;
    private final HydeService hydeService;
    private final SelfRagService selfRag;
    private final VectorStore vectorStore;
    private final ReasoningTracer reasoningTracer;

    // Configuration
    @Value("${sentinel.agentic.enabled:false}")
    private boolean enabled;

    @Value("${sentinel.agentic.max-iterations:3}")
    private int maxIterations;

    @Value("${sentinel.agentic.use-hyde:true}")
    private boolean useHyde;

    @Value("${sentinel.agentic.use-selfrag:true}")
    private boolean useSelfRag;

    @Value("${sentinel.agentic.confidence-threshold:0.6}")
    private double confidenceThreshold;

    /**
     * Result of agentic RAG orchestration.
     */
    public record AgenticResult(
            String response,
            List<Document> sources,
            double confidence,
            List<String> executedSteps,
            int iterations,
            Map<String, Object> metrics) {
    }

    public AgenticRagOrchestrator(
            AdaptiveRagService adaptiveRag,
            CragGraderService cragGrader,
            RewriteService rewriteService,
            HydeService hydeService,
            SelfRagService selfRag,
            VectorStore vectorStore,
            ReasoningTracer reasoningTracer) {
        this.adaptiveRag = adaptiveRag;
        this.cragGrader = cragGrader;
        this.rewriteService = rewriteService;
        this.hydeService = hydeService;
        this.selfRag = selfRag;
        this.vectorStore = vectorStore;
        this.reasoningTracer = reasoningTracer;
    }

    @PostConstruct
    public void init() {
        log.info("Agentic RAG Orchestrator initialized (enabled={}, maxIter={}, hyde={}, selfrag={})",
                enabled, maxIterations, useHyde, useSelfRag);
    }

    /**
     * Execute the full agentic RAG pipeline.
     *
     * @param query User's query
     * @param department Security department filter
     * @return AgenticResult with response and metadata
     */
    public AgenticResult process(String query, String department) {
        long startTime = System.currentTimeMillis();
        List<String> executedSteps = new ArrayList<>();
        Map<String, Object> metrics = new LinkedHashMap<>();

        if (!enabled) {
            log.debug("Agentic orchestrator disabled, performing simple retrieval");
            return simpleRetrieval(query, department, startTime);
        }

        // === STEP 1: ANALYZE - Route the query ===
        executedSteps.add("ANALYZE");
        var routing = adaptiveRag.route(query);
        metrics.put("routingDecision", routing.decision().name());

        // Handle NO_RETRIEVAL case (conversational queries)
        if (routing.decision() == RoutingDecision.NO_RETRIEVAL) {
            executedSteps.add("DIRECT_RESPONSE");
            return directResponse(query, executedSteps, startTime, metrics);
        }

        // === STEP 2: RETRIEVE - Select and execute retrieval strategy ===
        List<Document> documents = null;
        String currentQuery = query;

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            metrics.put("iteration", iteration + 1);

            // Choose retrieval strategy based on routing signals
            boolean shouldUseHyde = useHyde && (
                    hydeService.shouldUseHyde(routing.signals()) ||
                    hydeService.isSuitableForHyde(currentQuery));

            if (shouldUseHyde) {
                executedSteps.add("HYDE_RETRIEVAL");
                var hydeResult = hydeService.retrieve(currentQuery, department);
                documents = hydeResult.documents();
                metrics.put("hydeApplied", true);
                metrics.put("hydeSource", hydeService.shouldUseHyde(routing.signals()) ? "routing_signal" : "query_analysis");
            } else {
                executedSteps.add("STANDARD_RETRIEVAL");
                documents = standardRetrieval(currentQuery, department, routing);
                metrics.put("hydeApplied", false);
            }

            log.debug("Agentic: Iteration {} retrieved {} documents", iteration + 1, documents.size());

            // === STEP 3: VALIDATE - Grade retrieved documents ===
            executedSteps.add("CRAG_VALIDATION");
            CragResult cragResult = cragGrader.evaluate(currentQuery, documents);
            metrics.put("cragDecision", cragResult.decision().name());
            metrics.put("cragConfidence", cragResult.overallConfidence());

            // Check if we have sufficient evidence
            if (cragResult.decision() == CragDecision.USE_RETRIEVED ||
                cragResult.decision() == CragDecision.SUPPLEMENT_NEEDED) {
                // Good enough to proceed
                documents = cragGrader.getUsableDocuments(cragResult);
                break;
            }

            // === STEP 4: ITERATE - Rewrite query if validation failed ===
            if (iteration < maxIterations - 1 && cragResult.decision() == CragDecision.REWRITE_NEEDED) {
                executedSteps.add("QUERY_REWRITE");
                currentQuery = rewriteService.rewriteQuery(currentQuery);
                metrics.put("rewrittenQuery", currentQuery);
                log.info("Agentic: Rewriting query to: {}", currentQuery);
            } else {
                // Max iterations or insufficient evidence - proceed with what we have
                documents = cragGrader.getUsableDocuments(cragResult);
                if (documents.isEmpty()) {
                    executedSteps.add("INSUFFICIENT_EVIDENCE");
                    return insufficientEvidence(query, executedSteps, startTime, metrics);
                }
                break;
            }
        }

        // === STEP 5: GENERATE - Produce response ===
        String response;
        double confidence;

        if (useSelfRag) {
            executedSteps.add("SELFRAG_GENERATION");
            SelfRagResult selfRagResult = selfRag.generateWithReflection(query, documents);
            response = selfRagResult.cleanResponse();
            confidence = selfRagResult.overallConfidence();
            metrics.put("selfRagClaims", selfRagResult.claims().size());
            metrics.put("uncertainClaims", selfRagResult.uncertainClaims().size());
        } else {
            executedSteps.add("STANDARD_GENERATION");
            response = generateStandardResponse(query, documents);
            confidence = 0.7; // Default confidence for non-Self-RAG
        }

        long elapsed = System.currentTimeMillis() - startTime;
        metrics.put("totalMs", elapsed);

        // Log orchestration summary
        reasoningTracer.addStep(StepType.ORCHESTRATION,
                "Agentic RAG Orchestration",
                String.format("Completed in %d steps, %d iterations, %.0f%% confidence",
                        executedSteps.size(), (int) metrics.getOrDefault("iteration", 1), confidence * 100),
                elapsed,
                metrics);

        log.info("Agentic: Completed {} steps in {}ms with {:.0f}% confidence",
                executedSteps.size(), elapsed, confidence * 100);

        return new AgenticResult(response, documents, confidence, executedSteps,
                (int) metrics.getOrDefault("iteration", 1), metrics);
    }

    /**
     * Simple retrieval fallback when orchestrator is disabled.
     */
    private AgenticResult simpleRetrieval(String query, String department, long startTime) {
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.query(query)
                        .withTopK(5)
                        .withSimilarityThreshold(0.3)
                        .withFilterExpression("dept == '" + department + "'"));

        String response = generateStandardResponse(query, docs);
        long elapsed = System.currentTimeMillis() - startTime;

        return new AgenticResult(response, docs, 0.7, List.of("SIMPLE_RETRIEVAL"), 1,
                Map.of("totalMs", elapsed, "mode", "disabled"));
    }

    /**
     * Direct response for NO_RETRIEVAL queries.
     */
    private AgenticResult directResponse(String query, List<String> steps, long startTime, Map<String, Object> metrics) {
        // For conversational queries, respond without retrieval
        String response = "I'm here to help you search our knowledge base. " +
                "Please ask a specific question about your documents.";
        long elapsed = System.currentTimeMillis() - startTime;
        metrics.put("totalMs", elapsed);

        return new AgenticResult(response, List.of(), 1.0, steps, 1, metrics);
    }

    /**
     * Response when we can't find sufficient evidence.
     */
    private AgenticResult insufficientEvidence(String query, List<String> steps, long startTime, Map<String, Object> metrics) {
        String response = "I couldn't find sufficient information in the knowledge base to answer your question. " +
                "Please try rephrasing your query or contact an administrator if you believe the information should be available.";
        long elapsed = System.currentTimeMillis() - startTime;
        metrics.put("totalMs", elapsed);

        return new AgenticResult(response, List.of(), 0.2, steps, (int) metrics.getOrDefault("iteration", 1), metrics);
    }

    /**
     * Standard retrieval using routing decision parameters.
     */
    private List<Document> standardRetrieval(String query, String department,
                                              AdaptiveRagService.RoutingResult routing) {
        int topK = adaptiveRag.getTopK(routing.decision());
        double threshold = adaptiveRag.getSimilarityThreshold(routing.decision());

        return vectorStore.similaritySearch(
                SearchRequest.query(query)
                        .withTopK(topK)
                        .withSimilarityThreshold(threshold)
                        .withFilterExpression("dept == '" + department + "'"));
    }

    /**
     * Generate response without Self-RAG.
     */
    private String generateStandardResponse(String query, List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return "No relevant information found.";
        }

        StringBuilder context = new StringBuilder();
        for (Document doc : documents) {
            context.append(doc.getContent()).append("\n\n");
        }

        // Note: In production, this would use ChatClient for generation
        // For now, return a placeholder indicating the context was found
        return "Based on the retrieved information: " + context.substring(0, Math.min(500, context.length())) + "...";
    }

    public boolean isEnabled() {
        return enabled;
    }
}
