package com.jreinhal.mercenary.rag.adaptiverag;

import com.jreinhal.mercenary.reasoning.ReasoningTracer;
import com.jreinhal.mercenary.reasoning.ReasoningStep.StepType;
import org.springframework.ai.chat.client.ChatClient;
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
 * 1. NO_RETRIEVAL - Conversational queries that LLM can answer directly
 * (ZeroHop)
 * 2. CHUNK - Specific factual queries that need focused paragraph-level
 * retrieval
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
            Map<String, Object> signals) {
    }

    private final ReasoningTracer reasoningTracer;

    @Value("${sentinel.adaptiverag.enabled:true}")
    private boolean enabled;

    // When false, AdaptiveRAG uses only heuristics (no extra LLM call for routing).
    // This saves 3-5s per query. Default false for performance.
    @Value("${sentinel.adaptiverag.semantic-router-enabled:false}")
    private boolean semanticRouterEnabled;

    @Value("${sentinel.adaptiverag.chunk-top-k:5}")
    private int chunkTopK;

    @Value("${sentinel.adaptiverag.document-top-k:3}")
    private int documentTopK;

    // === PATTERN-BASED SIGNALS ===

    // Conversational patterns -> NO_RETRIEVAL
    private static final List<Pattern> CONVERSATIONAL_PATTERNS = List.of(
            Pattern.compile("^(hi|hello|hey|greetings|good\\s+(morning|afternoon|evening))\\b",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(thanks|thank\\s+you|thx)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(bye|goodbye|see\\s+you|later)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(yes|no|ok|okay|sure|fine|great|good)\\s*[.!?]?$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(what|who)\\s+(are|is)\\s+you\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^can\\s+you\\s+(help|assist)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(please\\s+)?(clarify|explain\\s+yourself|what\\s+do\\s+you\\s+mean)",
                    Pattern.CASE_INSENSITIVE));

    // Document-level analysis patterns -> DOCUMENT
    private static final List<Pattern> DOCUMENT_PATTERNS = List.of(
            Pattern.compile("\\b(summarize|summary|overview|abstract)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(compare|contrast|difference|similarities)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(analyze|analysis|evaluate|assessment)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(explain|describe|elaborate)\\s+(in\\s+detail|thoroughly|comprehensively)",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(overall|big\\s+picture|holistic|comprehensive)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(relationship|connection|correlation)\\s+between\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(how\\s+does|how\\s+do)\\s+.{5,}\\s+(work|function|operate|relate)",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(impact|effect|consequence|implication)s?\\s+of\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(pros?\\s+and\\s+cons?|advantages?\\s+and\\s+disadvantages?)\\b",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(timeline|history|evolution|progression)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(strategy|approach|methodology|framework)\\b", Pattern.CASE_INSENSITIVE));

    // Specific factual patterns -> CHUNK (restored - useful for heuristic fallback)
    // v2: Added direct "What is X?" pattern without article requirement
    private static final List<Pattern> CHUNK_PATTERNS = List.of(
            Pattern.compile("^what\\s+is\\s+\\w+", Pattern.CASE_INSENSITIVE),  // "What is X?" without article
            Pattern.compile("\\b(what\\s+is|what's)\\s+(the|a)\\s+\\w+\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(when\\s+(did|was|is|will))\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(where\\s+(is|are|was|were))\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(who\\s+(is|was|are|were))\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(how\\s+much|how\\s+many|how\\s+long|how\\s+often)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(define|definition\\s+of)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(list|name|enumerate)\\s+(the|all|some)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(find|locate|identify)\\s+(the|a|any)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b\\d{4}\\b", Pattern.CASE_INSENSITIVE), // Year mentions
            Pattern.compile("\\$[\\d,.]+|\\d+%|\\d+\\s*(million|billion|thousand)", Pattern.CASE_INSENSITIVE));

    // Named entity indicators (proper nouns, abbreviations) -> CHUNK
    private static final Pattern NAMED_ENTITY_PATTERN = Pattern.compile(
            "\\b([A-Z][a-z]+\\s+){1,3}[A-Z][a-z]+\\b|\\b[A-Z]{2,}\\b");

    // HyDE patterns - vague/conceptual queries benefit from hypothetical document generation
    private static final List<Pattern> HYDE_PATTERNS = List.of(
            Pattern.compile("\\b(that\\s+one|the\\s+thing|something\\s+about)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(concept|idea|approach)\\s+(like|similar|related)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(remember|recall|forgot)\\s+(the|a|about)\\b", Pattern.CASE_INSENSITIVE));

    // Multi-hop patterns - relationship/causation queries need graph traversal
    private static final List<Pattern> MULTI_HOP_PATTERNS = List.of(
            Pattern.compile("\\b(how\\s+does?)\\s+.+?\\s+(affect|impact|influence|cause)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\brelationship\\s+between\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(chain|cascade|sequence)\\s+of\\b", Pattern.CASE_INSENSITIVE));

    // === LLM ROUTER CONFIGURATION ===
    private final ChatClient chatClient;
    private static final String ROUTER_SYSTEM_PROMPT = """
            You are an expert query router for a RAG system.
            Classify the USER QUERY into one of these categories:

            1. NO_RETRIEVAL: ONLY for greetings (hi, thanks, bye) or meta-questions about the assistant itself.
               NEVER use this for definitional questions like "What is X?" - those need retrieval!
            2. CHUNK: Specific factual questions, definitions, looking up terms, numbers, dates, or entities.
               Examples: "What is RAG?", "What does X mean?", "Define Y", "Who is Z?"
            3. DOCUMENT: Complex analysis, summarization, comparisons, or broad "how does X work" questions.

            IMPORTANT: When in doubt between NO_RETRIEVAL and CHUNK, choose CHUNK.
            Definitional questions (What is X?) should ALWAYS be CHUNK, never NO_RETRIEVAL.

            Return ONLY the category name. Do not explain.
            """;

    public AdaptiveRagService(ReasoningTracer reasoningTracer, ChatClient.Builder chatClientBuilder) {
        this.reasoningTracer = reasoningTracer;
        this.chatClient = chatClientBuilder.build();
    }

    @PostConstruct
    public void init() {
        log.info("AdaptiveRAG Service initialized (enabled={}, semanticRouter={}, chunkK={}, documentK={})",
                enabled, semanticRouterEnabled, chunkTopK, documentTopK);
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

        // === DETECT SPECIALIZED RETRIEVAL SIGNALS ===
        // These signals inform the orchestrator which strategies to invoke

        // HyDE signal: vague/conceptual queries benefit from hypothetical document generation
        boolean isHydeCandidate = HYDE_PATTERNS.stream()
                .anyMatch(p -> p.matcher(normalizedQuery).find());
        signals.put("isHyde", isHydeCandidate);

        // Multi-hop signal: relationship/causation queries need graph traversal
        boolean isMultiHop = MULTI_HOP_PATTERNS.stream()
                .anyMatch(p -> p.matcher(normalizedQuery).find());
        signals.put("isMultiHop", isMultiHop);

        // Named entity signal: presence of proper nouns suggests focused retrieval
        boolean hasNamedEntity = NAMED_ENTITY_PATTERN.matcher(normalizedQuery).find();
        signals.put("hasNamedEntity", hasNamedEntity);

        // Log detected signals for debugging
        if (isHydeCandidate || isMultiHop) {
            log.debug("AdaptiveRAG signals: hyde={}, multiHop={}, namedEntity={}",
                    isHydeCandidate, isMultiHop, hasNamedEntity);
        }

        // === SIGNAL 1: Fast Path (Regex) - Zero Latency ===
        // We keep the regex patterns as a "Fast Path" optimization to save LLM
        // tokens/latency
        // for obvious cases (e.g., "Hello", "What is the capital of France?").

        boolean isConversational = CONVERSATIONAL_PATTERNS.stream()
                .anyMatch(p -> p.matcher(normalizedQuery).find());

        if (isConversational) {
            long duration = System.currentTimeMillis() - startTime;
            return logAndReturn(RoutingDecision.NO_RETRIEVAL, "FastPath: Conversational pattern", 0.99, signals,
                    duration);
        }

        // Fast path for definitional queries - "What is X?" should always use CHUNK
        boolean isChunkPattern = CHUNK_PATTERNS.stream()
                .anyMatch(p -> p.matcher(normalizedQuery).find());
        if (isChunkPattern) {
            long duration = System.currentTimeMillis() - startTime;
            return logAndReturn(RoutingDecision.CHUNK, "FastPath: Definitional/factual pattern", 0.95, signals,
                    duration);
        }

        if (!semanticRouterEnabled) {
            signals.put("semanticRouter", "disabled");
        }

        // === SIGNAL 2: Semantic Router (LLM) - High Accuracy ===
        // If not obvious, ask the LLM (optional; can be disabled for latency).
        if (semanticRouterEnabled) {
            try {
                long llmStart = System.currentTimeMillis();
                String classification = chatClient.prompt()
                        .system(ROUTER_SYSTEM_PROMPT)
                        .user(normalizedQuery)
                        .call()
                        .content()
                        .trim()
                        .toUpperCase();
                long llmDuration = System.currentTimeMillis() - llmStart;
                signals.put("llmDuration", llmDuration);

                RoutingDecision decision;
                String reason;

                if (classification.contains("NO_RETRIEVAL")) {
                    decision = RoutingDecision.NO_RETRIEVAL;
                    reason = "Semantic Classification: Conversational/Logic";
                } else if (classification.contains("DOCUMENT")) {
                    decision = RoutingDecision.DOCUMENT;
                    reason = "Semantic Classification: Analysis required";
                } else {
                    decision = RoutingDecision.CHUNK; // Default fallback
                    reason = "Semantic Classification: Factual";
                }

                long totalDuration = System.currentTimeMillis() - startTime;
                return logAndReturn(decision, reason, 0.90, signals, totalDuration);

            } catch (Exception e) {
                log.error("Semantic Router failed, falling back to heuristics: {}", e.getMessage());
                signals.put("routerError", e.getMessage());
            }
        }

        // === FALLBACK: Heuristics (Original Logic) ===
        // If LLM fails or is skipped, use the original heuristic logic...
        long documentPatternMatches = DOCUMENT_PATTERNS.stream().filter(p -> p.matcher(normalizedQuery).find()).count();
        boolean isLongQuery = wordCount > 15;

        RoutingDecision decision;
        String reason;
        double confidence;

        if (documentPatternMatches >= 1 || isLongQuery) {
            decision = RoutingDecision.DOCUMENT;
            reason = "Heuristic: Complex pattern or length";
            confidence = 0.70;
        } else {
            decision = RoutingDecision.CHUNK;
            reason = "Heuristic: Default factual";
            confidence = 0.75;
        }

        long duration = System.currentTimeMillis() - startTime;
        return logAndReturn(decision, reason, confidence, signals, duration);
    }

    private RoutingResult logAndReturn(RoutingDecision decision, String reason, double confidence,
            Map<String, Object> signals, long duration) {
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
            case CHUNK -> 0.20; // Higher threshold for precision
            case DOCUMENT -> 0.10; // Lower threshold for recall
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
