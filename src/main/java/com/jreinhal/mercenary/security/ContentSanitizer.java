package com.jreinhal.mercenary.security;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Shared utility for sanitizing retrieved content against indirect prompt injection.
 * Scans each line using NFKC-normalized text against known injection patterns
 * and replaces matching lines with a redaction marker.
 *
 * Used by both MercenaryController and RagOrchestrationService to defend
 * against indirect prompt injection in RAG-retrieved documents before
 * they reach the synthesis model.
 */
public final class ContentSanitizer {

    public static final String REDACTION_MARKER = "[REDACTED-PROMPT-INJECTION]";

    private ContentSanitizer() {
        // Utility class — no instantiation
    }

    /**
     * Sanitize retrieved content by redacting lines that match known injection patterns.
     *
     * @param content the raw retrieved document content
     * @return sanitized content with injection lines replaced by REDACTION_MARKER
     */
    public static String sanitize(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String[] lines = content.split("\\r?\\n", -1);
        StringBuilder sb = new StringBuilder(content.length());
        for (String line : lines) {
            String normalized = Normalizer.normalize(line, Normalizer.Form.NFKC);
            boolean hit = false;
            for (Pattern pattern : PromptInjectionPatterns.getPatterns()) {
                if (pattern.matcher(normalized).find()) {
                    hit = true;
                    break;
                }
            }
            sb.append(hit ? REDACTION_MARKER : line).append('\n');
        }
        return sb.toString();
    }
}
