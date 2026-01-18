package com.jreinhal.mercenary.rag.hgmem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * HGMem: Hypergraph Memory for Multi-Step RAG
 * Based on research paper arXiv:2512.23959v2
 *
 * Traditional RAG treats documents as isolated units. HGMem builds a
 * hypergraph structure where:
 * - NODES are entities (people, places, concepts) and document chunks
 * - HYPEREDGES connect multiple nodes that co-occur or relate
 *
 * This enables:
 * 1. Multi-hop reasoning (A relates to B, B relates to C)
 * 2. Complex relational queries across documents
 * 3. Entity-centric retrieval (find all docs mentioning entity X)
 * 4. Semantic clustering of related content
 *
 * The hypergraph is stored in MongoDB collections for persistence.
 */
@Component
public class HyperGraphMemory {

    private static final Logger log = LoggerFactory.getLogger(HyperGraphMemory.class);

    private static final String NODES_COLLECTION = "hypergraph_nodes";
    private static final String EDGES_COLLECTION = "hypergraph_edges";

    private final MongoTemplate mongoTemplate;
    private final EntityExtractor entityExtractor;

    @Value("${sentinel.hgmem.enabled:true}")
    private boolean enabled;

    @Value("${sentinel.hgmem.max-memory-points:50}")
    private int maxMemoryPoints;

    @Value("${sentinel.hgmem.merge-similarity-threshold:0.7}")
    private double mergeSimilarityThreshold;

    @Value("${sentinel.hgmem.max-hops:3}")
    private int maxHops;

    public HyperGraphMemory(MongoTemplate mongoTemplate, EntityExtractor entityExtractor) {
        this.mongoTemplate = mongoTemplate;
        this.entityExtractor = entityExtractor;
    }

    @PostConstruct
    public void init() {
        log.info("HGMem initialized (enabled={}, maxPoints={}, maxHops={})",
                enabled, maxMemoryPoints, maxHops);
    }

    /**
     * Index a document into the hypergraph.
     *
     * @param document The document to index
     * @param department Department context
     */
    public void indexDocument(Document document, String department) {
        if (!enabled) {
            return;
        }

        log.debug("HGMem: Indexing document for department {}", department);

        // Extract entities from document
        List<EntityExtractor.Entity> entities = entityExtractor.extract(document.getContent());
        log.debug("HGMem: Extracted {} entities", entities.size());

        // Create/update nodes for each entity
        List<String> nodeIds = new ArrayList<>();
        for (EntityExtractor.Entity entity : entities) {
            HGNode node = findOrCreateNode(entity, department);
            nodeIds.add(node.getId());
        }

        // Create chunk node for the document itself
        HGNode chunkNode = createChunkNode(document, department);
        nodeIds.add(chunkNode.getId());

        // Create hyperedge connecting all entities that co-occur in this document
        if (nodeIds.size() > 1) {
            createHyperedge(nodeIds, "co_occurrence", document.getMetadata().get("source"));
        }

        log.info("HGMem: Indexed document with {} nodes and 1 hyperedge", nodeIds.size());
    }

    /**
     * Query the hypergraph for related content.
     *
     * @param query The user's query
     * @param department Department filter
     * @param hops Number of hops to traverse
     * @return Related documents from the hypergraph
     */
    public HGQueryResult query(String query, String department, int hops) {
        if (!enabled) {
            return HGQueryResult.empty();
        }

        long startTime = System.currentTimeMillis();
        log.debug("HGMem: Querying hypergraph for: {}", query);

        // Extract entities from query
        List<EntityExtractor.Entity> queryEntities = entityExtractor.extract(query);
        log.debug("HGMem: Query contains {} entities", queryEntities.size());

        if (queryEntities.isEmpty()) {
            return HGQueryResult.empty();
        }

        // Find matching nodes
        Set<String> visitedNodes = new HashSet<>();
        Set<String> relevantChunkIds = new LinkedHashSet<>();
        Map<String, Double> entityScores = new HashMap<>();

        for (EntityExtractor.Entity entity : queryEntities) {
            List<HGNode> matchingNodes = findNodesByEntity(entity, department);

            for (HGNode node : matchingNodes) {
                // Traverse graph from this node
                traverseGraph(node.getId(), hops, visitedNodes, relevantChunkIds, entityScores, 1.0);
            }
        }

        // Collect related documents
        List<HGNode> relatedChunks = relevantChunkIds.stream()
                .map(this::getNode)
                .filter(Objects::nonNull)
                .filter(n -> n.getType() == HGNode.NodeType.CHUNK)
                .limit(maxMemoryPoints)
                .toList();

        long duration = System.currentTimeMillis() - startTime;
        log.info("HGMem: Query completed in {}ms, found {} related chunks", duration, relatedChunks.size());

        return new HGQueryResult(
                relatedChunks,
                queryEntities.stream().map(EntityExtractor.Entity::value).toList(),
                entityScores,
                visitedNodes.size(),
                duration
        );
    }

