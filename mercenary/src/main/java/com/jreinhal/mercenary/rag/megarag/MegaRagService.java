package com.jreinhal.mercenary.rag.megarag;

import com.jreinhal.mercenary.reasoning.ReasoningTracer;
import com.jreinhal.mercenary.reasoning.ReasoningStep.StepType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MegaRAG: Multimodal Knowledge Graph-Based Retrieval Augmented Generation
 * Based on research paper arXiv:2512.20626
 *
 * This service enables cross-modal reasoning by:
 * 1. VISUAL INGESTION: Process images/charts with vision models
 * 2. MULTIMODAL KG: Build knowledge graph linking text and visual entities
 * 3. CROSS-MODAL RETRIEVAL: Retrieve based on text AND visual similarity
 * 4. UNIFIED GENERATION: Generate responses using both modalities
 *
 * Supports:
 * - Chart understanding (trends, comparisons, data extraction)
 * - Diagram retrieval (architecture, flowcharts, org charts)
 * - Image-text entity linking
 * - Visual question answering
 */
@Service
public class MegaRagService {

    private static final Logger log = LoggerFactory.getLogger(MegaRagService.class);

    private final VectorStore vectorStore;
    private final MongoTemplate mongoTemplate;
    private final ChatClient chatClient;
    private final ImageAnalyzer imageAnalyzer;
    private final VisualEntityLinker visualEntityLinker;
    private final ReasoningTracer reasoningTracer;

    @Value("${sentinel.megarag.enabled:true}")
    private boolean enabled;

    @Value("${sentinel.megarag.visual-weight:0.3}")
    private double visualWeight;

    @Value("${sentinel.megarag.text-weight:0.7}")
    private double textWeight;

    @Value("${sentinel.megarag.cross-modal-threshold:0.5}")
    private double crossModalThreshold;

    // MongoDB collections for multimodal KG
    private static final String VISUAL_NODES_COLLECTION = "megarag_visual_nodes";
    private static final String CROSS_MODAL_EDGES_COLLECTION = "megarag_cross_modal_edges";

    public MegaRagService(VectorStore vectorStore,
                          MongoTemplate mongoTemplate,
                          ChatClient.Builder chatClientBuilder,
                          ImageAnalyzer imageAnalyzer,
                          VisualEntityLinker visualEntityLinker,
                          ReasoningTracer reasoningTracer) {
        this.vectorStore = vectorStore;
        this.mongoTemplate = mongoTemplate;
        this.chatClient = chatClientBuilder.build();
        this.imageAnalyzer = imageAnalyzer;
        this.visualEntityLinker = visualEntityLinker;
        this.reasoningTracer = reasoningTracer;
    }

    @PostConstruct
    public void init() {
        log.info("MegaRAG Service initialized (enabled={}, visualWeight={}, textWeight={})",
                enabled, visualWeight, textWeight);
    }

