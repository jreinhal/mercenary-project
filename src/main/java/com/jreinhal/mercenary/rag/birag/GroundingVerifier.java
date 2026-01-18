package com.jreinhal.mercenary.rag.birag;

import com.jreinhal.mercenary.rag.birag.BidirectionalRagService.GroundingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Grounding Verifier for Bidirectional RAG.
 *
 * Verifies that generated responses are properly grounded in retrieved documents.
 * Uses multiple verification strategies:
 * - Lexical overlap analysis
 * - Semantic similarity checking
 * - LLM-based entailment verification
 *
 * This prevents hallucinations from being stored in the experience store.
 */
@Component
public class GroundingVerifier {

    private static final Logger log = LoggerFactory.getLogger(GroundingVerifier.class);

    private final ChatClient chatClient;

    @Value("${sentinel.birag.use-llm-verification:true}")
    private boolean useLlmVerification;

    @Value("${sentinel.birag.lexical-weight:0.4}")
    private double lexicalWeight;

    @Value("${sentinel.birag.semantic-weight:0.6}")
    private double semanticWeight;

    private static final String ENTAILMENT_PROMPT = """
            You are a fact-checking assistant. Determine if the CLAIM is supported by the EVIDENCE.

            EVIDENCE:
            %s

            CLAIM:
            %s

            Respond with ONE of:
            - SUPPORTED: The claim is directly supported by the evidence
            - PARTIALLY_SUPPORTED: The claim is partially supported or inferred
            - NOT_SUPPORTED: The claim is not supported by the evidence
            - CONTRADICTED: The claim contradicts the evidence

            Response:
            """;

    public GroundingVerifier(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * Verify that a response is grounded in the retrieved documents.
     *
     * @param response Generated response to verify
     * @param documents Retrieved documents used for generation
     * @return Grounding verification result
     */
    public GroundingResult verify(String response, List<Document> documents) {
        if (response == null || response.isBlank() || documents.isEmpty()) {
            return new GroundingResult(0.0, List.of(), List.of(response));
        }

        // Extract statements from response
        List<String> statements = extractStatements(response);

        if (statements.isEmpty()) {
            return new GroundingResult(1.0, List.of(), List.of());
        }

        // Combine document content for verification
        String evidence = documents.stream()
                .map(Document::getContent)
                .collect(java.util.stream.Collectors.joining("\n\n"));

        List<String> grounded = new ArrayList<>();
        List<String> ungrounded = new ArrayList<>();

        for (String statement : statements) {
            VerificationScore score = verifyStatement(statement, evidence, documents);

            if (score.isGrounded()) {
                grounded.add(statement);
            } else {
                ungrounded.add(statement);
            }
        }

        double groundingScore = statements.isEmpty() ? 1.0 :
                (double) grounded.size() / statements.size();

        log.debug("GroundingVerifier: {}/{} statements grounded (score={:.2f})",
                grounded.size(), statements.size(), groundingScore);

        return new GroundingResult(groundingScore, grounded, ungrounded);
    }

    /**
     * Extract individual statements from a response.
     */
    private List<String> extractStatements(String response) {
        List<String> statements = new ArrayList<>();

        // Split by sentence-ending punctuation
        String[] sentences = response.split("(?<=[.!?])\\s+");

        for (String sentence : sentences) {
            String trimmed = sentence.trim();

            // Skip very short sentences, questions, or meta-statements
            if (trimmed.length() < 20) continue;
            if (trimmed.endsWith("?")) continue;
            if (trimmed.toLowerCase().startsWith("i ")) continue;
            if (trimmed.toLowerCase().contains("according to")) continue;

            statements.add(trimmed);
        }

        return statements;
    }

    /**
     * Verify a single statement against evidence.
     */
    private VerificationScore verifyStatement(String statement, String evidence,
                                               List<Document> documents) {
        // Calculate lexical grounding
        double lexicalScore = calculateLexicalOverlap(statement, evidence);

        // Calculate semantic grounding (if LLM verification enabled)
        double semanticScore = lexicalScore; // Default to lexical
        if (useLlmVerification && lexicalScore < 0.8) {
            // Only use LLM for borderline cases to save compute
            semanticScore = calculateSemanticGrounding(statement, evidence);
        }

        // Weighted combination
        double combinedScore = (lexicalWeight * lexicalScore) + (semanticWeight * semanticScore);

        return new VerificationScore(combinedScore, lexicalScore, semanticScore);
    }

    /**
     * Calculate lexical overlap between statement and evidence.
     */
    private double calculateLexicalOverlap(String statement, String evidence) {
        // Tokenize
        Set<String> statementTokens = tokenize(statement);
        Set<String> evidenceTokens = tokenize(evidence);

        if (statementTokens.isEmpty()) {
            return 0.0;
        }

        // Calculate overlap
        int matchCount = 0;
        for (String token : statementTokens) {
            if (evidenceTokens.contains(token)) {
                matchCount++;
            }
        }

        return (double) matchCount / statementTokens.size();
    }

    /**
     * Calculate semantic grounding using LLM entailment checking.
     */
    private double calculateSemanticGrounding(String statement, String evidence) {
        try {
            // Truncate evidence if too long
            String truncatedEvidence = evidence.length() > 3000
                    ? evidence.substring(0, 3000) + "..."
                    : evidence;

            String prompt = ENTAILMENT_PROMPT.formatted(truncatedEvidence, statement);

            @SuppressWarnings("deprecation")
            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (response == null) {
                return 0.5; // Uncertain
            }

            String upper = response.toUpperCase().trim();
            if (upper.contains("SUPPORTED") && !upper.contains("NOT_SUPPORTED")
                    && !upper.contains("PARTIALLY")) {
                return 1.0;
            } else if (upper.contains("PARTIALLY")) {
                return 0.7;
            } else if (upper.contains("NOT_SUPPORTED")) {
                return 0.3;
            } else if (upper.contains("CONTRADICTED")) {
                return 0.0;
            }

            return 0.5; // Default uncertain

        } catch (Exception e) {
            log.warn("Semantic grounding check failed: {}", e.getMessage());
            return 0.5; // Uncertain on error
        }
    }

    /**
     * Tokenize text for overlap calculation.
     */
    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        String lower = text.toLowerCase();

        // Extract meaningful words (length > 3, not stop words)
        Pattern pattern = Pattern.compile("\\b([a-z]{4,})\\b");
        Matcher matcher = pattern.matcher(lower);

        Set<String> stopWords = Set.of(
                "that", "this", "with", "from", "have", "been", "were", "they",
                "their", "what", "when", "where", "which", "would", "could",
                "should", "about", "there", "these", "those", "some", "more"
        );

        while (matcher.find()) {
            String word = matcher.group(1);
            if (!stopWords.contains(word)) {
                tokens.add(word);
            }
        }

        return tokens;
    }

    /**
     * Internal record for verification scores.
     */
    private record VerificationScore(
            double combinedScore,
            double lexicalScore,
            double semanticScore) {

        boolean isGrounded() {
            return combinedScore >= 0.5;
        }
    }
}
