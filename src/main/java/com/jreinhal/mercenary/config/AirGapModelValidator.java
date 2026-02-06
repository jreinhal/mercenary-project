package com.jreinhal.mercenary.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Air-gap safety net: ensure required Ollama models exist locally before accepting traffic.
 *
 * This is intentionally opt-in via config so dev/test environments are not forced to have
 * models preloaded. In SCIF/govcloud deployments, enable this to fail-fast if models are missing.
 */
@Component
@Profile("govcloud")
@ConditionalOnProperty(name = "sentinel.airgap.model-validation.enabled", havingValue = "true")
public class AirGapModelValidator {
    private static final Logger log = LoggerFactory.getLogger(AirGapModelValidator.class);

    @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${spring.ai.ollama.chat.options.model:}")
    private String chatModel;

    @Value("${spring.ai.ollama.embedding.model:}")
    private String embeddingModel;

    @Value("${sentinel.airgap.model-validation.fail-on-missing:true}")
    private boolean failOnMissing;

    @Value("${sentinel.airgap.model-validation.timeout-ms:2000}")
    private long timeoutMs;

    @EventListener(ApplicationReadyEvent.class)
    public void validateModelsPresent() {
        String baseUrl = this.ollamaBaseUrl != null ? this.ollamaBaseUrl.trim() : "";
        if (baseUrl.isBlank()) {
            return;
        }

        try {
            URI uri = URI.create(baseUrl);
            String host = uri.getHost();
            if (host != null && !"localhost".equalsIgnoreCase(host) && !"127.0.0.1".equals(host)) {
                // Not a hard failure: some deployments run Ollama on a dedicated local network node.
                log.warn("Air-gap model validation: Ollama base-url host is not loopback: {}", host);
            }
        } catch (Exception e) {
            log.warn("Air-gap model validation: unable to parse Ollama base-url");
        }

        RestTemplate rest = this.restTemplateWithTimeout();
        String tagsUrl = baseUrl.endsWith("/") ? baseUrl + "api/tags" : baseUrl + "/api/tags";

        Set<String> available = new HashSet<>();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = rest.getForObject(tagsUrl, Map.class);
            Object models = response != null ? response.get("models") : null;
            if (models instanceof List<?> list) {
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> entry)) {
                        continue;
                    }
                    Object name = entry.get("name");
                    if (name != null) {
                        available.add(String.valueOf(name));
                    }
                }
            }
        } catch (Exception e) {
            String msg = "Air-gap model validation: unable to query local Ollama tags endpoint";
            if (this.failOnMissing) {
                throw new IllegalStateException(msg + " (" + e.getMessage() + ")", e);
            }
            log.warn("{}: {}", msg, e.getMessage());
            return;
        }

        List<String> required = new ArrayList<>();
        if (this.chatModel != null && !this.chatModel.isBlank()) {
            required.add(this.chatModel.trim());
        }
        if (this.embeddingModel != null && !this.embeddingModel.isBlank()) {
            required.add(this.embeddingModel.trim());
        }

        List<String> missing = new ArrayList<>();
        for (String model : required) {
            if (!isModelAvailable(available, model)) {
                missing.add(model);
            }
        }

        if (!missing.isEmpty()) {
            String msg = "AIR-GAP: Required Ollama model(s) not found locally: " + String.join(", ", missing);
            if (this.failOnMissing) {
                throw new IllegalStateException(msg);
            }
            log.warn(msg);
            return;
        }

        log.info("Air-gap model validation passed. Required models present: {}", String.join(", ", required));
    }

    private RestTemplate restTemplateWithTimeout() {
        // timeoutMs is configurable but must be sane.
        long clamped = Math.max(250, Math.min(this.timeoutMs, 30_000));
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) clamped);
        factory.setReadTimeout((int) clamped);
        return new RestTemplate(factory);
    }

    private static boolean isModelAvailable(Set<String> available, String required) {
        if (required == null || required.isBlank()) {
            return true;
        }
        if (available.contains(required)) {
            return true;
        }
        // If config uses base name (no tag), accept any locally installed tag for that base.
        if (!required.contains(":")) {
            String prefix = required + ":";
            for (String name : available) {
                if (required.equals(name) || name.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }
}
