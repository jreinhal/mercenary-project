package com.jreinhal.mercenary.e2e;

import com.google.cloud.vertexai.VertexAI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "spring.ai.openai.api-key=test",
        "spring.ai.openai.chat.api-key=test",
        "spring.ai.openai.embedding.api-key=test",
        "spring.ai.vertex.ai.gemini.project-id=test",
        "spring.ai.vertex.ai.gemini.location=test",
        "sentinel.sessions.file-backup-enabled=false"
})
@ActiveProfiles("dev")
class PipelineE2eTest {

    @TestConfiguration
    static class ChatModelTestConfig {
        @Bean
        @Primary
        ChatModel chatModel() {
            return Mockito.mock(ChatModel.class);
        }

        @Bean
        @Primary
        EmbeddingModel embeddingModel() {
            return Mockito.mock(EmbeddingModel.class);
        }

        @Bean
        @Primary
        VertexAI vertexAI() {
            return Mockito.mock(VertexAI.class);
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void healthEndpointReturnsNominal() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/health", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("SYSTEMS NOMINAL", response.getBody());
    }

    @Test
    void statusEndpointReturnsPayload() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/api/status", Map.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("systemStatus"));
        assertTrue(response.getBody().containsKey("vectorDb"));
        assertTrue(response.getBody().containsKey("docsIndexed"));
    }
}
