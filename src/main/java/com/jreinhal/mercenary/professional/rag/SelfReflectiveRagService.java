package com.jreinhal.mercenary.professional.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Self-Reflective RAG service for hallucination detection and answer verification.
 *
 * PROFESSIONAL EDITION - Available in professional, medical, and government builds.
 *
 * Implements a multi-step verification process:
 * 1. Generate initial answer from retrieved documents
 * 2. Extract factual claims from the answer
 * 3. Verify each claim against source documents
 * 4. Flag unsupported claims as potential hallucinations
 * 5. Regenerate answer if confidence is below threshold
 */
@Service
public class SelfReflectiveRagService {

    private static final Logger log = LoggerFactory.getLogger(SelfReflectiveRagService.class);

    private final ChatClient chatClient;

    // Confidence threshold for accepting an answer
    private static final double CONFIDENCE_THRESHOLD = 0.75;

    // Maximum reflection iterations to prevent infinite loops
    private static final int MAX_REFLECTIONS = 3;

    public SelfReflectiveRagService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * Verification result for a single claim.
     */
    public record ClaimVerification(
        String claim,
        boolean supported,
        String sourceDocument,
        double confidence,
        String explanation
    ) {}

    /**
     * Result of self-reflective RAG process.
     */
    public record ReflectiveResult(
        String answer,
        List<ClaimVerification> verifications,
        double overallConfidence,
        int reflectionIterations,
        boolean containsHallucinations,
        List<String> warnings
    ) {}

    /**
     * Generate an answer with self-reflection and hallucination detection.
     */
    public ReflectiveResult generateWithReflection(String query, List<Document> retrievedDocs) {
        log.debug("Starting self-reflective RAG for query: {}", truncate(query, 100));

        String context = buildContext(retrievedDocs);
        List<String> warnings = new ArrayList<>();

        String currentAnswer = null;
        List<ClaimVerification> verifications = null;
        double confidence = 0.0;
        int iterations = 0;

        while (iterations < MAX_REFLECTIONS) {
            iterations++;

            // Step 1: Generate answer
            currentAnswer = generateAnswer(query, context, currentAnswer, verifications);

            // Step 2: Extract and verify claims
            verifications = verifyClaims(currentAnswer, retrievedDocs);

            // Step 3: Calculate confidence
            confidence = calculateConfidence(verifications);

            log.debug("Reflection iteration {}: confidence = {}", iterations, confidence);

            // Step 4: Check if answer is acceptable
            if (confidence >= CONFIDENCE_THRESHOLD) {
                break;
            }

            // Add warning about low confidence
            warnings.add("Iteration " + iterations + ": confidence " +
                String.format("%.1f%%", confidence * 100) + " below threshold");
        }

        // Check for hallucinations
        boolean hasHallucinations = verifications.stream()
            .anyMatch(v -> !v.supported() && v.confidence() < 0.3);

        if (hasHallucinations) {
            warnings.add("WARNING: Answer may contain unsupported claims. Please verify with source documents.");
        }

        return new ReflectiveResult(
            currentAnswer,
            verifications,
            confidence,
            iterations,
            hasHallucinations,
            warnings
        );
    }

    /**
     * Generate an answer, optionally incorporating feedback from previous verification.
     */
    private String generateAnswer(String query, String context,
                                  String previousAnswer, List<ClaimVerification> previousVerifications) {

        StringBuilder systemPrompt = new StringBuilder();
        systemPrompt.append("""
            You are a precise intelligence analyst. Answer the question based ONLY on the provided context.

            CRITICAL RULES:
            1. Only state facts that are directly supported by the context
            2. If information is not in the context, say "The provided documents do not contain information about..."
            3. Do not make inferences beyond what is explicitly stated
            4. Cite specific documents when making claims
            5. If uncertain, express that uncertainty clearly
            """);

        // Add feedback from previous verification if available
        if (previousAnswer != null && previousVerifications != null) {
            List<ClaimVerification> unsupported = previousVerifications.stream()
                .filter(v -> !v.supported())
                .toList();

            if (!unsupported.isEmpty()) {
                systemPrompt.append("\n\nPREVIOUS ANSWER HAD UNSUPPORTED CLAIMS:\n");
                for (ClaimVerification v : unsupported) {
                    systemPrompt.append("- \"").append(v.claim())
                        .append("\" - ").append(v.explanation()).append("\n");
                }
                systemPrompt.append("\nPlease revise your answer to remove or qualify these claims.");
            }
        }

        String userPrompt = "Context:\n" + context + "\n\nQuestion: " + query;

        try {
            return chatClient.prompt()
                .system(systemPrompt.toString())
                .user(userPrompt)
                .call()
                .content();
        } catch (Exception e) {
            log.error("Error generating answer: {}", e.getMessage());
            return "Unable to generate answer: " + e.getMessage();
        }
    }

