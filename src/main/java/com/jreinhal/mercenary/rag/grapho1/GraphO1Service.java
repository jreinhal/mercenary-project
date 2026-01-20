/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  jakarta.annotation.PostConstruct
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.ai.chat.client.ChatClient
 *  org.springframework.ai.chat.client.ChatClient$Builder
 *  org.springframework.ai.document.Document
 *  org.springframework.beans.factory.annotation.Value
 *  org.springframework.stereotype.Service
 */
package com.jreinhal.mercenary.rag.grapho1;

import com.jreinhal.mercenary.reasoning.ReasoningStep;
import com.jreinhal.mercenary.reasoning.ReasoningTracer;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class GraphO1Service {
    private static final Logger log = LoggerFactory.getLogger(GraphO1Service.class);
    private final ChatClient chatClient;
    private final ReasoningTracer reasoningTracer;
    @Value(value="${sentinel.grapho1.enabled:true}")
    private boolean enabled;
    @Value(value="${sentinel.grapho1.max-iterations:50}")
    private int maxIterations;
    @Value(value="${sentinel.grapho1.exploration-constant:1.414}")
    private double explorationConstant;
    @Value(value="${sentinel.grapho1.max-depth:5}")
    private int maxDepth;
    @Value(value="${sentinel.grapho1.simulation-count:3}")
    private int simulationCount;
    @Value(value="${sentinel.grapho1.min-confidence:0.6}")
    private double minConfidence;

    public GraphO1Service(ChatClient.Builder builder, ReasoningTracer reasoningTracer) {
        this.chatClient = builder.build();
        this.reasoningTracer = reasoningTracer;
    }

    @PostConstruct
    public void init() {
        log.info("Graph-O1 initialized (enabled={}, maxIter={}, exploreC={}, maxDepth={})", new Object[]{this.enabled, this.maxIterations, this.explorationConstant, this.maxDepth});
    }

    public MCTSResult reason(String query, List<Document> rootNodes, Map<String, List<GraphEdge>> adjacencyMap) {
        if (!this.enabled || rootNodes.isEmpty()) {
            return new MCTSResult(rootNodes, List.of(), 0.0, Map.of("mode", "disabled"));
        }
        long startTime = System.currentTimeMillis();
        MCTSNode root = new MCTSNode(null, "ROOT", null, 0);
        for (Document doc : rootNodes) {
            String nodeId = this.getDocId(doc);
            MCTSNode child = new MCTSNode(root, nodeId, doc, 1);
            root.children.add(child);
        }
        int totalSimulations = 0;
        for (int i = 0; i < this.maxIterations; ++i) {
            MCTSNode selected = this.select(root);
            if (selected.depth < this.maxDepth && !this.isFullyExpanded(selected, adjacencyMap)) {
                selected = this.expand(selected, adjacencyMap);
            }
            double reward = this.simulate(query, selected);
            ++totalSimulations;
            this.backpropagate(selected, reward);
            if (!(this.getBestPathConfidence(root) >= this.minConfidence)) continue;
            log.debug("MCTS: Early termination at iteration {} (confidence={})", i, this.getBestPathConfidence(root));
            break;
        }
        List<MCTSNode> bestPath = this.extractBestPath(root);
        List<Document> orderedDocs = bestPath.stream().filter(n -> n.document != null).map(n -> n.document).toList();
        double confidence = this.getBestPathConfidence(root);
        long elapsed = System.currentTimeMillis() - startTime;
        this.reasoningTracer.addStep(ReasoningStep.StepType.MCTS_REASONING, "Graph-O1 MCTS Reasoning", String.format("%d iterations, %d simulations, path length %d, confidence %.2f", this.maxIterations, totalSimulations, bestPath.size(), confidence), elapsed, Map.of("iterations", this.maxIterations, "simulations", totalSimulations, "pathLength", bestPath.size(), "confidence", confidence));
        log.info("Graph-O1: MCTS completed in {}ms ({} iterations, {} path nodes, {:.2f} confidence)", new Object[]{elapsed, this.maxIterations, bestPath.size(), confidence});
        return new MCTSResult(orderedDocs, this.extractPathIds(bestPath), confidence, Map.of("iterations", this.maxIterations, "simulations", totalSimulations, "elapsed", elapsed));
    }

    private MCTSNode select(MCTSNode node) {
        while (!node.children.isEmpty()) {
            if (node.children.stream().anyMatch(c -> c.visits == 0)) {
                return node.children.stream().filter(c -> c.visits == 0).findFirst().orElse(node);
            }
            node = node.children.stream().max(Comparator.comparingDouble(this::ucb1)).orElse(node);
        }
        return node;
    }

    private double ucb1(MCTSNode node) {
        if (node.visits == 0) {
            return Double.MAX_VALUE;
        }
        double exploitation = node.totalReward / (double)node.visits;
        double exploration = this.explorationConstant * Math.sqrt(Math.log(node.parent.visits) / (double)node.visits);
        return exploitation + exploration;
    }

    private MCTSNode expand(MCTSNode node, Map<String, List<GraphEdge>> adjacencyMap) {
        List<GraphEdge> edges = adjacencyMap.getOrDefault(node.nodeId, List.of());
        HashSet<String> exploredIds = new HashSet<String>();
        for (MCTSNode child : node.children) {
            exploredIds.add(child.nodeId);
        }
        for (GraphEdge edge : edges) {
            if (exploredIds.contains(edge.targetId())) continue;
            MCTSNode newChild = new MCTSNode(node, edge.targetId(), edge.targetDoc(), node.depth + 1);
            newChild.edgeType = edge.type();
            node.children.add(newChild);
            return newChild;
        }
        return node;
    }

    private boolean isFullyExpanded(MCTSNode node, Map<String, List<GraphEdge>> adjacencyMap) {
        List<GraphEdge> edges = adjacencyMap.getOrDefault(node.nodeId, List.of());
        return node.children.size() >= edges.size();
    }

    private double simulate(String query, MCTSNode node) {
        StringBuilder pathContext = new StringBuilder();
        MCTSNode current = node;
        ArrayList<String> pathDocs = new ArrayList<String>();
        while (current != null && current.document != null) {
            pathDocs.add(current.document.getContent());
            current = current.parent;
        }
        Collections.reverse(pathDocs);
        for (int i = 0; i < pathDocs.size(); ++i) {
            pathContext.append(String.format("[Document %d]: %s\n\n", i + 1, this.truncate((String)pathDocs.get(i), 500)));
        }
        if (pathContext.isEmpty()) {
            return 0.3;
        }
        try {
            String prompt = String.format("Rate how well these documents, in this order, help answer the query.\nConsider: relevance, logical flow, completeness.\n\nQuery: %s\n\nDocument Path:\n%s\n\nOutput only a number from 0.0 to 1.0 representing the quality score.\n", query, pathContext);
            String response = this.chatClient.prompt().user(prompt).call().content();
            return this.parseScore(response);
        }
        catch (Exception e) {
            log.warn("MCTS simulation failed: {}", e.getMessage());
            return this.heuristicScore(query, pathDocs);
        }
    }

    private void backpropagate(MCTSNode node, double reward) {
        while (node != null) {
            ++node.visits;
            node.totalReward += reward;
            node = node.parent;
        }
    }

    private List<MCTSNode> extractBestPath(MCTSNode root) {
        MCTSNode best;
        ArrayList<MCTSNode> path = new ArrayList<MCTSNode>();
        MCTSNode current = root;
        while (!current.children.isEmpty() && (best = (MCTSNode)current.children.stream().filter(c -> c.visits > 0).max(Comparator.comparingDouble(c -> c.totalReward / (double)c.visits)).orElse(null)) != null) {
            path.add(best);
            current = best;
        }
        return path;
    }

    private double getBestPathConfidence(MCTSNode root) {
        List<MCTSNode> path = this.extractBestPath(root);
        if (path.isEmpty()) {
            return 0.0;
        }
        return path.stream().filter(n -> n.visits > 0).mapToDouble(n -> n.totalReward / (double)n.visits).average().orElse(0.0);
    }

    private List<String> extractPathIds(List<MCTSNode> path) {
        return path.stream().map(n -> n.nodeId).toList();
    }

    private double parseScore(String response) {
        try {
            String cleaned = response.trim().replaceAll("[^0-9.]", "");
            double score = Double.parseDouble(cleaned);
            return Math.max(0.0, Math.min(1.0, score));
        }
        catch (NumberFormatException e) {
            return 0.5;
        }
    }

    private double heuristicScore(String query, List<String> docs) {
        HashSet<String> queryTerms = new HashSet<String>(Arrays.asList(query.toLowerCase().split("\\s+")));
        int totalMatches = 0;
        for (String doc : docs) {
            String lower = doc.toLowerCase();
            for (String term : queryTerms) {
                if (term.length() <= 2 || !lower.contains(term)) continue;
                ++totalMatches;
            }
        }
        double score = (double)totalMatches / (double)(queryTerms.size() * Math.max(1, docs.size()));
        return Math.min(1.0, score * 2.0);
    }

    private String getDocId(Document doc) {
        Object source = doc.getMetadata().get("source");
        int contentHash = doc.getContent().hashCode();
        return String.valueOf(source) + "_" + contentHash;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public record MCTSResult(List<Document> documents, List<String> reasoningPath, double confidence, Map<String, Object> metadata) {
    }

    private static class MCTSNode {
        MCTSNode parent;
        String nodeId;
        Document document;
        int depth;
        String edgeType;
        List<MCTSNode> children = new ArrayList<MCTSNode>();
        int visits = 0;
        double totalReward = 0.0;

        MCTSNode(MCTSNode parent, String nodeId, Document document, int depth) {
            this.parent = parent;
            this.nodeId = nodeId;
            this.document = document;
            this.depth = depth;
        }
    }

    public record GraphEdge(String targetId, Document targetDoc, String type, double weight) {
    }
}
