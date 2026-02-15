package com.jreinhal.mercenary.enterprise.rag.sparse;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SparseEmbeddingClientTest {

    private SparseEmbeddingClient client;

    @BeforeEach
    void setUp() {
        client = new SparseEmbeddingClient();
        // Simulate Spring @PostConstruct to initialise RestTemplate with default timeout
        client.init();
    }

    @Test
    void isEnabledReturnsFalseByDefault() {
        assertFalse(client.isEnabled());
    }

    @Test
    void isAvailableReturnsFalseWhenDisabled() {
        assertFalse(client.isAvailable());
    }

    @Test
    void isAvailableReturnsFalseWhenSidecarNotRunning() {
        ReflectionTestUtils.setField(client, "enabled", true);
        ReflectionTestUtils.setField(client, "serviceUrl", "http://localhost:9999");
        client.init(); // re-init with new settings
        assertFalse(client.isAvailable());
    }

    @Test
    void embedSparseReturnsEmptyWhenDisabled() {
        List<Map<String, Float>> result = client.embedSparse(List.of("test"));
        assertTrue(result.isEmpty());
    }

    @Test
    void embedSparseReturnsEmptyForNullInput() {
        ReflectionTestUtils.setField(client, "enabled", true);
        List<Map<String, Float>> result = client.embedSparse(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void embedSparseReturnsEmptyForEmptyList() {
        ReflectionTestUtils.setField(client, "enabled", true);
        List<Map<String, Float>> result = client.embedSparse(List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void embedSparseGracefullyHandlesConnectionFailure() {
        ReflectionTestUtils.setField(client, "enabled", true);
        ReflectionTestUtils.setField(client, "serviceUrl", "http://localhost:9999");
        client.init(); // re-init with new settings
        List<Map<String, Float>> result = client.embedSparse(List.of("test query"));
        assertTrue(result.isEmpty());
    }
}