    /**
     * Extract claims from an answer and verify each against source documents.
     */
    private List<ClaimVerification> verifyClaims(String answer, List<Document> documents) {
        // Extract claims
        List<String> claims = extractClaims(answer);
        List<ClaimVerification> verifications = new ArrayList<>();

        for (String claim : claims) {
            ClaimVerification verification = verifySingleClaim(claim, documents);
            verifications.add(verification);
        }

        return verifications;
    }

    /**
     * Extract factual claims from an answer.
     */
    private List<String> extractClaims(String answer) {
        String prompt = """
            Extract the main factual claims from this text. Return each claim on a separate line.
            Only extract objective, verifiable statements. Skip opinions or hedged statements.

            Text:
            %s

            Claims (one per line):
            """.formatted(answer);

        try {
            String response = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

            return response.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .filter(line -> !line.startsWith("-")) // Remove bullet points
                .map(line -> line.replaceFirst("^\\d+\\.\\s*", "")) // Remove numbering
                .toList();
        } catch (Exception e) {
            log.warn("Error extracting claims: {}", e.getMessage());
            // Fallback: split by sentences
            return List.of(answer.split("(?<=[.!?])\\s+"));
        }
    }

    /**
     * Verify a single claim against source documents.
     */
    private ClaimVerification verifySingleClaim(String claim, List<Document> documents) {
        // Build context from documents
        StringBuilder docContext = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            docContext.append("Document ").append(i + 1).append(":\n")
                .append(documents.get(i).getContent()).append("\n\n");
        }

        String prompt = """
            Verify if this claim is supported by the provided documents.

            Claim: "%s"

            Documents:
            %s

            Respond in this exact format:
            SUPPORTED: [yes/no/partial]
            CONFIDENCE: [0.0-1.0]
            SOURCE: [document number or "none"]
            EXPLANATION: [brief explanation]
            """.formatted(claim, docContext);

        try {
            String response = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

            return parseVerificationResponse(claim, response);
        } catch (Exception e) {
            log.warn("Error verifying claim: {}", e.getMessage());
            return new ClaimVerification(claim, false, "error", 0.0, "Verification failed: " + e.getMessage());
        }
    }

    /**
     * Parse the LLM's verification response.
     */
    private ClaimVerification parseVerificationResponse(String claim, String response) {
        boolean supported = response.toLowerCase().contains("supported: yes");
        boolean partial = response.toLowerCase().contains("supported: partial");

        double confidence = 0.5;
        try {
            int confIdx = response.toLowerCase().indexOf("confidence:");
            if (confIdx >= 0) {
                String confStr = response.substring(confIdx + 11).trim().split("\\s")[0];
                confidence = Double.parseDouble(confStr);
            }
        } catch (Exception e) {
            // Use default confidence
        }

        String source = "unknown";
        try {
            int srcIdx = response.toLowerCase().indexOf("source:");
            if (srcIdx >= 0) {
                source = response.substring(srcIdx + 7).trim().split("\n")[0].trim();
            }
        } catch (Exception e) {
            // Use default source
        }

        String explanation = "";
        try {
            int expIdx = response.toLowerCase().indexOf("explanation:");
            if (expIdx >= 0) {
                explanation = response.substring(expIdx + 12).trim();
            }
        } catch (Exception e) {
            explanation = response;
        }

        if (partial) {
            confidence *= 0.7; // Reduce confidence for partial matches
        }

        return new ClaimVerification(claim, supported || partial, source, confidence, explanation);
    }

    /**
     * Calculate overall confidence from individual claim verifications.
     */
    private double calculateConfidence(List<ClaimVerification> verifications) {
        if (verifications.isEmpty()) {
            return 0.5; // No claims = uncertain
        }

        double totalConfidence = 0;
        int supportedCount = 0;

        for (ClaimVerification v : verifications) {
            totalConfidence += v.confidence();
            if (v.supported()) {
                supportedCount++;
            }
        }

        // Weight: 60% average confidence, 40% supported ratio
        double avgConfidence = totalConfidence / verifications.size();
        double supportedRatio = (double) supportedCount / verifications.size();

        return (avgConfidence * 0.6) + (supportedRatio * 0.4);
    }

    /**
     * Build context string from retrieved documents.
     */
    private String buildContext(List<Document> documents) {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            context.append("[Document ").append(i + 1);

            // Add source metadata if available
            Map<String, Object> metadata = doc.getMetadata();
            if (metadata.containsKey("source")) {
                context.append(" - ").append(metadata.get("source"));
            }

            context.append("]\n");
            context.append(doc.getContent());
            context.append("\n\n");
        }
        return context.toString();
    }

    /**
     * Truncate string for logging.
     */
    private String truncate(String str, int maxLength) {
        if (str == null) return "";
        return str.length() <= maxLength ? str : str.substring(0, maxLength) + "...";
    }
}
