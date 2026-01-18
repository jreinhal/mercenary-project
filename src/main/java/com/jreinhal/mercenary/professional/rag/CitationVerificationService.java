package com.jreinhal.mercenary.professional.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Citation verification service for RAG responses.
 *
 * PROFESSIONAL EDITION - Available in professional, medical, and government builds.
 *
 * Ensures that:
 * 1. All claims in responses are properly cited
 * 2. Citations reference actual retrieved documents
 * 3. Cited text accurately reflects source content
 * 4. Citation format is consistent and traceable
 */
@Service
public class CitationVerificationService {

    private static final Logger log = LoggerFactory.getLogger(CitationVerificationService.class);

    private final ChatClient chatClient;

    // Pattern to match citations like [1], [Doc 1], [Source: filename.pdf]
    private static final Pattern CITATION_PATTERN = Pattern.compile(
        "\\[(?:Doc(?:ument)?\\s*)?(\\d+)\\]|\\[Source:\\s*([^\\]]+)\\]",
        Pattern.CASE_INSENSITIVE
    );

    public CitationVerificationService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * Citation verification result.
     */
    public record CitationResult(
        String citationText,
        int documentIndex,
        String documentSource,
        boolean verified,
        double accuracy,
        String quotedText,
        String explanation
    ) {}

    /**
     * Overall verification report.
     */
    public record VerificationReport(
        String originalResponse,
        String annotatedResponse,
        List<CitationResult> citations,
        int totalCitations,
        int verifiedCitations,
        double overallAccuracy,
        List<String> uncitedClaims,
        List<String> warnings
    ) {}

    /**
     * Verify all citations in a response against source documents.
     */
    public VerificationReport verifyResponse(String response, List<Document> documents) {
        log.debug("Verifying citations in response ({} chars) against {} documents",
            response.length(), documents.size());

        List<CitationResult> citations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Extract all citations from response
        Matcher matcher = CITATION_PATTERN.matcher(response);
        while (matcher.find()) {
            String citation = matcher.group();
            int docIndex = -1;

            // Try to get document index
            if (matcher.group(1) != null) {
                docIndex = Integer.parseInt(matcher.group(1)) - 1; // Convert to 0-based
            } else if (matcher.group(2) != null) {
                // Find document by source name
                String sourceName = matcher.group(2).trim();
                docIndex = findDocumentBySource(documents, sourceName);
            }

            // Get surrounding text (the claim being cited)
            String surroundingText = extractSurroundingText(response, matcher.start(), 150);

            // Verify the citation
            CitationResult result = verifyCitation(citation, docIndex, surroundingText, documents);
            citations.add(result);

            if (!result.verified()) {
                warnings.add("Citation " + citation + " could not be verified: " + result.explanation());
            }
        }

        // Check for uncited claims
        List<String> uncitedClaims = findUncitedClaims(response, documents);
        if (!uncitedClaims.isEmpty()) {
            warnings.add("Found " + uncitedClaims.size() + " claims without citations");
        }

        // Calculate statistics
        int verifiedCount = (int) citations.stream().filter(CitationResult::verified).count();
        double overallAccuracy = citations.isEmpty() ? 0.0 :
            citations.stream().mapToDouble(CitationResult::accuracy).average().orElse(0.0);

        // Generate annotated response with verification markers
        String annotatedResponse = annotateResponse(response, citations);

        return new VerificationReport(
            response,
            annotatedResponse,
            citations,
            citations.size(),
            verifiedCount,
            overallAccuracy,
            uncitedClaims,
            warnings
        );
    }

    /**
     * Verify a single citation.
     */
    private CitationResult verifyCitation(String citation, int docIndex,
                                          String surroundingText, List<Document> documents) {

        if (docIndex < 0 || docIndex >= documents.size()) {
            return new CitationResult(
                citation, docIndex, "unknown", false, 0.0, null,
                "Document index out of range"
            );
        }

        Document doc = documents.get(docIndex);
        String docContent = doc.getContent();
        String docSource = (String) doc.getMetadata().getOrDefault("source", "Document " + (docIndex + 1));

        // Use LLM to verify if the claim matches the source
        String prompt = """
            Verify if this claim is accurately represented in the source document.

            CLAIM (from response):
            "%s"

            SOURCE DOCUMENT:
            %s

            Respond in this exact format:
            VERIFIED: [yes/no/partial]
            ACCURACY: [0.0-1.0]
            MATCHING_TEXT: [quote the relevant text from the source, or "none"]
            EXPLANATION: [brief explanation]
            """.formatted(surroundingText, truncate(docContent, 2000));

        try {
            String llmResponse = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

            return parseCitationVerification(citation, docIndex, docSource, llmResponse);
        } catch (Exception e) {
            log.warn("Error verifying citation: {}", e.getMessage());
            return new CitationResult(
                citation, docIndex, docSource, false, 0.0, null,
                "Verification error: " + e.getMessage()
            );
        }
    }

