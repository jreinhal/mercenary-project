package com.jreinhal.mercenary.rag.miarag;

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
 * MiA-RAG: Mindscape-Aware Retrieval Augmented Generation
 * Based on research paper arXiv:2512.17220
 *
 * This service improves long-context understanding by:
 * 1. MINDSCAPE BUILDING: Create hierarchical summaries at ingestion time
 * 2. GLOBAL CONTEXT: Condition retriever on document-level semantics
 * 3. MINDSCAPE-AWARE RETRIEVAL: Align local chunks with global understanding
 * 4. COHERENT GENERATION: Generate within the mindscape context
 *
 * Key Concepts:
 * - Mindscape: A hierarchical representation of document semantics
 * - Level 0: Original chunks
 * - Level 1: Paragraph-level summaries
 * - Level 2: Section-level summaries
 * - Level 3: Document-level summary (the "mindscape")
 */
@Service
public class MiARagService {

    private static final Logger log = LoggerFactory.getLogger(MiARagService.class);

    private final VectorStore vectorStore;
    private final MongoTemplate mongoTemplate;
    private final ChatClient chatClient;
    private final MindscapeBuilder mindscapeBuilder;
    private final ReasoningTracer reasoningTracer;

    @Value("${sentinel.miarag.enabled:true}")
    private boolean enabled;

    @Value("${sentinel.miarag.hierarchy-levels:3}")
    private int hierarchyLevels;

    @Value("${sentinel.miarag.global-context-weight:0.3}")
    private double globalContextWeight;

    @Value("${sentinel.miarag.local-context-weight:0.7}")
    private double localContextWeight;

    // MongoDB collection for mindscapes
    private static final String MINDSCAPE_COLLECTION = "miarag_mindscapes";

    public MiARagService(VectorStore vectorStore,
                         MongoTemplate mongoTemplate,
                         ChatClient.Builder chatClientBuilder,
                         MindscapeBuilder mindscapeBuilder,
                         ReasoningTracer reasoningTracer) {
        this.vectorStore = vectorStore;
        this.mongoTemplate = mongoTemplate;
        this.chatClient = chatClientBuilder.build();
        this.mindscapeBuilder = mindscapeBuilder;
        this.reasoningTracer = reasoningTracer;
    }

    @PostConstruct
    public void init() {
        log.info("MiA-RAG Service initialized (enabled={}, levels={}, globalWeight={})",
                enabled, hierarchyLevels, globalContextWeight);
    }

