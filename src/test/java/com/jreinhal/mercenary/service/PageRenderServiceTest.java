package com.jreinhal.mercenary.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PageRenderServiceTest {

    private PageRenderService pageRenderService;

    @BeforeEach
    void setUp() {
        pageRenderService = new PageRenderService();
        ReflectionTestUtils.setField(pageRenderService, "renderDpi", 72);
        ReflectionTestUtils.setField(pageRenderService, "maxOutputPixels", 4_000_000);
    }

    @Test
    void renderPagePngReturnsImageBytesAndMetadata() throws Exception {
        byte[] pdfBytes = buildPdfWithPages(2);
        PageRenderService.RenderedImage rendered = pageRenderService.renderPagePng(pdfBytes, 1);

        assertTrue(rendered.imageBytes().length > 0);
        assertTrue(rendered.width() > 0);
        assertTrue(rendered.height() > 0);
        assertTrue(rendered.pageCount() == 2);
    }

    @Test
    void renderRegionPngReturnsCroppedImage() throws Exception {
        byte[] pdfBytes = buildPdfWithPages(1);
        PageRenderService.RenderedImage rendered = pageRenderService.renderRegionPng(
                pdfBytes, 1, 0, 0, 100, 100, 10, 10);

        assertTrue(rendered.imageBytes().length > 0);
        assertTrue(rendered.width() > 0);
        assertTrue(rendered.height() > 0);
    }

    @Test
    void renderPagePngRejectsOutOfRangePage() throws Exception {
        byte[] pdfBytes = buildPdfWithPages(1);
        assertThrows(IllegalArgumentException.class, () -> pageRenderService.renderPagePng(pdfBytes, 2));
    }

    @Test
    void renderRegionPngRejectsInvalidDimensions() throws Exception {
        byte[] pdfBytes = buildPdfWithPages(1);
        assertThrows(IllegalArgumentException.class, () ->
                pageRenderService.renderRegionPng(pdfBytes, 1, 0, 0, 0, 100, 10, 10));
    }

    private static byte[] buildPdfWithPages(int pageCount) throws Exception {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pageCount; i++) {
                doc.addPage(new PDPage());
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
