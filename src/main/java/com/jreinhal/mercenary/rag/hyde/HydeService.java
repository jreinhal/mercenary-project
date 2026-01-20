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
 *  org.springframework.stereotype.Service
 */
package com.jreinhal.mercenary.rag.hyde;

import com.jreinhal.mercenary.reasoning.ReasoningStep;
import com.jreinhal.mercenary.reasoning.ReasoningTracer;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class HydeService {
    private static final Logger log = LoggerFactory.getLogger(HydeService.class);
    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final ReasoningTracer reasoningTracer;
    @Value(value="${sentinel.hyde.enabled:true}")
    private boolean enabled;
    @Value(value="${sentinel.hyde.top-k:10}")
    private int topK;
    @Value(value="${sentinel.hyde.similarity-threshold:0.25}")
    private double similarityThreshold;
    @Value(value="${sentinel.hyde.hypothetical-length:150}")
    private int hypotheticalLength;
    private static final String HYDE_SYSTEM_PROMPT = "You are an expert knowledge assistant. Given a user question,\ngenerate a hypothetical answer that an ideal document would contain.\n\nRules:\n1. Write as if you are the document being searched for\n2. Use domain-specific terminology that would appear in authoritative sources\n3. Be factual in tone, even if you're making up the answer\n4. Keep the response to 2-3 sentences\n5. Do NOT say \"I don't know\" - generate a plausible answer\n\nThis hypothetical answer will be used for semantic search, so include\nkeywords and concepts that would appear in real documents.\n";

    public HydeService(ChatClient.Builder chatClientBuilder, VectorStore vectorStore, ReasoningTracer reasoningTracer) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
        this.reasoningTracer = reasoningTracer;
    }

    @PostConstruct
    public void init() {
        log.info("HyDE Service initialized (enabled={}, topK={}, threshold={})", new Object[]{this.enabled, this.topK, this.similarityThreshold});
    }

    public HydeResult retrieve(String query, String department) {
        long startTime = System.currentTimeMillis();
        if (!this.enabled) {
            log.debug("HyDE disabled, performing standard retrieval");
            List<Document> docs = this.standardRetrieval(query, department);
            return new HydeResult(docs, null, false, Map.of("mode", "disabled"));
        }
        String hypothetical = this.generateHypothetical(query);
        long hypoTime = System.currentTimeMillis() - startTime;
        if (hypothetical == null || hypothetical.isBlank()) {
            log.warn("HyDE: Failed to generate hypothetical, falling back to standard retrieval");
            List<Document> docs = this.standardRetrieval(query, department);
            return new HydeResult(docs, null, false, Map.of("fallback", "hypothesis_failed"));
        }
        log.debug("HyDE: Generated hypothetical ({}ms): {}", hypoTime, this.truncate(hypothetical, 100));
        long searchStart = System.currentTimeMillis();
        List hydeResults = this.vectorStore.similaritySearch(SearchRequest.query((String)hypothetical).withTopK(this.topK).withSimilarityThreshold(this.similarityThreshold).withFilterExpression("dept == '" + department + "'"));
        long searchTime = System.currentTimeMillis() - searchStart;
        List<Document> standardResults = this.standardRetrieval(query, department);
        List<Document> fusedResults = this.fuseResults(hydeResults, standardResults);
        long totalTime = System.currentTimeMillis() - startTime;
        Map<String, Object> metrics = Map.of("hypotheticalLength", hypothetical.length(), "hydeResultCount", hydeResults.size(), "standardResultCount", standardResults.size(), "fusedResultCount", fusedResults.size(), "hypoGenerationMs", hypoTime, "searchMs", searchTime, "totalMs", totalTime);
        this.reasoningTracer.addStep(ReasoningStep.StepType.RETRIEVAL, "HyDE Enhanced Retrieval", String.format("Generated hypothetical, found %d HyDE + %d standard = %d fused results", hydeResults.size(), standardResults.size(), fusedResults.size()), totalTime, metrics);
        log.info("HyDE: {} HyDE + {} standard = {} fused results ({}ms)", new Object[]{hydeResults.size(), standardResults.size(), fusedResults.size(), totalTime});
        return new HydeResult(fusedResults, hypothetical, true, metrics);
    }

    public String generateHypothetical(String query) {
        try {
            String response = this.chatClient.prompt().system(HYDE_SYSTEM_PROMPT).user("Question: " + query + "\n\nHypothetical document content:").call().content().trim();
            if (response.length() > this.hypotheticalLength * 2) {
                response = response.substring(0, this.hypotheticalLength * 2);
            }
            return response;
        }
        catch (Exception e) {
            log.error("HyDE: Failed to generate hypothetical: {}", e.getMessage());
            return null;
        }
    }

    private List<Document> standardRetrieval(String query, String department) {
        try {
            return this.vectorStore.similaritySearch(SearchRequest.query((String)query).withTopK(this.topK).withSimilarityThreshold(this.similarityThreshold).withFilterExpression("dept == '" + department + "'"));
        }
        catch (Exception e) {
            log.error("HyDE: Standard retrieval failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Document> fuseResults(List<Document> hydeResults, List<Document> standardResults) {
        String key;
        LinkedHashMap<String, Document> seen = new LinkedHashMap<String, Document>();
        for (Document doc : hydeResults) {
            key = this.getDocKey(doc);
            seen.putIfAbsent(key, doc);
        }
        for (Document doc : standardResults) {
            key = this.getDocKey(doc);
            seen.putIfAbsent(key, doc);
        }
        return new ArrayList<Document>(seen.values());
    }

    private String getDocKey(Document doc) {
        Object source = doc.getMetadata().get("source");
        return (source != null ? source.toString() : "") + "_" + doc.getContent().hashCode();
    }

    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    public boolean isSuitableForHyde(String query) {
        if (query == null) {
            return false;
        }
        String lower = query.toLowerCase();
        if (lower.contains("that one") || lower.contains("the thing") || lower.contains("something about") || lower.contains("remember")) {
            return true;
        }
        if (lower.contains("concept") || lower.contains("idea") || lower.contains("approach") || lower.contains("theory")) {
            return true;
        }
        return query.split("\\s+").length <= 3;
    }

    public boolean shouldUseHyde(Map<String, Object> routingSignals) {
        if (!this.enabled) {
            return false;
        }
        if (routingSignals == null) {
            return false;
        }
        Object hydeSignal = routingSignals.get("isHyde");
        return hydeSignal instanceof Boolean && (Boolean)hydeSignal != false;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public record HydeResult(List<Document> documents, String hypotheticalDocument, boolean hydeApplied, Map<String, Object> metrics) {
    }
}
