package com.jreinhal.mercenary.rag.thesaurus;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import com.jreinhal.mercenary.workspace.WorkspaceContext;

class DomainThesaurusTest {

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
}
