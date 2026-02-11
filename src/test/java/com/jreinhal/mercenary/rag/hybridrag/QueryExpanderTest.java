package com.jreinhal.mercenary.rag.hybridrag;

import com.github.benmanes.caffeine.cache.Cache;
import com.jreinhal.mercenary.rag.thesaurus.DomainThesaurus;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

class QueryExpanderTest {
    private QueryExpander queryExpander;
    private ChatClient.Builder builder;

    @BeforeEach
    void setUp() {
        builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        when(builder.build()).thenReturn(chatClient);

        queryExpander = new QueryExpander(builder, null);
        ReflectionTestUtils.setField(queryExpander, "cacheSize", 10);
        ReflectionTestUtils.setField(queryExpander, "cacheTtlSeconds", 300L);
        ReflectionTestUtils.setField(queryExpander, "llmExpansionEnabled", false);
        queryExpander.init();
    }

    @Test
    void cacheShouldBeIsolatedByDepartment() {
        String query = "Find system metrics";

        queryExpander.expand(query, 2, "ENTERPRISE");
        queryExpander.expand(query, 2, "MEDICAL");

        @SuppressWarnings("unchecked")
        Cache<String, List<String>> cache = (Cache<String, List<String>>) ReflectionTestUtils.getField(queryExpander, "expansionCache");
        assertNotNull(cache);

        String normalizedKey = query.trim().toLowerCase(Locale.ROOT);
        String keyEnterprise = "ENTERPRISE|" + normalizedKey + "|2";
        String keyMedical = "MEDICAL|" + normalizedKey + "|2";

        assertNotEquals(keyEnterprise, keyMedical);
        assertNotNull(cache.getIfPresent(keyEnterprise));
        assertNotNull(cache.getIfPresent(keyMedical));
    }

    @Test
    void includesDomainThesaurusVariantsWhenEnabled() {
        DomainThesaurus thesaurus = mock(DomainThesaurus.class);
        when(thesaurus.isEnabled()).thenReturn(true);
        when(thesaurus.expandQuery(anyString(), anyString(), anyInt())).thenReturn(List.of("EXPANDED_VARIANT"));

        QueryExpander expander = new QueryExpander(builder, thesaurus);
        ReflectionTestUtils.setField(expander, "llmExpansionEnabled", false);
        expander.init();

        List<String> variants = expander.expand("Hello?", 2, "ENTERPRISE");
        assertTrue(variants.contains("EXPANDED_VARIANT"));
        verify(thesaurus).expandQuery(anyString(), anyString(), anyInt());
    }
}
