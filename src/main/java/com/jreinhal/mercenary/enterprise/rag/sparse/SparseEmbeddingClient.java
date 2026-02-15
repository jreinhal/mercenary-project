package com.jreinhal.mercenary.enterprise.rag.sparse;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for the FlagEmbedding sparse embedding sidecar.
 *
 * Follows the same SSRF-hardened RestTemplate pattern as {@code LightOnOcrService}:
 * no-redirect following, graceful degradation on failure, configurable timeouts.
 *
 * Configuration:
 *   sentinel.sparse-embedding.enabled: true/false
 *   sentinel.sparse-embedding.service-url: http://localhost:8091
 *   sentinel.sparse-embedding.timeout-seconds: 30
 */
@Component
public class SparseEmbeddingClient {
    private static final Logger log = LoggerFactory.getLogger(SparseEmbeddingClient.class);

    private org.springframework.web.client.RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${sentinel.sparse-embedding.enabled:false}")
    private boolean enabled;

    @Value("${sentinel.sparse-embedding.service-url:http://localhost:8091}")
    private String serviceUrl;

    @Value("${sentinel.sparse-embedding.timeout-seconds:30}")
    private int timeoutSeconds;

    public SparseEmbeddingClient() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Build the RestTemplate after Spring injects @Value fields so that
     * the configured timeout-seconds value is actually applied.
     */
    @PostConstruct
    void init() {
        this.restTemplate = createNoRedirectRestTemplate(timeoutSeconds);
        log.info("Sparse embedding client initialised (enabled={}, url={}, timeout={}s)",
                enabled, serviceUrl, timeoutSeconds);
    }

    // R-04: Disable automatic redirect following to prevent SSRF bypass via 3xx redirects
    private static org.springframework.web.client.RestTemplate createNoRedirectRestTemplate(int timeoutSecs) {
        int timeoutMs = timeoutSecs * 1000;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return new org.springframework.web.client.RestTemplate(factory);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Check if the sidecar service is available and the model is loaded.
     */
    public boolean isAvailable() {
        if (!enabled) {
            return false;
        }
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(serviceUrl + "/health", String.class);
            if (response.getStatusCode().is3xxRedirection()) {
                log.warn("Sparse embedding sidecar returned redirect (possible SSRF); treating as unavailable");
                return false;
            }
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                HealthResponse health = objectMapper.readValue(response.getBody(), HealthResponse.class);
                return health.modelLoaded;
            }
            return false;
        } catch (Exception e) {
            log.debug("Sparse embedding sidecar not available: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Compute sparse (lexical) embeddings for a batch of texts.
     *
     * @param texts list of text strings to embed
     * @return list of sparse weight maps (token -> weight), one per input text;
     *         returns empty list on failure
     */
    public List<Map<String, Float>> embedSparse(List<String> texts) {
        if (!enabled || texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            EmbedSparseRequest request = new EmbedSparseRequest(texts);
            String requestJson = objectMapper.writeValueAsString(request);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    serviceUrl + "/embed-sparse", entity, String.class);

            if (response.getStatusCode().is3xxRedirection()) {
                log.warn("Sparse embedding sidecar returned redirect (possible SSRF); aborting");
                return Collections.emptyList();
            }

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                EmbedSparseResponse parsed = objectMapper.readValue(
                        response.getBody(), EmbedSparseResponse.class);
                List<Map<String, Float>> results = new ArrayList<>();
                for (TokenWeightsResult tw : parsed.results) {
                    results.add(tw.tokenWeights != null ? tw.tokenWeights : new HashMap<>());
                }
                if (log.isDebugEnabled()) {
                    log.debug("Sparse embedding: {} texts in {}ms", texts.size(), parsed.processingTimeMs);
                }
                return results;
            }

            log.warn("Sparse embedding sidecar returned status {}", response.getStatusCode());
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("Sparse embedding request failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ---------- DTOs ----------

    private record EmbedSparseRequest(List<String> texts) {}

    private static class EmbedSparseResponse {
        @JsonProperty("results")
        public List<TokenWeightsResult> results;

        @JsonProperty("processing_time_ms")
        public double processingTimeMs;
    }

    private static class TokenWeightsResult {
        @JsonProperty("token_weights")
        public Map<String, Float> tokenWeights;
    }

    private static class HealthResponse {
        @JsonProperty("status")
        public String status;

        @JsonProperty("model_loaded")
        public boolean modelLoaded;

        @JsonProperty("model_name")
        public String modelName;

        @JsonProperty("device")
        public String device;
    }
}
