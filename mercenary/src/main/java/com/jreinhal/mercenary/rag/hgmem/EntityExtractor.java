package com.jreinhal.mercenary.rag.hgmem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.*;

/**
 * Entity Extractor for HGMem Hypergraph Memory.
 *
 * Extracts named entities from text using pattern-based recognition.
 * Air-gap compatible - no external NLP API calls required.
 *
 * Entity Types:
 * - PERSON: Names of people
 * - ORGANIZATION: Company/agency names
 * - LOCATION: Geographic locations
 * - DATE: Dates and temporal expressions
 * - TECHNICAL: Technical terms, acronyms, protocols
 * - REFERENCE: Document/file references, IDs
 */
@Component
public class EntityExtractor {

    private static final Logger log = LoggerFactory.getLogger(EntityExtractor.class);

    /**
     * Entity types recognized by the extractor.
     */
    public enum EntityType {
        PERSON,
        ORGANIZATION,
        LOCATION,
        DATE,
        TECHNICAL,
        REFERENCE
    }

    /**
     * Extracted entity record.
     */
    public record Entity(String value, EntityType type, int startPos, int endPos) {
        public String normalizedValue() {
            return value.toLowerCase().trim();
        }
    }

    // Patterns for entity extraction
    private static final Pattern DATE_PATTERN = Pattern.compile(
            "\\b(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}|" +
            "\\d{4}[/-]\\d{2}[/-]\\d{2}|" +
            "(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+\\d{1,2},?\\s+\\d{4}|" +
            "\\d{1,2}\\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+\\d{4}|" +
            "Q[1-4]\\s+\\d{4}|" +
            "FY\\s*\\d{2,4}|" +
            "(?:19|20)\\d{2})\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern TECHNICAL_PATTERN = Pattern.compile(
            "\\b([A-Z]{2,}(?:-[A-Z0-9]+)*|" +
            "(?:API|SDK|URL|URI|SQL|HTTP|HTTPS|REST|SOAP|JWT|OAuth|SSL|TLS|SSH|FTP|DNS|TCP|IP|UUID|JSON|XML|HTML|CSS)[s]?|" +
            "\\w+(?:Service|Controller|Repository|Factory|Manager|Handler|Client|Server))\\b"
    );

    private static final Pattern REFERENCE_PATTERN = Pattern.compile(
            "\\b((?:DOC|REF|ID|CASE|TICKET|PR|MR|CR)-?\\d+|" +
            "#\\d+|" +
            "[A-Z]{2,3}-\\d{3,}|" +
            "\\b\\w+\\.(pdf|doc|docx|txt|md|csv|xlsx)\\b)",
            Pattern.CASE_INSENSITIVE
    );

    // Common name prefixes/suffixes
    private static final Set<String> NAME_PREFIXES = Set.of(
            "mr", "mrs", "ms", "dr", "prof", "sir", "dame", "gen", "col", "maj", "capt", "lt", "sgt"
    );

    private static final Set<String> NAME_SUFFIXES = Set.of(
            "jr", "sr", "ii", "iii", "iv", "phd", "md", "esq"
    );

    // Organization indicators
    private static final Set<String> ORG_INDICATORS = Set.of(
            "inc", "corp", "corporation", "llc", "ltd", "company", "co",
            "agency", "department", "bureau", "division", "office",
            "institute", "university", "college", "school", "foundation",
            "association", "organization", "group", "team", "committee"
    );

    // Location indicators
    private static final Set<String> LOCATION_INDICATORS = Set.of(
            "city", "town", "village", "state", "province", "country", "region",
            "street", "avenue", "road", "drive", "lane", "blvd", "highway",
            "building", "floor", "room", "suite", "office"
    );

