/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.ai.chat.client.ChatClient
 *  org.springframework.ai.chat.client.ChatClient$Builder
 *  org.springframework.stereotype.Service
 */
package com.jreinhal.mercenary.professional.rag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service(value="professionalQueryDecompositionService")
public class QueryDecompositionService {
    private static final Logger log = LoggerFactory.getLogger(QueryDecompositionService.class);
    private final ChatClient chatClient;

    public QueryDecompositionService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public DecompositionResult decompose(String query) {
        log.debug("Analyzing query for decomposition: {}", this.truncate(query, 100));
        if (!this.requiresDecomposition(query)) {
            return new DecompositionResult(query, DecompositionStrategy.PARALLEL, List.of(new SubQuery(query, 1, false, "Direct answer", List.of())), "Return the answer directly", false);
        }
        DecompositionStrategy strategy = this.determineStrategy(query);
        List<SubQuery> subQueries = this.performDecomposition(query, strategy);
        String synthesisPrompt = this.generateSynthesisPrompt(query, strategy, subQueries);
        return new DecompositionResult(query, strategy, subQueries, synthesisPrompt, true);
    }

    private boolean requiresDecomposition(String query) {
        String lower = query.toLowerCase();
        boolean hasMultipleParts = lower.contains(" and ") || lower.contains(" also ") || lower.contains(" as well as ") || query.contains(",");
        boolean isComparative = lower.contains("compare") || lower.contains("difference between") || lower.contains("versus") || lower.contains(" vs ");
        boolean isTemporal = lower.contains("over time") || lower.contains("history of") || lower.contains("evolution of") || lower.contains("from") && lower.contains("to");
        boolean isComplex = query.length() > 150 || lower.contains("explain how") || lower.contains("analyze") || lower.contains("evaluate");
        return hasMultipleParts || isComparative || isTemporal || isComplex;
    }

    private DecompositionStrategy determineStrategy(String query) {
        String lower = query.toLowerCase();
        if (lower.contains("compare") || lower.contains("difference") || lower.contains("versus") || lower.contains(" vs ")) {
            return DecompositionStrategy.COMPARATIVE;
        }
        if (lower.contains("over time") || lower.contains("history") || lower.contains("evolution") || lower.contains("timeline")) {
            return DecompositionStrategy.TEMPORAL;
        }
        if (lower.contains("first") || lower.contains("then") || lower.contains("after that") || lower.contains("finally")) {
            return DecompositionStrategy.SEQUENTIAL;
        }
        if (lower.contains("overview") || lower.contains("in detail") || lower.contains("specifically")) {
            return DecompositionStrategy.HIERARCHICAL;
        }
        return DecompositionStrategy.PARALLEL;
    }

    private List<SubQuery> performDecomposition(String query, DecompositionStrategy strategy) {
        String prompt = this.buildDecompositionPrompt(query, strategy);
        try {
            String response = this.chatClient.prompt().user(prompt).call().content();
            return this.parseSubQueries(response, strategy);
        }
        catch (Exception e) {
            log.error("Error decomposing query: {}", e.getMessage());
            return List.of(new SubQuery(query, 1, false, "Fallback - direct query", List.of()));
        }
    }

    private String buildDecompositionPrompt(String query, DecompositionStrategy strategy) {
        String strategyInstructions = switch (strategy.ordinal()) {
            default -> throw new MatchException(null, null);
            case 0 -> "Break this into sequential steps where each question builds on previous answers.\nThe first question should establish foundational knowledge.\n";
            case 1 -> "Break this into independent questions that can be answered separately.\nEach question should cover a distinct aspect of the query.\n";
            case 2 -> "Break this into questions that gather information about each entity being compared.\nInclude a final question about key differences/similarities.\n";
            case 3 -> "Break this into questions covering different time periods.\nStart with the earliest period and progress chronologically.\n";
            case 4 -> "Break this into questions from general overview to specific details.\nStart broad, then narrow down to specifics.\n";
        };
        return "Decompose this complex question into simpler sub-questions.\n\nStrategy: %s\n%s\n\nOriginal Question:\n%s\n\nFor each sub-question, provide:\n1. The sub-question text\n2. Its purpose/what it addresses\n3. Whether it depends on previous answers (yes/no)\n4. Key entities it involves\n\nFormat each sub-question as:\nSUBQUERY: [question text]\nPURPOSE: [what this addresses]\nDEPENDS: [yes/no]\nENTITIES: [comma-separated list]\n---\n".formatted(strategy.name(), strategyInstructions, query);
    }

