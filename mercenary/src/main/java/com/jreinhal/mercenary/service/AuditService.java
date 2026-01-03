package com.jreinhal.mercenary.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class AuditService {

        private final VectorStore vectorStore;
        private final ChatClient chatClient;

        public AuditService(VectorStore vectorStore, ChatClient.Builder builder) {
                this.vectorStore = vectorStore;
                this.chatClient = builder.build();
        }

        public String askQuestion(String question, String userDepartment) {
                // 1. Build Security Filter
                var filterBuilder = new FilterExpressionBuilder();
                var securityFilter = filterBuilder.eq("dept", userDepartment).build();

                // 2. Retrieve BROAD Context (Hybrid Step A: Semantic Search)
                // STRATEGY: We fetch 10 docs (instead of 3) to cast a wider net.
                List<Document> broadResults = vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query(question)
                                .topK(10) // Fetching more candidates
                                .filterExpression(securityFilter)
                                .build()
                );

                if (broadResults.isEmpty()) {
                        return "No relevant documents found in your department.";
                }

                // 3. Re-Rank by Keywords (Hybrid Step B: Exact Match Logic)
                // STRATEGY: We filter the Top 10 down to the Top 3 based on exact keyword hits.
                List<Document> topDocs = rankByKeywords(broadResults, question, 3);

                // 4. Format Context with Citations
                String context = topDocs.stream()
                        .map(doc -> {
                                String sourceName = (String) doc.getMetadata().getOrDefault("source", "Unknown File");
                                return "SOURCE: [" + sourceName + "]\nCONTENT: " + doc.getText();
                        })
                        .collect(Collectors.joining("\n\n"));

                // Debugging logs to verify Hybrid Search is working
                System.out.println("------------- HYBRID RAG CONTEXT -------------");
                System.out.println(context);
                System.out.println("----------------------------------------------");

                // 5. Generate Answer
                String promptTemplate = """
                Context information is below:
                ---------------------
                {context}
                ---------------------
                
                Answer the question: {question}
                
                STRICT RULES:
                1. Answer strictly based on the provided context.
                2. You MUST cite the source file for every fact you mention. 
                   Format: "Fact... (Source: filename)".
                """;

                return chatClient.prompt()
                        .system("You are a corporate auditor. You value accuracy and traceability.")
                        .user(u -> u.text(promptTemplate)
                                .param("context", context)
                                .param("question", question))
                        .call()
                        .content();
        }

        // --- THE HYBRID ENGINE ---
        private List<Document> rankByKeywords(List<Document> docs, String query, int topN) {
                // 1. Extract keywords from query (Split by space, ignore punctuation)
                // We ignore words with 3 letters or less (like "the", "is", "of") to focus on "Meat"
                Set<String> keywords = Arrays.stream(query.toLowerCase().split("\\W+"))
                        .filter(word -> word.length() > 3)
                        .collect(Collectors.toSet());

                // 2. Create a mutable list so we can sort it
                List<Document> mutableDocs = new ArrayList<>(docs);

                // 3. Sort: Documents with more keyword hits go to the TOP
                mutableDocs.sort((d1, d2) -> {
                        long matches1 = countMatches(d1.getText().toLowerCase(), keywords);
                        long matches2 = countMatches(d2.getText().toLowerCase(), keywords);
                        return Long.compare(matches2, matches1); // Descending order (High score first)
                });

                // 4. Return only the Top N (Top 3)
                return mutableDocs.stream().limit(topN).toList();
        }

        private long countMatches(String text, Set<String> keywords) {
                long count = 0;
                for (String word : keywords) {
                        if (text.contains(word)) {
                                count++;
                        }
                }
                return count;
        }
}