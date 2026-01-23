package com.jreinhal.mercenary.rag.crag;

import com.jreinhal.mercenary.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class RewriteService {
    private static final Logger log = LoggerFactory.getLogger(RewriteService.class);
    private final ChatClient chatClient;
    private static final String REWRITE_SYSTEM_PROMPT = "You are a query refinement expert for a semantic search engine.\nThe user's previous query yielded poor results.\n\nYour task:\n1. Analyze the query for ambiguity or lack of specificity.\n2. Rewrite the query to be more precise, using keywords likely to appear in a knowledge base.\n3. Do not change the underlying intent.\n4. Return ONLY the rewritten query. No explanations.\n\nExample:\nInput: \"bank money safety\"\nOutput: \"FDIC insurance limits and bank solvency regulations\"\n";

    public RewriteService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public String rewriteQuery(String originalQuery) {
        long startTime = System.currentTimeMillis();
        try {
            String rewritten = this.chatClient.prompt().system(REWRITE_SYSTEM_PROMPT).user(originalQuery).call().content().trim();
            if (rewritten.startsWith("\"") && rewritten.endsWith("\"")) {
                rewritten = rewritten.substring(1, rewritten.length() - 1);
            }
            log.info("CRAG: Rewrote query {} -> {} ({}ms)", LogSanitizer.querySummary(originalQuery), LogSanitizer.querySummary(rewritten), System.currentTimeMillis() - startTime);
            return rewritten;
        }
        catch (Exception e) {
            log.warn("CRAG: Query rewrite failed: {}", e.getMessage());
            return originalQuery;
        }
    }
}
