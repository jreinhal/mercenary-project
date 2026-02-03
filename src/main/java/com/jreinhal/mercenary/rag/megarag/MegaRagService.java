package com.jreinhal.mercenary.rag.megarag;

import com.jreinhal.mercenary.rag.megarag.ImageAnalyzer;
import com.jreinhal.mercenary.rag.megarag.VisualEntityLinker;
import com.jreinhal.mercenary.util.FilterExpressionBuilder;
import com.jreinhal.mercenary.reasoning.ReasoningStep;
import com.jreinhal.mercenary.reasoning.ReasoningTracer;
import com.jreinhal.mercenary.workspace.WorkspaceContext;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.CriteriaDefinition;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import com.jreinhal.mercenary.Department;

@Service
public class MegaRagService {
    private static final Logger log = LoggerFactory.getLogger(MegaRagService.class);
    private final VectorStore vectorStore;
    private final MongoTemplate mongoTemplate;
    private final ChatClient chatClient;
    private final ImageAnalyzer imageAnalyzer;
    private final VisualEntityLinker visualEntityLinker;
    private final ReasoningTracer reasoningTracer;
    private final ExecutorService ragExecutor;
    @Value(value="${sentinel.megarag.enabled:true}")
    private boolean enabled;
    @Value(value="${sentinel.megarag.visual-weight:0.3}")
    private double visualWeight;
    @Value(value="${sentinel.megarag.text-weight:0.7}")
    private double textWeight;
    @Value(value="${sentinel.megarag.cross-modal-threshold:0.5}")
    private double crossModalThreshold;
    @Value(value="${sentinel.performance.rag-future-timeout-seconds:8}")
    private int futureTimeoutSeconds;
    private static final String VISUAL_NODES_COLLECTION = "megarag_visual_nodes";
    private static final String CROSS_MODAL_EDGES_COLLECTION = "megarag_cross_modal_edges";

    public MegaRagService(VectorStore vectorStore, MongoTemplate mongoTemplate, ChatClient.Builder chatClientBuilder, ImageAnalyzer imageAnalyzer, VisualEntityLinker visualEntityLinker, ReasoningTracer reasoningTracer, @Qualifier("ragExecutor") ExecutorService ragExecutor) {
        this.vectorStore = vectorStore;
        this.mongoTemplate = mongoTemplate;
        this.chatClient = chatClientBuilder.build();
        this.imageAnalyzer = imageAnalyzer;
        this.visualEntityLinker = visualEntityLinker;
        this.reasoningTracer = reasoningTracer;
        this.ragExecutor = ragExecutor;
    }

    @PostConstruct
    public void init() {
        log.info("MegaRAG Service initialized (enabled={}, visualWeight={}, textWeight={})", new Object[]{this.enabled, this.visualWeight, this.textWeight});
    }

    public VisualIngestionResult ingestVisualAsset(byte[] imageBytes, String filename, String department, String contextText) {
        if (!this.enabled) {
            return new VisualIngestionResult(false, "MegaRAG disabled", List.of(), null);
        }
        long startTime = System.currentTimeMillis();
        try {
            String workspaceId = WorkspaceContext.getCurrentWorkspaceId();
            ImageAnalysis analysis = this.imageAnalyzer.analyze(imageBytes, filename);
            List<VisualEntity> visualEntities = analysis.entities();
            VisualNode node = new VisualNode(UUID.randomUUID().toString(), filename, analysis.imageType(), analysis.description(), analysis.extractedText(), visualEntities.stream().map(VisualEntity::name).toList(), department, workspaceId, System.currentTimeMillis());
            this.mongoTemplate.save(node, VISUAL_NODES_COLLECTION);
            if (contextText != null && !contextText.isBlank()) {
                List<CrossModalEdge> edges = this.visualEntityLinker.linkEntities(visualEntities, contextText, node.id(), workspaceId);
                for (CrossModalEdge edge : edges) {
                    this.mongoTemplate.save(edge, CROSS_MODAL_EDGES_COLLECTION);
                }
            }
            Document visualDoc = new Document(analysis.description());
            visualDoc.getMetadata().put("source", filename);
            visualDoc.getMetadata().put("dept", department);
            visualDoc.getMetadata().put("workspaceId", workspaceId);
            visualDoc.getMetadata().put("type", "visual");
            visualDoc.getMetadata().put("imageType", analysis.imageType().name());
            visualDoc.getMetadata().put("visualNodeId", node.id());
            visualDoc.getMetadata().put("extractedText", analysis.extractedText());
            this.vectorStore.add(List.of(visualDoc));
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("MegaRAG: Ingested visual asset '{}' ({}) with {} entities in {}ms", new Object[]{filename, analysis.imageType(), visualEntities.size(), elapsed});
            return new VisualIngestionResult(true, "Success", visualEntities, node.id());
        }
        catch (Exception e) {
            log.error("MegaRAG: Visual ingestion failed for '{}': {}", new Object[]{filename, e.getMessage(), e});
            return new VisualIngestionResult(false, e.getMessage(), List.of(), null);
        }
    }

