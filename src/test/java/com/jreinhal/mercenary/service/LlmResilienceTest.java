package com.jreinhal.mercenary.service;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import com.jreinhal.mercenary.e2e.InMemoryVectorStore;
import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.repository.ChatLogRepository;
import com.jreinhal.mercenary.repository.FeedbackRepository;
import com.jreinhal.mercenary.repository.ReportExportRepository;
import com.jreinhal.mercenary.repository.ReportScheduleRepository;
import com.jreinhal.mercenary.repository.UserRepository;
import com.jreinhal.mercenary.repository.WorkspaceRepository;
import com.jreinhal.mercenary.workspace.Workspace;
import com.jreinhal.mercenary.workspace.Workspace.WorkspaceQuota;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.ChatOptionsBuilder;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * E2E tests for LLM failure handling in RagOrchestrationService.
 * Verifies timeout messages, System Offline Mode, and graceful degradation
 * when the LLM is unavailable.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "sentinel.llm.timeout-seconds=2"
)
@ActiveProfiles({"ci-e2e", "dev"})
@AutoConfigureMockMvc
class LlmResilienceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private VectorStore vectorStore;

    @MockitoBean(answers = Answers.RETURNS_DEEP_STUBS)
    private MongoTemplate mongoTemplate;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private ChatLogRepository chatLogRepository;

    @MockitoBean
    private FeedbackRepository feedbackRepository;

    @MockitoBean
    private ReportScheduleRepository reportScheduleRepository;

    @MockitoBean
    private ReportExportRepository reportExportRepository;

    @MockitoBean
    private WorkspaceRepository workspaceRepository;

    @BeforeEach
    void setup() {
        Workspace defaultWorkspace = new Workspace("workspace_default", "Default Workspace", "Default workspace",
                "system", java.time.Instant.now(), java.time.Instant.now(), WorkspaceQuota.unlimited(), true);
        when(mongoTemplate.getCollection(anyString()).countDocuments()).thenReturn(0L);
        when(userRepository.findByUsername(anyString())).thenReturn(Optional.empty());
        when(userRepository.findByExternalId(anyString())).thenReturn(Optional.empty());
        when(userRepository.findById(anyString())).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.countByWorkspaceIdsContaining(anyString())).thenReturn(0L);
        when(userRepository.findByWorkspaceIdsContaining(anyString())).thenReturn(List.of());
        when(workspaceRepository.existsById(anyString())).thenReturn(true);
        when(workspaceRepository.findById(anyString())).thenReturn(Optional.of(defaultWorkspace));
        when(workspaceRepository.findAll()).thenReturn(List.of(defaultWorkspace));
        when(workspaceRepository.findByIdIn(any())).thenReturn(List.of(defaultWorkspace));
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Reset to default behavior (succeed)
        ConfigurableChatModel.behavior.set(Behavior.SUCCEED);

        if (vectorStore instanceof InMemoryVectorStore store) {
            store.clear();
            Map<String, Object> meta = new HashMap<>();
            meta.put("dept", "ENTERPRISE");
            meta.put("source", "resilience_test.txt");
            meta.put("workspaceId", "workspace_default");
            store.add(List.of(new Document(
                    "The total program budget is $150M allocated across three fiscal years.", meta)));
        }
    }

    @Nested
    @DisplayName("Zero-Hop LLM Failures")
    class ZeroHopFailures {

        @Test
        @DisplayName("LLM timeout on zero-hop returns timeout message")
        void zeroHopTimeoutReturnsTimeoutMessage() throws Exception {
            ConfigurableChatModel.behavior.set(Behavior.TIMEOUT);

            mockMvc.perform(get("/api/ask/enhanced")
                            .param("q", "Hello")
                            .param("dept", "ENTERPRISE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.answer", containsString("Response Timeout")))
                    .andExpect(jsonPath("$.metrics.zeroHop").value(true));
        }

        @Test
        @DisplayName("LLM exception on zero-hop returns apologetic fallback")
        void zeroHopExceptionReturnsFallback() throws Exception {
            ConfigurableChatModel.behavior.set(Behavior.THROW);

            mockMvc.perform(get("/api/ask/enhanced")
                            .param("q", "Hello")
                            .param("dept", "ENTERPRISE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.answer", containsString("unable to process")))
                    .andExpect(jsonPath("$.metrics.zeroHop").value(true));
        }
    }

    @Nested
    @DisplayName("Retrieval LLM Failures")
    class RetrievalFailures {

        @Test
        @DisplayName("LLM timeout on retrieval path returns timeout guidance")
        void retrievalTimeoutReturnsGuidance() throws Exception {
            ConfigurableChatModel.behavior.set(Behavior.TIMEOUT);

            mockMvc.perform(get("/api/ask/enhanced")
                            .param("q", "What is the total program budget?")
                            .param("dept", "ENTERPRISE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.answer", containsString("Response Timeout")));
        }

        @Test
        @DisplayName("LLM exception on retrieval path triggers System Offline Mode")
        void retrievalExceptionTriggersOfflineMode() throws Exception {
            ConfigurableChatModel.behavior.set(Behavior.THROW);

            mockMvc.perform(get("/api/ask/enhanced")
                            .param("q", "What is the total program budget?")
                            .param("dept", "ENTERPRISE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.answer", containsString("System Offline Mode")))
                    .andExpect(jsonPath("$.answer", containsString("AI model is currently unavailable")));
        }

        @Test
        @DisplayName("System Offline Mode includes document excerpts")
        void offlineModeIncludesExcerpts() throws Exception {
            ConfigurableChatModel.behavior.set(Behavior.THROW);

            mockMvc.perform(get("/api/ask/enhanced")
                            .param("q", "What is the total program budget?")
                            .param("dept", "ENTERPRISE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.answer", containsString("resilience_test.txt")))
                    .andExpect(jsonPath("$.answer", containsString("$150M")));
        }

        @Test
        @DisplayName("System Offline Mode still returns sources array")
        void offlineModeReturnsSources() throws Exception {
            ConfigurableChatModel.behavior.set(Behavior.THROW);

            mockMvc.perform(get("/api/ask/enhanced")
                            .param("q", "What is the total program budget?")
                            .param("dept", "ENTERPRISE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sources").isArray())
                    .andExpect(jsonPath("$.sources.length()").value(greaterThan(0)));
        }
    }

    @Nested
    @DisplayName("Recovery")
    class Recovery {

        @Test
        @DisplayName("Normal response after LLM recovers from failure")
        void normalResponseAfterRecovery() throws Exception {
            // First request fails
            ConfigurableChatModel.behavior.set(Behavior.THROW);
            mockMvc.perform(get("/api/ask/enhanced")
                            .param("q", "Hello")
                            .param("dept", "ENTERPRISE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.answer", containsString("unable to process")));

            // LLM recovers
            ConfigurableChatModel.behavior.set(Behavior.SUCCEED);
            mockMvc.perform(get("/api/ask/enhanced")
                            .param("q", "Hello")
                            .param("dept", "ENTERPRISE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.answer").isNotEmpty());
        }
    }

    /** Behavior modes for the configurable chat model. */
    enum Behavior {
        SUCCEED,
        THROW,
        TIMEOUT
    }

    @TestConfiguration
    static class LlmResilienceTestConfig {
        @Bean
        @Primary
        VectorStore vectorStore() {
            return new InMemoryVectorStore();
        }

        @Bean
        @Primary
        ChatModel chatModel() {
            return new ConfigurableChatModel();
        }

        @Bean
        @Primary
        EmbeddingModel embeddingModel() {
            return Mockito.mock(EmbeddingModel.class);
        }
    }

    /**
     * A ChatModel that can be switched between succeed/throw/timeout behaviors
     * via a static AtomicReference, allowing per-test control.
     */
    private static final class ConfigurableChatModel implements ChatModel {
        static final AtomicReference<Behavior> behavior = new AtomicReference<>(Behavior.SUCCEED);

        @Override
        public ChatResponse call(Prompt prompt) {
            switch (behavior.get()) {
                case THROW:
                    throw new RuntimeException("LLM connection refused — model unavailable");
                case TIMEOUT:
                    try {
                        // Block slightly past the 2s test timeout (set via properties)
                        Thread.sleep(4_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("late response"))));
                default:
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("Stub response"))));
            }
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ChatOptionsBuilder.builder().build();
        }
    }
}
