package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.model.ChatLog;
import com.jreinhal.mercenary.repository.ChatLogRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
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
        private final ChatLogRepository chatLogRepository; // <--- The Database Connection

        public AuditService(VectorStore vectorStore, ChatClient.Builder builder, ChatLogRepository chatLogRepository) {
                this.vectorStore = vectorStore;
                this.chatClient = builder.build();
                this.chatLogRepository = chatLogRepository;
        }

        public String askQuestion(String question, String userDepartment) {
                // 1. REPLAY HISTORY from MongoDB (The "Immortal" Memory)
                List<ChatLog> dbLogs = chatLogRepository.findByDepartmentOrderByTimestampAsc(userDepartment);

                // Convert DB objects back to AI Message objects
                List<Message> history = new ArrayList<>();
                // Only keep the last 10 messages to save context/money
                int start = Math.max(0, dbLogs.size() - 10);
                for (int i = start; i < dbLogs.size(); i++) {
                        ChatLog log = dbLogs.get(i);
                        if ("user".equals(log.getRole())) {
                                history.add(new UserMessage(log.getContent()));
                        } else {
                                history.add(new AssistantMessage(log.getContent()));
                        }
                }

                // 2. Build Security Filter
                var filterBuilder = new FilterExpressionBuilder();
                var securityFilter = filterBuilder.eq("dept", userDepartment).build();

                // 3. Retrieve BROAD Context (Hybrid Step A)
                List<Document> broadResults = vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query(question)
                                .topK(10)
                                .filterExpression(securityFilter)
                                .build()
                );

                if (broadResults.isEmpty()) {
                        return "No relevant documents found in your department.";
                }

                // 4. Re-Rank by Keywords (Hybrid Step B)
                List<Document> topDocs = rankByKeywords(broadResults, question, 3);

                String context = topDocs.stream()
                        .map(doc -> {
                                String sourceName = (String) doc.getMetadata().getOrDefault("source", "Unknown File");
                                return "SOURCE: [" + sourceName + "]\nCONTENT: " + doc.getText();
                        })
                        .collect(Collectors.joining("\n\n"));

                // 5. Construct the Message Chain
                List<Message> promptMessages = new ArrayList<>();
                promptMessages.add(new SystemMessage("You are a corporate auditor. You value accuracy and traceability."));
                promptMessages.addAll(history); // Inject persistent history

                String promptTemplate = """
                Context information is below:
                ---------------------
                %s
                ---------------------
                
                Answer the question: %s
                
                STRICT RULES:
                1. Answer strictly based on the provided context.
                2. You MUST cite the source file for every fact you mention. 
                   Format: "Fact... (Source: filename)".
                """;
                String augmentedUserPrompt = String.format(promptTemplate, context, question);
                promptMessages.add(new UserMessage(augmentedUserPrompt));

                // 6. Call the AI
                String aiResponse = chatClient.prompt()
                        .messages(promptMessages)
                        .call()
                        .content();

                // 7. SAVE TO DATABASE (Persistence)
                // We save the inputs so they exist next time we restart the server.
                chatLogRepository.save(new ChatLog(userDepartment, "user", question));
                chatLogRepository.save(new ChatLog(userDepartment, "assistant", aiResponse));

                return aiResponse;
        }

        private List<Document> rankByKeywords(List<Document> docs, String query, int topN) {
                Set<String> keywords = Arrays.stream(query.toLowerCase().split("\\W+"))
                        .filter(word -> word.length() > 3)
                        .collect(Collectors.toSet());

                List<Document> mutableDocs = new ArrayList<>(docs);
                mutableDocs.sort((d1, d2) -> {
                        long matches1 = countMatches(d1.getText().toLowerCase(), keywords);
                        long matches2 = countMatches(d2.getText().toLowerCase(), keywords);
                        return Long.compare(matches2, matches1);
                });

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