    // Common stop words to filter out
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "a", "an", "and", "or", "but", "is", "are", "was", "were",
            "be", "been", "being", "have", "has", "had", "do", "does", "did",
            "will", "would", "could", "should", "may", "might", "must",
            "this", "that", "these", "those", "it", "its", "they", "them"
    );

    /**
     * Extract entities from text.
     *
     * @param text The text to analyze
     * @return List of extracted entities
     */
    public List<Entity> extract(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        List<Entity> entities = new ArrayList<>();

        // Extract different entity types
        entities.addAll(extractDates(text));
        entities.addAll(extractTechnical(text));
        entities.addAll(extractReferences(text));
        entities.addAll(extractNamesAndOrgs(text));

        // Deduplicate and sort
        Map<String, Entity> deduplicated = new LinkedHashMap<>();
        for (Entity e : entities) {
            String key = e.normalizedValue() + "_" + e.type();
            if (!deduplicated.containsKey(key) ||
                deduplicated.get(key).value().length() < e.value().length()) {
                deduplicated.put(key, e);
            }
        }

        List<Entity> result = new ArrayList<>(deduplicated.values());
        log.debug("Extracted {} unique entities from text ({} chars)", result.size(), text.length());

        return result;
    }

    /**
     * Extract date entities.
     */
    private List<Entity> extractDates(String text) {
        List<Entity> dates = new ArrayList<>();
        Matcher matcher = DATE_PATTERN.matcher(text);

        while (matcher.find()) {
            String value = matcher.group(1);
            if (value != null && value.length() >= 4) {
                dates.add(new Entity(value, EntityType.DATE, matcher.start(), matcher.end()));
            }
        }

        return dates;
    }

    /**
     * Extract technical terms.
     */
    private List<Entity> extractTechnical(String text) {
        List<Entity> technical = new ArrayList<>();
        Matcher matcher = TECHNICAL_PATTERN.matcher(text);

        while (matcher.find()) {
            String value = matcher.group(1);
            if (value != null && value.length() >= 2 && !STOP_WORDS.contains(value.toLowerCase())) {
                technical.add(new Entity(value, EntityType.TECHNICAL, matcher.start(), matcher.end()));
            }
        }

        return technical;
    }

    /**
     * Extract reference IDs.
     */
    private List<Entity> extractReferences(String text) {
        List<Entity> refs = new ArrayList<>();
        Matcher matcher = REFERENCE_PATTERN.matcher(text);

        while (matcher.find()) {
            String value = matcher.group(1);
            if (value != null) {
                refs.add(new Entity(value, EntityType.REFERENCE, matcher.start(), matcher.end()));
            }
        }

        return refs;
    }

    /**
     * Extract names and organizations using capitalization patterns.
     */
    private List<Entity> extractNamesAndOrgs(String text) {
        List<Entity> entities = new ArrayList<>();

        // Pattern for capitalized word sequences
        Pattern capPattern = Pattern.compile("\\b([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)+)\\b");
        Matcher matcher = capPattern.matcher(text);

        while (matcher.find()) {
            String phrase = matcher.group(1);
            String[] words = phrase.split("\\s+");

            // Skip very short phrases
            if (words.length < 2) continue;

            // Classify as person, organization, or location
            EntityType type = classifyCapitalizedPhrase(phrase, words);

            if (type != null) {
                entities.add(new Entity(phrase, type, matcher.start(), matcher.end()));
            }
        }

        // Also extract single capitalized words that might be names
        Pattern singleCapPattern = Pattern.compile("\\b(?:Mr|Mrs|Ms|Dr|Prof)\\.?\\s+([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*)\\b");
        matcher = singleCapPattern.matcher(text);

        while (matcher.find()) {
            String name = matcher.group(1);
            if (name != null && name.length() > 2) {
                entities.add(new Entity(name, EntityType.PERSON, matcher.start(1), matcher.end(1)));
            }
        }

        return entities;
    }

    /**
     * Classify a capitalized phrase as person, organization, or location.
     */
    private EntityType classifyCapitalizedPhrase(String phrase, String[] words) {
        String lower = phrase.toLowerCase();
        String lastWord = words[words.length - 1].toLowerCase();
        String firstWord = words[0].toLowerCase();

        // Check for organization indicators
        for (String word : words) {
            if (ORG_INDICATORS.contains(word.toLowerCase())) {
                return EntityType.ORGANIZATION;
            }
        }
        if (ORG_INDICATORS.contains(lastWord)) {
            return EntityType.ORGANIZATION;
        }

        // Check for location indicators
        for (String word : words) {
            if (LOCATION_INDICATORS.contains(word.toLowerCase())) {
                return EntityType.LOCATION;
            }
        }

        // Check for name prefixes
        if (NAME_PREFIXES.contains(firstWord.replace(".", ""))) {
            return EntityType.PERSON;
        }

        // Check for name suffixes
        if (NAME_SUFFIXES.contains(lastWord.replace(".", ""))) {
            return EntityType.PERSON;
        }

        // Default heuristic: 2-3 capitalized words that look like names
        if (words.length == 2 || words.length == 3) {
            boolean allShort = Arrays.stream(words).allMatch(w -> w.length() <= 12);
            boolean noIndicators = Arrays.stream(words)
                    .noneMatch(w -> ORG_INDICATORS.contains(w.toLowerCase()) ||
                                    LOCATION_INDICATORS.contains(w.toLowerCase()));
            if (allShort && noIndicators) {
                return EntityType.PERSON;
            }
        }

        // For longer phrases, default to organization
        if (words.length >= 4) {
            return EntityType.ORGANIZATION;
        }

        return null; // Not confident enough to classify
    }

    /**
     * Extract entities and return as a simple list of values.
     */
    public List<String> extractValues(String text) {
        return extract(text).stream()
                .map(Entity::value)
                .distinct()
                .toList();
    }

    /**
     * Extract entities of a specific type.
     */
    public List<Entity> extractByType(String text, EntityType type) {
        return extract(text).stream()
                .filter(e -> e.type() == type)
                .toList();
    }
}
