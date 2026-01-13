package com.jreinhal.mercenary.rag.qucorag;

import com.jreinhal.mercenary.reasoning.ReasoningTracer;
import com.jreinhal.mercenary.reasoning.ReasoningStep.StepType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * QuCo-RAG: Quantifying Uncertainty from Corpus for RAG
 * Based on research paper arXiv:2512.19134
 *
 * This service implements uncertainty quantification for RAG systems by:
 * 1. ENTITY EXTRACTION: Identify named entities in query and generated text
 * 2. FREQUENCY ANALYSIS: Check entity frequency in corpus (local or
 * Infini-gram)
 * 3. CO-OCCURRENCE CHECK: Verify entity relationships exist in training data
 * 4. UNCERTAINTY SCORING: Compute overall uncertainty score
 * 5. RETRIEVAL TRIGGER: Trigger additional retrieval when uncertainty is high
 *
 * This helps detect and prevent hallucinations by identifying when the model
 * may be generating content about entities or relationships not grounded in
 * data.
 */
@Service
public class QuCoRagService {

    private static final Logger log = LoggerFactory.getLogger(QuCoRagService.class);

    private final EntityExtractor entityExtractor;
    private final LlmEntityExtractor llmEntityExtractor;
    private final InfiniGramClient infiniGramClient;
    private final VectorStore vectorStore;
    private final ReasoningTracer reasoningTracer;

    @Value("${sentinel.qucorag.enabled:true}")
    private boolean enabled;

    @Value("${sentinel.qucorag.uncertainty-threshold:0.7}")
    private double uncertaintyThreshold;

    @Value("${sentinel.qucorag.low-frequency-threshold:1000}")
    private long lowFrequencyThreshold;

    @Value("${sentinel.qucorag.zero-cooccurrence-penalty:0.3}")
    private double zeroCoOccurrencePenalty;

    public QuCoRagService(EntityExtractor entityExtractor,
            LlmEntityExtractor llmEntityExtractor,
            InfiniGramClient infiniGramClient,
            VectorStore vectorStore,
            ReasoningTracer reasoningTracer) {
        this.entityExtractor = entityExtractor;
        this.llmEntityExtractor = llmEntityExtractor;
        this.infiniGramClient = infiniGramClient;
        this.vectorStore = vectorStore;
        this.reasoningTracer = reasoningTracer;
    }

    @PostConstruct
    public void init() {
        log.info("QuCo-RAG Service initialized (enabled={}, threshold={}, infiniGram={}, llmExtraction={})",
                enabled, uncertaintyThreshold, infiniGramClient.isEnabled(), llmEntityExtractor.isEnabled());
    }

    /**
     * Extract entities using the best available method.
     * Uses LLM extraction if enabled, otherwise falls back to pattern-based.
     */
    private Set<String> extractEntities(String text) {
        if (llmEntityExtractor.isEnabled()) {
            return llmEntityExtractor.extractEntityStrings(text);
        }
        return entityExtractor.extractEntityStrings(text);
    }

    /**
     * Analyze uncertainty of a query before generation.
     * High uncertainty queries contain rare/unknown entities that may lead to
     * hallucination.
     *
     * @param query The user's query
     * @return UncertaintyResult with score and detected entities
     */
    public UncertaintyResult analyzeQueryUncertainty(String query) {
        if (!enabled) {
            return new UncertaintyResult(0.0, List.of(), "QuCo-RAG disabled");
        }

        long startTime = System.currentTimeMillis();
        Set<String> entities = extractEntities(query);

        if (entities.isEmpty()) {
            return new UncertaintyResult(0.0, List.of(), "No entities detected");
        }

        // Analyze each entity
        List<EntityAnalysis> analyses = new ArrayList<>();
        double totalUncertainty = 0.0;

        for (String entity : entities) {
            EntityAnalysis analysis = analyzeEntity(entity);
            analyses.add(analysis);
            totalUncertainty += analysis.uncertaintyContribution();
        }

        // Normalize by number of entities
        double uncertaintyScore = Math.min(1.0, totalUncertainty / entities.size());

        // Log reasoning step
        long elapsed = System.currentTimeMillis() - startTime;
        reasoningTracer.addStep(StepType.UNCERTAINTY_ANALYSIS,
                "QuCo-RAG Query Uncertainty",
                String.format("Analyzed %d entities, uncertainty=%.3f (threshold=%.2f)",
                        entities.size(), uncertaintyScore, uncertaintyThreshold),
                elapsed,
                Map.of(
                        "entities", entities,
                        "uncertaintyScore", uncertaintyScore,
                        "shouldRetrieve", uncertaintyScore >= uncertaintyThreshold));

        String reason = uncertaintyScore >= uncertaintyThreshold
                ? "High uncertainty - additional retrieval recommended"
                : "Low uncertainty - generation can proceed";

        log.info("QuCo-RAG: Query uncertainty={:.3f} for {} entities ({})",
                uncertaintyScore, entities.size(), reason);

        return new UncertaintyResult(uncertaintyScore, new ArrayList<>(entities), reason);
    }

