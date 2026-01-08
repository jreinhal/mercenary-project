package com.jreinhal.mercenary.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class HypergraphService {

    private static final Logger log = LoggerFactory.getLogger(HypergraphService.class);
    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    // MINIMUM SCORE: 0.0 to 1.0
    // 0.7 is a good baseline. If the match isn't at least 70% similar, ignore it.
    private static final double SIMILARITY_THRESHOLD = 0.7;

    public HypergraphService(VectorStore vectorStore, ChatClient.Builder chatClientBuilder) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClientBuilder.build();
    }

    public String recall(String query, String dept) {
        // 1. Search with a filter
        List<Document> directHits = vectorStore.similaritySearch(
                SearchRequest.query(query).withTopK(3)
                        .withSimilarityThreshold(SIMILARITY_THRESHOLD) // <--- THE FIX
        );

        if (directHits.isEmpty()) {
            log.info("SENTINEL: No high-confidence intel found (Score < {}).", SIMILARITY_THRESHOLD);
            return ""; // Return empty to trigger General Protocol in Controller
        }

        StringBuilder context = new StringBuilder();
        for (Document doc : directHits) {
            context.append(doc.getContent()).append("\n---\n");
        }

        return context.toString();
    }

    public void evolveMemory(String newFact, String dept) {
        List<Document> existingMemories = vectorStore.similaritySearch(SearchRequest.query(newFact).withTopK(1));

        if (!existingMemories.isEmpty()) {
            Document oldDoc = existingMemories.get(0);
            log.info("SENTINEL: Found existing memory point. Attempting HGMEM Merge...");

            String mergedContent = chatClient.prompt()
                    .system("Merge these two pieces of intel into a single, denser fact. Eliminate redundancy.")
                    .user("OLD: " + oldDoc.getContent() + "\nNEW: " + newFact)
                    .call()
                    .content();

            Document mergedDoc = new Document(mergedContent, Map.of("dept", dept, "status", "EVOLVED"));
            vectorStore.add(List.of(mergedDoc));
        } else {
            vectorStore.add(List.of(new Document(newFact, Map.of("dept", dept, "status", "NEW"))));
        }
    }
}