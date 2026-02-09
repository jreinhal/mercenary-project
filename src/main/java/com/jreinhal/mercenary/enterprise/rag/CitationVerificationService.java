package com.jreinhal.mercenary.enterprise.rag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

@Service
public class CitationVerificationService {
    private static final Logger log = LoggerFactory.getLogger(CitationVerificationService.class);
    private final ChatClient chatClient;
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(?:Doc(?:ument)?\\s*)?(\\d+)\\]|\\[Source:\\s*([^\\]]+)\\]", 2);

    public CitationVerificationService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public VerificationReport verifyResponse(String response, List<Document> documents) {
        log.debug("Verifying citations in response ({} chars) against {} documents", response.length(), documents.size());
        ArrayList<CitationResult> citations = new ArrayList<CitationResult>();
        ArrayList<String> warnings = new ArrayList<String>();
        Matcher matcher = CITATION_PATTERN.matcher(response);
        while (matcher.find()) {
            String citation = matcher.group();
            int docIndex = -1;
            if (matcher.group(1) != null) {
                docIndex = Integer.parseInt(matcher.group(1)) - 1;
            } else if (matcher.group(2) != null) {
                String sourceName = matcher.group(2).trim();
                docIndex = this.findDocumentBySource(documents, sourceName);
            }
            String surroundingText = this.extractSurroundingText(response, matcher.start(), 150);
            CitationResult result = this.verifyCitation(citation, docIndex, surroundingText, documents);
            citations.add(result);
            if (result.verified()) continue;
            warnings.add("Citation " + citation + " could not be verified: " + result.explanation());
        }
        List<String> uncitedClaims = this.findUncitedClaims(response, documents);
        if (!uncitedClaims.isEmpty()) {
            warnings.add("Found " + uncitedClaims.size() + " claims without citations");
        }
        int verifiedCount = (int)citations.stream().filter(CitationResult::verified).count();
        double overallAccuracy = citations.isEmpty() ? 0.0 : citations.stream().mapToDouble(CitationResult::accuracy).average().orElse(0.0);
        String annotatedResponse = this.annotateResponse(response, citations);
        return new VerificationReport(response, annotatedResponse, citations, citations.size(), verifiedCount, overallAccuracy, uncitedClaims, warnings);
    }

    private CitationResult verifyCitation(String citation, int docIndex, String surroundingText, List<Document> documents) {
        if (docIndex < 0 || docIndex >= documents.size()) {
            return new CitationResult(citation, docIndex, "unknown", false, 0.0, null, "Document index out of range");
        }
        Document doc = documents.get(docIndex);
        String docContent = doc.getContent();
        String docSource = (String)(doc.getMetadata().getOrDefault("source", "Document " + (docIndex + 1)));
        String prompt = "Verify if this claim is accurately represented in the source document.\n\nCLAIM (from response):\n\"%s\"\n\nSOURCE DOCUMENT:\n%s\n\nRespond in this exact format:\nVERIFIED: [yes/no/partial]\nACCURACY: [0.0-1.0]\nMATCHING_TEXT: [quote the relevant text from the source, or \"none\"]\nEXPLANATION: [brief explanation]\n".formatted(surroundingText, this.truncate(docContent, 2000));
        try {
            String llmResponse = this.chatClient.prompt().user(prompt).call().content();
            return this.parseCitationVerification(citation, docIndex, docSource, llmResponse);
        }
        catch (Exception e) {
            log.warn("Error verifying citation: {}", e.getMessage());
            return new CitationResult(citation, docIndex, docSource, false, 0.0, null, "Citation verification temporarily unavailable");
        }
    }

    private CitationResult parseCitationVerification(String citation, int docIndex, String docSource, String response) {
        boolean verified = response.toLowerCase().contains("verified: yes") || response.toLowerCase().contains("verified: partial");
        double accuracy = 0.5;
        try {
            int accIdx = response.toLowerCase().indexOf("accuracy:");
            if (accIdx >= 0) {
                String accStr = response.substring(accIdx + 9).trim().split("\\s")[0];
                accuracy = Double.parseDouble(accStr);
            }
        }
        catch (Exception accIdx) {
            // empty catch block
        }
        String matchingText = null;
        try {
            int mtIdx = response.toLowerCase().indexOf("matching_text:");
            if (mtIdx >= 0) {
                int endIdx = response.toLowerCase().indexOf("explanation:", mtIdx);
                if (endIdx < 0) {
                    endIdx = response.length();
                }
                if ((matchingText = response.substring(mtIdx + 14, endIdx).trim()).toLowerCase().equals("none")) {
                    matchingText = null;
                }
            }
        }
        catch (Exception mtIdx) {
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
        return new CitationResult(citation, docIndex, docSource, verified, accuracy, matchingText, explanation);
    }

    private List<String> findUncitedClaims(String response, List<Document> documents) {
        String[] sentences = response.split("(?<=[.!?])\\s+");
        ArrayList<String> uncited = new ArrayList<String>();
        for (String sentence : sentences) {
            if ((sentence = sentence.trim()).isEmpty() || CITATION_PATTERN.matcher(sentence).find() || this.isMetaSentence(sentence) || !this.looksLikeFactualClaim(sentence)) continue;
            uncited.add(sentence);
        }
        return uncited;
    }

    private boolean isMetaSentence(String sentence) {
        String lower = sentence.toLowerCase();
        return lower.startsWith("based on") || lower.startsWith("according to") || lower.startsWith("the documents") || lower.startsWith("this answer") || lower.contains("provided documents") || lower.contains("available information");
    }

    private boolean looksLikeFactualClaim(String sentence) {
        return sentence.matches(".*\\d+.*") || sentence.matches(".*[A-Z][a-z]+\\s+[A-Z][a-z]+.*") || sentence.contains(" is ") || sentence.contains(" was ") || sentence.contains(" are ") || sentence.contains(" were ");
    }

    private String extractSurroundingText(String text, int citationPos, int chars) {
        int end;
        int start = Math.max(0, citationPos - chars);
        String substring = text.substring(start, end = Math.min(text.length(), citationPos + chars));
        int sentenceStart = substring.lastIndexOf(". ", chars);
        if (sentenceStart > 0) {
            substring = substring.substring(sentenceStart + 2);
        }
        return substring.trim();
    }

    private int findDocumentBySource(List<Document> documents, String sourceName) {
        for (int i = 0; i < documents.size(); ++i) {
            String docSource = String.valueOf(documents.get(i).getMetadata().getOrDefault("source", ""));
            if (!docSource.toLowerCase().contains(sourceName.toLowerCase())) continue;
            return i;
        }
        return -1;
    }

    private String annotateResponse(String response, List<CitationResult> citations) {
        StringBuilder annotated = new StringBuilder(response);
        ArrayList<int[]> positions = new ArrayList<int[]>();
        Matcher matcher = CITATION_PATTERN.matcher(response);
        int citationIdx = 0;
        while (matcher.find() && citationIdx < citations.size()) {
            positions.add(new int[]{matcher.end(), citationIdx++});
        }
        Collections.reverse(positions);
        for (int[] pos : positions) {
            CitationResult result = citations.get(pos[1]);
            String marker = result.verified() ? " \u2713" : " \u26a0";
            annotated.insert(pos[0], marker);
        }
        return annotated.toString();
    }

    public String addCitations(String response, List<Document> documents) {
        String prompt = "Add proper citations to this response. For each factual claim, add a citation\nin the format [Doc N] where N is the document number (1-based).\n\nDocuments available:\n%s\n\nResponse to cite:\n%s\n\nReturn the response with citations added. Do not change the content, only add citations.\n".formatted(this.formatDocuments(documents), response);
        try {
            return this.chatClient.prompt().user(prompt).call().content();
        }
        catch (Exception e) {
            log.error("Error adding citations: {}", e.getMessage());
            return response;
        }
    }

    private String formatDocuments(List<Document> documents) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < documents.size(); ++i) {
            Document doc = documents.get(i);
            String source = String.valueOf(doc.getMetadata().getOrDefault("source", "Unknown"));
            sb.append("Document ").append(i + 1).append(" (").append(source).append("):\n");
            sb.append(this.truncate(doc.getContent(), 500)).append("\n\n");
        }
        return sb.toString();
    }

    private String truncate(String str, int maxLen) {
        if (str == null) {
            return "";
        }
        return str.length() <= maxLen ? str : str.substring(0, maxLen) + "...";
    }

    public record CitationResult(String citationText, int documentIndex, String documentSource, boolean verified, double accuracy, String quotedText, String explanation) {
    }

    public record VerificationReport(String originalResponse, String annotatedResponse, List<CitationResult> citations, int totalCitations, int verifiedCitations, double overallAccuracy, List<String> uncitedClaims, List<String> warnings) {
    }
}
