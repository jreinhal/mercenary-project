package com.jreinhal.mercenary.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jreinhal.mercenary.rag.thesaurus.DomainThesaurus;
import com.jreinhal.mercenary.rag.thesaurus.DomainThesaurus.ThesaurusMatch;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ThesaurusToolsConfigTest {

    @Test
    void searchThesaurusReturnsMessageWhenNoTermProvided() {
        ThesaurusToolsConfig cfg = new ThesaurusToolsConfig();
        Function<ThesaurusToolsConfig.ThesaurusSearchRequest, String> fn =
                cfg.searchThesaurus(mock(DomainThesaurus.class));

        assertEquals("No term provided.", fn.apply(null));
        assertEquals("No term provided.", fn.apply(new ThesaurusToolsConfig.ThesaurusSearchRequest(" ", "ENTERPRISE", 5)));
        assertEquals("No term provided.", fn.apply(new ThesaurusToolsConfig.ThesaurusSearchRequest(null, "ENTERPRISE", 5)));
    }

    @Test
    void searchThesaurusReturnsMessageWhenNoMatchesFound() {
        DomainThesaurus thesaurus = mock(DomainThesaurus.class);
        when(thesaurus.search(eq("NASA"), eq("ENTERPRISE"), eq(5))).thenReturn(List.of());

        ThesaurusToolsConfig cfg = new ThesaurusToolsConfig();
        Function<ThesaurusToolsConfig.ThesaurusSearchRequest, String> fn =
                cfg.searchThesaurus(thesaurus);

        String out = fn.apply(new ThesaurusToolsConfig.ThesaurusSearchRequest("NASA", "ENTERPRISE", null));
        assertEquals("No thesaurus matches found.", out);
    }

    @Test
    void searchThesaurusFormatsMatches() {
        DomainThesaurus thesaurus = mock(DomainThesaurus.class);
        when(thesaurus.search(eq("NASA"), eq("ENTERPRISE"), eq(2))).thenReturn(List.of(
                new ThesaurusMatch("NASA", List.of("National Aeronautics and Space Administration"), "ENTERPRISE", "config")
        ));

        ThesaurusToolsConfig cfg = new ThesaurusToolsConfig();
        Function<ThesaurusToolsConfig.ThesaurusSearchRequest, String> fn =
                cfg.searchThesaurus(thesaurus);

        String out = fn.apply(new ThesaurusToolsConfig.ThesaurusSearchRequest("NASA", "ENTERPRISE", 2));
        assertTrue(out.startsWith("Thesaurus matches:"), "Should include a header");
        assertTrue(out.contains("- NASA -> National Aeronautics and Space Administration"), "Should format the match line");
        verify(thesaurus).search(eq("NASA"), eq("ENTERPRISE"), eq(2));
    }
}

