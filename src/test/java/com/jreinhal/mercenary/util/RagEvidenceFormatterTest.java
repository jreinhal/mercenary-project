package com.jreinhal.mercenary.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RagEvidenceFormatterTest {

    @Test
    void buildDocumentHeaderIncludesPageWhenPresent() {
        Map<String, Object> meta = Map.of(
                "source", "file.pdf",
                "page_number", 3
        );

        assertEquals("[file.pdf]\nPage: 3\n", RagEvidenceFormatter.buildDocumentHeader(meta));
        assertEquals(" p. 3", RagEvidenceFormatter.buildCitationPageSuffix(meta));
    }

    @Test
    void buildDocumentHeaderIncludesPageRangeWhenPresent() {
        Map<String, Object> meta = Map.of(
                "source", "file.pdf",
                "page_number", 3,
                "end_page_number", 5
        );

        assertEquals("[file.pdf]\nPages: 3-5\n", RagEvidenceFormatter.buildDocumentHeader(meta));
        assertEquals(" pp. 3-5", RagEvidenceFormatter.buildCitationPageSuffix(meta));
    }

    @Test
    void buildDocumentHeaderOmitsPageWhenMissing() {
        Map<String, Object> meta = Map.of(
                "source", "file.pdf"
        );

        assertEquals("[file.pdf]\n", RagEvidenceFormatter.buildDocumentHeader(meta));
        assertEquals("", RagEvidenceFormatter.buildCitationPageSuffix(meta));
    }

    @Test
    void buildDocumentHeaderFallsBackToFilenameKey() {
        Map<String, Object> meta = Map.of(
                "filename", "alt.txt",
                "page_number", 1
        );

        assertEquals("[alt.txt]\nPage: 1\n", RagEvidenceFormatter.buildDocumentHeader(meta));
    }

    @Test
    void buildDocumentHeaderHandlesNullMeta() {
        assertEquals("[Unknown_Document.txt]\n", RagEvidenceFormatter.buildDocumentHeader(null));
        assertEquals("", RagEvidenceFormatter.buildCitationPageSuffix(null));
    }
}

