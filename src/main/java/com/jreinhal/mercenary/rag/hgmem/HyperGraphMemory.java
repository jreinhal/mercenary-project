package com.jreinhal.mercenary.rag.hgmem;

import com.jreinhal.mercenary.rag.hgmem.EntityExtractor;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.CriteriaDefinition;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

@Component
public class HyperGraphMemory {
    private static final Logger log = LoggerFactory.getLogger(HyperGraphMemory.class);
    private static final String NODES_COLLECTION = "hypergraph_nodes";
    private static final String EDGES_COLLECTION = "hypergraph_edges";
    private final MongoTemplate mongoTemplate;
    private final EntityExtractor entityExtractor;
    @Value(value="${sentinel.hgmem.enabled:true}")
    private boolean enabled;
    @Value(value="${sentinel.hgmem.max-memory-points:50}")
    private int maxMemoryPoints;
    @Value(value="${sentinel.hgmem.merge-similarity-threshold:0.7}")
    private double mergeSimilarityThreshold;
    @Value(value="${sentinel.hgmem.max-hops:3}")
    private int maxHops;

    public HyperGraphMemory(MongoTemplate mongoTemplate, EntityExtractor entityExtractor) {
        this.mongoTemplate = mongoTemplate;
        this.entityExtractor = entityExtractor;
    }

    @PostConstruct
    public void init() {
        log.info("HGMem initialized (enabled={}, maxPoints={}, maxHops={})", new Object[]{this.enabled, this.maxMemoryPoints, this.maxHops});
    }

    public void indexDocument(Document document, String department) {
        if (!this.enabled) {
            return;
        }
        log.debug("HGMem: Indexing document for department {}", department);
        List<EntityExtractor.Entity> entities = this.entityExtractor.extract(document.getContent());
        log.debug("HGMem: Extracted {} entities", entities.size());
        ArrayList<String> nodeIds = new ArrayList<String>();
        for (EntityExtractor.Entity entity : entities) {
            HGNode node = this.findOrCreateNode(entity, department);
            nodeIds.add(node.getId());
        }
        HGNode chunkNode = this.createChunkNode(document, department);
        nodeIds.add(chunkNode.getId());
        if (nodeIds.size() > 1) {
            this.createHyperedge(nodeIds, "co_occurrence", document.getMetadata().get("source"));
        }
        log.info("HGMem: Indexed document with {} nodes and 1 hyperedge", nodeIds.size());
    }

    public HGQueryResult query(String query, String department, int hops) {
        if (!this.enabled) {
            return HGQueryResult.empty();
        }
        long startTime = System.currentTimeMillis();
        log.debug("HGMem: Querying hypergraph for: {}", query);
        List<EntityExtractor.Entity> queryEntities = this.entityExtractor.extract(query);
        log.debug("HGMem: Query contains {} entities", queryEntities.size());
        if (queryEntities.isEmpty()) {
            return HGQueryResult.empty();
        }
        HashSet<String> visitedNodes = new HashSet<String>();
        LinkedHashSet<String> relevantChunkIds = new LinkedHashSet<String>();
        HashMap<String, Double> entityScores = new HashMap<String, Double>();
        for (EntityExtractor.Entity entity : queryEntities) {
            List<HGNode> matchingNodes = this.findNodesByEntity(entity, department);
            for (HGNode node : matchingNodes) {
                this.traverseGraph(node.getId(), hops, visitedNodes, relevantChunkIds, entityScores, 1.0);
            }
        }
        List<HGNode> relatedChunks = relevantChunkIds.stream().map(this::getNode).filter(Objects::nonNull).filter(n -> n.getType() == HGNode.NodeType.CHUNK).limit(this.maxMemoryPoints).toList();
        long duration = System.currentTimeMillis() - startTime;
        log.info("HGMem: Query completed in {}ms, found {} related chunks", duration, relatedChunks.size());
        return new HGQueryResult(relatedChunks, queryEntities.stream().map(EntityExtractor.Entity::value).toList(), entityScores, visitedNodes.size(), duration);
    }

    private void traverseGraph(String nodeId, int remainingHops, Set<String> visited, Set<String> chunkIds, Map<String, Double> scores, double currentScore) {
        if (remainingHops < 0 || visited.contains(nodeId)) {
            return;
        }
        visited.add(nodeId);
        HGNode node = this.getNode(nodeId);
        if (node == null) {
            return;
        }
        if (node.getType() == HGNode.NodeType.CHUNK) {
            chunkIds.add(nodeId);
        }
        if (node.getType() == HGNode.NodeType.ENTITY) {
            scores.merge(node.getValue(), currentScore, Math::max);
        }
        List<HGEdge> edges = this.findEdgesContaining(nodeId);
        for (HGEdge edge : edges) {
            double edgeWeight = edge.getWeight();
            for (String connectedId : edge.getNodeIds()) {
                if (connectedId.equals(nodeId)) continue;
                this.traverseGraph(connectedId, remainingHops - 1, visited, chunkIds, scores, currentScore * edgeWeight * 0.8);
            }
        }
    }

    private HGNode findOrCreateNode(EntityExtractor.Entity entity, String department) {
        Query query = new Query((CriteriaDefinition)Criteria.where((String)"type").is(HGNode.NodeType.ENTITY.name()).and("value").is(entity.value()).and("entityType").is(entity.type().name()).and("department").is(department));
        HGNode existing = (HGNode)this.mongoTemplate.findOne(query, HGNode.class, NODES_COLLECTION);
        if (existing != null) {
            existing.incrementReferences();
            this.mongoTemplate.save(existing, NODES_COLLECTION);
            return existing;
        }
        HGNode node = new HGNode(UUID.randomUUID().toString(), HGNode.NodeType.ENTITY, entity.value(), entity.type(), department, null, Instant.now());
        this.mongoTemplate.save(node, NODES_COLLECTION);
        return node;
    }

