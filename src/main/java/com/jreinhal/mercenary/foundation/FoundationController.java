package com.jreinhal.mercenary.foundation;

import com.jreinhal.mercenary.filter.SecurityContext;
import com.jreinhal.mercenary.model.User;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/foundation")
@PreAuthorize("hasAnyAuthority('ANALYST', 'ADMIN')")
public class FoundationController {

    private static final int MAX_MODEL_SEQUENCE_LENGTH = 5;
    private static final int MAX_PROMPT_LENGTH = 4000;

    private final ModelOrchestrator orchestrator;

    public FoundationController(ModelOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest request) {
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (request.model() == null || request.model().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Model is required"));
        }
        if (request.message() == null || request.message().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Message is required"));
        }
        if (request.message().length() > MAX_PROMPT_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of("error", "Message exceeds maximum length"));
        }
        try {
            String response = orchestrator.chat(request.model(), request.message());
            return ResponseEntity.ok(new ChatResponse(request.model(), response));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Requested model is not available"));
        }
    }

    @PostMapping("/bounce")
    public ResponseEntity<?> bounce(@RequestBody BounceRequest request) {
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (request.initialPrompt() == null || request.initialPrompt().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Initial prompt is required"));
        }
        if (request.initialPrompt().length() > MAX_PROMPT_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of("error", "Prompt exceeds maximum length"));
        }
        if (request.modelSequence() == null || request.modelSequence().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Model sequence is required"));
        }
        if (request.modelSequence().size() > MAX_MODEL_SEQUENCE_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of("error", "Model sequence exceeds maximum of " + MAX_MODEL_SEQUENCE_LENGTH));
        }
        for (String modelId : request.modelSequence()) {
            if (modelId == null || modelId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Model sequence entries must not be blank"));
            }
        }

        StringBuilder conversation = new StringBuilder();
        String currentInput = request.initialPrompt();
        conversation.append("USER: ").append(currentInput).append("\n\n");

        try {
            for (String modelId : request.modelSequence()) {
                String response = orchestrator.chat(modelId, "Review and build upon the following idea:\n" + currentInput);
                conversation.append("MODEL [").append(modelId).append("]: ").append(response).append("\n\n");
                currentInput = response;
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Requested model is not available"));
        }

        return ResponseEntity.ok(new BounceResponse(conversation.toString(), currentInput));
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
