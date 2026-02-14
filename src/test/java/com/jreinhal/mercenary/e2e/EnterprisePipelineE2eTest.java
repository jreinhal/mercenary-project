package com.jreinhal.mercenary.e2e;

import static org.hamcrest.Matchers.greaterThan;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * Enterprise pipeline E2E tests with advanced RAG strategies enabled
 * (hybridrag, ragpart, miarag, hifirag). Validates that the full
 * enterprise strategy waterfall executes without errors.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"ci-e2e-enterprise", "dev"})
@AutoConfigureMockMvc
class EnterprisePipelineE2eTest {
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

        if (vectorStore instanceof InMemoryVectorStore store) {
            store.clear();

            Map<String, Object> enterpriseMeta = new HashMap<>();
            enterpriseMeta.put("source", "enterprise_budget.txt");
            enterpriseMeta.put("dept", "ENTERPRISE");
            enterpriseMeta.put("workspaceId", "workspace_default");
            enterpriseMeta.put("partition_id", "budget_partition");

            Map<String, Object> docMeta = new HashMap<>();
            docMeta.put("source", "enterprise_strategy.txt");
            docMeta.put("dept", "ENTERPRISE");
            docMeta.put("workspaceId", "workspace_default");
            docMeta.put("partition_id", "strategy_partition");

            vectorStore.add(List.of(
                    new Document("The total program budget is $150M allocated across 5 divisions.", enterpriseMeta),
                    new Document("The enterprise modernization strategy focuses on cloud migration, "
                            + "microservices adoption, and zero-trust security architecture.", docMeta)
            ));
        }
    }

    @Test
    void healthEndpointReturnsNominal() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(content().string("SYSTEMS NOMINAL"));
    }

    @Test
    void chunkQueryReturnsSourcesWithStrategiesEnabled() throws Exception {
        mockMvc.perform(get("/api/ask/enhanced")
                        .param("q", "What is the total program budget?")
                        .param("dept", "ENTERPRISE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics.routingDecision").value("CHUNK"))
                .andExpect(jsonPath("$.sources").isArray())
                .andExpect(jsonPath("$.sources.length()").value(greaterThan(0)));
    }

    @Test
    void documentQueryRoutesToDocument() throws Exception {
        mockMvc.perform(get("/api/ask/enhanced")
                        .param("q", "Summarize the enterprise modernization strategy and its key pillars")
                        .param("dept", "ENTERPRISE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics.routingDecision").value("DOCUMENT"))
                .andExpect(jsonPath("$.sources").isArray());
    }

    @TestConfiguration
    static class EnterpriseVectorStoreTestConfig {
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
