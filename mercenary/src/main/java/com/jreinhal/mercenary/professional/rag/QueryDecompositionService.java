package com.jreinhal.mercenary.professional.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Query decomposition service for complex multi-part questions.
 *
 * PROFESSIONAL EDITION - Available in professional, medical, and government builds.
 *
 * Breaks down complex queries into simpler sub-queries that can be:
 * 1. Answered independently
 * 2. Combined into a comprehensive response
 * 3. Processed in parallel for efficiency
 *
 * Supports various decomposition strategies:
 * - Sequential: Sub-queries that build on each other
 * - Parallel: Independent sub-queries
 * - Comparative: Questions comparing multiple entities
 * - Temporal: Questions spanning time periods
 */
@Service
public class QueryDecompositionService {

    private static final Logger log = LoggerFactory.getLogger(QueryDecompositionService.class);

    private final ChatClient chatClient;

    public QueryDecompositionService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * Decomposition strategy.
     */
    public enum DecompositionStrategy {
        SEQUENTIAL,   // Sub-queries depend on previous answers
        PARALLEL,     // Sub-queries are independent
        COMPARATIVE,  // Comparing multiple entities
        TEMPORAL,     // Spanning time periods
        HIERARCHICAL  // General to specific
    }

    /**
     * A decomposed sub-query.
     */
    public record SubQuery(
        String query,
        int order,
        boolean dependsOnPrevious,
        String purpose,
        List<String> requiredEntities
    ) {}

    /**
     * Decomposition result.
     */
    public record DecompositionResult(
        String originalQuery,
        DecompositionStrategy strategy,
        List<SubQuery> subQueries,
        String synthesisPrompt,
        boolean isComplex
    ) {}

    /**
     * Analyze and decompose a complex query.
     */
    public DecompositionResult decompose(String query) {
        log.debug("Analyzing query for decomposition: {}", truncate(query, 100));

        // First, determine if decomposition is needed
        if (!requiresDecomposition(query)) {
            return new DecompositionResult(
                query,
                DecompositionStrategy.PARALLEL,
                List.of(new SubQuery(query, 1, false, "Direct answer", List.of())),
                "Return the answer directly",
                false
            );
        }

        // Determine the best decomposition strategy
        DecompositionStrategy strategy = determineStrategy(query);

        // Decompose based on strategy
        List<SubQuery> subQueries = performDecomposition(query, strategy);

        // Generate synthesis prompt
        String synthesisPrompt = generateSynthesisPrompt(query, strategy, subQueries);

        return new DecompositionResult(
            query,
            strategy,
            subQueries,
            synthesisPrompt,
            true
        );
    }

    /**
     * Check if a query requires decomposition.
     */
    private boolean requiresDecomposition(String query) {
        String lower = query.toLowerCase();

        // Multi-part indicators
        boolean hasMultipleParts = lower.contains(" and ") ||
                                   lower.contains(" also ") ||
                                   lower.contains(" as well as ") ||
                                   query.contains(",");

        // Comparison indicators
        boolean isComparative = lower.contains("compare") ||
                               lower.contains("difference between") ||
                               lower.contains("versus") ||
                               lower.contains(" vs ");

        // Temporal indicators
        boolean isTemporal = lower.contains("over time") ||
                            lower.contains("history of") ||
                            lower.contains("evolution of") ||
                            lower.contains("from") && lower.contains("to");

        // Complexity indicators
        boolean isComplex = query.length() > 150 ||
                           lower.contains("explain how") ||
                           lower.contains("analyze") ||
                           lower.contains("evaluate");

        return hasMultipleParts || isComparative || isTemporal || isComplex;
    }

    /**
     * Determine the best decomposition strategy.
     */
    private DecompositionStrategy determineStrategy(String query) {
        String lower = query.toLowerCase();

        if (lower.contains("compare") || lower.contains("difference") ||
            lower.contains("versus") || lower.contains(" vs ")) {
            return DecompositionStrategy.COMPARATIVE;
        }

        if (lower.contains("over time") || lower.contains("history") ||
            lower.contains("evolution") || lower.contains("timeline")) {
            return DecompositionStrategy.TEMPORAL;
        }

        if (lower.contains("first") || lower.contains("then") ||
            lower.contains("after that") || lower.contains("finally")) {
            return DecompositionStrategy.SEQUENTIAL;
        }

        if (lower.contains("overview") || lower.contains("in detail") ||
            lower.contains("specifically")) {
            return DecompositionStrategy.HIERARCHICAL;
        }

        return DecompositionStrategy.PARALLEL;
    }

    /**
     * Perform decomposition using LLM.
     */
    private List<SubQuery> performDecomposition(String query, DecompositionStrategy strategy) {
        String prompt = buildDecompositionPrompt(query, strategy);

        try {
            String response = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

            return parseSubQueries(response, strategy);
        } catch (Exception e) {
            log.error("Error decomposing query: {}", e.getMessage());
            // Fallback: return original query as single sub-query
            return List.of(new SubQuery(query, 1, false, "Fallback - direct query", List.of()));
        }
    }

