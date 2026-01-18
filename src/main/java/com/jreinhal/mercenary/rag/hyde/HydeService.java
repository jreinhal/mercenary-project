package com.jreinhal.mercenary.rag.hyde;

import com.jreinhal.mercenary.reasoning.ReasoningTracer;
import com.jreinhal.mercenary.reasoning.ReasoningStep.StepType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;

/**
 * HyDE (Hypothetical Document Embeddings) Service
 * Based on the HyDE architecture from "9 RAG Architectures" article.
 *
 * HyDE bridges the semantic gap between questions and answers by:
 * 1. Generating a hypothetical "ideal answer" to the query
 * 2. Embedding that hypothetical answer
 * 3. Using the embedding to find real documents that look like the answer
 *
 * This dramatically improves retrieval for:
 * - Vague queries ("that one thing about...")
 * - Conceptual questions
 * - Queries where question vocabulary differs from document vocabulary
 *
 * Note: HyDE adds one LLM call for the hypothetical, so use selectively
 * (e.g., only when AdaptiveRag detects a HYDE-suitable query).
 */
@Service
public class HydeService {

    private static final Logger log = LoggerFactory.getLogger(HydeService.class);

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final ReasoningTracer reasoningTracer;

    @Value("${sentinel.hyde.enabled:true}")
    private boolean enabled;

    @Value("${sentinel.hyde.top-k:10}")
    private int topK;

    @Value("${sentinel.hyde.similarity-threshold:0.25}")
    private double similarityThreshold;

    @Value("${sentinel.hyde.hypothetical-length:150}")
    private int hypotheticalLength;

    private static final String HYDE_SYSTEM_PROMPT = """
            You are an expert knowledge assistant. Given a user question,
            generate a hypothetical answer that an ideal document would contain.

            Rules:
            1. Write as if you are the document being searched for
            2. Use domain-specific terminology that would appear in authoritative sources
            3. Be factual in tone, even if you're making up the answer
            4. Keep the response to 2-3 sentences
            5. Do NOT say "I don't know" - generate a plausible answer

            This hypothetical answer will be used for semantic search, so include
            keywords and concepts that would appear in real documents.
            """;

