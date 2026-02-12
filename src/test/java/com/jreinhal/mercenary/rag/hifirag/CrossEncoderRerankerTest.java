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
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.document.Document;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.Answers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CrossEncoderRerankerTest {
    private ExecutorService executor;
    private ChatClient.Builder builder;
    private ChatClient chatClient;
    private CrossEncoderReranker reranker;

    @BeforeEach
    void setUp() {
        this.builder = mock(ChatClient.Builder.class);
        this.chatClient = mock(ChatClient.class, Answers.RETURNS_DEEP_STUBS);
        when(this.builder.build()).thenReturn(chatClient);
        this.executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        if (this.executor != null) {
            this.executor.shutdownNow();
        }
    }

    @Test
    void cacheShouldBeIsolatedByDepartment() {
        this.reranker = this.newReranker(null, false, "keyword");
        String query = "system metrics";
        Document enterpriseDoc = new Document("Metrics content", Map.of("dept", "ENTERPRISE", "source", "report.pdf"));
        Document medicalDoc = new Document("Metrics content", Map.of("dept", "MEDICAL", "source", "report.pdf"));

        this.reranker.rerank(query, List.of(enterpriseDoc, medicalDoc));

        @SuppressWarnings("unchecked")
        Cache<String, Double> cache = (Cache<String, Double>) ReflectionTestUtils.getField(this.reranker, "scoreCache");
        assertNotNull(cache);

        String enterpriseKey = ReflectionTestUtils.invokeMethod(this.reranker, "buildCacheKey", query, enterpriseDoc);
        String medicalKey = ReflectionTestUtils.invokeMethod(this.reranker, "buildCacheKey", query, medicalDoc);

        assertNotEquals(enterpriseKey, medicalKey);
        assertNotNull(cache.getIfPresent(enterpriseKey));
        assertNotNull(cache.getIfPresent(medicalKey));
    }

    @Test
    void dedicatedModeShouldUseEmbeddingScoring() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyString())).thenAnswer(invocation -> {
            String input = invocation.getArgument(0, String.class);
            if ("query: system metrics".equals(input)) {
                return new float[]{1.0f, 0.0f};
            }
            if (input.contains("document: Metrics content")) {
                return new float[]{1.0f, 0.0f};
            }
            if (input.contains("document: Unrelated content")) {
                return new float[]{0.0f, 1.0f};
            }
            return new float[]{0.5f, 0.5f};
        });
        this.reranker = this.newReranker(embeddingModel, false, "dedicated");

        String query = "system metrics";
        Document strongDoc = new Document("Metrics content", Map.of("dept", "ENTERPRISE", "source", "strong.pdf"));
        Document weakDoc = new Document("Unrelated content", Map.of("dept", "ENTERPRISE", "source", "weak.pdf"));

        List<HiFiRagService.ScoredDocument> ranked = this.reranker.rerank(query, List.of(weakDoc, strongDoc));

        assertEquals(2, ranked.size());
        assertEquals("strong.pdf", ranked.get(0).document().getMetadata().get("source"));
        assertTrue(ranked.get(0).score() > ranked.get(1).score());
    }

    @Test
    void dedicatedModeShouldFallbackToKeywordWhenEmbeddingModelMissing() {
        this.reranker = this.newReranker(null, false, "dedicated");

        String query = "alpha report";
        Document matching = new Document("alpha report summary", Map.of("dept", "ENTERPRISE", "source", "a.pdf"));
        Document nonMatching = new Document("different text", Map.of("dept", "ENTERPRISE", "source", "b.pdf"));

        List<HiFiRagService.ScoredDocument> ranked = this.reranker.rerank(query, List.of(nonMatching, matching));

        assertEquals(2, ranked.size());
        assertEquals("a.pdf", ranked.get(0).document().getMetadata().get("source"));
        assertTrue(ranked.get(0).score() >= ranked.get(1).score());
    }

    @Test
    void dedicatedModeShouldFallbackPerDocumentWhenEmbeddingFails() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyString())).thenAnswer(invocation -> {
            String input = invocation.getArgument(0, String.class);
            if ("query: system metrics".equals(input)) {
                return new float[]{1.0f, 0.0f};
            }
            throw new RuntimeException("embedding failure");
        });
        this.reranker = this.newReranker(embeddingModel, false, "dedicated");

        Document doc = new Document("system metrics baseline", Map.of("dept", "ENTERPRISE", "source", "x.pdf"));
        List<HiFiRagService.ScoredDocument> ranked = this.reranker.rerank("system metrics", List.of(doc));

        assertEquals(1, ranked.size());
        assertTrue(ranked.get(0).score() >= 0.99);
    }

    @Test
    void unknownModeShouldUseAutoBehavior() {
        this.reranker = this.newReranker(null, false, "mystery-mode");

        Document doc = new Document("alpha content", Map.of("dept", "ENTERPRISE", "source", "x.pdf"));
        List<HiFiRagService.ScoredDocument> ranked = this.reranker.rerank("alpha", List.of(doc));

        assertEquals(1, ranked.size());
        assertTrue(ranked.get(0).score() >= 0.0);
    }

    @Test
    void llmModeShouldParseNumericScores() {
        when(this.chatClient.prompt().user(anyString()).call().content()).thenReturn("0.87");
        this.reranker = this.newReranker(null, true, "llm");

        Document doc = new Document("irrelevant", Map.of("dept", "ENTERPRISE", "source", "llm.pdf"));
        List<HiFiRagService.ScoredDocument> ranked = this.reranker.rerank("query", List.of(doc));

        assertEquals(1, ranked.size());
        assertTrue(ranked.get(0).score() >= 0.86);
    }

    @Test
    void llmModeShouldFallbackToNeutralWhenScoreIsUnparseable() {
        when(this.chatClient.prompt().user(anyString()).call().content()).thenReturn("not-a-number");
        this.reranker = this.newReranker(null, true, "llm");

        Document doc = new Document("irrelevant", Map.of("dept", "ENTERPRISE", "source", "llm.pdf"));
        List<HiFiRagService.ScoredDocument> ranked = this.reranker.rerank("query", List.of(doc));

        assertEquals(1, ranked.size());
        assertTrue(ranked.get(0).score() >= 0.49 && ranked.get(0).score() <= 0.51);
    }

    private CrossEncoderReranker newReranker(EmbeddingModel embeddingModel, boolean useLlm, String mode) {
        CrossEncoderReranker rr = new CrossEncoderReranker(this.builder, this.executor, embeddingModel);
        ReflectionTestUtils.setField(rr, "cacheSize", 10);
        ReflectionTestUtils.setField(rr, "cacheTtlSeconds", 300L);
        ReflectionTestUtils.setField(rr, "useLlm", useLlm);
        ReflectionTestUtils.setField(rr, "rerankerMode", mode);
        ReflectionTestUtils.setField(rr, "batchSize", 2);
        ReflectionTestUtils.setField(rr, "timeoutSeconds", 1);
        rr.init();
        return rr;
    }
}
