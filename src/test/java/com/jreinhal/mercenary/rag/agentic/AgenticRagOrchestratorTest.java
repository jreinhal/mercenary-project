package com.jreinhal.mercenary.rag.agentic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jreinhal.mercenary.rag.adaptiverag.AdaptiveRagService;
import com.jreinhal.mercenary.rag.crag.CragGraderService;
import com.jreinhal.mercenary.rag.crag.RewriteService;
import com.jreinhal.mercenary.rag.hyde.HydeService;
import com.jreinhal.mercenary.rag.selfrag.SelfRagService;
import com.jreinhal.mercenary.reasoning.ReasoningTracer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;

class AgenticRagOrchestratorTest {

    private AdaptiveRagService adaptiveRag;
    private CragGraderService cragGrader;
    private RewriteService rewriteService;
    private HydeService hydeService;
    private SelfRagService selfRag;
    private VectorStore vectorStore;
    private ReasoningTracer reasoningTracer;
    private AgenticRagOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        adaptiveRag = mock(AdaptiveRagService.class);
        cragGrader = mock(CragGraderService.class);
        rewriteService = mock(RewriteService.class);
        hydeService = mock(HydeService.class);
        selfRag = mock(SelfRagService.class);
        vectorStore = mock(VectorStore.class);
        reasoningTracer = mock(ReasoningTracer.class);

