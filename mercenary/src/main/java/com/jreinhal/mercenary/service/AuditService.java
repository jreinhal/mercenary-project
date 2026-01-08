package com.jreinhal.mercenary.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class AuditService {

        private final ChatClient chatClient;
        private final VectorStore vectorStore;

        public AuditService(ChatClient.Builder chatClientBuilder, VectorStore vectorStore) {
                // We use the Builder to create a client bound to our default model (Ollama)
                this.chatClient = chatClientBuilder.build();
                this.vectorStore = vectorStore;
        }

        public String askQuestion(String question, String department) {
                // 1. Create the Department Security Filter
                var filter = new FilterExpressionBuilder()
                        .eq("dept", department)
                        .build();

                // 2. Perform Vector Search
                List<Document> similarDocuments = vectorStore.similaritySearch(
                        SearchRequest.query(question)
                                .withTopK(4)
                                .withFilterExpression(filter)
                );

                // 3. Fallback if empty
                if (similarDocuments.isEmpty()) {
                        return "CLASSIFIED // NO INTEL FOUND IN " + department + " SECTOR.";
                }

                // 4. Re-Rank / Sort Results (THE FIX)
                // The list returned above is "Immutable" (Read-Only).
                // We must copy it into a new ArrayList to make it "Mutable" (Editable) so we can sort it.
                List<Document> mutableDocs = new ArrayList<>(similarDocuments);

                List<String> keywords = List.of(question.toLowerCase().split("\\s+"));
                mutableDocs.sort((d1, d2) -> {
                        long matches1 = countMatches(d1.getContent().toLowerCase(), keywords);
                        long matches2 = countMatches(d2.getContent().toLowerCase(), keywords);
                        return Long.compare(matches2, matches1); // Descending order
                });

                // 5. Build Context String
                String context = mutableDocs.stream()
                        .map(doc -> {
                                String source = (String) doc.getMetadata().getOrDefault("source", "UNKNOWN");
                                return "SOURCE: [" + source + "]\nCONTENT: " + doc.getContent();
                        })
                        .collect(Collectors.joining("\n\n"));

                // 6. Send to AI (Llama 3)
                return chatClient.prompt()
                        .system(sp -> sp.text(
                                "You are a Defense Intelligence Analyst. " +
                                        "Answer based ONLY on the provided context. " +
                                        "If the answer is not in the context, say 'DATA NOT AVAILABLE'."
                        ))
                        .user(u -> u.text("CONTEXT:\n" + context + "\n\nQUESTION: " + question))
                        .call()
                        .content();
        }

        // Helper to count keyword matches for re-ranking
        private long countMatches(String text, List<String> keywords) {
                return keywords.stream().filter(text::contains).count();
        }
}