    /**
     * Build decomposition prompt based on strategy.
     */
    private String buildDecompositionPrompt(String query, DecompositionStrategy strategy) {
        String strategyInstructions = switch (strategy) {
            case SEQUENTIAL -> """
                Break this into sequential steps where each question builds on previous answers.
                The first question should establish foundational knowledge.
                """;
            case PARALLEL -> """
                Break this into independent questions that can be answered separately.
                Each question should cover a distinct aspect of the query.
                """;
            case COMPARATIVE -> """
                Break this into questions that gather information about each entity being compared.
                Include a final question about key differences/similarities.
                """;
            case TEMPORAL -> """
                Break this into questions covering different time periods.
                Start with the earliest period and progress chronologically.
                """;
            case HIERARCHICAL -> """
                Break this into questions from general overview to specific details.
                Start broad, then narrow down to specifics.
                """;
        };

        return """
            Decompose this complex question into simpler sub-questions.

            Strategy: %s
            %s

            Original Question:
            %s

            For each sub-question, provide:
            1. The sub-question text
            2. Its purpose/what it addresses
            3. Whether it depends on previous answers (yes/no)
            4. Key entities it involves

            Format each sub-question as:
            SUBQUERY: [question text]
            PURPOSE: [what this addresses]
            DEPENDS: [yes/no]
            ENTITIES: [comma-separated list]
            ---
            """.formatted(strategy.name(), strategyInstructions, query);
    }

    /**
     * Parse sub-queries from LLM response.
     */
    private List<SubQuery> parseSubQueries(String response, DecompositionStrategy strategy) {
        List<SubQuery> subQueries = new ArrayList<>();
        String[] blocks = response.split("---");

        int order = 0;
        for (String block : blocks) {
            block = block.trim();
            if (block.isEmpty()) continue;

            order++;
            String queryText = extractField(block, "SUBQUERY:");
            String purpose = extractField(block, "PURPOSE:");
            boolean depends = extractField(block, "DEPENDS:").toLowerCase().contains("yes");
            String entitiesStr = extractField(block, "ENTITIES:");
            List<String> entities = entitiesStr.isEmpty() ?
                List.of() : Arrays.asList(entitiesStr.split(",\\s*"));

            if (!queryText.isEmpty()) {
                subQueries.add(new SubQuery(queryText, order, depends, purpose, entities));
            }
        }

        // If parsing failed, create basic decomposition
        if (subQueries.isEmpty()) {
            subQueries.add(new SubQuery(response.trim(), 1, false, "Parsed response", List.of()));
        }

        return subQueries;
    }

    /**
     * Extract a field value from a block of text.
     */
    private String extractField(String block, String fieldName) {
        int idx = block.toUpperCase().indexOf(fieldName.toUpperCase());
        if (idx < 0) return "";

        int start = idx + fieldName.length();
        int end = block.indexOf('\n', start);
        if (end < 0) end = block.length();

        return block.substring(start, end).trim();
    }

    /**
     * Generate a synthesis prompt for combining sub-answers.
     */
    private String generateSynthesisPrompt(String originalQuery, DecompositionStrategy strategy,
                                          List<SubQuery> subQueries) {
        String instructions = switch (strategy) {
            case SEQUENTIAL -> "Combine these answers in order, showing how each builds on the previous.";
            case PARALLEL -> "Synthesize these parallel answers into a comprehensive response.";
            case COMPARATIVE -> "Create a comparison highlighting similarities and differences.";
            case TEMPORAL -> "Weave these into a chronological narrative.";
            case HIERARCHICAL -> "Organize from overview to details, maintaining logical flow.";
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

    /**
     * Synthesize sub-answers into a final response.
     */
    public String synthesize(DecompositionResult decomposition, Map<Integer, String> subAnswers) {
        if (!decomposition.isComplex()) {
            // Simple query - return the single answer
            return subAnswers.getOrDefault(1, "No answer available");
        }

        // Build synthesis prompt with actual answers
        String prompt = decomposition.synthesisPrompt();
        for (Map.Entry<Integer, String> entry : subAnswers.entrySet()) {
            prompt = prompt.replace("[ANSWER_" + entry.getKey() + "]", entry.getValue());
        }

        try {
            return chatClient.prompt()
                .user(prompt)
                .call()
                .content();
        } catch (Exception e) {
            log.error("Error synthesizing answers: {}", e.getMessage());
            // Fallback: concatenate answers
            StringBuilder fallback = new StringBuilder();
            for (SubQuery sq : decomposition.subQueries()) {
                fallback.append(sq.query()).append("\n");
                fallback.append(subAnswers.getOrDefault(sq.order(), "No answer")).append("\n\n");
            }
            return fallback.toString();
        }
    }

    private String truncate(String str, int maxLen) {
        if (str == null) return "";
        return str.length() <= maxLen ? str : str.substring(0, maxLen) + "...";
    }
}
