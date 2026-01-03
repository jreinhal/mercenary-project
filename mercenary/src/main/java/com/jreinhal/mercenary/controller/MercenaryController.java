package com.jreinhal.mercenary.controller;

import com.jreinhal.mercenary.service.AuditService;
import com.jreinhal.mercenary.service.IngestionService;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class MercenaryController {

    // 1. Declare the fields as final
    private final IngestionService ingestionService;
    private final AuditService auditService;
    private final VectorStore vectorStore;

    // 2. THE CONSTRUCTOR (This fixes your error)
    // This connects the "final" fields above to the actual Spring beans.
    public MercenaryController(IngestionService ingestionService, AuditService auditService, VectorStore vectorStore) {
        this.ingestionService = ingestionService;
        this.auditService = auditService;
        this.vectorStore = vectorStore;
    }

    @PostMapping(value = "/ingest/file", consumes = "multipart/form-data")
    public String ingestFile(@RequestParam("file") MultipartFile file, @RequestParam("dept") String dept) {
        try {
            ingestionService.ingestFile(file.getInputStream(), dept, file.getOriginalFilename());
            return "Ingested " + file.getOriginalFilename();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @GetMapping("/ask")
    public String ask(@RequestParam String q, @RequestParam String dept) {
        return auditService.askQuestion(q, dept);
    }

    @GetMapping("/inspect")
    public String inspectDocument(@RequestParam String fileName) {
        var filter = new FilterExpressionBuilder()
                .eq("source", fileName)
                .build();

        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(fileName) // We search for the filename to satisfy OpenAI
                        .topK(100)
                        .filterExpression(filter)
                        .build()
        );

        if (docs.isEmpty()) {
            return "CLASSIFIED // NO DATA FOUND FOR: " + fileName;
        }

        return docs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n--- [SECTION BREAK] ---\n\n"));
    }
}