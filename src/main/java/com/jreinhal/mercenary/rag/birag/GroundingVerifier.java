/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.ai.chat.client.ChatClient
 *  org.springframework.ai.chat.client.ChatClient$Builder
 *  org.springframework.ai.document.Document
 *  org.springframework.beans.factory.annotation.Value
 *  org.springframework.stereotype.Component
 */
package com.jreinhal.mercenary.rag.birag;

import com.jreinhal.mercenary.rag.birag.BidirectionalRagService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GroundingVerifier {
    private static final Logger log = LoggerFactory.getLogger(GroundingVerifier.class);
    private final ChatClient chatClient;
    @Value(value="${sentinel.birag.use-llm-verification:true}")
    private boolean useLlmVerification;
    @Value(value="${sentinel.birag.lexical-weight:0.4}")
    private double lexicalWeight;
    @Value(value="${sentinel.birag.semantic-weight:0.6}")
    private double semanticWeight;
    private static final String ENTAILMENT_PROMPT = "You are a fact-checking assistant. Determine if the CLAIM is supported by the EVIDENCE.\n\nEVIDENCE:\n%s\n\nCLAIM:\n%s\n\nRespond with ONE of:\n- SUPPORTED: The claim is directly supported by the evidence\n- PARTIALLY_SUPPORTED: The claim is partially supported or inferred\n- NOT_SUPPORTED: The claim is not supported by the evidence\n- CONTRADICTED: The claim contradicts the evidence\n\nResponse:\n";

    public GroundingVerifier(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public BidirectionalRagService.GroundingResult verify(String response, List<Document> documents) {
        if (response == null || response.isBlank() || documents.isEmpty()) {
            return new BidirectionalRagService.GroundingResult(0.0, List.of(), List.of(response));
        }
        List<String> statements = this.extractStatements(response);
        if (statements.isEmpty()) {
            return new BidirectionalRagService.GroundingResult(1.0, List.of(), List.of());
        }
        String evidence = documents.stream().map(Document::getContent).collect(Collectors.joining("\n\n"));
        ArrayList<String> grounded = new ArrayList<String>();
        ArrayList<String> ungrounded = new ArrayList<String>();
        for (String statement : statements) {
            VerificationScore score = this.verifyStatement(statement, evidence, documents);
            if (score.isGrounded()) {
                grounded.add(statement);
                continue;
            }
            ungrounded.add(statement);
        }
        double groundingScore = statements.isEmpty() ? 1.0 : (double)grounded.size() / (double)statements.size();
        log.debug("GroundingVerifier: {}/{} statements grounded (score={:.2f})", new Object[]{grounded.size(), statements.size(), groundingScore});
        return new BidirectionalRagService.GroundingResult(groundingScore, grounded, ungrounded);
    }

    private List<String> extractStatements(String response) {
        String[] sentences;
        ArrayList<String> statements = new ArrayList<String>();
        for (String sentence : sentences = response.split("(?<=[.!?])\\s+")) {
            String trimmed = sentence.trim();
            if (trimmed.length() < 20 || trimmed.endsWith("?") || trimmed.toLowerCase().startsWith("i ") || trimmed.toLowerCase().contains("according to")) continue;
            statements.add(trimmed);
        }
        return statements;
    }

    private VerificationScore verifyStatement(String statement, String evidence, List<Document> documents) {
        double lexicalScore;
        double semanticScore = lexicalScore = this.calculateLexicalOverlap(statement, evidence);
        if (this.useLlmVerification && lexicalScore < 0.8) {
            semanticScore = this.calculateSemanticGrounding(statement, evidence);
        }
        double combinedScore = this.lexicalWeight * lexicalScore + this.semanticWeight * semanticScore;
        return new VerificationScore(combinedScore, lexicalScore, semanticScore);
    }

    private double calculateLexicalOverlap(String statement, String evidence) {
        Set<String> statementTokens = this.tokenize(statement);
        Set<String> evidenceTokens = this.tokenize(evidence);
        if (statementTokens.isEmpty()) {
            return 0.0;
        }
        int matchCount = 0;
        for (String token : statementTokens) {
            if (!evidenceTokens.contains(token)) continue;
            ++matchCount;
        }
        return (double)matchCount / (double)statementTokens.size();
    }

    private double calculateSemanticGrounding(String statement, String evidence) {
        try {
            String truncatedEvidence = evidence.length() > 3000 ? evidence.substring(0, 3000) + "..." : evidence;
            String prompt = ENTAILMENT_PROMPT.formatted(truncatedEvidence, statement);
            String response = this.chatClient.prompt().user(prompt).call().content();
            if (response == null) {
                return 0.5;
            }
            String upper = response.toUpperCase().trim();
            if (upper.contains("SUPPORTED") && !upper.contains("NOT_SUPPORTED") && !upper.contains("PARTIALLY")) {
                return 1.0;
            }
            if (upper.contains("PARTIALLY")) {
                return 0.7;
            }
            if (upper.contains("NOT_SUPPORTED")) {
                return 0.3;
            }
            if (upper.contains("CONTRADICTED")) {
                return 0.0;
            }
            return 0.5;
        }
        catch (Exception e) {
            log.warn("Semantic grounding check failed: {}", e.getMessage());
            return 0.5;
        }
    }

    private Set<String> tokenize(String text) {
        HashSet<String> tokens = new HashSet<String>();
        String lower = text.toLowerCase();
        Pattern pattern = Pattern.compile("\\b([a-z]{4,})\\b");
        Matcher matcher = pattern.matcher(lower);
        Set<String> stopWords = Set.of("that", "this", "with", "from", "have", "been", "were", "they", "their", "what", "when", "where", "which", "would", "could", "should", "about", "there", "these", "those", "some", "more");
        while (matcher.find()) {
            String word = matcher.group(1);
            if (stopWords.contains(word)) continue;
            tokens.add(word);
        }
        return tokens;
    }

    private record VerificationScore(double combinedScore, double lexicalScore, double semanticScore) {
        boolean isGrounded() {
            return this.combinedScore >= 0.5;
        }
    }
}
