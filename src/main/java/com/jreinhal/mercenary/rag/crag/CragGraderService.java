package com.jreinhal.mercenary.rag.crag;

import com.jreinhal.mercenary.reasoning.ReasoningTracer;
import com.jreinhal.mercenary.reasoning.ReasoningStep.StepType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Corrective RAG (CRAG) Grader Service
 * Based on the CRAG architecture from "9 RAG Architectures" article.
 *
 * This service evaluates the quality of retrieved documents BEFORE generation
 * to prevent hallucinations and ensure response quality.
 *
 * Decision Flow:
 * 1. CORRECT: Documents are relevant -> proceed to generation
 * 2. AMBIGUOUS: Partial relevance -> supplement with additional retrieval
 * 3. INCORRECT: Documents not relevant -> trigger query rewrite or return insufficient evidence
 *
 * For air-gapped deployments (no web fallback), INCORRECT triggers:
 * - Query rewrite via RewriteService
 * - Audit logging for low-confidence responses
 * - "Insufficient evidence" warnings to user
 */
@Service
public class CragGraderService {

    private static final Logger log = LoggerFactory.getLogger(CragGraderService.class);

    /**
     * Grading result for a single document.
     */
    public enum DocumentGrade {
        /** Document directly answers the query */
        CORRECT,
        /** Document partially relevant, may need supplementation */
        AMBIGUOUS,
        /** Document not relevant to the query */
        INCORRECT
    }

    /**
     * Overall decision for the retrieval set.
     */
    public enum CragDecision {
        /** Proceed with generation using retrieved documents */
        USE_RETRIEVED,
        /** Retrieved docs need supplementation */
        SUPPLEMENT_NEEDED,
        /** Insufficient evidence - warn user */
        INSUFFICIENT_EVIDENCE,
        /** Trigger query rewrite and retry */
        REWRITE_NEEDED
    }

    /**
     * Result of CRAG evaluation.
     */
    public record CragResult(
            CragDecision decision,
            List<GradedDocument> gradedDocuments,
            String reason,
            double overallConfidence,
            Map<String, Object> metrics) {
    }

    /**
     * Document with its assigned grade.
     */
    public record GradedDocument(
            Document document,
            DocumentGrade grade,
            double relevanceScore,
            String gradeReason) {
    }

    private final ChatClient chatClient;
    private final ReasoningTracer reasoningTracer;

    @Value("${sentinel.crag.enabled:true}")
    private boolean enabled;

    @Value("${sentinel.crag.min-correct-threshold:0.5}")
    private double minCorrectThreshold;

    @Value("${sentinel.crag.use-llm-grading:true}")
    private boolean useLlmGrading;

    @Value("${sentinel.crag.confidence-threshold:0.6}")
    private double confidenceThreshold;

    private static final String GRADER_SYSTEM_PROMPT = """
            You are a relevance grader for a RAG system.
            Given a USER QUERY and a DOCUMENT, determine if the document helps answer the query.

            Respond with ONLY one of these grades:
            - CORRECT: Document directly contains information to answer the query
            - AMBIGUOUS: Document is related but doesn't fully answer the query
            - INCORRECT: Document is not relevant to the query

            Be strict: if the document doesn't contain specific facts needed, mark it INCORRECT.
            """;

    public CragGraderService(ChatClient.Builder chatClientBuilder, ReasoningTracer reasoningTracer) {
        this.chatClient = chatClientBuilder.build();
        this.reasoningTracer = reasoningTracer;
    }

    @PostConstruct
    public void init() {
        log.info("CRAG Grader initialized (enabled={}, llmGrading={}, threshold={})",
                enabled, useLlmGrading, minCorrectThreshold);
    }

