package com.jreinhal.mercenary.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.Base64;
import java.util.List;

/**
 * Service for OCR processing using LightOnOCR-2-1B microservice.
 *
 * Provides enhanced OCR for scanned PDFs and images, complementing
 * Apache Tika's text extraction with AI-powered visual document understanding.
 *
 * Configuration:
 *   sentinel.ocr.enabled: true/false
 *   sentinel.ocr.service-url: http://localhost:8090
 *   sentinel.ocr.timeout-seconds: 60
 */
@Service
public class LightOnOcrService {
    private static final Logger log = LoggerFactory.getLogger(LightOnOcrService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${sentinel.ocr.enabled:false}")
    private boolean enabled;

    @Value("${sentinel.ocr.service-url:http://localhost:8090}")
    private String serviceUrl;

    @Value("${sentinel.ocr.timeout-seconds:60}")
    private int timeoutSeconds;

    @Value("${sentinel.ocr.max-tokens-per-page:2048}")
    private int maxTokensPerPage;

    @Value("${sentinel.ocr.max-pages:50}")
    private int maxPages;

    // R-04: Disable automatic redirect following to prevent SSRF bypass via 3xx redirects
    private static RestTemplate createNoRedirectRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        return new RestTemplate(factory);
    }

    public LightOnOcrService() {
        this.restTemplate = createNoRedirectRestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Check if OCR service is enabled and available.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Check if the OCR service is healthy.
     */
    public boolean isHealthy() {
        if (!enabled) {
            return false;
        }
        try {
            ResponseEntity<HealthResponse> response = restTemplate.getForEntity(
                serviceUrl + "/health",
                HealthResponse.class
            );
            return response.getBody() != null &&
                   "healthy".equals(response.getBody().status) &&
                   response.getBody().modelLoaded;
        } catch (Exception e) {
            log.warn("OCR service health check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * OCR a single image from bytes.
     *
     * @param imageBytes The image data (PNG, JPEG, etc.)
     * @param filename Original filename for logging
     * @return Extracted text from the image
     */
    public String ocrImage(byte[] imageBytes, String filename) {
        if (!enabled) {
            log.debug("OCR service disabled, skipping image: {}", filename);
            return "";
        }

        try {
            log.info(">> LightOnOCR: Processing image {}", filename);

            String base64Image = Base64.getEncoder().encodeToString(imageBytes);

            OcrImageRequest request = new OcrImageRequest();
            request.imageBase64 = base64Image;
            request.maxTokens = maxTokensPerPage;

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<OcrImageRequest> entity = new HttpEntity<>(request, headers);

            ResponseEntity<OcrResponse> response = restTemplate.postForEntity(
                serviceUrl + "/ocr/image",
                entity,
                OcrResponse.class
            );

            if (response.getBody() != null) {
                log.info(">> LightOnOCR: Extracted {} chars from {} in {}ms",
                    response.getBody().text.length(),
                    filename,
                    response.getBody().processingTimeMs);
                return response.getBody().text;
            }

            return "";

        } catch (Exception e) {
            log.error("LightOnOCR image processing failed for {}: {}", filename, e.getMessage());
            return "";
        }
    }

    /**
     * OCR a PDF document, extracting text from all pages.
     *
     * @param pdfBytes The PDF file data
     * @param filename Original filename for logging
     * @return Combined text from all pages
     */
    public String ocrPdf(byte[] pdfBytes, String filename) {
        if (!enabled) {
            log.debug("OCR service disabled, skipping PDF: {}", filename);
            return "";
        }

        try {
            log.info(">> LightOnOCR: Processing PDF {}", filename);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new ByteArrayResource(pdfBytes) {
                @Override
                public String getFilename() {
                    return filename;
                }
            });
            body.add("max_tokens_per_page", String.valueOf(maxTokensPerPage));
            body.add("max_pages", String.valueOf(maxPages));

            HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<PdfOcrResponse> response = restTemplate.postForEntity(
                serviceUrl + "/ocr/pdf",
                entity,
                PdfOcrResponse.class
            );

            if (response.getBody() != null && response.getBody().pages != null) {
                StringBuilder combined = new StringBuilder();
                for (PageOcrResult page : response.getBody().pages) {
                    if (page.text != null && !page.text.isEmpty()) {
                        combined.append("\n\n--- Page ").append(page.pageNumber).append(" ---\n\n");
                        combined.append(page.text);
                    }
                }

                log.info(">> LightOnOCR: Extracted {} pages from {} in {}ms",
                    response.getBody().totalPages,
                    filename,
                    response.getBody().processingTimeMs);

                return combined.toString().trim();
            }

            return "";

        } catch (Exception e) {
            log.error("LightOnOCR PDF processing failed for {}: {}", filename, e.getMessage());
            return "";
        }
    }

    /**
     * OCR a scanned document image within a PDF.
     * Use this when Tika extracts little or no text from a PDF page.
     *
     * @param pageImageBytes Rendered page image
     * @param pageNumber Page number for logging
     * @return Extracted text from the page image
     */
    public String ocrPdfPage(byte[] pageImageBytes, int pageNumber) {
        return ocrImage(pageImageBytes, "pdf-page-" + pageNumber);
    }

    // --- Request/Response DTOs ---

    static class OcrImageRequest {
        @JsonProperty("image_base64")
        public String imageBase64;

        @JsonProperty("max_tokens")
        public int maxTokens = 2048;
    }

    static class OcrResponse {
        @JsonProperty("text")
        public String text;

        @JsonProperty("confidence")
        public Double confidence;

        @JsonProperty("processing_time_ms")
        public Double processingTimeMs;
    }

    static class PdfOcrResponse {
        @JsonProperty("pages")
        public List<PageOcrResult> pages;

        @JsonProperty("total_pages")
        public int totalPages;

        @JsonProperty("processing_time_ms")
        public Double processingTimeMs;
    }

    static class PageOcrResult {
        @JsonProperty("page_number")
        public int pageNumber;

        @JsonProperty("text")
        public String text;

        @JsonProperty("width")
        public int width;

        @JsonProperty("height")
        public int height;
    }

    static class HealthResponse {
        @JsonProperty("status")
        public String status;

        @JsonProperty("model_loaded")
        public boolean modelLoaded;

        @JsonProperty("device")
        public String device;
    }
}
