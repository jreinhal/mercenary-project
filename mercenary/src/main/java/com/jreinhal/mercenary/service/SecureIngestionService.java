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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class SecureIngestionService {

    private static final Logger log = LoggerFactory.getLogger(SecureIngestionService.class);

    private final VectorStore vectorStore;
    private final PiiRedactionService piiRedactionService;

    public SecureIngestionService(VectorStore vectorStore, PiiRedactionService piiRedactionService) {
        this.vectorStore = vectorStore;
        this.piiRedactionService = piiRedactionService;
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
                PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(resource);
                rawDocuments = pdfReader.get();
            } else {
                log.info(">> DETECTED TEXT: Engaging Standard Text Stream...");
                TextReader textReader = new TextReader(resource);
                rawDocuments = textReader.get();
            }

            // 2. PRE-SPLIT SANITIZATION
            // We create new, clean documents to ensure no null metadata triggers the Splitter bug.
            List<Document> cleanDocs = new ArrayList<>();
            for (Document doc : rawDocuments) {
                HashMap<String, Object> cleanMeta = new HashMap<>();
                cleanMeta.put("source", filename);
                cleanMeta.put("dept", dept.name());
                cleanDocs.add(new Document(doc.getContent(), cleanMeta));
            }

            // 3. SPLIT & REDACT
            TokenTextSplitter splitter = new TokenTextSplitter();
            List<Document> splitDocuments = splitter.apply(cleanDocs);

            List<Document> finalDocuments = new ArrayList<>();
            int totalRedactions = 0;

            for (Document doc : splitDocuments) {
                // PII REDACTION using industry-standard service
                PiiRedactionService.RedactionResult result = piiRedactionService.redact(doc.getContent());
                Document redactedDoc = new Document(result.getRedactedContent(), doc.getMetadata());

                // Track redaction statistics
                totalRedactions += result.getTotalRedactions();

                // Directly add the document without experimental evolution
                finalDocuments.add(redactedDoc);
            }

            vectorStore.add(finalDocuments);
            log.info("Securely ingested {} memory points. Total PII redactions: {}",
                    finalDocuments.size(), totalRedactions);

        } catch (IOException e) {
            throw new RuntimeException("Secure Ingestion Failed: " + e.getMessage());
        }
    }
}