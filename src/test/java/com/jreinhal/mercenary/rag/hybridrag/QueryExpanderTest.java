package com.jreinhal.mercenary.rag.hybridrag;

import com.github.benmanes.caffeine.cache.Cache;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryExpanderTest {
    private QueryExpander queryExpander;

    @BeforeEach
    void setUp() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        when(builder.build()).thenReturn(chatClient);

        queryExpander = new QueryExpander(builder);
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
}