    public HydeService(ChatClient.Builder chatClientBuilder, VectorStore vectorStore,
                       ReasoningTracer reasoningTracer) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
        this.reasoningTracer = reasoningTracer;
    }

    @PostConstruct
    public void init() {
        log.info("HyDE Service initialized (enabled={}, topK={}, threshold={})",
                enabled, topK, similarityThreshold);
    }

    /**
     * Perform HyDE-enhanced retrieval.
     *
     * @param query User's query
     * @param department Security department filter
     * @return Retrieved documents with HyDE enhancement
     */
    public HydeResult retrieve(String query, String department) {
        long startTime = System.currentTimeMillis();

        if (!enabled) {
            log.debug("HyDE disabled, performing standard retrieval");
            List<Document> docs = standardRetrieval(query, department);
            return new HydeResult(docs, null, false, Map.of("mode", "disabled"));
        }

        // Step 1: Generate hypothetical document
        String hypothetical = generateHypothetical(query);
        long hypoTime = System.currentTimeMillis() - startTime;

        if (hypothetical == null || hypothetical.isBlank()) {
            log.warn("HyDE: Failed to generate hypothetical, falling back to standard retrieval");
            List<Document> docs = standardRetrieval(query, department);
            return new HydeResult(docs, null, false, Map.of("fallback", "hypothesis_failed"));
        }

        log.debug("HyDE: Generated hypothetical ({}ms): {}", hypoTime,
                truncate(hypothetical, 100));

        // Step 2: Search using the hypothetical (not the question)
        long searchStart = System.currentTimeMillis();
        List<Document> hydeResults = vectorStore.similaritySearch(
                SearchRequest.query(hypothetical)
                        .withTopK(topK)
                        .withSimilarityThreshold(similarityThreshold)
                        .withFilterExpression("dept == '" + department + "'"));
        long searchTime = System.currentTimeMillis() - searchStart;

        // Step 3: Also do standard retrieval for comparison/fusion
        List<Document> standardResults = standardRetrieval(query, department);

        // Step 4: Fuse results (HyDE results first, then unique standard results)
        List<Document> fusedResults = fuseResults(hydeResults, standardResults);

        long totalTime = System.currentTimeMillis() - startTime;

        // Log reasoning step
        Map<String, Object> metrics = Map.of(
                "hypotheticalLength", hypothetical.length(),
                "hydeResultCount", hydeResults.size(),
                "standardResultCount", standardResults.size(),
                "fusedResultCount", fusedResults.size(),
                "hypoGenerationMs", hypoTime,
                "searchMs", searchTime,
                "totalMs", totalTime
        );

        reasoningTracer.addStep(StepType.RETRIEVAL,
                "HyDE Enhanced Retrieval",
                String.format("Generated hypothetical, found %d HyDE + %d standard = %d fused results",
                        hydeResults.size(), standardResults.size(), fusedResults.size()),
                totalTime,
                metrics);

        log.info("HyDE: {} HyDE + {} standard = {} fused results ({}ms)",
                hydeResults.size(), standardResults.size(), fusedResults.size(), totalTime);

        return new HydeResult(fusedResults, hypothetical, true, metrics);
    }

    /**
     * Generate a hypothetical answer to the query.
     */
    public String generateHypothetical(String query) {
        try {
            String response = chatClient.prompt()
                    .system(HYDE_SYSTEM_PROMPT)
                    .user("Question: " + query + "\n\nHypothetical document content:")
                    .call()
                    .content()
                    .trim();

            // Truncate if too long
            if (response.length() > hypotheticalLength * 2) {
                response = response.substring(0, hypotheticalLength * 2);
            }

            return response;

        } catch (Exception e) {
            log.error("HyDE: Failed to generate hypothetical: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Standard vector retrieval (fallback).
     */
    private List<Document> standardRetrieval(String query, String department) {
        try {
            return vectorStore.similaritySearch(
                    SearchRequest.query(query)
                            .withTopK(topK)
                            .withSimilarityThreshold(similarityThreshold)
                            .withFilterExpression("dept == '" + department + "'"));
        } catch (Exception e) {
            log.error("HyDE: Standard retrieval failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Fuse HyDE and standard results, removing duplicates.
     * HyDE results are prioritized.
     */
    private List<Document> fuseResults(List<Document> hydeResults, List<Document> standardResults) {
        Map<String, Document> seen = new LinkedHashMap<>();

        // Add HyDE results first (higher priority)
        for (Document doc : hydeResults) {
            String key = getDocKey(doc);
            seen.putIfAbsent(key, doc);
        }

        // Add standard results that aren't duplicates
        for (Document doc : standardResults) {
            String key = getDocKey(doc);
            seen.putIfAbsent(key, doc);
        }

        return new ArrayList<>(seen.values());
    }

    /**
     * Generate a unique key for deduplication.
     */
    private String getDocKey(Document doc) {
        Object source = doc.getMetadata().get("source");
        return (source != null ? source.toString() : "") + "_" + doc.getContent().hashCode();
    }

    /**
     * Truncate string for logging.
     */
    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * Check if a query is suitable for HyDE (vague/conceptual queries).
     * Can use pre-computed signals from AdaptiveRagService routing.
     */
    public boolean isSuitableForHyde(String query) {
        if (query == null) return false;
        String lower = query.toLowerCase();

        // Vague references
        if (lower.contains("that one") || lower.contains("the thing") ||
            lower.contains("something about") || lower.contains("remember")) {
            return true;
        }

        // Conceptual questions
        if (lower.contains("concept") || lower.contains("idea") ||
            lower.contains("approach") || lower.contains("theory")) {
            return true;
        }

        // Very short queries often benefit from expansion
        return query.split("\\s+").length <= 3;
    }

    /**
     * Check if HyDE should be used based on routing signals.
     * This method integrates with AdaptiveRagService's pre-computed signals.
     *
     * @param routingSignals Signals from AdaptiveRagService.route()
     * @return true if HyDE should be invoked
     */
    public boolean shouldUseHyde(Map<String, Object> routingSignals) {
        if (!enabled) return false;
        if (routingSignals == null) return false;

        // Check for pre-computed isHyde signal from AdaptiveRagService
        Object hydeSignal = routingSignals.get("isHyde");
        return hydeSignal instanceof Boolean && (Boolean) hydeSignal;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * HyDE retrieval result.
     */
    public record HydeResult(
            List<Document> documents,
            String hypotheticalDocument,
            boolean hydeApplied,
            Map<String, Object> metrics) {
    }
}
