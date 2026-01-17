package com.jreinhal.mercenary.rag.crag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * Service for Corrective RAG (CRAG) query rewriting.
 * Uses an LLM to refine queries that fail to return high-confidence results.
 */
@Service
public class RewriteService {

    private static final Logger log = LoggerFactory.getLogger(RewriteService.class);
    private final ChatClient chatClient;

    private static final String REWRITE_SYSTEM_PROMPT = """
            You are a query refinement expert for a semantic search engine.
            The user's previous query yielded poor results.

            Your task:
            1. Analyze the query for ambiguity or lack of specificity.
            2. Rewrite the query to be more precise, using keywords likely to appear in a knowledge base.
            3. Do not change the underlying intent.
            4. Return ONLY the rewritten query. No explanations.

            Example:
            Input: "bank money safety"
            Output: "FDIC insurance limits and bank solvency regulations"
            """;

    public RewriteService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * Rewrite a query to improve retrieval performance.
     *
     * @param originalQuery The original user query
     * @return The rewritten query
     */
    public String rewriteQuery(String originalQuery) {
        long startTime = System.currentTimeMillis();
        try {
            String rewritten = chatClient.prompt()
                    .system(REWRITE_SYSTEM_PROMPT)
                    .user(originalQuery)
                    .call()
                    .content()
                    .trim();

            // Cleanup: sometimes models wrap in quotes despite instructions
            if (rewritten.startsWith("\"") && rewritten.endsWith("\"")) {
                rewritten = rewritten.substring(1, rewritten.length() - 1);
            }

            log.info("CRAG: Rewrote query '{}' -> '{}' ({}ms)", originalQuery, rewritten,
                    System.currentTimeMillis() - startTime);
            return rewritten;

        } catch (Exception e) {
            log.warn("CRAG: Query rewrite failed: {}", e.getMessage());
            return originalQuery; // Fallback to original
        }
    }
}
