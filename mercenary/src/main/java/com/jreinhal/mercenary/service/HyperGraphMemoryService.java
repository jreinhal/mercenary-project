package com.jreinhal.mercenary.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * HGMem - Hypergraph-based Memory Service
 * 
 * Implementation based on: "Improving Multi-Step RAG with Hypergraph Based Memory 
 * for Long-Context Complex Relational Modeling" (arXiv:2512.23959v2)
 * 
 * Key Innovation: Memory is represented as a hypergraph where hyperedges correspond 
 * to distinct memory units, enabling progressive formation of higher-order interactions.
 * Unlike regular graphs where edges connect 2 nodes, hyperedges can connect N nodes,
 * enabling n-ary relationship modeling.
 * 
 * Operations:
 * - UPDATE: Revise descriptions of existing memory points
 * - INSERT: Add new memory points (hyperedges)
 * - MERGE: Combine related memory points into higher-order correlations
 * 
 * @author Implementation based on Zhou et al., 2026
 */
@Service
public class HyperGraphMemoryService {

    private static final Logger log = LoggerFactory.getLogger(HyperGraphMemoryService.class);

    private final ChatClient chatClient;

    // The hypergraph structure
    // Key: Session ID, Value: HyperGraph for that session
    private final Map<String, HyperGraph> sessionMemories = new ConcurrentHashMap<>();

    // Maximum memory points before forced consolidation
    private static final int MAX_MEMORY_POINTS = 50;
    
    // Similarity threshold for merge candidates
    private static final double MERGE_SIMILARITY_THRESHOLD = 0.7;

