package com.jreinhal.mercenary.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Calendar;
import java.util.TimeZone;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

class DocumentTemporalMetadataExtractorTest {

    @Test
    void extractsFromIsoDateText() {
        DocumentTemporalMetadataExtractor.TemporalMetadata meta =
                DocumentTemporalMetadataExtractor.extractFromText("Report Date: 2021-05-03\nTitle: Example");

        assertEquals(2021, meta.documentYear());
        assertNotNull(meta.documentDateEpoch());
        assertEquals("text_date", meta.documentDateSource());
    }

    @Test
    void extractsFromMonthNameText() {
        DocumentTemporalMetadataExtractor.TemporalMetadata meta =
                DocumentTemporalMetadataExtractor.extractFromText("Issued January 2, 2020\nFoo bar");

        assertEquals(2020, meta.documentYear());
        assertNotNull(meta.documentDateEpoch());
    }

    @Test
    void extractsFromUsDateText() {
        DocumentTemporalMetadataExtractor.TemporalMetadata meta =
                DocumentTemporalMetadataExtractor.extractFromText("Dated 12/31/2019\nFoo bar");

        assertEquals(2019, meta.documentYear());
        assertNotNull(meta.documentDateEpoch());
    }

    @Test
    void extractsFromPdfMetadataWhenPresent() throws Exception {
        byte[] bytes;
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            PDDocumentInformation info = new PDDocumentInformation();
            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            cal.clear();
            cal.set(2022, Calendar.JANUARY, 15, 0, 0, 0);
            info.setCreationDate(cal);
            doc.setDocumentInformation(info);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            bytes = baos.toByteArray();
        }

        DocumentTemporalMetadataExtractor.TemporalMetadata meta =
                DocumentTemporalMetadataExtractor.extractFromBytes(bytes, "application/pdf");

        assertEquals(2022, meta.documentYear());
        assertNotNull(meta.documentDateEpoch());
        assertEquals("pdf_metadata", meta.documentDateSource());
    }

    @Test
    void extractsYearOnlyWhenNoFullDateIsPresent() {
        DocumentTemporalMetadataExtractor.TemporalMetadata meta =
                DocumentTemporalMetadataExtractor.extractFromText("Prepared in 2018\nNo specific date here");

        assertEquals(2018, meta.documentYear());
        assertNull(meta.documentDateEpoch());
        assertEquals("text_year", meta.documentDateSource());
    }

    @Test
    void fallsBackToFilenameYearWhenBytesAndTextAreEmpty() {
        DocumentTemporalMetadataExtractor.TemporalMetadata meta =
                DocumentTemporalMetadataExtractor.extract(new byte[0], "text/plain", List.of(), "report-2017.txt");

        assertEquals(2017, meta.documentYear());
        assertNull(meta.documentDateEpoch());
        assertEquals("filename_year", meta.documentDateSource());
    }

    @Test
    void ignoresOutOfRangeFutureDates() {
        // 2099 matches the regex but should be rejected by safeYear().
        DocumentTemporalMetadataExtractor.TemporalMetadata meta =
                DocumentTemporalMetadataExtractor.extractFromText("Report Date: 2099-01-01");

        assertNull(meta.documentYear());
        assertNull(meta.documentDateEpoch());
    }

    @Test
    void extractFromBytesReturnsEmptyForNonPdfOrMalformedPdf() {
        DocumentTemporalMetadataExtractor.TemporalMetadata notPdf =
                DocumentTemporalMetadataExtractor.extractFromBytes("plain text".getBytes(), "text/plain");
        assertTrue(notPdf.isEmpty());

        DocumentTemporalMetadataExtractor.TemporalMetadata malformed =
                DocumentTemporalMetadataExtractor.extractFromBytes("not a real pdf".getBytes(), "application/pdf");
        assertTrue(malformed.isEmpty());
    }

    @Test
    void extractUsesTextBeforeFilenameFallback() {
        org.springframework.ai.document.Document doc =
                new org.springframework.ai.document.Document("Issued on 01/02/2021");

        DocumentTemporalMetadataExtractor.TemporalMetadata meta =
                DocumentTemporalMetadataExtractor.extract(new byte[0], "text/plain", List.of(doc), "report-2017.txt");

        assertEquals(2021, meta.documentYear());
        assertEquals("text_date", meta.documentDateSource());
    }

    @Test
    void returnsEmptyForBlankText() {
        DocumentTemporalMetadataExtractor.TemporalMetadata meta = DocumentTemporalMetadataExtractor.extractFromText("   ");
        assertTrue(meta.isEmpty());
    }

    @Test
    void invalidDateFallsBackToYearOnly() {
        DocumentTemporalMetadataExtractor.TemporalMetadata meta =
                DocumentTemporalMetadataExtractor.extractFromText("Published 2021-02-31 (draft)");

        assertEquals(2021, meta.documentYear());
        assertNull(meta.documentDateEpoch());
        assertEquals("text_year", meta.documentDateSource());
    }

    @Test
    void filenameFallbackSupportsUnderscoreYearBoundary() {
        DocumentTemporalMetadataExtractor.TemporalMetadata meta =
                DocumentTemporalMetadataExtractor.extract(new byte[0], "text/plain", List.of(), "report_2017.txt");

        assertEquals(2017, meta.documentYear());
        assertEquals("filename_year", meta.documentDateSource());
    }

    @Test
    void extractsFromPdfModificationDateWhenCreationDateMissing() throws Exception {
        byte[] bytes;
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            PDDocumentInformation info = new PDDocumentInformation();
            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            cal.clear();
            cal.set(2020, Calendar.MARCH, 20, 0, 0, 0);
            info.setModificationDate(cal);
            doc.setDocumentInformation(info);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            bytes = baos.toByteArray();
        }

        DocumentTemporalMetadataExtractor.TemporalMetadata meta =
                DocumentTemporalMetadataExtractor.extractFromBytes(bytes, "application/pdf");

        assertFalse(meta.isEmpty());
        assertEquals(2020, meta.documentYear());
        assertEquals("pdf_metadata", meta.documentDateSource());
    }

    @Test
    void extractsFromAllMonthNames() {
        String[] months = {
                "January", "February", "March", "April", "May", "June",
                "July", "August", "September", "October", "November", "December"
        };

        for (String month : months) {
            DocumentTemporalMetadataExtractor.TemporalMetadata meta =
                    DocumentTemporalMetadataExtractor.extractFromText("Issued " + month + " 2, 2020");
            assertEquals(2020, meta.documentYear(), "Failed for month: " + month);
            assertNotNull(meta.documentDateEpoch(), "Expected date epoch for month: " + month);
        }
    }
}
