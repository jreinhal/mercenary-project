package com.jreinhal.mercenary.vector;

import com.mongodb.client.result.DeleteResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.CriteriaDefinition;
import org.springframework.data.mongodb.core.query.Query;

public class LocalMongoVectorStore
implements VectorStore {
    private static final Logger log = LoggerFactory.getLogger(LocalMongoVectorStore.class);
    private static final String COLLECTION_NAME = "vector_store";
    private static final Set<String> PREFILTER_KEYS = Set.of("dept", "workspaceId", "type", "partition_id", "source", "filename", "mimeType");
    private final MongoTemplate mongoTemplate;
    private final EmbeddingModel embeddingModel;

    public LocalMongoVectorStore(MongoTemplate mongoTemplate, EmbeddingModel embeddingModel) {
        this.mongoTemplate = mongoTemplate;
        this.embeddingModel = embeddingModel;
        log.info("Initialized LocalMongoVectorStore (Off-Grid Persistence Mode)");
    }

    @SuppressWarnings("deprecation")
    public void add(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        try {
            for (Document doc : documents) {
                List<Double> embedding = null;
                float[] rawEmbedding = doc.getEmbedding();
                if (rawEmbedding != null && rawEmbedding.length > 0) {
                    embedding = new java.util.ArrayList<>(rawEmbedding.length);
                    for (float f : rawEmbedding) {
                        embedding.add((double) f);
                    }
                }
                if (embedding == null || embedding.isEmpty()) {
                    // Avoid logging document identifiers in regulated deployments.
                    log.debug("Generating embedding for document (id redacted)");
                    float[] embeddingArray = this.embeddingModel.embed(doc.getContent());
                    embedding = new java.util.ArrayList<>(embeddingArray.length);
                    for (float f : embeddingArray) {
                        embedding.add((double) f);
                    }
                }
                double embeddingNorm = this.computeNorm(embedding);
                MongoDocument mongoDoc = new MongoDocument();
                mongoDoc.setId(doc.getId());
                mongoDoc.setContent(doc.getContent());
                mongoDoc.setMetadata(doc.getMetadata());
                mongoDoc.setEmbedding(embedding);
                mongoDoc.setEmbeddingNorm(embeddingNorm);
                this.mongoTemplate.save(mongoDoc, COLLECTION_NAME);
            }
            log.info("Persisted {} documents to local MongoDB", documents.size());
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
        log.info("Deleted {} documents from local store", result.getDeletedCount());
        return Optional.of(result.getDeletedCount() > 0L);
    }

    public List<Document> similaritySearch(SearchRequest request) {
        String queryText = request.getQuery();
        int topK = request.getTopK();
        double threshold = request.getSimilarityThreshold();
        Object filterExpression = request.getFilterExpression();
        float[] embeddingArray = this.embeddingModel.embed(queryText);
        double queryNorm = this.computeNorm(embeddingArray);
        Query prefilterQuery = this.buildPrefilterQuery(filterExpression);
        List<MongoDocument> allDocs = prefilterQuery != null ? this.mongoTemplate.find(prefilterQuery, MongoDocument.class, COLLECTION_NAME) : this.mongoTemplate.findAll(MongoDocument.class, COLLECTION_NAME);
        log.debug("Total documents found in vector store: {}", allDocs.size());
        FilterEvaluator evaluator = this.buildFilterEvaluator(filterExpression);
        return allDocs.stream().filter(md -> evaluator.matches(md.getMetadata())).map(md -> {
            HashMap<String, Object> metadata = md.getMetadata() != null ? new HashMap<String, Object>(md.getMetadata()) : new HashMap<>();
            Document doc = new Document(md.getId(), md.getContent(), metadata);
            return new ScoredDocument(doc, this.calculateCosineSimilarity(embeddingArray, queryNorm, md.getEmbedding(), md.getEmbeddingNorm()));
        }).filter(scored -> scored.score >= threshold).sorted((a, b) -> Double.compare(b.score, a.score)).limit(topK).map(scored -> {
            scored.document.getMetadata().put("score", scored.score);
            return scored.getDocument();
        }).collect(Collectors.toList());
    }

    private FilterEvaluator buildFilterEvaluator(Object filterExpression) {
        ParsedFilter parsed = this.parseFilterExpression(filterExpression);
        if (parsed == null || parsed.orGroups().isEmpty()) {
            return metadata -> true;
        }
        return metadata -> {
            for (List<Condition> group : parsed.orGroups()) {
                boolean match = true;
                for (Condition condition : group) {
                    if (!this.matchesCondition(metadata, condition.key(), condition.op(), condition.values())) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    return true;
                }
            }
            return false;
        };
    }

    private ParsedFilter parseFilterExpression(Object filterExpression) {
        if (filterExpression == null) {
            return null;
        }
        String filter = String.valueOf(filterExpression).trim();
        if (filter.isBlank()) {
            return null;
        }
        // Backward compatibility for Spring AI expression string formatting
        // Format: Expression[type=EQ, left=Key[key=dept], right=Value[value=ENTERPRISE]]
        if (filter.contains("Key[key=") && filter.contains("Value[value=")) {
            try {
                String key = this.extractBetween(filter, "Key[key=", "]");
                String value = this.extractBetween(filter, "Value[value=", "]");
                // Keys/values may be sensitive in regulated deployments; avoid logging raw values.
                log.debug("Parsed Spring AI Expression filter for key='{}'", key);
                return new ParsedFilter(List.of(List.of(new Condition(key, "==", List.of(this.stripQuotes(value))))));
            }
            catch (Exception e) {
                log.warn("Failed to parse Spring AI Expression: {}", filter, e);
                return null;
            }
        }
        String[] orParts = filter.split("\\s*\\|\\|\\s*");
        java.util.ArrayList<List<Condition>> groups = new java.util.ArrayList<>();
        for (String orPart : orParts) {
            String[] andParts = orPart.split("\\s*&&\\s*");
            java.util.ArrayList<Condition> conditions = new java.util.ArrayList<>();
            for (String cond : andParts) {
                Condition parsed = this.parseCondition(cond.trim());
                if (parsed != null) {
                    conditions.add(parsed);
                }
            }
            if (!conditions.isEmpty()) {
                groups.add(conditions);
            }
        }
        if (groups.isEmpty()) {
            return null;
        }
        return new ParsedFilter(groups);
    }

    private Condition parseCondition(String condition) {
        if (condition.isBlank()) {
            return null;
        }
        String normalized = condition.replaceAll("\\s+", " ").trim();
        String op = null;
        if (normalized.contains(" in ")) {
            op = "in";
        } else if (normalized.contains("!=")) {
            op = "!=";
        } else if (normalized.contains("==")) {
            op = "==";
        }
        if (op == null) {
            return null;
        }
        String[] parts;
        if (op.equals("in")) {
            parts = normalized.split("\\s+in\\s+");
        } else {
            parts = normalized.split(op.equals("==") ? "==" : "!=");
        }
        if (parts.length < 2) {
            return null;
        }
        String key = parts[0].trim();
        String rawValue = parts[1].trim();
        if (op.equals("in")) {
            List<String> values = this.parseList(rawValue);
            return new Condition(key, op, values);
        }
        return new Condition(key, op, List.of(this.stripQuotes(rawValue)));
    }

    private boolean matchesCondition(Map<String, Object> metadata, String key, String op, List<String> values) {
        Object metaValue = metadata != null ? metadata.get(key) : null;
        if (op.equals("in")) {
            if (metaValue == null) {
                return false;
            }
            return values.stream().anyMatch(v -> this.valuesEqual(metaValue, v));
        }
        if (values.isEmpty()) {
            return true;
        }
        String cleaned = values.get(0);
        if (op.equals("==")) {
            if (metaValue == null) {
                return false;
            }
            return this.valuesEqual(metaValue, cleaned);
        }
        if (op.equals("!=")) {
            if (metaValue == null) {
                return true;
            }
            return !this.valuesEqual(metaValue, cleaned);
        }
        return true;
    }

    private boolean valuesEqual(Object metaValue, String candidate) {
        if (metaValue instanceof Number) {
            Double metaNumber = ((Number) metaValue).doubleValue();
            Double candidateNumber = this.tryParseDouble(candidate);
            if (candidateNumber != null) {
                return Double.compare(metaNumber, candidateNumber) == 0;
            }
        }
        String metaString = String.valueOf(metaValue);
        return metaString.equalsIgnoreCase(candidate);
    }

    private List<String> parseList(String rawValue) {
        String trimmed = rawValue.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        if (trimmed.isBlank()) {
            return List.of();
        }
        String[] parts = trimmed.split(",");
        List<String> values = new java.util.ArrayList<>();
        for (String part : parts) {
            String cleaned = this.stripQuotes(part.trim());
            if (!cleaned.isEmpty()) {
                values.add(cleaned);
            }
        }
        return values;
    }

    private String stripQuotes(String value) {
        String trimmed = value.trim();
        if ((trimmed.startsWith("'") && trimmed.endsWith("'")) || (trimmed.startsWith("\"") && trimmed.endsWith("\""))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private Double tryParseDouble(String value) {
        try {
            return Double.parseDouble(value);
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    private String extractBetween(String text, String startToken, String endToken) {
        int start = text.indexOf(startToken);
        if (start < 0) {
            return "";
        }
        start += startToken.length();
        int end = text.indexOf(endToken, start);
        if (end < 0) {
            end = text.length();
        }
        return text.substring(start, end).trim();
    }

    private double calculateCosineSimilarity(float[] v1, double normA, List<Double> v2, Double normB) {
        if (v1 == null || v2 == null || v1.length == 0 || v2.isEmpty() || v1.length != v2.size()) {
            return 0.0;
        }
        double dotProduct = 0.0;
        double docNorm = normB != null ? normB.doubleValue() : this.computeNorm(v2);
        for (int i = 0; i < v1.length; ++i) {
            dotProduct += (double)v1[i] * v2.get(i);
        }
        if (normA == 0.0 || docNorm == 0.0) {
            return 0.0;
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(docNorm));
    }

    private double computeNorm(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (float f : embedding) {
            sum += (double)f * (double)f;
        }
        return sum;
    }

    private double computeNorm(List<Double> embedding) {
        if (embedding == null || embedding.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (Double value : embedding) {
            if (value != null) {
                sum += value.doubleValue() * value.doubleValue();
            }
        }
        return sum;
    }

    private Query buildPrefilterQuery(Object filterExpression) {
        ParsedFilter parsed = this.parseFilterExpression(filterExpression);
        if (parsed == null || parsed.orGroups().isEmpty()) {
            return null;
        }
        List<List<Condition>> groups = parsed.orGroups();
        if (groups.isEmpty()) {
            return null;
        }
        ArrayList<Criteria> orCriteria = new ArrayList<>();
        for (List<Condition> group : groups) {
            if (group.isEmpty()) {
                return null;
            }
            ArrayList<Criteria> andCriteria = new ArrayList<>();
            for (Condition condition : group) {
                String key = condition.key();
                if (!PREFILTER_KEYS.contains(key)) {
                    return null;
                }
                String field = "metadata." + key;
                if (condition.op().equals("==") && !condition.values().isEmpty()) {
                    andCriteria.add(Criteria.where(field).in(this.normalizeValuesForQuery(condition.values().get(0))));
                } else if (condition.op().equals("!=") && !condition.values().isEmpty()) {
                    andCriteria.add(Criteria.where(field).nin(this.normalizeValuesForQuery(condition.values().get(0))));
                } else if (condition.op().equals("in") && !condition.values().isEmpty()) {
                    Set<Object> normalized = new HashSet<>();
                    for (String value : condition.values()) {
                        normalized.addAll(this.normalizeValuesForQuery(value));
                    }
                    andCriteria.add(Criteria.where(field).in(normalized));
                }
            }
            if (andCriteria.isEmpty()) {
                return null;
            }
            Criteria groupCriteria = andCriteria.size() == 1 ? andCriteria.get(0) : new Criteria().andOperator(andCriteria.toArray(new Criteria[0]));
            orCriteria.add(groupCriteria);
        }
        if (orCriteria.isEmpty()) {
            return null;
        }
        Query query = new Query();
        if (orCriteria.size() == 1) {
            query.addCriteria(orCriteria.get(0));
        } else {
            query.addCriteria(new Criteria().orOperator(orCriteria.toArray(new Criteria[0])));
        }
        return query;
    }

    private List<Object> normalizeValuesForQuery(String value) {
        java.util.ArrayList<Object> variants = new java.util.ArrayList<>();
        Double numeric = this.tryParseDouble(value);
        if (numeric != null) {
            if (numeric.doubleValue() == Math.floor(numeric.doubleValue())) {
                variants.add(numeric.longValue());
            }
            variants.add(numeric);
        }
        variants.add(value);
        return variants;
    }

    public static class MongoDocument {
        @Id
        private String id;
        private String content;
        private Map<String, Object> metadata;
        private List<Double> embedding;
        private Double embeddingNorm;

        public String getId() {
            return this.id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getContent() {
            return this.content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public Map<String, Object> getMetadata() {
            return this.metadata;
        }

        public void setMetadata(Map<String, Object> metadata) {
            this.metadata = metadata;
        }

        public List<Double> getEmbedding() {
            return this.embedding;
        }

        public void setEmbedding(List<Double> embedding) {
            this.embedding = embedding;
        }

        public Double getEmbeddingNorm() {
            return this.embeddingNorm;
        }

        public void setEmbeddingNorm(Double embeddingNorm) {
            this.embeddingNorm = embeddingNorm;
        }
    }

    private record ScoredDocument(Document document, double score) {
        public Document getDocument() {
            return this.document;
        }
    }

    private record Condition(String key, String op, List<String> values) {
    }

    private record ParsedFilter(List<List<Condition>> orGroups) {
    }

    @FunctionalInterface
    private interface FilterEvaluator {
        boolean matches(Map<String, Object> metadata);
    }
}
