package com.jreinhal.mercenary.rag.miarag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mindscape Builder for MiA-RAG hierarchical summarization.
 *
 * Builds multi-level summaries that capture document semantics:
 * - Level 0: Original text chunks
 * - Level 1: Paragraph/chunk summaries (local context)
 * - Level 2: Section summaries (intermediate context)
 * - Level 3: Document summary (global mindscape)
 *
 * Each level provides increasingly abstract representations while
 * preserving key information for retrieval and generation.
 */
@Component
public class MindscapeBuilder {

    private static final Logger log = LoggerFactory.getLogger(MindscapeBuilder.class);

    private final ChatClient chatClient;

    @Value("${sentinel.miarag.chunks-per-summary:5}")
    private int chunksPerSummary;

    @Value("${sentinel.miarag.summary-max-tokens:200}")
    private int summaryMaxTokens;

    // Prompts for different summarization levels
    private static final String LEVEL_1_PROMPT = """
            Summarize the following text passages into a cohesive paragraph.
            Preserve key facts, entities, and relationships.
            Keep the summary under %d words.

            Passages:
            %s

            Summary:
            """;

    private static final String LEVEL_2_PROMPT = """
            Combine these section summaries into a higher-level overview.
            Focus on main themes, key findings, and important connections.
            Keep the summary under %d words.

            Section Summaries:
            %s

            Combined Summary:
            """;

    private static final String LEVEL_3_PROMPT = """
            Create a comprehensive document summary from these section overviews.
            This summary should capture:
            1. The document's main purpose/topic
            2. Key findings or conclusions
            3. Important entities and relationships
            4. The overall narrative structure

            Keep the summary under %d words.

            Section Overviews:
            %s

            Document Summary:
            """;

    private static final String CONCEPT_EXTRACTION_PROMPT = """
            Extract the 5-10 most important concepts from this document summary.
            These should be key topics, entities, or themes that would help someone
            find this document.

            Format as a comma-separated list.

            Summary:
            %s

            Key Concepts:
            """;

    public MindscapeBuilder(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * Summarize a level of the hierarchy.
     *
     * @param inputs Texts from the previous level
     * @param level The level being created (1, 2, or 3)
     * @return List of summaries for this level
     */
    public List<String> summarizeLevel(List<String> inputs, int level) {
        if (inputs.isEmpty()) {
            return List.of();
        }

        // Single input - just summarize directly
        if (inputs.size() == 1) {
            String summary = summarizeSingle(inputs.get(0), level);
            return List.of(summary);
        }

        // Group inputs and summarize each group
        List<String> summaries = new ArrayList<>();
        List<List<String>> groups = partition(inputs, chunksPerSummary);

        for (List<String> group : groups) {
            String combinedInput = String.join("\n\n---\n\n", group);
            String summary = summarizeGroup(combinedInput, level);
            summaries.add(summary);
        }

        return summaries;
    }

    /**
     * Summarize a single text at a given level.
     */
    private String summarizeSingle(String input, int level) {
        String prompt = switch (level) {
            case 1 -> LEVEL_1_PROMPT.formatted(summaryMaxTokens, input);
            case 2 -> LEVEL_2_PROMPT.formatted(summaryMaxTokens * 2, input);
            default -> LEVEL_3_PROMPT.formatted(summaryMaxTokens * 3, input);
        };

        return callLLM(prompt);
    }

    /**
     * Summarize a group of texts at a given level.
     */
    private String summarizeGroup(String combinedInput, int level) {
        String prompt = switch (level) {
            case 1 -> LEVEL_1_PROMPT.formatted(summaryMaxTokens, combinedInput);
            case 2 -> LEVEL_2_PROMPT.formatted(summaryMaxTokens * 2, combinedInput);
            default -> LEVEL_3_PROMPT.formatted(summaryMaxTokens * 3, combinedInput);
        };

        return callLLM(prompt);
    }

    /**
     * Extract key concepts from the document summary.
     */
    public List<String> extractKeyConcepts(String documentSummary) {
        if (documentSummary == null || documentSummary.isBlank()) {
            return List.of();
        }

        String prompt = CONCEPT_EXTRACTION_PROMPT.formatted(documentSummary);
        String response = callLLM(prompt);

        if (response == null || response.isBlank()) {
            return List.of();
        }

        // Parse comma-separated concepts
        List<String> concepts = new ArrayList<>();
        for (String concept : response.split(",")) {
            String trimmed = concept.trim()
                    .replaceAll("^\\d+\\.\\s*", "") // Remove numbering
                    .replaceAll("^[-*]\\s*", "");   // Remove bullets

            if (!trimmed.isBlank() && trimmed.length() > 2) {
                concepts.add(trimmed);
            }
        }

        // Limit to 10 concepts
        return concepts.stream().limit(10).toList();
    }

    /**
     * Call the LLM for summarization.
     */
    private String callLLM(String prompt) {
        try {
            @SuppressWarnings("deprecation")
            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
            return response != null ? response.trim() : "";
        } catch (Exception e) {
            log.error("MindscapeBuilder: LLM call failed: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Partition a list into groups of specified size.
     */
    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }
}
