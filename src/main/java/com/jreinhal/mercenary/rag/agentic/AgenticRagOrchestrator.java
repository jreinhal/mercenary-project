/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.jreinhal.mercenary.rag.adaptiverag.AdaptiveRagService
 *  com.jreinhal.mercenary.rag.adaptiverag.AdaptiveRagService$RoutingDecision
 *  com.jreinhal.mercenary.rag.adaptiverag.AdaptiveRagService$RoutingResult
 *  com.jreinhal.mercenary.rag.agentic.AgenticRagOrchestrator
 *  com.jreinhal.mercenary.rag.agentic.AgenticRagOrchestrator$AgenticResult
 *  com.jreinhal.mercenary.rag.crag.CragGraderService
 *  com.jreinhal.mercenary.rag.crag.CragGraderService$CragDecision
 *  com.jreinhal.mercenary.rag.crag.CragGraderService$CragResult
 *  com.jreinhal.mercenary.rag.crag.RewriteService
 *  com.jreinhal.mercenary.rag.hyde.HydeService
 *  com.jreinhal.mercenary.rag.hyde.HydeService$HydeResult
 *  com.jreinhal.mercenary.rag.selfrag.SelfRagService
 *  com.jreinhal.mercenary.rag.selfrag.SelfRagService$SelfRagResult
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
package com.jreinhal.mercenary.rag.agentic;

