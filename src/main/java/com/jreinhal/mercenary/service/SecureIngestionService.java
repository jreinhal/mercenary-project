package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.service.PiiRedactionService;
import com.jreinhal.mercenary.util.LogSanitizer;
import com.jreinhal.mercenary.rag.megarag.MegaRagService;
import com.jreinhal.mercenary.rag.miarag.MiARagService;
import com.jreinhal.mercenary.rag.ragpart.PartitionAssigner;
import com.jreinhal.mercenary.rag.hgmem.HyperGraphMemory;
import com.jreinhal.mercenary.workspace.WorkspaceContext;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
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
    private static final int MAX_TIKA_CHARS = 1_000_000;
    private final VectorStore vectorStore;
    private final PiiRedactionService piiRedactionService;
    private final PartitionAssigner partitionAssigner;
    private final MiARagService miARagService;
    private final MegaRagService megaRagService;
    private final HyperGraphMemory hyperGraphMemory;
    private final LightOnOcrService lightOnOcrService;
    private final HipaaPolicy hipaaPolicy;
    private final com.jreinhal.mercenary.workspace.WorkspaceQuotaService workspaceQuotaService;
    private final Tika tika;
    private static final String PDF_MIME_TYPE = "application/pdf";
    private static final Set<String> BLOCKED_MIME_TYPES = Set.of(
        "application/x-executable", "application/x-msdos-program", "application/x-msdownload",
        "application/x-sh", "application/x-shellscript", "text/x-shellscript",
        "application/java-archive", "application/x-httpd-php",
        // Fix #4: Block archive/container formats that can smuggle malicious content
        "application/zip", "application/java-vm", "application/x-java-applet",
        "application/x-rar-compressed", "application/x-7z-compressed",
        "application/vnd.rar", "application/x-tar", "application/gzip",
        "application/x-bzip2", "application/x-xz"
    );
    @Value("${sentinel.miarag.min-chunks-for-mindscape:10}")
    private int minChunksForMindscape;
    @Value("${sentinel.megarag.extract-images-from-pdf:true}")
    private boolean extractImagesFromPdf;
    @Value("${sentinel.ocr.fallback-for-scanned-pdf:true}")
    private boolean ocrFallbackForScannedPdf;

    public SecureIngestionService(VectorStore vectorStore, PiiRedactionService piiRedactionService, PartitionAssigner partitionAssigner, MiARagService miARagService, MegaRagService megaRagService, HyperGraphMemory hyperGraphMemory, LightOnOcrService lightOnOcrService, HipaaPolicy hipaaPolicy, com.jreinhal.mercenary.workspace.WorkspaceQuotaService workspaceQuotaService) {
        this.vectorStore = vectorStore;
        this.piiRedactionService = piiRedactionService;
        this.partitionAssigner = partitionAssigner;
        this.miARagService = miARagService;
        this.megaRagService = megaRagService;
        this.hyperGraphMemory = hyperGraphMemory;
        this.lightOnOcrService = lightOnOcrService;
        this.hipaaPolicy = hipaaPolicy;
        this.workspaceQuotaService = workspaceQuotaService;
        this.tika = new Tika();
    }

    public void ingest(MultipartFile file, Department dept) {
        try {
            List<Document> rawDocuments;
            String filename = file.getOriginalFilename();
            log.info("Initiating RAGPart Defense Protocol for: {} [Sector: {}]", filename, dept);
            String workspaceId = WorkspaceContext.getCurrentWorkspaceId();
            boolean hipaaStrict = this.hipaaPolicy.isStrict(dept);
            byte[] fileBytes = file.getBytes();
            this.workspaceQuotaService.enforceIngestionQuota(workspaceId, fileBytes.length);
            String detectedMimeType = this.detectMimeType(fileBytes, filename);
            log.info(">> Magic byte detection: {} -> {}", filename, detectedMimeType);
            this.validateFileType(filename, detectedMimeType, fileBytes);
            if (detectedMimeType.startsWith("image/")) {
                log.info(">> DETECTED IMAGE: Engaging MegaRAG Visual Ingestion...");
                if (hipaaStrict && this.hipaaPolicy.shouldDisableVisual(dept)) {
                    log.warn("HIPAA strict: visual ingestion disabled for medical sector, rejecting {}", filename);
                    throw new SecureIngestionException("Visual ingestion disabled for HIPAA medical deployments.", null);
                }
                if (this.megaRagService != null && this.megaRagService.isEnabled()) {
                    this.megaRagService.ingestVisualAsset(fileBytes, filename, dept.name(), "");
                } else {
                    log.warn("MegaRAG disabled; skipping visual ingestion for {}", filename);
                }
                return;
            }
            InputStreamResource resource = new InputStreamResource(new ByteArrayInputStream(fileBytes));
            if (PDF_MIME_TYPE.equals(detectedMimeType)) {
                log.info(">> DETECTED PDF: Engaging Optical Character Recognition / PDF Stream...");
                PagePdfDocumentReader pdfReader = new PagePdfDocumentReader((Resource)resource);
                rawDocuments = pdfReader.get();

                // Check if this might be a scanned PDF (little extractable text)
                int totalTextLength = rawDocuments.stream()
                    .mapToInt(doc -> doc.getContent() != null ? doc.getContent().length() : 0)
                    .sum();

                if (!hipaaStrict && totalTextLength < MIN_TEXT_LENGTH_FOR_VALID_PDF && this.ocrFallbackForScannedPdf
                        && this.lightOnOcrService != null && this.lightOnOcrService.isEnabled()) {
                    log.info(">> SCANNED PDF DETECTED: Text extraction yielded only {} chars. Engaging LightOnOCR...", totalTextLength);
                    String ocrText = this.lightOnOcrService.ocrPdf(fileBytes, filename);
                    if (ocrText != null && !ocrText.isEmpty()) {
                        rawDocuments = List.of(new Document(ocrText, java.util.Map.of("source", filename, "ocr", "true")));
                        log.info(">> LightOnOCR: Successfully extracted {} chars from scanned PDF", ocrText.length());
                    }
                }

                if (!hipaaStrict && this.extractImagesFromPdf && this.megaRagService != null && this.megaRagService.isEnabled()) {
                    this.ingestEmbeddedImages(fileBytes, filename, dept.name(), rawDocuments);
                }
            } else {
                log.info(">> DETECTED DOCUMENT: Engaging Tika text extraction...");
                rawDocuments = this.extractTextDocuments(fileBytes, filename);
            }
            ArrayList<Document> cleanDocs = new ArrayList<Document>();
            for (Document doc : rawDocuments) {
                java.util.Map<String, Object> cleanMeta = new java.util.HashMap<>();
                cleanMeta.put("source", filename);
                cleanMeta.put("dept", dept.name());
                cleanMeta.put("workspaceId", workspaceId);
                cleanMeta.put("mimeType", detectedMimeType);
                cleanMeta.put("fileSizeBytes", fileBytes.length);
                cleanDocs.add(new Document(doc.getContent(), cleanMeta));
            }
            TokenTextSplitter splitter = new TokenTextSplitter();
            List<Document> splitDocuments = splitter.apply(cleanDocs);
            ArrayList<Document> finalDocuments = new ArrayList<Document>();
            int totalRedactions = 0;
            for (Document doc : splitDocuments) {
                PiiRedactionService.RedactionResult result = this.piiRedactionService.redact(doc.getContent(), hipaaStrict ? Boolean.TRUE : null);
                Document redactedDoc = new Document(result.getRedactedContent(), doc.getMetadata());
                totalRedactions += result.getTotalRedactions();
                finalDocuments.add(redactedDoc);
            }
            this.partitionAssigner.assignBatch(finalDocuments);
            this.vectorStore.add(finalDocuments);
            if (this.hyperGraphMemory != null && this.hyperGraphMemory.isIndexingEnabled()) {
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
            throw new SecureIngestionException("Secure Ingestion Failed", e);
        }
    }

    private String detectMimeType(byte[] bytes, String filename) throws IOException {
        try (BufferedInputStream is = new BufferedInputStream(new ByteArrayInputStream(bytes));){
            return this.tika.detect((InputStream)is, filename);
        }
    }

    public void ingestBytes(byte[] fileBytes, String filename, Department dept) {
        if (fileBytes == null || fileBytes.length == 0) {
            throw new SecureIngestionException("Empty file payload provided for ingestion.", null);
        }
        String safeName = (filename == null || filename.isBlank()) ? "uploaded.bin" : filename;
        ingest(new InMemoryMultipartFile(safeName, fileBytes), dept);
    }

    private static final class InMemoryMultipartFile implements MultipartFile {
        private final String filename;
        private final byte[] bytes;

        private InMemoryMultipartFile(String filename, byte[] bytes) {
            this.filename = filename;
            this.bytes = bytes;
        }

        @Override
        public String getName() {
            return this.filename;
        }

        @Override
        public String getOriginalFilename() {
            return this.filename;
        }

        @Override
        public String getContentType() {
            return null;
        }

        @Override
        public boolean isEmpty() {
            return this.bytes.length == 0;
        }

        @Override
        public long getSize() {
            return this.bytes.length;
        }

        @Override
        public byte[] getBytes() throws IOException {
            return this.bytes;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return new ByteArrayInputStream(this.bytes);
        }

        @Override
        public void transferTo(java.io.File dest) throws IOException, IllegalStateException {
            throw new UnsupportedOperationException("In-memory file cannot be transferred.");
        }
    }

    private void validateFileType(String filename, String detectedMimeType, byte[] bytes) {
        String safeFilename = LogSanitizer.sanitize(filename);
        String safeMimeType = LogSanitizer.sanitize(detectedMimeType);
        if (BLOCKED_MIME_TYPES.contains(detectedMimeType)) {
            log.error("SECURITY: Blocked dangerous file type. File: {}, Detected: {}", safeFilename, safeMimeType);
            throw new SecurityException("File type not allowed: " + detectedMimeType + ". Executable and script files are blocked.");
        }
        if (this.hasExecutableMagic(bytes)) {
            log.error("SECURITY: Blocked executable magic bytes. File: {}", safeFilename);
            throw new SecurityException("File content appears to be an executable and is not allowed: " + filename);
        }
        String extension = this.getExtension(filename).toLowerCase();
        if ("pdf".equals(extension) && !PDF_MIME_TYPE.equals(detectedMimeType)) {
            log.warn("SECURITY WARNING: PDF extension but detected as: {} - File: {}", safeMimeType, safeFilename);
        }
        // Fix #4: Block content-type/extension mismatch — PDF content with non-PDF extension
        if (PDF_MIME_TYPE.equals(detectedMimeType) && !"pdf".equals(extension)) {
            log.error("SECURITY: PDF content disguised as .{} file: {}", LogSanitizer.sanitize(extension), safeFilename);
            throw new SecurityException("Content type mismatch: file contains PDF data but has ." + extension + " extension.");
        }
        if (Set.of("exe", "dll", "bat", "sh", "cmd", "ps1", "jar").contains(extension)) {
            log.error("SECURITY: Executable extension blocked: {}", safeFilename);
            throw new SecurityException("Executable files are not allowed: " + filename);
        }
    }

    private boolean hasExecutableMagic(byte[] bytes) {
        if (bytes == null || bytes.length < 2) {
            return false;
        }
        // Windows EXE/DLL: 'MZ'
        if (bytes[0] == 0x4D && bytes[1] == 0x5A) {
            return true;
        }
        // ELF: 0x7F 'E' 'L' 'F'
        if (bytes.length >= 4 && bytes[0] == 0x7F && bytes[1] == 0x45 && bytes[2] == 0x4C && bytes[3] == 0x46) {
            return true;
        }
        // Fix #4: Detect archive/container magic bytes
        // ZIP / JAR: 'PK\x03\x04'
        if (bytes.length >= 4 && bytes[0] == 0x50 && bytes[1] == 0x4B && bytes[2] == 0x03 && bytes[3] == 0x04) {
            return true;
        }
        // Java class: 0xCAFEBABE
        if (bytes.length >= 4 && bytes[0] == (byte) 0xCA && bytes[1] == (byte) 0xFE
                && bytes[2] == (byte) 0xBA && bytes[3] == (byte) 0xBE) {
            return true;
        }
        // RAR: 'Rar!\x1A\x07'
        if (bytes.length >= 6 && bytes[0] == 0x52 && bytes[1] == 0x61 && bytes[2] == 0x72
                && bytes[3] == 0x21 && bytes[4] == 0x1A && bytes[5] == 0x07) {
            return true;
        }
        // 7-Zip: '7z\xBC\xAF\x27\x1C'
        if (bytes.length >= 6 && bytes[0] == 0x37 && bytes[1] == 0x7A && bytes[2] == (byte) 0xBC
                && bytes[3] == (byte) 0xAF && bytes[4] == 0x27 && bytes[5] == 0x1C) {
            return true;
        }
        // Shell script shebang: '#!'
        if (bytes.length >= 2 && bytes[0] == 0x23 && bytes[1] == 0x21) {
            return true;
        }
        return false;
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

    private List<Document> extractTextDocuments(byte[] fileBytes, String filename) {
        String extracted = this.extractTextWithTika(fileBytes, filename);
        if (extracted == null || extracted.isBlank()) {
            try {
                extracted = new String(fileBytes, StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.warn("UTF-8 fallback failed for {}: {}", filename, e.getMessage());
            }
        }
        if (extracted == null || extracted.isBlank()) {
            throw new SecureIngestionException("Unable to extract text content from " + filename, null);
        }
        return List.of(new Document(extracted));
    }

    private String extractTextWithTika(byte[] fileBytes, String filename) {
        try {
            AutoDetectParser parser = new AutoDetectParser();
            ParseContext context = new ParseContext();
            // C-09: Disable XXE by configuring SAXParserFactory with external entity restrictions
            javax.xml.parsers.SAXParserFactory spf = javax.xml.parsers.SAXParserFactory.newInstance();
            spf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            spf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            spf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            context.set(javax.xml.parsers.SAXParserFactory.class, spf);
            Metadata metadata = new Metadata();
            if (filename != null) {
                metadata.set("resourceName", filename);
            }
            BodyContentHandler handler = new BodyContentHandler(MAX_TIKA_CHARS);
            parser.parse(new ByteArrayInputStream(fileBytes), handler, metadata, context);
            return handler.toString();
        } catch (IOException | SAXException | TikaException e) {
            log.warn("Tika extraction failed for {}: {}", filename, e.getMessage());
            return "";
        } catch (Exception e) {
            log.warn("Unexpected extraction failure for {}: {}", filename, e.getMessage());
            return "";
        }
    }
}
