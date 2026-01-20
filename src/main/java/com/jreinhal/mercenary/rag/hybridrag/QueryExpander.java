package com.jreinhal.mercenary.rag.hybridrag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class QueryExpander {
    private static final Logger log = LoggerFactory.getLogger(QueryExpander.class);
    private final ChatClient chatClient;
    @Value(value="${sentinel.hybridrag.llm-expansion:false}")
    private boolean llmExpansionEnabled;
    private static final Pattern VARIANT_PATTERN = Pattern.compile("^\\s*[-*\\d.)]?\\s*(.+?)\\s*$", 8);
    private static final String EXPANSION_PROMPT = "Generate %d alternative ways to ask this question. Each variant should:\n- Preserve the original meaning\n- Use different words or phrasing\n- Be a complete question or search query\n\nOutput each variant on a new line, numbered 1-N.\n\nOriginal query: %s\n";
    private static final Map<String, List<String>> SYNONYMS = Map.ofEntries(Map.entry("find", List.of("search", "locate", "discover", "identify")), Map.entry("show", List.of("display", "present", "reveal", "list")), Map.entry("explain", List.of("describe", "clarify", "elaborate", "detail")), Map.entry("create", List.of("make", "generate", "build", "produce")), Map.entry("delete", List.of("remove", "erase", "eliminate", "clear")), Map.entry("update", List.of("modify", "change", "edit", "revise")), Map.entry("error", List.of("issue", "problem", "bug", "fault")), Map.entry("security", List.of("protection", "safety", "defense", "safeguard")), Map.entry("data", List.of("information", "records", "content", "details")), Map.entry("user", List.of("person", "individual", "account", "member")), Map.entry("system", List.of("platform", "application", "software", "service")), Map.entry("access", List.of("permission", "authorization", "entry", "rights")));

    public QueryExpander(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    public List<String> expand(String query, int count) {
        if (query == null || query.isBlank() || count <= 0) {
            return List.of();
        }
        ArrayList<String> variants = new ArrayList<String>();
        List<String> synonymVariants = this.generateSynonymVariants(query);
        variants.addAll(synonymVariants);
        List<String> reformulations = this.generateReformulations(query);
        variants.addAll(reformulations);
        if (this.llmExpansionEnabled && variants.size() < count) {
            int remaining = count - variants.size();
            List<String> llmVariants = this.generateLlmVariants(query, remaining);
            variants.addAll(llmVariants);
        }
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        seen.add(query.toLowerCase().trim());
        ArrayList<String> result = new ArrayList<String>();
        for (String variant : variants) {
            String normalized = variant.toLowerCase().trim();
            if (normalized.isBlank() || !seen.add(normalized)) continue;
            result.add(variant);
            if (result.size() < count) continue;
            break;
        }
        log.debug("QueryExpander: Generated {} variants for query: '{}'", result.size(), query.length() > 50 ? query.substring(0, 50) + "..." : query);
        return result;
    }

    private List<String> generateSynonymVariants(String query) {
        ArrayList<String> variants = new ArrayList<String>();
        String lower = query.toLowerCase();
        for (Map.Entry<String, List<String>> entry : SYNONYMS.entrySet()) {
            String word = entry.getKey();
            if (!lower.contains(word)) continue;
            for (String synonym : entry.getValue()) {
                String variant = query.replaceAll("(?i)\\b" + word + "\\b", synonym);
                if (variant.equalsIgnoreCase(query)) continue;
                variants.add(variant);
            }
        }
        return variants;
    }

    private List<String> generateReformulations(String query) {
        ArrayList<String> variants = new ArrayList<String>();
        String trimmed = query.trim();
        if (trimmed.endsWith("?")) {
            String statement = trimmed.substring(0, trimmed.length() - 1);
            if (statement.toLowerCase().startsWith("what is ")) {
                String subject = statement.substring(8);
                variants.add(subject + " definition");
                variants.add("about " + subject);
            } else if (statement.toLowerCase().startsWith("how to ")) {
                String action = statement.substring(7);
                variants.add(action + " guide");
                variants.add("steps to " + action);
            } else if (statement.toLowerCase().startsWith("why ")) {
                String subject = statement.substring(4);
                variants.add("reason for " + subject);
                variants.add(subject + " explanation");
            }
        } else if (!trimmed.endsWith("?")) {
            variants.add("What is " + trimmed + "?");
            variants.add("How does " + trimmed + " work?");
        }
        return variants;
    }

    private List<String> generateLlmVariants(String query, int count) {
        try {
            String prompt = EXPANSION_PROMPT.formatted(count, query);
            String response = this.chatClient.prompt().user(prompt).call().content();
            return this.parseLlmVariants(response);
        }
        catch (Exception e) {
            log.warn("LLM query expansion failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<String> parseLlmVariants(String response) {
        ArrayList<String> variants = new ArrayList<String>();
        if (response == null || response.isBlank()) {
            return variants;
        }
        for (String line : response.split("\n")) {
            Matcher matcher;
            if ((line = line.trim()).isEmpty() || !(matcher = VARIANT_PATTERN.matcher(line)).matches()) continue;
            String variant = matcher.group(1).trim();
            if (variant.endsWith(".") || variant.endsWith(",")) {
                variant = variant.substring(0, variant.length() - 1).trim();
            }
            if (variant.isBlank() || variant.length() <= 3) continue;
            variants.add(variant);
        }
        return variants;
    }
}
