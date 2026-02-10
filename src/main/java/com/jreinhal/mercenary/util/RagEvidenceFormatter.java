package com.jreinhal.mercenary.util;

import java.util.Map;
import org.springframework.lang.Nullable;

/**
 * Shared formatting for evidence context and citations.
 *
 * Note: Citation parsers in the codebase currently expect the filename to appear
 * in brackets as "[file.ext]". Any page hint is appended outside the brackets
 * (e.g., "[file.pdf] p. 3") so existing citation detection remains stable.
 */
public final class RagEvidenceFormatter {
    private RagEvidenceFormatter() {
    }

    public static String buildDocumentHeader(@Nullable Map<String, Object> meta) {
        String filename = "Unknown_Document.txt";
        if (meta != null) {
            Object source = meta.get("source");
            if (source == null) {
                source = meta.get("filename");
            }
            String candidate = normalize(source);
            if (!candidate.isBlank()) {
                filename = candidate;
            }
        }

        StringBuilder header = new StringBuilder();
        header.append("[").append(filename).append("]\n");

        String pageLine = buildPageLine(meta);
        if (!pageLine.isBlank()) {
            header.append(pageLine).append("\n");
        }

        return header.toString();
    }

    public static String buildCitationPageSuffix(@Nullable Map<String, Object> meta) {
        String page = normalize(meta != null ? meta.get("page_number") : null);
        if (page.isBlank()) {
            return "";
        }

        String endPage = normalize(meta != null ? meta.get("end_page_number") : null);
        if (!endPage.isBlank() && !endPage.equals(page)) {
            return " pp. " + page + "-" + endPage;
        }
        return " p. " + page;
    }

    private static String buildPageLine(@Nullable Map<String, Object> meta) {
        String page = normalize(meta != null ? meta.get("page_number") : null);
        if (page.isBlank()) {
            return "";
        }

        String endPage = normalize(meta != null ? meta.get("end_page_number") : null);
        if (!endPage.isBlank() && !endPage.equals(page)) {
            return "Pages: " + page + "-" + endPage;
        }
        return "Page: " + page;
    }

    private static String normalize(@Nullable Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value).trim();
    }
}

