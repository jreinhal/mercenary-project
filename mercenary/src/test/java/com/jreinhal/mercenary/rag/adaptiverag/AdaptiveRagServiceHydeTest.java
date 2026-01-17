package com.jreinhal.mercenary.rag.adaptiverag;

import com.jreinhal.mercenary.reasoning.ReasoningTracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AdaptiveRagService HyDE and Multi-Hop signal detection.
 *
 * These tests verify that the routing service correctly identifies queries
 * that would benefit from specialized retrieval strategies:
 * - HyDE: Vague/conceptual queries
 * - Multi-Hop: Relationship/causation queries
 * - Named Entity: Proper noun presence
 */
@ExtendWith(MockitoExtension.class)
class AdaptiveRagServiceHydeTest {

    @Mock
    private ReasoningTracer reasoningTracer;

    @Mock
    private ChatClient.Builder chatClientBuilder;

    @Mock
    private ChatClient chatClient;

    private AdaptiveRagService adaptiveRagService;

    @BeforeEach
    void setUp() {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        adaptiveRagService = new AdaptiveRagService(reasoningTracer, chatClientBuilder);

        // Enable routing but make LLM calls throw to force heuristic fallback
        // This tests the signal detection which happens BEFORE routing decision
        org.springframework.test.util.ReflectionTestUtils.setField(adaptiveRagService, "enabled", true);

        // Mock LLM to return CHUNK (fallback to heuristics if exception)
        lenient().when(chatClient.prompt()).thenThrow(new RuntimeException("Test: Use heuristics"));
    }

    @Nested
    @DisplayName("HyDE Signal Detection")
    class HydeSignalTests {

        @Test
        @DisplayName("Detects 'that one' vague reference pattern")
        void testThatOnePattern() {
            // Given
            String query = "that one report about quarterly earnings";

            // When
            AdaptiveRagService.RoutingResult result = adaptiveRagService.route(query);

            // Then
            Map<String, Object> signals = result.signals();
            assertTrue((Boolean) signals.get("isHyde"),
                    "Should detect HyDE-suitable query with 'that one' pattern");
        }

        @Test
        @DisplayName("Detects 'something about' vague reference pattern")
        void testSomethingAboutPattern() {
            // Given
            String query = "something about compliance regulations";

            // When
            AdaptiveRagService.RoutingResult result = adaptiveRagService.route(query);

            // Then
            assertTrue((Boolean) result.signals().get("isHyde"),
                    "Should detect HyDE-suitable query with 'something about' pattern");
        }

        @Test
        @DisplayName("Detects 'remember' pattern for vague recall")
        void testRememberPattern() {
            // Given
            String query = "remember the document about security protocols";

            // When
            AdaptiveRagService.RoutingResult result = adaptiveRagService.route(query);

            // Then
            assertTrue((Boolean) result.signals().get("isHyde"),
                    "Should detect HyDE-suitable query with 'remember' pattern");
        }

        @Test
        @DisplayName("Detects conceptual queries with 'concept like'")
        void testConceptPattern() {
            // Given
            String query = "concept like zero-trust architecture";

            // When
            AdaptiveRagService.RoutingResult result = adaptiveRagService.route(query);

            // Then
            assertTrue((Boolean) result.signals().get("isHyde"),
                    "Should detect HyDE-suitable conceptual query");
        }

        @Test
        @DisplayName("Does not flag specific factual queries as HyDE")
        void testSpecificQueryNotHyde() {
            // Given
            String query = "What is the HIPAA compliance deadline for 2024?";

            // When
            AdaptiveRagService.RoutingResult result = adaptiveRagService.route(query);

            // Then
            assertFalse((Boolean) result.signals().get("isHyde"),
                    "Specific factual query should not trigger HyDE");
        }
    }

    @Nested
    @DisplayName("Multi-Hop Signal Detection")
    class MultiHopSignalTests {