    /**
     * Ingest a visual asset (image, chart, diagram) into the multimodal KG.
     *
     * @param imageBytes Raw image bytes
     * @param filename Source filename
     * @param department Security department
     * @param contextText Optional surrounding text context
     * @return Ingestion result with extracted entities
     */
    public VisualIngestionResult ingestVisualAsset(byte[] imageBytes, String filename,
                                                    String department, String contextText) {
        if (!enabled) {
            return new VisualIngestionResult(false, "MegaRAG disabled", List.of(), null);
        }

        long startTime = System.currentTimeMillis();

        try {
            // Step 1: Analyze image with vision model
            ImageAnalysis analysis = imageAnalyzer.analyze(imageBytes, filename);

            // Step 2: Extract visual entities
            List<VisualEntity> visualEntities = analysis.entities();

            // Step 3: Create visual node in KG
            VisualNode node = new VisualNode(
                    UUID.randomUUID().toString(),
                    filename,
                    analysis.imageType(),
                    analysis.description(),
                    analysis.extractedText(),
                    visualEntities.stream().map(VisualEntity::name).toList(),
                    department,
                    System.currentTimeMillis()
            );
            mongoTemplate.save(node, VISUAL_NODES_COLLECTION);

            // Step 4: Link visual entities to text entities
            if (contextText != null && !contextText.isBlank()) {
                List<CrossModalEdge> edges = visualEntityLinker.linkEntities(
                        visualEntities, contextText, node.id());
                for (CrossModalEdge edge : edges) {
                    mongoTemplate.save(edge, CROSS_MODAL_EDGES_COLLECTION);
                }
            }

            // Step 5: Store visual embedding for retrieval
            Document visualDoc = new Document(analysis.description());
            visualDoc.getMetadata().put("source", filename);
            visualDoc.getMetadata().put("dept", department);
            visualDoc.getMetadata().put("type", "visual");
            visualDoc.getMetadata().put("imageType", analysis.imageType().name());
            visualDoc.getMetadata().put("visualNodeId", node.id());
            visualDoc.getMetadata().put("extractedText", analysis.extractedText());
            vectorStore.add(List.of(visualDoc));

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("MegaRAG: Ingested visual asset '{}' ({}) with {} entities in {}ms",
                    filename, analysis.imageType(), visualEntities.size(), elapsed);

            return new VisualIngestionResult(true, "Success", visualEntities, node.id());

        } catch (Exception e) {
            log.error("MegaRAG: Visual ingestion failed for '{}': {}", filename, e.getMessage(), e);
            return new VisualIngestionResult(false, e.getMessage(), List.of(), null);
        }
    }

    /**
     * Perform cross-modal retrieval combining text and visual matches.
     *
     * @param query User's query
     * @param department Security department filter
     * @return Cross-modal retrieval result
     */
    public CrossModalRetrievalResult retrieve(String query, String department) {
        if (!enabled) {
            return new CrossModalRetrievalResult(List.of(), List.of(), List.of());
        }

        long startTime = System.currentTimeMillis();

        // Step 1: Text-based retrieval
        List<Document> textDocs = vectorStore.similaritySearch(
                SearchRequest.query(query)
                        .withTopK(15)
                        .withSimilarityThreshold(0.3)
                        .withFilterExpression("dept == '" + department + "' && type != 'visual'"));

        // Step 2: Visual retrieval (semantic match on descriptions)
        List<Document> visualDocs = vectorStore.similaritySearch(
                SearchRequest.query(query)
                        .withTopK(10)
                        .withSimilarityThreshold(0.3)
                        .withFilterExpression("dept == '" + department + "' && type == 'visual'"));

        // Step 3: Cross-modal linking - find text docs linked to visual docs
        Set<String> visualNodeIds = visualDocs.stream()
                .map(d -> (String) d.getMetadata().get("visualNodeId"))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<CrossModalEdge> relevantEdges = new ArrayList<>();
        if (!visualNodeIds.isEmpty()) {
            Query edgeQuery = new Query(Criteria.where("visualNodeId").in(visualNodeIds));
            relevantEdges = mongoTemplate.find(edgeQuery, CrossModalEdge.class, CROSS_MODAL_EDGES_COLLECTION);
        }

        // Step 4: Score and merge results
        List<ScoredDocument> scoredResults = new ArrayList<>();

        // Score text documents
        for (int i = 0; i < textDocs.size(); i++) {
            Document doc = textDocs.get(i);
            double score = textWeight * (1.0 - (i * 0.05)); // Decay by rank
            scoredResults.add(new ScoredDocument(doc, score, "text"));
        }

        // Score visual documents (boost if cross-modal links exist)
        for (int i = 0; i < visualDocs.size(); i++) {
            Document doc = visualDocs.get(i);
            String nodeId = (String) doc.getMetadata().get("visualNodeId");
            boolean hasCrossModalLink = relevantEdges.stream()
                    .anyMatch(e -> e.visualNodeId().equals(nodeId));

            double score = visualWeight * (1.0 - (i * 0.05));
            if (hasCrossModalLink) {
                score *= 1.3; // Boost cross-modal linked visuals
            }
            scoredResults.add(new ScoredDocument(doc, score, "visual"));
        }

        // Sort by score
        scoredResults.sort((a, b) -> Double.compare(b.score(), a.score()));

        long elapsed = System.currentTimeMillis() - startTime;

        // Add reasoning step
        reasoningTracer.addStep(StepType.CROSS_MODAL_RETRIEVAL,
                "MegaRAG Cross-Modal Retrieval",
                String.format("Found %d text + %d visual docs, %d cross-modal edges",
                        textDocs.size(), visualDocs.size(), relevantEdges.size()),
                elapsed,
                Map.of("textDocs", textDocs.size(),
                       "visualDocs", visualDocs.size(),
                       "crossModalEdges", relevantEdges.size()));

        log.info("MegaRAG: Retrieved {} text + {} visual docs with {} edges in {}ms",
                textDocs.size(), visualDocs.size(), relevantEdges.size(), elapsed);

        return new CrossModalRetrievalResult(
                scoredResults.stream().map(ScoredDocument::document).toList(),
                visualDocs,
                relevantEdges);
    }