        orchestrator = new AgenticRagOrchestrator(
                adaptiveRag, cragGrader, rewriteService, hydeService,
                selfRag, vectorStore, reasoningTracer);
    }

    @Nested
    @DisplayName("Disabled orchestrator")
    class DisabledTest {
        @Test
        @DisplayName("Should perform simple retrieval when disabled")
        void shouldPerformSimpleRetrievalWhenDisabled() {
            ReflectionTestUtils.setField(orchestrator, "enabled", false);
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

            AgenticRagOrchestrator.AgenticResult result =
                    orchestrator.process("test query", "ENTERPRISE");

            assertThat(result).isNotNull();
            assertThat(result.executedSteps()).contains("SIMPLE_RETRIEVAL");
        }
    }

    @Nested
    @DisplayName("NO_RETRIEVAL routing")
    class NoRetrievalTest {
        @Test
        @DisplayName("Should return direct response when routing says NO_RETRIEVAL")
        void shouldReturnDirectResponseForNoRetrieval() {
            ReflectionTestUtils.setField(orchestrator, "enabled", true);
            AdaptiveRagService.RoutingResult routing = new AdaptiveRagService.RoutingResult(
                    AdaptiveRagService.RoutingDecision.NO_RETRIEVAL,
                    "greeting", 0.9, Map.of());
            when(adaptiveRag.route(anyString())).thenReturn(routing);

            AgenticRagOrchestrator.AgenticResult result =
                    orchestrator.process("hello", "ENTERPRISE");

            assertThat(result.executedSteps()).contains("DIRECT_RESPONSE");
        }
    }

    @Nested
    @DisplayName("AgenticResult record")
    class AgenticResultTest {
        @Test
        @DisplayName("Record should store all fields correctly")
        void shouldStoreAllFields() {
            List<Document> sources = List.of(new Document("doc1"));
            AgenticRagOrchestrator.AgenticResult result =
                    new AgenticRagOrchestrator.AgenticResult(
                            "response", sources, 0.95,
                            List.of("STEP1", "STEP2"), 2, Map.of("key", "value"));

            assertThat(result.response()).isEqualTo("response");
            assertThat(result.sources()).hasSize(1);
            assertThat(result.confidence()).isEqualTo(0.95);
            assertThat(result.executedSteps()).containsExactly("STEP1", "STEP2");
            assertThat(result.iterations()).isEqualTo(2);
            assertThat(result.metrics()).containsEntry("key", "value");
        }

        @Test
        @DisplayName("Record equality should work for identical values")
        void shouldHaveCorrectEquality() {
            List<Document> sources = List.of();
            AgenticRagOrchestrator.AgenticResult a =
                    new AgenticRagOrchestrator.AgenticResult(
                            "resp", sources, 0.5, List.of("S1"), 1, Map.of());
            AgenticRagOrchestrator.AgenticResult b =
                    new AgenticRagOrchestrator.AgenticResult(
                            "resp", sources, 0.5, List.of("S1"), 1, Map.of());

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }
    }

    @Nested
    @DisplayName("Enabled orchestrator with CHUNK routing")
    class EnabledChunkTest {
        @BeforeEach
        void enableOrchestrator() {
            ReflectionTestUtils.setField(orchestrator, "enabled", true);
            ReflectionTestUtils.setField(orchestrator, "useHyde", true);
            ReflectionTestUtils.setField(orchestrator, "useSelfRag", false);
            ReflectionTestUtils.setField(orchestrator, "maxIterations", 1);
            ReflectionTestUtils.setField(orchestrator, "confidenceThreshold", 0.6);
        }

        @Test
        @DisplayName("hydeAllowed=false should use standard retrieval")
        void hydeDisabledShouldUseStandardRetrieval() {
            AdaptiveRagService.RoutingResult routing = new AdaptiveRagService.RoutingResult(
                    AdaptiveRagService.RoutingDecision.CHUNK,
                    "factual query", 0.8, Map.of());
            when(adaptiveRag.route(anyString())).thenReturn(routing);

            Document doc = new Document("test content");
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));

            CragGraderService.GradedDocument graded = new CragGraderService.GradedDocument(
                    doc, CragGraderService.DocumentGrade.CORRECT, 0.9, "relevant");
            CragGraderService.CragResult cragResult = new CragGraderService.CragResult(
                    CragGraderService.CragDecision.USE_RETRIEVED,
                    List.of(graded), "good results", 0.9, Map.of());
            when(cragGrader.evaluate(anyString(), any())).thenReturn(cragResult);
            when(cragGrader.getUsableDocuments(any())).thenReturn(List.of(doc));

            AgenticRagOrchestrator.AgenticResult result =
                    orchestrator.process("test", "ENTERPRISE", false);

            assertThat(result.executedSteps()).contains("STANDARD_RETRIEVAL");
        }

        @Test
        @DisplayName("hydeAllowed=true with HyDE enabled should allow HyDE retrieval")
        void hydeEnabledShouldAllowHydeRetrieval() {
            AdaptiveRagService.RoutingResult routing = new AdaptiveRagService.RoutingResult(
                    AdaptiveRagService.RoutingDecision.CHUNK,
                    "factual query", 0.8, Map.of());
            when(adaptiveRag.route(anyString())).thenReturn(routing);
            when(hydeService.shouldUseHyde(any())).thenReturn(true);

            Document doc = new Document("content");
            HydeService.HydeResult hydeResult = new HydeService.HydeResult(
                    List.of(doc), "hypothesis", true, Map.of());
            when(hydeService.retrieve(anyString(), anyString())).thenReturn(hydeResult);

            CragGraderService.GradedDocument graded = new CragGraderService.GradedDocument(
                    doc, CragGraderService.DocumentGrade.CORRECT, 0.9, "relevant");
            CragGraderService.CragResult cragResult = new CragGraderService.CragResult(
                    CragGraderService.CragDecision.USE_RETRIEVED,
                    List.of(graded), "good", 0.9, Map.of());
            when(cragGrader.evaluate(anyString(), any())).thenReturn(cragResult);
            when(cragGrader.getUsableDocuments(any())).thenReturn(List.of(doc));

            AgenticRagOrchestrator.AgenticResult result =
                    orchestrator.process("test", "ENTERPRISE", true);

            assertThat(result.executedSteps()).contains("HYDE_RETRIEVAL");
        }
    }
}
