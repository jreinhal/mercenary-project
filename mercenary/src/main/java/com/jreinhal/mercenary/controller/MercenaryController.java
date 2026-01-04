package com.jreinhal.mercenary.controller;

import com.jreinhal.mercenary.model.Department;
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

    private final IngestionService ingestionService;
    private final AuditService auditService;
    private final VectorStore vectorStore;

    public MercenaryController(IngestionService ingestionService, AuditService auditService, VectorStore vectorStore) {
        this.ingestionService = ingestionService;
        this.auditService = auditService;
        this.vectorStore = vectorStore;
    }

    @PostMapping(value = "/ingest/file", consumes = "multipart/form-data")
    // SECURITY UPGRADE: dept is now an Enum. Invalid values are rejected 400 Bad Request.
    public String ingestFile(@RequestParam("file") MultipartFile file, @RequestParam("dept") Department dept) {
        try {
            // We safely convert the validated Enum to a String for the service layer
            ingestionService.ingestFile(file.getInputStream(), dept.name(), file.getOriginalFilename());
            return "Ingested " + file.getOriginalFilename() + " into " + dept.name() + " Vault.";
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @GetMapping("/ask")
    // SECURITY UPGRADE: dept is now an Enum.
    public String ask(@RequestParam String q, @RequestParam Department dept) {
        return auditService.askQuestion(q, dept.name());
    }

    @GetMapping("/inspect")
    public String inspectDocument(@RequestParam String fileName) {
        var filter = new FilterExpressionBuilder()
                .eq("source", fileName)
                .build();

        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(fileName)
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