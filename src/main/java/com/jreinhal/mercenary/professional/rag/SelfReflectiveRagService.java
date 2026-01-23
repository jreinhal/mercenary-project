package com.jreinhal.mercenary.professional.rag;

import com.jreinhal.mercenary.util.LogSanitizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

@Service
public class SelfReflectiveRagService {
    private static final Logger log = LoggerFactory.getLogger(SelfReflectiveRagService.class);
    private final ChatClient chatClient;
    private static final double CONFIDENCE_THRESHOLD = 0.75;
    private static final int MAX_REFLECTIONS = 3;

    public SelfReflectiveRagService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public ReflectiveResult generateWithReflection(String query, List<Document> retrievedDocs) {
        boolean hasHallucinations;
        log.debug("Starting self-reflective RAG for query {}", LogSanitizer.querySummary(query));
        String context = this.buildContext(retrievedDocs);
        ArrayList<String> warnings = new ArrayList<String>();
        String currentAnswer = null;
        List<ClaimVerification> verifications = null;
        double confidence = 0.0;
        int iterations = 0;
        while (iterations < 3) {
            currentAnswer = this.generateAnswer(query, context, currentAnswer, verifications);
            verifications = this.verifyClaims(currentAnswer, retrievedDocs);
            confidence = this.calculateConfidence(verifications);
            log.debug("Reflection iteration {}: confidence = {}", (++iterations), confidence);
            if (confidence >= 0.75) break;
            warnings.add("Iteration " + iterations + ": confidence " + String.format("%.1f%%", confidence * 100.0) + " below threshold");
        }
        if (hasHallucinations = verifications.stream().anyMatch(v -> !v.supported() && v.confidence() < 0.3)) {
            warnings.add("WARNING: Answer may contain unsupported claims. Please verify with source documents.");
        }
        return new ReflectiveResult(currentAnswer, verifications, confidence, iterations, hasHallucinations, warnings);
    }

    private String generateAnswer(String query, String context, String previousAnswer, List<ClaimVerification> previousVerifications) {
        List<ClaimVerification> unsupported;
        StringBuilder systemPrompt = new StringBuilder();
        systemPrompt.append("You are a precise intelligence analyst. Answer the question based ONLY on the provided context.\n\nCRITICAL RULES:\n1. Only state facts that are directly supported by the context\n2. If information is not in the context, say \"The provided documents do not contain information about...\"\n3. Do not make inferences beyond what is explicitly stated\n4. Cite specific documents when making claims\n5. If uncertain, express that uncertainty clearly\n");
        if (previousAnswer != null && previousVerifications != null && !(unsupported = previousVerifications.stream().filter(v -> !v.supported()).toList()).isEmpty()) {
            systemPrompt.append("\n\nPREVIOUS ANSWER HAD UNSUPPORTED CLAIMS:\n");
            for (ClaimVerification v2 : unsupported) {
                systemPrompt.append("- \"").append(v2.claim()).append("\" - ").append(v2.explanation()).append("\n");
            }
            systemPrompt.append("\nPlease revise your answer to remove or qualify these claims.");
        }
        String userPrompt = "Context:\n" + context + "\n\nQuestion: " + query;
        try {
            return this.chatClient.prompt().system(systemPrompt.toString()).user(userPrompt).call().content();
        }
        catch (Exception e) {
            log.error("Error generating answer: {}", e.getMessage());
            return "Unable to generate answer: " + e.getMessage();
        }
    }

    private List<ClaimVerification> verifyClaims(String answer, List<Document> documents) {
        List<String> claims = this.extractClaims(answer);
        ArrayList<ClaimVerification> verifications = new ArrayList<ClaimVerification>();
        for (String claim : claims) {
            ClaimVerification verification = this.verifySingleClaim(claim, documents);
            verifications.add(verification);
        }
        return verifications;
    }

    private List<String> extractClaims(String answer) {
        String prompt = "Extract the main factual claims from this text. Return each claim on a separate line.\nOnly extract objective, verifiable statements. Skip opinions or hedged statements.\n\nText:\n%s\n\nClaims (one per line):\n".formatted(answer);
        try {
            String response = this.chatClient.prompt().user(prompt).call().content();
            return response.lines().map(String::trim).filter(line -> !line.isEmpty()).filter(line -> !line.startsWith("-")).map(line -> line.replaceFirst("^\\d+\\.\\s*", "")).toList();
        }
        catch (Exception e) {
            log.warn("Error extracting claims: {}", e.getMessage());
            return List.of(answer.split("(?<=[.!?])\\s+"));
        }
    }

    private ClaimVerification verifySingleClaim(String claim, List<Document> documents) {
        StringBuilder docContext = new StringBuilder();
        for (int i = 0; i < documents.size(); ++i) {
            docContext.append("Document ").append(i + 1).append(":\n").append(documents.get(i).getContent()).append("\n\n");
        }
        String prompt = "Verify if this claim is supported by the provided documents.\n\nClaim: \"%s\"\n\nDocuments:\n%s\n\nRespond in this exact format:\nSUPPORTED: [yes/no/partial]\nCONFIDENCE: [0.0-1.0]\nSOURCE: [document number or \"none\"]\nEXPLANATION: [brief explanation]\n".formatted(claim, docContext);
        try {
            String response = this.chatClient.prompt().user(prompt).call().content();
            return this.parseVerificationResponse(claim, response);
        }
        catch (Exception e) {
            log.warn("Error verifying claim: {}", e.getMessage());
            return new ClaimVerification(claim, false, "error", 0.0, "Verification failed: " + e.getMessage());
        }
    }

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
        }
        catch (Exception confIdx) {
            // empty catch block
        }
        String source = "unknown";
        try {
            int srcIdx = response.toLowerCase().indexOf("source:");
            if (srcIdx >= 0) {
                source = response.substring(srcIdx + 7).trim().split("\n")[0].trim();
            }
        }
        catch (Exception srcIdx) {
            // empty catch block
        }
        String explanation = "";
        try {
            int expIdx = response.toLowerCase().indexOf("explanation:");
            if (expIdx >= 0) {
                explanation = response.substring(expIdx + 12).trim();
            }
        }
        catch (Exception e) {
            explanation = response;
        }
        if (partial) {
            confidence *= 0.7;
        }
        return new ClaimVerification(claim, supported || partial, source, confidence, explanation);
    }

    private double calculateConfidence(List<ClaimVerification> verifications) {
        if (verifications.isEmpty()) {
            return 0.5;
        }
        double totalConfidence = 0.0;
        int supportedCount = 0;
        for (ClaimVerification v : verifications) {
            totalConfidence += v.confidence();
            if (!v.supported()) continue;
            ++supportedCount;
        }
        double avgConfidence = totalConfidence / (double)verifications.size();
        double supportedRatio = (double)supportedCount / (double)verifications.size();
        return avgConfidence * 0.6 + supportedRatio * 0.4;
    }

    private String buildContext(List<Document> documents) {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < documents.size(); ++i) {
            Document doc = documents.get(i);
            context.append("[Document ").append(i + 1);
            Map metadata = doc.getMetadata();
            if (metadata.containsKey("source")) {
                context.append(" - ").append(metadata.get("source"));
            }
            context.append("]\n");
            context.append(doc.getContent());
            context.append("\n\n");
        }
        return context.toString();
    }

    public record ReflectiveResult(String answer, List<ClaimVerification> verifications, double overallConfidence, int reflectionIterations, boolean containsHallucinations, List<String> warnings) {
    }

    public record ClaimVerification(String claim, boolean supported, String sourceDocument, double confidence, String explanation) {
    }
}