    /**
     * Parse LLM verification response.
     */
    private CitationResult parseCitationVerification(String citation, int docIndex,
                                                     String docSource, String response) {
        boolean verified = response.toLowerCase().contains("verified: yes") ||
                          response.toLowerCase().contains("verified: partial");

        double accuracy = 0.5;
        try {
            int accIdx = response.toLowerCase().indexOf("accuracy:");
            if (accIdx >= 0) {
                String accStr = response.substring(accIdx + 9).trim().split("\\s")[0];
                accuracy = Double.parseDouble(accStr);
            }
        } catch (Exception e) {
            // Use default
        }

        String matchingText = null;
        try {
            int mtIdx = response.toLowerCase().indexOf("matching_text:");
            if (mtIdx >= 0) {
                int endIdx = response.toLowerCase().indexOf("explanation:", mtIdx);
                if (endIdx < 0) endIdx = response.length();
                matchingText = response.substring(mtIdx + 14, endIdx).trim();
                if (matchingText.toLowerCase().equals("none")) {
                    matchingText = null;
                }
            }
        } catch (Exception e) {
            // No matching text found
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

        return new CitationResult(
            citation, docIndex, docSource, verified, accuracy, matchingText, explanation
        );
    }

    /**
     * Find claims in the response that don't have citations.
     */
    private List<String> findUncitedClaims(String response, List<Document> documents) {
        // Split response into sentences
        String[] sentences = response.split("(?<=[.!?])\\s+");
        List<String> uncited = new ArrayList<>();

        for (String sentence : sentences) {
            sentence = sentence.trim();
            if (sentence.isEmpty()) continue;

            // Skip sentences that have citations
            if (CITATION_PATTERN.matcher(sentence).find()) continue;

            // Skip meta-sentences (e.g., "Based on the documents...")
            if (isMetaSentence(sentence)) continue;

            // Check if this looks like a factual claim
            if (looksLikeFactualClaim(sentence)) {
                uncited.add(sentence);
            }
        }

        return uncited;
    }

    /**
     * Check if a sentence is a meta-sentence (about the documents/answer itself).
     */
    private boolean isMetaSentence(String sentence) {
        String lower = sentence.toLowerCase();
        return lower.startsWith("based on") ||
               lower.startsWith("according to") ||
               lower.startsWith("the documents") ||
               lower.startsWith("this answer") ||
               lower.contains("provided documents") ||
               lower.contains("available information");
    }

    /**
     * Check if a sentence looks like a factual claim that should be cited.
     */
    private boolean looksLikeFactualClaim(String sentence) {
        // Simple heuristic: contains numbers, dates, names, or specific statements
        return sentence.matches(".*\\d+.*") ||  // Contains numbers
               sentence.matches(".*[A-Z][a-z]+\\s+[A-Z][a-z]+.*") ||  // Contains proper names
               sentence.contains(" is ") ||
               sentence.contains(" was ") ||
               sentence.contains(" are ") ||
               sentence.contains(" were ");
    }

    /**
     * Extract text surrounding a citation.
     */
    private String extractSurroundingText(String text, int citationPos, int chars) {
        int start = Math.max(0, citationPos - chars);
        int end = Math.min(text.length(), citationPos + chars);

        // Try to start/end at sentence boundaries
        String substring = text.substring(start, end);
        int sentenceStart = substring.lastIndexOf(". ", chars);
        if (sentenceStart > 0) {
            substring = substring.substring(sentenceStart + 2);
        }

        return substring.trim();
    }

    /**
     * Find document index by source name.
     */
    private int findDocumentBySource(List<Document> documents, String sourceName) {
        for (int i = 0; i < documents.size(); i++) {
            String docSource = (String) documents.get(i).getMetadata().getOrDefault("source", "");
            if (docSource.toLowerCase().contains(sourceName.toLowerCase())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Annotate response with verification markers.
     */
    private String annotateResponse(String response, List<CitationResult> citations) {
        // Add verification status after each citation
        StringBuilder annotated = new StringBuilder(response);

        // Sort by position descending to avoid index shifting
        List<int[]> positions = new ArrayList<>();
        Matcher matcher = CITATION_PATTERN.matcher(response);
        int citationIdx = 0;
        while (matcher.find() && citationIdx < citations.size()) {
            positions.add(new int[]{matcher.end(), citationIdx});
            citationIdx++;
        }

        Collections.reverse(positions);

        for (int[] pos : positions) {
            CitationResult result = citations.get(pos[1]);
            String marker = result.verified() ? " ✓" : " ⚠";
            annotated.insert(pos[0], marker);
        }

        return annotated.toString();
    }

    /**
     * Add citations to an uncited response.
     */
    public String addCitations(String response, List<Document> documents) {
        String prompt = """
            Add proper citations to this response. For each factual claim, add a citation
            in the format [Doc N] where N is the document number (1-based).

            Documents available:
            %s

            Response to cite:
            %s

            Return the response with citations added. Do not change the content, only add citations.
            """.formatted(formatDocuments(documents), response);

        try {
            return chatClient.prompt()
                .user(prompt)
                .call()
                .content();
        } catch (Exception e) {
            log.error("Error adding citations: {}", e.getMessage());
            return response;
        }
    }

    /**
     * Format documents for prompts.
     */
    private String formatDocuments(List<Document> documents) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            String source = (String) doc.getMetadata().getOrDefault("source", "Unknown");
            sb.append("Document ").append(i + 1).append(" (").append(source).append("):\n");
            sb.append(truncate(doc.getContent(), 500)).append("\n\n");
        }
        return sb.toString();
    }

    private String truncate(String str, int maxLen) {
        if (str == null) return "";
        return str.length() <= maxLen ? str : str.substring(0, maxLen) + "...";
    }
}
