package com.jreinhal.mercenary.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.List;
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
                // CRITICAL FIX: We now use "dept" to match the IngestionService tag
                var filterBuilder = new FilterExpressionBuilder();
                var securityFilter = filterBuilder.eq("dept", userDepartment).build();

                // 2. Retrieve Context
                List<Document> similarDocs = vectorStore.similaritySearch(
                                SearchRequest.builder()
                                                .query(question)
                                                .topK(3)
                                                .filterExpression(securityFilter)
                                                .build());

                // Fail fast if the vector store returns nothing
                if (similarDocs.isEmpty()) {
                        return "No relevant documents found in your department.";
                }

                // Combine the documents into a single string
                String context = similarDocs.stream()
                                .map(Document::getText)
                                .collect(Collectors.joining("\n"));

                // Debugging (visible in your IntelliJ console)
                System.out.println("------------- RAG CONTEXT FOUND -------------");
                System.out.println(context);
                System.out.println("---------------------------------------------");

                // 3. Generate Answer
                // CRITICAL FIX: We define a template with {context} so the AI knows where to
                // look.
                String promptTemplate = """
                                Context information is below:
                                ---------------------
                                {context}
                                ---------------------
                                Given the context and no prior knowledge, answer the question: {question}
                                """;

                return chatClient.prompt()
                                .system("You are a corporate auditor. Answer strictly based on the provided context.")
                                .user(u -> u.text(promptTemplate)
                                                .param("context", context)
                                                .param("question", question))
                                .call()
                                .content();
        }
}