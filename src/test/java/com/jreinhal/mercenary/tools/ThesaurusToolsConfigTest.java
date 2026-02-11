package com.jreinhal.mercenary.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jreinhal.mercenary.rag.thesaurus.DomainThesaurus;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ThesaurusToolsConfigTest {

    @Test
    void searchThesaurusReturnsFormattedMatches() {
        DomainThesaurus thesaurus = mock(DomainThesaurus.class);
        when(thesaurus.search(eq("HIPAA"), eq("MEDICAL"), anyInt()))
                .thenReturn(List.of(new DomainThesaurus.ThesaurusMatch(
                        "HIPAA",
                        List.of("Health Insurance Portability and Accountability Act"),
                        "MEDICAL",
                        "lexical"
                )));

        ThesaurusToolsConfig cfg = new ThesaurusToolsConfig();
        Function<ThesaurusToolsConfig.ThesaurusSearchRequest, String> tool = cfg.searchThesaurus(thesaurus);

        String out = tool.apply(new ThesaurusToolsConfig.ThesaurusSearchRequest("HIPAA", "MEDICAL", 5));
        assertTrue(out.contains("HIPAA"));
        assertTrue(out.toLowerCase().contains("health insurance portability"));
    }

    @Test
    void searchThesaurusHandlesMissingTerm() {
        DomainThesaurus thesaurus = mock(DomainThesaurus.class);
        when(thesaurus.search(anyString(), anyString(), anyInt())).thenReturn(List.of());

        ThesaurusToolsConfig cfg = new ThesaurusToolsConfig();
        Function<ThesaurusToolsConfig.ThesaurusSearchRequest, String> tool = cfg.searchThesaurus(thesaurus);

        String out = tool.apply(new ThesaurusToolsConfig.ThesaurusSearchRequest("   ", "MEDICAL", 5));
        assertTrue(out.toLowerCase().contains("no term"));
    }
}

