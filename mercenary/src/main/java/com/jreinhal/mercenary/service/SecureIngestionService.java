package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.Department;
import org.apache.tika.Tika;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Secure document ingestion service with PII redaction.
 *
 * SECURITY: Uses Apache Tika magic byte detection to verify file types.
 * This prevents malicious file uploads disguised with fake extensions
 * (e.g., malware.exe renamed to document.pdf).
 */
@Service
public class SecureIngestionService {

    private static final Logger log = LoggerFactory.getLogger(SecureIngestionService.class);

    private final VectorStore vectorStore;
    private final PiiRedactionService piiRedactionService;
    private final Tika tika;

    /**
     * Blocked MIME types that indicate executable or dangerous content.
     */
    private static final Set<String> BLOCKED_MIME_TYPES = Set.of(
        "application/x-executable",
        "application/x-msdos-program",
        "application/x-msdownload",
        "application/x-sh",
        "application/x-shellscript",
        "application/java-archive",
        "application/x-httpd-php"
    );

    public SecureIngestionService(VectorStore vectorStore, PiiRedactionService piiRedactionService) {
        this.vectorStore = vectorStore;
        this.piiRedactionService = piiRedactionService;
        this.tika = new Tika();
    }

    public void ingest(MultipartFile file, Department dept) {
        try {
            String filename = file.getOriginalFilename();
            log.info("Initiating RAGPart Defense Protocol for: {} [Sector: {}]", filename, dept);

            // SECURITY: Detect actual file type via magic bytes, not extension
            String detectedMimeType = detectMimeType(file);
            log.info(">> Magic byte detection: {} -> {}", filename, detectedMimeType);

            // Validate file type - block executables and dangerous files
            validateFileType(filename, detectedMimeType);

            InputStreamResource resource = new InputStreamResource(file.getInputStream());
            List<Document> rawDocuments;

            // Use detected MIME type, not file extension
            if (detectedMimeType.equals("application/pdf")) {
                log.info(">> DETECTED PDF: Engaging Optical Character Recognition / PDF Stream...");
                PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(resource);
                rawDocuments = pdfReader.get();
            } else {
                log.info(">> DETECTED TEXT: Engaging Standard Text Stream...");
                TextReader textReader = new TextReader(resource);
                rawDocuments = textReader.get();
            }

            // 2. PRE-SPLIT SANITIZATION
            List<Document> cleanDocs = new ArrayList<>();
            for (Document doc : rawDocuments) {
                HashMap<String, Object> cleanMeta = new HashMap<>();
                cleanMeta.put("source", filename);
                cleanMeta.put("dept", dept.name());
                cleanMeta.put("mimeType", detectedMimeType);
                cleanDocs.add(new Document(doc.getContent(), cleanMeta));
            }

            // 3. SPLIT & REDACT
            TokenTextSplitter splitter = new TokenTextSplitter();
            List<Document> splitDocuments = splitter.apply(cleanDocs);

            List<Document> finalDocuments = new ArrayList<>();
            int totalRedactions = 0;

            for (Document doc : splitDocuments) {
                PiiRedactionService.RedactionResult result = piiRedactionService.redact(doc.getContent());
                Document redactedDoc = new Document(result.getRedactedContent(), doc.getMetadata());
                totalRedactions += result.getTotalRedactions();
                finalDocuments.add(redactedDoc);
            }

            vectorStore.add(finalDocuments);
            log.info("Securely ingested {} memory points. Total PII redactions: {}",
                    finalDocuments.size(), totalRedactions);

        } catch (IOException e) {
            throw new RuntimeException("Secure Ingestion Failed: " + e.getMessage());
        }
    }

    /**
     * Detect MIME type using Apache Tika magic byte analysis.
     * SECURITY: This examines file content, not just extension.
     */
    private String detectMimeType(MultipartFile file) throws IOException {
        try (InputStream is = new BufferedInputStream(file.getInputStream())) {
            return tika.detect(is, file.getOriginalFilename());
        }
    }

    /**
     * Validate that the detected file type is safe for ingestion.
     * SECURITY: Blocks executables and other dangerous file types.
     *
     * @throws SecurityException if file type is blocked or suspicious
     */
    private void validateFileType(String filename, String detectedMimeType) {
        // Check for blocked types
        if (BLOCKED_MIME_TYPES.contains(detectedMimeType)) {
            log.error("SECURITY: Blocked dangerous file type. File: {}, Detected: {}",
                    filename, detectedMimeType);
            throw new SecurityException(
                "File type not allowed: " + detectedMimeType + ". Executable and script files are blocked.");
        }

        // Check for extension mismatch (potential spoofing)
        String extension = getExtension(filename).toLowerCase();

        if (extension.equals("pdf") && !detectedMimeType.equals("application/pdf")) {
            log.warn("SECURITY WARNING: PDF extension but detected as: {} - File: {}",
                    detectedMimeType, filename);
        }

        // Block executable extensions regardless of detected type
        if (Set.of("exe", "dll", "bat", "sh", "cmd", "ps1", "jar").contains(extension)) {
            log.error("SECURITY: Executable extension blocked: {}", filename);
            throw new SecurityException("Executable files are not allowed: " + filename);
        }
    }

    /**
     * Get file extension from filename.
     */
    private String getExtension(String filename) {
        if (filename == null) return "";
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot + 1) : "";
    }
}
