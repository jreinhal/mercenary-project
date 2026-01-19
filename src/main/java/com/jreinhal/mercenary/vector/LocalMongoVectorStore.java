/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.jreinhal.mercenary.vector.LocalMongoVectorStore
 *  com.jreinhal.mercenary.vector.LocalMongoVectorStore$MongoDocument
 *  com.jreinhal.mercenary.vector.LocalMongoVectorStore$ScoredDocument
 *  com.mongodb.client.result.DeleteResult
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.ai.document.Document
 *  org.springframework.ai.embedding.EmbeddingModel
 *  org.springframework.ai.vectorstore.SearchRequest
 *  org.springframework.ai.vectorstore.VectorStore
 *  org.springframework.data.mongodb.core.MongoTemplate
 *  org.springframework.data.mongodb.core.query.Criteria
 *  org.springframework.data.mongodb.core.query.CriteriaDefinition
 *  org.springframework.data.mongodb.core.query.Query
 */
package com.jreinhal.mercenary.vector;

import com.jreinhal.mercenary.vector.LocalMongoVectorStore;
import com.mongodb.client.result.DeleteResult;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.CriteriaDefinition;
import org.springframework.data.mongodb.core.query.Query;

public class LocalMongoVectorStore
implements VectorStore {
    private static final Logger log = LoggerFactory.getLogger(LocalMongoVectorStore.class);
    private static final String COLLECTION_NAME = "vector_store";
    private final MongoTemplate mongoTemplate;
    private final EmbeddingModel embeddingModel;

    public LocalMongoVectorStore(MongoTemplate mongoTemplate, EmbeddingModel embeddingModel) {
        this.mongoTemplate = mongoTemplate;
        this.embeddingModel = embeddingModel;
        log.info("Initialized LocalMongoVectorStore (Off-Grid Persistence Mode)");
    }

    public void add(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        try {
            for (Document doc : documents) {
                List embedding = doc.getEmbedding();
                if (embedding == null || embedding.isEmpty()) {
                    log.debug("Generating embedding for doc: {}", (Object)doc.getId());
                    embedding = this.embeddingModel.embed(doc.getContent());
                }
                MongoDocument mongoDoc = new MongoDocument();
                mongoDoc.setId(doc.getId());
                mongoDoc.setContent(doc.getContent());
                mongoDoc.setMetadata(doc.getMetadata());
                mongoDoc.setEmbedding(embedding);
                this.mongoTemplate.save((Object)mongoDoc, COLLECTION_NAME);
            }
            log.info("Persisted {} documents to local MongoDB", (Object)documents.size());
        }
        catch (Exception e) {
            log.error("CRITICAL ERROR in LocalMongoVectorStore.add()", (Throwable)e);
            throw new RuntimeException("Failed to save vectors: " + e.getMessage(), e);
        }
    }

    public Optional<Boolean> delete(List<String> idList) {
        if (idList == null || idList.isEmpty()) {
            return Optional.of(false);
        }
        Query query = new Query((CriteriaDefinition)Criteria.where((String)"_id").in(idList));
        DeleteResult result = this.mongoTemplate.remove(query, COLLECTION_NAME);
        log.info("Deleted {} documents from local store", (Object)result.getDeletedCount());
        return Optional.of(result.getDeletedCount() > 0L);
    }

    public List<Document> similaritySearch(SearchRequest request) {
        String queryText = request.getQuery();
        int topK = request.getTopK();
        double threshold = request.getSimilarityThreshold();
        List queryEmbedding = this.embeddingModel.embed(queryText);
        List allDocs = this.mongoTemplate.findAll(MongoDocument.class, COLLECTION_NAME);
        log.debug("Total documents found in vector store: {}", (Object)allDocs.size());
        return allDocs.stream().filter(md -> {
            try {
                String[] parts;
                String filter;
                if (request.getFilterExpression() != null && (filter = request.getFilterExpression().toString()).contains("key=dept") && filter.contains("value=") && (parts = filter.split("value=")).length > 1) {
                    String targetDept = parts[1].split("]")[0];
                    Object docDept = md.getMetadata().get("dept");
                    return targetDept.equals(docDept != null ? docDept.toString() : null);
                }
            }
            catch (Exception e) {
                return true;
            }
            return true;
        }).map(md -> {
            HashMap metadata = md.getMetadata() != null ? new HashMap(md.getMetadata()) : new HashMap();
            Document doc = new Document(md.getId(), md.getContent(), metadata);
            return new ScoredDocument(doc, this.calculateCosineSimilarity(queryEmbedding, md.getEmbedding()));
        }).filter(scored -> scored.score >= threshold).sorted((a, b) -> Double.compare(b.score, a.score)).limit(topK).map(scored -> {
            scored.document.getMetadata().put("score", scored.score);
            return scored.getDocument();
        }).collect(Collectors.toList());
    }

    private double calculateCosineSimilarity(List<Double> v1, List<Double> v2) {
        if (v1 == null || v2 == null || v1.isEmpty() || v2.isEmpty() || v1.size() != v2.size()) {
            return 0.0;
        }
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < v1.size(); ++i) {
            dotProduct += v1.get(i) * v2.get(i);
            normA += Math.pow(v1.get(i), 2.0);
            normB += Math.pow(v2.get(i), 2.0);
        }
        return normA == 0.0 || normB == 0.0 ? 0.0 : dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}

