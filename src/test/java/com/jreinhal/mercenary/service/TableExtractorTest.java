package com.jreinhal.mercenary.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.test.util.ReflectionTestUtils;

class TableExtractorTest {

    @Test
    void extractTablesReturnsMarkdownDocsForSimpleTextLayerPdf() throws Exception {
        TableExtractor extractor = new TableExtractor();
        ReflectionTestUtils.setField(extractor, "enabled", true);
        ReflectionTestUtils.setField(extractor, "maxTablesPerDocument", 10);
        ReflectionTestUtils.setField(extractor, "maxTableChars", 20000);

        byte[] pdfBytes = buildSimpleTablePdf();
        List<Document> tables = extractor.extractTables(pdfBytes, "simple.pdf");

        assertNotNull(tables);
        assertFalse(tables.isEmpty(), "Expected at least one table to be extracted from the synthetic PDF");

        Document first = tables.get(0);
        assertNotNull(first.getMetadata());
        assertTrue("table".equalsIgnoreCase(String.valueOf(first.getMetadata().get("type"))));
        assertTrue("tabula".equalsIgnoreCase(String.valueOf(first.getMetadata().get("extractor"))));
        assertTrue(first.getContent().contains("|"), "Expected markdown table content");
        assertTrue(first.getContent().contains("---"), "Expected markdown header separator");
    }

    @Test
    void extractTablesReturnsEmptyWhenDisabled() throws Exception {
        TableExtractor extractor = new TableExtractor();
        ReflectionTestUtils.setField(extractor, "enabled", false);
        ReflectionTestUtils.setField(extractor, "maxTablesPerDocument", 10);
        ReflectionTestUtils.setField(extractor, "maxTableChars", 20000);

        byte[] pdfBytes = buildSimpleTablePdf();
        List<Document> tables = extractor.extractTables(pdfBytes, "simple.pdf");

        assertNotNull(tables);
        assertTrue(tables.isEmpty());
    }

    @Test
    void extractTablesReturnsEmptyWhenPdfIsInvalid() {
        TableExtractor extractor = new TableExtractor();
        ReflectionTestUtils.setField(extractor, "enabled", true);
        ReflectionTestUtils.setField(extractor, "maxTablesPerDocument", 10);
        ReflectionTestUtils.setField(extractor, "maxTableChars", 20000);

        byte[] notPdf = "not a pdf".getBytes(StandardCharsets.UTF_8);
        List<Document> tables = extractor.extractTables(notPdf, "bad.pdf");

        assertNotNull(tables);
        assertTrue(tables.isEmpty());
    }

    @Test
    void extractTablesAcceptsBlankFilename() throws Exception {
        TableExtractor extractor = new TableExtractor();
        ReflectionTestUtils.setField(extractor, "enabled", true);
        ReflectionTestUtils.setField(extractor, "maxTablesPerDocument", 10);
        ReflectionTestUtils.setField(extractor, "maxTableChars", 20000);

        byte[] pdfBytes = buildSimpleTablePdf();
        List<Document> tables = extractor.extractTables(pdfBytes, " ");

        assertNotNull(tables);
        assertFalse(tables.isEmpty());
    }

    @Test
    void extractTablesReturnsEmptyWhenPdfBytesNull() {
        TableExtractor extractor = new TableExtractor();
        ReflectionTestUtils.setField(extractor, "enabled", true);
        ReflectionTestUtils.setField(extractor, "maxTablesPerDocument", 10);
        ReflectionTestUtils.setField(extractor, "maxTableChars", 20000);

        List<Document> tables = extractor.extractTables(null, "simple.pdf");

        assertNotNull(tables);
        assertTrue(tables.isEmpty());
    }

    @Test
    void extractTablesReturnsEmptyWhenPdfBytesEmpty() {
        TableExtractor extractor = new TableExtractor();
        ReflectionTestUtils.setField(extractor, "enabled", true);
        ReflectionTestUtils.setField(extractor, "maxTablesPerDocument", 10);
        ReflectionTestUtils.setField(extractor, "maxTableChars", 20000);

        List<Document> tables = extractor.extractTables(new byte[0], "simple.pdf");

        assertNotNull(tables);
        assertTrue(tables.isEmpty());
    }

    @Test
    void extractTablesAcceptsNullFilename() throws Exception {
        TableExtractor extractor = new TableExtractor();
        ReflectionTestUtils.setField(extractor, "enabled", true);
        ReflectionTestUtils.setField(extractor, "maxTablesPerDocument", 10);
        ReflectionTestUtils.setField(extractor, "maxTableChars", 20000);

        byte[] pdfBytes = buildSimpleTablePdf();
        List<Document> tables = extractor.extractTables(pdfBytes, null);

        assertNotNull(tables);
        assertFalse(tables.isEmpty());
    }

    @Test
    void extractTablesHonorsMaxTablesPerDocument() throws Exception {
        TableExtractor extractor = new TableExtractor();
        ReflectionTestUtils.setField(extractor, "enabled", true);
        ReflectionTestUtils.setField(extractor, "maxTablesPerDocument", 0);
        ReflectionTestUtils.setField(extractor, "maxTableChars", 20000);

        byte[] pdfBytes = buildSimpleTablePdf();
        List<Document> tables = extractor.extractTables(pdfBytes, "simple.pdf");

        assertNotNull(tables);
        assertTrue(tables.isEmpty());
    }

    @Test
    void extractTablesTruncatesOversizedTables() throws Exception {
        TableExtractor extractor = new TableExtractor();
        ReflectionTestUtils.setField(extractor, "enabled", true);
        ReflectionTestUtils.setField(extractor, "maxTablesPerDocument", 10);
        ReflectionTestUtils.setField(extractor, "maxTableChars", 40);

        byte[] pdfBytes = buildSimpleTablePdf();
        List<Document> tables = extractor.extractTables(pdfBytes, "simple.pdf");

        assertNotNull(tables);
        assertFalse(tables.isEmpty());

        Document first = tables.get(0);
        assertNotNull(first.getMetadata());
        assertTrue(Boolean.TRUE.equals(first.getMetadata().get("table_truncated")));
        assertNotNull(first.getMetadata().get("table_original_char_count"));
        assertTrue(first.getContent().length() <= 40);
        assertTrue(first.getContent().contains("[TRUNCATED]"));
    }

    private static byte[] buildSimpleTablePdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                writeCell(cs, 50, 700, "A");
                writeCell(cs, 150, 700, "B");
                writeCell(cs, 50, 680, "1");
                writeCell(cs, 150, 680, "2");
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static void writeCell(PDPageContentStream cs, float x, float y, String text) throws Exception {
        cs.beginText();
        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }
}
