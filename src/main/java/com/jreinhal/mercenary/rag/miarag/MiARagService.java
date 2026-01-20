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
 *  org.springframework.ai.vectorstore.SearchRequest
 *  org.springframework.ai.vectorstore.VectorStore
 *  org.springframework.beans.factory.annotation.Value
 *  org.springframework.data.mongodb.core.MongoTemplate
 *  org.springframework.data.mongodb.core.query.Criteria
 *  org.springframework.data.mongodb.core.query.CriteriaDefinition
 *  org.springframework.data.mongodb.core.query.Query
 *  org.springframework.stereotype.Service
 */
package com.jreinhal.mercenary.rag.miarag;

import com.jreinhal.mercenary.rag.miarag.MindscapeBuilder;
import com.jreinhal.mercenary.reasoning.ReasoningStep;
import com.jreinhal.mercenary.reasoning.ReasoningTracer;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.CriteriaDefinition;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

@Service
public class MiARagService {
    private static final Logger log = LoggerFactory.getLogger(MiARagService.class);
    private final VectorStore vectorStore;
    private final MongoTemplate mongoTemplate;
    private final ChatClient chatClient;
    private final MindscapeBuilder mindscapeBuilder;
    private final ReasoningTracer reasoningTracer;
    @Value(value="${sentinel.miarag.enabled:true}")
    private boolean enabled;
    @Value(value="${sentinel.miarag.hierarchy-levels:3}")
    private int hierarchyLevels;
    @Value(value="${sentinel.miarag.global-context-weight:0.3}")
    private double globalContextWeight;
    @Value(value="${sentinel.miarag.local-context-weight:0.7}")
    private double localContextWeight;
    private static final String MINDSCAPE_COLLECTION = "miarag_mindscapes";

    public MiARagService(VectorStore vectorStore, MongoTemplate mongoTemplate, ChatClient.Builder chatClientBuilder, MindscapeBuilder mindscapeBuilder, ReasoningTracer reasoningTracer) {
        this.vectorStore = vectorStore;
        this.mongoTemplate = mongoTemplate;
        this.chatClient = chatClientBuilder.build();
        this.mindscapeBuilder = mindscapeBuilder;
        this.reasoningTracer = reasoningTracer;
    }

    @PostConstruct
    public void init() {
        log.info("MiA-RAG Service initialized (enabled={}, levels={}, globalWeight={})", new Object[]{this.enabled, this.hierarchyLevels, this.globalContextWeight});
    }

    public Mindscape buildMindscape(List<String> chunks, String filename, String department) {
        if (!this.enabled || chunks.isEmpty()) {
            return null;
        }
        long startTime = System.currentTimeMillis();
        log.info("MiA-RAG: Building mindscape for '{}' with {} chunks", filename, chunks.size());
        try {
            ArrayList<List<String>> hierarchy = new ArrayList<List<String>>();
            hierarchy.add(chunks);
            List<String> currentLevel = chunks;
            for (int level = 1; level <= this.hierarchyLevels; ++level) {
                List<String> summaries = this.mindscapeBuilder.summarizeLevel(currentLevel, level);
                hierarchy.add(summaries);
                currentLevel = summaries;
                log.debug("Level {}: {} summaries", level, summaries.size());
                if (summaries.size() <= 1) break;
            }
            String documentMindscape = currentLevel.isEmpty() ? "" : currentLevel.get(0);
            List<String> keyConcepts = this.mindscapeBuilder.extractKeyConcepts(documentMindscape);
            Mindscape mindscape = new Mindscape(UUID.randomUUID().toString(), filename, department, documentMindscape, hierarchy, keyConcepts, chunks.size(), System.currentTimeMillis());
            this.mongoTemplate.save(mindscape, MINDSCAPE_COLLECTION);
            Document mindscapeDoc = new Document(documentMindscape);
            mindscapeDoc.getMetadata().put("source", filename);
            mindscapeDoc.getMetadata().put("dept", department);
            mindscapeDoc.getMetadata().put("type", "mindscape");
            mindscapeDoc.getMetadata().put("mindscapeId", mindscape.id());
            mindscapeDoc.getMetadata().put("keyConcepts", String.join((CharSequence)", ", keyConcepts));
            this.vectorStore.add(List.of(mindscapeDoc));
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("MiA-RAG: Built mindscape for '{}' with {} levels in {}ms", new Object[]{filename, hierarchy.size(), elapsed});
            return mindscape;
        }
        catch (Exception e) {
            log.error("MiA-RAG: Failed to build mindscape for '{}': {}", new Object[]{filename, e.getMessage(), e});
            return null;
        }
    }

