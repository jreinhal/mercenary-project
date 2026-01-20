package com.jreinhal.mercenary.rag.selfrag;

import com.jreinhal.mercenary.reasoning.ReasoningStep;
import com.jreinhal.mercenary.reasoning.ReasoningTracer;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SelfRagService {
    private static final Logger log = LoggerFactory.getLogger(SelfRagService.class);
    private final ChatClient chatClient;
    private final ReasoningTracer reasoningTracer;
    @Value(value="${sentinel.selfrag.enabled:true}")
    private boolean enabled;
    @Value(value="${sentinel.selfrag.max-uncertain-claims:2}")
    private int maxUncertainClaims;
    @Value(value="${sentinel.selfrag.re-retrieve-on-uncertain:true}")
    private boolean reRetrieveOnUncertain;
    private static final Pattern SUPPORTED_PATTERN = Pattern.compile("\\[SUPPORTED\\]\\s*([^\\[]+)", 2);
    private static final Pattern INFERRED_PATTERN = Pattern.compile("\\[INFERRED\\]\\s*([^\\[]+)", 2);
    private static final Pattern UNCERTAIN_PATTERN = Pattern.compile("\\[UNCERTAIN\\]\\s*([^\\[]+)", 2);
    private static final Pattern UNSUPPORTED_PATTERN = Pattern.compile("\\[UNSUPPORTED\\]\\s*([^\\[]+)", 2);
    private static final Pattern ALL_TOKENS_PATTERN = Pattern.compile("\\[(SUPPORTED|INFERRED|UNCERTAIN|UNSUPPORTED)\\]", 2);
    private static final String SELF_RAG_SYSTEM_PROMPT = "You are a self-reflective AI that critically evaluates your own claims.\n\nWhen answering, mark EACH factual claim with a reflection token:\n- [SUPPORTED] Claim directly found in the provided context\n- [INFERRED] Claim reasonably deduced from context\n- [UNCERTAIN] Claim that may need verification - not fully supported\n- [UNSUPPORTED] Claim with no supporting evidence\n\nExample format:\n\"[SUPPORTED] The company was founded in 2010. [INFERRED] Based on the growth trajectory,\nthey likely expanded internationally by 2015. [UNCERTAIN] Their current market share\nmay be around 15%.\"\n\nBe rigorous: if information isn't in the context, mark it [UNCERTAIN] or [UNSUPPORTED].\n";

    public SelfRagService(ChatClient.Builder chatClientBuilder, ReasoningTracer reasoningTracer) {
        this.chatClient = chatClientBuilder.build();
        this.reasoningTracer = reasoningTracer;
    }

    @PostConstruct
    public void init() {
        log.info("Self-RAG Service initialized (enabled={}, maxUncertain={}, reRetrieve={})", new Object[]{this.enabled, this.maxUncertainClaims, this.reRetrieveOnUncertain});
    }

    public SelfRagResult generateWithReflection(String query, List<Document> context) {
        String rawResponse;
        long startTime = System.currentTimeMillis();
        if (!this.enabled) {
            log.debug("Self-RAG disabled, performing standard generation");
            return this.standardGeneration(query, context, startTime);
        }
        String contextStr = this.buildContextString(context);
        try {
            rawResponse = this.chatClient.prompt().system(SELF_RAG_SYSTEM_PROMPT).user(this.buildUserPrompt(query, contextStr)).call().content();
        }
        catch (Exception e) {
            log.error("Self-RAG generation failed: {}", e.getMessage());
            return this.standardGeneration(query, context, startTime);
        }
        List<ReflectedClaim> claims = this.parseReflectionTokens(rawResponse);
        List<String> uncertainClaims = claims.stream().filter(c -> c.token() == ReflectionToken.UNCERTAIN || c.token() == ReflectionToken.UNSUPPORTED).map(ReflectedClaim::claim).toList();
        double confidence = this.calculateConfidence(claims);
        boolean needsReRetrieval = this.reRetrieveOnUncertain && uncertainClaims.size() > this.maxUncertainClaims;
        String cleanResponse = this.cleanReflectionTokens(rawResponse);
        long elapsed = System.currentTimeMillis() - startTime;
        Map<String, Object> metrics = Map.of("totalClaims", claims.size(), "supportedClaims", claims.stream().filter(c -> c.token() == ReflectionToken.SUPPORTED).count(), "inferredClaims", claims.stream().filter(c -> c.token() == ReflectionToken.INFERRED).count(), "uncertainClaims", uncertainClaims.size(), "confidence", confidence, "needsReRetrieval", needsReRetrieval, "elapsed", elapsed);
        this.reasoningTracer.addStep(ReasoningStep.StepType.GENERATION, "Self-RAG Reflective Generation", String.format("%d claims analyzed: %.0f%% confidence, %d uncertain", claims.size(), confidence * 100.0, uncertainClaims.size()), elapsed, metrics);
        log.info("Self-RAG: {} claims, {:.0f}% confidence, {} uncertain ({}ms)", new Object[]{claims.size(), confidence * 100.0, uncertainClaims.size(), elapsed});
        return new SelfRagResult(rawResponse, cleanResponse, claims, needsReRetrieval, uncertainClaims, confidence, metrics);
    }

    public SelfRagResult verifyResponse(String response, List<Document> context) {
        long startTime = System.currentTimeMillis();
        String contextStr = this.buildContextString(context);
        String verificationPrompt = "Analyze this response and verify each claim against the provided context.\nMark each claim with: [SUPPORTED], [INFERRED], [UNCERTAIN], or [UNSUPPORTED].\n\nRESPONSE TO VERIFY:\n%s\n\nCONTEXT:\n%s\n\nProvide the response with reflection tokens added:\n".formatted(response, contextStr);
        try {
            String verifiedResponse = this.chatClient.prompt().system("You verify claims against provided context.").user(verificationPrompt).call().content();
            List<ReflectedClaim> claims = this.parseReflectionTokens(verifiedResponse);
            List<String> uncertainClaims = claims.stream().filter(c -> c.token() == ReflectionToken.UNCERTAIN || c.token() == ReflectionToken.UNSUPPORTED).map(ReflectedClaim::claim).toList();
            double confidence = this.calculateConfidence(claims);
            long elapsed = System.currentTimeMillis() - startTime;
            return new SelfRagResult(verifiedResponse, response, claims, false, uncertainClaims, confidence, Map.of("mode", "verification", "elapsed", elapsed));
        }
        catch (Exception e) {
            log.error("Self-RAG verification failed: {}", e.getMessage());
            return new SelfRagResult(response, response, List.of(), false, List.of(), 0.5, Map.of("error", e.getMessage()));
        }
    }

    private List<ReflectedClaim> parseReflectionTokens(String response) {
        ArrayList<ReflectedClaim> claims = new ArrayList<ReflectedClaim>();
        Matcher supportedMatcher = SUPPORTED_PATTERN.matcher(response);
        while (supportedMatcher.find()) {
            claims.add(new ReflectedClaim(supportedMatcher.group(1).trim(), ReflectionToken.SUPPORTED, null, 0.95));
        }
        Matcher inferredMatcher = INFERRED_PATTERN.matcher(response);
        while (inferredMatcher.find()) {
            claims.add(new ReflectedClaim(inferredMatcher.group(1).trim(), ReflectionToken.INFERRED, null, 0.75));
        }
        Matcher uncertainMatcher = UNCERTAIN_PATTERN.matcher(response);
        while (uncertainMatcher.find()) {
            claims.add(new ReflectedClaim(uncertainMatcher.group(1).trim(), ReflectionToken.UNCERTAIN, null, 0.4));
        }
        Matcher unsupportedMatcher = UNSUPPORTED_PATTERN.matcher(response);
        while (unsupportedMatcher.find()) {
            claims.add(new ReflectedClaim(unsupportedMatcher.group(1).trim(), ReflectionToken.UNSUPPORTED, null, 0.1));
        }
        return claims;
    }

    private String cleanReflectionTokens(String response) {
        return ALL_TOKENS_PATTERN.matcher(response).replaceAll("").trim().replaceAll("\\s+", " ");
    }

    private double calculateConfidence(List<ReflectedClaim> claims) {
        if (claims.isEmpty()) {
            return 0.5;
        }
        double totalScore = claims.stream().mapToDouble(ReflectedClaim::confidence).sum();
        return totalScore / (double)claims.size();
    }

    private String buildContextString(List<Document> context) {
        if (context == null || context.isEmpty()) {
            return "[No context provided]";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < context.size(); ++i) {
            sb.append("[Document ").append(i + 1).append("]\n");
            sb.append(context.get(i).getContent());
            sb.append("\n\n");
        }
        return sb.toString();
    }

    private String buildUserPrompt(String query, String context) {
        return "CONTEXT:\n%s\n\nQUESTION: %s\n\nAnswer the question using the context. Mark each claim with reflection tokens.\n".formatted(context, query);
    }

    private SelfRagResult standardGeneration(String query, List<Document> context, long startTime) {
        String contextStr = this.buildContextString(context);
        try {
            String response = this.chatClient.prompt().user("Context: " + contextStr + "\n\nQuestion: " + query).call().content();
            long elapsed = System.currentTimeMillis() - startTime;
            return new SelfRagResult(response, response, List.of(), false, List.of(), 0.7, Map.of("mode", "standard", "elapsed", elapsed));
        }
        catch (Exception e) {
            return new SelfRagResult("Unable to generate response.", "Unable to generate response.", List.of(), false, List.of(), 0.0, Map.of("error", e.getMessage()));
        }
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public record SelfRagResult(String response, String cleanResponse, List<ReflectedClaim> claims, boolean needsReRetrieval, List<String> uncertainClaims, double overallConfidence, Map<String, Object> metrics) {
    }

    public record ReflectedClaim(String claim, ReflectionToken token, String supportingContext, double confidence) {
    }

    public static enum ReflectionToken {
        SUPPORTED,
        INFERRED,
        UNCERTAIN,
        UNSUPPORTED;

    }
}
