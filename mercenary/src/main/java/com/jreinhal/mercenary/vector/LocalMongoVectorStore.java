package com.jreinhal.mercenary.vector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.*;
import java.util.stream.Collectors;
import org.springframework.data.annotation.Id;

/**
 * A custom VectorStore implementation for LOCAL, AIR-GAPPED deployments.
 * 
 * DESIGN:
 * 1. PERSISTENCE: Stores documents in a local MongoDB collection
 * ("vector_store").
 * 2. SEARCH: Performs "Brute Force" Cosine Similarity in Java.
 * - Fetches all docs from Mongo (or caches them).
 * - Calculates distance to query vector.
 * - Returns top K.
 * 
 * WHY?
 * - Avoids dependency on MongoDB Atlas Search (Cloud).
 * - Avoids "In-Memory" data loss on restart.
 * - Acceptable performance for <10,000 documents.
 */
public class LocalMongoVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(LocalMongoVectorStore.class);
    private static final String COLLECTION_NAME = "vector_store";

    private final MongoTemplate mongoTemplate;
    private final EmbeddingModel embeddingModel;

    public LocalMongoVectorStore(MongoTemplate mongoTemplate, EmbeddingModel embeddingModel) {
        this.mongoTemplate = mongoTemplate;
        this.embeddingModel = embeddingModel;
        log.info("Initialized LocalMongoVectorStore (Off-Grid Persistence Mode)");
    }

    @Override
    public void add(List<Document> documents) {
        if (documents == null || documents.isEmpty())
            return;

        try {
            for (Document doc : documents) {
                // Ensure embedding exists
                List<Double> embedding = doc.getEmbedding();
                if (embedding == null || embedding.isEmpty()) {
                    log.debug("Generating embedding for doc: {}", doc.getId());
                    embedding = embeddingModel.embed(doc.getContent());
                }

                // Map to Wrapper
                MongoDocument mongoDoc = new MongoDocument();
                mongoDoc.setId(doc.getId());
                mongoDoc.setContent(doc.getContent());
                mongoDoc.setMetadata(doc.getMetadata());
                mongoDoc.setEmbedding(embedding);

                mongoTemplate.save(mongoDoc, COLLECTION_NAME);
            }
            log.info("Persisted {} documents to local MongoDB", documents.size());
        } catch (Exception e) {
            log.error("CRITICAL ERROR in LocalMongoVectorStore.add()", e);
            throw new RuntimeException("Failed to save vectors: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Boolean> delete(List<String> idList) {
        if (idList == null || idList.isEmpty())
            return Optional.of(false);
        Query query = new Query(Criteria.where("_id").in(idList));
        var result = mongoTemplate.remove(query, COLLECTION_NAME);
        log.info("Deleted {} documents from local store", result.getDeletedCount());
        return Optional.of(result.getDeletedCount() > 0);
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        String queryText = request.getQuery();
        int topK = request.getTopK();
        double threshold = request.getSimilarityThreshold();

        // 1. Generate query embedding
        List<Double> queryEmbedding = embeddingModel.embed(queryText);

        // 2. Fetch ALL candidates (Mapping to MongoDocument)
        List<MongoDocument> allDocs = mongoTemplate.findAll(MongoDocument.class, COLLECTION_NAME);
        log.error("DEBUG DB DUMP: Total Docs Found: {}", allDocs.size());

        // 3. Score and Map back to Spring AI Document
        return allDocs.stream()
                .filter(md -> {
                    try {
                        if (request.getFilterExpression() != null) {
                            String filter = request.getFilterExpression().toString();
                            // Handle Spring AI Expression string format:
                            // Expression[type=EQ, left=Key[key=dept], right=Value[value=DEFENSE]]
                            if (filter.contains("key=dept") && filter.contains("value=")) {
                                String[] parts = filter.split("value=");
                                if (parts.length > 1) {
                                    String targetDept = parts[1].split("]")[0];
                                    Object docDept = md.getMetadata().get("dept");
                                    return targetDept.equals(docDept != null ? docDept.toString() : null);
                                }
                            }
                        }
                    } catch (Exception e) {
                        // Fallback: include all if parsing fails
                        return true;
                    }
                    return true;
                })
                .map(md -> {
                    Document doc = new Document(md.getId(), md.getContent(), md.getMetadata());
                    // We don't necessarily need to set embedding back on the result doc for
                    // display,
                    // but it's good practice? Actually Document constructor doesn't take embedding.
                    // But we calculate score using md.embedding.
                    return new ScoredDocument(doc, calculateCosineSimilarity(queryEmbedding, md.getEmbedding()));
                })
                .filter(scored -> scored.score >= threshold)
                .sorted((a, b) -> Double.compare(b.score, a.score))
                .limit(topK)
                .map(ScoredDocument::getDocument)
                .collect(Collectors.toList());
    }

    private double calculateCosineSimilarity(List<Double> v1, List<Double> v2) {
        if (v1 == null || v2 == null || v1.isEmpty() || v2.isEmpty() || v1.size() != v2.size())
            return 0.0;
        double dotProduct = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < v1.size(); i++) {
            dotProduct += v1.get(i) * v2.get(i);
            normA += Math.pow(v1.get(i), 2);
            normB += Math.pow(v2.get(i), 2);
        }
        return (normA == 0 || normB == 0) ? 0.0 : dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private record ScoredDocument(Document document, double score) {
        public Document getDocument() {
            return document;
        }
    }

    /**
     * Inner wrapper class to handle MongoDB serialization/deserialization requires
     * No-Arg Constructor.
     */
    public static class MongoDocument {
        @Id
        private String id;
        private String content;
        private Map<String, Object> metadata;
        private List<Double> embedding;

        public MongoDocument() {
        } // No-Arg Constructor

        // Getters and Setters
        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }

        public void setMetadata(Map<String, Object> metadata) {
            this.metadata = metadata;
        }

        public List<Double> getEmbedding() {
            return embedding;
        }

        public void setEmbedding(List<Double> embedding) {
            this.embedding = embedding;
        }
    }
}
