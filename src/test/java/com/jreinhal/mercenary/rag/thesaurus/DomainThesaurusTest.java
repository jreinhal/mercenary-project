package com.jreinhal.mercenary.rag.thesaurus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import com.jreinhal.mercenary.workspace.WorkspaceContext;
import org.springframework.test.util.ReflectionTestUtils;

class DomainThesaurusTest {

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Test
    void expandsAcronymsFromConfiguredEntries() {
        DomainThesaurusProperties props = new DomainThesaurusProperties();
        props.setEnabled(true);
        props.setUnitConversionEnabled(false);
        props.setMaxQueryVariants(10);
        props.setEntries(Map.of(
                "GLOBAL", Map.of(
                        "HIPAA", List.of("Health Insurance Portability and Accountability Act")
                ),
                "GOVERNMENT", Map.of(
                        "LOX", List.of("Liquid Oxygen")
                )
        ));

        DomainThesaurus thesaurus = new DomainThesaurus(props, null);
        thesaurus.init();

        List<String> hipaa = thesaurus.expandQuery("What is HIPAA compliance?", "MEDICAL", 5);
        assertTrue(hipaa.stream().anyMatch(v -> v.toLowerCase().contains("health insurance portability")),
                "Expected HIPAA expansion to appear in variants");

        List<String> lox = thesaurus.expandQuery("LOX tank pressure", "GOVERNMENT", 5);
        assertTrue(lox.stream().anyMatch(v -> v.toLowerCase().contains("liquid oxygen")),
                "Expected LOX expansion to appear in variants");
    }

    @Test
    void expandsUnitConversionsWhenEnabled() {
        DomainThesaurusProperties props = new DomainThesaurusProperties();
        props.setEnabled(true);
        props.setUnitConversionEnabled(true);
        props.setMaxQueryVariants(10);
        props.setEntries(Map.of());

        DomainThesaurus thesaurus = new DomainThesaurus(props, null);
        thesaurus.init();

        List<String> variants = thesaurus.expandQuery("max pressure 100 psi", "ENTERPRISE", 5);
        assertTrue(variants.stream().anyMatch(v -> v.toLowerCase().contains("mpa")),
                "Expected PSI->MPa conversion variant");
    }

