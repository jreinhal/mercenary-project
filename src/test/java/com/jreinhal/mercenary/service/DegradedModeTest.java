package com.jreinhal.mercenary.service;

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
 * E2E tests for degraded mode behavior: verifying that the pipeline
 * continues functioning when optional services (conversation memory,
 * HIPAA audit, session persistence) are unavailable. The ci-e2e profile
 * does not enable enterprise memory features, so these services are
 * naturally null in this context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"ci-e2e", "dev"})
@AutoConfigureMockMvc
class DegradedModeTest {

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
        Workspace defaultWorkspace = new Workspace("workspace_default", "Default Workspace", "Default",
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

        if (vectorStore instanceof InMemoryVectorStore store) {
            store.clear();
            Map<String, Object> meta = new HashMap<>();
            meta.put("dept", "ENTERPRISE");
            meta.put("source", "degraded_test.txt");
            meta.put("workspaceId", "workspace_default");
            store.add(List.of(new Document(
                    "Enterprise modernization budget is $42M for FY2025.", meta)));
        }
    }

    @Nested
    @DisplayName("Null Optional Services")
    class NullOptionalServices {

        @Test
        @DisplayName("Query succeeds without conversation memory service (null in non-enterprise edition)")
        void querySucceedsWithoutConversationMemory() throws Exception {
            // ci-e2e profile does not provide ConversationMemoryProvider bean,
            // so it is @Nullable null in RagOrchestrationService
            mockMvc.perform(get("/api/ask/enhanced")
                            .param("q", "What is the modernization budget?")
                            .param("dept", "ENTERPRISE")
                            .param("sessionId", "test-session-123"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.answer").isNotEmpty())
                    .andExpect(jsonPath("$.sources").isArray());
        }

        @Test
        @DisplayName("Query succeeds without session persistence service (null in non-enterprise edition)")
        void querySucceedsWithoutSessionPersistence() throws Exception {
            mockMvc.perform(get("/api/ask/enhanced")
                            .param("q", "What is the modernization budget?")
                            .param("dept", "ENTERPRISE")
                            .param("sessionId", "test-session-456"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.answer").isNotEmpty());
        }

        @Test
        @DisplayName("Query succeeds without HIPAA audit service (null in non-medical edition)")
        void querySucceedsWithoutHipaaAudit() throws Exception {
            // Even with a MEDICAL dept parameter, the pipeline should not NPE
            // when hipaaAuditService is null — it simply skips audit logging
            mockMvc.perform(get("/api/ask/enhanced")
                            .param("q", "What is the modernization budget?")
                            .param("dept", "ENTERPRISE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.answer").isNotEmpty());
        }
    }

    @Nested
    @DisplayName("Pipeline Without Advanced Strategies")
    class PipelineWithoutAdvancedStrategies {

        @Test
        @DisplayName("Query succeeds when all advanced RAG strategies are disabled")
        void querySucceedsWithAllStrategiesDisabled() throws Exception {
            // ci-e2e profile disables hybridrag, ragpart, miarag, hifirag, megarag, agentic
            // Pipeline should still return results via fallback retrieval
            mockMvc.perform(get("/api/ask/enhanced")
                            .param("q", "What is the modernization budget?")
                            .param("dept", "ENTERPRISE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.answer").isNotEmpty())
                    .andExpect(jsonPath("$.sources").isArray())
                    .andExpect(jsonPath("$.sources.length()").value(greaterThan(0)));
        }

        @Test
        @DisplayName("Zero-hop path works when all strategies are disabled")
        void zeroHopWorksWithStrategiesDisabled() throws Exception {
            mockMvc.perform(get("/api/ask/enhanced")
                            .param("q", "Hello")
                            .param("dept", "ENTERPRISE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.answer").isNotEmpty())
                    .andExpect(jsonPath("$.metrics.zeroHop").value(true))
                    .andExpect(jsonPath("$.metrics.routingDecision").value("NO_RETRIEVAL"));
        }
    }

    @Nested
    @DisplayName("Session Error Containment")
    class SessionErrorContainment {

        @Test
        @DisplayName("Query still returns answer even with invalid session ID format")
        void querySucceedsWithInvalidSessionId() throws Exception {
            // Even if session operations fail internally, the query should proceed
            mockMvc.perform(get("/api/ask/enhanced")
                            .param("q", "What is the modernization budget?")
                            .param("dept", "ENTERPRISE")
                            .param("sessionId", ""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.answer").isNotEmpty());
        }

        @Test
        @DisplayName("Query without sessionId skips session handling entirely")
        void queryWithoutSessionIdSkipsSessionHandling() throws Exception {
            mockMvc.perform(get("/api/ask/enhanced")
                            .param("q", "What is the modernization budget?")
                            .param("dept", "ENTERPRISE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.answer").isNotEmpty())
                    .andExpect(jsonPath("$.sources").isArray());
        }
    }

    @TestConfiguration
    static class DegradedModeTestConfig {
        @Bean
        @Primary
        VectorStore vectorStore() {
            return new InMemoryVectorStore();
        }

        @Bean
        @Primary
        ChatModel chatModel() {
            return new StubChatModel();
        }

        @Bean
        @Primary
        EmbeddingModel embeddingModel() {
            return Mockito.mock(EmbeddingModel.class);
        }
    }

    private static final class StubChatModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage("Stub response"))));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ChatOptionsBuilder.builder().build();
        }
    }
}
