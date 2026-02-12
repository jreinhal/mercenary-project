package com.jreinhal.mercenary.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.DeleteResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.Media;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.MimeTypeUtils;

class LocalMongoVectorStoreTest {

    @Test
    void addBatchesTextEmbeddingsAndUsesEmbeddingTextOverride() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        LocalMongoVectorStore store = new LocalMongoVectorStore(mongoTemplate, embeddingModel, 2, 0, false);

        Document d1 = new Document("d1", "Visible content 1", new HashMap<>(Map.of("dept", "ENTERPRISE", "embeddingText", "alpha")));
        Document d2 = new Document("d2", "Visible content 2", new HashMap<>(Map.of("dept", "ENTERPRISE", "embeddingText", "beta")));
        Document d3 = new Document("d3", "gamma", new HashMap<>(Map.of("dept", "ENTERPRISE")));

        when(embeddingModel.embed(eq(List.of("alpha", "beta"))))
                .thenReturn(List.of(new float[]{1.0f, 0.0f}, new float[]{0.0f, 1.0f}));
        when(embeddingModel.embed(eq(List.of("gamma"))))
                .thenReturn(List.of(new float[]{0.5f, 0.5f}));

        store.add(List.of(d1, d2, d3));

        verify(embeddingModel).embed(eq(List.of("alpha", "beta")));
        verify(embeddingModel).embed(eq(List.of("gamma")));
        verify(mongoTemplate, times(3)).save(any(LocalMongoVectorStore.MongoDocument.class), eq("vector_store"));
    }

    @Test
    void addUsesMultimodalEmbeddingForDocumentsWithMedia() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        LocalMongoVectorStore store = new LocalMongoVectorStore(mongoTemplate, embeddingModel, 128, 0, true);

        Document visualDoc = new Document(
                "visual-1",
                "chart summary",
                List.of(new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(new byte[]{1, 2, 3}))),
                new HashMap<>(Map.of("type", "visual", "embeddingText", "joint visual payload")));

        when(embeddingModel.embed(any(Document.class))).thenReturn(new float[]{0.2f, 0.8f});

        store.add(List.of(visualDoc));

        verify(embeddingModel).embed(any(Document.class));
        verify(embeddingModel, never()).embed(anyList());
    }

    @Test
    void addFallsBackToTextEmbeddingWhenMultimodalEmbeddingFails() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        LocalMongoVectorStore store = new LocalMongoVectorStore(mongoTemplate, embeddingModel, 128, 0, true);

        Document visualDoc = new Document(
                "visual-2",
                "fallback content",
                List.of(new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(new byte[]{9, 8, 7}))),
                new HashMap<>(Map.of("type", "visual", "embeddingText", "vision text")));

        doThrow(new RuntimeException("no multimodal support")).when(embeddingModel).embed(any(Document.class));
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{1.0f, 0.0f});

        store.add(List.of(visualDoc));

        verify(embeddingModel).embed(any(Document.class));
        verify(embeddingModel).embed(eq("vision text"));
    }

    @Test
    void addFailsWhenEmbeddingBatchResponseSizeDoesNotMatchInputSize() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        LocalMongoVectorStore store = new LocalMongoVectorStore(mongoTemplate, embeddingModel, 2, 0, false);

        Document d1 = new Document("id-1", "alpha", new HashMap<>(Map.of("dept", "ENTERPRISE")));
        Document d2 = new Document("id-2", "beta", new HashMap<>(Map.of("dept", "ENTERPRISE")));
        when(embeddingModel.embed(eq(List.of("alpha", "beta"))))
                .thenReturn(List.of(new float[]{1.0f, 0.0f}));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> store.add(List.of(d1, d2)));
        assertTrue(ex.getMessage().contains("Failed to save vectors"));
        verify(mongoTemplate, never()).save(any(LocalMongoVectorStore.MongoDocument.class), eq("vector_store"));
    }

    @Test
    void similaritySearchUsesPrefilterQueryForNumericComparators() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{1.0f, 0.0f});

        LocalMongoVectorStore store = new LocalMongoVectorStore(mongoTemplate, embeddingModel);

        LocalMongoVectorStore.MongoDocument inRange = new LocalMongoVectorStore.MongoDocument();
        inRange.setId("1");
        inRange.setContent("IN_RANGE");
        inRange.setMetadata(Map.of("dept", "MEDICAL", "workspaceId", "ws", "documentYear", 2021));
        inRange.setEmbedding(List.of(1.0, 0.0));
        inRange.setEmbeddingNorm(1.0);

        LocalMongoVectorStore.MongoDocument outOfRange = new LocalMongoVectorStore.MongoDocument();
        outOfRange.setId("2");
        outOfRange.setContent("OUT_OF_RANGE");
        outOfRange.setMetadata(Map.of("dept", "MEDICAL", "workspaceId", "ws", "documentYear", 2019));
        outOfRange.setEmbedding(List.of(1.0, 0.0));
        outOfRange.setEmbeddingNorm(1.0);

        when(mongoTemplate.find(any(Query.class), eq(LocalMongoVectorStore.MongoDocument.class), anyString()))
                .thenReturn(List.of(inRange, outOfRange));

        String filter = "dept == 'MEDICAL' && workspaceId == 'ws' && documentYear >= 2020 && documentYear <= 2022";
        FilterExpressionParser.ParsedFilter parsed = FilterExpressionParser.parse(filter);
        assertNotNull(parsed);
        assertFalse(parsed.invalid());
        assertEquals(1, parsed.orGroups().size());
        assertEquals(4, parsed.orGroups().get(0).size());
        assertEquals(true, FilterExpressionEvaluator.matches(inRange.getMetadata(), parsed));
        assertEquals(false, FilterExpressionEvaluator.matches(outOfRange.getMetadata(), parsed));
        SearchRequest request = SearchRequest.query("q")
                .withTopK(10)
                .withSimilarityThreshold(0.0)
                .withFilterExpression(filter);
        assertNotNull(request.getFilterExpression());
        FilterExpressionParser.ParsedFilter parsedFromRequest = FilterExpressionParser.parse(request.getFilterExpression());
        assertNotNull(parsedFromRequest);
        assertFalse(parsedFromRequest.invalid());
        assertEquals(1, parsedFromRequest.orGroups().size());
        assertEquals(4, parsedFromRequest.orGroups().get(0).size());

        List<Document> results = store.similaritySearch(request);

        assertFalse(results.isEmpty());
        assertEquals(1, results.size());
        assertEquals("IN_RANGE", results.get(0).getContent());

        verify(mongoTemplate, never()).findAll(eq(LocalMongoVectorStore.MongoDocument.class), anyString());
    }

    @Test
    void similaritySearchUsesFindAllWhenNoFilterExpressionProvided() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{1.0f, 0.0f});

        LocalMongoVectorStore store = new LocalMongoVectorStore(mongoTemplate, embeddingModel);

        LocalMongoVectorStore.MongoDocument doc = new LocalMongoVectorStore.MongoDocument();
        doc.setId("1");
        doc.setContent("doc");
        doc.setMetadata(Map.of("dept", "MEDICAL"));
        doc.setEmbedding(List.of(1.0, 0.0));
        doc.setEmbeddingNorm(1.0);
        when(mongoTemplate.findAll(LocalMongoVectorStore.MongoDocument.class, "vector_store"))
                .thenReturn(List.of(doc));

        List<Document> results = store.similaritySearch(SearchRequest.query("q").withTopK(5).withSimilarityThreshold(0.0));

        assertEquals(1, results.size());
        verify(mongoTemplate).findAll(LocalMongoVectorStore.MongoDocument.class, "vector_store");
        verify(mongoTemplate, never()).find(any(Query.class), eq(LocalMongoVectorStore.MongoDocument.class), anyString());
    }

    @Test
    void similaritySearchFailsClosedWhenFilterCannotBeParsed() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{1.0f, 0.0f});

        LocalMongoVectorStore store = new LocalMongoVectorStore(mongoTemplate, embeddingModel);

        LocalMongoVectorStore.MongoDocument doc = new LocalMongoVectorStore.MongoDocument();
        doc.setId("1");
        doc.setContent("doc");
        doc.setMetadata(Map.of("dept", "MEDICAL"));
        doc.setEmbedding(List.of(1.0, 0.0));
        doc.setEmbeddingNorm(1.0);
        when(mongoTemplate.find(any(Query.class), eq(LocalMongoVectorStore.MongoDocument.class), anyString()))
                .thenReturn(List.of(doc));

        SearchRequest request = SearchRequest.query("q").withTopK(5).withSimilarityThreshold(0.0);
        Filter.Expression invalidExpression = mock(Filter.Expression.class);
        when(invalidExpression.toString()).thenReturn("not a parseable filter");
        ReflectionTestUtils.setField(request, "filterExpression", invalidExpression);

        List<Document> results = store.similaritySearch(request);

        assertTrue(results.isEmpty(), "Invalid filter should fail closed");
        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(queryCaptor.capture(), eq(LocalMongoVectorStore.MongoDocument.class), eq("vector_store"));
        assertTrue(queryCaptor.getValue().toString().contains("__filter_parse_failure__"));
    }

    @Test
    void similaritySearchFailsClosedWhenInClauseIsEmpty() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{1.0f, 0.0f});

        LocalMongoVectorStore store = new LocalMongoVectorStore(mongoTemplate, embeddingModel);

        LocalMongoVectorStore.MongoDocument doc = new LocalMongoVectorStore.MongoDocument();
        doc.setId("1");
        doc.setContent("doc");
        doc.setMetadata(Map.of("dept", "MEDICAL"));
        doc.setEmbedding(List.of(1.0, 0.0));
        doc.setEmbeddingNorm(1.0);
        when(mongoTemplate.find(any(Query.class), eq(LocalMongoVectorStore.MongoDocument.class), anyString()))
                .thenReturn(List.of(doc));

        SearchRequest request = SearchRequest.query("q")
                .withTopK(5)
                .withSimilarityThreshold(0.0);
        Filter.Expression emptyInExpression = mock(Filter.Expression.class);
        when(emptyInExpression.toString()).thenReturn("dept in []");
        ReflectionTestUtils.setField(request, "filterExpression", emptyInExpression);

        List<Document> results = store.similaritySearch(request);

        assertTrue(results.isEmpty(), "Empty IN filter should fail closed");
        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(queryCaptor.capture(), eq(LocalMongoVectorStore.MongoDocument.class), eq("vector_store"));
        String queryString = queryCaptor.getValue().toString();
        assertTrue(queryString.contains("\"_id\""));
        assertTrue(queryString.contains("\"$in\""));
        assertTrue(queryString.contains("[]"));
    }

    @Test
    void deleteReturnsFalseForEmptyIdsAndTrueWhenDocumentsDeleted() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        LocalMongoVectorStore store = new LocalMongoVectorStore(mongoTemplate, embeddingModel);

        Optional<Boolean> empty = store.delete(List.of());
        assertTrue(empty.isPresent());
        assertFalse(empty.get());

        when(mongoTemplate.remove(any(Query.class), eq("vector_store"))).thenReturn(DeleteResult.acknowledged(2L));
        Optional<Boolean> deleted = store.delete(List.of("a", "b"));
        assertTrue(deleted.isPresent());
        assertTrue(deleted.get());
    }
}
