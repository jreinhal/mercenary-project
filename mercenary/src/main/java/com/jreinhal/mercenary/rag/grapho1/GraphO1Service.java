package com.jreinhal.mercenary.rag.grapho1;

import com.jreinhal.mercenary.reasoning.ReasoningTracer;
import com.jreinhal.mercenary.reasoning.ReasoningStep.StepType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Graph-O1: Monte Carlo Tree Search for Graph-Structured Reasoning
 * Based on research paper arXiv:2512.17912
 *
 * Enhances HGMem hypergraph traversal with MCTS-based strategic reasoning:
 * 1. SELECTION: UCB1 bandit algorithm for path selection
 * 2. EXPANSION: Generate new reasoning paths from unexplored nodes
 * 3. SIMULATION: LLM-based rollout to evaluate paths
 * 4. BACKPROPAGATION: Update path values based on outcomes
 *
 * Key innovations:
 * - Treats knowledge graph traversal as a sequential decision problem
 * - Uses reinforcement learning signals for path optimization
 * - Balances exploration vs exploitation in reasoning
 * - Provides confidence scores for reasoning chains
 */
@Service
public class GraphO1Service {

    private static final Logger log = LoggerFactory.getLogger(GraphO1Service.class);

    private final ChatClient chatClient;
    private final ReasoningTracer reasoningTracer;

    @Value("${sentinel.grapho1.enabled:true}")
    private boolean enabled;

    @Value("${sentinel.grapho1.max-iterations:50}")
    private int maxIterations;

    @Value("${sentinel.grapho1.exploration-constant:1.414}")
    private double explorationConstant; // sqrt(2) is common UCB1 value

    @Value("${sentinel.grapho1.max-depth:5}")
    private int maxDepth;

    @Value("${sentinel.grapho1.simulation-count:3}")
    private int simulationCount;

    @Value("${sentinel.grapho1.min-confidence:0.6}")
    private double minConfidence;

    public GraphO1Service(ChatClient.Builder builder, ReasoningTracer reasoningTracer) {
        this.chatClient = builder.build();
        this.reasoningTracer = reasoningTracer;
    }

    @PostConstruct
    public void init() {
        log.info("Graph-O1 initialized (enabled={}, maxIter={}, exploreC={}, maxDepth={})",
                enabled, maxIterations, explorationConstant, maxDepth);
    }

    /**
     * Perform MCTS-guided reasoning over a knowledge graph.
     *
     * @param query User's query
     * @param rootNodes Initial nodes from retrieval
     * @param adjacencyMap Graph structure (node -> connected nodes)
     * @return MCTS reasoning result with optimal path
     */
    public MCTSResult reason(String query,
                             List<Document> rootNodes,
                             Map<String, List<GraphEdge>> adjacencyMap) {
        if (!enabled || rootNodes.isEmpty()) {
            return new MCTSResult(rootNodes, List.of(), 0.0, Map.of("mode", "disabled"));
        }

        long startTime = System.currentTimeMillis();

        // Initialize MCTS tree
        MCTSNode root = new MCTSNode(null, "ROOT", null, 0);
        for (Document doc : rootNodes) {
            String nodeId = getDocId(doc);
            MCTSNode child = new MCTSNode(root, nodeId, doc, 1);
            root.children.add(child);
        }

        // Run MCTS iterations
        int totalSimulations = 0;
        for (int i = 0; i < maxIterations; i++) {
            // Selection: Find most promising node using UCB1
            MCTSNode selected = select(root);

            // Expansion: Add new children if not fully expanded
            if (selected.depth < maxDepth && !isFullyExpanded(selected, adjacencyMap)) {
                selected = expand(selected, adjacencyMap);
            }

            // Simulation: Evaluate the path
            double reward = simulate(query, selected);
            totalSimulations++;

            // Backpropagation: Update statistics
            backpropagate(selected, reward);

            // Early termination if we found a high-confidence path
            if (getBestPathConfidence(root) >= minConfidence) {
                log.debug("MCTS: Early termination at iteration {} (confidence={})",
                        i, getBestPathConfidence(root));
                break;
            }
        }

        // Extract best path
        List<MCTSNode> bestPath = extractBestPath(root);
        List<Document> orderedDocs = bestPath.stream()
                .filter(n -> n.document != null)
                .map(n -> n.document)
                .toList();

        double confidence = getBestPathConfidence(root);
        long elapsed = System.currentTimeMillis() - startTime;

        // Add reasoning step
        reasoningTracer.addStep(StepType.MCTS_REASONING,
                "Graph-O1 MCTS Reasoning",
                String.format("%d iterations, %d simulations, path length %d, confidence %.2f",
                        maxIterations, totalSimulations, bestPath.size(), confidence),
                elapsed,
                Map.of("iterations", maxIterations,
                       "simulations", totalSimulations,
                       "pathLength", bestPath.size(),
                       "confidence", confidence));

        log.info("Graph-O1: MCTS completed in {}ms ({} iterations, {} path nodes, {:.2f} confidence)",
                elapsed, maxIterations, bestPath.size(), confidence);

        return new MCTSResult(orderedDocs, extractPathIds(bestPath), confidence, Map.of(
                "iterations", maxIterations,
                "simulations", totalSimulations,
                "elapsed", elapsed
        ));
    }