    /**
     * Generate a visual-aware response combining text and visual context.
     */
    public String generateWithVisualContext(String query, List<Document> textDocs,
                                            List<Document> visualDocs) {
        if (!enabled || (textDocs.isEmpty() && visualDocs.isEmpty())) {
            return null;
        }

        StringBuilder context = new StringBuilder();

        // Add text context
        if (!textDocs.isEmpty()) {
            context.append("=== TEXT SOURCES ===\n");
            for (Document doc : textDocs) {
                String source = (String) doc.getMetadata().getOrDefault("source", "Unknown");
                context.append("SOURCE: ").append(source).append("\n");
                context.append(doc.getContent()).append("\n\n");
            }
        }

        // Add visual context
        if (!visualDocs.isEmpty()) {
            context.append("=== VISUAL SOURCES ===\n");
            for (Document doc : visualDocs) {
                String source = (String) doc.getMetadata().getOrDefault("source", "Unknown");
                String imageType = (String) doc.getMetadata().getOrDefault("imageType", "UNKNOWN");
                String extractedText = (String) doc.getMetadata().getOrDefault("extractedText", "");

                context.append("VISUAL SOURCE: ").append(source).append(" [").append(imageType).append("]\n");
                context.append("DESCRIPTION: ").append(doc.getContent()).append("\n");
                if (!extractedText.isBlank()) {
                    context.append("EXTRACTED TEXT: ").append(extractedText).append("\n");
                }
                context.append("\n");
            }
        }

        String prompt = """
                You are SENTINEL with multimodal intelligence capabilities.

                You have access to both TEXT and VISUAL sources. When answering:
                1. Cite text sources as [filename.ext]
                2. Cite visual sources as [IMAGE: filename.ext]
                3. If information comes from a chart/diagram, describe what it shows
                4. Combine insights from both modalities when relevant

                CONTEXT:
                %s

                QUERY: %s
                """.formatted(context.toString(), query);

        try {
            @SuppressWarnings("deprecation")
            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
            return response;
        } catch (Exception e) {
            log.error("MegaRAG: Generation failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Check if MegaRAG is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    // ==================== Record Types ====================

    public record VisualIngestionResult(
            boolean success,
            String message,
            List<VisualEntity> entities,
            String nodeId) {}

    public record CrossModalRetrievalResult(
            List<Document> mergedResults,
            List<Document> visualDocs,
            List<CrossModalEdge> crossModalEdges) {}

    public record ScoredDocument(
            Document document,
            double score,
            String modality) {}

    public record VisualNode(
            String id,
            String filename,
            ImageType imageType,
            String description,
            String extractedText,
            List<String> entities,
            String department,
            long timestamp) {}

    public record VisualEntity(
            String name,
            String type,
            double confidence,
            Map<String, Object> attributes) {}

    public record CrossModalEdge(
            String id,
            String visualNodeId,
            String textEntityName,
            String visualEntityName,
            double similarity,
            String relationshipType) {}

    public enum ImageType {
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
        UNKNOWN
    }

    public record ImageAnalysis(
            ImageType imageType,
            String description,
            String extractedText,
            List<VisualEntity> entities,
            Map<String, Object> chartData) {}
}