    public HyperGraphMemoryService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
        log.info(">>> HyperGraph Memory Service initialized <<<");
    }

    // ========== Core Data Structures ==========

    /**
     * A vertex in the hypergraph, representing an entity.
     */
    public static class Vertex {
        private final String id;
        private String name;
        private String description;
        private Set<String> sourceChunks;  // Associated text chunk IDs
        private Instant createdAt;
        private Instant updatedAt;

        public Vertex(String id, String name, String description) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.sourceChunks = new HashSet<>();
            this.createdAt = Instant.now();
            this.updatedAt = Instant.now();
        }

        // Getters and setters
        public String getId() { return id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; this.updatedAt = Instant.now(); }
        public String getDescription() { return description; }
        public void setDescription(String desc) { this.description = desc; this.updatedAt = Instant.now(); }
        public Set<String> getSourceChunks() { return sourceChunks; }
        public void addSourceChunk(String chunkId) { this.sourceChunks.add(chunkId); }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getUpdatedAt() { return updatedAt; }
    }

    /**
     * A hyperedge - the core memory unit connecting multiple vertices.
     * 
     * From paper: "Every hyperedge can be treated as a separate memory point, 
     * each of which corresponds to a certain aspect of the entire information 
     * stored in current memory."
     */
    public static class HyperEdge {
        private final String id;
        private String description;           // Relationship description
        private Set<String> vertexIds;        // Connected vertex IDs (n-ary relation)
        private double confidence;
        private int order;                    // Order of the hyperedge (how many merges created it)
        private Instant createdAt;
        private Instant updatedAt;

        public HyperEdge(String id, String description, Set<String> vertexIds) {
            this.id = id;
            this.description = description;
            this.vertexIds = new HashSet<>(vertexIds);
            this.confidence = 1.0;
            this.order = 1;  // First-order (primitive fact)
            this.createdAt = Instant.now();
            this.updatedAt = Instant.now();
        }

        // Getters and setters
        public String getId() { return id; }
        public String getDescription() { return description; }
        public void setDescription(String desc) { this.description = desc; this.updatedAt = Instant.now(); }
        public Set<String> getVertexIds() { return vertexIds; }
        public void addVertex(String vertexId) { this.vertexIds.add(vertexId); }
        public double getConfidence() { return confidence; }
        public void setConfidence(double conf) { this.confidence = conf; }
        public int getOrder() { return order; }
        public void setOrder(int order) { this.order = order; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getUpdatedAt() { return updatedAt; }

        /**
         * Merge another hyperedge into this one.
         * Creates a higher-order memory point.
         */
        public void merge(HyperEdge other, String mergedDescription) {
            this.vertexIds.addAll(other.getVertexIds());
            this.description = mergedDescription;
            this.order = Math.max(this.order, other.order) + 1;
            this.confidence = (this.confidence + other.confidence) / 2.0;
            this.updatedAt = Instant.now();
        }
    }

    /**
     * The complete hypergraph structure.
     */
    public static class HyperGraph {
        private final String sessionId;
        private final Map<String, Vertex> vertices;
        private final Map<String, HyperEdge> hyperEdges;
        private int interactionStep;
        private Instant createdAt;
        private Instant updatedAt;

        public HyperGraph(String sessionId) {
            this.sessionId = sessionId;
            this.vertices = new ConcurrentHashMap<>();
            this.hyperEdges = new ConcurrentHashMap<>();
            this.interactionStep = 0;
            this.createdAt = Instant.now();
            this.updatedAt = Instant.now();
        }

        public String getSessionId() { return sessionId; }
        public Map<String, Vertex> getVertices() { return vertices; }
        public Map<String, HyperEdge> getHyperEdges() { return hyperEdges; }
        public int getInteractionStep() { return interactionStep; }
        public void incrementStep() { this.interactionStep++; this.updatedAt = Instant.now(); }
        
        public int getMemoryPointCount() { return hyperEdges.size(); }
        
        public void addVertex(Vertex v) { vertices.put(v.getId(), v); updatedAt = Instant.now(); }
        public void addHyperEdge(HyperEdge e) { hyperEdges.put(e.getId(), e); updatedAt = Instant.now(); }
        public void removeHyperEdge(String id) { hyperEdges.remove(id); updatedAt = Instant.now(); }
    }

    // ========== Memory Operations ==========

    /**
     * Get or create a hypergraph for a session.
     */
    public HyperGraph getOrCreateMemory(String sessionId) {
        return sessionMemories.computeIfAbsent(sessionId, HyperGraph::new);
    }

    /**
     * INSERT Operation: Add a new memory point (hyperedge) to the hypergraph.
     * 
     * From paper: "The insertion operation should be evoked when some content of 
     * the retrieved information is suitable to be inserted as additional memory 
     * points into the current memory."
     */
    public HyperEdge insertMemoryPoint(String sessionId, String description, 
                                        Set<String> entityNames, Document sourceDoc) {
        HyperGraph graph = getOrCreateMemory(sessionId);

        // Create or find vertices for each entity
        Set<String> vertexIds = new HashSet<>();
        for (String entityName : entityNames) {
            String vertexId = generateVertexId(entityName);
            Vertex vertex = graph.getVertices().computeIfAbsent(vertexId, 
                id -> new Vertex(id, entityName, ""));
            
            // Associate source document
            if (sourceDoc != null && sourceDoc.getMetadata().get("source") != null) {
                vertex.addSourceChunk(sourceDoc.getMetadata().get("source").toString());
            }
            vertexIds.add(vertexId);
        }

        // Create the hyperedge (memory point)
        String edgeId = "he_" + UUID.randomUUID().toString().substring(0, 8);
        HyperEdge edge = new HyperEdge(edgeId, description, vertexIds);
        graph.addHyperEdge(edge);

        log.info("HGMem INSERT: Created memory point '{}' connecting {} entities", 
                 edgeId, vertexIds.size());

        // Check if we need to consolidate
        if (graph.getMemoryPointCount() > MAX_MEMORY_POINTS) {
            consolidateMemory(sessionId);
        }

        return edge;
    }

    /**
     * UPDATE Operation: Revise the description of an existing memory point.
     * 
     * From paper: "The update operation will revise the descriptions of corresponding 
     * hyperedges without changing their subordinate entities."
     */
    public void updateMemoryPoint(String sessionId, String edgeId, String newDescription) {
        HyperGraph graph = getOrCreateMemory(sessionId);
        HyperEdge edge = graph.getHyperEdges().get(edgeId);
        
        if (edge != null) {
            String oldDesc = edge.getDescription();
            edge.setDescription(newDescription);
            log.info("HGMem UPDATE: Modified memory point '{}': '{}' -> '{}'", 
                     edgeId, truncate(oldDesc, 30), truncate(newDescription, 30));
        } else {
            log.warn("HGMem UPDATE: Memory point '{}' not found", edgeId);
        }
    }

    /**
     * MERGE Operation: Combine related memory points into a higher-order correlation.
     * 
     * From paper: "The LLM inspects current memory and selectively merges existing 
     * memory points that are more suitable to constitute a single semantically/logically 
     * cohesive unit."
     */
    public HyperEdge mergeMemoryPoints(String sessionId, String edgeId1, String edgeId2, 
                                        String targetQuery) {
        HyperGraph graph = getOrCreateMemory(sessionId);
        HyperEdge edge1 = graph.getHyperEdges().get(edgeId1);
        HyperEdge edge2 = graph.getHyperEdges().get(edgeId2);

        if (edge1 == null || edge2 == null) {
            log.warn("HGMem MERGE: One or both edges not found: {}, {}", edgeId1, edgeId2);
            return null;
        }

        // Generate merged description using LLM
        String mergedDescription = generateMergedDescription(
            edge1.getDescription(), 
            edge2.getDescription(), 
            targetQuery
        );

        // Create new merged hyperedge
        Set<String> mergedVertices = new HashSet<>();
        mergedVertices.addAll(edge1.getVertexIds());
        mergedVertices.addAll(edge2.getVertexIds());

        String newEdgeId = "he_merged_" + UUID.randomUUID().toString().substring(0, 8);
        HyperEdge mergedEdge = new HyperEdge(newEdgeId, mergedDescription, mergedVertices);
        mergedEdge.setOrder(Math.max(edge1.getOrder(), edge2.getOrder()) + 1);
        mergedEdge.setConfidence((edge1.getConfidence() + edge2.getConfidence()) / 2.0);

        // Add merged edge, remove originals
        graph.addHyperEdge(mergedEdge);
        graph.removeHyperEdge(edgeId1);
        graph.removeHyperEdge(edgeId2);

        log.info("HGMem MERGE: Combined '{}' + '{}' -> '{}' (order: {})", 
                 edgeId1, edgeId2, newEdgeId, mergedEdge.getOrder());

        return mergedEdge;
    }

    /**
     * Use LLM to generate a coherent merged description.
     */
    private String generateMergedDescription(String desc1, String desc2, String targetQuery) {
        String systemPrompt = """
            You are a knowledge synthesis assistant. Given two related facts and a target query,
            create a single coherent description that combines both facts.
            
            Keep the merged description concise (1-2 sentences) but comprehensive.
            Focus on information relevant to the query.
            
            Fact 1: %s
            Fact 2: %s
            Target Query: %s
            
            Merged Description:
            """.formatted(desc1, desc2, targetQuery);

        try {
            Prompt prompt = new Prompt(List.of(
                new SystemPromptTemplate(systemPrompt).createMessage(),
                new UserMessage("Generate the merged description:")
            ));
            
            String response = chatClient.call(prompt).getResult().getOutput().getContent();
            return response.trim();
        } catch (Exception e) {
            log.error("Failed to generate merged description: {}", e.getMessage());
            // Fallback: simple concatenation
            return desc1 + " Additionally, " + desc2;
        }
    }

    // ========== Retrieval Strategies ==========

    /**
     * Local Investigation: Retrieve from neighborhood of a specific memory point.
     * 
     * From paper: "When the LLM plans to more deeply investigate some specific memory 
     * points, its generated subqueries are utilized to trigger local evidence retrieval."
     */
    public Set<Vertex> localInvestigation(String sessionId, String edgeId) {
        HyperGraph graph = getOrCreateMemory(sessionId);
        HyperEdge edge = graph.getHyperEdges().get(edgeId);
        
        if (edge == null) {
            return Collections.emptySet();
        }

        Set<Vertex> neighborhood = new HashSet<>();
        
        // Get vertices connected to this edge
        for (String vertexId : edge.getVertexIds()) {
            Vertex v = graph.getVertices().get(vertexId);
            if (v != null) {
                neighborhood.add(v);
            }
        }

        // Find neighboring edges (edges sharing at least one vertex)
        for (HyperEdge otherEdge : graph.getHyperEdges().values()) {
            if (!otherEdge.getId().equals(edgeId)) {
                boolean hasCommonVertex = otherEdge.getVertexIds().stream()
                    .anyMatch(edge.getVertexIds()::contains);
                
                if (hasCommonVertex) {
                    for (String vertexId : otherEdge.getVertexIds()) {
                        Vertex v = graph.getVertices().get(vertexId);
                        if (v != null) {
                            neighborhood.add(v);
                        }
                    }
                }
            }
        }

        log.debug("HGMem Local Investigation: Found {} vertices in neighborhood of '{}'", 
                  neighborhood.size(), edgeId);
        return neighborhood;
    }

    /**
     * Global Exploration: Retrieve vertices not yet in memory.
     * 
     * From paper: "When there are unexplored aspects transcending the scope of 
     * current memory, the LLM resorts to generating subqueries for exploring 
     * broader information."
     */
    public Set<String> getUnexploredEntities(String sessionId, Set<String> candidateEntities) {
        HyperGraph graph = getOrCreateMemory(sessionId);
        
        Set<String> existingNames = graph.getVertices().values().stream()
            .map(Vertex::getName)
            .map(String::toLowerCase)
            .collect(Collectors.toSet());

        return candidateEntities.stream()
            .filter(entity -> !existingNames.contains(entity.toLowerCase()))
            .collect(Collectors.toSet());
    }

    // ========== Memory Consolidation ==========

    /**
     * Consolidate memory by merging similar hyperedges.
     * Called when memory exceeds MAX_MEMORY_POINTS.
     */
    public void consolidateMemory(String sessionId) {
        HyperGraph graph = getOrCreateMemory(sessionId);
        log.info("HGMem: Consolidating memory (current size: {})", graph.getMemoryPointCount());

        List<HyperEdge> edges = new ArrayList<>(graph.getHyperEdges().values());
        Set<String> merged = new HashSet<>();

        for (int i = 0; i < edges.size(); i++) {
            if (merged.contains(edges.get(i).getId())) continue;

            for (int j = i + 1; j < edges.size(); j++) {
                if (merged.contains(edges.get(j).getId())) continue;

                HyperEdge e1 = edges.get(i);
                HyperEdge e2 = edges.get(j);

                // Check if edges share significant vertex overlap
                double similarity = computeEdgeSimilarity(e1, e2);
                if (similarity >= MERGE_SIMILARITY_THRESHOLD) {
                    mergeMemoryPoints(sessionId, e1.getId(), e2.getId(), "consolidation");
                    merged.add(e1.getId());
                    merged.add(e2.getId());
                    break;  // e1 is now merged, move to next
                }
            }
        }

        log.info("HGMem: Consolidation complete (new size: {})", graph.getMemoryPointCount());
    }

    private double computeEdgeSimilarity(HyperEdge e1, HyperEdge e2) {
        Set<String> v1 = e1.getVertexIds();
        Set<String> v2 = e2.getVertexIds();

        Set<String> intersection = new HashSet<>(v1);
        intersection.retainAll(v2);

        Set<String> union = new HashSet<>(v1);
        union.addAll(v2);

        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    // ========== Memory Serialization for Context ==========

    /**
     * Export memory to string format for LLM context.
     * 
     * From paper: "Besides the descriptions of all memory points, the text chunks 
     * associated with all entities in current memory are also provided to the LLM 
     * for producing the final response."
     */
    public String exportMemoryToContext(String sessionId) {
        HyperGraph graph = sessionMemories.get(sessionId);
        if (graph == null || graph.getHyperEdges().isEmpty()) {
            return "No memory points available.";
        }

        StringBuilder context = new StringBuilder();
        context.append("=== HYPERGRAPH MEMORY STATE ===\n\n");

        // Sort by order (higher-order correlations first)
        List<HyperEdge> sortedEdges = graph.getHyperEdges().values().stream()
            .sorted((a, b) -> Integer.compare(b.getOrder(), a.getOrder()))
            .collect(Collectors.toList());

        for (HyperEdge edge : sortedEdges) {
            context.append(String.format("• Memory Point [%s] (Order: %d, Confidence: %.2f)\n",
                edge.getId(), edge.getOrder(), edge.getConfidence()));
            
            // List connected entities
            List<String> entityNames = edge.getVertexIds().stream()
                .map(id -> graph.getVertices().get(id))
                .filter(Objects::nonNull)
                .map(Vertex::getName)
                .collect(Collectors.toList());
            context.append(String.format("  Entities: %s\n", String.join(", ", entityNames)));
            context.append(String.format("  Description: %s\n\n", edge.getDescription()));
        }

        context.append("=== END MEMORY STATE ===\n");
        return context.toString();
    }

    /**
     * Generate subqueries based on current memory state.
     * 
     * From paper: "At each step before response generation, the LLM examines 
     * the current memory and generates subqueries."
     */
    public List<String> generateSubqueries(String sessionId, String targetQuery) {
        String memoryContext = exportMemoryToContext(sessionId);

        String systemPrompt = """
            You are analyzing a knowledge retrieval task. Based on the current memory state 
            and the target query, generate 2-3 specific subqueries that would help gather 
            additional relevant information.
            
            Current Memory State:
            %s
            
            Target Query: %s
            
            Generate subqueries that:
            1. Explore gaps in current knowledge
            2. Deepen understanding of existing memory points
            3. Connect related concepts
            
            Return each subquery on a new line, nothing else.
            """.formatted(memoryContext, targetQuery);

        try {
            Prompt prompt = new Prompt(List.of(
                new SystemPromptTemplate(systemPrompt).createMessage(),
                new UserMessage("Generate subqueries:")
            ));
            
            String response = chatClient.call(prompt).getResult().getOutput().getContent();
            return Arrays.stream(response.split("\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .limit(3)
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to generate subqueries: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ========== Utility Methods ==========

    private String generateVertexId(String entityName) {
        return "v_" + entityName.toLowerCase().replaceAll("\\s+", "_").replaceAll("[^a-z0-9_]", "");
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    /**
     * Get statistics about a session's memory.
     */
    public Map<String, Object> getMemoryStats(String sessionId) {
        HyperGraph graph = sessionMemories.get(sessionId);
        if (graph == null) {
            return Map.of("exists", false);
        }

        int maxOrder = graph.getHyperEdges().values().stream()
            .mapToInt(HyperEdge::getOrder)
            .max()
            .orElse(0);

        return Map.of(
            "exists", true,
            "vertices", graph.getVertices().size(),
            "memoryPoints", graph.getHyperEdges().size(),
            "interactionSteps", graph.getInteractionStep(),
            "maxOrder", maxOrder
        );
    }

    /**
     * Clear memory for a session.
     */
    public void clearMemory(String sessionId) {
        sessionMemories.remove(sessionId);
        log.info("HGMem: Cleared memory for session '{}'", sessionId);
    }
}
