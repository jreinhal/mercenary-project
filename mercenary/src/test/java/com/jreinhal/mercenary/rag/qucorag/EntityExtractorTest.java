package com.jreinhal.mercenary.rag.qucorag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for QuCo-RAG Entity Extractor.
 */
class EntityExtractorTest {

    private EntityExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new EntityExtractor();
    }

    @Test
    @DisplayName("Extract organization names with suffixes")
    void testExtractOrganizations() {
        String text = "Acme Corporation announced a partnership with TechCorp Inc.";
        List<EntityExtractor.Entity> entities = extractor.extractEntities(text);

        Set<String> names = extractor.extractEntityStrings(text);
        assertTrue(names.contains("Acme Corporation") || names.contains("TechCorp Inc"),
                "Should extract organization names");
    }

    @Test
    @DisplayName("Extract technical terms and acronyms")
    void testExtractTechnicalTerms() {
        String text = "The system uses OAuth 2.0 with JWT tokens over HTTPS.";
        Set<String> entities = extractor.extractEntityStrings(text);

        assertTrue(entities.contains("OAuth") || entities.contains("JWT") || entities.contains("HTTPS"),
                "Should extract technical terms like OAuth, JWT, HTTPS");
    }

    @Test
    @DisplayName("Extract date patterns")
    void testExtractDates() {
        String text = "The meeting was scheduled for January 15, 2024.";
        Set<String> entities = extractor.extractEntityStrings(text);

        assertTrue(entities.stream().anyMatch(e -> e.contains("January") || e.contains("2024")),
                "Should extract date expressions");
    }

    @Test
    @DisplayName("Extract capitalized person names")
    void testExtractPersonNames() {
        String text = "Agent John Smith reported to Director Sarah Connor yesterday.";
        Set<String> entities = extractor.extractEntityStrings(text);

        assertTrue(entities.contains("John Smith") || entities.contains("Sarah Connor"),
                "Should extract capitalized multi-word names");
    }

    @Test
    @DisplayName("Handle empty input gracefully")
    void testEmptyInput() {
        List<EntityExtractor.Entity> entities = extractor.extractEntities("");
        assertTrue(entities.isEmpty(), "Empty input should return empty list");

        entities = extractor.extractEntities(null);
        assertTrue(entities.isEmpty(), "Null input should return empty list");
    }

    @Test
    @DisplayName("Extract location patterns")
    void testExtractLocations() {
        String text = "The operation took place in New York and San Francisco.";
        Set<String> entities = extractor.extractEntityStrings(text);

        assertTrue(entities.contains("New York") || entities.contains("San Francisco"),
                "Should extract location names");
    }

    @Test
    @DisplayName("Entity deduplication across patterns")
    void testDeduplication() {
        String text = "Acme Corp and Acme Corp announced results.";
        List<EntityExtractor.Entity> entities = extractor.extractEntities(text);

        // Should not have duplicate entities
        long uniqueCount = entities.stream().map(EntityExtractor.Entity::text).distinct().count();
        assertEquals(uniqueCount, entities.size(), "Should not have duplicate entities");
    }
}
