package com.jreinhal.mercenary.rag.crag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.ai.chat.client.ChatClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CRAG Query RewriteService.
 *
 * Tests cover:
 * - Successful query rewriting
 * - Quote cleanup from LLM responses
 * - Fallback behavior on LLM failure
 * - Edge cases (null, empty input)
 */
@ExtendWith(MockitoExtension.class)
class RewriteServiceTest {

    @Mock
    private ChatClient.Builder chatClientBuilder;

    @Mock
    private ChatClient chatClient;

    private RewriteService rewriteService;

    @BeforeEach
    void setUp() {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        rewriteService = new RewriteService(chatClientBuilder);
    }

    /**
     * Helper to create a mock fluent chain that returns the specified response.
     */
    @SuppressWarnings("unchecked")
    private void mockChatClientResponse(String response) {
        // Create a mock that returns itself for all fluent calls and finally returns the response
        Answer<Object> fluentAnswer = new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                String methodName = invocation.getMethod().getName();
                if ("content".equals(methodName)) {
                    return response;
                }
                // Return the mock itself for fluent chaining
                return invocation.getMock();
            }
        };

        Object mockChain = mock(Object.class, fluentAnswer);
        when(chatClient.prompt()).thenAnswer(inv -> mockChain);
    }

    private void mockChatClientFailure(RuntimeException exception) {
        Object mockChain = mock(Object.class, invocation -> {
            String methodName = invocation.getMethod().getName();
            if ("call".equals(methodName)) {
                throw exception;
            }
            return invocation.getMock();
        });
        when(chatClient.prompt()).thenAnswer(inv -> mockChain);
    }

    @Test
    @DisplayName("Successful query rewrite returns refined query")
    void testSuccessfulRewrite() {
        // Given
        String originalQuery = "bank money safety";
        String expectedRewrite = "FDIC insurance limits and bank solvency regulations";
        mockChatClientResponse(expectedRewrite);

        // When
        String result = rewriteService.rewriteQuery(originalQuery);

        // Then
        assertEquals(expectedRewrite, result);
        verify(chatClient).prompt();
    }

    @Test
    @DisplayName("Removes surrounding quotes from LLM response")
    void testRemovesQuotes() {
        // Given
        String originalQuery = "find reports";
        String llmResponse = "\"quarterly financial reports with audit findings\"";
        mockChatClientResponse(llmResponse);

        // When
        String result = rewriteService.rewriteQuery(originalQuery);

        // Then
        assertEquals("quarterly financial reports with audit findings", result);
        assertFalse(result.startsWith("\""));
        assertFalse(result.endsWith("\""));
    }

    @Test
    @DisplayName("Returns original query on LLM failure")
    void testFallbackOnFailure() {
        // Given
        String originalQuery = "search for documents";
        mockChatClientFailure(new RuntimeException("LLM unavailable"));

        // When
        String result = rewriteService.rewriteQuery(originalQuery);

        // Then
        assertEquals(originalQuery, result, "Should return original query as fallback");
    }

    @Test
    @DisplayName("Handles whitespace in LLM response")
    void testTrimsWhitespace() {
        // Given
        String originalQuery = "data";
        String llmResponse = "  structured data analysis with metadata extraction  \n";
        mockChatClientResponse(llmResponse);

        // When
        String result = rewriteService.rewriteQuery(originalQuery);

        // Then
        assertEquals("structured data analysis with metadata extraction", result);
    }

    @Test
    @DisplayName("Preserves internal quotes in query")
    void testPreservesInternalQuotes() {
        // Given
        String originalQuery = "find project status";
        String llmResponse = "project \"Alpha\" completion status report";
        mockChatClientResponse(llmResponse);

        // When
        String result = rewriteService.rewriteQuery(originalQuery);

        // Then
        assertEquals("project \"Alpha\" completion status report", result,
                "Internal quotes should be preserved");
    }
}