    public MindscapeRetrievalResult retrieve(String query, String department) {
        if (!this.enabled) {
            List<Document> docs = this.vectorStore.similaritySearch(SearchRequest.query((String)query).withTopK(10).withSimilarityThreshold(0.3).withFilterExpression("dept == '" + department + "'"));
            return new MindscapeRetrievalResult(docs, null, List.of());
        }
        long startTime = System.currentTimeMillis();
        List<Document> mindscapeDocs = this.vectorStore.similaritySearch(SearchRequest.query((String)query).withTopK(3).withSimilarityThreshold(0.4).withFilterExpression("dept == '" + department + "' && type == 'mindscape'"));
        ArrayList<Mindscape> relevantMindscapes = new ArrayList<Mindscape>();
        for (Document doc : mindscapeDocs) {
            Query q;
            Mindscape ms;
            String mindscapeId = (String)doc.getMetadata().get("mindscapeId");
            if (mindscapeId == null || (ms = (Mindscape)this.mongoTemplate.findOne(q = new Query((CriteriaDefinition)Criteria.where((String)"id").is(mindscapeId)), Mindscape.class, MINDSCAPE_COLLECTION)) == null) continue;
            relevantMindscapes.add(ms);
        }
        String globalContext = this.buildGlobalContext(relevantMindscapes);
        Set<String> relevantSources = relevantMindscapes.stream().map(Mindscape::filename).collect(Collectors.toSet());
        List<Document> localDocs = this.vectorStore.similaritySearch(SearchRequest.query((String)query).withTopK(15).withSimilarityThreshold(0.25).withFilterExpression("dept == '" + department + "' && type != 'mindscape'"));
        ArrayList<ScoredChunk> scoredChunks = new ArrayList<ScoredChunk>();
        for (int i = 0; i < localDocs.size(); ++i) {
            Document doc = localDocs.get(i);
            String source = (String)doc.getMetadata().get("source");
            double baseScore = 1.0 - (double)i * 0.05;
            if (relevantSources.contains(source)) {
                baseScore *= 1.3;
            }
            scoredChunks.add(new ScoredChunk(doc, baseScore));
        }
        scoredChunks.sort((a, b) -> Double.compare(b.score(), a.score()));
        List<Document> finalDocs = scoredChunks.stream().limit(10L).map(ScoredChunk::document).toList();
        long elapsed = System.currentTimeMillis() - startTime;
        this.reasoningTracer.addStep(ReasoningStep.StepType.MINDSCAPE_RETRIEVAL, "MiA-RAG Mindscape-Aware Retrieval", String.format("Found %d mindscapes, %d local chunks (boosted %d)", relevantMindscapes.size(), localDocs.size(), relevantSources.size()), elapsed, Map.of("mindscapes", relevantMindscapes.size(), "localChunks", localDocs.size(), "globalContextLength", globalContext.length()));
        log.info("MiA-RAG: Retrieved with {} mindscapes, {} local docs in {}ms", new Object[]{relevantMindscapes.size(), finalDocs.size(), elapsed});
        return new MindscapeRetrievalResult(finalDocs, globalContext, relevantMindscapes);
    }

    public String generateWithMindscape(String query, List<Document> localDocs, String globalContext) {
        if (!this.enabled || localDocs.isEmpty()) {
            return null;
        }
        StringBuilder context = new StringBuilder();
        if (globalContext != null && !globalContext.isBlank()) {
            context.append("=== DOCUMENT OVERVIEW (Global Context) ===\n");
            context.append(globalContext);
            context.append("\n\n");
        }
        context.append("=== DETAILED SOURCES ===\n");
        for (Document doc : localDocs) {
            String source = String.valueOf(doc.getMetadata().getOrDefault("source", "Unknown"));
            context.append("SOURCE: ").append(source).append("\n");
            context.append(doc.getContent()).append("\n\n");
        }
        String prompt = "You are SENTINEL with enhanced long-document understanding.\n\nYou have access to:\n1. DOCUMENT OVERVIEW: High-level summary providing global context\n2. DETAILED SOURCES: Specific passages with precise information\n\nWhen answering:\n- Use the overview to understand the big picture\n- Cite specific sources with [filename.ext]\n- Ensure your answer is coherent with the overall document context\n- Connect local details to the broader narrative when relevant\n\nCONTEXT:\n%s\n\nQUERY: %s\n".formatted(context.toString(), query);
        try {
            String response = this.chatClient.prompt().user(prompt).call().content();
            return response;
        }
        catch (Exception e) {
            log.error("MiA-RAG: Generation failed: {}", e.getMessage());
            return null;
        }
    }

    private String buildGlobalContext(List<Mindscape> mindscapes) {
        if (mindscapes.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Mindscape ms : mindscapes) {
            sb.append("Document: ").append(ms.filename()).append("\n");
            sb.append("Summary: ").append(ms.documentSummary()).append("\n");
            if (!ms.keyConcepts().isEmpty()) {
                sb.append("Key Concepts: ").append(String.join((CharSequence)", ", ms.keyConcepts())).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    public Mindscape getMindscape(String filename, String department) {
        Query query = new Query((CriteriaDefinition)Criteria.where((String)"filename").is(filename).and("department").is(department));
        return (Mindscape)this.mongoTemplate.findOne(query, Mindscape.class, MINDSCAPE_COLLECTION);
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public record Mindscape(String id, String filename, String department, String documentSummary, List<List<String>> hierarchy, List<String> keyConcepts, int chunkCount, long timestamp) {
    }

    public record MindscapeRetrievalResult(List<Document> localDocs, String globalContext, List<Mindscape> mindscapes) {
    }

    public record ScoredChunk(Document document, double score) {
    }
}
