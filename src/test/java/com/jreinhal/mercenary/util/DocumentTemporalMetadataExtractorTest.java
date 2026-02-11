package com.jreinhal.mercenary.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayOutputStream;
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
}