    @Test
    void searchUsesVectorStoreWhenEnabled() {
        DomainThesaurusProperties props = new DomainThesaurusProperties();
        props.setEnabled(true);
        props.setVectorIndexEnabled(true);
        props.setMaxQueryVariants(10);
        props.setEntries(Map.of(
                "MEDICAL", Map.of(
                        "BP", List.of("blood pressure")
                )
        ));

        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new Document("thesaurus|MEDICAL|BP", "x", Map.of(
                        "term", "BP",
                        "expansions", List.of("blood pressure")
                ))
        ));

        WorkspaceContext.setCurrentWorkspaceId("ws");
        try {
            DomainThesaurus thesaurus = new DomainThesaurus(props, vectorStore);
            thesaurus.init();

            List<DomainThesaurus.ThesaurusMatch> matches = thesaurus.search("BP", "MEDICAL", 5);
            assertTrue(matches.stream().anyMatch(m -> m.term().equalsIgnoreCase("BP")));
            verify(vectorStore).add(any());
            verify(vectorStore).similaritySearch(any(SearchRequest.class));
        } finally {
            WorkspaceContext.clear();
        }
    }

    @Test
    void expandQueryReturnsEmptyWhenDisabled() {
        DomainThesaurusProperties props = new DomainThesaurusProperties();
        props.setEnabled(false);
        props.setEntries(Map.of("GLOBAL", Map.of("HIPAA", List.of("x"))));

        DomainThesaurus thesaurus = new DomainThesaurus(props, null);
        thesaurus.init();

        assertTrue(thesaurus.expandQuery("HIPAA", "MEDICAL", 5).isEmpty());
    }

    @Test
    void expandsTemperatureConversionWhenEnabled() {
        DomainThesaurusProperties props = new DomainThesaurusProperties();
        props.setEnabled(true);
        props.setUnitConversionEnabled(true);
        props.setMaxQueryVariants(10);
        props.setEntries(Map.of());

        DomainThesaurus thesaurus = new DomainThesaurus(props, null);
        thesaurus.init();

        List<String> variants = thesaurus.expandQuery("temp 32 F", "ENTERPRISE", 5);
        assertTrue(variants.stream().anyMatch(v -> v.contains("0 C")));
    }

    @Test
    void searchFallsBackToLexicalWhenVectorSearchFails() {
        DomainThesaurusProperties props = new DomainThesaurusProperties();
        props.setEnabled(true);
        props.setVectorIndexEnabled(true);
        props.setMaxQueryVariants(10);
        props.setEntries(Map.of("GLOBAL", Map.of(
                "HIPAA", List.of("Health Insurance Portability and Accountability Act")
        )));

        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenThrow(new RuntimeException("boom"));

        DomainThesaurus thesaurus = new DomainThesaurus(props, vectorStore);
        thesaurus.init();

        List<DomainThesaurus.ThesaurusMatch> matches = thesaurus.search("HIPAA", "MEDICAL", 5);
        assertFalse(matches.isEmpty());
        assertTrue(matches.stream().anyMatch(m -> m.term().equalsIgnoreCase("HIPAA")));
    }

    @Test
    void searchCanMatchOnExpansionText() {
        DomainThesaurusProperties props = new DomainThesaurusProperties();
        props.setEnabled(true);
        props.setVectorIndexEnabled(false);
        props.setEntries(Map.of("GLOBAL", Map.of(
                "HIPAA", List.of("Health Insurance Portability and Accountability Act")
        )));

        DomainThesaurus thesaurus = new DomainThesaurus(props, null);
        thesaurus.init();

        List<DomainThesaurus.ThesaurusMatch> matches = thesaurus.search("health insurance", "ENTERPRISE", 5);
        assertFalse(matches.isEmpty());
        assertTrue(matches.stream().anyMatch(m -> m.term().equalsIgnoreCase("HIPAA")));
    }

    @Test
    void expandQueryHonorsConfiguredCapAndSkipsBlankExpansions() {
        DomainThesaurusProperties props = new DomainThesaurusProperties();
        props.setEnabled(true);
        props.setMaxQueryVariants(1);

        Map<String, Map<String, List<String>>> entries = new HashMap<>();
        entries.put("GLOBAL", Map.of(
                "BP", List.of("blood pressure", " ", "")
        ));
        props.setEntries(entries);

        DomainThesaurus thesaurus = new DomainThesaurus(props, null);
        thesaurus.init();

        List<String> variants = thesaurus.expandQuery("BP threshold", "MEDICAL", 10);
        assertTrue(variants.size() <= 2);
        assertFalse(variants.isEmpty());
        assertTrue(variants.stream().noneMatch(String::isBlank));
    }

    @Test
    void expandQueryReturnsEmptyForInvalidInputs() {
        DomainThesaurusProperties props = new DomainThesaurusProperties();
        props.setEnabled(true);
        props.setEntries(Map.of("GLOBAL", Map.of("BP", List.of("blood pressure"))));
        DomainThesaurus thesaurus = new DomainThesaurus(props, null);
        thesaurus.init();

        assertTrue(thesaurus.expandQuery("", "MEDICAL", 5).isEmpty());
        assertTrue(thesaurus.expandQuery("BP", "MEDICAL", 0).isEmpty());
    }

    @Test
    void searchFallsBackToLexicalWhenWorkspaceOrDepartmentIsMissing() {
        DomainThesaurusProperties props = new DomainThesaurusProperties();
        props.setEnabled(true);
        props.setVectorIndexEnabled(true);
        props.setEntries(Map.of("GLOBAL", Map.of("HIPAA", List.of("Health Insurance Portability and Accountability Act"))));
        VectorStore vectorStore = mock(VectorStore.class);

        DomainThesaurus thesaurus = new DomainThesaurus(props, vectorStore);
        thesaurus.init();

        WorkspaceContext.setCurrentWorkspaceId("ws");
        try {
            List<DomainThesaurus.ThesaurusMatch> noDept = thesaurus.search("HIPAA", " ", 5);
            assertFalse(noDept.isEmpty());
            assertTrue(noDept.stream().anyMatch(m -> m.term().equalsIgnoreCase("HIPAA")));
        } finally {
            WorkspaceContext.clear();
        }
    }

    @Test
    void vectorIndexUsesCacheAndIndexesOnlyOncePerWorkspaceAndDept() {
        DomainThesaurusProperties props = new DomainThesaurusProperties();
        props.setEnabled(true);
        props.setVectorIndexEnabled(true);
        props.setEntries(Map.of("MEDICAL", Map.of("BP", List.of("blood pressure"))));

        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        DomainThesaurus thesaurus = new DomainThesaurus(props, vectorStore);
        ReflectionTestUtils.setField(thesaurus, "indexCacheTtlSeconds", 3600L);
        thesaurus.init();

        WorkspaceContext.setCurrentWorkspaceId("ws");
        try {
            thesaurus.search("BP", "MEDICAL", 5);
            thesaurus.search("BP", "MEDICAL", 5);
            verify(vectorStore).add(any());
        } finally {
            WorkspaceContext.clear();
        }
    }

    @Test
    void searchMergesSemanticResultsAndHandlesNonListExpansions() {
        DomainThesaurusProperties props = new DomainThesaurusProperties();
        props.setEnabled(true);
        props.setVectorIndexEnabled(true);
        props.setEntries(Map.of("MEDICAL", Map.of("BP", List.of("blood pressure"))));

        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new Document("x", "x", Map.of("term", " ", "expansions", List.of("ignored"))),
                new Document("y", "y", Map.of("term", "MAP", "expansions", "mean arterial pressure"))
        ));

        DomainThesaurus thesaurus = new DomainThesaurus(props, vectorStore);
        thesaurus.init();

        WorkspaceContext.setCurrentWorkspaceId("ws");
        try {
            List<DomainThesaurus.ThesaurusMatch> matches = thesaurus.search("BP", "MEDICAL", 5);
            assertTrue(matches.stream().anyMatch(m -> m.term().equals("BP")));
            assertTrue(matches.stream().anyMatch(m -> m.term().equals("MAP")));
        } finally {
            WorkspaceContext.clear();
        }
    }

    @Test
    void unitConversionSupportsMpaAndCelsiusBranches() {
        DomainThesaurusProperties props = new DomainThesaurusProperties();
        props.setEnabled(true);
        props.setUnitConversionEnabled(true);
        props.setEntries(Map.of());

        DomainThesaurus thesaurus = new DomainThesaurus(props, null);
        thesaurus.init();

        List<String> variants = thesaurus.expandQuery("Set to 1 MPa and 100 C", "ENTERPRISE", 5);
        assertTrue(variants.stream().anyMatch(v -> v.toLowerCase().contains("psi")));
        assertTrue(variants.stream().anyMatch(v -> v.contains("F")));
    }

    @Test
    void rebuildIndexSkipsInvalidEntriesAndKeepsValidOnes() {
        DomainThesaurusProperties props = new DomainThesaurusProperties();
        props.setEnabled(true);
        Map<String, Map<String, List<String>>> entries = new HashMap<>();
        entries.put(" ", Map.of("IGNORED", List.of("x")));
        entries.put("MEDICAL", Map.of(
                " ", List.of("ignored"),
                "BP", List.of("", "blood pressure")
        ));
        props.setEntries(entries);

        DomainThesaurus thesaurus = new DomainThesaurus(props, null);
        thesaurus.init();

        List<String> variants = thesaurus.expandQuery("BP", "MEDICAL", 5);
        assertFalse(variants.isEmpty());
        assertTrue(variants.stream().anyMatch(v -> v.toLowerCase().contains("blood pressure")));
    }
}
