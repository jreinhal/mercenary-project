/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.jreinhal.mercenary.Department
 *  com.jreinhal.mercenary.service.PiiRedactionService
 *  com.jreinhal.mercenary.service.PiiRedactionService$RedactionResult
 *  com.jreinhal.mercenary.service.SecureIngestionService
 *  org.apache.tika.Tika
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.ai.document.Document
 *  org.springframework.ai.reader.TextReader
 *  org.springframework.ai.reader.pdf.PagePdfDocumentReader
 *  org.springframework.ai.transformer.splitter.TokenTextSplitter
 *  org.springframework.ai.vectorstore.VectorStore
 *  org.springframework.core.io.InputStreamResource
 *  org.springframework.core.io.Resource
 *  org.springframework.stereotype.Service
 *  org.springframework.web.multipart.MultipartFile
 */
package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.service.PiiRedactionService;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class SecureIngestionService {
    private static final Logger log = LoggerFactory.getLogger(SecureIngestionService.class);
    private final VectorStore vectorStore;
    private final PiiRedactionService piiRedactionService;
    private final Tika tika;
    private static final Set<String> BLOCKED_MIME_TYPES = Set.of("application/x-executable", "application/x-msdos-program", "application/x-msdownload", "application/x-sh", "application/x-shellscript", "application/java-archive", "application/x-httpd-php");

    public SecureIngestionService(VectorStore vectorStore, PiiRedactionService piiRedactionService) {
        this.vectorStore = vectorStore;
        this.piiRedactionService = piiRedactionService;
        this.tika = new Tika();
    }

    public void ingest(MultipartFile file, Department dept) {
        try {
            List rawDocuments;
            String filename = file.getOriginalFilename();
            log.info("Initiating RAGPart Defense Protocol for: {} [Sector: {}]", (Object)filename, (Object)dept);
            String detectedMimeType = this.detectMimeType(file);
            log.info(">> Magic byte detection: {} -> {}", (Object)filename, (Object)detectedMimeType);
            this.validateFileType(filename, detectedMimeType);
            InputStreamResource resource = new InputStreamResource(file.getInputStream());
            if (detectedMimeType.equals("application/pdf")) {
                log.info(">> DETECTED PDF: Engaging Optical Character Recognition / PDF Stream...");
                PagePdfDocumentReader pdfReader = new PagePdfDocumentReader((Resource)resource);
                rawDocuments = pdfReader.get();
            } else {
                log.info(">> DETECTED TEXT: Engaging Standard Text Stream...");
                TextReader textReader = new TextReader((Resource)resource);
                rawDocuments = textReader.get();
            }
            ArrayList<Document> cleanDocs = new ArrayList<Document>();
            for (Document doc : rawDocuments) {
                HashMap<String, String> cleanMeta = new HashMap<String, String>();
                cleanMeta.put("source", filename);
                cleanMeta.put("dept", dept.name());
                cleanMeta.put("mimeType", detectedMimeType);
                cleanDocs.add(new Document(doc.getContent(), cleanMeta));
            }
            TokenTextSplitter splitter = new TokenTextSplitter();
            List splitDocuments = splitter.apply(cleanDocs);
            ArrayList<Document> finalDocuments = new ArrayList<Document>();
            int totalRedactions = 0;
            for (Document doc : splitDocuments) {
                PiiRedactionService.RedactionResult result = this.piiRedactionService.redact(doc.getContent());
                Document redactedDoc = new Document(result.getRedactedContent(), doc.getMetadata());
                totalRedactions += result.getTotalRedactions();
                finalDocuments.add(redactedDoc);
            }
            this.vectorStore.add(finalDocuments);
            log.info("Securely ingested {} memory points. Total PII redactions: {}", (Object)finalDocuments.size(), (Object)totalRedactions);
        }
        catch (IOException e) {
            throw new RuntimeException("Secure Ingestion Failed: " + e.getMessage());
        }
    }

    private String detectMimeType(MultipartFile file) throws IOException {
        try (BufferedInputStream is = new BufferedInputStream(file.getInputStream());){
            String string = this.tika.detect((InputStream)is, file.getOriginalFilename());
            return string;
        }
    }

    private void validateFileType(String filename, String detectedMimeType) {
        if (BLOCKED_MIME_TYPES.contains(detectedMimeType)) {
            log.error("SECURITY: Blocked dangerous file type. File: {}, Detected: {}", (Object)filename, (Object)detectedMimeType);
            throw new SecurityException("File type not allowed: " + detectedMimeType + ". Executable and script files are blocked.");
        }
        String extension = this.getExtension(filename).toLowerCase();
        if (extension.equals("pdf") && !detectedMimeType.equals("application/pdf")) {
            log.warn("SECURITY WARNING: PDF extension but detected as: {} - File: {}", (Object)detectedMimeType, (Object)filename);
        }
        if (Set.of("exe", "dll", "bat", "sh", "cmd", "ps1", "jar").contains(extension)) {
            log.error("SECURITY: Executable extension blocked: {}", (Object)filename);
            throw new SecurityException("Executable files are not allowed: " + filename);
        }
    }

    private String getExtension(String filename) {
        if (filename == null) {
            return "";
        }
        int lastDot = filename.lastIndexOf(46);
        return lastDot > 0 ? filename.substring(lastDot + 1) : "";
    }
}

