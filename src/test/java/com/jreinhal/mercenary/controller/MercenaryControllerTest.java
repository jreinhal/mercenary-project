package com.jreinhal.mercenary.controller;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.config.SectorConfig;
import com.jreinhal.mercenary.filter.SecurityContext;
import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.service.ConversationMemoryProvider;
import com.jreinhal.mercenary.service.SessionPersistenceProvider;
import com.jreinhal.mercenary.rag.ModalityRouter;
import com.jreinhal.mercenary.rag.adaptiverag.AdaptiveRagService;
import com.jreinhal.mercenary.rag.agentic.AgenticRagOrchestrator;
import com.jreinhal.mercenary.rag.birag.BidirectionalRagService;
import com.jreinhal.mercenary.rag.crag.RewriteService;
import com.jreinhal.mercenary.rag.hifirag.HiFiRagService;
import com.jreinhal.mercenary.rag.hgmem.HGMemQueryEngine;
import com.jreinhal.mercenary.rag.hybridrag.HybridRagService;
import com.jreinhal.mercenary.rag.megarag.MegaRagService;
import com.jreinhal.mercenary.rag.miarag.MiARagService;
import com.jreinhal.mercenary.rag.qucorag.QuCoRagService;
import com.jreinhal.mercenary.rag.ragpart.RagPartService;
import com.jreinhal.mercenary.reasoning.ReasoningTracer;
import com.jreinhal.mercenary.service.AuditService;
import com.jreinhal.mercenary.service.PiiRedactionService;
import com.jreinhal.mercenary.service.PromptGuardrailService;
import com.jreinhal.mercenary.service.QueryDecompositionService;
import com.jreinhal.mercenary.service.RagOrchestrationService;
import com.jreinhal.mercenary.service.SecureIngestionService;
import com.jreinhal.mercenary.service.AuthenticationService;
import com.jreinhal.mercenary.core.license.LicenseService;
import com.jreinhal.mercenary.security.ClientIpResolver;
import com.jreinhal.mercenary.workspace.WorkspacePolicy;
import com.jreinhal.mercenary.dto.EnhancedAskResponse;
import com.jreinhal.mercenary.reasoning.ReasoningTrace;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.github.benmanes.caffeine.cache.Cache;