    /**
     * Traverse graph from a starting node.
     */
    private void traverseGraph(String nodeId, int remainingHops, Set<String> visited,
                               Set<String> chunkIds, Map<String, Double> scores, double currentScore) {
        if (remainingHops < 0 || visited.contains(nodeId)) {
            return;
        }

        visited.add(nodeId);

        HGNode node = getNode(nodeId);
        if (node == null) {
            return;
        }

        // If it's a chunk, add to results
        if (node.getType() == HGNode.NodeType.CHUNK) {
            chunkIds.add(nodeId);
        }

        // Update entity score
        if (node.getType() == HGNode.NodeType.ENTITY) {
            scores.merge(node.getValue(), currentScore, Math::max);
        }

        // Find connected nodes via hyperedges
        List<HGEdge> edges = findEdgesContaining(nodeId);
        for (HGEdge edge : edges) {
            double edgeWeight = edge.getWeight();
            for (String connectedId : edge.getNodeIds()) {
                if (!connectedId.equals(nodeId)) {
                    traverseGraph(connectedId, remainingHops - 1, visited, chunkIds, scores,
                            currentScore * edgeWeight * 0.8); // Decay factor per hop
                }
            }
        }
    }

    /**
     * Find or create a node for an entity.
     */
    private HGNode findOrCreateNode(EntityExtractor.Entity entity, String department) {
        Query query = new Query(Criteria.where("type").is(HGNode.NodeType.ENTITY.name())
                .and("value").is(entity.value())
                .and("entityType").is(entity.type().name())
                .and("department").is(department));

        HGNode existing = mongoTemplate.findOne(query, HGNode.class, NODES_COLLECTION);
        if (existing != null) {
            // Update reference count
            existing.incrementReferences();
            mongoTemplate.save(existing, NODES_COLLECTION);
            return existing;
        }

        // Create new node
        HGNode node = new HGNode(
                UUID.randomUUID().toString(),
                HGNode.NodeType.ENTITY,
                entity.value(),
                entity.type(),
                department,
                null,
                Instant.now()
        );
        mongoTemplate.save(node, NODES_COLLECTION);
        return node;
    }

    /**
     * Create a chunk node for a document.
     */
    private HGNode createChunkNode(Document document, String department) {
        String chunkId = UUID.randomUUID().toString();
        String content = document.getContent();
        Object source = document.getMetadata().get("source");

        HGNode node = new HGNode(
                chunkId,
                HGNode.NodeType.CHUNK,
                content.length() > 200 ? content.substring(0, 200) : content,
                null,
                department,
                source != null ? source.toString() : null,
                Instant.now()
        );
        mongoTemplate.save(node, NODES_COLLECTION);
        return node;
    }

    /**
     * Create a hyperedge connecting nodes.
     */
    private void createHyperedge(List<String> nodeIds, String relation, Object source) {
        HGEdge edge = new HGEdge(
                UUID.randomUUID().toString(),
                new ArrayList<>(nodeIds),
                relation,
                1.0,
                source != null ? source.toString() : null,
                Instant.now()
        );
        mongoTemplate.save(edge, EDGES_COLLECTION);
    }

    /**
     * Find nodes matching an entity.
     */
    private List<HGNode> findNodesByEntity(EntityExtractor.Entity entity, String department) {
        // Exact match
        Query exactQuery = new Query(Criteria.where("type").is(HGNode.NodeType.ENTITY.name())
                .and("value").regex("^" + escapeRegex(entity.value()) + "$", "i")
                .and("department").is(department));

        List<HGNode> results = mongoTemplate.find(exactQuery, HGNode.class, NODES_COLLECTION);

        // If no exact match, try partial
        if (results.isEmpty()) {
            Query partialQuery = new Query(Criteria.where("type").is(HGNode.NodeType.ENTITY.name())
                    .and("value").regex(escapeRegex(entity.value()), "i")
                    .and("department").is(department));
            results = mongoTemplate.find(partialQuery, HGNode.class, NODES_COLLECTION);
        }

        return results;
    }

