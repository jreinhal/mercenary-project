package com.jreinhal.mercenary.reasoning;

import java.util.Map;

/**
 * Represents a single step in the Glass Box reasoning chain.
 *
 * Each step captures what the RAG pipeline did, how long it took,
 * and relevant metrics for that operation.
 */
public record ReasoningStep(
    /**
     * Type of reasoning step.
     */
    StepType type,

    /**
     * Human-readable label for this step.
     */
    String label,

    /**
     * Detailed description of what happened.
     */
    String detail,

    /**
     * Duration of this step in milliseconds.
     */
    long durationMs,

    /**
     * Step-specific data and metrics.
     */
    Map<String, Object> data
) {
    /**
     * Types of reasoning steps in the RAG pipeline.
     */
    public enum StepType {
        /**
         * Initial query analysis and preprocessing.
         */
        QUERY_ANALYSIS,

        /**
         * Query decomposition into sub-queries.
         */
        QUERY_DECOMPOSITION,

        /**
         * Semantic vector search against the corpus.
         */
        VECTOR_SEARCH,

        /**
         * Keyword-based fallback search.
         */
        KEYWORD_SEARCH,

        /**
         * Document retrieval and ranking.
         */
        RETRIEVAL,

        /**
         * Cross-encoder reranking (HiFi-RAG).
         */
        RERANKING,

        /**
         * RAGPart corpus poisoning detection.
         */
        POISON_DETECTION,

        /**
         * HGMem hypergraph traversal.
         */
        GRAPH_TRAVERSAL,

        /**
         * Gap detection for iterative retrieval.
         */
        GAP_DETECTION,

        /**
         * Context filtering and assembly.
         */
        CONTEXT_ASSEMBLY,

        /**
         * LLM prompt construction.
         */
        PROMPT_CONSTRUCTION,

        /**
         * LLM response generation.
         */
        GENERATION,

        /**
         * LLM response generation (alias for GENERATION).
         */
        LLM_GENERATION,

        /**
         * Document filtering operations.
         */
        FILTERING,

        /**
         * Citation extraction and verification.
         */
        CITATION_VERIFICATION,

        /**
         * Final response formatting.
         */
        RESPONSE_FORMATTING,

        /**
         * Cache lookup or fallback.
         */
        CACHE_OPERATION,

        /**
         * Security check (clearance, permissions).
         */
        SECURITY_CHECK,

        /**
         * Error or exception handling.
         */
        ERROR
    }

    /**
     * Create a simple reasoning step without extra data.
     */
    public static ReasoningStep of(StepType type, String label, String detail, long durationMs) {
        return new ReasoningStep(type, label, detail, durationMs, Map.of());
    }

    /**
     * Create a reasoning step with additional data.
     */
    public static ReasoningStep of(StepType type, String label, String detail, long durationMs, Map<String, Object> data) {
        return new ReasoningStep(type, label, detail, durationMs, data);
    }
}
