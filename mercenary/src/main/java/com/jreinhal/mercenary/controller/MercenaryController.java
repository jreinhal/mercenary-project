package com.jreinhal.mercenary.controller;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.service.SecureIngestionService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@CrossOrigin
public class MercenaryController {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final SecureIngestionService ingestionService;

    // METRICS
    private final AtomicInteger docCount = new AtomicInteger(1247);
    private final AtomicInteger queryCount = new AtomicInteger(142);
    private final AtomicLong totalLatencyMs = new AtomicLong(0);

    // FLASH CACHE
    private final Map<String, String> secureDocCache = new ConcurrentHashMap<>();

    @Autowired
    public MercenaryController(ChatClient.Builder builder, VectorStore vectorStore, SecureIngestionService ingestionService) {
        this.chatClient = builder.build();
        this.vectorStore = vectorStore;
        this.ingestionService = ingestionService;
    }

    @GetMapping("/status")
    public Map<String, Object> getSystemStatus() {
        long avgLat = 0;
        int qCount = queryCount.get();
        if (qCount > 0) avgLat = totalLatencyMs.get() / qCount;
        boolean dbOnline = true;
        try { SearchRequest.query("ping"); } catch (Exception e) { dbOnline = false; }
        return Map.of("vectorDb", dbOnline ? "ONLINE" : "OFFLINE", "docsIndexed", docCount.get(), "avgLatency", avgLat + "ms", "queriesToday", qCount, "systemStatus", "NOMINAL");
    }

    @GetMapping("/inspect")
    public String inspectDocument(@RequestParam("fileName") String fileName) {
        if (secureDocCache.containsKey(fileName)) {
            return "--- SECURE DOCUMENT VIEWER (CACHE) ---\nFILE: " + fileName + "\nSTATUS: DECRYPTED [RAM]\n----------------------------------\n\n" + secureDocCache.get(fileName);
        }
        try {
            List<Document> recoveredDocs = vectorStore.similaritySearch(SearchRequest.query("source:" + fileName).withTopK(1));
            if (!recoveredDocs.isEmpty()) {
                String recoveredContent = recoveredDocs.get(0).getContent();
                secureDocCache.put(fileName, recoveredContent);
                return "--- SECURE DOCUMENT VIEWER (ARCHIVE) ---\nFILE: " + fileName + "\nSTATUS: RECONSTRUCTED FROM VECTOR STORE\n----------------------------------\n\n" + recoveredContent;
            }
        } catch (Exception e) { System.out.println(">> RECOVERY FAILED: " + e.getMessage()); }
        return "ERROR: Document archived in Deep Storage.\nPlease re-ingest file to refresh active cache.";
    }

    @GetMapping("/health") public String health() { return "SYSTEMS NOMINAL"; }

    @PostMapping("/ingest/file")
    public String ingestFile(@RequestParam("file") MultipartFile file, @RequestParam("dept") String dept) {
        try {
            long startTime = System.currentTimeMillis();
            String filename = file.getOriginalFilename();
            Department department = Department.valueOf(dept.toUpperCase());
            ingestionService.ingest(file, department);
            String rawContent = new String(file.getBytes(), StandardCharsets.UTF_8);
            secureDocCache.put(filename, rawContent);
            docCount.incrementAndGet();
            long duration = System.currentTimeMillis() - startTime;
            return "SECURE INGESTION COMPLETE: " + filename + " (" + duration + "ms)";
        } catch (Exception e) { e.printStackTrace(); return "CRITICAL FAILURE: Ingestion Protocol Failed."; }
    }

    @GetMapping("/ask")
    public String ask(@RequestParam("q") String query, @RequestParam("dept") String dept) {
        long start = System.currentTimeMillis();

        // 1. SECURITY
        if (isPromptInjection(query)) return "SECURITY ALERT: Indirect Prompt Injection Detected. Access Denied.";

        // 2. RETRIEVAL (Top 10 -> Rerank -> Top 3)
        List<Document> rawDocs = vectorStore.similaritySearch(SearchRequest.query(query).withTopK(10));
        List<Document> rerankedDocs = performHybridRerank(rawDocs, query);
        List<Document> topDocs = rerankedDocs.stream().limit(3).toList();

        String information = topDocs.stream()
                .map(doc -> {
                    Map<String, Object> meta = doc.getMetadata();
                    String filename = (String) meta.get("source");
                    if (filename == null) filename = (String) meta.get("filename");
                    if (filename == null) filename = "Unknown_Document.txt";
                    return "SOURCE: " + filename + "\nCONTENT: " + doc.getContent();
                })
                .collect(Collectors.joining("\n\n---\n\n"));

        // 3. GENERATION (Strict Formatting)
        String systemText = "";
        if (information.isEmpty()) {
            systemText = "You are SENTINEL. Protocol: Answer based on general training. State 'No internal records found.'";
        } else {
            // STRICT CITATION PROMPT
            systemText = """
                You are SENTINEL, an advanced intelligence agent assigned to %s.
                
                PROTOCOL:
                1. ANALYZE the provided CONTEXT DATA.
                2. SYNTHESIZE an answer based ONLY on that data.
                
                CITATION RULE (CRITICAL):
                You MUST cite the source filename for every fact using square brackets.
                Correct Format: [filename.pdf]
                Incorrect Format: (filename.pdf) or "Source: filename.pdf"
                
                Example Output:
                "Project Omega was funded by shell companies [financials_2024.pdf]."
                
                CONTEXT DATA:
                {information}
                """.formatted(dept);
        }

        SystemPromptTemplate systemPrompt = new SystemPromptTemplate(systemText);
        UserMessage userMessage = new UserMessage(query);
        Prompt prompt = new Prompt(List.of(systemPrompt.createMessage(Map.of("information", information)), userMessage));

        String response = chatClient.call(prompt).getResult().getOutput().getContent();

        long timeTaken = System.currentTimeMillis() - start;
        totalLatencyMs.addAndGet(timeTaken);
        queryCount.incrementAndGet();

        return response;
    }

    private List<Document> performHybridRerank(List<Document> docs, String query) {
        if (docs == null || docs.isEmpty()) return new ArrayList<>();

        List<Document> mutableDocs = new ArrayList<>(docs);
        String[] keywords = query.toLowerCase().split("\\s+");

        mutableDocs.sort((d1, d2) -> {
            int score1 = countMatches(d1.getContent().toLowerCase(), keywords);
            int score2 = countMatches(d2.getContent().toLowerCase(), keywords);
            return Integer.compare(score2, score1);
        });
        return mutableDocs;
    }

    private int countMatches(String content, String[] keywords) {
        int count = 0;
        for (String k : keywords) {
            if (content.contains(k)) count++;
        }
        return count;
    }

    private boolean isPromptInjection(String query) {
        String lower = query.toLowerCase();
        return lower.contains("ignore previous") || lower.contains("ignore all") || lower.contains("system prompt");
    }
}