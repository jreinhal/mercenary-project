package com.jreinhal.mercenary.rag.hifirag;

import com.jreinhal.mercenary.rag.hifirag.HiFiRagService.ScoredDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/**
 * Cross-Encoder Reranker using local LLM (Ollama).
 *
 * Unlike bi-encoders that encode query and documents separately,
 * cross-encoders score (query, document) pairs together, allowing
 * for richer interaction modeling and better relevance assessment.
 *
 * This implementation uses the local Ollama LLM for air-gap compatibility.
 * The LLM acts as a "semantic judge" scoring document relevance.
 */
@Component
public class CrossEncoderReranker {

    private static final Logger log = LoggerFactory.getLogger(CrossEncoderReranker.class);

    // Thread pool configuration
    private static final int THREAD_POOL_SIZE = 4;

    private final ChatClient chatClient;
    private final ExecutorService executor;

    @Value("${sentinel.hifirag.reranker.batch-size:5}")
    private int batchSize;

    @Value("${sentinel.hifirag.reranker.timeout-seconds:30}")
    private int timeoutSeconds;

    @Value("${sentinel.hifirag.reranker.use-llm:true}")
    private boolean useLlm;

    public CrossEncoderReranker(ChatClient.Builder builder) {
        this.chatClient = builder.build();
        this.executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    }

    @PostConstruct
    public void init() {
        log.info("Cross-Encoder Reranker initialized (useLlm={})", useLlm);
    }

    /**
     * Rerank documents by semantic relevance to query.
     *
     * @param query The user's query
     * @param documents Documents to rerank
     * @return Scored documents sorted by relevance (highest first)
     */
    public List<ScoredDocument> rerank(String query, List<Document> documents) {
        if (documents.isEmpty()) {
            return List.of();
        }

        log.debug("Cross-encoder reranking {} documents", documents.size());
        long startTime = System.currentTimeMillis();

        List<ScoredDocument> scored;
        if (useLlm) {
            scored = rerankWithLlm(query, documents);
        } else {
            // Fallback to keyword-based scoring
            scored = rerankWithKeywords(query, documents);
        }

        log.debug("Reranking completed in {}ms", System.currentTimeMillis() - startTime);

        return scored.stream()
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .collect(Collectors.toList());
    }

    /**
     * LLM-based reranking using Ollama as semantic judge.
     */
    private List<ScoredDocument> rerankWithLlm(String query, List<Document> documents) {
        List<ScoredDocument> results = new ArrayList<>();

        // Process in batches to avoid overwhelming the LLM
        for (int i = 0; i < documents.size(); i += batchSize) {
            int end = Math.min(i + batchSize, documents.size());
            List<Document> batch = documents.subList(i, end);

            // Score batch in parallel
            List<Future<ScoredDocument>> futures = new ArrayList<>();
            for (Document doc : batch) {
                futures.add(executor.submit(() -> scoreDocument(query, doc)));
            }

            // Collect results with timeout
            for (Future<ScoredDocument> future : futures) {
                try {
                    ScoredDocument sd = future.get(timeoutSeconds, TimeUnit.SECONDS);
                    if (sd != null) {
                        results.add(sd);
                    }
                } catch (TimeoutException e) {
                    log.warn("Cross-encoder scoring timed out");
                } catch (Exception e) {
                    log.warn("Cross-encoder scoring failed: {}", e.getMessage());
                }
            }
        }

        return results;
    }

    /**
     * Score a single document's relevance to the query using LLM.
     */
    private ScoredDocument scoreDocument(String query, Document doc) {
        try {
            String content = doc.getContent();
            // Truncate long documents
            if (content.length() > 1000) {
                content = content.substring(0, 1000) + "...";
            }

            String prompt = String.format("""
                Rate the relevance of this document to the query on a scale of 0.0 to 1.0.

                QUERY: %s

                DOCUMENT:
                %s

                Respond with ONLY a number between 0.0 and 1.0, nothing else.
                0.0 = completely irrelevant
                0.5 = somewhat relevant
                1.0 = highly relevant

                Score:""", query, content);

            @SuppressWarnings("deprecation")
            String response = chatClient.call(new Prompt(new UserMessage(prompt)))
                    .getResult().getOutput().getContent();

            // Extract score from response
            double score = parseScore(response);
            return new ScoredDocument(doc, score);

        } catch (Exception e) {
            log.debug("LLM scoring failed for document, using fallback: {}", e.getMessage());
            // Fallback: use keyword-based score
            return scoreWithKeywords(query, doc);
        }
    }

    /**
     * Parse score from LLM response.
     */
    private double parseScore(String response) {
        if (response == null || response.isEmpty()) {
            return 0.5;
        }

        // Extract first decimal number from response
        Pattern pattern = Pattern.compile("(0\\.\\d+|1\\.0|0|1)");
        Matcher matcher = pattern.matcher(response.trim());

        if (matcher.find()) {
            try {
                double score = Double.parseDouble(matcher.group(1));
                return Math.max(0.0, Math.min(1.0, score));
            } catch (NumberFormatException e) {
                // Fallback
            }
        }

        // Default to medium relevance if parsing fails
        return 0.5;
    }

    /**
     * Fallback keyword-based reranking (no LLM required).
     */
    private List<ScoredDocument> rerankWithKeywords(String query, List<Document> documents) {
        return documents.stream()
                .map(doc -> scoreWithKeywords(query, doc))
                .collect(Collectors.toList());
    }

    /**
     * Score document based on keyword overlap.
     */
    private ScoredDocument scoreWithKeywords(String query, Document doc) {
        String lowerQuery = query.toLowerCase();
        String lowerContent = doc.getContent().toLowerCase();

        // Extract meaningful terms (filter stop words)
        Set<String> stopWords = Set.of("the", "a", "an", "is", "are", "was", "were",
                "what", "where", "when", "who", "how", "why", "which",
                "and", "or", "but", "in", "on", "at", "to", "for", "of", "with");

        String[] queryTerms = lowerQuery.split("\\W+");
        int totalTerms = 0;
        int matchedTerms = 0;

        for (String term : queryTerms) {
            if (term.length() > 2 && !stopWords.contains(term)) {
                totalTerms++;
                if (lowerContent.contains(term)) {
                    matchedTerms++;
                }
            }
        }

        double score = totalTerms > 0 ? (double) matchedTerms / totalTerms : 0.0;

        // Boost if query terms appear in document source/title
        Object source = doc.getMetadata().get("source");
        if (source != null) {
            String lowerSource = source.toString().toLowerCase();
            for (String term : queryTerms) {
                if (term.length() > 2 && lowerSource.contains(term)) {
                    score = Math.min(1.0, score + 0.1);
                }
            }
        }

        return new ScoredDocument(doc, score);
    }
}
