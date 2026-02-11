package com.jreinhal.mercenary.service;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PageRenderService {

    @Value("${sentinel.source-render.dpi:160}")
    private int renderDpi;

    @Value("${sentinel.source-render.max-output-pixels:3686400}")
    private int maxOutputPixels;

    public RenderedImage renderPagePng(byte[] pdfBytes, int pageNumber) throws IOException {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new IllegalArgumentException("PDF bytes are required.");
        }
        if (pageNumber <= 0) {
            throw new IllegalArgumentException("Page number must be >= 1.");
        }
        try (PDDocument pd = Loader.loadPDF(pdfBytes)) {
            int pageCount = pd.getNumberOfPages();
            if (pageNumber > pageCount) {
                throw new IllegalArgumentException("Requested page is out of range.");
            }
            BufferedImage rendered = new PDFRenderer(pd).renderImageWithDPI(pageNumber - 1, this.renderDpi);
            BufferedImage bounded = this.downscaleIfNeeded(rendered);
            return new RenderedImage(this.toPngBytes(bounded), pageNumber, pageCount, bounded.getWidth(), bounded.getHeight());
        }
    }

    public RenderedImage renderRegionPng(byte[] pdfBytes, int pageNumber, int x, int y, int width, int height,
            int expandAbovePx, int expandBelowPx) throws IOException {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be > 0.");
        }
        if (expandAbovePx < 0 || expandBelowPx < 0) {
            throw new IllegalArgumentException("expand_above and expand_below must be >= 0.");
        }
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new IllegalArgumentException("PDF bytes are required.");
        }
        if (pageNumber <= 0) {
            throw new IllegalArgumentException("Page number must be >= 1.");
        }
        try (PDDocument pd = Loader.loadPDF(pdfBytes)) {
            int pageCount = pd.getNumberOfPages();
            if (pageNumber > pageCount) {
                throw new IllegalArgumentException("Requested page is out of range.");
            }
            BufferedImage pageImage = new PDFRenderer(pd).renderImageWithDPI(pageNumber - 1, this.renderDpi);
            int pageWidth = pageImage.getWidth();
            int pageHeight = pageImage.getHeight();
            int left = this.clamp(x, 0, Math.max(0, pageWidth - 1));
            int top = this.clamp(y - expandAbovePx, 0, Math.max(0, pageHeight - 1));
            int right = this.clamp(x + width, left + 1, pageWidth);
            int bottom = this.clamp(y + height + expandBelowPx, top + 1, pageHeight);
            BufferedImage cropped = pageImage.getSubimage(left, top, right - left, bottom - top);
            BufferedImage bounded = this.downscaleIfNeeded(cropped);
            return new RenderedImage(this.toPngBytes(bounded), pageNumber, pageCount, bounded.getWidth(), bounded.getHeight());
        }
    }

    private BufferedImage downscaleIfNeeded(BufferedImage image) {
        if (image == null) {
            throw new IllegalArgumentException("Rendered image cannot be null.");
        }
        int pixels = image.getWidth() * image.getHeight();
        if (pixels <= this.maxOutputPixels || this.maxOutputPixels <= 0) {
            return image;
        }
        double scale = Math.sqrt((double) this.maxOutputPixels / (double) pixels);
        int outW = Math.max(1, (int) Math.floor(image.getWidth() * scale));
        int outH = Math.max(1, (int) Math.floor(image.getHeight() * scale));
        BufferedImage scaled = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.drawImage(image, 0, 0, outW, outH, null);
        } finally {
            g.dispose();
        }
        return scaled;
    }

    private byte[] toPngBytes(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    public static final class RenderedImage {
        private final byte[] imageBytes;
        private final int pageNumber;
        private final int pageCount;
        private final int width;
        private final int height;

        public RenderedImage(byte[] imageBytes, int pageNumber, int pageCount, int width, int height) {
            this.imageBytes = imageBytes != null ? imageBytes.clone() : new byte[0];
            this.pageNumber = pageNumber;
            this.pageCount = pageCount;
            this.width = width;
            this.height = height;
        }

        public byte[] imageBytes() {
            return this.imageBytes.clone();
        }

        public int pageNumber() {
            return this.pageNumber;
        }

        public int pageCount() {
            return this.pageCount;
        }

        public int width() {
            return this.width;
        }

        public int height() {
            return this.height;
        }
    }
}
