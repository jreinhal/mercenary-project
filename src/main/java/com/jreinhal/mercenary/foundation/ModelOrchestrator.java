package com.jreinhal.mercenary.foundation;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;

@Service
public class ModelOrchestrator {

    private final Map<String, ChatClient> clients = new ConcurrentHashMap<>();

    // We inject the raw Models and build Clients around them to ensure separation
    public ModelOrchestrator(
            Optional<OpenAiChatModel> openAiModel,
            Optional<AnthropicChatModel> anthropicModel,
            Optional<VertexAiGeminiChatModel> geminiModel,
            Optional<OllamaChatModel> ollamaModel) {
        openAiModel.ifPresent(m -> clients.put("gpt", ChatClient.builder(m).build()));
        anthropicModel.ifPresent(m -> clients.put("claude", ChatClient.builder(m).build()));
        geminiModel.ifPresent(m -> clients.put("gemini", ChatClient.builder(m).build()));
        ollamaModel.ifPresent(m -> clients.put("local", ChatClient.builder(m).build()));
    }

    public String chat(String modelId, String prompt) {
        ChatClient client = clients.get(modelId.toLowerCase());
        if (client == null) {
            // Fallback strategy or error
            if (clients.containsKey("local")) {
                return clients.get("local").prompt(prompt).call().content() + "\n[Note: Fallback to Local Model]";
            }
            throw new IllegalArgumentException("Requested model is not available");
        }

        return client.prompt(prompt).call().content();
    }

    public Map<String, String> bounceIdeas(String initialPrompt, String[] modelSequence) {
        // Implementation for "bouncing ideas" - chaining outputs
        // TODO: Implement sophisticated chaining
        return Map.of("status", "Not implemented in basics V1");
    }
}