import com.jreinhal.mercenary.rag.adaptiverag.AdaptiveRagService;
import com.jreinhal.mercenary.rag.agentic.AgenticRagOrchestrator;
import com.jreinhal.mercenary.rag.crag.CragGraderService;
import com.jreinhal.mercenary.rag.crag.RewriteService;
import com.jreinhal.mercenary.rag.hyde.HydeService;
import com.jreinhal.mercenary.rag.selfrag.SelfRagService;
import com.jreinhal.mercenary.reasoning.ReasoningStep;
import com.jreinhal.mercenary.reasoning.ReasoningTracer;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AgenticRagOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(AgenticRagOrchestrator.class);
    private final AdaptiveRagService adaptiveRag;
    private final CragGraderService cragGrader;
    private final RewriteService rewriteService;
    private final HydeService hydeService;
    private final SelfRagService selfRag;
    private final VectorStore vectorStore;
    private final ReasoningTracer reasoningTracer;
    @Value(value="${sentinel.agentic.enabled:false}")
    private boolean enabled;
    @Value(value="${sentinel.agentic.max-iterations:3}")
    private int maxIterations;
    @Value(value="${sentinel.agentic.use-hyde:true}")
    private boolean useHyde;
    @Value(value="${sentinel.agentic.use-selfrag:true}")
    private boolean useSelfRag;
    @Value(value="${sentinel.agentic.confidence-threshold:0.6}")
    private double confidenceThreshold;

    public AgenticRagOrchestrator(AdaptiveRagService adaptiveRag, CragGraderService cragGrader, RewriteService rewriteService, HydeService hydeService, SelfRagService selfRag, VectorStore vectorStore, ReasoningTracer reasoningTracer) {
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
        log.info("Agentic RAG Orchestrator initialized (enabled={}, maxIter={}, hyde={}, selfrag={})", new Object[]{this.enabled, this.maxIterations, this.useHyde, this.useSelfRag});
    }

    public AgenticResult process(String query, String department) {
        double confidence;
        String response;
        long startTime = System.currentTimeMillis();
        ArrayList<String> executedSteps = new ArrayList<String>();
        LinkedHashMap<String, Object> metrics = new LinkedHashMap<String, Object>();
        if (!this.enabled) {
            log.debug("Agentic orchestrator disabled, performing simple retrieval");
            return this.simpleRetrieval(query, department, startTime);
        }
        executedSteps.add("ANALYZE");
        AdaptiveRagService.RoutingResult routing = this.adaptiveRag.route(query);
        metrics.put("routingDecision", routing.decision().name());
        if (routing.decision() == AdaptiveRagService.RoutingDecision.NO_RETRIEVAL) {
            executedSteps.add("DIRECT_RESPONSE");
            return this.directResponse(query, executedSteps, startTime, metrics);
        }
        List documents = null;
        String currentQuery = query;
        for (int iteration = 0; iteration < this.maxIterations; ++iteration) {
            boolean shouldUseHyde;
            metrics.put("iteration", iteration + 1);
            boolean bl = shouldUseHyde = this.useHyde && (this.hydeService.shouldUseHyde(routing.signals()) || this.hydeService.isSuitableForHyde(currentQuery));
            if (shouldUseHyde) {
                executedSteps.add("HYDE_RETRIEVAL");
                HydeService.HydeResult hydeResult = this.hydeService.retrieve(currentQuery, department);
                documents = hydeResult.documents();
                metrics.put("hydeApplied", true);
                metrics.put("hydeSource", this.hydeService.shouldUseHyde(routing.signals()) ? "routing_signal" : "query_analysis");
            } else {
                executedSteps.add("STANDARD_RETRIEVAL");
                documents = this.standardRetrieval(currentQuery, department, routing);
                metrics.put("hydeApplied", false);
            }
            log.debug("Agentic: Iteration {} retrieved {} documents", (Object)(iteration + 1), (Object)documents.size());
            executedSteps.add("CRAG_VALIDATION");
            CragGraderService.CragResult cragResult = this.cragGrader.evaluate(currentQuery, documents);
            metrics.put("cragDecision", cragResult.decision().name());
            metrics.put("cragConfidence", cragResult.overallConfidence());
            if (cragResult.decision() == CragGraderService.CragDecision.USE_RETRIEVED || cragResult.decision() == CragGraderService.CragDecision.SUPPLEMENT_NEEDED) {
                documents = this.cragGrader.getUsableDocuments(cragResult);
                break;
            }
            if (iteration >= this.maxIterations - 1 || cragResult.decision() != CragGraderService.CragDecision.REWRITE_NEEDED) {
                documents = this.cragGrader.getUsableDocuments(cragResult);
                if (!documents.isEmpty()) break;
                executedSteps.add("INSUFFICIENT_EVIDENCE");
                return this.insufficientEvidence(query, executedSteps, startTime, metrics);
            }
            executedSteps.add("QUERY_REWRITE");
            currentQuery = this.rewriteService.rewriteQuery(currentQuery);
            metrics.put("rewrittenQuery", currentQuery);
            log.info("Agentic: Rewriting query to: {}", (Object)currentQuery);
        }
        if (this.useSelfRag) {
            executedSteps.add("SELFRAG_GENERATION");
            SelfRagService.SelfRagResult selfRagResult = this.selfRag.generateWithReflection(query, documents);
            response = selfRagResult.cleanResponse();
            confidence = selfRagResult.overallConfidence();
            metrics.put("selfRagClaims", selfRagResult.claims().size());
            metrics.put("uncertainClaims", selfRagResult.uncertainClaims().size());
        } else {
            executedSteps.add("STANDARD_GENERATION");
            response = this.generateStandardResponse(query, documents);
            confidence = 0.7;
        }
        long elapsed = System.currentTimeMillis() - startTime;
        metrics.put("totalMs", elapsed);
        this.reasoningTracer.addStep(ReasoningStep.StepType.ORCHESTRATION, "Agentic RAG Orchestration", String.format("Completed in %d steps, %d iterations, %.0f%% confidence", executedSteps.size(), (int)metrics.getOrDefault("iteration", 1), confidence * 100.0), elapsed, metrics);
        log.info("Agentic: Completed {} steps in {}ms with {:.0f}% confidence", new Object[]{executedSteps.size(), elapsed, confidence * 100.0});
        return new AgenticResult(response, documents, confidence, executedSteps, metrics.getOrDefault("iteration", 1).intValue(), metrics);
    }

    private AgenticResult simpleRetrieval(String query, String department, long startTime) {
        List docs = this.vectorStore.similaritySearch(SearchRequest.query((String)query).withTopK(5).withSimilarityThreshold(0.3).withFilterExpression("dept == '" + department + "'"));
        String response = this.generateStandardResponse(query, docs);
        long elapsed = System.currentTimeMillis() - startTime;
        return new AgenticResult(response, docs, 0.7, List.of("SIMPLE_RETRIEVAL"), 1, Map.of("totalMs", elapsed, "mode", "disabled"));
    }

    private AgenticResult directResponse(String query, List<String> steps, long startTime, Map<String, Object> metrics) {
        String response = "I'm here to help you search our knowledge base. Please ask a specific question about your documents.";
        long elapsed = System.currentTimeMillis() - startTime;
        metrics.put("totalMs", elapsed);
        return new AgenticResult(response, List.of(), 1.0, steps, 1, metrics);
    }

    private AgenticResult insufficientEvidence(String query, List<String> steps, long startTime, Map<String, Object> metrics) {
        String response = "I couldn't find sufficient information in the knowledge base to answer your question. Please try rephrasing your query or contact an administrator if you believe the information should be available.";
        long elapsed = System.currentTimeMillis() - startTime;
        metrics.put("totalMs", elapsed);
        return new AgenticResult(response, List.of(), 0.2, steps, ((Integer)metrics.getOrDefault("iteration", 1)).intValue(), metrics);
    }

    private List<Document> standardRetrieval(String query, String department, AdaptiveRagService.RoutingResult routing) {
        int topK = this.adaptiveRag.getTopK(routing.decision());
        double threshold = this.adaptiveRag.getSimilarityThreshold(routing.decision());
        return this.vectorStore.similaritySearch(SearchRequest.query((String)query).withTopK(topK).withSimilarityThreshold(threshold).withFilterExpression("dept == '" + department + "'"));
    }

    private String generateStandardResponse(String query, List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return "No relevant information found.";
        }
        StringBuilder context = new StringBuilder();
        for (Document doc : documents) {
            context.append(doc.getContent()).append("\n\n");
        }
        return "Based on the retrieved information: " + context.substring(0, Math.min(500, context.length())) + "...";
    }

    public boolean isEnabled() {
        return this.enabled;
    }
}

