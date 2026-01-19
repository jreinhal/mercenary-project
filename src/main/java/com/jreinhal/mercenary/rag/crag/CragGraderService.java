/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.jreinhal.mercenary.rag.crag.CragGraderService
 *  com.jreinhal.mercenary.rag.crag.CragGraderService$CragDecision
 *  com.jreinhal.mercenary.rag.crag.CragGraderService$CragResult
 *  com.jreinhal.mercenary.rag.crag.CragGraderService$DocumentGrade
 *  com.jreinhal.mercenary.rag.crag.CragGraderService$GradedDocument
 *  com.jreinhal.mercenary.reasoning.ReasoningStep$StepType
 *  com.jreinhal.mercenary.reasoning.ReasoningTracer
 *  jakarta.annotation.PostConstruct
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.ai.chat.client.ChatClient
 *  org.springframework.ai.chat.client.ChatClient$Builder
 *  org.springframework.ai.document.Document
 *  org.springframework.beans.factory.annotation.Value
 *  org.springframework.stereotype.Service
 */
package com.jreinhal.mercenary.rag.crag;

import com.jreinhal.mercenary.rag.crag.CragGraderService;
import com.jreinhal.mercenary.reasoning.ReasoningStep;
import com.jreinhal.mercenary.reasoning.ReasoningTracer;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CragGraderService {
    private static final Logger log = LoggerFactory.getLogger(CragGraderService.class);
    private final ChatClient chatClient;
    private final ReasoningTracer reasoningTracer;
    @Value(value="${sentinel.crag.enabled:true}")
    private boolean enabled;
    @Value(value="${sentinel.crag.min-correct-threshold:0.5}")
    private double minCorrectThreshold;
    @Value(value="${sentinel.crag.use-llm-grading:true}")
    private boolean useLlmGrading;
    @Value(value="${sentinel.crag.confidence-threshold:0.6}")
    private double confidenceThreshold;
    private static final String GRADER_SYSTEM_PROMPT = "You are a relevance grader for a RAG system.\nGiven a USER QUERY and a DOCUMENT, determine if the document helps answer the query.\n\nRespond with ONLY one of these grades:\n- CORRECT: Document directly contains information to answer the query\n- AMBIGUOUS: Document is related but doesn't fully answer the query\n- INCORRECT: Document is not relevant to the query\n\nBe strict: if the document doesn't contain specific facts needed, mark it INCORRECT.\n";

    public CragGraderService(ChatClient.Builder chatClientBuilder, ReasoningTracer reasoningTracer) {
        this.chatClient = chatClientBuilder.build();
        this.reasoningTracer = reasoningTracer;
    }

    @PostConstruct
    public void init() {
        log.info("CRAG Grader initialized (enabled={}, llmGrading={}, threshold={})", new Object[]{this.enabled, this.useLlmGrading, this.minCorrectThreshold});
    }

    public CragResult evaluate(String query, List<Document> documents) {
        double confidence;
        String reason;
        CragDecision decision;
        long startTime = System.currentTimeMillis();
        if (!this.enabled) {
            log.debug("CRAG disabled, passing all documents through");
            List passthrough = documents.stream().map(d -> new GradedDocument(d, DocumentGrade.CORRECT, 1.0, "CRAG disabled")).collect(Collectors.toList());
            return new CragResult(CragDecision.USE_RETRIEVED, passthrough, "CRAG disabled", 1.0, Map.of());
        }
        if (documents == null || documents.isEmpty()) {
            log.info("CRAG: No documents to evaluate, returning REWRITE_NEEDED");
            return new CragResult(CragDecision.REWRITE_NEEDED, List.of(), "No documents retrieved", 0.0, Map.of("documentsCount", 0));
        }
        ArrayList<GradedDocument> gradedDocs = new ArrayList<GradedDocument>();
        for (Document doc : documents) {
            GradedDocument graded = this.gradeDocument(query, doc);
            gradedDocs.add(graded);
        }
        long correctCount = gradedDocs.stream().filter(g -> g.grade() == DocumentGrade.CORRECT).count();
        long ambiguousCount = gradedDocs.stream().filter(g -> g.grade() == DocumentGrade.AMBIGUOUS).count();
        long incorrectCount = gradedDocs.stream().filter(g -> g.grade() == DocumentGrade.INCORRECT).count();
        double correctRatio = (double)correctCount / (double)documents.size();
        double usableRatio = (double)(correctCount + ambiguousCount) / (double)documents.size();
        if (correctRatio >= this.minCorrectThreshold) {
            decision = CragDecision.USE_RETRIEVED;
            reason = String.format("%d/%d documents are relevant", correctCount, documents.size());
            confidence = correctRatio;
        } else if (usableRatio >= this.minCorrectThreshold) {
            decision = CragDecision.SUPPLEMENT_NEEDED;
            reason = String.format("Only %d correct, %d ambiguous - may need more context", correctCount, ambiguousCount);
            confidence = usableRatio * 0.8;
        } else if (correctCount == 0L && ambiguousCount == 0L) {
            decision = CragDecision.REWRITE_NEEDED;
            reason = "No relevant documents found - query rewrite recommended";
            confidence = 0.1;
        } else {
            decision = CragDecision.INSUFFICIENT_EVIDENCE;
            reason = String.format("Only %d/%d documents potentially relevant", correctCount + ambiguousCount, documents.size());
            confidence = usableRatio * 0.5;
        }
        long elapsed = System.currentTimeMillis() - startTime;
        Map<String, Long> metrics = Map.of("totalDocuments", documents.size(), "correctCount", correctCount, "ambiguousCount", ambiguousCount, "incorrectCount", incorrectCount, "correctRatio", correctRatio, "elapsed", elapsed);
        this.reasoningTracer.addStep(ReasoningStep.StepType.VALIDATION, "CRAG Document Grading", String.format("%s: %s (%.0f%% confidence)", decision, reason, confidence * 100.0), elapsed, metrics);
        log.info("CRAG: {} - {} correct, {} ambiguous, {} incorrect ({}ms)", new Object[]{decision, correctCount, ambiguousCount, incorrectCount, elapsed});
        List usableDocs = gradedDocs.stream().filter(g -> g.grade() != DocumentGrade.INCORRECT).sorted((a, b) -> Double.compare(b.relevanceScore(), a.relevanceScore())).collect(Collectors.toList());
        return new CragResult(decision, usableDocs, reason, confidence, metrics);
    }

    private GradedDocument gradeDocument(String query, Document document) {
        if (this.useLlmGrading) {
            return this.gradDocumentWithLlm(query, document);
        }
        return this.gradeDocumentWithHeuristics(query, document);
    }

    private GradedDocument gradDocumentWithLlm(String query, Document document) {
        try {
            double score;
            DocumentGrade grade;
            String content = this.truncateContent(document.getContent(), 500);
            String prompt = String.format("USER QUERY: %s\n\nDOCUMENT:\n%s", query, content);
            String response = this.chatClient.prompt().system(GRADER_SYSTEM_PROMPT).user(prompt).call().content().trim().toUpperCase();
            if (response.contains("CORRECT")) {
                grade = DocumentGrade.CORRECT;
                score = 0.9;
            } else if (response.contains("AMBIGUOUS")) {
                grade = DocumentGrade.AMBIGUOUS;
                score = 0.5;
            } else {
                grade = DocumentGrade.INCORRECT;
                score = 0.1;
            }
            return new GradedDocument(document, grade, score, "LLM grading: " + response);
        }
        catch (Exception e) {
            log.warn("CRAG LLM grading failed, falling back to heuristics: {}", (Object)e.getMessage());
            return this.gradeDocumentWithHeuristics(query, document);
        }
    }

    private GradedDocument gradeDocumentWithHeuristics(String query, Document document) {
        String reason;
        DocumentGrade grade;
        double overlapRatio;
        String queryLower = query.toLowerCase();
        String contentLower = document.getContent().toLowerCase();
        Set queryKeywords = this.extractKeywords(queryLower);
        Set contentKeywords = this.extractKeywords(contentLower);
        long matchCount = queryKeywords.stream().filter(contentKeywords::contains).count();
        double d = overlapRatio = queryKeywords.isEmpty() ? 0.0 : (double)matchCount / (double)queryKeywords.size();
        if (overlapRatio >= 0.6) {
            grade = DocumentGrade.CORRECT;
            reason = String.format("%.0f%% keyword overlap", overlapRatio * 100.0);
        } else if (overlapRatio >= 0.3) {
            grade = DocumentGrade.AMBIGUOUS;
            reason = String.format("%.0f%% keyword overlap (partial)", overlapRatio * 100.0);
        } else {
            grade = DocumentGrade.INCORRECT;
            reason = String.format("Only %.0f%% keyword overlap", overlapRatio * 100.0);
        }
        return new GradedDocument(document, grade, overlapRatio, "Heuristic: " + reason);
    }

    private Set<String> extractKeywords(String text) {
        Set<String> stopWords = Set.of("the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for", "of", "with", "by", "from", "as", "is", "was", "are", "were", "been", "be", "have", "has", "had", "what", "where", "when", "who", "how", "why", "this", "that", "these", "those", "it", "its", "they", "them", "their");
        HashSet<String> keywords = new HashSet<String>();
        for (String word : text.split("\\s+")) {
            String cleaned = word.replaceAll("[^a-z0-9]", "");
            if (cleaned.length() < 3 || stopWords.contains(cleaned)) continue;
            keywords.add(cleaned);
        }
        return keywords;
    }

    private String truncateContent(String content, int maxLength) {
        if (content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength) + "...";
    }

    public List<Document> getCorrectDocuments(CragResult result) {
        return result.gradedDocuments().stream().filter(g -> g.grade() == DocumentGrade.CORRECT).map(GradedDocument::document).collect(Collectors.toList());
    }

    public List<Document> getUsableDocuments(CragResult result) {
        return result.gradedDocuments().stream().map(GradedDocument::document).collect(Collectors.toList());
    }

    public boolean needsRewrite(CragResult result) {
        return result.decision() == CragDecision.REWRITE_NEEDED;
    }

    public boolean hasSufficientEvidence(CragResult result) {
        return result.decision() == CragDecision.USE_RETRIEVED || result.decision() == CragDecision.SUPPLEMENT_NEEDED;
    }

    public boolean isEnabled() {
        return this.enabled;
    }
}

