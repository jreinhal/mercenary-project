package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.Department;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class SecureIngestionService {

    private static final Logger log = LoggerFactory.getLogger(SecureIngestionService.class);

    private final VectorStore vectorStore;
    private final MemoryEvolutionService memoryEvolutionService;

    // PII PATTERNS
    private static final Pattern SSN_PATTERN = Pattern.compile("\\d{3}-\\d{2}-\\d{4}");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,6}");

    public SecureIngestionService(VectorStore vectorStore, MemoryEvolutionService memoryEvolutionService) {
        this.vectorStore = vectorStore;
        this.memoryEvolutionService = memoryEvolutionService;
    }

    public void ingest(MultipartFile file, Department dept) {
        try {
            String filename = file.getOriginalFilename();
            log.info("Initiating RAGPart Defense Protocol for: {} [Sector: {}]", filename, dept);

            InputStreamResource resource = new InputStreamResource(file.getInputStream());
            List<Document> rawDocuments;

            // 1. SELECT CORRECT READER (PDF vs TEXT)
            if (filename.toLowerCase().endsWith(".pdf")) {
                log.info(">> DETECTED PDF: Engaging Optical Character Recognition / PDF Stream...");
                // Requires 'spring-ai-pdf-document-reader' dependency.
                // If this fails to compile, we fall back to Tika or simple text extraction.
                PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(resource);
                rawDocuments = pdfReader.get();
            } else {
                log.info(">> DETECTED TEXT: Engaging Standard Text Stream...");
                TextReader textReader = new TextReader(resource);
                rawDocuments = textReader.get();
            }

            // 2. PRE-SPLIT SANITIZATION (Fixes the NPE Crash)
            // We create new, clean documents to ensure no null metadata triggers the
            // Splitter bug.
            List<Document> cleanDocs = new ArrayList<>();
            for (Document doc : rawDocuments) {
                HashMap<String, Object> cleanMeta = new HashMap<>();
                cleanMeta.put("source", filename);
                cleanMeta.put("department", dept.name());
                cleanDocs.add(new Document(doc.getContent(), cleanMeta));
            }

            // 3. SPLIT & REDACT
            TokenTextSplitter splitter = new TokenTextSplitter();
            List<Document> splitDocuments = splitter.apply(cleanDocs);

            List<Document> finalDocuments = new ArrayList<>();
            for (Document doc : splitDocuments) {
                // PII REDACTION
                String cleanContent = redactSensitiveInfo(doc.getContent());
                Document redactedDoc = new Document(cleanContent, doc.getMetadata());

                // MEMORY EVOLUTION (Hypergraph)
                Document evolvedDoc = memoryEvolutionService.evolve(redactedDoc);

                finalDocuments.add(evolvedDoc);
            }

            vectorStore.add(finalDocuments);
            log.info("Securely ingested {} memory points.", finalDocuments.size());

        } catch (IOException e) {
            throw new RuntimeException("Secure Ingestion Failed: " + e.getMessage());
        }
    }

    private String redactSensitiveInfo(String content) {
        String safe = SSN_PATTERN.matcher(content).replaceAll("[REDACTED-SSN]");
        safe = EMAIL_PATTERN.matcher(safe).replaceAll("[REDACTED-EMAIL]");
        return safe;
    }
}