    private List<SubQuery> parseSubQueries(String response, DecompositionStrategy strategy) {
        ArrayList<SubQuery> subQueries = new ArrayList<SubQuery>();
        String[] blocks = response.split("---");
        int order = 0;
        for (String block : blocks) {
            List<String> entities;
            if ((block = block.trim()).isEmpty()) continue;
            ++order;
            String queryText = this.extractField(block, "SUBQUERY:");
            String purpose = this.extractField(block, "PURPOSE:");
            boolean depends = this.extractField(block, "DEPENDS:").toLowerCase().contains("yes");
            String entitiesStr = this.extractField(block, "ENTITIES:");
            List<String> list = entities = entitiesStr.isEmpty() ? List.of() : Arrays.asList(entitiesStr.split(",\\s*"));
            if (queryText.isEmpty()) continue;
            subQueries.add(new SubQuery(queryText, order, depends, purpose, entities));
        }
        if (subQueries.isEmpty()) {
            subQueries.add(new SubQuery(response.trim(), 1, false, "Parsed response", List.of()));
        }
        return subQueries;
    }

    private String extractField(String block, String fieldName) {
        int idx = block.toUpperCase().indexOf(fieldName.toUpperCase());
        if (idx < 0) {
            return "";
        }
        int start = idx + fieldName.length();
        int end = block.indexOf(10, start);
        if (end < 0) {
            end = block.length();
        }
        return block.substring(start, end).trim();
    }

    private String generateSynthesisPrompt(String originalQuery, DecompositionStrategy strategy, List<SubQuery> subQueries) {
        String instructions = switch (strategy.ordinal()) {
            default -> throw new MatchException(null, null);
            case 0 -> "Combine these answers in order, showing how each builds on the previous.";
            case 1 -> "Synthesize these parallel answers into a comprehensive response.";
            case 2 -> "Create a comparison highlighting similarities and differences.";
            case 3 -> "Weave these into a chronological narrative.";
            case 4 -> "Organize from overview to details, maintaining logical flow.";
        };
        StringBuilder prompt = new StringBuilder();
        prompt.append("Original Question: ").append(originalQuery).append("\n\n");
        prompt.append("Strategy: ").append(strategy.name()).append("\n");
        prompt.append("Instructions: ").append(instructions).append("\n\n");
        prompt.append("Sub-answers to synthesize:\n");
        for (SubQuery sq : subQueries) {
            prompt.append("Q").append(sq.order()).append(": ").append(sq.query()).append("\n");
            prompt.append("A").append(sq.order()).append(": [ANSWER_").append(sq.order()).append("]\n\n");
        }
        prompt.append("\nSynthesize into a single, coherent response.");
        return prompt.toString();
    }

    public String synthesize(DecompositionResult decomposition, Map<Integer, String> subAnswers) {
        if (!decomposition.isComplex()) {
            return subAnswers.getOrDefault(1, "No answer available");
        }
        String prompt = decomposition.synthesisPrompt();
        for (Map.Entry<Integer, String> entry : subAnswers.entrySet()) {
            prompt = prompt.replace("[ANSWER_" + String.valueOf(entry.getKey()) + "]", entry.getValue());
        }
        try {
            return this.chatClient.prompt().user(prompt).call().content();
        }
        catch (Exception e) {
            log.error("Error synthesizing answers: {}", e.getMessage());
            StringBuilder fallback = new StringBuilder();
            for (SubQuery sq : decomposition.subQueries()) {
                fallback.append(sq.query()).append("\n");
                fallback.append(subAnswers.getOrDefault(sq.order(), "No answer")).append("\n\n");
            }
            return fallback.toString();
        }
    }

    private String truncate(String str, int maxLen) {
        if (str == null) {
            return "";
        }
        return str.length() <= maxLen ? str : str.substring(0, maxLen) + "...";
    }

    public record DecompositionResult(String originalQuery, DecompositionStrategy strategy, List<SubQuery> subQueries, String synthesisPrompt, boolean isComplex) {
    }

    public static enum DecompositionStrategy {
        SEQUENTIAL,
        PARALLEL,
        COMPARATIVE,
        TEMPORAL,
        HIERARCHICAL;

    }

    public record SubQuery(String query, int order, boolean dependsOnPrevious, String purpose, List<String> requiredEntities) {
    }
}
