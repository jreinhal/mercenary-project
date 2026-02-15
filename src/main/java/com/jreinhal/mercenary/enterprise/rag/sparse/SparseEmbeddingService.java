package com.jreinhal.mercenary.enterprise.rag.sparse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Orchestration layer for sparse embedding operations.
 *
 * Handles two workflows:
 * 1. Ingestion-time: compute sparse weights for documents and persist to MongoDB
 * 2. Query-time: compute sparse weights for a query string
 *
 * Graceful degradation: all operations return empty/no-op when the sidecar is
 * unavailable, ensuring the application functions without the optional sidecar.
 */
@Service
public class SparseEmbeddingService {
    private static final Logger log = LoggerFactory.getLogger(SparseEmbeddingService.class);
    private static final String COLLECTION_NAME = "vector_store";

    private final SparseEmbeddingClient client;
    private final MongoTemplate mongoTemplate;

    @Value("${sentinel.sparse-embedding.enabled:false}")
    private boolean enabled;

    @Value("${sentinel.sparse-embedding.batch-size:64}")
    private int batchSize;

    public SparseEmbeddingService(SparseEmbeddingClient client, MongoTemplate mongoTemplate) {
        this.client = client;
        this.mongoTemplate = mongoTemplate;
    }

    public boolean isEnabled() {
        return enabled && client.isEnabled();
    }

    /**
     * Compute sparse weights for documents and store them in MongoDB.
     * Called during the ingestion pipeline after dense vectors are persisted.
     *
     * Non-blocking: failures are logged but do not propagate exceptions.
     *
     * @param documents the documents to compute sparse weights for
     */
    public void computeAndStoreSparseWeights(List<Document> documents) {
        if (!isEnabled() || documents == null || documents.isEmpty()) {
            return;
        }
        try {
            long start = System.currentTimeMillis();
            int totalStored = 0;

            // Process in batches
            for (int i = 0; i < documents.size(); i += batchSize) {
                int end = Math.min(i + batchSize, documents.size());
                List<Document> batch = documents.subList(i, end);

                List<String> texts = new ArrayList<>();
                List<String> docIds = new ArrayList<>();
                for (Document doc : batch) {
                    texts.add(doc.getContent());
                    docIds.add(doc.getId());
                }

                List<Map<String, Float>> sparseWeights = client.embedSparse(texts);
                if (sparseWeights.isEmpty()) {
                    log.warn("Sparse embedding sidecar returned empty results for batch starting at index {}", i);
                    continue;
                }

                for (int j = 0; j < sparseWeights.size() && j < docIds.size(); j++) {
                    Map<String, Float> weights = sparseWeights.get(j);
                    if (weights.isEmpty()) {
                        continue;
                    }
                    Query query = Query.query(Criteria.where("_id").is(docIds.get(j)));
                    Update update = new Update().set("sparseWeights", weights);
                    mongoTemplate.updateFirst(query, update, COLLECTION_NAME);
                    totalStored++;
                }
            }

            long elapsed = System.currentTimeMillis() - start;
            log.info("Computed and stored sparse weights for {}/{} documents in {}ms",
                    totalStored, documents.size(), elapsed);
        } catch (Exception e) {
            log.error("Failed to compute sparse weights (non-fatal): {}", e.getMessage());
        }
    }

    /**
     * Compute sparse weights for a single query string.
     * Called at query time for sparse retrieval.
     *
     * @param query the query text
     * @return sparse weight map (token -> weight), or empty map on failure
     */
    public Map<String, Float> embedQuery(String query) {
        if (!isEnabled() || query == null || query.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            List<Map<String, Float>> results = client.embedSparse(List.of(query));
            if (results.isEmpty()) {
                return Collections.emptyMap();
            }
            return results.get(0);
        } catch (Exception e) {
            log.error("Failed to compute sparse query embedding (non-fatal): {}", e.getMessage());
            return Collections.emptyMap();
        }
    }
}
