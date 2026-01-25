package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.service.PiiRedactionService;
import com.jreinhal.mercenary.rag.megarag.MegaRagService;
import com.jreinhal.mercenary.rag.miarag.MiARagService;
import com.jreinhal.mercenary.rag.ragpart.PartitionAssigner;
import com.jreinhal.mercenary.rag.hgmem.HyperGraphMemory;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

@Service
public class SecureIngestionService {
    private static final Logger log = LoggerFactory.getLogger(SecureIngestionService.class);
    private static final int MAX_EMBEDDED_IMAGES = 10;
    private static final int MIN_TEXT_LENGTH_FOR_VALID_PDF = 100; // Threshold for scanned PDF detection
    private final VectorStore vectorStore;
    private final PiiRedactionService piiRedactionService;
    private final PartitionAssigner partitionAssigner;
    private final MiARagService miARagService;
    private final MegaRagService megaRagService;
    private final HyperGraphMemory hyperGraphMemory;
    private final LightOnOcrService lightOnOcrService;
    private final Tika tika;
    private static final Set<String> BLOCKED_MIME_TYPES = Set.of("application/x-executable", "application/x-msdos-program", "application/x-msdownload", "application/x-sh", "application/x-shellscript", "application/java-archive", "application/x-httpd-php");
    @Value(value="${sentinel.miarag.min-chunks-for-mindscape:10}")
    private int minChunksForMindscape;
    @Value(value="${sentinel.megarag.extract-images-from-pdf:true}")
    private boolean extractImagesFromPdf;
    @Value(value="${sentinel.ocr.fallback-for-scanned-pdf:true}")
    private boolean ocrFallbackForScannedPdf;

    public SecureIngestionService(VectorStore vectorStore, PiiRedactionService piiRedactionService, PartitionAssigner partitionAssigner, MiARagService miARagService, MegaRagService megaRagService, HyperGraphMemory hyperGraphMemory, LightOnOcrService lightOnOcrService) {
        this.vectorStore = vectorStore;
        this.piiRedactionService = piiRedactionService;
        this.partitionAssigner = partitionAssigner;
        this.miARagService = miARagService;
        this.megaRagService = megaRagService;
        this.hyperGraphMemory = hyperGraphMemory;
        this.lightOnOcrService = lightOnOcrService;
        this.tika = new Tika();
    }

    public void ingest(MultipartFile file, Department dept) {
        try {
            List<Document> rawDocuments;
            String filename = file.getOriginalFilename();
            log.info("Initiating RAGPart Defense Protocol for: {} [Sector: {}]", filename, dept);
            byte[] fileBytes = file.getBytes();
            String detectedMimeType = this.detectMimeType(fileBytes, filename);
            log.info(">> Magic byte detection: {} -> {}", filename, detectedMimeType);
            this.validateFileType(filename, detectedMimeType);
            if (detectedMimeType.startsWith("image/")) {
                log.info(">> DETECTED IMAGE: Engaging MegaRAG Visual Ingestion...");
                if (this.megaRagService != null && this.megaRagService.isEnabled()) {
                    this.megaRagService.ingestVisualAsset(fileBytes, filename, dept.name(), "");
                } else {
                    log.warn("MegaRAG disabled; skipping visual ingestion for {}", filename);
                }
                return;
            }
            InputStreamResource resource = new InputStreamResource(new ByteArrayInputStream(fileBytes));
            if (detectedMimeType.equals("application/pdf")) {
                log.info(">> DETECTED PDF: Engaging Optical Character Recognition / PDF Stream...");
                PagePdfDocumentReader pdfReader = new PagePdfDocumentReader((Resource)resource);
                rawDocuments = pdfReader.get();

                // Check if this might be a scanned PDF (little extractable text)
                int totalTextLength = rawDocuments.stream()
                    .mapToInt(doc -> doc.getContent() != null ? doc.getContent().length() : 0)
                    .sum();

                if (totalTextLength < MIN_TEXT_LENGTH_FOR_VALID_PDF && this.ocrFallbackForScannedPdf
                        && this.lightOnOcrService != null && this.lightOnOcrService.isEnabled()) {
                    log.info(">> SCANNED PDF DETECTED: Text extraction yielded only {} chars. Engaging LightOnOCR...", totalTextLength);
                    String ocrText = this.lightOnOcrService.ocrPdf(fileBytes, filename);
                    if (ocrText != null && !ocrText.isEmpty()) {
                        rawDocuments = List.of(new Document(ocrText, java.util.Map.of("source", filename, "ocr", "true")));
                        log.info(">> LightOnOCR: Successfully extracted {} chars from scanned PDF", ocrText.length());
                    }
                }

                if (this.extractImagesFromPdf && this.megaRagService != null && this.megaRagService.isEnabled()) {
                    this.ingestEmbeddedImages(fileBytes, filename, dept.name(), rawDocuments);
                }
            } else {
                log.info(">> DETECTED TEXT: Engaging Standard Text Stream...");
                TextReader textReader = new TextReader((Resource)resource);
                rawDocuments = textReader.get();
            }
            ArrayList<Document> cleanDocs = new ArrayList<Document>();
            for (Document doc : rawDocuments) {
                java.util.Map<String, Object> cleanMeta = new java.util.HashMap<>();
                cleanMeta.put("source", filename);
                cleanMeta.put("dept", dept.name());
                cleanMeta.put("mimeType", detectedMimeType);
                cleanDocs.add(new Document(doc.getContent(), cleanMeta));
            }
            TokenTextSplitter splitter = new TokenTextSplitter();
            List<Document> splitDocuments = splitter.apply(cleanDocs);
            ArrayList<Document> finalDocuments = new ArrayList<Document>();
            int totalRedactions = 0;
            for (Document doc : splitDocuments) {
                PiiRedactionService.RedactionResult result = this.piiRedactionService.redact(doc.getContent());
                Document redactedDoc = new Document(result.getRedactedContent(), doc.getMetadata());
                totalRedactions += result.getTotalRedactions();
                finalDocuments.add(redactedDoc);
            }
            this.partitionAssigner.assignBatch(finalDocuments);
            this.vectorStore.add(finalDocuments);
            if (this.hyperGraphMemory != null && this.hyperGraphMemory.isEnabled()) {
                for (Document doc : finalDocuments) {
                    this.hyperGraphMemory.indexDocument(doc, dept.name());
                }
            }
            if (this.miARagService != null && this.miARagService.isEnabled() && finalDocuments.size() >= this.minChunksForMindscape) {
                List<String> chunks = finalDocuments.stream().map(Document::getContent).toList();
                this.miARagService.buildMindscape(chunks, filename, dept.name());
            }
            log.info("Securely ingested {} memory points. Total PII redactions: {}", finalDocuments.size(), totalRedactions);
        }
        catch (IOException e) {
            throw new SecureIngestionException("Secure Ingestion Failed: " + e.getMessage(), e);
        }
    }

