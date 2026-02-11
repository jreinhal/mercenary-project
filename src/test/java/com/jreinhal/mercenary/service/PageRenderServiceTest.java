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

    @Test
    void renderPageRejectsNullPdfBytes() {
        assertThrows(IllegalArgumentException.class, () -> pageRenderService.renderPagePng(null, 1));
    }

    @Test
    void renderPageRejectsZeroPageNumber() throws Exception {
        byte[] pdfBytes = buildPdfWithPages(1);
        assertThrows(IllegalArgumentException.class, () -> pageRenderService.renderPagePng(pdfBytes, 0));
    }

    @Test
    void renderRegionRejectsNegativeExpandValues() throws Exception {
        byte[] pdfBytes = buildPdfWithPages(1);
        assertThrows(IllegalArgumentException.class, () ->
                pageRenderService.renderRegionPng(pdfBytes, 1, 0, 0, 10, 10, -1, 0));
    }

    @Test
    void renderRegionRejectsOutOfRangePage() throws Exception {
        byte[] pdfBytes = buildPdfWithPages(1);
        assertThrows(IllegalArgumentException.class, () ->
                pageRenderService.renderRegionPng(pdfBytes, 2, 0, 0, 10, 10, 0, 0));
    }

    @Test
    void renderRegionClampsCoordinatesOutsidePageBounds() throws Exception {
        byte[] pdfBytes = buildPdfWithPages(1);
        PageRenderService.RenderedImage rendered = pageRenderService.renderRegionPng(
                pdfBytes, 1, 99999, 99999, 50, 50, 0, 0);
        assertTrue(rendered.imageBytes().length > 0);
    }

    @Test
    void renderPageDownscalesWhenPixelBudgetExceeded() throws Exception {
        ReflectionTestUtils.setField(pageRenderService, "maxOutputPixels", 5_000);
        byte[] pdfBytes = buildPdfWithPages(1);
        PageRenderService.RenderedImage rendered = pageRenderService.renderPagePng(pdfBytes, 1);
        assertTrue((rendered.width() * rendered.height()) <= 5_000);
    }

    @Test
    void renderedImageExposesDefensiveByteArray() throws Exception {
        byte[] pdfBytes = buildPdfWithPages(1);
        PageRenderService.RenderedImage rendered = pageRenderService.renderPagePng(pdfBytes, 1);
        byte[] first = rendered.imageBytes();
        first[0] = (byte) (first[0] ^ 0x7F);
        byte[] second = rendered.imageBytes();
        assertTrue(first[0] != second[0]);
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