    /**
     * Evaluate retrieved documents for relevance to the query.
     *
     * @param query The user's query
     * @param documents Retrieved documents to evaluate
     * @return CragResult with decision and graded documents
     */
    public CragResult evaluate(String query, List<Document> documents) {
        long startTime = System.currentTimeMillis();

        if (!enabled) {
            log.debug("CRAG disabled, passing all documents through");
            List<GradedDocument> passthrough = documents.stream()
                    .map(d -> new GradedDocument(d, DocumentGrade.CORRECT, 1.0, "CRAG disabled"))
                    .collect(Collectors.toList());
            return new CragResult(CragDecision.USE_RETRIEVED, passthrough, "CRAG disabled", 1.0, Map.of());
        }

        if (documents == null || documents.isEmpty()) {
            log.info("CRAG: No documents to evaluate, returning REWRITE_NEEDED");
            return new CragResult(CragDecision.REWRITE_NEEDED, List.of(),
                    "No documents retrieved", 0.0, Map.of("documentsCount", 0));
        }

        // Grade each document
        List<GradedDocument> gradedDocs = new ArrayList<>();
        for (Document doc : documents) {
            GradedDocument graded = gradeDocument(query, doc);
            gradedDocs.add(graded);
        }

        // Calculate metrics
        long correctCount = gradedDocs.stream().filter(g -> g.grade() == DocumentGrade.CORRECT).count();
        long ambiguousCount = gradedDocs.stream().filter(g -> g.grade() == DocumentGrade.AMBIGUOUS).count();
        long incorrectCount = gradedDocs.stream().filter(g -> g.grade() == DocumentGrade.INCORRECT).count();

        double correctRatio = (double) correctCount / documents.size();
        double usableRatio = (double) (correctCount + ambiguousCount) / documents.size();

        // Determine decision
        CragDecision decision;
        String reason;
        double confidence;

        if (correctRatio >= minCorrectThreshold) {
            decision = CragDecision.USE_RETRIEVED;
            reason = String.format("%d/%d documents are relevant", correctCount, documents.size());
            confidence = correctRatio;
        } else if (usableRatio >= minCorrectThreshold) {
            decision = CragDecision.SUPPLEMENT_NEEDED;
            reason = String.format("Only %d correct, %d ambiguous - may need more context",
                    correctCount, ambiguousCount);
            confidence = usableRatio * 0.8; // Reduced confidence for ambiguous results
        } else if (correctCount == 0 && ambiguousCount == 0) {
            decision = CragDecision.REWRITE_NEEDED;
            reason = "No relevant documents found - query rewrite recommended";
            confidence = 0.1;
        } else {
            decision = CragDecision.INSUFFICIENT_EVIDENCE;
            reason = String.format("Only %d/%d documents potentially relevant", correctCount + ambiguousCount, documents.size());
            confidence = usableRatio * 0.5;
        }

        long elapsed = System.currentTimeMillis() - startTime;

        // Log reasoning step
        Map<String, Object> metrics = Map.of(
                "totalDocuments", documents.size(),
                "correctCount", correctCount,
                "ambiguousCount", ambiguousCount,
                "incorrectCount", incorrectCount,
                "correctRatio", correctRatio,
                "elapsed", elapsed
        );

        reasoningTracer.addStep(StepType.VALIDATION,
                "CRAG Document Grading",
                String.format("%s: %s (%.0f%% confidence)", decision, reason, confidence * 100),
                elapsed,
                metrics);

        log.info("CRAG: {} - {} correct, {} ambiguous, {} incorrect ({}ms)",
                decision, correctCount, ambiguousCount, incorrectCount, elapsed);

        // Filter to only return usable documents
        List<GradedDocument> usableDocs = gradedDocs.stream()
                .filter(g -> g.grade() != DocumentGrade.INCORRECT)
                .sorted((a, b) -> Double.compare(b.relevanceScore(), a.relevanceScore()))
                .collect(Collectors.toList());

        return new CragResult(decision, usableDocs, reason, confidence, metrics);
    }

    /**
     * Grade a single document for relevance.
     */
    private GradedDocument gradeDocument(String query, Document document) {
        if (useLlmGrading) {
            return gradDocumentWithLlm(query, document);
        } else {
            return gradeDocumentWithHeuristics(query, document);
        }
    }