    public CrossModalRetrievalResult retrieve(String query, String department) {
        Document doc;
        int i;
        if (!this.enabled) {
            return new CrossModalRetrievalResult(List.of(), List.of(), List.of());
        }
        String normalizedDept = this.normalizeDepartment(department);
        if (normalizedDept == null) {
            log.warn("MegaRAG: Invalid department '{}'", department);
            return new CrossModalRetrievalResult(List.of(), List.of(), List.of());
        }
        String workspaceId = WorkspaceContext.getCurrentWorkspaceId();
        long startTime = System.currentTimeMillis();
        CompletableFuture<List<Document>> textFuture = CompletableFuture.supplyAsync(() -> this.vectorStore.similaritySearch(SearchRequest.query((String)query).withTopK(15).withSimilarityThreshold(0.3).withFilterExpression(FilterExpressionBuilder.forDepartmentAndWorkspaceExcludingType(normalizedDept, workspaceId, "visual"))), this.ragExecutor);
        CompletableFuture<List<Document>> visualFuture = CompletableFuture.supplyAsync(() -> this.vectorStore.similaritySearch(SearchRequest.query((String)query).withTopK(10).withSimilarityThreshold(0.3).withFilterExpression(FilterExpressionBuilder.forDepartmentAndWorkspaceAndType(normalizedDept, workspaceId, "visual"))), this.ragExecutor);
        List<Document> textDocs;
        List<Document> visualDocs;
        try {
            textDocs = textFuture.get(this.futureTimeoutSeconds, TimeUnit.SECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("MegaRAG: Text retrieval interrupted");
            textDocs = List.of();
        }
        catch (TimeoutException e) {
            log.warn("MegaRAG: Text retrieval timed out");
            textFuture.cancel(true);
            textDocs = List.of();
        }
        catch (Exception e) {
            log.warn("MegaRAG: Text retrieval failed: {}", e.getMessage());
            textDocs = List.of();
        }
        try {
            visualDocs = visualFuture.get(this.futureTimeoutSeconds, TimeUnit.SECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("MegaRAG: Visual retrieval interrupted");
            visualDocs = List.of();
        }
        catch (TimeoutException e) {
            log.warn("MegaRAG: Visual retrieval timed out");
            visualFuture.cancel(true);
            visualDocs = List.of();
        }
        catch (Exception e) {
            log.warn("MegaRAG: Visual retrieval failed: {}", e.getMessage());
            visualDocs = List.of();
        }
        Set<String> visualNodeIds = visualDocs.stream().map(d -> (String)d.getMetadata().get("visualNodeId")).filter(Objects::nonNull).collect(Collectors.toSet());
        List<CrossModalEdge> relevantEdges = new ArrayList<>();
        if (!visualNodeIds.isEmpty()) {
            Query edgeQuery = new Query((CriteriaDefinition)Criteria.where((String)"visualNodeId").in(visualNodeIds).and("workspaceId").is(workspaceId));
            relevantEdges = this.mongoTemplate.find(edgeQuery, CrossModalEdge.class, CROSS_MODAL_EDGES_COLLECTION);
        }
        Set<String> linkedNodeIds = relevantEdges.stream().map(CrossModalEdge::visualNodeId).collect(Collectors.toSet());
        ArrayList<ScoredDocument> scoredResults = new ArrayList<ScoredDocument>();
        for (i = 0; i < textDocs.size(); ++i) {
            doc = (Document)textDocs.get(i);
            double score = this.textWeight * (1.0 - (double)i * 0.05);
            scoredResults.add(new ScoredDocument(doc, score, "text"));
        }
        for (i = 0; i < visualDocs.size(); ++i) {
            doc = (Document)visualDocs.get(i);
            String nodeId = (String)doc.getMetadata().get("visualNodeId");
            boolean hasCrossModalLink = nodeId != null && linkedNodeIds.contains(nodeId);
            double score = this.visualWeight * (1.0 - (double)i * 0.05);
            if (hasCrossModalLink) {
                score *= 1.3;
            }
            scoredResults.add(new ScoredDocument(doc, score, "visual"));
        }
        scoredResults.sort((a, b) -> Double.compare(b.score(), a.score()));
        long elapsed = System.currentTimeMillis() - startTime;
        this.reasoningTracer.addStep(ReasoningStep.StepType.CROSS_MODAL_RETRIEVAL, "MegaRAG Cross-Modal Retrieval", String.format("Found %d text + %d visual docs, %d cross-modal edges", textDocs.size(), visualDocs.size(), relevantEdges.size()), elapsed, Map.of("textDocs", textDocs.size(), "visualDocs", visualDocs.size(), "crossModalEdges", relevantEdges.size()));
        log.info("MegaRAG: Retrieved {} text + {} visual docs with {} edges in {}ms", new Object[]{textDocs.size(), visualDocs.size(), relevantEdges.size(), elapsed});
        return new CrossModalRetrievalResult(scoredResults.stream().map(ScoredDocument::document).toList(), visualDocs, relevantEdges);
    }

    public String generateWithVisualContext(String query, List<Document> textDocs, List<Document> visualDocs) {
        String source;
        if (!this.enabled || textDocs.isEmpty() && visualDocs.isEmpty()) {
            return null;
        }
        StringBuilder context = new StringBuilder();
        if (!textDocs.isEmpty()) {
            context.append("=== TEXT SOURCES ===\n");
            for (Document doc : textDocs) {
                source = String.valueOf(doc.getMetadata().getOrDefault("source", "Unknown"));
                context.append("SOURCE: ").append(source).append("\n");
                context.append(doc.getContent()).append("\n\n");
            }
        }
        if (!visualDocs.isEmpty()) {
            context.append("=== VISUAL SOURCES ===\n");
            for (Document doc : visualDocs) {
                source = String.valueOf(doc.getMetadata().getOrDefault("source", "Unknown"));
                String imageType = String.valueOf(doc.getMetadata().getOrDefault("imageType", "UNKNOWN"));
                String extractedText = String.valueOf(doc.getMetadata().getOrDefault("extractedText", ""));
                context.append("VISUAL SOURCE: ").append(source).append(" [").append(imageType).append("]\n");
                context.append("DESCRIPTION: ").append(doc.getContent()).append("\n");
                if (!extractedText.isBlank()) {
                    context.append("EXTRACTED TEXT: ").append(extractedText).append("\n");
                }
                context.append("\n");
            }
        }
        String prompt = "You are SENTINEL with multimodal intelligence capabilities.\n\nYou have access to both TEXT and VISUAL sources. When answering:\n1. Cite text sources as [filename.ext]\n2. Cite visual sources as [IMAGE: filename.ext]\n3. If information comes from a chart/diagram, describe what it shows\n4. Combine insights from both modalities when relevant\n\nCONTEXT:\n%s\n\nQUERY: %s\n".formatted(context.toString(), query);
        try {
            String response = this.chatClient.prompt().user(prompt).call().content();
            return response;
        }
        catch (Exception e) {
            log.error("MegaRAG: Generation failed: {}", e.getMessage());
            return null;
        }
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    private String normalizeDepartment(String department) {
        if (department == null) {
            return null;
        }
        try {
            return Department.valueOf(department.trim().toUpperCase()).name();
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }

    public record VisualIngestionResult(boolean success, String message, List<VisualEntity> entities, String nodeId) {
    }

    public record ImageAnalysis(ImageType imageType, String description, String extractedText, List<VisualEntity> entities, Map<String, Object> chartData) {
    }

    public record VisualNode(String id, String filename, ImageType imageType, String description, String extractedText, List<String> entities, String department, String workspaceId, long timestamp) {
    }

    public static enum ImageType {
        CHART_BAR,
        CHART_LINE,
        CHART_PIE,
        DIAGRAM_FLOWCHART,
        DIAGRAM_ARCHITECTURE,
        DIAGRAM_ORG_CHART,
        MAP,
        PHOTO,
        SCREENSHOT,
        TABLE,
        UNKNOWN;

    }

    public record CrossModalEdge(String id, String visualNodeId, String textEntityName, String visualEntityName, double similarity, String relationshipType, String workspaceId) {
    }

    public record CrossModalRetrievalResult(List<Document> mergedResults, List<Document> visualDocs, List<CrossModalEdge> crossModalEdges) {
    }

    public record ScoredDocument(Document document, double score, String modality) {
    }

    public record VisualEntity(String name, String type, double confidence, Map<String, Object> attributes) {
    }
}
