package com.jreinhal.mercenary.rag.adaptiverag;

import com.jreinhal.mercenary.reasoning.ReasoningTracer;
import com.jreinhal.mercenary.reasoning.ReasoningStep.StepType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.regex.Pattern;

/**
 * AdaptiveRAG: Intelligent Query Routing Service
 * Based on UniversalRAG concepts (arXiv:2504.20734)
 *
 * Implements three routing decisions:
 * 1. NO_RETRIEVAL - Conversational queries that LLM can answer directly (ZeroHop)
 * 2. CHUNK - Specific factual queries that need focused paragraph-level retrieval
 * 3. DOCUMENT - Analytical/synthesis queries that need full document context
 *
 * Uses rule-based heuristics for near-zero latency overhead (<5ms).
 * This provides 80% of the benefit of ML-based routers without the cost.
 */
@Service
public class AdaptiveRagService {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveRagService.class);

    /**
     * Routing decision enum - determines retrieval strategy.
     */
    public enum RoutingDecision {
        /**
         * Skip RAG entirely - query can be answered by LLM directly.
         * Examples: greetings, clarification requests, general knowledge
         */
        NO_RETRIEVAL,

        /**
         * Use chunk-level retrieval (paragraph granularity).
         * Examples: specific facts, named entity lookups, date queries
         */
        CHUNK,

        /**
         * Use document-level retrieval (full document granularity).
         * Examples: analysis, comparison, summarization, multi-hop reasoning
         */
        DOCUMENT
    }

    /**
     * Result of query routing analysis.
     */
    public record RoutingResult(
            RoutingDecision decision,
            String reason,
            double confidence,
            Map<String, Object> signals) {}

    private final ReasoningTracer reasoningTracer;

    @Value("${sentinel.adaptiverag.enabled:true}")
    private boolean enabled;

    @Value("${sentinel.adaptiverag.chunk-top-k:5}")
    private int chunkTopK;

    @Value("${sentinel.adaptiverag.document-top-k:3}")
    private int documentTopK;

    // === PATTERN-BASED SIGNALS ===

    // Conversational patterns -> NO_RETRIEVAL
    private static final List<Pattern> CONVERSATIONAL_PATTERNS = List.of(
            Pattern.compile("^(hi|hello|hey|greetings|good\\s+(morning|afternoon|evening))\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(thanks|thank\\s+you|thx)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(bye|goodbye|see\\s+you|later)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(yes|no|ok|okay|sure|fine|great|good)\\s*[.!?]?$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(what|who)\\s+(are|is)\\s+you\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^can\\s+you\\s+(help|assist)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(please\\s+)?(clarify|explain\\s+yourself|what\\s+do\\s+you\\s+mean)", Pattern.CASE_INSENSITIVE)
    );

    // Document-level analysis patterns -> DOCUMENT
    private static final List<Pattern> DOCUMENT_PATTERNS = List.of(
            Pattern.compile("\\b(summarize|summary|overview|abstract)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(compare|contrast|difference|similarities)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(analyze|analysis|evaluate|assessment)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(explain|describe|elaborate)\\s+(in\\s+detail|thoroughly|comprehensively)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(overall|big\\s+picture|holistic|comprehensive)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(relationship|connection|correlation)\\s+between\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(how\\s+does|how\\s+do)\\s+.{5,}\\s+(work|function|operate|relate)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(impact|effect|consequence|implication)s?\\s+of\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(pros?\\s+and\\s+cons?|advantages?\\s+and\\s+disadvantages?)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(timeline|history|evolution|progression)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(strategy|approach|methodology|framework)\\b", Pattern.CASE_INSENSITIVE)
    );

    // Specific factual patterns -> CHUNK
    private static final List<Pattern> CHUNK_PATTERNS = List.of(
            Pattern.compile("\\b(what\\s+is|what's)\\s+(the|a)\\s+\\w+\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(when\\s+(did|was|is|will))\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(where\\s+(is|are|was|were))\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(who\\s+(is|was|are|were))\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(how\\s+much|how\\s+many|how\\s+long|how\\s+often)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(define|definition\\s+of)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(list|name|enumerate)\\s+(the|all|some)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(find|locate|identify)\\s+(the|a|any)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b\\d{4}\\b", Pattern.CASE_INSENSITIVE),  // Year mentions
            Pattern.compile("\\$[\\d,.]+|\\d+%|\\d+\\s*(million|billion|thousand)", Pattern.CASE_INSENSITIVE)  // Numbers/money
    );

    // Named entity indicators (proper nouns, abbreviations) -> CHUNK
    private static final Pattern NAMED_ENTITY_PATTERN = Pattern.compile(
            "\\b([A-Z][a-z]+\\s+){1,3}[A-Z][a-z]+\\b|\\b[A-Z]{2,}\\b"
    );

    public AdaptiveRagService(ReasoningTracer reasoningTracer) {
        this.reasoningTracer = reasoningTracer;
    }

    @PostConstruct
    public void init() {
        log.info("AdaptiveRAG Service initialized (enabled={}, chunkK={}, documentK={})",
                enabled, chunkTopK, documentTopK);
    }

    /**
     * Route a query to the appropriate retrieval strategy.
     *
     * @param query The user's query
     * @return RoutingResult with decision, reason, and confidence
     */
    public RoutingResult route(String query) {
        long startTime = System.currentTimeMillis();

        if (!enabled) {
            log.debug("AdaptiveRAG disabled, defaulting to CHUNK retrieval");
            return new RoutingResult(RoutingDecision.CHUNK, "AdaptiveRAG disabled", 1.0, Map.of());
        }

        if (query == null || query.isBlank()) {
            return new RoutingResult(RoutingDecision.NO_RETRIEVAL, "Empty query", 1.0, Map.of());
        }

        // Normalize query
        String normalizedQuery = query.trim();
        int wordCount = normalizedQuery.split("\\s+").length;

        // Collect signals
        Map<String, Object> signals = new LinkedHashMap<>();
        signals.put("wordCount", wordCount);
        signals.put("hasQuestionMark", normalizedQuery.contains("?"));

        // === SIGNAL 1: Conversational Detection ===
        boolean isConversational = CONVERSATIONAL_PATTERNS.stream()
                .anyMatch(p -> p.matcher(normalizedQuery).find());
        signals.put("isConversational", isConversational);

        if (isConversational) {
            long duration = System.currentTimeMillis() - startTime;
            logRoutingStep(RoutingDecision.NO_RETRIEVAL, "Conversational query detected", duration, signals);
            return new RoutingResult(
                    RoutingDecision.NO_RETRIEVAL,
                    "Conversational query - LLM can respond directly",
                    0.95,
                    signals);
        }

        // === SIGNAL 2: Very Short Query (< 5 words, no entities) ===
        boolean hasNamedEntities = NAMED_ENTITY_PATTERN.matcher(normalizedQuery).find();
        signals.put("hasNamedEntities", hasNamedEntities);

        // Check if query contains document-level keywords before short-circuit
        boolean hasDocumentKeywords = DOCUMENT_PATTERNS.stream()
                .anyMatch(p -> p.matcher(normalizedQuery).find());

        if (wordCount < 5 && !hasNamedEntities && !normalizedQuery.contains("?") && !hasDocumentKeywords) {
            long duration = System.currentTimeMillis() - startTime;
            logRoutingStep(RoutingDecision.NO_RETRIEVAL, "Very short non-query", duration, signals);
            return new RoutingResult(
                    RoutingDecision.NO_RETRIEVAL,
                    "Very short input without question indicators",
                    0.80,
                    signals);
        }

        // === SIGNAL 3: Document-Level Analysis Patterns ===
        long documentPatternMatches = DOCUMENT_PATTERNS.stream()
                .filter(p -> p.matcher(normalizedQuery).find())
                .count();
        signals.put("documentPatternMatches", documentPatternMatches);

        // === SIGNAL 4: Chunk-Level Factual Patterns ===
        long chunkPatternMatches = CHUNK_PATTERNS.stream()
                .filter(p -> p.matcher(normalizedQuery).find())
                .count();
        signals.put("chunkPatternMatches", chunkPatternMatches);

        // === SIGNAL 5: Query Length Heuristic ===
        // Longer queries typically need more context
        boolean isLongQuery = wordCount > 15;
        signals.put("isLongQuery", isLongQuery);

        // === DECISION LOGIC ===
        RoutingDecision decision;
        String reason;
        double confidence;

        if (documentPatternMatches >= 2 || (documentPatternMatches >= 1 && isLongQuery)) {
            // Strong document-level signals
            decision = RoutingDecision.DOCUMENT;
            reason = "Analysis/synthesis query requiring full document context";
            confidence = 0.85 + (documentPatternMatches * 0.05);
        } else if (documentPatternMatches >= 1 && chunkPatternMatches == 0) {
            // Weak document signal, no chunk signals
            decision = RoutingDecision.DOCUMENT;
            reason = "Document-level query pattern detected";
            confidence = 0.75;
        } else if (chunkPatternMatches >= 1 || hasNamedEntities) {
            // Factual/specific query
            decision = RoutingDecision.CHUNK;
            reason = "Specific factual query - focused retrieval optimal";
            confidence = 0.85 + (chunkPatternMatches * 0.03);
        } else if (isLongQuery) {
            // Long query without clear signals -> document level is safer
            decision = RoutingDecision.DOCUMENT;
            reason = "Complex query length suggests broader context needed";
            confidence = 0.70;
        } else {
            // Default to chunk-level (most common case)
            decision = RoutingDecision.CHUNK;
            reason = "Standard query - using focused retrieval";
            confidence = 0.75;
        }

        // Cap confidence at 0.99
        confidence = Math.min(confidence, 0.99);
        signals.put("confidence", confidence);

        long duration = System.currentTimeMillis() - startTime;
        logRoutingStep(decision, reason, duration, signals);

        return new RoutingResult(decision, reason, confidence, signals);
    }

    /**
     * Get the recommended top-K value based on routing decision.
     */
    public int getTopK(RoutingDecision decision) {
        return switch (decision) {
            case NO_RETRIEVAL -> 0;
            case CHUNK -> chunkTopK;
            case DOCUMENT -> documentTopK;
        };
    }

    /**
     * Get the recommended similarity threshold based on routing decision.
     */
    public double getSimilarityThreshold(RoutingDecision decision) {
        return switch (decision) {
            case NO_RETRIEVAL -> 0.0;
            case CHUNK -> 0.20;      // Higher threshold for precision
            case DOCUMENT -> 0.10;   // Lower threshold for recall
        };
    }

    /**
     * Check if retrieval should be skipped.
     */
    public boolean shouldSkipRetrieval(RoutingDecision decision) {
        return decision == RoutingDecision.NO_RETRIEVAL;
    }

    /**
     * Log routing step to Glass Box reasoning trace.
     */
    private void logRoutingStep(RoutingDecision decision, String reason, long durationMs, Map<String, Object> signals) {
        log.info("AdaptiveRAG: Routed to {} ({}ms) - {}", decision, durationMs, reason);

        Map<String, Object> stepData = new LinkedHashMap<>(signals);
        stepData.put("decision", decision.name());
        stepData.put("reason", reason);

        reasoningTracer.addStep(
                StepType.QUERY_ROUTING,
                "Query Routing",
                String.format("%s: %s", decision.name(), reason),
                durationMs,
                stepData);
    }

    /**
     * Check if AdaptiveRAG is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }
}
