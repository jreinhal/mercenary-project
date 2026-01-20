package com.jreinhal.mercenary.rag.miarag;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MindscapeBuilder {
    private static final Logger log = LoggerFactory.getLogger(MindscapeBuilder.class);
    private final ChatClient chatClient;
    @Value(value="${sentinel.miarag.chunks-per-summary:5}")
    private int chunksPerSummary;
    @Value(value="${sentinel.miarag.summary-max-tokens:200}")
    private int summaryMaxTokens;
    private static final String LEVEL_1_PROMPT = "Summarize the following text passages into a cohesive paragraph.\nPreserve key facts, entities, and relationships.\nKeep the summary under %d words.\n\nPassages:\n%s\n\nSummary:\n";
    private static final String LEVEL_2_PROMPT = "Combine these section summaries into a higher-level overview.\nFocus on main themes, key findings, and important connections.\nKeep the summary under %d words.\n\nSection Summaries:\n%s\n\nCombined Summary:\n";
    private static final String LEVEL_3_PROMPT = "Create a comprehensive document summary from these section overviews.\nThis summary should capture:\n1. The document's main purpose/topic\n2. Key findings or conclusions\n3. Important entities and relationships\n4. The overall narrative structure\n\nKeep the summary under %d words.\n\nSection Overviews:\n%s\n\nDocument Summary:\n";
    private static final String CONCEPT_EXTRACTION_PROMPT = "Extract the 5-10 most important concepts from this document summary.\nThese should be key topics, entities, or themes that would help someone\nfind this document.\n\nFormat as a comma-separated list.\n\nSummary:\n%s\n\nKey Concepts:\n";

    public MindscapeBuilder(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public List<String> summarizeLevel(List<String> inputs, int level) {
        if (inputs.isEmpty()) {
            return List.of();
        }
        if (inputs.size() == 1) {
            String summary = this.summarizeSingle(inputs.get(0), level);
            return List.of(summary);
        }
        ArrayList<String> summaries = new ArrayList<String>();
        List<List<String>> groups = this.partition(inputs, this.chunksPerSummary);
        for (List<String> group : groups) {
            String combinedInput = String.join((CharSequence)"\n\n---\n\n", group);
            String summary = this.summarizeGroup(combinedInput, level);
            summaries.add(summary);
        }
        return summaries;
    }

    private String summarizeSingle(String input, int level) {
        String prompt = switch (level) {
            case 1 -> LEVEL_1_PROMPT.formatted(this.summaryMaxTokens, input);
            case 2 -> LEVEL_2_PROMPT.formatted(this.summaryMaxTokens * 2, input);
            default -> LEVEL_3_PROMPT.formatted(this.summaryMaxTokens * 3, input);
        };
        return this.callLLM(prompt);
    }

    private String summarizeGroup(String combinedInput, int level) {
        String prompt = switch (level) {
            case 1 -> LEVEL_1_PROMPT.formatted(this.summaryMaxTokens, combinedInput);
            case 2 -> LEVEL_2_PROMPT.formatted(this.summaryMaxTokens * 2, combinedInput);
            default -> LEVEL_3_PROMPT.formatted(this.summaryMaxTokens * 3, combinedInput);
        };
        return this.callLLM(prompt);
    }

    public List<String> extractKeyConcepts(String documentSummary) {
        if (documentSummary == null || documentSummary.isBlank()) {
            return List.of();
        }
        String prompt = CONCEPT_EXTRACTION_PROMPT.formatted(documentSummary);
        String response = this.callLLM(prompt);
        if (response == null || response.isBlank()) {
            return List.of();
        }
        ArrayList<String> concepts = new ArrayList<String>();
        for (String concept : response.split(",")) {
            String trimmed = concept.trim().replaceAll("^\\d+\\.\\s*", "").replaceAll("^[-*]\\s*", "");
            if (trimmed.isBlank() || trimmed.length() <= 2) continue;
            concepts.add(trimmed);
        }
        return concepts.stream().limit(10L).toList();
    }

    private String callLLM(String prompt) {
        try {
            String response = this.chatClient.prompt().user(prompt).call().content();
            return response != null ? response.trim() : "";
        }
        catch (Exception e) {
            log.error("MindscapeBuilder: LLM call failed: {}", e.getMessage());
            return "";
        }
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        ArrayList<List<T>> partitions = new ArrayList<List<T>>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }
}
