package com.jreinhal.mercenary.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jreinhal.mercenary.util.SimpleCircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link SimpleCircuitBreaker} state machine and its integration
 * with {@link PromptGuardrailService} fail-closed behavior.
 */
class GuardrailCircuitBreakerTest {

    @Nested
    @DisplayName("SimpleCircuitBreaker State Machine")
    class StateMachine {

        private SimpleCircuitBreaker breaker;

        @BeforeEach
        void setUp() {
            // threshold=3, openDuration=1s (short for tests), halfOpenMaxCalls=1
            breaker = new SimpleCircuitBreaker(3, Duration.ofSeconds(1), 1);
        }

        @Test
        @DisplayName("Starts in CLOSED state and allows requests")
        void startsClosedAndAllowsRequests() {
            assertEquals(SimpleCircuitBreaker.State.CLOSED, breaker.getState());
            assertTrue(breaker.allowRequest());
        }

        @Test
        @DisplayName("Stays CLOSED after failures below threshold")
        void staysClosedBelowThreshold() {
            breaker.recordFailure(new RuntimeException("fail 1"));
            breaker.recordFailure(new RuntimeException("fail 2"));
            assertEquals(SimpleCircuitBreaker.State.CLOSED, breaker.getState());
            assertTrue(breaker.allowRequest());
        }

        @Test
        @DisplayName("Transitions CLOSED to OPEN at failure threshold")
        void opensAtThreshold() {
            breaker.recordFailure(new RuntimeException("fail 1"));
            breaker.recordFailure(new RuntimeException("fail 2"));
            breaker.recordFailure(new RuntimeException("fail 3"));
            assertEquals(SimpleCircuitBreaker.State.OPEN, breaker.getState());
        }

        @Test
        @DisplayName("OPEN state blocks requests")
        void openBlocksRequests() {
            tripCircuit();
            assertFalse(breaker.allowRequest(), "OPEN circuit should block requests");
        }

        @Test
        @DisplayName("Transitions OPEN to HALF_OPEN after duration expires")
        void transitionsToHalfOpenAfterDuration() throws InterruptedException {
            tripCircuit();
            // Wait for openDuration (1s) to expire
            Thread.sleep(1100);
            assertTrue(breaker.allowRequest(), "Should allow request after open duration expires");
            assertEquals(SimpleCircuitBreaker.State.HALF_OPEN, breaker.getState());
        }

        @Test
        @DisplayName("HALF_OPEN limits requests to halfOpenMaxCalls")
        void halfOpenLimitsRequests() throws InterruptedException {
            tripCircuit();
            Thread.sleep(1100);
            // First call transitions to HALF_OPEN and is allowed (halfOpenMaxCalls=1)
            assertTrue(breaker.allowRequest());
            // Second call exceeds limit
            assertFalse(breaker.allowRequest(), "Should block after halfOpenMaxCalls exceeded");
        }

        @Test
        @DisplayName("Success in HALF_OPEN transitions to CLOSED")
        void successInHalfOpenCloses() throws InterruptedException {
            tripCircuit();
            Thread.sleep(1100);
            breaker.allowRequest(); // transition to HALF_OPEN
            breaker.recordSuccess();
            assertEquals(SimpleCircuitBreaker.State.CLOSED, breaker.getState());
            assertTrue(breaker.allowRequest(), "CLOSED circuit should allow requests");
        }

        @Test
        @DisplayName("Failure in HALF_OPEN transitions back to OPEN")
        void failureInHalfOpenReopens() throws InterruptedException {
            tripCircuit();
            Thread.sleep(1100);
            breaker.allowRequest(); // transition to HALF_OPEN
            assertEquals(SimpleCircuitBreaker.State.HALF_OPEN, breaker.getState());

            breaker.recordFailure(new RuntimeException("half-open fail"));
            assertEquals(SimpleCircuitBreaker.State.OPEN, breaker.getState(),
                    "Single failure in HALF_OPEN should reopen circuit");
        }

        @Test
        @DisplayName("recordSuccess in CLOSED resets failure count")
        void successInClosedResetsCount() {
            breaker.recordFailure(new RuntimeException("fail 1"));
            breaker.recordFailure(new RuntimeException("fail 2"));
            breaker.recordSuccess();
            // After reset, need full threshold again to trip
            breaker.recordFailure(new RuntimeException("fail A"));
            breaker.recordFailure(new RuntimeException("fail B"));
            assertEquals(SimpleCircuitBreaker.State.CLOSED, breaker.getState(),
                    "Should still be CLOSED — only 2 failures since reset");
        }

