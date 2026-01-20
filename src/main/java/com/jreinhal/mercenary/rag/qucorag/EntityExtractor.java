/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.stereotype.Component
 */
package com.jreinhal.mercenary.rag.qucorag;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component(value="qucoragEntityExtractor")
public class EntityExtractor {
    private static final Logger log = LoggerFactory.getLogger(EntityExtractor.class);
    private static final Pattern CAPITALIZED_PHRASE = Pattern.compile("\\b([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)+)\\b");
    private static final Pattern TITLED_NAME_PATTERN = Pattern.compile("\\b(?:Agent|Director|President|CEO|CTO|CFO|Dr\\.?|Mr\\.?|Mrs\\.?|Ms\\.?|Prof\\.?|General|Colonel|Captain|Lieutenant|Sergeant|Officer|Secretary|Senator|Governor|Mayor|Chief|Head|Lead|Senior|Junior)\\s+([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)+)\\b");
    private static final Pattern ORG_PATTERN = Pattern.compile("\\b([A-Z][\\w\\s&]+(?:Inc\\.?|Corp\\.?|LLC|Ltd\\.?|Company|Corporation|Group|Foundation|Institute|Agency|Department))\\b", 2);
    private static final Pattern LOCATION_PATTERN = Pattern.compile("\\b((?:New|San|Los|Las|Fort|Mount|Lake|North|South|East|West)\\s+[A-Z][a-z]+|[A-Z][a-z]+(?:land|burg|ville|town|city|shire|stan|ia))\\b");
    private static final Pattern DATE_PATTERN = Pattern.compile("\\b(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}|(?:January|February|March|April|May|June|July|August|September|October|November|December)\\s+\\d{1,2}(?:,?\\s+\\d{4})?|\\d{4})\\b", 2);
    private static final Pattern TECHNICAL_PATTERN = Pattern.compile("\\b([A-Z]{2,}(?:-\\d+)?|(?:HTTP|HTTPS|TCP|UDP|SSH|SSL|TLS|API|REST|SOAP|JSON|XML|SQL|NoSQL|OAuth|JWT|SAML|LDAP|DNS|SMTP|FTP|SFTP|NFS|CIFS|AES|RSA|SHA|MD5|HMAC|PKI|X509|CAC|PIV|FIDO|NIST|ISO|SOC|HIPAA|GDPR|PCI|STIG|FedRAMP|FISMA|RAG|LLM|NLP|NER|OCR|ML|AI|GPU|CPU|RAM|SSD))\\b");

    public List<Entity> extractEntities(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        LinkedHashSet<Entity> entities = new LinkedHashSet<Entity>();
        this.extractByPattern(text, ORG_PATTERN, EntityType.ORG, entities);
        this.extractByPattern(text, DATE_PATTERN, EntityType.DATE, entities);
        this.extractByPattern(text, TECHNICAL_PATTERN, EntityType.TECHNICAL, entities);
        this.extractByPattern(text, LOCATION_PATTERN, EntityType.LOCATION, entities);
        this.extractByPattern(text, TITLED_NAME_PATTERN, EntityType.PERSON, entities);
        this.extractCapitalizedPhrases(text, entities);
        ArrayList<Entity> result = new ArrayList<Entity>(entities);
        log.debug("Extracted {} entities from text ({} chars)", result.size(), text.length());
        return result;
    }

    public Set<String> extractEntityStrings(String text) {
        LinkedHashSet<String> result = new LinkedHashSet<String>();
        for (Entity entity : this.extractEntities(text)) {
            result.add(entity.text());
        }
        return result;
    }

    private void extractByPattern(String text, Pattern pattern, EntityType type, Set<Entity> entities) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String match = matcher.group(1);
            if (match == null || match.isBlank()) continue;
            entities.add(new Entity(match.trim(), type, matcher.start(1), matcher.end(1)));
        }
    }

    private void extractCapitalizedPhrases(String text, Set<Entity> entities) {
        HashSet<String> existingTexts = new HashSet<String>();
        for (Entity e : entities) {
            existingTexts.add(e.text().toLowerCase());
        }
        Matcher matcher = CAPITALIZED_PHRASE.matcher(text);
        while (matcher.find()) {
            String normalized;
            String match = matcher.group(1);
            if (match == null || match.isBlank() || existingTexts.contains(normalized = match.trim().toLowerCase()) || this.isCommonPhrase(match)) continue;
            entities.add(new Entity(match.trim(), EntityType.PERSON, matcher.start(1), matcher.end(1)));
            existingTexts.add(normalized);
        }
    }

    private boolean isCommonPhrase(String phrase) {
        String lower = phrase.toLowerCase();
        return lower.startsWith("the ") || lower.startsWith("a ") || lower.startsWith("an ") || lower.equals("united states") || lower.equals("new york") || lower.length() < 4;
    }

    public static enum EntityType {
        PERSON,
        ORG,
        LOCATION,
        DATE,
        TECHNICAL,
        UNKNOWN;

    }

    public record Entity(String text, EntityType type, int startIndex, int endIndex) {
        @Override
        public String toString() {
            return String.format("%s [%s]", new Object[]{this.text, this.type});
        }
    }
}