    /**
     * Detect hallucination risk in generated text by checking entity co-occurrence.
     *
     * @param generatedText The LLM's generated response
     * @param query         The original query for context
     * @return HallucinationResult with risk score and flagged content
     */
    public HallucinationResult detectHallucinationRisk(String generatedText, String query) {
        if (!enabled) {
            return new HallucinationResult(0.0, List.of(), false);
        }

        long startTime = System.currentTimeMillis();
        Set<String> generatedEntities = extractEntities(generatedText);
        Set<String> queryEntities = extractEntities(query);

        // Find novel entities (in response but not in query)
        Set<String> novelEntities = new HashSet<>(generatedEntities);
        novelEntities.removeAll(queryEntities);

        if (novelEntities.isEmpty()) {
            return new HallucinationResult(0.0, List.of(), false);
        }

        // Check co-occurrence of novel entities with query entities
        List<String> flaggedEntities = new ArrayList<>();
        double riskScore = 0.0;

        for (String novelEntity : novelEntities) {
            boolean hasCoOccurrence = false;

            // Check if novel entity co-occurs with any query entity
            for (String queryEntity : queryEntities) {
                long coOccurrence = checkCoOccurrence(novelEntity, queryEntity);
                if (coOccurrence > 0) {
                    hasCoOccurrence = true;
                    break;
                }
            }

            if (!hasCoOccurrence) {
                flaggedEntities.add(novelEntity);
                riskScore += zeroCoOccurrencePenalty;
            }
        }

        // Normalize
        riskScore = Math.min(1.0, riskScore);
        boolean isHighRisk = riskScore >= uncertaintyThreshold;

        // Log reasoning
        long elapsed = System.currentTimeMillis() - startTime;
        reasoningTracer.addStep(StepType.UNCERTAINTY_ANALYSIS,
                "QuCo-RAG Hallucination Check",
                String.format("Checked %d novel entities, %d flagged, risk=%.3f",
                        novelEntities.size(), flaggedEntities.size(), riskScore),
                elapsed,
                Map.of(
                        "novelEntities", novelEntities,
                        "flaggedEntities", flaggedEntities,
                        "riskScore", riskScore,
                        "isHighRisk", isHighRisk));

        log.info("QuCo-RAG: Hallucination risk={:.3f}, {} flagged entities",
                riskScore, flaggedEntities.size());

        return new HallucinationResult(riskScore, flaggedEntities, isHighRisk);
    }

    /**
     * Determine if additional retrieval should be triggered based on uncertainty.
     */
    public boolean shouldTriggerRetrieval(double uncertaintyScore) {
        return enabled && uncertaintyScore >= uncertaintyThreshold;
    }

    /**
     * Analyze a single entity for uncertainty.
     */
    private EntityAnalysis analyzeEntity(String entity) {
        // Try Infini-gram first (if enabled)
        if (infiniGramClient.isEnabled()) {
            long count = infiniGramClient.getCount("\"" + entity + "\"");
            if (count >= 0) {
                double uncertainty = count < lowFrequencyThreshold
                        ? 1.0 - (count / (double) lowFrequencyThreshold)
                        : 0.0;
                return new EntityAnalysis(entity, count, uncertainty, "infini-gram");
            }
        }

        // Fallback: Local corpus analysis via vector store
        return analyzeEntityLocally(entity);
    }

    /**
     * Analyze entity using local vector store as fallback.
     */
    private EntityAnalysis analyzeEntityLocally(String entity) {
        try {
            List<Document> matches = vectorStore.similaritySearch(
                    SearchRequest.query(entity)
                            .withTopK(10)
                            .withSimilarityThreshold(0.5));

            // Use match count as proxy for frequency
            int matchCount = matches.size();
            double uncertainty = matchCount == 0 ? 1.0 : Math.max(0.0, 1.0 - (matchCount / 10.0));

            return new EntityAnalysis(entity, matchCount, uncertainty, "local-corpus");

        } catch (Exception e) {
            log.warn("Local entity analysis failed for '{}': {}", entity, e.getMessage());
            // Conservative: treat as uncertain if analysis fails
            return new EntityAnalysis(entity, 0, 0.5, "fallback");
        }
    }

    /**
     * Check co-occurrence of two entities.
     * Uses proper AND-style logic: both entities must appear in the same document.
     */
    private long checkCoOccurrence(String entity1, String entity2) {
        // Try Infini-gram first (uses actual n-gram co-occurrence)
        if (infiniGramClient.isEnabled()) {
            long count = infiniGramClient.getCoOccurrence(entity1, entity2);
            if (count >= 0) {
                return count;
            }
        }

        // Fallback: Local document-based co-occurrence check
        // Search for each entity separately, then count documents containing BOTH
        try {
            String entity1Lower = entity1.toLowerCase();
            String entity2Lower = entity2.toLowerCase();

            // Get documents matching entity1
            List<Document> entity1Docs = vectorStore.similaritySearch(
                    SearchRequest.query(entity1)
                            .withTopK(20)
                            .withSimilarityThreshold(0.4));

            // Filter to documents that actually contain BOTH entities in their content
            long coOccurrenceCount = entity1Docs.stream()
                    .filter(doc -> {
                        String content = doc.getContent().toLowerCase();
                        return content.contains(entity1Lower) && content.contains(entity2Lower);
                    })
                    .count();

            log.debug("Co-occurrence check: '{}' AND '{}' = {} documents", entity1, entity2, coOccurrenceCount);
            return coOccurrenceCount;

        } catch (Exception e) {
            log.warn("Co-occurrence check failed: {} AND {}: {}", entity1, entity2, e.getMessage());
            return 0;
        }
    }

    /**
     * Check if QuCo-RAG is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Result of uncertainty analysis.
     */
    public record UncertaintyResult(
            double uncertaintyScore,
            List<String> detectedEntities,
            String reason) {
        public boolean isHighUncertainty(double threshold) {
            return uncertaintyScore >= threshold;
        }
    }

    /**
     * Result of hallucination detection.
     */
    public record HallucinationResult(
            double riskScore,
            List<String> flaggedEntities,
            boolean isHighRisk) {
    }

    /**
     * Analysis of a single entity.
     */
    private record EntityAnalysis(
            String entity,
            long frequency,
            double uncertaintyContribution,
            String source) {
    }
}
