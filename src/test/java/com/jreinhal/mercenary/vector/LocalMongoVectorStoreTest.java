package com.jreinhal.mercenary.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class LocalMongoVectorStoreTest {

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
}
