package com.jreinhal.mercenary.rag.qucorag;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@Component
public class InfiniGramClient {
    private static final Logger log = LoggerFactory.getLogger(InfiniGramClient.class);
    private static final String API_URL = "https://api.infini-gram.io/";
    private static final String DEFAULT_INDEX = "v4_olmo-2-0325-32b-instruct_llama";
    @Value(value="${sentinel.qucorag.infini-gram-enabled:false}")
    private boolean enabled;
    @Value(value="${sentinel.qucorag.infini-gram-index:v4_olmo-2-0325-32b-instruct_llama}")
    private String indexName;
    private final Environment environment;
    private final RestTemplate restTemplate;
    private final int timeoutMs;

    public InfiniGramClient(RestTemplateBuilder restTemplateBuilder, Environment environment, @Value("${sentinel.qucorag.infini-gram-timeout-ms:5000}") int timeoutMs) {
        this.environment = environment;
        this.timeoutMs = timeoutMs;
        this.restTemplate = restTemplateBuilder
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .readTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    @PostConstruct
    public void init() {
        boolean govcloudProfile = Arrays.stream(this.environment.getActiveProfiles())
                .anyMatch(profile -> "govcloud".equalsIgnoreCase(profile));
        if (govcloudProfile && this.enabled) {
            log.warn("InfiniGram disabled in govcloud profile (air-gapped mode enforced).");
            this.enabled = false;
        }
        log.info("InfiniGramClient initialized (enabled={}, timeout={}ms, index={})", new Object[]{this.enabled, this.timeoutMs, this.indexName});
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public long getCount(String query) {
        if (!this.enabled) {
            return -1L;
        }
        try {
            return this.queryInfiniGram(query, "count");
        }
        catch (Exception e) {
            log.error("Infini-gram error for query: {}", query, e);
            return -1L;
        }
    }

    public long getCoOccurrence(String entity1, String entity2) {
        if (!this.enabled) {
            return -1L;
        }
        String query = String.format("\"%s\" AND \"%s\"", this.escapeQuery(entity1), this.escapeQuery(entity2));
        try {
            return this.queryInfiniGram(query, "count");
        }
        catch (Exception e) {
            log.error("Infini-gram error for co-occurrence: {} AND {}", new Object[]{entity1, entity2, e});
            return -1L;
        }
    }

    private long queryInfiniGram(String query, String queryType) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, String> payload = Map.of("index", this.indexName, "query_type", queryType, "query", query);
            HttpEntity request = new HttpEntity(payload, (MultiValueMap)headers);
            ResponseEntity response = this.restTemplate.postForEntity(API_URL, request, Map.class, new Object[0]);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map body = (Map)response.getBody();
                if (body.containsKey("error")) {
                    log.warn("Infini-gram API error: {}", body.get("error"));
                    return -1L;
                }
                Object count = body.get("count");
                if (count instanceof Number) {
                    return ((Number)count).longValue();
                }
            }
            log.warn("Unexpected Infini-gram response: {}", response);
            return -1L;
        }
        catch (Exception e) {
            log.error("Infini-gram API call failed", (Throwable)e);
            return -1L;
        }
    }

    private String escapeQuery(String query) {
        if (query == null) {
            return "";
        }
        return query.replace("\\", "\\\\").replace("\"", "\\\"");
    }

}
