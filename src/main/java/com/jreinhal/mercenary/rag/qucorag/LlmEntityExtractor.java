/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.ai.chat.client.ChatClient
 *  org.springframework.ai.chat.client.ChatClient$Builder
 *  org.springframework.beans.factory.annotation.Value
 *  org.springframework.stereotype.Component
 */
package com.jreinhal.mercenary.rag.qucorag;

import com.jreinhal.mercenary.rag.qucorag.EntityExtractor;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LlmEntityExtractor {
    private static final Logger log = LoggerFactory.getLogger(LlmEntityExtractor.class);
    private final ChatClient chatClient;
    private final EntityExtractor patternExtractor;
    @Value(value="${sentinel.qucorag.llm-extraction-enabled:false}")
    private boolean enabled;
    @Value(value="${sentinel.qucorag.llm-extraction-timeout-ms:3000}")
    private int timeoutMs;
    private static final Pattern ENTITY_LINE_PATTERN = Pattern.compile("^\\s*[-*]?\\s*(.+?)\\s*\\[([A-Z_]+)\\]\\s*$", 8);
    private static final String EXTRACTION_PROMPT = "Extract all named entities from the following text. For each entity, output it on a new line in this exact format:\n- EntityName [TYPE]\n\nEntity types to use:\n- PERSON: Names of people\n- ORG: Organizations, companies, agencies, institutions\n- LOCATION: Cities, countries, geographic locations\n- DATE: Dates, time periods, years\n- TECHNICAL: Technical terms, protocols, standards, acronyms\n- PRODUCT: Product names, software, systems\n- EVENT: Named events, operations, missions\n\nOnly output entities, no explanations. If no entities found, output: NONE\n\nText to analyze:\n%s\n";

    public LlmEntityExtractor(ChatClient.Builder builder, EntityExtractor patternExtractor) {
        this.chatClient = builder.build();
        this.patternExtractor = patternExtractor;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public Set<String> extractEntityStrings(String text) {
        if (!this.enabled || text == null || text.isBlank()) {
            return this.patternExtractor.extractEntityStrings(text);
        }
        try {
            long startTime = System.currentTimeMillis();
            String truncatedText = text.length() > 2000 ? text.substring(0, 2000) + "..." : text;
            String prompt = EXTRACTION_PROMPT.formatted(truncatedText);
            String response = this.chatClient.prompt().user(prompt).call().content();
            Set<String> entities = this.parseEntityResponse(response);
            long elapsed = System.currentTimeMillis() - startTime;
            log.debug("LLM entity extraction: {} entities in {}ms", entities.size(), elapsed);
            Set<String> patternEntities = this.patternExtractor.extractEntityStrings(text);
            entities.addAll(patternEntities);
            return entities;
        }
        catch (Exception e) {
            log.warn("LLM entity extraction failed, falling back to patterns: {}", e.getMessage());
            return this.patternExtractor.extractEntityStrings(text);
        }
    }

    public List<ExtractedEntity> extractEntitiesWithTypes(String text) {
        if (!this.enabled || text == null || text.isBlank()) {
            return this.patternExtractor.extractEntities(text).stream().map(e -> new ExtractedEntity(e.text(), e.type().name())).toList();
        }
        try {
            String truncatedText = text.length() > 2000 ? text.substring(0, 2000) + "..." : text;
            String prompt = EXTRACTION_PROMPT.formatted(truncatedText);
            String response = this.chatClient.prompt().user(prompt).call().content();
            return this.parseEntityResponseWithTypes(response);
        }
        catch (Exception ex) {
            log.warn("LLM entity extraction failed: {}", ex.getMessage());
            return this.patternExtractor.extractEntities(text).stream().map(ent -> new ExtractedEntity(ent.text(), ent.type().name())).toList();
        }
    }

    private Set<String> parseEntityResponse(String response) {
        LinkedHashSet<String> entities = new LinkedHashSet<String>();
        if (response == null || response.isBlank() || response.trim().equals("NONE")) {
            return entities;
        }
        Matcher matcher = ENTITY_LINE_PATTERN.matcher(response);
        while (matcher.find()) {
            String entityName = matcher.group(1).trim();
            if (entityName.isBlank() || entityName.length() <= 1) continue;
            entities.add(entityName);
        }
        if (entities.isEmpty()) {
            for (String line : response.split("\n")) {
                int bracketIdx;
                if ((line = line.trim()).startsWith("-") || line.startsWith("*")) {
                    line = line.substring(1).trim();
                }
                if ((bracketIdx = line.lastIndexOf(91)) > 0) {
                    line = line.substring(0, bracketIdx).trim();
                }
                if (line.isBlank() || line.length() <= 1 || line.equalsIgnoreCase("NONE")) continue;
                entities.add(line);
            }
        }
        return entities;
    }

    private List<ExtractedEntity> parseEntityResponseWithTypes(String response) {
        ArrayList<ExtractedEntity> entities = new ArrayList<ExtractedEntity>();
        if (response == null || response.isBlank() || response.trim().equals("NONE")) {
            return entities;
        }
        Matcher matcher = ENTITY_LINE_PATTERN.matcher(response);
        while (matcher.find()) {
            String entityName = matcher.group(1).trim();
            String entityType = matcher.group(2).trim();
            if (entityName.isBlank() || entityName.length() <= 1) continue;
            entities.add(new ExtractedEntity(entityName, entityType));
        }
        return entities;
    }

    public record ExtractedEntity(String name, String type) {
        @Override
        public String toString() {
            return this.name + " [" + this.type + "]";
        }
    }
}
