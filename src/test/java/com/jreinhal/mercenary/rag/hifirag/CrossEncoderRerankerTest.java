package com.jreinhal.mercenary.rag.hifirag;

import com.github.benmanes.caffeine.cache.Cache;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CrossEncoderRerankerTest {
    private ExecutorService executor;
    private CrossEncoderReranker reranker;

    @BeforeEach
    void setUp() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        when(builder.build()).thenReturn(chatClient);

        executor = Executors.newSingleThreadExecutor();
        reranker = new CrossEncoderReranker(builder, executor);
        ReflectionTestUtils.setField(reranker, "cacheSize", 10);
        ReflectionTestUtils.setField(reranker, "cacheTtlSeconds", 300L);
        ReflectionTestUtils.setField(reranker, "useLlm", false);
        ReflectionTestUtils.setField(reranker, "batchSize", 2);
        ReflectionTestUtils.setField(reranker, "timeoutSeconds", 1);
        reranker.init();
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void cacheShouldBeIsolatedByDepartment() {
        String query = "system metrics";
        Document enterpriseDoc = new Document("Metrics content", Map.of("dept", "ENTERPRISE", "source", "report.pdf"));
        Document medicalDoc = new Document("Metrics content", Map.of("dept", "MEDICAL", "source", "report.pdf"));

        reranker.rerank(query, List.of(enterpriseDoc, medicalDoc));

        @SuppressWarnings("unchecked")
        Cache<String, Double> cache = (Cache<String, Double>) ReflectionTestUtils.getField(reranker, "scoreCache");
        assertNotNull(cache);

        String enterpriseKey = ReflectionTestUtils.invokeMethod(reranker, "buildCacheKey", query, enterpriseDoc);
        String medicalKey = ReflectionTestUtils.invokeMethod(reranker, "buildCacheKey", query, medicalDoc);

        assertNotEquals(enterpriseKey, medicalKey);
        assertNotNull(cache.getIfPresent(enterpriseKey));
        assertNotNull(cache.getIfPresent(medicalKey));
    }
}
