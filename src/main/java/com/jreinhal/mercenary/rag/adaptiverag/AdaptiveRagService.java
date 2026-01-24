package com.jreinhal.mercenary.rag.adaptiverag;

import com.jreinhal.mercenary.reasoning.ReasoningStep;
import com.jreinhal.mercenary.reasoning.ReasoningTracer;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AdaptiveRagService {
    private static final Logger log = LoggerFactory.getLogger(AdaptiveRagService.class);
    private final ReasoningTracer reasoningTracer;
    @Value(value="${sentinel.adaptiverag.enabled:true}")
    private boolean enabled;
    @Value(value="${sentinel.adaptiverag.semantic-router-enabled:false}")
    private boolean semanticRouterEnabled;
    @Value(value="${sentinel.adaptiverag.chunk-top-k:5}")
    private int chunkTopK;
    @Value(value="${sentinel.adaptiverag.document-top-k:3}")
    private int documentTopK;
    private static final List<Pattern> CONVERSATIONAL_PATTERNS = List.of(Pattern.compile("^(hi|hello|hey|greetings|good\\s+(morning|afternoon|evening))\\b", 2), Pattern.compile("^(thanks|thank\\s+you|thx)\\b", 2), Pattern.compile("^(bye|goodbye|see\\s+you|later)\\b", 2), Pattern.compile("^(yes|no|ok|okay|sure|fine|great|good)\\s*[.!?]?$", 2), Pattern.compile("^(what|who)\\s+(are|is)\\s+you\\b", 2), Pattern.compile("^can\\s+you\\s+(help|assist)\\b", 2), Pattern.compile("^(please\\s+)?(clarify|explain\\s+yourself|what\\s+do\\s+you\\s+mean)", 2));
    private static final List<Pattern> DOCUMENT_PATTERNS = List.of(Pattern.compile("\\b(summarize|summary|overview|abstract)\\b", 2), Pattern.compile("\\b(compare|contrast|difference|similarities)\\b", 2), Pattern.compile("\\b(analyze|analysis|evaluate|assessment)\\b", 2), Pattern.compile("\\b(explain|describe|elaborate)\\s+(in\\s+detail|thoroughly|comprehensively)", 2), Pattern.compile("\\b(overall|big\\s+picture|holistic|comprehensive)\\b", 2), Pattern.compile("\\b(relationship|connection|correlation)\\s+between\\b", 2), Pattern.compile("\\b(how\\s+does|how\\s+do)\\s+.{5,}\\s+(work|function|operate|relate)", 2), Pattern.compile("\\b(impact|effect|consequence|implication)s?\\s+of\\b", 2), Pattern.compile("\\b(pros?\\s+and\\s+cons?|advantages?\\s+and\\s+disadvantages?)\\b", 2), Pattern.compile("\\b(timeline|history|evolution|progression)\\b", 2), Pattern.compile("\\b(strategy|approach|methodology|framework)\\b", 2));
    private static final List<Pattern> CHUNK_PATTERNS = List.of(Pattern.compile("^what\\s+is\\s+\\w+", 2), Pattern.compile("\\b(what\\s+is|what's)\\s+(the|a)\\s+\\w+\\b", 2), Pattern.compile("\\b(when\\s+(did|was|is|will))\\b", 2), Pattern.compile("\\b(where\\s+(is|are|was|were))\\b", 2), Pattern.compile("\\b(who\\s+(is|was|are|were))\\b", 2), Pattern.compile("\\b(how\\s+much|how\\s+many|how\\s+long|how\\s+often)\\b", 2), Pattern.compile("\\b(define|definition\\s+of)\\b", 2), Pattern.compile("\\b(list|name|enumerate)\\s+(the|all|some)\\b", 2), Pattern.compile("\\b(find|locate|identify)\\s+(the|a|any)\\b", 2), Pattern.compile("\\b\\d{4}\\b", 2), Pattern.compile("\\$[\\d,.]+|\\d+%|\\d+\\s*(million|billion|thousand)", 2));
    private static final Pattern NAMED_ENTITY_PATTERN = Pattern.compile("\\b([A-Z][a-z]+\\s+){1,3}[A-Z][a-z]+\\b|\\b[A-Z]{2,}\\b");
    private static final List<Pattern> HYDE_PATTERNS = List.of(Pattern.compile("\\b(that\\s+one|the\\s+thing|something\\s+about)\\b", 2), Pattern.compile("\\b(concept|idea|approach)\\s+(like|similar|related)\\b", 2), Pattern.compile("\\b(remember|recall|forgot)\\s+(the|a|about)\\b", 2));
    private static final List<Pattern> MULTI_HOP_PATTERNS = List.of(Pattern.compile("\\b(how\\s+does?)\\s+.+?\\s+(affect|impact|influence|cause)\\b", 2), Pattern.compile("\\brelationship\\s+between\\b", 2), Pattern.compile("\\b(chain|cascade|sequence)\\s+of\\b", 2), Pattern.compile("\\b(who|what|where|when)\\b.+\\band\\s+(what|who|how|its|their|the)\\b", 2), Pattern.compile("\\bcompare\\b.+\\b(with|to|and)\\b", 2));
    private final ChatClient chatClient;
    private static final String ROUTER_SYSTEM_PROMPT = "You are an expert query router for a RAG system.\nClassify the USER QUERY into one of these categories:\n\n1. NO_RETRIEVAL: ONLY for greetings (hi, thanks, bye) or meta-questions about the assistant itself.\n   NEVER use this for definitional questions like \"What is X?\" - those need retrieval!\n2. CHUNK: Specific factual questions, definitions, looking up terms, numbers, dates, or entities.\n   Examples: \"What is RAG?\", \"What does X mean?\", \"Define Y\", \"Who is Z?\"\n3. DOCUMENT: Complex analysis, summarization, comparisons, or broad \"how does X work\" questions.\n\nIMPORTANT: When in doubt between NO_RETRIEVAL and CHUNK, choose CHUNK.\nDefinitional questions (What is X?) should ALWAYS be CHUNK, never NO_RETRIEVAL.\n\nReturn ONLY the category name. Do not explain.\n";

    public AdaptiveRagService(ReasoningTracer reasoningTracer, ChatClient.Builder chatClientBuilder) {
        this.reasoningTracer = reasoningTracer;
        this.chatClient = chatClientBuilder.build();
    }

    @PostConstruct
    public void init() {
        log.info("AdaptiveRAG Service initialized (enabled={}, semanticRouter={}, chunkK={}, documentK={})", new Object[]{this.enabled, this.semanticRouterEnabled, this.chunkTopK, this.documentTopK});
    }

    public RoutingResult route(String query) {
        double confidence;
        String reason;
        RoutingDecision decision;
        boolean isLongQuery;
        boolean isConversational;
        long startTime = System.currentTimeMillis();
        if (!this.enabled) {
            log.debug("AdaptiveRAG disabled, defaulting to CHUNK retrieval");
            return new RoutingResult(RoutingDecision.CHUNK, "AdaptiveRAG disabled", 1.0, Map.of());
        }
        if (query == null || query.isBlank()) {
            return new RoutingResult(RoutingDecision.NO_RETRIEVAL, "Empty query", 1.0, Map.of());
        }
        String normalizedQuery = query.trim();
        int wordCount = normalizedQuery.split("\\s+").length;
        LinkedHashMap<String, Object> signals = new LinkedHashMap<String, Object>();
        signals.put("wordCount", wordCount);
        signals.put("hasQuestionMark", normalizedQuery.contains("?"));
        boolean isHydeCandidate = HYDE_PATTERNS.stream().anyMatch(p -> p.matcher(normalizedQuery).find());
        signals.put("isHyde", isHydeCandidate);
        boolean isMultiHop = MULTI_HOP_PATTERNS.stream().anyMatch(p -> p.matcher(normalizedQuery).find());
        signals.put("isMultiHop", isMultiHop);
        boolean hasNamedEntity = NAMED_ENTITY_PATTERN.matcher(normalizedQuery).find();
        signals.put("hasNamedEntity", hasNamedEntity);
        if (isHydeCandidate || isMultiHop) {
            log.debug("AdaptiveRAG signals: hyde={}, multiHop={}, namedEntity={}", new Object[]{isHydeCandidate, isMultiHop, hasNamedEntity});
        }
        if (isConversational = CONVERSATIONAL_PATTERNS.stream().anyMatch(p -> p.matcher(normalizedQuery).find())) {
            long duration = System.currentTimeMillis() - startTime;
            return this.logAndReturn(RoutingDecision.NO_RETRIEVAL, "FastPath: Conversational pattern", 0.99, signals, duration);
        }
        boolean isChunkPattern = CHUNK_PATTERNS.stream().anyMatch(p -> p.matcher(normalizedQuery).find());
        if (isChunkPattern) {
            long duration = System.currentTimeMillis() - startTime;
            return this.logAndReturn(RoutingDecision.CHUNK, "FastPath: Definitional/factual pattern", 0.95, signals, duration);
        }
        if (!this.semanticRouterEnabled) {
            signals.put("semanticRouter", "disabled");
        }
        if (this.semanticRouterEnabled) {
            try {
                String reason2;
                RoutingDecision decision2;
                long llmStart = System.currentTimeMillis();
                String classification = this.chatClient.prompt().system(ROUTER_SYSTEM_PROMPT).user(normalizedQuery).call().content().trim().toUpperCase();
                long llmDuration = System.currentTimeMillis() - llmStart;
                signals.put("llmDuration", llmDuration);
                if (classification.contains("NO_RETRIEVAL")) {
                    decision2 = RoutingDecision.NO_RETRIEVAL;
                    reason2 = "Semantic Classification: Conversational/Logic";
                } else if (classification.contains("DOCUMENT")) {
                    decision2 = RoutingDecision.DOCUMENT;
                    reason2 = "Semantic Classification: Analysis required";
                } else {
                    decision2 = RoutingDecision.CHUNK;
                    reason2 = "Semantic Classification: Factual";
                }
                long totalDuration = System.currentTimeMillis() - startTime;
                return this.logAndReturn(decision2, reason2, 0.9, signals, totalDuration);
            }
            catch (Exception e) {
                log.error("Semantic Router failed, falling back to heuristics: {}", e.getMessage());
                signals.put("routerError", e.getMessage());
            }
        }
        long documentPatternMatches = DOCUMENT_PATTERNS.stream().filter(p -> p.matcher(normalizedQuery).find()).count();
        boolean bl = isLongQuery = wordCount > 15;
        if (documentPatternMatches >= 1L || isLongQuery) {
            decision = RoutingDecision.DOCUMENT;
            reason = "Heuristic: Complex pattern or length";
            confidence = 0.7;
        } else {
            decision = RoutingDecision.CHUNK;
            reason = "Heuristic: Default factual";
            confidence = 0.75;
        }
        long duration = System.currentTimeMillis() - startTime;
        return this.logAndReturn(decision, reason, confidence, signals, duration);
    }

    private RoutingResult logAndReturn(RoutingDecision decision, String reason, double confidence, Map<String, Object> signals, long duration) {
        this.logRoutingStep(decision, reason, duration, signals);
        return new RoutingResult(decision, reason, confidence, signals);
    }

    public int getTopK(RoutingDecision decision) {
        return switch (decision.ordinal()) {
            default -> throw new IllegalArgumentException("Unknown routing decision: " + decision);
            case 0 -> 0;
            case 1 -> this.chunkTopK;
            case 2 -> this.documentTopK;
        };
    }

    public double getSimilarityThreshold(RoutingDecision decision) {
        return switch (decision.ordinal()) {
            default -> throw new IllegalArgumentException("Unknown routing decision: " + decision);
            case 0 -> 0.0;
            case 1 -> 0.2;
            case 2 -> 0.1;
        };
    }

    public boolean shouldSkipRetrieval(RoutingDecision decision) {
        return decision == RoutingDecision.NO_RETRIEVAL;
    }

    private void logRoutingStep(RoutingDecision decision, String reason, long durationMs, Map<String, Object> signals) {
        log.info("AdaptiveRAG: Routed to {} ({}ms) - {}", new Object[]{decision, durationMs, reason});
        LinkedHashMap<String, Object> stepData = new LinkedHashMap<String, Object>(signals);
        stepData.put("decision", decision.name());
        stepData.put("reason", reason);
        this.reasoningTracer.addStep(ReasoningStep.StepType.QUERY_ROUTING, "Query Routing", String.format("%s: %s", decision.name(), reason), durationMs, stepData);
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public record RoutingResult(RoutingDecision decision, String reason, double confidence, Map<String, Object> signals) {
    }

    public static enum RoutingDecision {
        NO_RETRIEVAL,
        CHUNK,
        DOCUMENT;

    }
}
