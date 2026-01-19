/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.jreinhal.mercenary.rag.hgmem.EntityExtractor
 *  com.jreinhal.mercenary.rag.hgmem.EntityExtractor$Entity
 *  com.jreinhal.mercenary.rag.hgmem.HyperGraphMemory
 *  com.jreinhal.mercenary.rag.hgmem.HyperGraphMemory$HGEdge
 *  com.jreinhal.mercenary.rag.hgmem.HyperGraphMemory$HGNode
 *  com.jreinhal.mercenary.rag.hgmem.HyperGraphMemory$HGNode$NodeType
 *  com.jreinhal.mercenary.rag.hgmem.HyperGraphMemory$HGQueryResult
 *  com.jreinhal.mercenary.rag.hgmem.HyperGraphMemory$HGStats
 *  jakarta.annotation.PostConstruct
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.ai.document.Document
 *  org.springframework.beans.factory.annotation.Value
 *  org.springframework.data.mongodb.core.MongoTemplate
 *  org.springframework.data.mongodb.core.query.Criteria
 *  org.springframework.data.mongodb.core.query.CriteriaDefinition
 *  org.springframework.data.mongodb.core.query.Query
 *  org.springframework.stereotype.Component
 */
package com.jreinhal.mercenary.rag.hgmem;

import com.jreinhal.mercenary.rag.hgmem.EntityExtractor;
import com.jreinhal.mercenary.rag.hgmem.HyperGraphMemory;
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

/*
 * Exception performing whole class analysis ignored.
 */
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
        log.debug("HGMem: Indexing document for department {}", (Object)department);
        List entities = this.entityExtractor.extract(document.getContent());
        log.debug("HGMem: Extracted {} entities", (Object)entities.size());
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
        log.info("HGMem: Indexed document with {} nodes and 1 hyperedge", (Object)nodeIds.size());
    }

    public HGQueryResult query(String query, String department, int hops) {
        if (!this.enabled) {
            return HGQueryResult.empty();
        }
        long startTime = System.currentTimeMillis();
        log.debug("HGMem: Querying hypergraph for: {}", (Object)query);
        List queryEntities = this.entityExtractor.extract(query);
        log.debug("HGMem: Query contains {} entities", (Object)queryEntities.size());
        if (queryEntities.isEmpty()) {
            return HGQueryResult.empty();
        }
        HashSet visitedNodes = new HashSet();
        LinkedHashSet relevantChunkIds = new LinkedHashSet();
        HashMap entityScores = new HashMap();
        for (EntityExtractor.Entity entity : queryEntities) {
            List matchingNodes = this.findNodesByEntity(entity, department);
            for (HGNode node : matchingNodes) {
                this.traverseGraph(node.getId(), hops, visitedNodes, relevantChunkIds, entityScores, 1.0);
            }
        }
        List<HGNode> relatedChunks = relevantChunkIds.stream().map(arg_0 -> this.getNode(arg_0)).filter(Objects::nonNull).filter(n -> n.getType() == HGNode.NodeType.CHUNK).limit(this.maxMemoryPoints).toList();
        long duration = System.currentTimeMillis() - startTime;
        log.info("HGMem: Query completed in {}ms, found {} related chunks", (Object)duration, (Object)relatedChunks.size());
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
        List edges = this.findEdgesContaining(nodeId);
        for (HGEdge edge : edges) {
            double edgeWeight = edge.getWeight();
            for (String connectedId : edge.getNodeIds()) {
                if (connectedId.equals(nodeId)) continue;
                this.traverseGraph(connectedId, remainingHops - 1, visited, chunkIds, scores, currentScore * edgeWeight * 0.8);
            }
        }
    }

    private HGNode findOrCreateNode(EntityExtractor.Entity entity, String department) {
        Query query = new Query((CriteriaDefinition)Criteria.where((String)"type").is((Object)HGNode.NodeType.ENTITY.name()).and("value").is((Object)entity.value()).and("entityType").is((Object)entity.type().name()).and("department").is((Object)department));
        HGNode existing = (HGNode)this.mongoTemplate.findOne(query, HGNode.class, "hypergraph_nodes");
        if (existing != null) {
            existing.incrementReferences();
            this.mongoTemplate.save((Object)existing, "hypergraph_nodes");
            return existing;
        }
        HGNode node = new HGNode(UUID.randomUUID().toString(), HGNode.NodeType.ENTITY, entity.value(), entity.type(), department, null, Instant.now());
        this.mongoTemplate.save((Object)node, "hypergraph_nodes");
        return node;
    }

    private HGNode createChunkNode(Document document, String department) {
        String chunkId = UUID.randomUUID().toString();
        String content = document.getContent();
        Object source = document.getMetadata().get("source");
        HGNode node = new HGNode(chunkId, HGNode.NodeType.CHUNK, content.length() > 200 ? content.substring(0, 200) : content, null, department, source != null ? source.toString() : null, Instant.now());
        this.mongoTemplate.save((Object)node, "hypergraph_nodes");
        return node;
    }

    private void createHyperedge(List<String> nodeIds, String relation, Object source) {
        HGEdge edge = new HGEdge(UUID.randomUUID().toString(), new ArrayList<String>(nodeIds), relation, 1.0, source != null ? source.toString() : null, Instant.now());
        this.mongoTemplate.save((Object)edge, "hypergraph_edges");
    }

    private List<HGNode> findNodesByEntity(EntityExtractor.Entity entity, String department) {
        Query exactQuery = new Query((CriteriaDefinition)Criteria.where((String)"type").is((Object)HGNode.NodeType.ENTITY.name()).and("value").regex("^" + this.escapeRegex(entity.value()) + "$", "i").and("department").is((Object)department));
        List results = this.mongoTemplate.find(exactQuery, HGNode.class, "hypergraph_nodes");
        if (results.isEmpty()) {
            Query partialQuery = new Query((CriteriaDefinition)Criteria.where((String)"type").is((Object)HGNode.NodeType.ENTITY.name()).and("value").regex(this.escapeRegex(entity.value()), "i").and("department").is((Object)department));
            results = this.mongoTemplate.find(partialQuery, HGNode.class, "hypergraph_nodes");
        }
        return results;
    }

    private List<HGEdge> findEdgesContaining(String nodeId) {
        Query query = new Query((CriteriaDefinition)Criteria.where((String)"nodeIds").is((Object)nodeId));
        return this.mongoTemplate.find(query, HGEdge.class, "hypergraph_edges");
    }

    private HGNode getNode(String nodeId) {
        return (HGNode)this.mongoTemplate.findById((Object)nodeId, HGNode.class, "hypergraph_nodes");
    }

    private String escapeRegex(String text) {
        return text.replaceAll("([\\\\\\^\\$\\.\\|\\?\\*\\+\\(\\)\\[\\]\\{\\}])", "\\\\$1");
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public HGStats getStats() {
        long nodeCount = this.mongoTemplate.count(new Query(), "hypergraph_nodes");
        long edgeCount = this.mongoTemplate.count(new Query(), "hypergraph_edges");
        long entityCount = this.mongoTemplate.count(new Query((CriteriaDefinition)Criteria.where((String)"type").is((Object)HGNode.NodeType.ENTITY.name())), "hypergraph_nodes");
        long chunkCount = this.mongoTemplate.count(new Query((CriteriaDefinition)Criteria.where((String)"type").is((Object)HGNode.NodeType.CHUNK.name())), "hypergraph_nodes");
        return new HGStats(nodeCount, edgeCount, entityCount, chunkCount);
    }
}

