package com.jreinhal.mercenary.rag.adaptiverag;

import com.jreinhal.mercenary.reasoning.ReasoningTracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests pattern-based routing for all decision types in {@link AdaptiveRagService}.
 */
class AdaptiveRagRoutingTest {

    private AdaptiveRagService service;

    @BeforeEach
    void setUp() {
        ReasoningTracer tracer = mock(ReasoningTracer.class);
        ChatClient.Builder mockBuilder = mock(ChatClient.Builder.class);
        ChatClient mockClient = mock(ChatClient.class);
        when(mockBuilder.build()).thenReturn(mockClient);

        service = new AdaptiveRagService(tracer, mockBuilder);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "semanticRouterEnabled", false);
        ReflectionTestUtils.setField(service, "chunkTopK", 5);
        ReflectionTestUtils.setField(service, "documentTopK", 3);
    }

    @Nested
    @DisplayName("NO_RETRIEVAL Routing")
    class NoRetrievalRouting {

        @Test
        @DisplayName("Should route 'Hello' to NO_RETRIEVAL")
        void shouldRouteHelloToNoRetrieval() {
            var result = service.route("Hello");
            assertEquals(AdaptiveRagService.RoutingDecision.NO_RETRIEVAL, result.decision());
        }

        @Test
        @DisplayName("Should route 'Thank you' to NO_RETRIEVAL")
        void shouldRouteThankYouToNoRetrieval() {
            var result = service.route("Thanks");
            assertEquals(AdaptiveRagService.RoutingDecision.NO_RETRIEVAL, result.decision());
        }

        @Test
        @DisplayName("Should route 'Goodbye' to NO_RETRIEVAL")
        void shouldRouteGoodbyeToNoRetrieval() {
            var result = service.route("Bye");
            assertEquals(AdaptiveRagService.RoutingDecision.NO_RETRIEVAL, result.decision());
        }

        @Test
        @DisplayName("Should route 'Yes' to NO_RETRIEVAL")
        void shouldRouteYesToNoRetrieval() {
            var result = service.route("Yes");
            assertEquals(AdaptiveRagService.RoutingDecision.NO_RETRIEVAL, result.decision());
        }

        @Test
        @DisplayName("Should route null to NO_RETRIEVAL")
        void shouldRouteNullToNoRetrieval() {
            var result = service.route(null);
            assertEquals(AdaptiveRagService.RoutingDecision.NO_RETRIEVAL, result.decision());
        }

        @Test
        @DisplayName("Should route blank to NO_RETRIEVAL")
        void shouldRouteBlankToNoRetrieval() {
            var result = service.route("   ");
            assertEquals(AdaptiveRagService.RoutingDecision.NO_RETRIEVAL, result.decision());
        }

        @Test
        @DisplayName("Should have high confidence for conversational patterns")
        void shouldHaveHighConfidenceForConversational() {
            var result = service.route("Hello");
            assertTrue(result.confidence() >= 0.95, "Conversational confidence should be >= 0.95");
        }
    }

    @Nested
    @DisplayName("CHUNK Routing")
    class ChunkRouting {

        @Test
        @DisplayName("Should route 'What is the budget?' to CHUNK")
        void shouldRouteFactualToChunk() {
            var result = service.route("What is the total program budget?");
            assertEquals(AdaptiveRagService.RoutingDecision.CHUNK, result.decision());
        }

        @Test
        @DisplayName("Should route 'When did the project start?' to CHUNK")
        void shouldRouteWhenToChunk() {
            var result = service.route("When did the project start?");
            assertEquals(AdaptiveRagService.RoutingDecision.CHUNK, result.decision());
        }

        @Test
        @DisplayName("Should route 'Who is the project manager?' to CHUNK")
        void shouldRouteWhoToChunk() {
            var result = service.route("Who is the project manager?");
            assertEquals(AdaptiveRagService.RoutingDecision.CHUNK, result.decision());
        }

        @Test
        @DisplayName("Should route 'How much does it cost?' to CHUNK")
        void shouldRouteHowMuchToChunk() {
            var result = service.route("How much does the system cost?");
            assertEquals(AdaptiveRagService.RoutingDecision.CHUNK, result.decision());
        }

        @Test
        @DisplayName("Should route 'Define RAG' to CHUNK")
        void shouldRouteDefineToChunk() {
            var result = service.route("Define RAG");
            assertEquals(AdaptiveRagService.RoutingDecision.CHUNK, result.decision());
        }

        @Test
        @DisplayName("Should route query with dollar amounts to CHUNK")
        void shouldRouteQueryWithDollarToChunk() {
            var result = service.route("What was the $150M allocation for?");
            assertEquals(AdaptiveRagService.RoutingDecision.CHUNK, result.decision());
        }

        @Test
        @DisplayName("Should have high confidence for chunk patterns")
        void shouldHaveHighConfidenceForChunk() {
            var result = service.route("What is the budget?");
            assertTrue(result.confidence() >= 0.90, "Chunk confidence should be >= 0.90");
        }
    }

    @Nested
    @DisplayName("DOCUMENT Routing")
    class DocumentRouting {

        @Test
        @DisplayName("Should route 'Summarize the report' to DOCUMENT")
        void shouldRouteSummarizeToDocument() {
            var result = service.route("Summarize the entire quarterly report");
            assertEquals(AdaptiveRagService.RoutingDecision.DOCUMENT, result.decision());
        }

        @Test
        @DisplayName("Should route 'Compare X and Y' to DOCUMENT")
        void shouldRouteCompareToDocument() {
            var result = service.route("Compare the two project proposals and their differences");
            assertEquals(AdaptiveRagService.RoutingDecision.DOCUMENT, result.decision());
        }

        @Test
        @DisplayName("Should route 'Analyze the impact' to DOCUMENT")
        void shouldRouteAnalyzeToDocument() {
            var result = service.route("Analyze the impact of the new policy on operations");
            assertEquals(AdaptiveRagService.RoutingDecision.DOCUMENT, result.decision());
        }

        @Test
        @DisplayName("Should route long queries (>15 words) to DOCUMENT")
        void shouldRouteLongQueryToDocument() {
            var result = service.route(
                    "Please explain in detail how the procurement process works from initial request "
                    + "through final delivery and what steps are involved at each stage");
            assertEquals(AdaptiveRagService.RoutingDecision.DOCUMENT, result.decision());
        }
    }

    @Nested
    @DisplayName("Disabled State")
    class DisabledState {

        @Test
        @DisplayName("Should default to CHUNK when disabled")
        void shouldDefaultToChunkWhenDisabled() {
            ReflectionTestUtils.setField(service, "enabled", false);
            var result = service.route("Hello");
            assertEquals(AdaptiveRagService.RoutingDecision.CHUNK, result.decision());
            assertEquals(1.0, result.confidence(), "Disabled state should have confidence 1.0");
        }
    }

    @Nested
    @DisplayName("TopK and Threshold")
    class TopKAndThreshold {

        @Test
        @DisplayName("NO_RETRIEVAL should have topK=0")
        void noRetrievalTopK() {
            assertEquals(0, service.getTopK(AdaptiveRagService.RoutingDecision.NO_RETRIEVAL));
        }

        @Test
        @DisplayName("CHUNK should use chunkTopK")
        void chunkTopK() {
            assertEquals(5, service.getTopK(AdaptiveRagService.RoutingDecision.CHUNK));
        }

        @Test
        @DisplayName("DOCUMENT should use documentTopK")
        void documentTopK() {
            assertEquals(3, service.getTopK(AdaptiveRagService.RoutingDecision.DOCUMENT));
        }
    }
}
