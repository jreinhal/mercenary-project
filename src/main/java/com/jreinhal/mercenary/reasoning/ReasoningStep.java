/*
 * Decompiled with CFR 0.152.
 */
package com.jreinhal.mercenary.reasoning;

import java.util.Map;

public record ReasoningStep(StepType type, String label, String detail, long durationMs, Map<String, Object> data) {
    public static ReasoningStep of(StepType type, String label, String detail, long durationMs) {
        return new ReasoningStep(type, label, detail, durationMs, Map.of());
    }

    public static ReasoningStep of(StepType type, String label, String detail, long durationMs, Map<String, Object> data) {
        return new ReasoningStep(type, label, detail, durationMs, data);
    }

    public static enum StepType {
        QUERY_ROUTING,
        QUERY_ANALYSIS,
        QUERY_DECOMPOSITION,
        VECTOR_SEARCH,
        KEYWORD_SEARCH,
        RETRIEVAL,
        RERANKING,
        POISON_DETECTION,
        GRAPH_TRAVERSAL,
        GAP_DETECTION,
        CONTEXT_ASSEMBLY,
        PROMPT_CONSTRUCTION,
        GENERATION,
        LLM_GENERATION,
        FILTERING,
        CITATION_VERIFICATION,
        RESPONSE_FORMATTING,
        CACHE_OPERATION,
        SECURITY_CHECK,
        UNCERTAINTY_ANALYSIS,
        CROSS_MODAL_RETRIEVAL,
        MINDSCAPE_RETRIEVAL,
        HYBRID_RETRIEVAL,
        EXPERIENCE_VALIDATION,
        VALIDATION,
        MCTS_REASONING,
        ORCHESTRATION,
        VISUAL_ANALYSIS,
        MINDSCAPE_BUILDING,
        ERROR;

    }
}
