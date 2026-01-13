package com.jreinhal.mercenary.rag.qucorag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.*;

/**
 * HTTP Client for the Infini-gram API.
 * 
 * Infini-gram provides n-gram statistics from a 4-trillion token corpus,
 * enabling corpus-grounded uncertainty quantification for RAG systems.
 * 
 * API: https://api.infini-gram.io/
 * 
 * NOTE: This is optional and disabled by default for air-gapped deployments.
 * When disabled, QuCoRagService falls back to local frequency analysis.
 */
@Component
public class InfiniGramClient {

    private static final Logger log = LoggerFactory.getLogger(InfiniGramClient.class);
    private static final String API_URL = "https://api.infini-gram.io/";

    // Default corpus index (OLMo-2 + Llama training data)
    private static final String DEFAULT_INDEX = "v4_olmo-2-0325-32b-instruct_llama";

    @Value("${sentinel.qucorag.infini-gram-enabled:false}")
    private boolean enabled;

    @Value("${sentinel.qucorag.infini-gram-timeout-ms:5000}")
    private int timeoutMs;

    @Value("${sentinel.qucorag.infini-gram-index:v4_olmo-2-0325-32b-instruct_llama}")
    private String indexName;

    private RestTemplate restTemplate;
    private ExecutorService executor;

    @PostConstruct
    public void init() {
        this.restTemplate = new RestTemplate();
        this.executor = Executors.newCachedThreadPool();
        log.info("InfiniGramClient initialized (enabled={}, timeout={}ms, index={})",
                enabled, timeoutMs, indexName);
    }

    /**
     * Check if Infini-gram integration is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Get the count (frequency) of a phrase in the Infini-gram corpus.
     *
     * @param query The phrase to count
     * @return The count, or -1 if unavailable/error
     */
    public long getCount(String query) {
        if (!enabled) {
            return -1;
        }

        try {
            Future<Long> future = executor.submit(() -> queryInfiniGram(query, "count"));
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("Infini-gram timeout for query: {}", query);
            return -1;
        } catch (Exception e) {
            log.error("Infini-gram error for query: {}", query, e);
            return -1;
        }
    }

    /**
     * Check co-occurrence of two entities in the corpus.
     * Uses AND query to find documents containing both entities.
     *
     * @param entity1 First entity
     * @param entity2 Second entity
     * @return Co-occurrence count, or -1 if unavailable
     */
    public long getCoOccurrence(String entity1, String entity2) {
        if (!enabled) {
            return -1;
        }

        // Construct AND query for co-occurrence
        String query = String.format("\"%s\" AND \"%s\"",
                escapeQuery(entity1), escapeQuery(entity2));

        try {
            Future<Long> future = executor.submit(() -> queryInfiniGram(query, "count"));
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("Infini-gram timeout for co-occurrence: {} AND {}", entity1, entity2);
            return -1;
        } catch (Exception e) {
            log.error("Infini-gram error for co-occurrence: {} AND {}", entity1, entity2, e);
            return -1;
        }
    }

    /**
     * Execute the actual Infini-gram API call.
     */
    private long queryInfiniGram(String query, String queryType) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> payload = Map.of(
                    "index", indexName,
                    "query_type", queryType,
                    "query", query);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(API_URL, request, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> body = response.getBody();

                // Check for API error
                if (body.containsKey("error")) {
                    log.warn("Infini-gram API error: {}", body.get("error"));
                    return -1;
                }

                // Extract count
                Object count = body.get("count");
                if (count instanceof Number) {
                    return ((Number) count).longValue();
                }
            }

            log.warn("Unexpected Infini-gram response: {}", response);
            return -1;

        } catch (Exception e) {
            log.error("Infini-gram API call failed", e);
            return -1;
        }
    }

    /**
     * Escape special characters in query string.
     */
    private String escapeQuery(String query) {
        if (query == null)
            return "";
        return query.replace("\"", "\\\"").replace("\\", "\\\\");
    }

    /**
     * Graceful shutdown.
     */
    public void shutdown() {
        if (executor != null) {
            executor.shutdown();
        }
    }
}
