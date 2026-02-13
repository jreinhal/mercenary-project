package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.service.PiiRedactionService;
import com.jreinhal.mercenary.util.LogSanitizer;
import com.jreinhal.mercenary.rag.megarag.MegaRagService;
import com.jreinhal.mercenary.rag.miarag.MiARagService;
import com.jreinhal.mercenary.rag.ragpart.PartitionAssigner;
import com.jreinhal.mercenary.rag.hgmem.HyperGraphMemory;
import com.jreinhal.mercenary.workspace.WorkspaceContext;
import com.jreinhal.mercenary.util.DocumentTemporalMetadataExtractor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
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
    private final TableExtractor tableExtractor;
    private final SourceDocumentService sourceDocumentService;
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
    @Value("${sentinel.ingest.chunking.chunk-size-tokens:800}")
    private int chunkSizeTokens = 800;
    @Value("${sentinel.ingest.chunking.min-chunk-size-chars:350}")
    private int minChunkSizeChars = 350;
    @Value("${sentinel.ingest.chunking.min-chunk-length-to-embed:5}")
    private int minChunkLengthToEmbed = 5;
    @Value("${sentinel.ingest.chunking.max-num-chunks:10000}")
    private int maxNumChunks = 10000;
    @Value("${sentinel.ingest.chunking.keep-separator:true}")
    private boolean keepSeparator = true;
    @Value("${sentinel.ingest.chunk-merge.enabled:true}")
    private boolean chunkMergeEnabled = true;
    @Value("${sentinel.ingest.chunk-merge.min-tokens:512}")
    private int chunkMergeMinTokens = 512;
    @Value("${sentinel.ingest.chunk-merge.max-tokens:2000}")
    private int chunkMergeMaxTokens = 2000;
    @Value("${sentinel.ingest.resilience.enabled:true}")
    private boolean resilienceEnabled = true;
    @Value("${sentinel.ingest.resilience.max-retries:1}")
    private int ingestMaxRetries = 1;
    @Value("${sentinel.ingest.resilience.failure-threshold-percent:50}")
    private double ingestFailureThresholdPercent = 50.0;
    @Value("${sentinel.ingest.resilience.failure-threshold-min-samples:5}")
    private int ingestFailureThresholdMinSamples = 5;
    @Value("${sentinel.ingest.resilience.checkpoint-path:${java.io.tmpdir}/sentinel-ingestion/session.json}")
    private String ingestCheckpointPath = Paths.get(System.getProperty("java.io.tmpdir", "."), "sentinel-ingestion", "session.json").toString();
    @Value("${sentinel.ingest.resilience.failed-docs-max:500}")
    private int ingestFailedDocsMax = 500;
    private static final EncodingRegistry TOKEN_ENCODING_REGISTRY = Encodings.newLazyEncodingRegistry();
    private static final Encoding TOKEN_ENCODING = TOKEN_ENCODING_REGISTRY.getEncoding(EncodingType.CL100K_BASE);
    private final Object checkpointLock = new Object();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private long sessionStartTime = System.currentTimeMillis();
    private int processedCount = 0;
    private int failedCount = 0;
    private int securityRejectedCount = 0;
    private String lastProcessedDoc = "";
    private final Set<String> failedDocs = new LinkedHashSet<>();

    public SecureIngestionService(VectorStore vectorStore, PiiRedactionService piiRedactionService, PartitionAssigner partitionAssigner, MiARagService miARagService, MegaRagService megaRagService, HyperGraphMemory hyperGraphMemory, LightOnOcrService lightOnOcrService, TableExtractor tableExtractor, SourceDocumentService sourceDocumentService, HipaaPolicy hipaaPolicy, com.jreinhal.mercenary.workspace.WorkspaceQuotaService workspaceQuotaService) {
        this.vectorStore = vectorStore;
        this.piiRedactionService = piiRedactionService;
        this.partitionAssigner = partitionAssigner;
        this.miARagService = miARagService;
        this.megaRagService = megaRagService;
        this.hyperGraphMemory = hyperGraphMemory;
        this.lightOnOcrService = lightOnOcrService;
        this.tableExtractor = tableExtractor;
        this.sourceDocumentService = sourceDocumentService;
        this.hipaaPolicy = hipaaPolicy;
        this.workspaceQuotaService = workspaceQuotaService;
        this.tika = new Tika();
    }

    @PostConstruct
    void initializeResilienceState() {
        if (this.resilienceEnabled) {
            this.loadCheckpointState();
        }
    }

    public void ingest(MultipartFile file, Department dept) {
        this.ingest(file, dept, Map.of());
    }

    public void ingest(MultipartFile file, Department dept, Map<String, Object> additionalMetadata) {
        if (file == null) {
            throw new SecureIngestionException("No file provided for ingestion.", null);
        }
        this.enforceFailureThreshold();
        int maxAttempts = this.resilienceEnabled ? Math.max(1, this.ingestMaxRetries + 1) : 1;
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown";
        Map<String, Object> safeAdditionalMetadata = this.normalizeAdditionalMetadata(additionalMetadata);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            boolean fallbackMode = attempt > 1;
            try {
                this.ingestInternal(file, dept, fallbackMode, safeAdditionalMetadata);
                this.recordIngestionOutcome(filename, true);
                return;
            } catch (SecurityException e) {
                this.recordSecurityRejection(filename);
                throw e;
            } catch (RuntimeException e) {
                boolean lastAttempt = attempt >= maxAttempts;
                if (lastAttempt || !this.isRetriableIngestionFailure(e)) {
                    this.recordIngestionOutcome(filename, false);
                    throw e;
                }
                if (log.isWarnEnabled()) {
                    log.warn("Ingestion attempt {}/{} failed for {}. Retrying with fallback mode. Error: {}",
                            attempt, maxAttempts, LogSanitizer.sanitize(filename), e.getMessage());
                }
            }
        }
    }

    private boolean isRetriableIngestionFailure(RuntimeException e) {
        if (e instanceof SecurityException || e instanceof NonRetriableIngestionException || e instanceof SecureIngestionException || e instanceof IllegalArgumentException) {
            return false;
        }
        String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        return message.contains("timeout")
                || message.contains("tempor")
                || message.contains("connection")
                || message.contains("transient");
    }

    private void ingestInternal(MultipartFile file, Department dept, boolean fallbackMode,
                                Map<String, Object> additionalMetadata) {
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
                this.sourceDocumentService.storePdfSource(workspaceId, dept, filename, fileBytes);
                log.info(">> DETECTED PDF: Engaging Optical Character Recognition / PDF Stream...");
                PagePdfDocumentReader pdfReader = new PagePdfDocumentReader((Resource)resource);
                rawDocuments = pdfReader.get();

                // Check if this might be a scanned PDF (little extractable text)
                int totalTextLength = rawDocuments.stream()
                    .mapToInt(doc -> doc.getContent() != null ? doc.getContent().length() : 0)
                    .sum();
                boolean scannedPdf = totalTextLength < MIN_TEXT_LENGTH_FOR_VALID_PDF;
                boolean hasAnyText = totalTextLength > 0;

                if (!hipaaStrict && scannedPdf && this.ocrFallbackForScannedPdf
                        && this.lightOnOcrService != null && this.lightOnOcrService.isEnabled()) {
                    log.info(">> SCANNED PDF DETECTED: Text extraction yielded only {} chars. Engaging LightOnOCR...", totalTextLength);
                    String ocrText = this.lightOnOcrService.ocrPdf(fileBytes, filename);
                    if (ocrText != null && !ocrText.isEmpty()) {
                        rawDocuments = List.of(new Document(ocrText, java.util.Map.of("source", filename, "ocr", "true")));
                        log.info(">> LightOnOCR: Successfully extracted {} chars from scanned PDF", ocrText.length());
                    }
                }

                // Decouple table extraction from the OCR/scanned-PDF heuristic: short-but-text-layer PDFs can contain
                // valuable tables and should still attempt extraction.
                if (!fallbackMode && hasAnyText && this.tableExtractor != null && this.tableExtractor.isEnabled()) {
                    List<Document> tableDocs = this.tableExtractor.extractTables(fileBytes, filename);
                    if (tableDocs != null && !tableDocs.isEmpty()) {
                        List<Document> combined = new ArrayList<>(rawDocuments);
                        combined.addAll(tableDocs);
                        rawDocuments = combined;
                    }
                }

                if (!fallbackMode && !hipaaStrict && this.extractImagesFromPdf && this.megaRagService != null && this.megaRagService.isEnabled()) {
                    this.ingestEmbeddedImages(fileBytes, filename, dept.name(), rawDocuments);
                }
            } else {
                log.info(">> DETECTED DOCUMENT: Engaging Tika text extraction...");
                rawDocuments = this.extractTextDocuments(fileBytes, filename);
            }
            DocumentTemporalMetadataExtractor.TemporalMetadata temporal =
                    DocumentTemporalMetadataExtractor.extract(fileBytes, detectedMimeType, rawDocuments, filename);
            List<Document> cleanDocs = new ArrayList<>();
            for (Document doc : rawDocuments) {
                Map<String, Object> mergedMeta = new HashMap<>();
                if (doc.getMetadata() != null) {
                    mergedMeta.putAll(doc.getMetadata());
                }
                if (additionalMetadata != null && !additionalMetadata.isEmpty()) {
                    additionalMetadata.forEach(mergedMeta::putIfAbsent);
                }
                mergedMeta.put("source", filename);
                mergedMeta.put("dept", dept.name());
                mergedMeta.put("workspaceId", workspaceId);
                mergedMeta.put("mimeType", detectedMimeType);
                mergedMeta.put("fileSizeBytes", fileBytes.length);
                if (temporal != null && !temporal.isEmpty()) {
                    if (temporal.documentYear() != null) {
                        mergedMeta.put("documentYear", temporal.documentYear());
                    }
                    if (temporal.documentDateEpoch() != null) {
                        mergedMeta.put("documentDateEpoch", temporal.documentDateEpoch());
                    }
                    if (temporal.documentDateSource() != null) {
                        mergedMeta.put("documentDateSource", temporal.documentDateSource());
                    }
                }
                cleanDocs.add(new Document(doc.getContent(), mergedMeta));
            }
            List<Document> atomicDocs = new ArrayList<>();
            List<Document> splitCandidates = new ArrayList<>();
            for (Document doc : cleanDocs) {
                if (isTableDoc(doc)) {
                    atomicDocs.add(doc);
                } else {
                    splitCandidates.add(doc);
                }
            }

            TokenTextSplitter splitter = new TokenTextSplitter(this.chunkSizeTokens, this.minChunkSizeChars, this.minChunkLengthToEmbed, this.maxNumChunks, this.keepSeparator);
            List<Document> splitDocuments = splitter.apply(splitCandidates);
            if (this.chunkMergeEnabled) {
                splitDocuments = this.mergeSmallChunks(splitDocuments, this.chunkMergeMinTokens, this.chunkMergeMaxTokens);
            }
            if (!atomicDocs.isEmpty()) {
                // Keep table docs atomic (no splitter, no merge).
                List<Document> combined = new ArrayList<>(splitDocuments.size() + atomicDocs.size());
                combined.addAll(splitDocuments);
                combined.addAll(atomicDocs);
                splitDocuments = combined;
            }
            this.assignChunkIndices(splitDocuments);
            List<Document> finalDocuments = new ArrayList<>();
            int totalRedactions = 0;
            for (Document doc : splitDocuments) {
                PiiRedactionService.RedactionResult result = this.piiRedactionService.redact(doc.getContent(), hipaaStrict ? Boolean.TRUE : null);
                Document redactedDoc = new Document(result.getRedactedContent(), doc.getMetadata());
                totalRedactions += result.getTotalRedactions();
                finalDocuments.add(redactedDoc);
            }
            boolean vectorStoreWritten = false;
            try {
                this.partitionAssigner.assignBatch(finalDocuments);
                this.vectorStore.add(finalDocuments);
                vectorStoreWritten = true;
                if (this.hyperGraphMemory != null && this.hyperGraphMemory.isIndexingEnabled()) {
                    for (Document doc : finalDocuments) {
                        this.hyperGraphMemory.indexDocument(doc, dept.name());
                    }
                }
                if (this.miARagService != null && this.miARagService.isEnabled() && finalDocuments.size() >= this.minChunksForMindscape) {
                    List<String> chunks = finalDocuments.stream().map(Document::getContent).toList();
                    this.miARagService.buildMindscape(chunks, filename, dept.name());
                }
            } catch (RuntimeException e) {
                if (vectorStoreWritten) {
                    throw new NonRetriableIngestionException("Post-write ingestion step failed after vector persistence", e);
                }
                throw e;
            }
            if (log.isInfoEnabled()) {
                log.info("Securely ingested {} memory points. Total PII redactions: {}", finalDocuments.size(), totalRedactions);
            }
        }
        catch (IOException e) {
            throw new SecureIngestionException("Secure Ingestion Failed", e);
        }
    }

    private List<Document> mergeSmallChunks(List<Document> docs, int minTokens, int maxTokens) {
        if (docs == null || docs.size() < 2) {
            return docs;
        }
        List<Integer> tokenCounts = new ArrayList<>(docs.size());
        for (Document doc : docs) {
            tokenCounts.add(this.countTokens(doc.getContent()));
        }

        List<Document> merged = new ArrayList<>();
        int i = 0;
        while (i < docs.size()) {
            Document base = docs.get(i);
            String groupKey = this.chunkGroupKey(base);
            StringBuilder content = new StringBuilder(Objects.toString(base.getContent(), ""));
            Map<String, Object> meta = new HashMap<>(base.getMetadata());
            int tokens = tokenCounts.get(i);

            int j = i + 1;
            while (tokens < minTokens && j < docs.size() && groupKey.equals(this.chunkGroupKey(docs.get(j)))) {
                int nextTokens = tokenCounts.get(j);
                if (tokens + nextTokens > maxTokens) {
                    break;
                }
                content.append("\n\n").append(docs.get(j).getContent());
                tokens += nextTokens;
                j++;
            }

            boolean atGroupEnd = j >= docs.size() || !groupKey.equals(this.chunkGroupKey(docs.get(j)));
            if (tokens < minTokens && atGroupEnd && !merged.isEmpty() && groupKey.equals(this.chunkGroupKey(merged.get(merged.size() - 1)))) {
                Document prev = merged.remove(merged.size() - 1);
                int prevTokens = this.countTokens(prev.getContent());
                if (prevTokens + tokens <= maxTokens) {
                    String combined = Objects.toString(prev.getContent(), "") + "\n\n" + content;
                    merged.add(new Document(combined, new HashMap<>(prev.getMetadata())));
                } else {
                    merged.add(prev);
                    merged.add(new Document(content.toString(), meta));
                }
            } else {
                merged.add(new Document(content.toString(), meta));
            }
            i = j;
        }
        return merged;
    }

    private static boolean isTableDoc(Document doc) {
        if (doc == null) {
            return false;
        }
        Map<String, Object> meta = doc.getMetadata();
        if (meta == null) {
            return false;
        }
        Object type = meta.get("type");
        return type != null && "table".equalsIgnoreCase(type.toString());
    }

    private int countTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return TOKEN_ENCODING.encode(text).size();
    }

    private String chunkGroupKey(Document doc) {
        if (doc == null) {
            return "";
        }
        Map<String, Object> meta = doc.getMetadata();
        if (meta == null) {
            return "";
        }
        // Never merge across pages when page metadata exists.
        Object source = meta.get("source");
        Object dept = meta.get("dept");
        Object workspaceId = meta.get("workspaceId");
        Object pageNumber = meta.get("page_number");
        Object endPageNumber = meta.get("end_page_number");
        Object ocr = meta.get("ocr");
        Object type = meta.get("type");
        Object tableIndex = meta.get("table_index");
        return Objects.toString(source, "")
                + "|" + Objects.toString(dept, "")
                + "|" + Objects.toString(workspaceId, "")
                + "|p=" + Objects.toString(pageNumber, "")
                + "|ep=" + Objects.toString(endPageNumber, "")
                + "|ocr=" + Objects.toString(ocr, "")
                + "|type=" + Objects.toString(type, "")
                + "|tbl=" + Objects.toString(tableIndex, "");
    }

    private void assignChunkIndices(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return;
        }
        Map<String, Integer> fileCounters = new HashMap<>();
        Map<String, Integer> pageCounters = new HashMap<>();
        for (Document doc : docs) {
            Map<String, Object> meta = doc.getMetadata();
            if (meta == null) {
                continue;
            }
            String fileKey = Objects.toString(meta.get("source"), "") + "|" + Objects.toString(meta.get("dept"), "") + "|" + Objects.toString(meta.get("workspaceId"), "");
            int fileIndex = fileCounters.getOrDefault(fileKey, 0);
            fileCounters.put(fileKey, fileIndex + 1);
            meta.put("chunk_index", fileIndex);

            String pageKey = fileKey + "|p=" + Objects.toString(meta.get("page_number"), "");
            int pageIndex = pageCounters.getOrDefault(pageKey, 0);
            pageCounters.put(pageKey, pageIndex + 1);
            meta.put("page_chunk_index", pageIndex);
        }
    }

    private String detectMimeType(byte[] bytes, String filename) throws IOException {
        try (BufferedInputStream is = new BufferedInputStream(new ByteArrayInputStream(bytes));){
            return this.tika.detect((InputStream)is, filename);
        }
    }

    public void ingestBytes(byte[] fileBytes, String filename, Department dept) {
        this.ingestBytes(fileBytes, filename, dept, Map.of());
    }

    public void ingestBytes(byte[] fileBytes, String filename, Department dept, Map<String, Object> additionalMetadata) {
        if (fileBytes == null || fileBytes.length == 0) {
            throw new SecureIngestionException("Empty file payload provided for ingestion.", null);
        }
        String safeName = (filename == null || filename.isBlank()) ? "uploaded.bin" : filename;
        ingest(new InMemoryMultipartFile(safeName, fileBytes), dept, additionalMetadata);
    }

    private Map<String, Object> normalizeAdditionalMetadata(Map<String, Object> additionalMetadata) {
        if (additionalMetadata == null || additionalMetadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new HashMap<>();
        for (Map.Entry<String, Object> entry : additionalMetadata.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                continue;
            }
            normalized.put(entry.getKey().trim(), entry.getValue());
        }
        return normalized;
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
        String extension = this.getExtension(filename).toLowerCase(Locale.ROOT);
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
        return bytes.length >= 2 && bytes[0] == 0x23 && bytes[1] == 0x21;
    }

    private String getExtension(String filename) {
        if (filename == null) {
            return "";
        }
        int lastDot = filename.lastIndexOf(46);
        return lastDot > 0 ? filename.substring(lastDot + 1) : "";
    }

    private void ingestEmbeddedImages(byte[] fileBytes, String filename, String department, List<Document> rawDocuments) {
        String contextText = rawDocuments.stream()
                .filter(doc -> !isTableDoc(doc))
                .map(Document::getContent)
                .collect(java.util.stream.Collectors.joining("\n\n"));
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
        List<byte[]> images = new ArrayList<>();
        if (fileBytes == null || fileBytes.length == 0) {
            return images;
        }
        try {
            // Prefer PDFBox for embedded-image extraction to avoid Tika/PDFBox version skew.
            try (PDDocument pd = Loader.loadPDF(fileBytes)) {
                for (PDPage page : pd.getPages()) {
                    if (images.size() >= MAX_EMBEDDED_IMAGES) {
                        break;
                    }
                    PDResources resources = page.getResources();
                    if (resources == null) {
                        continue;
                    }
                    this.collectEmbeddedImages(resources, images);
                }
            }
        }
        catch (Exception e) {
            log.warn("Embedded image extraction failed: {}", e.getMessage());
        }
        return images;
    }

    private void collectEmbeddedImages(PDResources resources, List<byte[]> images) throws IOException {
        for (COSName name : resources.getXObjectNames()) {
            if (images.size() >= MAX_EMBEDDED_IMAGES) {
                return;
            }
            PDXObject xObject = resources.getXObject(name);
            if (xObject instanceof PDImageXObject image) {
                java.awt.image.BufferedImage buffered = image.getImage();
                if (buffered == null) {
                    continue;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(buffered, "png", out);
                images.add(out.toByteArray());
            }
            else if (xObject instanceof PDFormXObject form) {
                PDResources formResources = form.getResources();
                if (formResources != null) {
                    this.collectEmbeddedImages(formResources, images);
                }
            }
        }
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

    private void loadCheckpointState() {
        if (this.ingestCheckpointPath == null || this.ingestCheckpointPath.isBlank()) {
            return;
        }
        Path checkpoint = Paths.get(this.ingestCheckpointPath);
        if (!Files.exists(checkpoint)) {
            return;
        }
        synchronized (this.checkpointLock) {
            try {
                Map<String, Object> state = this.objectMapper.readValue(checkpoint.toFile(), new TypeReference<Map<String, Object>>() {});
                this.sessionStartTime = this.readLong(state.get("startTime"), System.currentTimeMillis());
                this.processedCount = this.readInt(state.get("processedCount"), 0);
                this.failedCount = this.readInt(state.get("failedCount"), 0);
                this.securityRejectedCount = this.readInt(state.get("securityRejectedCount"), 0);
                this.lastProcessedDoc = Objects.toString(state.get("lastProcessedDoc"), "");
                this.failedDocs.clear();
                Object docs = state.get("failedDocs");
                if (docs instanceof List<?> list) {
                    for (Object item : list) {
                        if (item != null) {
                            this.failedDocs.add(item.toString());
                        }
                    }
                    this.enforceFailedDocRetentionLimit();
                }
                if (log.isInfoEnabled()) {
                    log.info("Loaded ingestion checkpoint: processed={}, failed={}, securityRejected={}, lastDoc={}",
                            this.processedCount, this.failedCount, this.securityRejectedCount, LogSanitizer.sanitize(this.lastProcessedDoc));
                }
            } catch (Exception e) {
                if (log.isWarnEnabled()) {
                    log.warn("Unable to load ingestion checkpoint '{}': {}", this.ingestCheckpointPath, e.getMessage());
                }
            }
        }
    }

    private void recordIngestionOutcome(String filename, boolean success) {
        if (!this.resilienceEnabled) {
            return;
        }
        synchronized (this.checkpointLock) {
            this.lastProcessedDoc = filename != null ? filename : "";
            if (success) {
                this.processedCount++;
                this.failedDocs.remove(filename);
            } else {
                this.failedCount++;
                if (filename != null && !filename.isBlank() && !this.failedDocs.contains(filename)) {
                    this.failedDocs.add(filename);
                    this.enforceFailedDocRetentionLimit();
                }
            }
            this.persistCheckpointState();
        }
    }

    private void recordSecurityRejection(String filename) {
        if (!this.resilienceEnabled) {
            return;
        }
        synchronized (this.checkpointLock) {
            this.lastProcessedDoc = filename != null ? filename : "";
            this.securityRejectedCount++;
            this.persistCheckpointState();
        }
    }

    private void persistCheckpointState() {
        if (this.ingestCheckpointPath == null || this.ingestCheckpointPath.isBlank()) {
            return;
        }
        Path tempCheckpoint = null;
        try {
            Path checkpoint = Paths.get(this.ingestCheckpointPath);
            Path parent = checkpoint.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Map<String, Object> state = new HashMap<>();
            state.put("startTime", this.sessionStartTime);
            state.put("processedCount", this.processedCount);
            state.put("failedCount", this.failedCount);
            state.put("securityRejectedCount", this.securityRejectedCount);
            state.put("lastProcessedDoc", this.lastProcessedDoc);
            state.put("failedDocs", new ArrayList<>(this.failedDocs));
            tempCheckpoint = checkpoint.resolveSibling(checkpoint.getFileName().toString() + ".tmp");
            this.objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempCheckpoint.toFile(), state);
            try {
                Files.move(tempCheckpoint, checkpoint, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempCheckpoint, checkpoint, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("Unable to persist ingestion checkpoint '{}': {}", this.ingestCheckpointPath, e.getMessage());
            }
        } finally {
            if (tempCheckpoint != null) {
                try {
                    Files.deleteIfExists(tempCheckpoint);
                } catch (Exception ignore) {
                }
            }
        }
    }

    private void enforceFailureThreshold() {
        if (!this.resilienceEnabled || this.ingestFailureThresholdPercent <= 0.0) {
            return;
        }
        synchronized (this.checkpointLock) {
            int total = this.processedCount + this.failedCount;
            if (total < Math.max(1, this.ingestFailureThresholdMinSamples)) {
                return;
            }
            double failureRate = (double) this.failedCount * 100.0 / (double) total;
            if (failureRate > this.ingestFailureThresholdPercent) {
                throw new SecureIngestionException(String.format(
                        "Ingestion halted: failure rate %.1f%% exceeded threshold %.1f%% (processed=%d, failed=%d)",
                        failureRate, this.ingestFailureThresholdPercent, this.processedCount, this.failedCount), null);
            }
        }
    }

    private void enforceFailedDocRetentionLimit() {
        int maxFailedDocs = Math.max(1, this.ingestFailedDocsMax);
        while (this.failedDocs.size() > maxFailedDocs) {
            String oldest = this.failedDocs.iterator().next();
            this.failedDocs.remove(oldest);
        }
    }

    private int readInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (Exception e) {
            return fallback;
        }
    }

    private long readLong(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static final class NonRetriableIngestionException extends RuntimeException {
        private NonRetriableIngestionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
