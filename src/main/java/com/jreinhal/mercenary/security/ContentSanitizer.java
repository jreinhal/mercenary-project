package com.jreinhal.mercenary.security;

import java.text.Normalizer;
import java.util.Locale;
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
    private static final Pattern CONTROL_CHAR_PATTERN = Pattern.compile("[\\p{Cntrl}&&[^\\r\\n\\t]]");
    private static final Pattern REPLACEMENT_CHAR_PATTERN = Pattern.compile("\uFFFD+");
    private static final Pattern MULTI_SPACE_PATTERN = Pattern.compile("[ \\t]{2,}");
    private static final Pattern NON_WORD_PATTERN = Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}]+");

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
            String cleanedLine = cleanFormattingLine(line);
            String normalized = Normalizer.normalize(cleanedLine, Normalizer.Form.NFKC);
            boolean hit = false;
            for (Pattern pattern : PromptInjectionPatterns.getPatterns()) {
                if (pattern.matcher(normalized).find()) {
                    hit = true;
                    break;
                }
            }
            sb.append(hit ? REDACTION_MARKER : cleanedLine).append('\n');
        }
        return sb.toString();
    }

    /**
     * Sanitizes model output for display. Removes control/mojibake characters and
     * collapses mirrored duplicate clauses that commonly appear in malformed bullets.
     */
    public static String sanitizeResponseText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String[] lines = text.split("\\r?\\n", -1);
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < lines.length; i++) {
            String cleanedLine = cleanFormattingLine(lines[i]);
            cleanedLine = dedupeMirroredClause(cleanedLine);
            sb.append(cleanedLine);
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString().trim();
    }

    private static String cleanFormattingLine(String line) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        String cleaned = line.replace("\uFEFF", "");
        cleaned = CONTROL_CHAR_PATTERN.matcher(cleaned).replaceAll(" ");
        cleaned = REPLACEMENT_CHAR_PATTERN.matcher(cleaned).replaceAll("");
        // Strip common binary signature noise when it leaks into text-oriented responses.
        cleaned = cleaned.replaceAll("(?i)^(\\d+\\.\\s*)?PK\\s*(?=(this is a zip|test zip|zip archive))", "$1");
        cleaned = cleaned.replaceAll("(?i)^(\\d+\\.\\s*)?Rar!\\s*", "$1");
        cleaned = cleaned.replaceAll("(?i)^(\\d+\\.\\s*)?\\d+\\s*(?=Fake\\s+Java\\s+class\\b)", "$1");
        cleaned = MULTI_SPACE_PATTERN.matcher(cleaned).replaceAll(" ");
        return cleaned.trim();
    }

    private static String dedupeMirroredClause(String line) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        String[] separators = {" - ", " — ", " – "};
        for (String separator : separators) {
            int fromIndex = 0;
            while (fromIndex < line.length()) {
                int index = line.indexOf(separator, fromIndex);
                if (index <= 0 || index + separator.length() >= line.length()) {
                    break;
                }

                String left = line.substring(0, index).trim();
                String right = line.substring(index + separator.length()).trim();
                if (!left.isEmpty() && !right.isEmpty()) {
                    String leftCanonical = canonicalizeForComparison(left);
                    String rightCanonical = canonicalizeForComparison(right);
                    if (!leftCanonical.isEmpty()
                        && !rightCanonical.isEmpty()
                        && (leftCanonical.equals(rightCanonical)
                        || leftCanonical.endsWith(" " + rightCanonical)
                        || rightCanonical.endsWith(" " + leftCanonical))) {
                        return left;
                    }
                }

                fromIndex = index + separator.length();
            }
        }
        return line;
    }

    private static String canonicalizeForComparison(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        normalized = NON_WORD_PATTERN.matcher(normalized).replaceAll(" ").trim();
        normalized = MULTI_SPACE_PATTERN.matcher(normalized).replaceAll(" ");
        return normalized;
    }
}
