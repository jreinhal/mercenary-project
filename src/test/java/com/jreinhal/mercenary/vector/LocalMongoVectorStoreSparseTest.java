package com.jreinhal.mercenary.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.data.mongodb.core.MongoTemplate;

class LocalMongoVectorStoreSparseTest {

    @Test
    void sparseSearchReturnsEmptyForNullWeights() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        LocalMongoVectorStore store = new LocalMongoVectorStore(mongoTemplate, embeddingModel);

        List<Document> results = store.sparseSearch(null, null, 10, 0.01);
        assertTrue(results.isEmpty());
    }

    @Test
    void sparseSearchReturnsEmptyForEmptyWeights() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        LocalMongoVectorStore store = new LocalMongoVectorStore(mongoTemplate, embeddingModel);

        List<Document> results = store.sparseSearch(Map.of(), null, 10, 0.01);
        assertTrue(results.isEmpty());
    }

    @Test
    void sparseSearchRanksDocumentsByDotProduct() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        LocalMongoVectorStore store = new LocalMongoVectorStore(mongoTemplate, embeddingModel);

        // Create test MongoDocuments with sparse weights
        LocalMongoVectorStore.MongoDocument doc1 = new LocalMongoVectorStore.MongoDocument();
        doc1.setId("doc1");
        doc1.setContent("Intelligence report on cyber threats");
        doc1.setMetadata(new HashMap<>(Map.of("dept", "ENTERPRISE")));
        doc1.setEmbedding(List.of(1.0, 0.0));
        doc1.setEmbeddingNorm(1.0);
        doc1.setEmbeddingDimensions(2);
        doc1.setSparseWeights(Map.of("cyber", 0.9f, "threat", 0.7f, "report", 0.3f));

        LocalMongoVectorStore.MongoDocument doc2 = new LocalMongoVectorStore.MongoDocument();
        doc2.setId("doc2");
        doc2.setContent("Financial analysis quarterly update");
        doc2.setMetadata(new HashMap<>(Map.of("dept", "ENTERPRISE")));
        doc2.setEmbedding(List.of(0.0, 1.0));
        doc2.setEmbeddingNorm(1.0);
        doc2.setEmbeddingDimensions(2);
        doc2.setSparseWeights(Map.of("financial", 0.8f, "analysis", 0.5f));

        LocalMongoVectorStore.MongoDocument doc3 = new LocalMongoVectorStore.MongoDocument();
        doc3.setId("doc3");
        doc3.setContent("Cyber security assessment");
        doc3.setMetadata(new HashMap<>(Map.of("dept", "ENTERPRISE")));
        doc3.setEmbedding(List.of(0.5, 0.5));
        doc3.setEmbeddingNorm(0.5);
        doc3.setEmbeddingDimensions(2);
        doc3.setSparseWeights(Map.of("cyber", 0.6f, "security", 0.8f));

        // doc without sparse weights should be excluded
        LocalMongoVectorStore.MongoDocument doc4 = new LocalMongoVectorStore.MongoDocument();
        doc4.setId("doc4");
        doc4.setContent("No sparse weights here");
        doc4.setMetadata(new HashMap<>(Map.of("dept", "ENTERPRISE")));
        doc4.setEmbedding(List.of(0.3, 0.3));
        doc4.setEmbeddingNorm(0.18);
        doc4.setEmbeddingDimensions(2);
        // No sparse weights set

        when(mongoTemplate.findAll(eq(LocalMongoVectorStore.MongoDocument.class), eq("vector_store")))
                .thenReturn(List.of(doc1, doc2, doc3, doc4));

        // Query for "cyber threat"
        Map<String, Float> queryWeights = Map.of("cyber", 1.0f, "threat", 0.8f);
        List<Document> results = store.sparseSearch(queryWeights, null, 10, 0.01);

        // doc1 score: 0.9*1.0 + 0.7*0.8 = 1.46 (highest)
        // doc3 score: 0.6*1.0 = 0.6 (second)
        // doc2 score: 0 (no shared terms)
        // doc4: excluded (no sparse weights)
        assertEquals(2, results.size());
        assertEquals("doc1", results.get(0).getId());
        assertEquals("doc3", results.get(1).getId());

        // Verify sparse scores in metadata
        assertTrue((Double) results.get(0).getMetadata().get("sparseScore") > 1.0);
    }

    @Test
    void sparseSearchRespectsThreshold() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        LocalMongoVectorStore store = new LocalMongoVectorStore(mongoTemplate, embeddingModel);

        LocalMongoVectorStore.MongoDocument doc1 = new LocalMongoVectorStore.MongoDocument();
        doc1.setId("doc1");
        doc1.setContent("Low relevance document");
        doc1.setMetadata(new HashMap<>(Map.of("dept", "ENTERPRISE")));
        doc1.setEmbedding(List.of(1.0, 0.0));
        doc1.setEmbeddingNorm(1.0);
        doc1.setEmbeddingDimensions(2);
        doc1.setSparseWeights(Map.of("minor", 0.01f));

        when(mongoTemplate.findAll(eq(LocalMongoVectorStore.MongoDocument.class), eq("vector_store")))
                .thenReturn(List.of(doc1));

        // Query with high threshold
        Map<String, Float> queryWeights = Map.of("minor", 0.01f);
        List<Document> results = store.sparseSearch(queryWeights, null, 10, 0.5);

        // Score = 0.01 * 0.01 = 0.0001, below threshold of 0.5
        assertTrue(results.isEmpty());
    }

    @Test
    void sparseSearchRespectsTopK() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        LocalMongoVectorStore store = new LocalMongoVectorStore(mongoTemplate, embeddingModel);

        LocalMongoVectorStore.MongoDocument doc1 = new LocalMongoVectorStore.MongoDocument();
        doc1.setId("doc1");
        doc1.setContent("First");
        doc1.setMetadata(new HashMap<>(Map.of("dept", "ENTERPRISE")));
        doc1.setEmbedding(List.of(1.0, 0.0));
        doc1.setEmbeddingNorm(1.0);
        doc1.setEmbeddingDimensions(2);
        doc1.setSparseWeights(Map.of("test", 0.9f));

        LocalMongoVectorStore.MongoDocument doc2 = new LocalMongoVectorStore.MongoDocument();
        doc2.setId("doc2");
        doc2.setContent("Second");
        doc2.setMetadata(new HashMap<>(Map.of("dept", "ENTERPRISE")));
        doc2.setEmbedding(List.of(0.0, 1.0));
        doc2.setEmbeddingNorm(1.0);
        doc2.setEmbeddingDimensions(2);
        doc2.setSparseWeights(Map.of("test", 0.5f));

        when(mongoTemplate.findAll(eq(LocalMongoVectorStore.MongoDocument.class), eq("vector_store")))
                .thenReturn(List.of(doc1, doc2));

        Map<String, Float> queryWeights = Map.of("test", 1.0f);
        List<Document> results = store.sparseSearch(queryWeights, null, 1, 0.01);

        assertEquals(1, results.size());
        assertEquals("doc1", results.get(0).getId());
    }
}