    private HGNode createChunkNode(Document document, String department) {
        String chunkId = UUID.randomUUID().toString();
        String content = document.getContent();
        Object source = document.getMetadata().get("source");
        HGNode node = new HGNode(chunkId, HGNode.NodeType.CHUNK, content.length() > 200 ? content.substring(0, 200) : content, null, department, source != null ? source.toString() : null, Instant.now());
        this.mongoTemplate.save(node, NODES_COLLECTION);
        return node;
    }

    private void createHyperedge(List<String> nodeIds, String relation, Object source) {
        HGEdge edge = new HGEdge(UUID.randomUUID().toString(), new ArrayList<String>(nodeIds), relation, 1.0, source != null ? source.toString() : null, Instant.now());
        this.mongoTemplate.save(edge, EDGES_COLLECTION);
    }

    private List<HGNode> findNodesByEntity(EntityExtractor.Entity entity, String department) {
        Query exactQuery = new Query((CriteriaDefinition)Criteria.where((String)"type").is(HGNode.NodeType.ENTITY.name()).and("value").regex("^" + this.escapeRegex(entity.value()) + "$", "i").and("department").is(department));
        List results = this.mongoTemplate.find(exactQuery, HGNode.class, NODES_COLLECTION);
        if (results.isEmpty()) {
            Query partialQuery = new Query((CriteriaDefinition)Criteria.where((String)"type").is(HGNode.NodeType.ENTITY.name()).and("value").regex(this.escapeRegex(entity.value()), "i").and("department").is(department));
            results = this.mongoTemplate.find(partialQuery, HGNode.class, NODES_COLLECTION);
        }
        return results;
    }

    private List<HGEdge> findEdgesContaining(String nodeId) {
        Query query = new Query((CriteriaDefinition)Criteria.where((String)"nodeIds").is(nodeId));
        return this.mongoTemplate.find(query, HGEdge.class, EDGES_COLLECTION);
    }

    private HGNode getNode(String nodeId) {
        return (HGNode)this.mongoTemplate.findById(nodeId, HGNode.class, NODES_COLLECTION);
    }

    private String escapeRegex(String text) {
        return text.replaceAll("([\\\\\\^\\$\\.\\|\\?\\*\\+\\(\\)\\[\\]\\{\\}])", "\\\\$1");
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public HGStats getStats() {
        long nodeCount = this.mongoTemplate.count(new Query(), NODES_COLLECTION);
        long edgeCount = this.mongoTemplate.count(new Query(), EDGES_COLLECTION);
        long entityCount = this.mongoTemplate.count(new Query((CriteriaDefinition)Criteria.where((String)"type").is(HGNode.NodeType.ENTITY.name())), NODES_COLLECTION);
        long chunkCount = this.mongoTemplate.count(new Query((CriteriaDefinition)Criteria.where((String)"type").is(HGNode.NodeType.CHUNK.name())), NODES_COLLECTION);
        return new HGStats(nodeCount, edgeCount, entityCount, chunkCount);
    }

    public static class HGNode {
        private String id;
        private NodeType type;
        private String value;
        private EntityExtractor.EntityType entityType;
        private String department;
        private String sourceDoc;
        private Instant createdAt;
        private int referenceCount = 1;

        public HGNode() {
        }

        public HGNode(String id, NodeType type, String value, EntityExtractor.EntityType entityType, String department, String sourceDoc, Instant createdAt) {
            this.id = id;
            this.type = type;
            this.value = value;
            this.entityType = entityType;
            this.department = department;
            this.sourceDoc = sourceDoc;
            this.createdAt = createdAt;
        }

        public String getId() {
            return this.id;
        }

        public NodeType getType() {
            return this.type;
        }

        public String getValue() {
            return this.value;
        }

        public EntityExtractor.EntityType getEntityType() {
            return this.entityType;
        }

        public String getDepartment() {
            return this.department;
        }

        public String getSourceDoc() {
            return this.sourceDoc;
        }

        public Instant getCreatedAt() {
            return this.createdAt;
        }

        public int getReferenceCount() {
            return this.referenceCount;
        }

        public void incrementReferences() {
            ++this.referenceCount;
        }

        public static enum NodeType {
            ENTITY,
            CHUNK;

        }
    }

    public record HGQueryResult(List<HGNode> relatedChunks, List<String> matchedEntities, Map<String, Double> entityScores, int nodesTraversed, long queryTimeMs) {
        public static HGQueryResult empty() {
            return new HGQueryResult(List.of(), List.of(), Map.of(), 0, 0L);
        }

        public boolean isEmpty() {
            return this.relatedChunks.isEmpty();
        }
    }

    public static class HGEdge {
        private String id;
        private List<String> nodeIds;
        private String relation;
        private double weight;
        private String sourceDoc;
        private Instant createdAt;

        public HGEdge() {
        }

        public HGEdge(String id, List<String> nodeIds, String relation, double weight, String sourceDoc, Instant createdAt) {
            this.id = id;
            this.nodeIds = nodeIds;
            this.relation = relation;
            this.weight = weight;
            this.sourceDoc = sourceDoc;
            this.createdAt = createdAt;
        }

        public String getId() {
            return this.id;
        }

        public List<String> getNodeIds() {
            return this.nodeIds;
        }

        public String getRelation() {
            return this.relation;
        }

        public double getWeight() {
            return this.weight;
        }

        public String getSourceDoc() {
            return this.sourceDoc;
        }

        public Instant getCreatedAt() {
            return this.createdAt;
        }
    }

    public record HGStats(long totalNodes, long totalEdges, long entityNodes, long chunkNodes) {
    }
}
