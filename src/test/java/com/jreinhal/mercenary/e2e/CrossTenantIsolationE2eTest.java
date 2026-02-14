package com.jreinhal.mercenary.e2e;

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
import java.util.Set;
import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.model.ClearanceLevel;
import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.UserRole;
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
 * Cross-tenant isolation E2E tests verifying zero data leakage across
 * sectors (ENTERPRISE, GOVERNMENT, MEDICAL) and workspaces (alpha, beta).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"ci-e2e", "dev"})
@AutoConfigureMockMvc
class CrossTenantIsolationE2eTest {

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

    private static final String WS_ALPHA = "workspace_alpha";
    private static final String WS_BETA = "workspace_beta";

    @BeforeEach
    void setup() {
        Workspace wsAlpha = new Workspace(WS_ALPHA, "Alpha Workspace", "Alpha",
                "system", java.time.Instant.now(), java.time.Instant.now(), WorkspaceQuota.unlimited(), true);
        Workspace wsBeta = new Workspace(WS_BETA, "Beta Workspace", "Beta",
                "system", java.time.Instant.now(), java.time.Instant.now(), WorkspaceQuota.unlimited(), true);
        Workspace wsDefault = new Workspace("workspace_default", "Default", "Default",
                "system", java.time.Instant.now(), java.time.Instant.now(), WorkspaceQuota.unlimited(), true);

        when(mongoTemplate.getCollection(anyString()).countDocuments()).thenReturn(0L);
        when(userRepository.findByUsername(anyString())).thenReturn(Optional.empty());
        when(userRepository.findByExternalId(anyString())).thenReturn(Optional.empty());
        when(userRepository.findById(anyString())).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.countByWorkspaceIdsContaining(anyString())).thenReturn(0L);
        when(userRepository.findByWorkspaceIdsContaining(anyString())).thenReturn(List.of());
        when(workspaceRepository.existsById(anyString())).thenReturn(true);
        when(workspaceRepository.findById(WS_ALPHA)).thenReturn(Optional.of(wsAlpha));
        when(workspaceRepository.findById(WS_BETA)).thenReturn(Optional.of(wsBeta));
        when(workspaceRepository.findById("workspace_default")).thenReturn(Optional.of(wsDefault));
        when(workspaceRepository.findAll()).thenReturn(List.of(wsAlpha, wsBeta, wsDefault));
        when(workspaceRepository.findByIdIn(any())).thenReturn(List.of(wsAlpha, wsBeta, wsDefault));
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(invocation -> invocation.getArgument(0));

        if (vectorStore instanceof InMemoryVectorStore store) {
            store.clear();
            seedDocuments(store);
        }
    }

    private void seedDocuments(InMemoryVectorStore store) {
        // ENTERPRISE sector documents — split across workspaces
        store.add(List.of(
                doc("Enterprise Q3 revenue was $42M.", "ENTERPRISE", "enterprise_finance.txt", WS_ALPHA),
                doc("Enterprise security audit passed in December.", "ENTERPRISE", "enterprise_audit.txt", WS_BETA)
        ));

        // GOVERNMENT sector documents
        store.add(List.of(
                doc("Government classified satellite budget is $890M.", "GOVERNMENT", "gov_budget.txt", WS_ALPHA),
                doc("Government facility clearance requires SECRET minimum.", "GOVERNMENT", "gov_clearance.txt", WS_ALPHA)
        ));

        // MEDICAL sector documents
        store.add(List.of(
                doc("Medical patient readmission rate is 12.5%.", "MEDICAL", "med_stats.txt", WS_BETA),
                doc("Medical HIPAA compliance audit passed March 2025.", "MEDICAL", "med_hipaa.txt", WS_BETA)
        ));
    }

    private Document doc(String content, String dept, String source) {
        return doc(content, dept, source, "workspace_default");
    }

    private Document doc(String content, String dept, String source, String workspaceId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("dept", dept);
        metadata.put("source", source);
        metadata.put("workspaceId", workspaceId);
        return new Document(content, metadata);
    }

    @Nested
    @DisplayName("Sector Isolation")
    class SectorIsolation {

        @Test
        @DisplayName("Enterprise query should not return government documents")
        void enterpriseShouldNotReturnGovernmentDocs() throws Exception {
            mockMvc.perform(get("/api/ask/enhanced")
                            .param("q", "What is the satellite budget?")
                            .param("dept", "ENTERPRISE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sources[?(@.metadata.dept == 'GOVERNMENT')]").isEmpty());
        }

        @Test
        @DisplayName("Enterprise query should not return medical documents")
        void enterpriseShouldNotReturnMedicalDocs() throws Exception {
            mockMvc.perform(get("/api/ask/enhanced")
                            .param("q", "What is the patient readmission rate?")
                            .param("dept", "ENTERPRISE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sources[?(@.metadata.dept == 'MEDICAL')]").isEmpty());
        }

        @Test
        @DisplayName("Government query should not return enterprise documents")
        void governmentShouldNotReturnEnterpriseDocs() throws Exception {
            mockMvc.perform(get("/api/ask/enhanced")
                            .param("q", "What is the Q3 revenue?")
                            .param("dept", "GOVERNMENT"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sources[?(@.metadata.dept == 'ENTERPRISE')]").isEmpty());
        }

        @Test
        @DisplayName("Medical query should not return government documents")
        void medicalShouldNotReturnGovernmentDocs() throws Exception {
            mockMvc.perform(get("/api/ask/enhanced")
                            .param("q", "What is the satellite budget?")
                            .param("dept", "MEDICAL"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sources[?(@.metadata.dept == 'GOVERNMENT')]").isEmpty());
        }

        @Test
        @DisplayName("Enterprise query should return enterprise documents")
        void enterpriseShouldReturnOwnDocs() throws Exception {
            mockMvc.perform(get("/api/ask/enhanced")
                            .param("q", "What is the Q3 revenue?")
                            .param("dept", "ENTERPRISE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sources").isArray())
                    .andExpect(jsonPath("$.sources.length()").value(greaterThan(0)));
        }
    }

    @Nested
    @DisplayName("Cross-Sector Query Safety")
    class CrossSectorQuerySafety {

        @Test
        @DisplayName("Query for content exclusive to another sector should return no matching sources")
        void queryShouldNotLeakCrossSectorContent() throws Exception {
            // Query MEDICAL about a term only in GOVERNMENT docs
            mockMvc.perform(get("/api/ask/enhanced")
                            .param("q", "What is the satellite budget?")
                            .param("dept", "MEDICAL"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sources[?(@.metadata.dept == 'GOVERNMENT')]").isEmpty())
                    .andExpect(jsonPath("$.sources[?(@.metadata.dept == 'ENTERPRISE')]").isEmpty());
        }
    }

    @TestConfiguration
    static class CrossTenantTestConfig {
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
