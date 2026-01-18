package com.jreinhal.mercenary.rag.hybridrag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Query Expander for Hybrid RAG multi-query retrieval.
 *
 * Generates query variants to improve recall through:
 * 1. Synonym expansion
 * 2. Paraphrase generation (LLM-based)
 * 3. Question reformulation
 * 4. Keyword extraction
 *
 * Based on research showing 15-25% recall improvement with multi-query retrieval.
 */
@Component
public class QueryExpander {

    private static final Logger log = LoggerFactory.getLogger(QueryExpander.class);

    private final ChatClient chatClient;

    @Value("${sentinel.hybridrag.llm-expansion:false}")
    private boolean llmExpansionEnabled;

    // Pattern to extract query variants from LLM response
    private static final Pattern VARIANT_PATTERN = Pattern.compile(
            "^\\s*[-*\\d.)]?\\s*(.+?)\\s*$", Pattern.MULTILINE);

    private static final String EXPANSION_PROMPT = """
            Generate %d alternative ways to ask this question. Each variant should:
            - Preserve the original meaning
            - Use different words or phrasing
            - Be a complete question or search query

            Output each variant on a new line, numbered 1-N.

            Original query: %s
            """;

    // Common synonym mappings for rule-based expansion
    private static final Map<String, List<String>> SYNONYMS = Map.ofEntries(
            Map.entry("find", List.of("search", "locate", "discover", "identify")),
            Map.entry("show", List.of("display", "present", "reveal", "list")),
            Map.entry("explain", List.of("describe", "clarify", "elaborate", "detail")),
            Map.entry("create", List.of("make", "generate", "build", "produce")),
            Map.entry("delete", List.of("remove", "erase", "eliminate", "clear")),
            Map.entry("update", List.of("modify", "change", "edit", "revise")),
            Map.entry("error", List.of("issue", "problem", "bug", "fault")),
            Map.entry("security", List.of("protection", "safety", "defense", "safeguard")),
            Map.entry("data", List.of("information", "records", "content", "details")),
            Map.entry("user", List.of("person", "individual", "account", "member")),
            Map.entry("system", List.of("platform", "application", "software", "service")),
            Map.entry("access", List.of("permission", "authorization", "entry", "rights"))
    );

    public QueryExpander(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /**
     * Expand a query into multiple variants for improved retrieval.
     *
     * @param query Original user query
     * @param count Number of variants to generate (excluding original)
     * @return List of query variants
     */
    public List<String> expand(String query, int count) {
        if (query == null || query.isBlank() || count <= 0) {
            return List.of();
        }

        List<String> variants = new ArrayList<>();

        // Strategy 1: Rule-based synonym expansion
        List<String> synonymVariants = generateSynonymVariants(query);
        variants.addAll(synonymVariants);

        // Strategy 2: Question reformulation
        List<String> reformulations = generateReformulations(query);
        variants.addAll(reformulations);

        // Strategy 3: LLM-based expansion (if enabled and still need more variants)
        if (llmExpansionEnabled && variants.size() < count) {
            int remaining = count - variants.size();
            List<String> llmVariants = generateLlmVariants(query, remaining);
            variants.addAll(llmVariants);
        }

        // Deduplicate and limit to requested count
        Set<String> seen = new LinkedHashSet<>();
        seen.add(query.toLowerCase().trim()); // Exclude original

        List<String> result = new ArrayList<>();
        for (String variant : variants) {
            String normalized = variant.toLowerCase().trim();
            if (!normalized.isBlank() && seen.add(normalized)) {
                result.add(variant);
                if (result.size() >= count) {
                    break;
                }
            }
        }

        log.debug("QueryExpander: Generated {} variants for query: '{}'", result.size(),
                query.length() > 50 ? query.substring(0, 50) + "..." : query);

        return result;
    }

    /**
     * Generate variants using synonym replacement.
     */
    private List<String> generateSynonymVariants(String query) {
        List<String> variants = new ArrayList<>();
        String lower = query.toLowerCase();

        for (Map.Entry<String, List<String>> entry : SYNONYMS.entrySet()) {
            String word = entry.getKey();
            if (lower.contains(word)) {
                for (String synonym : entry.getValue()) {
                    // Case-insensitive replacement
                    String variant = query.replaceAll("(?i)\\b" + word + "\\b", synonym);
                    if (!variant.equalsIgnoreCase(query)) {
                        variants.add(variant);
                    }
                }
            }
        }

        return variants;
    }

    /**
     * Generate question reformulations.
     */
    private List<String> generateReformulations(String query) {
        List<String> variants = new ArrayList<>();
        String trimmed = query.trim();

        // If it's a question, try converting to a statement/search
        if (trimmed.endsWith("?")) {
            String statement = trimmed.substring(0, trimmed.length() - 1);

            // "What is X?" -> "X definition" / "about X"
            if (statement.toLowerCase().startsWith("what is ")) {
                String subject = statement.substring(8);
                variants.add(subject + " definition");
                variants.add("about " + subject);
            }
            // "How to X?" -> "X guide" / "steps to X"
            else if (statement.toLowerCase().startsWith("how to ")) {
                String action = statement.substring(7);
                variants.add(action + " guide");
                variants.add("steps to " + action);
            }
            // "Why does X?" -> "reason for X" / "X explanation"
            else if (statement.toLowerCase().startsWith("why ")) {
                String subject = statement.substring(4);
                variants.add("reason for " + subject);
                variants.add(subject + " explanation");
            }
        }
        // If it's a statement, try converting to a question
        else if (!trimmed.endsWith("?")) {
            variants.add("What is " + trimmed + "?");
            variants.add("How does " + trimmed + " work?");
        }

        return variants;
    }

    /**
     * Generate variants using LLM paraphrasing.
     */
    private List<String> generateLlmVariants(String query, int count) {
        try {
            String prompt = EXPANSION_PROMPT.formatted(count, query);

            @SuppressWarnings("deprecation")
            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            return parseLlmVariants(response);

        } catch (Exception e) {
            log.warn("LLM query expansion failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Parse LLM response to extract query variants.
     */
    private List<String> parseLlmVariants(String response) {
        List<String> variants = new ArrayList<>();

        if (response == null || response.isBlank()) {
            return variants;
        }

        for (String line : response.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // Remove numbering (1. 2. etc.) or bullets
            Matcher matcher = VARIANT_PATTERN.matcher(line);
            if (matcher.matches()) {
                String variant = matcher.group(1).trim();
                // Remove any trailing punctuation except ?
                if (variant.endsWith(".") || variant.endsWith(",")) {
                    variant = variant.substring(0, variant.length() - 1).trim();
                }
                if (!variant.isBlank() && variant.length() > 3) {
                    variants.add(variant);
                }
            }
        }

        return variants;
    }
}
