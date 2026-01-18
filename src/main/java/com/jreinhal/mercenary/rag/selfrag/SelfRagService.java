package com.jreinhal.mercenary.rag.selfrag;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Self-RAG (Self-Reflective RAG) Service
 * Based on the Self-RAG architecture from "9 RAG Architectures" article.
 *
 * This service implements self-critique during generation by:
 * 1. Generating responses with inline reflection markers
 * 2. Detecting [SUPPORTED], [INFERRED], [UNCERTAIN] tokens
 * 3. Re-retrieving when [UNCERTAIN] claims are detected
 * 4. Providing transparency into the reasoning process
 *
 * Self-RAG provides the highest factual "groundedness" by having the
 * model audit its own claims in real-time.
 *
 * Note: This is computationally expensive. Use for medical/government
 * editions where accuracy is critical.
 */
@Service
public class SelfRagService {

    private static final Logger log = LoggerFactory.getLogger(SelfRagService.class);

    /**
     * Reflection token types generated during self-critique.
     */
    public enum ReflectionToken {
        /** Claim is directly supported by retrieved context */
        SUPPORTED,
        /** Claim is reasonably inferred from context */
        INFERRED,
        /** Claim needs verification - may trigger re-retrieval */
        UNCERTAIN,
        /** No supporting evidence in context */
        UNSUPPORTED
    }

    /**
     * A claim extracted from the response with its reflection token.
     */
    public record ReflectedClaim(
            String claim,
            ReflectionToken token,
            String supportingContext,
            double confidence) {
    }

    /**
     * Result of self-reflective generation.
     */
    public record SelfRagResult(
            String response,
            String cleanResponse,
            List<ReflectedClaim> claims,
            boolean needsReRetrieval,
            List<String> uncertainClaims,
            double overallConfidence,
            Map<String, Object> metrics) {
    }

    private final ChatClient chatClient;
    private final ReasoningTracer reasoningTracer;

    @Value("${sentinel.selfrag.enabled:true}")
    private boolean enabled;

    @Value("${sentinel.selfrag.max-uncertain-claims:2}")
    private int maxUncertainClaims;

    @Value("${sentinel.selfrag.re-retrieve-on-uncertain:true}")
    private boolean reRetrieveOnUncertain;