        @Test
        @DisplayName("Constructor enforces minimum failureThreshold of 1")
        void enforcesMinimumThreshold() {
            SimpleCircuitBreaker minBreaker = new SimpleCircuitBreaker(0, Duration.ofSeconds(1), 1);
            // With threshold clamped to 1, a single failure should trip
            minBreaker.recordFailure(new RuntimeException("single fail"));
            assertEquals(SimpleCircuitBreaker.State.OPEN, minBreaker.getState());
        }

        @Test
        @DisplayName("Constructor defaults null openDuration to 30 seconds")
        void defaultsNullDuration() {
            SimpleCircuitBreaker nullDuration = new SimpleCircuitBreaker(3, null, 1);
            // Trip it and verify it stays OPEN (30s hasn't elapsed)
            for (int i = 0; i < 3; i++) {
                nullDuration.recordFailure(new RuntimeException("fail"));
            }
            assertFalse(nullDuration.allowRequest(), "Should stay OPEN with default 30s duration");
        }

        @Test
        @DisplayName("Concurrent threads see consistent state transitions")
        void concurrentStateTransitions() throws InterruptedException {
            int threadCount = 10;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger allowedCount = new AtomicInteger(0);

            tripCircuit();
            Thread.sleep(1100); // Let open duration expire

            for (int i = 0; i < threadCount; i++) {
                new Thread(() -> {
                    try {
                        startLatch.await();
                        if (breaker.allowRequest()) {
                            allowedCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                }).start();
            }

            startLatch.countDown();
            doneLatch.await();

            // halfOpenMaxCalls=1, so at most 1 should be allowed
            assertTrue(allowedCount.get() <= 1,
                    "At most halfOpenMaxCalls (1) requests should be allowed, got " + allowedCount.get());
        }

        private void tripCircuit() {
            for (int i = 0; i < 3; i++) {
                breaker.recordFailure(new RuntimeException("trip " + i));
            }
        }
    }

    @Nested
    @DisplayName("PromptGuardrailService Circuit Breaker Integration")
    class GuardrailIntegration {

        private PromptGuardrailService guardrailService;

        @BeforeEach
        void setUp() {
            ChatClient.Builder mockBuilder = mock(ChatClient.Builder.class);
            ChatClient mockClient = mock(ChatClient.class);
            when(mockBuilder.build()).thenReturn(mockClient);

            guardrailService = new PromptGuardrailService(mockBuilder, new ObjectMapper());
            ReflectionTestUtils.setField(guardrailService, "enabled", true);
            ReflectionTestUtils.setField(guardrailService, "llmEnabled", true);
            ReflectionTestUtils.setField(guardrailService, "llmSchemaEnabled", false);
            ReflectionTestUtils.setField(guardrailService, "strictMode", false);
            ReflectionTestUtils.setField(guardrailService, "llmTimeoutMs", 100L);
            ReflectionTestUtils.setField(guardrailService, "llmCircuitBreakerEnabled", true);
            ReflectionTestUtils.setField(guardrailService, "llmCircuitBreakerFailureThreshold", 3);
            ReflectionTestUtils.setField(guardrailService, "llmCircuitBreakerOpenSeconds", 30L);
            ReflectionTestUtils.setField(guardrailService, "llmCircuitBreakerHalfOpenCalls", 1);
            guardrailService.init();
        }

        @Test
        @DisplayName("Fail-closed when circuit breaker is OPEN")
        void failClosedWhenOpen() {
            // Manually set circuit breaker to OPEN by injecting pre-tripped breaker
            SimpleCircuitBreaker breaker = new SimpleCircuitBreaker(1, Duration.ofSeconds(300), 1);
            breaker.recordFailure(new RuntimeException("trip"));
            assertEquals(SimpleCircuitBreaker.State.OPEN, breaker.getState());
            ReflectionTestUtils.setField(guardrailService, "llmCircuitBreaker", breaker);

            // A query that passes pattern+semantic layers should be blocked by circuit breaker
            PromptGuardrailService.GuardrailResult result =
                    guardrailService.analyze("What is the budget for Q3?");

            assertTrue(result.blocked(), "Should fail-closed when circuit is OPEN");
            assertTrue(result.reason().contains("circuit open"),
                    "Reason should mention circuit open, got: " + result.reason());
            assertEquals("UNKNOWN", result.classification());
            assertEquals(0.5, result.confidenceScore(), 0.01);
        }

        @Test
        @DisplayName("Passes queries when circuit breaker is CLOSED and LLM succeeds")
        void passesWhenClosedAndLlmSucceeds() {
            // Set up mock chain for successful LLM call
            ChatClient.Builder mockBuilder = mock(ChatClient.Builder.class);
            ChatClient mockChatClient = mock(ChatClient.class);
            ChatClient.ChatClientRequestSpec mockRequest = mock(ChatClient.ChatClientRequestSpec.class);
            ChatClient.CallResponseSpec mockCallResponse = mock(ChatClient.CallResponseSpec.class);

            when(mockBuilder.build()).thenReturn(mockChatClient);
            when(mockChatClient.prompt()).thenReturn(mockRequest);
            when(mockRequest.system(anyString())).thenReturn(mockRequest);
            when(mockRequest.user(anyString())).thenReturn(mockRequest);
            when(mockRequest.options(any())).thenReturn(mockRequest);
            when(mockRequest.call()).thenReturn(mockCallResponse);
            when(mockCallResponse.content()).thenReturn("{\"classification\":\"SAFE\"}");

            guardrailService = new PromptGuardrailService(mockBuilder, new ObjectMapper());
            ReflectionTestUtils.setField(guardrailService, "enabled", true);
            ReflectionTestUtils.setField(guardrailService, "llmEnabled", true);
            ReflectionTestUtils.setField(guardrailService, "llmSchemaEnabled", false);
            ReflectionTestUtils.setField(guardrailService, "strictMode", false);
            ReflectionTestUtils.setField(guardrailService, "llmTimeoutMs", 5000L);
            ReflectionTestUtils.setField(guardrailService, "llmCircuitBreakerEnabled", true);
            ReflectionTestUtils.setField(guardrailService, "llmCircuitBreakerFailureThreshold", 3);
            ReflectionTestUtils.setField(guardrailService, "llmCircuitBreakerOpenSeconds", 30L);
            ReflectionTestUtils.setField(guardrailService, "llmCircuitBreakerHalfOpenCalls", 1);
            guardrailService.init();

            PromptGuardrailService.GuardrailResult result =
                    guardrailService.analyze("What is the budget for Q3?");

            assertFalse(result.blocked(), "Should pass when circuit is CLOSED and LLM says SAFE");
        }

        @Test
        @DisplayName("LLM timeout records failure and fails closed")
        void timeoutRecordsFailureAndFailsClosed() {
            // ChatClient that blocks forever — will exceed 100ms timeout
            ChatClient.Builder mockBuilder = mock(ChatClient.Builder.class);
            ChatClient mockChatClient = mock(ChatClient.class);
            ChatClient.ChatClientRequestSpec mockRequest = mock(ChatClient.ChatClientRequestSpec.class);
            ChatClient.CallResponseSpec mockCallResponse = mock(ChatClient.CallResponseSpec.class);

            when(mockBuilder.build()).thenReturn(mockChatClient);
            when(mockChatClient.prompt()).thenReturn(mockRequest);
            when(mockRequest.system(anyString())).thenReturn(mockRequest);
            when(mockRequest.user(anyString())).thenReturn(mockRequest);
            when(mockRequest.options(any())).thenReturn(mockRequest);
            when(mockRequest.call()).thenReturn(mockCallResponse);
            when(mockCallResponse.content()).thenAnswer(invocation -> {
                Thread.sleep(5000); // block well past 100ms timeout
                return "{\"classification\":\"SAFE\"}";
            });

            guardrailService = new PromptGuardrailService(mockBuilder, new ObjectMapper());
            ReflectionTestUtils.setField(guardrailService, "enabled", true);
            ReflectionTestUtils.setField(guardrailService, "llmEnabled", true);
            ReflectionTestUtils.setField(guardrailService, "llmSchemaEnabled", false);
            ReflectionTestUtils.setField(guardrailService, "strictMode", false);
            ReflectionTestUtils.setField(guardrailService, "llmTimeoutMs", 100L);
            ReflectionTestUtils.setField(guardrailService, "llmCircuitBreakerEnabled", true);
            ReflectionTestUtils.setField(guardrailService, "llmCircuitBreakerFailureThreshold", 3);
            ReflectionTestUtils.setField(guardrailService, "llmCircuitBreakerOpenSeconds", 30L);
            ReflectionTestUtils.setField(guardrailService, "llmCircuitBreakerHalfOpenCalls", 1);
            guardrailService.init();

            PromptGuardrailService.GuardrailResult result =
                    guardrailService.analyze("What is the budget for Q3?");

            assertTrue(result.blocked(), "Should fail-closed on timeout");
            assertTrue(result.reason().contains("timed out"),
                    "Reason should mention timeout, got: " + result.reason());
        }

        @Test
        @DisplayName("LLM exception records failure and fails closed")
        void exceptionRecordsFailureAndFailsClosed() {
            ChatClient.Builder mockBuilder = mock(ChatClient.Builder.class);
            ChatClient mockChatClient = mock(ChatClient.class);
            ChatClient.ChatClientRequestSpec mockRequest = mock(ChatClient.ChatClientRequestSpec.class);

            when(mockBuilder.build()).thenReturn(mockChatClient);
            when(mockChatClient.prompt()).thenReturn(mockRequest);
            when(mockRequest.system(anyString())).thenReturn(mockRequest);
            when(mockRequest.user(anyString())).thenReturn(mockRequest);
            when(mockRequest.options(any())).thenReturn(mockRequest);
            when(mockRequest.call()).thenThrow(new RuntimeException("LLM connection refused"));

            guardrailService = new PromptGuardrailService(mockBuilder, new ObjectMapper());
            ReflectionTestUtils.setField(guardrailService, "enabled", true);
            ReflectionTestUtils.setField(guardrailService, "llmEnabled", true);
            ReflectionTestUtils.setField(guardrailService, "llmSchemaEnabled", false);
            ReflectionTestUtils.setField(guardrailService, "strictMode", false);
            ReflectionTestUtils.setField(guardrailService, "llmTimeoutMs", 5000L);
            ReflectionTestUtils.setField(guardrailService, "llmCircuitBreakerEnabled", true);
            ReflectionTestUtils.setField(guardrailService, "llmCircuitBreakerFailureThreshold", 3);
            ReflectionTestUtils.setField(guardrailService, "llmCircuitBreakerOpenSeconds", 30L);
            ReflectionTestUtils.setField(guardrailService, "llmCircuitBreakerHalfOpenCalls", 1);
            guardrailService.init();

            PromptGuardrailService.GuardrailResult result =
                    guardrailService.analyze("What is the budget for Q3?");

            assertTrue(result.blocked(), "Should fail-closed on exception");
            assertTrue(result.reason().contains("failed"),
                    "Reason should mention failure, got: " + result.reason());
        }

        @Test
        @DisplayName("Consecutive LLM failures trip circuit breaker to OPEN")
        void consecutiveFailuresTripCircuit() {
            ChatClient.Builder mockBuilder = mock(ChatClient.Builder.class);
            ChatClient mockChatClient = mock(ChatClient.class);
            ChatClient.ChatClientRequestSpec mockRequest = mock(ChatClient.ChatClientRequestSpec.class);

            when(mockBuilder.build()).thenReturn(mockChatClient);
            when(mockChatClient.prompt()).thenReturn(mockRequest);
            when(mockRequest.system(anyString())).thenReturn(mockRequest);
            when(mockRequest.user(anyString())).thenReturn(mockRequest);
            when(mockRequest.options(any())).thenReturn(mockRequest);
            when(mockRequest.call()).thenThrow(new RuntimeException("LLM unavailable"));

            guardrailService = new PromptGuardrailService(mockBuilder, new ObjectMapper());
            ReflectionTestUtils.setField(guardrailService, "enabled", true);
            ReflectionTestUtils.setField(guardrailService, "llmEnabled", true);
            ReflectionTestUtils.setField(guardrailService, "llmSchemaEnabled", false);
            ReflectionTestUtils.setField(guardrailService, "strictMode", false);
            ReflectionTestUtils.setField(guardrailService, "llmTimeoutMs", 5000L);
            ReflectionTestUtils.setField(guardrailService, "llmCircuitBreakerEnabled", true);
            ReflectionTestUtils.setField(guardrailService, "llmCircuitBreakerFailureThreshold", 3);
            ReflectionTestUtils.setField(guardrailService, "llmCircuitBreakerOpenSeconds", 300L);
            ReflectionTestUtils.setField(guardrailService, "llmCircuitBreakerHalfOpenCalls", 1);
            guardrailService.init();

            // 3 failures should trip the circuit
            for (int i = 0; i < 3; i++) {
                guardrailService.analyze("What is the budget for Q3?");
            }

            SimpleCircuitBreaker breaker = (SimpleCircuitBreaker)
                    ReflectionTestUtils.getField(guardrailService, "llmCircuitBreaker");
            assertNotNull(breaker);
            assertEquals(SimpleCircuitBreaker.State.OPEN, breaker.getState(),
                    "Circuit should be OPEN after 3 consecutive failures");

            // Next call should be blocked by circuit breaker, not LLM
            PromptGuardrailService.GuardrailResult result =
                    guardrailService.analyze("What is the budget for Q3?");
            assertTrue(result.blocked());
            assertTrue(result.reason().contains("circuit open"));
        }
    }
}
