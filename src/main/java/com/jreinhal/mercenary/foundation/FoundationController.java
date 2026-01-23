package com.jreinhal.mercenary.foundation;

import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/foundation")
public class FoundationController {

    private final ModelOrchestrator orchestrator;

    public FoundationController(ModelOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String response = orchestrator.chat(request.model(), request.message());
        return new ChatResponse(request.model(), response);
    }

    // Simple "Debate" or "Bounce" feature
    // Takes a prompt and chains it through a list of models locally
    @PostMapping("/bounce")
    public BounceResponse bounce(@RequestBody BounceRequest request) {
        StringBuilder conversation = new StringBuilder();
        String currentInput = request.initialPrompt();
        conversation.append("USER: ").append(currentInput).append("\n\n");

        for (String modelId : request.modelSequence()) {
            String response = orchestrator.chat(modelId, "Review and build upon the following idea:\n" + currentInput);
            conversation.append("MODEL [").append(modelId).append("]: ").append(response).append("\n\n");
            currentInput = response; // Chain the output as input to next
        }

        return new BounceResponse(conversation.toString(), currentInput);
    }

    public record ChatRequest(String model, String message) {
    }

    public record ChatResponse(String model, String response) {
    }

    public record BounceRequest(String initialPrompt, List<String> modelSequence) {
    }

    public record BounceResponse(String fullTranscript, String finalResult) {
    }
}