    // Patterns to detect reflection tokens in response
    private static final Pattern SUPPORTED_PATTERN = Pattern.compile("\\[SUPPORTED\\]\\s*([^\\[]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern INFERRED_PATTERN = Pattern.compile("\\[INFERRED\\]\\s*([^\\[]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNCERTAIN_PATTERN = Pattern.compile("\\[UNCERTAIN\\]\\s*([^\\[]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNSUPPORTED_PATTERN = Pattern.compile("\\[UNSUPPORTED\\]\\s*([^\\[]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALL_TOKENS_PATTERN = Pattern.compile("\\[(SUPPORTED|INFERRED|UNCERTAIN|UNSUPPORTED)\\]", Pattern.CASE_INSENSITIVE);

    private static final String SELF_RAG_SYSTEM_PROMPT = """
            You are a self-reflective AI that critically evaluates your own claims.

            When answering, mark EACH factual claim with a reflection token:
            - [SUPPORTED] Claim directly found in the provided context
            - [INFERRED] Claim reasonably deduced from context
            - [UNCERTAIN] Claim that may need verification - not fully supported
            - [UNSUPPORTED] Claim with no supporting evidence

            Example format:
            "[SUPPORTED] The company was founded in 2010. [INFERRED] Based on the growth trajectory,
            they likely expanded internationally by 2015. [UNCERTAIN] Their current market share
            may be around 15%."

            Be rigorous: if information isn't in the context, mark it [UNCERTAIN] or [UNSUPPORTED].
            """;

    public SelfRagService(ChatClient.Builder chatClientBuilder, ReasoningTracer reasoningTracer) {
        this.chatClient = chatClientBuilder.build();
        this.reasoningTracer = reasoningTracer;
    }

    @PostConstruct
    public void init() {
        log.info("Self-RAG Service initialized (enabled={}, maxUncertain={}, reRetrieve={})",
                enabled, maxUncertainClaims, reRetrieveOnUncertain);
    }

    /**
     * Generate a response with self-reflection.
     *
     * @param query User's query
     * @param context Retrieved documents as context
     * @return SelfRagResult with reflected claims and confidence
     */
    public SelfRagResult generateWithReflection(String query, List<Document> context) {
        long startTime = System.currentTimeMillis();

        if (!enabled) {
            log.debug("Self-RAG disabled, performing standard generation");
            return standardGeneration(query, context, startTime);
        }

        // Build context string
        String contextStr = buildContextString(context);

        // Generate with self-reflection prompt
        String rawResponse;
        try {
            rawResponse = chatClient.prompt()
                    .system(SELF_RAG_SYSTEM_PROMPT)
                    .user(buildUserPrompt(query, contextStr))
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("Self-RAG generation failed: {}", e.getMessage());
            return standardGeneration(query, context, startTime);
        }

        // Parse reflection tokens
        List<ReflectedClaim> claims = parseReflectionTokens(rawResponse);

        // Identify uncertain claims
        List<String> uncertainClaims = claims.stream()
                .filter(c -> c.token() == ReflectionToken.UNCERTAIN || c.token() == ReflectionToken.UNSUPPORTED)
                .map(ReflectedClaim::claim)
                .toList();

        // Calculate overall confidence
        double confidence = calculateConfidence(claims);

        // Determine if re-retrieval is needed
        boolean needsReRetrieval = reRetrieveOnUncertain && uncertainClaims.size() > maxUncertainClaims;

        // Clean response (remove reflection tokens for user display)
        String cleanResponse = cleanReflectionTokens(rawResponse);

        long elapsed = System.currentTimeMillis() - startTime;

        // Log reasoning step
        Map<String, Object> metrics = Map.of(
                "totalClaims", claims.size(),
                "supportedClaims", claims.stream().filter(c -> c.token() == ReflectionToken.SUPPORTED).count(),
                "inferredClaims", claims.stream().filter(c -> c.token() == ReflectionToken.INFERRED).count(),
                "uncertainClaims", uncertainClaims.size(),
                "confidence", confidence,
                "needsReRetrieval", needsReRetrieval,
                "elapsed", elapsed
        );

        reasoningTracer.addStep(StepType.GENERATION,
                "Self-RAG Reflective Generation",
                String.format("%d claims analyzed: %.0f%% confidence, %d uncertain",
                        claims.size(), confidence * 100, uncertainClaims.size()),
                elapsed,
                metrics);

        log.info("Self-RAG: {} claims, {:.0f}% confidence, {} uncertain ({}ms)",
                claims.size(), confidence * 100, uncertainClaims.size(), elapsed);

        return new SelfRagResult(rawResponse, cleanResponse, claims, needsReRetrieval,
                uncertainClaims, confidence, metrics);
    }

    /**
     * Perform verification on a generated response.
     * Useful for post-hoc verification of non-Self-RAG responses.
     */
    public SelfRagResult verifyResponse(String response, List<Document> context) {
        long startTime = System.currentTimeMillis();

        String contextStr = buildContextString(context);
        String verificationPrompt = """
                Analyze this response and verify each claim against the provided context.
                Mark each claim with: [SUPPORTED], [INFERRED], [UNCERTAIN], or [UNSUPPORTED].

                RESPONSE TO VERIFY:
                %s

                CONTEXT:
                %s

                Provide the response with reflection tokens added:
                """.formatted(response, contextStr);

        try {
            String verifiedResponse = chatClient.prompt()
                    .system("You verify claims against provided context.")
                    .user(verificationPrompt)
                    .call()
                    .content();

            List<ReflectedClaim> claims = parseReflectionTokens(verifiedResponse);
            List<String> uncertainClaims = claims.stream()
                    .filter(c -> c.token() == ReflectionToken.UNCERTAIN || c.token() == ReflectionToken.UNSUPPORTED)
                    .map(ReflectedClaim::claim)
                    .toList();

            double confidence = calculateConfidence(claims);
            long elapsed = System.currentTimeMillis() - startTime;

            return new SelfRagResult(verifiedResponse, response, claims, false,
                    uncertainClaims, confidence, Map.of("mode", "verification", "elapsed", elapsed));

        } catch (Exception e) {
            log.error("Self-RAG verification failed: {}", e.getMessage());
            return new SelfRagResult(response, response, List.of(), false, List.of(), 0.5,
                    Map.of("error", e.getMessage()));
        }
    }

    /**
     * Parse reflection tokens from response.
     */
    private List<ReflectedClaim> parseReflectionTokens(String response) {
        List<ReflectedClaim> claims = new ArrayList<>();

        // Parse SUPPORTED claims
        Matcher supportedMatcher = SUPPORTED_PATTERN.matcher(response);
        while (supportedMatcher.find()) {
            claims.add(new ReflectedClaim(
                    supportedMatcher.group(1).trim(),
                    ReflectionToken.SUPPORTED,
                    null,
                    0.95));
        }

        // Parse INFERRED claims
        Matcher inferredMatcher = INFERRED_PATTERN.matcher(response);
        while (inferredMatcher.find()) {
            claims.add(new ReflectedClaim(
                    inferredMatcher.group(1).trim(),
                    ReflectionToken.INFERRED,
                    null,
                    0.75));
        }

        // Parse UNCERTAIN claims
        Matcher uncertainMatcher = UNCERTAIN_PATTERN.matcher(response);
        while (uncertainMatcher.find()) {
            claims.add(new ReflectedClaim(
                    uncertainMatcher.group(1).trim(),
                    ReflectionToken.UNCERTAIN,
                    null,
                    0.4));
        }

        // Parse UNSUPPORTED claims
        Matcher unsupportedMatcher = UNSUPPORTED_PATTERN.matcher(response);
        while (unsupportedMatcher.find()) {
            claims.add(new ReflectedClaim(
                    unsupportedMatcher.group(1).trim(),
                    ReflectionToken.UNSUPPORTED,
                    null,
                    0.1));
        }

        return claims;
    }

    /**
     * Remove reflection tokens from response for clean user display.
     */
    private String cleanReflectionTokens(String response) {
        return ALL_TOKENS_PATTERN.matcher(response).replaceAll("").trim()
                .replaceAll("\\s+", " ");
    }

    /**
     * Calculate overall confidence based on claim distribution.
     */
    private double calculateConfidence(List<ReflectedClaim> claims) {
        if (claims.isEmpty()) return 0.5; // Neutral if no claims parsed

        double totalScore = claims.stream()
                .mapToDouble(ReflectedClaim::confidence)
                .sum();

        return totalScore / claims.size();
    }

    /**
     * Build context string from documents.
     */
    private String buildContextString(List<Document> context) {
        if (context == null || context.isEmpty()) {
            return "[No context provided]";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < context.size(); i++) {
            sb.append("[Document ").append(i + 1).append("]\n");
            sb.append(context.get(i).getContent());
            sb.append("\n\n");
        }
        return sb.toString();
    }

    /**
     * Build user prompt with query and context.
     */
    private String buildUserPrompt(String query, String context) {
        return """
                CONTEXT:
                %s

                QUESTION: %s

                Answer the question using the context. Mark each claim with reflection tokens.
                """.formatted(context, query);
    }

    /**
     * Fallback to standard generation without reflection.
     */
    private SelfRagResult standardGeneration(String query, List<Document> context, long startTime) {
        String contextStr = buildContextString(context);
        try {
            String response = chatClient.prompt()
                    .user("Context: " + contextStr + "\n\nQuestion: " + query)
                    .call()
                    .content();

            long elapsed = System.currentTimeMillis() - startTime;
            return new SelfRagResult(response, response, List.of(), false, List.of(), 0.7,
                    Map.of("mode", "standard", "elapsed", elapsed));
        } catch (Exception e) {
            return new SelfRagResult("Unable to generate response.", "Unable to generate response.",
                    List.of(), false, List.of(), 0.0, Map.of("error", e.getMessage()));
        }
    }

    public boolean isEnabled() {
        return enabled;
    }
}
