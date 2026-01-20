/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  jakarta.annotation.PostConstruct
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.ai.document.Document
 *  org.springframework.ai.vectorstore.SearchRequest
 *  org.springframework.ai.vectorstore.VectorStore
 *  org.springframework.beans.factory.annotation.Value
 *  org.springframework.stereotype.Service
 */
package com.jreinhal.mercenary.rag.qucorag;

import com.jreinhal.mercenary.rag.qucorag.EntityExtractor;
import com.jreinhal.mercenary.rag.qucorag.InfiniGramClient;
import com.jreinhal.mercenary.rag.qucorag.LlmEntityExtractor;
import com.jreinhal.mercenary.reasoning.ReasoningStep;
import com.jreinhal.mercenary.reasoning.ReasoningTracer;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class QuCoRagService {
    private static final Logger log = LoggerFactory.getLogger(QuCoRagService.class);
    private final EntityExtractor entityExtractor;
    private final LlmEntityExtractor llmEntityExtractor;
    private final InfiniGramClient infiniGramClient;
    private final VectorStore vectorStore;
    private final ReasoningTracer reasoningTracer;
    private static final int MAX_QUERY_ENTITIES_FOR_COOC = 8;
    private static final int MAX_NOVEL_ENTITIES_FOR_COOC = 12;
    private static final int COOC_TOP_K = 20;
    private static final double COOC_SIMILARITY_THRESHOLD = 0.4;
    private static final int MAX_COOC_DOC_CHARS = 20000;
    @Value(value="${sentinel.qucorag.enabled:true}")
    private boolean enabled;
    @Value(value="${sentinel.qucorag.uncertainty-threshold:0.7}")
    private double uncertaintyThreshold;
    @Value(value="${sentinel.qucorag.low-frequency-threshold:1000}")
    private long lowFrequencyThreshold;
    @Value(value="${sentinel.qucorag.zero-cooccurrence-penalty:0.3}")
    private double zeroCoOccurrencePenalty;

    public QuCoRagService(EntityExtractor entityExtractor, LlmEntityExtractor llmEntityExtractor, InfiniGramClient infiniGramClient, VectorStore vectorStore, ReasoningTracer reasoningTracer) {
        this.entityExtractor = entityExtractor;
        this.llmEntityExtractor = llmEntityExtractor;
        this.infiniGramClient = infiniGramClient;
        this.vectorStore = vectorStore;
        this.reasoningTracer = reasoningTracer;
    }

    @PostConstruct
    public void init() {
        log.info("QuCo-RAG Service initialized (enabled={}, threshold={}, infiniGram={}, llmExtraction={})", new Object[]{this.enabled, this.uncertaintyThreshold, this.infiniGramClient.isEnabled(), this.llmEntityExtractor.isEnabled()});
    }

    private Set<String> extractEntities(String text) {
        if (this.llmEntityExtractor.isEnabled()) {
            return this.llmEntityExtractor.extractEntityStrings(text);
        }
        return this.entityExtractor.extractEntityStrings(text);
    }

    public UncertaintyResult analyzeQueryUncertainty(String query) {
        if (!this.enabled) {
            return new UncertaintyResult(0.0, List.of(), "QuCo-RAG disabled");
        }
        long startTime = System.currentTimeMillis();
        Set<String> entities = this.extractEntities(query);
        if (entities.isEmpty()) {
            return new UncertaintyResult(0.0, List.of(), "No entities detected");
        }
        ArrayList<EntityAnalysis> analyses = new ArrayList<EntityAnalysis>();
        double totalUncertainty = 0.0;
        for (String entity : entities) {
            EntityAnalysis analysis = this.analyzeEntity(entity);
            analyses.add(analysis);
            totalUncertainty += analysis.uncertaintyContribution();
        }
        double uncertaintyScore = Math.min(1.0, totalUncertainty / (double)entities.size());
        long elapsed = System.currentTimeMillis() - startTime;
        this.reasoningTracer.addStep(ReasoningStep.StepType.UNCERTAINTY_ANALYSIS, "QuCo-RAG Query Uncertainty", String.format("Analyzed %d entities, uncertainty=%.3f (threshold=%.2f)", entities.size(), uncertaintyScore, this.uncertaintyThreshold), elapsed, Map.of("entities", entities, "uncertaintyScore", uncertaintyScore, "shouldRetrieve", uncertaintyScore >= this.uncertaintyThreshold));
        String reason = uncertaintyScore >= this.uncertaintyThreshold ? "High uncertainty - additional retrieval recommended" : "Low uncertainty - generation can proceed";
        log.info("QuCo-RAG: Query uncertainty={:.3f} for {} entities ({})", new Object[]{uncertaintyScore, entities.size(), reason});
        return new UncertaintyResult(uncertaintyScore, new ArrayList<String>(entities), reason);
    }

    public HallucinationResult detectHallucinationRisk(String generatedText, String query) {
        if (!this.enabled) {
            return new HallucinationResult(0.0, List.of(), false);
        }
        long startTime = System.currentTimeMillis();
        Set<String> generatedEntities = this.extractEntities(generatedText);
        Set<String> queryEntities = this.extractEntities(query);
        HashSet<String> novelEntities = new HashSet<String>(generatedEntities);
        novelEntities.removeAll(queryEntities);
        if (novelEntities.isEmpty()) {
            return new HallucinationResult(0.0, List.of(), false);
        }
        if (queryEntities.isEmpty()) {
            return new HallucinationResult(0.0, List.of(), false);
        }
        ArrayList<String> flaggedEntities = new ArrayList<String>();
        int verifiedCount = 0;
        ArrayList<String> queryEntityList = new ArrayList<String>(queryEntities);
        queryEntityList.sort((a, b) -> Integer.compare(b.length(), a.length()));
        if (queryEntityList.size() > 8) {
            queryEntityList = new ArrayList<String>(queryEntityList.subList(0, 8));
        }
        ArrayList<String> novelEntityList = new ArrayList<String>(novelEntities);
        novelEntityList.sort((a, b) -> Integer.compare(b.length(), a.length()));
        if (novelEntityList.size() > 12) {
            novelEntityList = new ArrayList<String>(novelEntityList.subList(0, 12));
        }
        for (String novelEntity : novelEntityList) {
            boolean hasCoOccurrence = false;
            String novelLower = novelEntity.toLowerCase();
            try {
                List<Document> entityDocs = this.vectorStore.similaritySearch(SearchRequest.query((String)novelEntity).withTopK(20).withSimilarityThreshold(0.4));
                for (Document doc : entityDocs) {
                    String contentLower;
                    String content;
                    String string = content = doc.getContent() == null ? "" : doc.getContent();
                    if (content.length() > 20000) {
                        content = content.substring(0, 20000);
                    }
                    if (!(contentLower = content.toLowerCase()).contains(novelLower)) continue;
                    for (String queryEntity : queryEntityList) {
                        if (!contentLower.contains(queryEntity.toLowerCase())) continue;
                        hasCoOccurrence = true;
                        ++verifiedCount;
                        break;
                    }
                    if (!hasCoOccurrence) continue;
                    break;
                }
            }
            catch (Exception e) {
                log.warn("Co-occurrence check failed for novel entity '{}': {}", novelEntity, e.getMessage());
            }
            if (hasCoOccurrence) continue;
            flaggedEntities.add(novelEntity);
        }
        double riskScore = novelEntities.isEmpty() ? 0.0 : (double)flaggedEntities.size() / (double)novelEntities.size();
        boolean isHighRisk = riskScore >= this.uncertaintyThreshold && flaggedEntities.size() >= 3;
        long elapsed = System.currentTimeMillis() - startTime;
        this.reasoningTracer.addStep(ReasoningStep.StepType.UNCERTAINTY_ANALYSIS, "QuCo-RAG Hallucination Check", String.format("Checked %d novel entities: %d verified, %d unverified (ratio=%.2f)", novelEntities.size(), verifiedCount, flaggedEntities.size(), riskScore), elapsed, Map.of("novelEntities", novelEntities.size(), "verifiedEntities", verifiedCount, "flaggedEntities", flaggedEntities, "riskScore", riskScore, "isHighRisk", isHighRisk));
        log.info("QuCo-RAG: Hallucination check - {} novel entities, {} verified, {} flagged (risk={:.3f})", new Object[]{novelEntities.size(), verifiedCount, flaggedEntities.size(), riskScore});
        return new HallucinationResult(riskScore, flaggedEntities, isHighRisk);
    }

    public boolean shouldTriggerRetrieval(double uncertaintyScore) {
        return this.enabled && uncertaintyScore >= this.uncertaintyThreshold;
    }

    private EntityAnalysis analyzeEntity(String entity) {
        long count;
        if (this.infiniGramClient.isEnabled() && (count = this.infiniGramClient.getCount("\"" + entity + "\"")) >= 0L) {
            double uncertainty = count < this.lowFrequencyThreshold ? 1.0 - (double)count / (double)this.lowFrequencyThreshold : 0.0;
            return new EntityAnalysis(entity, count, uncertainty, "infini-gram");
        }
        return this.analyzeEntityLocally(entity);
    }

    private EntityAnalysis analyzeEntityLocally(String entity) {
        try {
            List<Document> matches = this.vectorStore.similaritySearch(SearchRequest.query((String)entity).withTopK(10).withSimilarityThreshold(0.5));
            int matchCount = matches.size();
            double uncertainty = matchCount == 0 ? 1.0 : Math.max(0.0, 1.0 - (double)matchCount / 10.0);
            return new EntityAnalysis(entity, matchCount, uncertainty, "local-corpus");
        }
        catch (Exception e) {
            log.warn("Local entity analysis failed for '{}': {}", entity, e.getMessage());
            return new EntityAnalysis(entity, 0L, 0.5, "fallback");
        }
    }

    private long checkCoOccurrence(String entity1, String entity2) {
        long count;
        if (this.infiniGramClient.isEnabled() && (count = this.infiniGramClient.getCoOccurrence(entity1, entity2)) >= 0L) {
            return count;
        }
        try {
            String entity1Lower = entity1.toLowerCase();
            String entity2Lower = entity2.toLowerCase();
            List<Document> entity1Docs = this.vectorStore.similaritySearch(SearchRequest.query((String)entity1).withTopK(20).withSimilarityThreshold(0.4));
            long coOccurrenceCount = entity1Docs.stream().filter(doc -> {
                String content = doc.getContent().toLowerCase();
                return content.contains(entity1Lower) && content.contains(entity2Lower);
            }).count();
            log.debug("Co-occurrence check: '{}' AND '{}' = {} documents", new Object[]{entity1, entity2, coOccurrenceCount});
            return coOccurrenceCount;
        }
        catch (Exception e) {
            log.warn("Co-occurrence check failed: {} AND {}: {}", new Object[]{entity1, entity2, e.getMessage()});
            return 0L;
        }
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public record UncertaintyResult(double uncertaintyScore, List<String> detectedEntities, String reason) {
        public boolean isHighUncertainty(double threshold) {
            return this.uncertaintyScore >= threshold;
        }
    }

    private record EntityAnalysis(String entity, long frequency, double uncertaintyContribution, String source) {
    }

    public record HallucinationResult(double riskScore, List<String> flaggedEntities, boolean isHighRisk) {
    }
}