        @Test
        @DisplayName("Detects 'how does X affect Y' relationship pattern")
        void testAffectPattern() {
            // Given
            String query = "how does the new policy affect employee benefits";

            // When
            AdaptiveRagService.RoutingResult result = adaptiveRagService.route(query);

            // Then
            assertTrue((Boolean) result.signals().get("isMultiHop"),
                    "Should detect multi-hop query with 'affect' pattern");
        }

        @Test
        @DisplayName("Detects 'relationship between' pattern")
        void testRelationshipPattern() {
            // Given
            String query = "relationship between security clearance and data access";

            // When
            AdaptiveRagService.RoutingResult result = adaptiveRagService.route(query);

            // Then
            assertTrue((Boolean) result.signals().get("isMultiHop"),
                    "Should detect multi-hop query with 'relationship between' pattern");
        }

        @Test
        @DisplayName("Detects causation patterns with 'chain of'")
        void testChainOfPattern() {
            // Given
            String query = "chain of events leading to the security breach";

            // When
            AdaptiveRagService.RoutingResult result = adaptiveRagService.route(query);

            // Then
            assertTrue((Boolean) result.signals().get("isMultiHop"),
                    "Should detect multi-hop query with 'chain of' pattern");
        }

        @Test
        @DisplayName("Simple lookup queries are not multi-hop")
        void testSimpleQueryNotMultiHop() {
            // Given
            String query = "What is the current budget allocation?";

            // When
            AdaptiveRagService.RoutingResult result = adaptiveRagService.route(query);

            // Then
            assertFalse((Boolean) result.signals().get("isMultiHop"),
                    "Simple lookup should not trigger multi-hop");
        }
    }

    @Nested
    @DisplayName("Named Entity Signal Detection")
    class NamedEntitySignalTests {

        @Test
        @DisplayName("Detects proper noun names")
        void testProperNouns() {
            // Given
            String query = "Find documents authored by John Smith";

            // When
            AdaptiveRagService.RoutingResult result = adaptiveRagService.route(query);

            // Then
            assertTrue((Boolean) result.signals().get("hasNamedEntity"),
                    "Should detect named entity 'John Smith'");
        }

        @Test
        @DisplayName("Detects acronyms as named entities")
        void testAcronyms() {
            // Given
            String query = "What are the HIPAA requirements for PHI?";

            // When
            AdaptiveRagService.RoutingResult result = adaptiveRagService.route(query);

            // Then
            assertTrue((Boolean) result.signals().get("hasNamedEntity"),
                    "Should detect acronyms like HIPAA, PHI as named entities");
        }

        @Test
        @DisplayName("Lowercase queries have no named entity signal")
        void testNoNamedEntity() {
            // Given
            String query = "how do i search for documents";

            // When
            AdaptiveRagService.RoutingResult result = adaptiveRagService.route(query);

            // Then
            assertFalse((Boolean) result.signals().get("hasNamedEntity"),
                    "All-lowercase query should not have named entity signal");
        }
    }

    @Nested
    @DisplayName("Combined Signal Detection")
    class CombinedSignalTests {

        @Test
        @DisplayName("Query can have both HyDE and Multi-Hop signals")
        void testCombinedSignals() {
            // Given - vague reference ("remember the") + causation ("chain of")
            String query = "remember the chain of events affecting compliance";

            // When
            AdaptiveRagService.RoutingResult result = adaptiveRagService.route(query);

            // Then
            Map<String, Object> signals = result.signals();
            assertTrue((Boolean) signals.get("isHyde"), "Should detect HyDE signal");
            assertTrue((Boolean) signals.get("isMultiHop"), "Should detect multi-hop signal");
        }

        @Test
        @DisplayName("Signals include word count and question mark")
        void testBasicSignals() {
            // Given
            String query = "What is the policy?";

            // When
            AdaptiveRagService.RoutingResult result = adaptiveRagService.route(query);

            // Then
            Map<String, Object> signals = result.signals();
            assertNotNull(signals.get("wordCount"));
            assertTrue((Boolean) signals.get("hasQuestionMark"));
        }
    }
}