    /**
     * Use LLM to grade document relevance (higher accuracy, higher latency).
     */
    private GradedDocument gradDocumentWithLlm(String query, Document document) {
        try {
            String content = truncateContent(document.getContent(), 500);
            String prompt = String.format("USER QUERY: %s\n\nDOCUMENT:\n%s", query, content);

            String response = chatClient.prompt()
                    .system(GRADER_SYSTEM_PROMPT)
                    .user(prompt)
                    .call()
                    .content()
                    .trim()
                    .toUpperCase();

            DocumentGrade grade;
            double score;
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

        } catch (Exception e) {
            log.warn("CRAG LLM grading failed, falling back to heuristics: {}", e.getMessage());
            return gradeDocumentWithHeuristics(query, document);
        }
    }

    /**
     * Use heuristics to grade document relevance (lower accuracy, zero latency).
     */
    private GradedDocument gradeDocumentWithHeuristics(String query, Document document) {
        String queryLower = query.toLowerCase();
        String contentLower = document.getContent().toLowerCase();

        // Extract query keywords
        Set<String> queryKeywords = extractKeywords(queryLower);
        Set<String> contentKeywords = extractKeywords(contentLower);

        // Calculate overlap
        long matchCount = queryKeywords.stream()
                .filter(contentKeywords::contains)
                .count();

        double overlapRatio = queryKeywords.isEmpty() ? 0 : (double) matchCount / queryKeywords.size();

        DocumentGrade grade;
        String reason;
        if (overlapRatio >= 0.6) {
            grade = DocumentGrade.CORRECT;
            reason = String.format("%.0f%% keyword overlap", overlapRatio * 100);
        } else if (overlapRatio >= 0.3) {
            grade = DocumentGrade.AMBIGUOUS;
            reason = String.format("%.0f%% keyword overlap (partial)", overlapRatio * 100);
        } else {
            grade = DocumentGrade.INCORRECT;
            reason = String.format("Only %.0f%% keyword overlap", overlapRatio * 100);
        }

        return new GradedDocument(document, grade, overlapRatio, "Heuristic: " + reason);
    }

    /**
     * Extract significant keywords from text.
     */
    private Set<String> extractKeywords(String text) {
        Set<String> stopWords = Set.of(
                "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
                "of", "with", "by", "from", "as", "is", "was", "are", "were", "been",
                "be", "have", "has", "had", "what", "where", "when", "who", "how", "why",
                "this", "that", "these", "those", "it", "its", "they", "them", "their"
        );

        Set<String> keywords = new HashSet<>();
        for (String word : text.split("\\s+")) {
            String cleaned = word.replaceAll("[^a-z0-9]", "");
            if (cleaned.length() >= 3 && !stopWords.contains(cleaned)) {
                keywords.add(cleaned);
            }
        }
        return keywords;
    }

    /**
     * Truncate content to avoid token limits.
     */
    private String truncateContent(String content, int maxLength) {
        if (content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength) + "...";
    }

    /**
     * Get only CORRECT documents from result.
     */
    public List<Document> getCorrectDocuments(CragResult result) {
        return result.gradedDocuments().stream()
                .filter(g -> g.grade() == DocumentGrade.CORRECT)
                .map(GradedDocument::document)
                .collect(Collectors.toList());
    }

    /**
     * Get all usable documents (CORRECT + AMBIGUOUS).
     */
    public List<Document> getUsableDocuments(CragResult result) {
        return result.gradedDocuments().stream()
                .map(GradedDocument::document)
                .collect(Collectors.toList());
    }

    /**
     * Check if result requires query rewrite.
     */
    public boolean needsRewrite(CragResult result) {
        return result.decision() == CragDecision.REWRITE_NEEDED;
    }

    /**
     * Check if result has sufficient evidence.
     */
    public boolean hasSufficientEvidence(CragResult result) {
        return result.decision() == CragDecision.USE_RETRIEVED ||
               result.decision() == CragDecision.SUPPLEMENT_NEEDED;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
