package com.jreinhal.mercenary;

import com.google.cloud.vertexai.VertexAI;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "spring.ai.openai.api-key=test",
        "spring.ai.openai.chat.api-key=test",
        "spring.ai.openai.embedding.api-key=test",
        "spring.ai.vertex.ai.gemini.project-id=test",
        "spring.ai.vertex.ai.gemini.location=test"
})
@ActiveProfiles("dev")
class MercenaryApplicationTests {

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

    @Test
    void contextLoads() {
        // Verify Spring context loads successfully
    }
}