    /**
     * Find edges containing a node.
     */
    private List<HGEdge> findEdgesContaining(String nodeId) {
        Query query = new Query(Criteria.where("nodeIds").is(nodeId));
        return mongoTemplate.find(query, HGEdge.class, EDGES_COLLECTION);
    }

    /**
     * Get a node by ID.
     */
    private HGNode getNode(String nodeId) {
        return mongoTemplate.findById(nodeId, HGNode.class, NODES_COLLECTION);
    }

    /**
     * Escape special regex characters.
     */
    private String escapeRegex(String text) {
        return text.replaceAll("([\\\\\\^\\$\\.\\|\\?\\*\\+\\(\\)\\[\\]\\{\\}])", "\\\\$1");
    }

    /**
     * Check if HGMem is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Get statistics about the hypergraph.
     */
    public HGStats getStats() {
        long nodeCount = mongoTemplate.count(new Query(), NODES_COLLECTION);
        long edgeCount = mongoTemplate.count(new Query(), EDGES_COLLECTION);
        long entityCount = mongoTemplate.count(
                new Query(Criteria.where("type").is(HGNode.NodeType.ENTITY.name())), NODES_COLLECTION);
        long chunkCount = mongoTemplate.count(
                new Query(Criteria.where("type").is(HGNode.NodeType.CHUNK.name())), NODES_COLLECTION);

        return new HGStats(nodeCount, edgeCount, entityCount, chunkCount);
    }

    /**
     * Hypergraph statistics.
     */
    public record HGStats(long totalNodes, long totalEdges, long entityNodes, long chunkNodes) {}

    /**
     * Query result from hypergraph.
     */
    public record HGQueryResult(
            List<HGNode> relatedChunks,
            List<String> matchedEntities,
            Map<String, Double> entityScores,
            int nodesTraversed,
            long queryTimeMs
    ) {
        public static HGQueryResult empty() {
            return new HGQueryResult(List.of(), List.of(), Map.of(), 0, 0);
        }

        public boolean isEmpty() {
            return relatedChunks.isEmpty();
        }
    }

    /**
     * Hypergraph node.
     */
    public static class HGNode {
        public enum NodeType { ENTITY, CHUNK }

        private String id;
        private NodeType type;
        private String value;
        private EntityExtractor.EntityType entityType;
        private String department;
        private String sourceDoc;
        private Instant createdAt;
        private int referenceCount = 1;

        public HGNode() {}

        public HGNode(String id, NodeType type, String value, EntityExtractor.EntityType entityType,
                     String department, String sourceDoc, Instant createdAt) {
            this.id = id;
            this.type = type;
            this.value = value;
            this.entityType = entityType;
            this.department = department;
            this.sourceDoc = sourceDoc;
            this.createdAt = createdAt;
        }

        public String getId() { return id; }
        public NodeType getType() { return type; }
        public String getValue() { return value; }
        public EntityExtractor.EntityType getEntityType() { return entityType; }
        public String getDepartment() { return department; }
        public String getSourceDoc() { return sourceDoc; }
        public Instant getCreatedAt() { return createdAt; }
        public int getReferenceCount() { return referenceCount; }

        public void incrementReferences() { this.referenceCount++; }
    }

    /**
     * Hyperedge connecting multiple nodes.
     */
    public static class HGEdge {
        private String id;
        private List<String> nodeIds;
        private String relation;
        private double weight;
        private String sourceDoc;
        private Instant createdAt;

        public HGEdge() {}

        public HGEdge(String id, List<String> nodeIds, String relation, double weight,
                     String sourceDoc, Instant createdAt) {
            this.id = id;
            this.nodeIds = nodeIds;
            this.relation = relation;
            this.weight = weight;
            this.sourceDoc = sourceDoc;
            this.createdAt = createdAt;
        }

        public String getId() { return id; }
        public List<String> getNodeIds() { return nodeIds; }
        public String getRelation() { return relation; }
        public double getWeight() { return weight; }
        public String getSourceDoc() { return sourceDoc; }
        public Instant getCreatedAt() { return createdAt; }
    }
}
