package com.jreinhal.mercenary.model;

import org.springframework.ai.document.Document;
import java.util.List;
import java.util.Map;

public class MemoryPoint {
    private String id;
    private String description;
    private List<String> entities;
    private List<Double> embedding; // The RAGPart robust vector
    private Map<String, Object> metadata;

    public MemoryPoint(String description, List<String> entities, List<Double> embedding, Map<String, Object> metadata) {
        this.description = description;
        this.entities = entities;
        this.embedding = embedding;
        this.metadata = metadata;
        this.id = java.util.UUID.randomUUID().toString();
    }

    public Document toDocument() {
        // FIX: Use constructor (id, content, metadata) then setEmbedding manually
        Document doc = new Document(id, description, metadata);
        if (embedding != null) {
            // Spring AI uses List<Double> for embeddings
            doc.setEmbedding(embedding);
        }
        return doc;
    }

    public String getDescription() { return description; }
    public List<String> getEntities() { return entities; }
    public List<Double> getEmbedding() { return embedding; }
}