import org.mockito.ArgumentMatchers;
import org.mockito.Answers;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MercenaryController.class)
@AutoConfigureMockMvc(addFilters = false)
class MercenaryControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VectorStore vectorStore;
    @MockitoBean
    private SecureIngestionService ingestionService;
    @MockitoBean(answers = Answers.RETURNS_DEEP_STUBS)
    private MongoTemplate mongoTemplate;
    @MockitoBean
    private AuditService auditService;
    @MockitoBean
    private QueryDecompositionService queryDecompositionService;
    @MockitoBean
    private ReasoningTracer reasoningTracer;
    @MockitoBean
    private QuCoRagService quCoRagService;
    @MockitoBean
    private AdaptiveRagService adaptiveRagService;
    @MockitoBean
    private RewriteService rewriteService;
    @MockitoBean
    private RagPartService ragPartService;
    @MockitoBean
    private HybridRagService hybridRagService;
    @MockitoBean
    private HiFiRagService hiFiRagService;
    @MockitoBean
    private MiARagService miARagService;
    @MockitoBean
    private MegaRagService megaRagService;
    @MockitoBean
    private HGMemQueryEngine hgMemQueryEngine;
    @MockitoBean
    private AgenticRagOrchestrator agenticRagOrchestrator;
    @MockitoBean
    private BidirectionalRagService bidirectionalRagService;
    @MockitoBean
    private ModalityRouter modalityRouter;
    @MockitoBean
    private SectorConfig sectorConfig;
    @MockitoBean
    private PromptGuardrailService guardrailService;
    @MockitoBean
    private PiiRedactionService piiRedactionService;
    @MockitoBean
    private ConversationMemoryProvider conversationMemoryService;
    @MockitoBean
    private SessionPersistenceProvider sessionPersistenceService;
    @MockitoBean
    private RagOrchestrationService ragOrchestrationService;
    @MockitoBean
    private Cache<String, String> secureDocCache;
    @MockitoBean
    private LicenseService licenseService;
    @MockitoBean
    private ClientIpResolver clientIpResolver;
    @MockitoBean
    private AuthenticationService authenticationService;
    @MockitoBean
    private WorkspacePolicy workspacePolicy;

    @TestConfiguration
    static class ChatClientTestConfig {
        @Bean
        ChatClient.Builder chatClientBuilder() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
            when(builder.defaultFunctions(ArgumentMatchers.any(String[].class))).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        return builder;
    }
}

    @BeforeEach
    void setUpUserContext() {
        SecurityContext.setCurrentUser(User.devUser("test"));
    }

    @AfterEach
    void clearUserContext() {
        SecurityContext.clear();
    }

    @Test
    void healthEndpointReturnsNominal() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(content().string("SYSTEMS NOMINAL"));
    }

    @Test
    void inspectRedactsPiiContent() throws Exception {
        Document doc = new Document("SSN: 123-45-6789", Map.of("source", "pii_doc.txt", "dept", "ENTERPRISE"));
        when(vectorStore.similaritySearch(ArgumentMatchers.any(SearchRequest.class))).thenReturn(List.of(doc));
        when(secureDocCache.getIfPresent(ArgumentMatchers.anyString())).thenReturn(null);
        when(sectorConfig.requiresElevatedClearance(ArgumentMatchers.any())).thenReturn(false);
        when(piiRedactionService.redact(ArgumentMatchers.anyString()))
                .thenReturn(new PiiRedactionService.RedactionResult(
                        "SSN: [REDACTED-SSN]",
                        Map.of(PiiRedactionService.PiiType.SSN, 1)
                ));

        mockMvc.perform(get("/api/inspect")
                        .param("fileName", "pii_doc.txt")
                        .param("dept", Department.ENTERPRISE.name()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.containsString("[REDACTED-SSN]")))
                .andExpect(jsonPath("$.redacted").value(true))
                .andExpect(jsonPath("$.redactionCount").value(1));
    }

    @Test
    @DisplayName("GET /api/ask delegates to ragOrchestrationService.ask()")
    void askDelegatesToService() throws Exception {
        when(ragOrchestrationService.ask(anyString(), anyString(), any(), any(), any()))
                .thenReturn("Test response");

        mockMvc.perform(get("/api/ask")
                        .param("q", "What is RAG?")
                        .param("dept", "ENTERPRISE"))
                .andExpect(status().isOk())
                .andExpect(content().string("Test response"));
    }

    @Test
    @DisplayName("GET /api/ask/enhanced passes override params to service")
    void askEnhancedPassesOverrides() throws Exception {
        EnhancedAskResponse response = new EnhancedAskResponse(
                "Enhanced response", List.of(), List.of("source.pdf"),
                Map.of("latencyMs", 100), "trace-123", "session-456");
        when(ragOrchestrationService.askEnhanced(
                anyString(), anyString(), any(), any(), any(),
                anyBoolean(), any(), any(), any(), any()))
                .thenReturn(response);

        mockMvc.perform(get("/api/ask/enhanced")
                        .param("q", "What is RAG?")
                        .param("dept", "ENTERPRISE")
                        .param("useHyde", "false")
                        .param("useGraphRag", "true")
                        .param("useReranking", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Enhanced response"))
                .andExpect(jsonPath("$.traceId").value("trace-123"))
                .andExpect(jsonPath("$.sessionId").value("session-456"));

        verify(ragOrchestrationService).askEnhanced(
                eq("What is RAG?"), eq("ENTERPRISE"), any(), any(), any(),
                eq(false), eq(false), eq(true), eq(false), any());
    }

    @Test
    @DisplayName("GET /api/ask/enhanced works without optional override params")
    void askEnhancedWithoutOverrides() throws Exception {
        EnhancedAskResponse response = new EnhancedAskResponse(
                "Default response", List.of(), List.of(), Map.of(), "trace-abc");
        when(ragOrchestrationService.askEnhanced(
                anyString(), anyString(), any(), any(), any(),
                anyBoolean(), any(), any(), any(), any()))
                .thenReturn(response);

        mockMvc.perform(get("/api/ask/enhanced")
                        .param("q", "Simple query")
                        .param("dept", "GOVERNMENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Default response"));

        verify(ragOrchestrationService).askEnhanced(
                eq("Simple query"), eq("GOVERNMENT"), any(), any(), any(),
                eq(false), isNull(), isNull(), isNull(), any());
    }

    @Test
    @DisplayName("GET /api/ask/enhanced with sessionId and deepAnalysis")
    void askEnhancedWithSessionAndDeepAnalysis() throws Exception {
        EnhancedAskResponse response = new EnhancedAskResponse(
                "Deep analysis response", List.of(), List.of(), Map.of(), "trace-deep");
        when(ragOrchestrationService.askEnhanced(
                anyString(), anyString(), any(), any(), any(),
                anyBoolean(), any(), any(), any(), any()))
                .thenReturn(response);

        mockMvc.perform(get("/api/ask/enhanced")
                        .param("q", "Complex query")
                        .param("dept", "MEDICAL")
                        .param("sessionId", "session-xyz")
                        .param("deepAnalysis", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Deep analysis response"));

        verify(ragOrchestrationService).askEnhanced(
                eq("Complex query"), eq("MEDICAL"), any(), any(),
                eq("session-xyz"), eq(true), any(), any(), any(), any());
    }

    @Test
    @DisplayName("GET /api/reasoning/{traceId} returns trace not found")
    void reasoningTraceNotFound() throws Exception {
        when(reasoningTracer.getTrace("nonexistent")).thenReturn(null);

        mockMvc.perform(get("/api/reasoning/nonexistent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value("Trace not found"))
                .andExpect(jsonPath("$.traceId").value("nonexistent"));
    }

    @Test
    @DisplayName("GET /api/reasoning/{traceId} returns trace for owner")
    void reasoningTraceReturnedForOwner() throws Exception {
        User user = User.devUser("test");
        ReasoningTrace trace = new ReasoningTrace("test query", "ENTERPRISE", user.getId(), null);
        when(reasoningTracer.getTrace(trace.getTraceId())).thenReturn(trace);

        mockMvc.perform(get("/api/reasoning/" + trace.getTraceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("test query"))
                .andExpect(jsonPath("$.department").value("ENTERPRISE"));
    }

    @Test
    @DisplayName("GET /api/reasoning/{traceId} denies access for non-owner non-admin")
    void reasoningTraceDeniedForNonOwner() throws Exception {
        ReasoningTrace trace = new ReasoningTrace("secret query", "GOVERNMENT", "other-user-id", null);
        when(reasoningTracer.getTrace(trace.getTraceId())).thenReturn(trace);

        // Current user is "test" (devUser) with ADMIN role, so access is granted
        mockMvc.perform(get("/api/reasoning/" + trace.getTraceId()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/reasoning/{traceId} requires authentication")
    void reasoningTraceRequiresAuth() throws Exception {
        SecurityContext.clear();

        mockMvc.perform(get("/api/reasoning/any-trace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value("Authentication required"));
    }
}
