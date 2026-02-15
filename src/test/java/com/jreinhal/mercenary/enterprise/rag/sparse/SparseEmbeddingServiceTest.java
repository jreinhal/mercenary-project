package com.jreinhal.mercenary.enterprise.rag.sparse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.document.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.util.ReflectionTestUtils;

class SparseEmbeddingServiceTest {

    @Mock
    private SparseEmbeddingClient client;
    @Mock
    private MongoTemplate mongoTemplate;

    private SparseEmbeddingService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new SparseEmbeddingService(client, mongoTemplate);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "batchSize", 64);
    }

    @Test
    void isEnabledReturnsFalseWhenDisabled() {
        ReflectionTestUtils.setField(service, "enabled", false);
        assertFalse(service.isEnabled());
    }

    @Test
    void isEnabledReturnsFalseWhenClientDisabled() {
        when(client.isEnabled()).thenReturn(false);
        assertFalse(service.isEnabled());
    }

    @Test
    void isEnabledReturnsTrueWhenBothEnabled() {
        when(client.isEnabled()).thenReturn(true);
        assertTrue(service.isEnabled());
    }

    @Test
    void computeAndStoreSparseWeightsSkipsWhenDisabled() {
        ReflectionTestUtils.setField(service, "enabled", false);
        Document doc = new Document("d1", "test content", new HashMap<>());
        service.computeAndStoreSparseWeights(List.of(doc));
        verify(client, never()).embedSparse(any());
    }

    @Test
    void computeAndStoreSparseWeightsSkipsNullInput() {
        when(client.isEnabled()).thenReturn(true);
        service.computeAndStoreSparseWeights(null);
        verify(client, never()).embedSparse(any());
    }

    @Test
    void computeAndStoreSparseWeightsStoresWeights() {
        when(client.isEnabled()).thenReturn(true);

        Map<String, Float> weights1 = Map.of("intelligence", 0.85f, "report", 0.42f);
        Map<String, Float> weights2 = Map.of("security", 0.91f, "threat", 0.67f);
        when(client.embedSparse(any())).thenReturn(List.of(weights1, weights2));

        Document d1 = new Document("doc1", "intelligence report content", new HashMap<>());
        Document d2 = new Document("doc2", "security threat assessment", new HashMap<>());

        service.computeAndStoreSparseWeights(List.of(d1, d2));

        verify(mongoTemplate, times(2)).updateFirst(any(Query.class), any(Update.class), eq("vector_store"));
    }

    @Test
    void computeAndStoreSparseWeightsHandlesEmptyResults() {
        when(client.isEnabled()).thenReturn(true);
        when(client.embedSparse(any())).thenReturn(List.of());

        Document d1 = new Document("d1", "test", new HashMap<>());
        service.computeAndStoreSparseWeights(List.of(d1));

        verify(mongoTemplate, never()).updateFirst(any(), any(), anyString());
    }

    @Test
    void computeAndStoreSparseWeightsHandlesClientException() {
        when(client.isEnabled()).thenReturn(true);
        when(client.embedSparse(any())).thenThrow(new RuntimeException("Sidecar down"));

        Document d1 = new Document("d1", "test", new HashMap<>());
        // Should not throw - graceful degradation
        service.computeAndStoreSparseWeights(List.of(d1));
    }

    @Test
    void embedQueryReturnsWeightsOnSuccess() {
        when(client.isEnabled()).thenReturn(true);
        Map<String, Float> expected = Map.of("cyber", 0.95f, "attack", 0.72f);
        when(client.embedSparse(eq(List.of("cyber attack vectors")))).thenReturn(List.of(expected));

        Map<String, Float> result = service.embedQuery("cyber attack vectors");
        assertEquals(expected, result);
    }

    @Test
    void embedQueryReturnsEmptyWhenDisabled() {
        ReflectionTestUtils.setField(service, "enabled", false);
        Map<String, Float> result = service.embedQuery("test query");
        assertTrue(result.isEmpty());
    }

    @Test
    void embedQueryReturnsEmptyForBlankInput() {
        when(client.isEnabled()).thenReturn(true);
        Map<String, Float> result = service.embedQuery("   ");
        assertTrue(result.isEmpty());
    }

    @Test
    void embedQueryReturnsEmptyOnClientFailure() {
        when(client.isEnabled()).thenReturn(true);
        when(client.embedSparse(any())).thenReturn(List.of());
        Map<String, Float> result = service.embedQuery("test");
        assertTrue(result.isEmpty());
    }
}
