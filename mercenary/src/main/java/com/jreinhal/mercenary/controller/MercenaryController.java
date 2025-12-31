package com.jreinhal.mercenary.controller;

import com.jreinhal.mercenary.service.AuditService;
import com.jreinhal.mercenary.service.IngestionService;
import org.apache.tika.Tika; // NEW IMPORT
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class MercenaryController {

    private final IngestionService ingestion;
    private final AuditService audit;

    // NEW: The Tika parser instance
    private final Tika tika = new Tika();

    public MercenaryController(IngestionService ingestion, AuditService audit) {
        this.ingestion = ingestion;
        this.audit = audit;
    }

    // --- 1. JSON Ingest ---
    public record IngestRequest(String text, String dept) {}

    @PostMapping("/ingest")
    public void ingest(@RequestBody IngestRequest request) {
        // We pass "Manual Input" as the source for raw JSON requests
        ingestion.ingest(request.text(), request.dept(), "Manual Input");
    }

    // --- 2. NEW: File Ingest (Now with Tika Power) ---
    @PostMapping(value = "/ingest/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void ingestFile(@RequestPart("file") MultipartFile file,
                           @RequestParam("dept") String dept) throws Exception {

        // 1. USE TIKA: This automatically detects PDF/Docx/etc and extracts text
        String content = tika.parseToString(file.getInputStream());

        // 2. Pass the content AND the filename to the service
        ingestion.ingest(content, dept, file.getOriginalFilename());
    }

    // --- 3. Ask ---
    @GetMapping("/ask")
    public String ask(@RequestParam String q, @RequestParam String dept) {
        return audit.askQuestion(q, dept);
    }
}