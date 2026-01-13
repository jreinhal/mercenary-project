package com.jreinhal.mercenary.rag.qucorag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-based Entity Extractor for QuCo-RAG uncertainty quantification.
 *
 * Uses the LLM to extract named entities with higher accuracy than pattern-based
 * extraction, especially for:
 * - Complex multi-word entity names
 * - Domain-specific terminology
 * - Contextual entity disambiguation
 * - Non-standard entity formats
 *
 * This is an optional enhancement that can be enabled via configuration.
 * When disabled, falls back to the pattern-based EntityExtractor.
 *
 * Trade-off: Higher accuracy but adds ~200-500ms latency per extraction.
 */
@Component
public class LlmEntityExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmEntityExtractor.class);

    private final ChatClient chatClient;
    private final EntityExtractor patternExtractor;

    @Value("${sentinel.qucorag.llm-extraction-enabled:false}")
    private boolean enabled;

    @Value("${sentinel.qucorag.llm-extraction-timeout-ms:3000}")
    private int timeoutMs;

    // Pattern to parse LLM entity output
    private static final Pattern ENTITY_LINE_PATTERN = Pattern.compile(
            "^\\s*[-*]?\\s*(.+?)\\s*\\[([A-Z_]+)\\]\\s*$", Pattern.MULTILINE);

    private static final String EXTRACTION_PROMPT = """
            Extract all named entities from the following text. For each entity, output it on a new line in this exact format:
            - EntityName [TYPE]

            Entity types to use:
            - PERSON: Names of people
            - ORG: Organizations, companies, agencies, institutions
            - LOCATION: Cities, countries, geographic locations
            - DATE: Dates, time periods, years
            - TECHNICAL: Technical terms, protocols, standards, acronyms
            - PRODUCT: Product names, software, systems
            - EVENT: Named events, operations, missions

            Only output entities, no explanations. If no entities found, output: NONE

            Text to analyze:
            %s
            """;

    public LlmEntityExtractor(ChatClient.Builder builder, EntityExtractor patternExtractor) {
        this.chatClient = builder.build();
        this.patternExtractor = patternExtractor;
    }

    /**
     * Check if LLM-based extraction is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Extract entities using LLM, with fallback to pattern-based extraction.
     *
     * @param text Input text to analyze
     * @return Set of extracted entity strings
     */
    public Set<String> extractEntityStrings(String text) {
        if (!enabled || text == null || text.isBlank()) {
            return patternExtractor.extractEntityStrings(text);
        }

        try {
            long startTime = System.currentTimeMillis();

            // Truncate text if too long (to avoid context limits)
            String truncatedText = text.length() > 2000
                    ? text.substring(0, 2000) + "..."
                    : text;

            String prompt = EXTRACTION_PROMPT.formatted(truncatedText);

            @SuppressWarnings("deprecation")
            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            Set<String> entities = parseEntityResponse(response);

            long elapsed = System.currentTimeMillis() - startTime;
            log.debug("LLM entity extraction: {} entities in {}ms", entities.size(), elapsed);

            // Merge with pattern-based extraction for completeness
            Set<String> patternEntities = patternExtractor.extractEntityStrings(text);
            entities.addAll(patternEntities);

            return entities;

        } catch (Exception e) {
            log.warn("LLM entity extraction failed, falling back to patterns: {}", e.getMessage());
            return patternExtractor.extractEntityStrings(text);
        }
    }

    /**
     * Extract entities with type information using LLM.
     *
     * @param text Input text to analyze
     * @return List of extracted entities with types
     */
    public List<ExtractedEntity> extractEntitiesWithTypes(String text) {
        if (!enabled || text == null || text.isBlank()) {
            // Convert pattern-based entities to ExtractedEntity format
            return patternExtractor.extractEntities(text).stream()
                    .map(e -> new ExtractedEntity(e.text(), e.type().name()))
                    .toList();
        }

        try {
            String truncatedText = text.length() > 2000
                    ? text.substring(0, 2000) + "..."
                    : text;

            String prompt = EXTRACTION_PROMPT.formatted(truncatedText);

            @SuppressWarnings("deprecation")
            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            return parseEntityResponseWithTypes(response);

        } catch (Exception ex) {
            log.warn("LLM entity extraction failed: {}", ex.getMessage());
            return patternExtractor.extractEntities(text).stream()
                    .map(ent -> new ExtractedEntity(ent.text(), ent.type().name()))
                    .toList();
        }
    }

    /**
     * Parse LLM response to extract entity strings.
     */
    private Set<String> parseEntityResponse(String response) {
        Set<String> entities = new LinkedHashSet<>();

        if (response == null || response.isBlank() || response.trim().equals("NONE")) {
            return entities;
        }

        Matcher matcher = ENTITY_LINE_PATTERN.matcher(response);
        while (matcher.find()) {
            String entityName = matcher.group(1).trim();
            if (!entityName.isBlank() && entityName.length() > 1) {
                entities.add(entityName);
            }
        }

        // Fallback: if pattern didn't match, try line-by-line parsing
        if (entities.isEmpty()) {
            for (String line : response.split("\n")) {
                line = line.trim();
                if (line.startsWith("-") || line.startsWith("*")) {
                    line = line.substring(1).trim();
                }
                // Remove trailing [TYPE] if present
                int bracketIdx = line.lastIndexOf('[');
                if (bracketIdx > 0) {
                    line = line.substring(0, bracketIdx).trim();
                }
                if (!line.isBlank() && line.length() > 1 && !line.equalsIgnoreCase("NONE")) {
                    entities.add(line);
                }
            }
        }

        return entities;
    }

    /**
     * Parse LLM response to extract entities with their types.
     */
    private List<ExtractedEntity> parseEntityResponseWithTypes(String response) {
        List<ExtractedEntity> entities = new ArrayList<>();

        if (response == null || response.isBlank() || response.trim().equals("NONE")) {
            return entities;
        }

        Matcher matcher = ENTITY_LINE_PATTERN.matcher(response);
        while (matcher.find()) {
            String entityName = matcher.group(1).trim();
            String entityType = matcher.group(2).trim();
            if (!entityName.isBlank() && entityName.length() > 1) {
                entities.add(new ExtractedEntity(entityName, entityType));
            }
        }

        return entities;
    }

    /**
     * Represents an extracted entity with its type.
     */
    public record ExtractedEntity(String name, String type) {
        @Override
        public String toString() {
            return name + " [" + type + "]";
        }
    }
}