    /**
     * Selection phase: Use UCB1 to find most promising node.
     */
    private MCTSNode select(MCTSNode node) {
        while (!node.children.isEmpty()) {
            // If not all children explored, return this node
            if (node.children.stream().anyMatch(c -> c.visits == 0)) {
                return node.children.stream()
                        .filter(c -> c.visits == 0)
                        .findFirst()
                        .orElse(node);
            }

            // UCB1 selection
            node = node.children.stream()
                    .max(Comparator.comparingDouble(this::ucb1))
                    .orElse(node);
        }
        return node;
    }

    /**
     * UCB1 formula: exploitation + exploration.
     */
    private double ucb1(MCTSNode node) {
        if (node.visits == 0) {
            return Double.MAX_VALUE;
        }

        double exploitation = node.totalReward / node.visits;
        double exploration = explorationConstant *
                Math.sqrt(Math.log(node.parent.visits) / node.visits);

        return exploitation + exploration;
    }

    /**
     * Expansion phase: Add new child nodes from graph edges.
     */
    private MCTSNode expand(MCTSNode node, Map<String, List<GraphEdge>> adjacencyMap) {
        List<GraphEdge> edges = adjacencyMap.getOrDefault(node.nodeId, List.of());

        // Find unexplored edges
        Set<String> exploredIds = new HashSet<>();
        for (MCTSNode child : node.children) {
            exploredIds.add(child.nodeId);
        }

        for (GraphEdge edge : edges) {
            if (!exploredIds.contains(edge.targetId())) {
                MCTSNode newChild = new MCTSNode(node, edge.targetId(), edge.targetDoc(), node.depth + 1);
                newChild.edgeType = edge.type();
                node.children.add(newChild);
                return newChild;
            }
        }

        return node;
    }

    /**
     * Check if all possible children have been expanded.
     */
    private boolean isFullyExpanded(MCTSNode node, Map<String, List<GraphEdge>> adjacencyMap) {
        List<GraphEdge> edges = adjacencyMap.getOrDefault(node.nodeId, List.of());
        return node.children.size() >= edges.size();
    }

    /**
     * Simulation phase: Evaluate reasoning path using LLM.
     */
    private double simulate(String query, MCTSNode node) {
        // Build path context
        StringBuilder pathContext = new StringBuilder();
        MCTSNode current = node;
        List<String> pathDocs = new ArrayList<>();

        while (current != null && current.document != null) {
            pathDocs.add(current.document.getContent());
            current = current.parent;
        }

        Collections.reverse(pathDocs);
        for (int i = 0; i < pathDocs.size(); i++) {
            pathContext.append(String.format("[Document %d]: %s\n\n", i + 1,
                    truncate(pathDocs.get(i), 500)));
        }

        if (pathContext.isEmpty()) {
            return 0.3; // Default low score for empty paths
        }

        // LLM evaluation
        try {
            String prompt = String.format("""
                    Rate how well these documents, in this order, help answer the query.
                    Consider: relevance, logical flow, completeness.

                    Query: %s

                    Document Path:
                    %s

                    Output only a number from 0.0 to 1.0 representing the quality score.
                    """, query, pathContext);

            @SuppressWarnings("deprecation")
            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            return parseScore(response);

        } catch (Exception e) {
            log.warn("MCTS simulation failed: {}", e.getMessage());
            // Fallback to heuristic scoring
            return heuristicScore(query, pathDocs);
        }
    }

