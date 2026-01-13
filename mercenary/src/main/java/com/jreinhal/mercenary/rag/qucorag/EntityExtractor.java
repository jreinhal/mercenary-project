package com.jreinhal.mercenary.rag.qucorag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Entity Extractor for QuCo-RAG uncertainty quantification.
 * 
 * Extracts named entities from text using pattern-based NER:
 * - PERSON: Capitalized multi-word names
 * - ORG: Organization patterns (Inc, Corp, LLC, etc.)
 * - LOCATION: Geographic entities
 * - DATE: Temporal expressions
 * - TECHNICAL: Domain-specific terms (protocols, standards, etc.)
 * 
 * This is a lightweight alternative to spaCy/Hugging Face models,
 * suitable for real-time inference without GPU dependencies.
 */
@Component("qucoragEntityExtractor")
public class EntityExtractor {

    private static final Logger log = LoggerFactory.getLogger(EntityExtractor.class);

    // Pattern for capitalized multi-word names (potential PERSON or ORG)
    private static final Pattern CAPITALIZED_PHRASE = Pattern.compile(
            "\\b([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)+)\\b");

    // Pattern for names following titles/roles (Agent John Smith, Director Sarah Connor, etc.)
    private static final Pattern TITLED_NAME_PATTERN = Pattern.compile(
            "\\b(?:Agent|Director|President|CEO|CTO|CFO|Dr\\.?|Mr\\.?|Mrs\\.?|Ms\\.?|Prof\\.?|General|Colonel|Captain|Lieutenant|Sergeant|Officer|Secretary|Senator|Governor|Mayor|Chief|Head|Lead|Senior|Junior)\\s+([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)+)\\b");

    // Organization suffixes
    private static final Pattern ORG_PATTERN = Pattern.compile(
            "\\b([A-Z][\\w\\s&]+(?:Inc\\.?|Corp\\.?|LLC|Ltd\\.?|Company|Corporation|Group|Foundation|Institute|Agency|Department))\\b",
            Pattern.CASE_INSENSITIVE);

    // Location patterns (cities, countries, geographic features)
    private static final Pattern LOCATION_PATTERN = Pattern.compile(
            "\\b((?:New|San|Los|Las|Fort|Mount|Lake|North|South|East|West)\\s+[A-Z][a-z]+|" +
                    "[A-Z][a-z]+(?:land|burg|ville|town|city|shire|stan|ia))\\b");

    // Date patterns
    private static final Pattern DATE_PATTERN = Pattern.compile(
            "\\b(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}|" +
                    "(?:January|February|March|April|May|June|July|August|September|October|November|December)\\s+\\d{1,2}(?:,?\\s+\\d{4})?|"
                    +
                    "\\d{4})\\b",
            Pattern.CASE_INSENSITIVE);

    // Technical terms (protocols, standards, acronyms)
    private static final Pattern TECHNICAL_PATTERN = Pattern.compile(
            "\\b([A-Z]{2,}(?:-\\d+)?|" +
                    "(?:HTTP|HTTPS|TCP|UDP|SSH|SSL|TLS|API|REST|SOAP|JSON|XML|SQL|NoSQL|" +
                    "OAuth|JWT|SAML|LDAP|DNS|SMTP|FTP|SFTP|NFS|CIFS|" +
                    "AES|RSA|SHA|MD5|HMAC|PKI|X509|CAC|PIV|FIDO|" +
                    "NIST|ISO|SOC|HIPAA|GDPR|PCI|STIG|FedRAMP|FISMA|" +
                    "RAG|LLM|NLP|NER|OCR|ML|AI|GPU|CPU|RAM|SSD))\\b");

    /**
     * Entity types supported by the extractor.
     */
    public enum EntityType {
        PERSON, ORG, LOCATION, DATE, TECHNICAL, UNKNOWN
    }

    /**
     * Represents an extracted entity.
     */
    public record Entity(String text, EntityType type, int startIndex, int endIndex) {
        @Override
        public String toString() {
            return String.format("%s [%s]", text, type);
        }
    }

    /**
     * Extract all entities from the given text.
     *
     * @param text Input text to analyze
     * @return List of extracted entities
     */
    public List<Entity> extractEntities(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        Set<Entity> entities = new LinkedHashSet<>();

        // Extract by type (order matters - more specific patterns first)
        extractByPattern(text, ORG_PATTERN, EntityType.ORG, entities);
        extractByPattern(text, DATE_PATTERN, EntityType.DATE, entities);
        extractByPattern(text, TECHNICAL_PATTERN, EntityType.TECHNICAL, entities);
        extractByPattern(text, LOCATION_PATTERN, EntityType.LOCATION, entities);

        // Titled names (Agent John Smith, Director Sarah Connor, etc.)
        extractByPattern(text, TITLED_NAME_PATTERN, EntityType.PERSON, entities);

        // Capitalized phrases (potential PERSON) - only if not already matched
        extractCapitalizedPhrases(text, entities);

        List<Entity> result = new ArrayList<>(entities);
        log.debug("Extracted {} entities from text ({} chars)", result.size(), text.length());
        return result;
    }

    /**
     * Extract just entity strings (without type info) for frequency analysis.
     *
     * @param text Input text
     * @return Set of unique entity strings
     */
    public Set<String> extractEntityStrings(String text) {
        Set<String> result = new LinkedHashSet<>();
        for (Entity entity : extractEntities(text)) {
            result.add(entity.text());
        }
        return result;
    }

    /**
     * Extract entities matching a specific pattern.
     */
    private void extractByPattern(String text, Pattern pattern, EntityType type, Set<Entity> entities) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String match = matcher.group(1);
            if (match != null && !match.isBlank()) {
                entities.add(new Entity(match.trim(), type, matcher.start(1), matcher.end(1)));
            }
        }
    }

    /**
     * Extract capitalized phrases as potential PERSON entities.
     * Only adds if not already matched by other patterns.
     */
    private void extractCapitalizedPhrases(String text, Set<Entity> entities) {
        Set<String> existingTexts = new HashSet<>();
        for (Entity e : entities) {
            existingTexts.add(e.text().toLowerCase());
        }

        Matcher matcher = CAPITALIZED_PHRASE.matcher(text);
        while (matcher.find()) {
            String match = matcher.group(1);
            if (match != null && !match.isBlank()) {
                String normalized = match.trim().toLowerCase();
                // Skip if already matched or if it's a common phrase
                if (!existingTexts.contains(normalized) && !isCommonPhrase(match)) {
                    entities.add(new Entity(match.trim(), EntityType.PERSON, matcher.start(1), matcher.end(1)));
                    existingTexts.add(normalized);
                }
            }
        }
    }

    /**
     * Filter out common phrases that aren't real entities.
     */
    private boolean isCommonPhrase(String phrase) {
        String lower = phrase.toLowerCase();
        return lower.startsWith("the ") ||
                lower.startsWith("a ") ||
                lower.startsWith("an ") ||
                lower.equals("united states") ||
                lower.equals("new york") ||
                lower.length() < 4;
    }
}