    private String detectMimeType(byte[] bytes, String filename) throws IOException {
        try (BufferedInputStream is = new BufferedInputStream(new ByteArrayInputStream(bytes));){
            return this.tika.detect((InputStream)is, filename);
        }
    }

    private void validateFileType(String filename, String detectedMimeType) {
        if (BLOCKED_MIME_TYPES.contains(detectedMimeType)) {
            log.error("SECURITY: Blocked dangerous file type. File: {}, Detected: {}", filename, detectedMimeType);
            throw new SecurityException("File type not allowed: " + detectedMimeType + ". Executable and script files are blocked.");
        }
        String extension = this.getExtension(filename).toLowerCase();
        if (extension.equals("pdf") && !detectedMimeType.equals("application/pdf")) {
            log.warn("SECURITY WARNING: PDF extension but detected as: {} - File: {}", detectedMimeType, filename);
        }
        if (Set.of("exe", "dll", "bat", "sh", "cmd", "ps1", "jar").contains(extension)) {
            log.error("SECURITY: Executable extension blocked: {}", filename);
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

    private void ingestEmbeddedImages(byte[] fileBytes, String filename, String department, List<Document> rawDocuments) {
        String contextText = rawDocuments.stream().map(Document::getContent).collect(java.util.stream.Collectors.joining("\n\n"));
        List<byte[]> images = this.extractEmbeddedImages(fileBytes);
        if (images.isEmpty()) {
            return;
        }
        int count = 0;
        for (byte[] img : images) {
            if (count >= MAX_EMBEDDED_IMAGES) {
                break;
            }
            this.megaRagService.ingestVisualAsset(img, filename, department, contextText);
            count++;
        }
        log.info("MegaRAG: Extracted and ingested {} embedded images from {}", count, filename);
    }

    private List<byte[]> extractEmbeddedImages(byte[] fileBytes) {
        ArrayList<byte[]> images = new ArrayList<>();
        try {
            AutoDetectParser parser = new AutoDetectParser();
            ParseContext context = new ParseContext();
            EmbeddedDocumentExtractor extractor = new EmbeddedDocumentExtractor() {
                public boolean shouldParseEmbedded(Metadata metadata) {
                    String type = metadata.get("Content-Type");
                    return type != null && type.startsWith("image/");
                }

                public void parseEmbedded(InputStream stream, ContentHandler handler, Metadata metadata, boolean outputHtml) throws SAXException, IOException {
                    images.add(stream.readAllBytes());
                }
            };
            context.set(EmbeddedDocumentExtractor.class, extractor);
            parser.parse(new ByteArrayInputStream(fileBytes), new BodyContentHandler(), new Metadata(), context);
        }
        catch (Exception e) {
            log.warn("Embedded image extraction failed: {}", e.getMessage());
        }
        return images;
    }
}