    /**
     * Build a mindscape for a document during ingestion.
     * Creates hierarchical summaries from chunks to document level.
     *
     * @param chunks Document chunks to process
     * @param filename Source document name
     * @param department Security department
     * @return The created mindscape
     */
    public Mindscape buildMindscape(List<String> chunks, String filename, String department) {
        if (!enabled || chunks.isEmpty()) {
            return null;
        }

        long startTime = System.currentTimeMillis();
        log.info("MiA-RAG: Building mindscape for '{}' with {} chunks", filename, chunks.size());

        try {
            // Build hierarchical summaries
            List<List<String>> hierarchy = new ArrayList<>();
            hierarchy.add(chunks); // Level 0: original chunks

            List<String> currentLevel = chunks;
            for (int level = 1; level <= hierarchyLevels; level++) {
                List<String> summaries = mindscapeBuilder.summarizeLevel(currentLevel, level);
                hierarchy.add(summaries);
                currentLevel = summaries;

                log.debug("Level {}: {} summaries", level, summaries.size());

                // Stop if we've collapsed to a single summary
                if (summaries.size() <= 1) {
                    break;
                }
            }

            // The final level is the document mindscape
            String documentMindscape = currentLevel.isEmpty() ? "" : currentLevel.get(0);

            // Extract key concepts from the mindscape
            List<String> keyConcepts = mindscapeBuilder.extractKeyConcepts(documentMindscape);

            // Create and store mindscape
            Mindscape mindscape = new Mindscape(
                    UUID.randomUUID().toString(),
                    filename,
                    department,
                    documentMindscape,
                    hierarchy,
                    keyConcepts,
                    chunks.size(),
                    System.currentTimeMillis()
            );

            mongoTemplate.save(mindscape, MINDSCAPE_COLLECTION);

            // Store mindscape as a searchable document
            Document mindscapeDoc = new Document(documentMindscape);
            mindscapeDoc.getMetadata().put("source", filename);
            mindscapeDoc.getMetadata().put("dept", department);
            mindscapeDoc.getMetadata().put("type", "mindscape");
            mindscapeDoc.getMetadata().put("mindscapeId", mindscape.id());
            mindscapeDoc.getMetadata().put("keyConcepts", String.join(", ", keyConcepts));
            vectorStore.add(List.of(mindscapeDoc));

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("MiA-RAG: Built mindscape for '{}' with {} levels in {}ms",
                    filename, hierarchy.size(), elapsed);

            return mindscape;

        } catch (Exception e) {
            log.error("MiA-RAG: Failed to build mindscape for '{}': {}", filename, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Perform mindscape-aware retrieval.
     * Combines global (mindscape) and local (chunk) context.
     *
     * @param query User's query
     * @param department Security department filter
     * @return Retrieval result with global context
     */
    public MindscapeRetrievalResult retrieve(String query, String department) {
        if (!enabled) {
            // Fallback to standard retrieval
            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.query(query)
                            .withTopK(10)
                            .withSimilarityThreshold(0.3)
                            .withFilterExpression("dept == '" + department + "'"));
            return new MindscapeRetrievalResult(docs, null, List.of());
        }

        long startTime = System.currentTimeMillis();

        // Step 1: Find relevant mindscapes (document-level context)
        List<Document> mindscapeDocs = vectorStore.similaritySearch(
                SearchRequest.query(query)
                        .withTopK(3)
                        .withSimilarityThreshold(0.4)
                        .withFilterExpression("dept == '" + department + "' && type == 'mindscape'"));

        // Get full mindscape data
        List<Mindscape> relevantMindscapes = new ArrayList<>();
        for (Document doc : mindscapeDocs) {
            String mindscapeId = (String) doc.getMetadata().get("mindscapeId");
            if (mindscapeId != null) {
                Query q = new Query(Criteria.where("id").is(mindscapeId));
                Mindscape ms = mongoTemplate.findOne(q, Mindscape.class, MINDSCAPE_COLLECTION);
                if (ms != null) {
                    relevantMindscapes.add(ms);
                }
            }
        }

        // Build global context from mindscapes
        String globalContext = buildGlobalContext(relevantMindscapes);

        // Step 2: Retrieve local chunks, boosted by mindscape relevance
        Set<String> relevantSources = relevantMindscapes.stream()
                .map(Mindscape::filename)
                .collect(Collectors.toSet());

        List<Document> localDocs = vectorStore.similaritySearch(
                SearchRequest.query(query)
                        .withTopK(15)
                        .withSimilarityThreshold(0.25)
                        .withFilterExpression("dept == '" + department + "' && type != 'mindscape'"));

        // Boost scores for chunks from documents with relevant mindscapes
        List<ScoredChunk> scoredChunks = new ArrayList<>();
        for (int i = 0; i < localDocs.size(); i++) {
            Document doc = localDocs.get(i);
            String source = (String) doc.getMetadata().get("source");
            double baseScore = 1.0 - (i * 0.05);

            // Boost if from a relevant mindscape document
            if (relevantSources.contains(source)) {
                baseScore *= 1.3;
            }

            scoredChunks.add(new ScoredChunk(doc, baseScore));
        }

        // Sort and take top results
        scoredChunks.sort((a, b) -> Double.compare(b.score(), a.score()));
        List<Document> finalDocs = scoredChunks.stream()
                .limit(10)
                .map(ScoredChunk::document)
                .toList();

        long elapsed = System.currentTimeMillis() - startTime;

        // Add reasoning step
        reasoningTracer.addStep(StepType.MINDSCAPE_RETRIEVAL,
                "MiA-RAG Mindscape-Aware Retrieval",
                String.format("Found %d mindscapes, %d local chunks (boosted %d)",
                        relevantMindscapes.size(), localDocs.size(), relevantSources.size()),
                elapsed,
                Map.of("mindscapes", relevantMindscapes.size(),
                       "localChunks", localDocs.size(),
                       "globalContextLength", globalContext.length()));

        log.info("MiA-RAG: Retrieved with {} mindscapes, {} local docs in {}ms",
                relevantMindscapes.size(), finalDocs.size(), elapsed);

        return new MindscapeRetrievalResult(finalDocs, globalContext, relevantMindscapes);
    }

    /**
     * Generate response with mindscape context for coherence.
     */
    public String generateWithMindscape(String query, List<Document> localDocs,
                                         String globalContext) {
        if (!enabled || localDocs.isEmpty()) {
            return null;
        }

        StringBuilder context = new StringBuilder();

        // Add global mindscape context first
        if (globalContext != null && !globalContext.isBlank()) {
            context.append("=== DOCUMENT OVERVIEW (Global Context) ===\n");
            context.append(globalContext);
            context.append("\n\n");
        }

        // Add local chunk context
        context.append("=== DETAILED SOURCES ===\n");
        for (Document doc : localDocs) {
            String source = (String) doc.getMetadata().getOrDefault("source", "Unknown");
            context.append("SOURCE: ").append(source).append("\n");
            context.append(doc.getContent()).append("\n\n");
        }

        String prompt = """
                You are SENTINEL with enhanced long-document understanding.

                You have access to:
                1. DOCUMENT OVERVIEW: High-level summary providing global context
                2. DETAILED SOURCES: Specific passages with precise information

                When answering:
                - Use the overview to understand the big picture
                - Cite specific sources with [filename.ext]
                - Ensure your answer is coherent with the overall document context
                - Connect local details to the broader narrative when relevant

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
            log.error("MiA-RAG: Generation failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Build global context from relevant mindscapes.
     */
    private String buildGlobalContext(List<Mindscape> mindscapes) {
        if (mindscapes.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (Mindscape ms : mindscapes) {
            sb.append("Document: ").append(ms.filename()).append("\n");
            sb.append("Summary: ").append(ms.documentSummary()).append("\n");
            if (!ms.keyConcepts().isEmpty()) {
                sb.append("Key Concepts: ").append(String.join(", ", ms.keyConcepts())).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Get mindscape for a specific document.
     */
    public Mindscape getMindscape(String filename, String department) {
        Query query = new Query(Criteria.where("filename").is(filename)
                .and("department").is(department));
        return mongoTemplate.findOne(query, Mindscape.class, MINDSCAPE_COLLECTION);
    }

    /**
     * Check if MiA-RAG is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    // ==================== Record Types ====================

    public record Mindscape(
            String id,
            String filename,
            String department,
            String documentSummary,
            List<List<String>> hierarchy,
            List<String> keyConcepts,
            int chunkCount,
            long timestamp) {}

    public record MindscapeRetrievalResult(
            List<Document> localDocs,
            String globalContext,
            List<Mindscape> mindscapes) {}

    public record ScoredChunk(
            Document document,
            double score) {}
}
