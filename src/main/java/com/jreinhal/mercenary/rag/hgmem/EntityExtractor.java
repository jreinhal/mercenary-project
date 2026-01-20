/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.stereotype.Component
 */
package com.jreinhal.mercenary.rag.hgmem;

import java.lang.invoke.CallSite;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component(value="hgmemEntityExtractor")
public class EntityExtractor {
    private static final Logger log = LoggerFactory.getLogger(EntityExtractor.class);
    private static final Pattern DATE_PATTERN = Pattern.compile("\\b(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}|\\d{4}[/-]\\d{2}[/-]\\d{2}|(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+\\d{1,2},?\\s+\\d{4}|\\d{1,2}\\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+\\d{4}|Q[1-4]\\s+\\d{4}|FY\\s*\\d{2,4}|(?:19|20)\\d{2})\\b", 2);
    private static final Pattern TECHNICAL_PATTERN = Pattern.compile("\\b([A-Z]{2,}(?:-[A-Z0-9]+)*|(?:API|SDK|URL|URI|SQL|HTTP|HTTPS|REST|SOAP|JWT|OAuth|SSL|TLS|SSH|FTP|DNS|TCP|IP|UUID|JSON|XML|HTML|CSS)[s]?|\\w+(?:Service|Controller|Repository|Factory|Manager|Handler|Client|Server))\\b");
    private static final Pattern REFERENCE_PATTERN = Pattern.compile("\\b((?:DOC|REF|ID|CASE|TICKET|PR|MR|CR)-?\\d+|#\\d+|[A-Z]{2,3}-\\d{3,}|\\b\\w+\\.(pdf|doc|docx|txt|md|csv|xlsx)\\b)", 2);
    private static final Set<String> NAME_PREFIXES = Set.of("mr", "mrs", "ms", "dr", "prof", "sir", "dame", "gen", "col", "maj", "capt", "lt", "sgt");
    private static final Set<String> NAME_SUFFIXES = Set.of("jr", "sr", "ii", "iii", "iv", "phd", "md", "esq");
    private static final Set<String> ORG_INDICATORS = Set.of("inc", "corp", "corporation", "llc", "ltd", "company", "co", "agency", "department", "bureau", "division", "office", "institute", "university", "college", "school", "foundation", "association", "organization", "group", "team", "committee");
    private static final Set<String> LOCATION_INDICATORS = Set.of("city", "town", "village", "state", "province", "country", "region", "street", "avenue", "road", "drive", "lane", "blvd", "highway", "building", "floor", "room", "suite", "office");
    private static final Set<String> STOP_WORDS = Set.of("the", "a", "an", "and", "or", "but", "is", "are", "was", "were", "be", "been", "being", "have", "has", "had", "do", "does", "did", "will", "would", "could", "should", "may", "might", "must", "this", "that", "these", "those", "it", "its", "they", "them");

    public List<Entity> extract(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        ArrayList<Entity> entities = new ArrayList<Entity>();
        entities.addAll(this.extractDates(text));
        entities.addAll(this.extractTechnical(text));
        entities.addAll(this.extractReferences(text));
        entities.addAll(this.extractNamesAndOrgs(text));
        LinkedHashMap<String, Entity> deduplicated = new LinkedHashMap<String, Entity>();
        for (Entity e : entities) {
            String key = e.normalizedValue() + "_" + String.valueOf(e.type());
            if (deduplicated.containsKey(key) && deduplicated.get(key).value().length() >= e.value().length()) continue;
            deduplicated.put(key, e);
        }
        ArrayList<Entity> result = new ArrayList<Entity>(deduplicated.values());
        log.debug("Extracted {} unique entities from text ({} chars)", result.size(), text.length());
        return result;
    }

    private List<Entity> extractDates(String text) {
        ArrayList<Entity> dates = new ArrayList<Entity>();
        Matcher matcher = DATE_PATTERN.matcher(text);
        while (matcher.find()) {
            String value = matcher.group(1);
            if (value == null || value.length() < 4) continue;
            dates.add(new Entity(value, EntityType.DATE, matcher.start(), matcher.end()));
        }
        return dates;
    }