    /**
     * Backpropagation phase: Update node statistics.
     */
    private void backpropagate(MCTSNode node, double reward) {
        while (node != null) {
            node.visits++;
            node.totalReward += reward;
            node = node.parent;
        }
    }

    /**
     * Extract the best path based on visit counts.
     */
    private List<MCTSNode> extractBestPath(MCTSNode root) {
        List<MCTSNode> path = new ArrayList<>();
        MCTSNode current = root;

        while (!current.children.isEmpty()) {
            // Select child with highest average reward (exploitation)
            MCTSNode best = current.children.stream()
                    .filter(c -> c.visits > 0)
                    .max(Comparator.comparingDouble(c -> c.totalReward / c.visits))
                    .orElse(null);

            if (best == null) break;

            path.add(best);
            current = best;
        }

        return path;
    }

    /**
     * Get confidence of the best path.
     */
    private double getBestPathConfidence(MCTSNode root) {
        List<MCTSNode> path = extractBestPath(root);
        if (path.isEmpty()) return 0.0;

        // Average confidence along the path
        return path.stream()
                .filter(n -> n.visits > 0)
                .mapToDouble(n -> n.totalReward / n.visits)
                .average()
                .orElse(0.0);
    }

    /**
     * Extract node IDs from path.
     */
    private List<String> extractPathIds(List<MCTSNode> path) {
        return path.stream()
                .map(n -> n.nodeId)
                .toList();
    }

    /**
     * Parse LLM score response.
     */
    private double parseScore(String response) {
        try {
            String cleaned = response.trim().replaceAll("[^0-9.]", "");
            double score = Double.parseDouble(cleaned);
            return Math.max(0.0, Math.min(1.0, score));
        } catch (NumberFormatException e) {
            return 0.5; // Default mid-range score
        }
    }

    /**
     * Heuristic scoring based on keyword overlap.
     */
    private double heuristicScore(String query, List<String> docs) {
        Set<String> queryTerms = new HashSet<>(Arrays.asList(query.toLowerCase().split("\\s+")));
        int totalMatches = 0;

        for (String doc : docs) {
            String lower = doc.toLowerCase();
            for (String term : queryTerms) {
                if (term.length() > 2 && lower.contains(term)) {
                    totalMatches++;
                }
            }
        }

        // Normalize by query terms and doc count
        double score = (double) totalMatches / (queryTerms.size() * Math.max(1, docs.size()));
        return Math.min(1.0, score * 2); // Scale up
    }

    /**
     * Get unique identifier for a document.
     */
    private String getDocId(Document doc) {
        Object source = doc.getMetadata().get("source");
        int contentHash = doc.getContent().hashCode();
        return source + "_" + contentHash;
    }

    /**
     * Truncate text to max length.
     */
    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ==================== Record Types ====================

    public record MCTSResult(
            List<Document> documents,
            List<String> reasoningPath,
            double confidence,
            Map<String, Object> metadata) {}

    public record GraphEdge(
            String targetId,
            Document targetDoc,
            String type,
            double weight) {}

    // ==================== MCTS Node ====================

    private static class MCTSNode {
        MCTSNode parent;
        String nodeId;
        Document document;
        int depth;
        String edgeType;

        List<MCTSNode> children = new ArrayList<>();
        int visits = 0;
        double totalReward = 0.0;

        MCTSNode(MCTSNode parent, String nodeId, Document document, int depth) {
            this.parent = parent;
            this.nodeId = nodeId;
            this.document = document;
            this.depth = depth;
        }
    }
}