    private List<Entity> extractTechnical(String text) {
        ArrayList<Entity> technical = new ArrayList<Entity>();
        Matcher matcher = TECHNICAL_PATTERN.matcher(text);
        while (matcher.find()) {
            String value = matcher.group(1);
            if (value == null || value.length() < 2 || STOP_WORDS.contains(value.toLowerCase())) continue;
            technical.add(new Entity(value, EntityType.TECHNICAL, matcher.start(), matcher.end()));
        }
        return technical;
    }

    private List<Entity> extractReferences(String text) {
        ArrayList<Entity> refs = new ArrayList<Entity>();
        Matcher matcher = REFERENCE_PATTERN.matcher(text);
        while (matcher.find()) {
            String value = matcher.group(1);
            if (value == null) continue;
            refs.add(new Entity(value, EntityType.REFERENCE, matcher.start(), matcher.end()));
        }
        return refs;
    }

    private List<Entity> extractNamesAndOrgs(String text) {
        ArrayList<Entity> entities = new ArrayList<Entity>();
        Pattern capPattern = Pattern.compile("\\b([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)+)\\b");
        Matcher matcher = capPattern.matcher(text);
        while (matcher.find()) {
            EntityType type;
            String phrase = matcher.group(1);
            String[] words = phrase.split("\\s+");
            if (words.length < 2 || (type = this.classifyCapitalizedPhrase(phrase, words)) == null) continue;
            entities.add(new Entity(phrase, type, matcher.start(), matcher.end()));
        }
        Pattern singleCapPattern = Pattern.compile("\\b(?:Mr|Mrs|Ms|Dr|Prof)\\.?\\s+([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*)\\b");
        matcher = singleCapPattern.matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (name == null || name.length() <= 2) continue;
            entities.add(new Entity(name, EntityType.PERSON, matcher.start(1), matcher.end(1)));
        }
        return entities;
    }

    private EntityType classifyCapitalizedPhrase(String phrase, String[] words) {
        String lower = phrase.toLowerCase();
        String lastWord = words[words.length - 1].toLowerCase();
        String firstWord = words[0].toLowerCase();
        for (String word : words) {
            if (!ORG_INDICATORS.contains(word.toLowerCase())) continue;
            return EntityType.ORGANIZATION;
        }
        if (ORG_INDICATORS.contains(lastWord)) {
            return EntityType.ORGANIZATION;
        }
        for (String word : words) {
            if (!LOCATION_INDICATORS.contains(word.toLowerCase())) continue;
            return EntityType.LOCATION;
        }
        if (NAME_PREFIXES.contains(firstWord.replace(".", ""))) {
            return EntityType.PERSON;
        }
        if (NAME_SUFFIXES.contains(lastWord.replace(".", ""))) {
            return EntityType.PERSON;
        }
        if (words.length == 2 || words.length == 3) {
            boolean allShort = Arrays.stream(words).allMatch(w -> w.length() <= 12);
            boolean noIndicators = Arrays.stream(words).noneMatch(w -> ORG_INDICATORS.contains(w.toLowerCase()) || LOCATION_INDICATORS.contains(w.toLowerCase()));
            if (allShort && noIndicators) {
                return EntityType.PERSON;
            }
        }
        if (words.length >= 4) {
            return EntityType.ORGANIZATION;
        }
        return null;
    }

    public List<String> extractValues(String text) {
        return this.extract(text).stream().map(Entity::value).distinct().toList();
    }

    public List<Entity> extractByType(String text, EntityType type) {
        return this.extract(text).stream().filter(e -> e.type() == type).toList();
    }

    public record Entity(String value, EntityType type, int startPos, int endPos) {
        public String normalizedValue() {
            return this.value.toLowerCase().trim();
        }
    }

    public static enum EntityType {
        PERSON,
        ORGANIZATION,
        LOCATION,
        DATE,
        TECHNICAL,
        REFERENCE;

    }
}
