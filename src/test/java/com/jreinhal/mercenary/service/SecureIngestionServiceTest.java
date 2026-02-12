package com.jreinhal.mercenary.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.rag.hgmem.HyperGraphMemory;
import com.jreinhal.mercenary.rag.megarag.MegaRagService;
import com.jreinhal.mercenary.rag.miarag.MiARagService;
import com.jreinhal.mercenary.rag.ragpart.PartitionAssigner;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SecureIngestionServiceTest {

    @Mock
    private VectorStore vectorStore;

    @Mock
    private PiiRedactionService piiRedactionService;

    @Mock
    private PartitionAssigner partitionAssigner;

    @Mock
    private MiARagService miARagService;

    @Mock
    private MegaRagService megaRagService;

    @Mock
    private HyperGraphMemory hyperGraphMemory;

    @Mock
    private LightOnOcrService lightOnOcrService;

    @Mock
    private TableExtractor tableExtractor;
    @Mock
    private SourceDocumentService sourceDocumentService;

    @Mock
    private HipaaPolicy hipaaPolicy;
    @Mock
    private com.jreinhal.mercenary.workspace.WorkspaceQuotaService workspaceQuotaService;

    private SecureIngestionService ingestionService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(miARagService.isEnabled()).thenReturn(false);
        when(megaRagService.isEnabled()).thenReturn(false);
        when(hyperGraphMemory.isIndexingEnabled()).thenReturn(false);
        when(lightOnOcrService.isEnabled()).thenReturn(false);
        when(tableExtractor.isEnabled()).thenReturn(false);
        when(hipaaPolicy.isStrict(any(Department.class))).thenReturn(false);
        when(hipaaPolicy.shouldDisableVisual(any(Department.class))).thenReturn(false);
        ingestionService = new SecureIngestionService(vectorStore, piiRedactionService, partitionAssigner, miARagService, megaRagService, hyperGraphMemory, lightOnOcrService, tableExtractor, sourceDocumentService, hipaaPolicy, workspaceQuotaService);
    }

    @Test
    @DisplayName("Should accept text files")
    void shouldAcceptTextFiles() {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "document.txt",
            "text/plain",
            "This is a test document with normal content.".getBytes()
        );

        when(piiRedactionService.redact(anyString(), any()))
            .thenReturn(new PiiRedactionService.RedactionResult(
                "This is a test document with normal content.",
                java.util.Collections.emptyMap()
            ));

        // Should not throw
        assertDoesNotThrow(() -> ingestionService.ingest(file, Department.ENTERPRISE));
    }

    @Test
    @DisplayName("Should retain source PDF bytes for on-demand page rendering")
    void shouldRetainSourcePdfBytes() throws Exception {
        byte[] pdfBytes = buildBlankPdf();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "source.pdf",
                "application/pdf",
                pdfBytes
        );
        when(piiRedactionService.redact(anyString(), any()))
                .thenAnswer(invocation -> new PiiRedactionService.RedactionResult(
                        invocation.getArgument(0),
                        java.util.Collections.emptyMap()
                ));

        assertDoesNotThrow(() -> ingestionService.ingest(file, Department.ENTERPRISE));
        verify(sourceDocumentService).storePdfSource(anyString(), eq(Department.ENTERPRISE), eq("source.pdf"), any(byte[].class));
    }

    @Test
    @DisplayName("Should stamp documentYear metadata onto ingested chunks when a date is present")
    void shouldStampDocumentYearWhenDatePresent() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "dated.txt",
                "text/plain",
                "Report Date: 2021-05-03\nThis is a test document.".getBytes(StandardCharsets.UTF_8)
        );

        when(piiRedactionService.redact(anyString(), any()))
                .thenAnswer(invocation -> new PiiRedactionService.RedactionResult(
                        invocation.getArgument(0),
                        java.util.Collections.emptyMap()
                ));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass((Class) List.class);
        doNothing().when(vectorStore).add(captor.capture());

        assertDoesNotThrow(() -> ingestionService.ingest(file, Department.ENTERPRISE));

        List<Document> added = captor.getValue();
        assertNotNull(added);
        assertFalse(added.isEmpty());
        Document first = added.get(0);
        assertNotNull(first.getMetadata());
        assertEquals(2021, first.getMetadata().get("documentYear"));
        assertNotNull(first.getMetadata().get("documentDateSource"));
    }

    @Test
    @DisplayName("Should keep table documents atomic (not split/merged) when table extraction adds them")
    void shouldKeepTableDocsAtomicWhenPresent() throws Exception {
        // Make a PDF with enough text so it is not considered scanned.
        byte[] pdfBytes = buildPdfWithText("x".repeat(400));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "tables.pdf",
                "application/pdf",
                pdfBytes
        );

        // Return one synthetic table doc (already markdown) and ensure it bypasses splitting.
        when(tableExtractor.isEnabled()).thenReturn(true);
        Document tableDoc = new Document("| A | B |\n| --- | --- |\n| 1 | 2 |",
                Map.of("type", "table", "page_number", 1, "table_index", 0));
        when(tableExtractor.extractTables(any(), eq("tables.pdf"))).thenReturn(List.of(tableDoc));

        when(piiRedactionService.redact(anyString(), any()))
                .thenAnswer(invocation -> new PiiRedactionService.RedactionResult(
                        invocation.getArgument(0),
                        java.util.Collections.emptyMap()
                ));

        AtomicReference<List<Document>> captured = new AtomicReference<>();
        doAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return null;
        }).when(vectorStore).add(anyList());

        assertDoesNotThrow(() -> ingestionService.ingest(file, Department.ENTERPRISE));

        List<Document> added = captured.get();
        assertNotNull(added);
        assertFalse(added.isEmpty());

        Document addedTable = added.stream()
                .filter(d -> d.getMetadata() != null && "table".equalsIgnoreCase(String.valueOf(d.getMetadata().get("type"))))
                .findFirst()
                .orElse(null);
        assertNotNull(addedTable, "Expected at least one table document to be added to the vector store");
        assertTrue(addedTable.getContent().contains("| A | B |"));
        assertEquals(1, addedTable.getMetadata().get("page_number"));
        assertEquals(0, addedTable.getMetadata().get("table_index"));
    }

    @Test
    @DisplayName("Should attempt table extraction even when PDF text is below scanned threshold (short text-layer PDFs)")
    void shouldAttemptTableExtractionForShortTextPdf() throws Exception {
        // Below MIN_TEXT_LENGTH_FOR_VALID_PDF (100) but still text-layer.
        byte[] pdfBytes = buildPdfWithText("x".repeat(50));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "short.pdf",
                "application/pdf",
                pdfBytes
        );

        when(tableExtractor.isEnabled()).thenReturn(true);
        when(tableExtractor.extractTables(any(), eq("short.pdf")))
                .thenReturn(List.of(new Document("| A | B |\n| --- | --- |\n| 1 | 2 |", Map.of("type", "table"))));

        when(piiRedactionService.redact(anyString(), any()))
                .thenAnswer(invocation -> new PiiRedactionService.RedactionResult(
                        invocation.getArgument(0),
                        java.util.Collections.emptyMap()
                ));

        assertDoesNotThrow(() -> ingestionService.ingest(file, Department.ENTERPRISE));
        verify(tableExtractor, times(1)).extractTables(any(), eq("short.pdf"));
    }

    @Test
    @DisplayName("Should not attempt table extraction when PDF has no extractable text")
    void shouldNotAttemptTableExtractionWhenPdfHasNoText() throws Exception {
        byte[] pdfBytes = buildBlankPdf();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "blank.pdf",
                "application/pdf",
                pdfBytes
        );

        when(tableExtractor.isEnabled()).thenReturn(true);
        when(piiRedactionService.redact(anyString(), any()))
                .thenAnswer(invocation -> new PiiRedactionService.RedactionResult(
                        invocation.getArgument(0),
                        java.util.Collections.emptyMap()
                ));

        assertDoesNotThrow(() -> ingestionService.ingest(file, Department.ENTERPRISE));
        verify(tableExtractor, never()).extractTables(any(), any());
    }

    @Test
    @DisplayName("Embedded image ingestion should build context excluding table documents")
    void embeddedImageIngestionShouldExcludeTableDocsFromContext() throws Exception {
        byte[] pdfBytes = buildPdfWithEmbeddedImage();
        List<Document> rawDocs = List.of(
                new Document("TEXT_CONTEXT", Map.of("source", "x.pdf")),
                new Document("TABLE_CONTEXT", Map.of("type", "table"))
        );

        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(
                ingestionService,
                "ingestEmbeddedImages",
                pdfBytes,
                "x.pdf",
                "ENTERPRISE",
                rawDocs
        ));

        ArgumentCaptor<String> contextCaptor = ArgumentCaptor.forClass(String.class);
        verify(megaRagService, atLeastOnce()).ingestVisualAsset(any(), eq("x.pdf"), eq("ENTERPRISE"), contextCaptor.capture());
        String context = contextCaptor.getValue();
        assertTrue(context.contains("TEXT_CONTEXT"));
        assertFalse(context.contains("TABLE_CONTEXT"));
    }

    @Test
    void extractEmbeddedImagesReturnsEmptyForNullBytes() {
        @SuppressWarnings("unchecked")
        List<byte[]> images = ReflectionTestUtils.invokeMethod(ingestionService, "extractEmbeddedImages", (Object) null);
        assertNotNull(images);
        assertTrue(images.isEmpty());
    }

    @Test
    void extractEmbeddedImagesReturnsEmptyForBlankPdf() throws Exception {
        byte[] pdfBytes = buildBlankPdf();
        @SuppressWarnings("unchecked")
        List<byte[]> images = ReflectionTestUtils.invokeMethod(ingestionService, "extractEmbeddedImages", pdfBytes);
        assertNotNull(images);
        assertTrue(images.isEmpty());
    }

    @Test
    @DisplayName("Should block executable files by extension")
    void shouldBlockExecutableFilesByExtension() {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "malware.exe",
            "application/octet-stream",
            "fake executable content".getBytes()
        );

        SecurityException exception = assertThrows(
            SecurityException.class,
            () -> ingestionService.ingest(file, Department.ENTERPRISE)
        );

        assertTrue(exception.getMessage().contains("not allowed") ||
                   exception.getMessage().contains("Executable"));
    }

    @Test
    @DisplayName("Should block .bat script files")
    void shouldBlockBatFiles() {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "script.bat",
            "application/x-bat",
            "@echo off\ndel /f /q *.*".getBytes()
        );

        assertThrows(SecurityException.class,
            () -> ingestionService.ingest(file, Department.ENTERPRISE));
    }

    @Test
    @DisplayName("Should block .sh shell script files")
    void shouldBlockShellScripts() {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "script.sh",
            "application/x-sh",
            "#!/bin/bash\nrm -rf /".getBytes()
        );

        assertThrows(SecurityException.class,
            () -> ingestionService.ingest(file, Department.ENTERPRISE));
    }

    @Test
    @DisplayName("Should block .jar files")
    void shouldBlockJarFiles() {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "malicious.jar",
            "application/java-archive",
            "fake jar content".getBytes()
        );

        assertThrows(SecurityException.class,
            () -> ingestionService.ingest(file, Department.ENTERPRISE));
    }

    @Test
    @DisplayName("Should block .ps1 PowerShell files")
    void shouldBlockPowerShellFiles() {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "malware.ps1",
            "application/x-powershell",
            "Remove-Item * -Force -Recurse".getBytes()
        );

        assertThrows(SecurityException.class,
            () -> ingestionService.ingest(file, Department.ENTERPRISE));
    }

    @Test
    @DisplayName("Should block .dll files")
    void shouldBlockDllFiles() {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "malware.dll",
            "application/x-msdownload",
            "fake dll content".getBytes()
        );

        assertThrows(SecurityException.class,
            () -> ingestionService.ingest(file, Department.ENTERPRISE));
    }

    @Test
    @DisplayName("Should block .cmd files")
    void shouldBlockCmdFiles() {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "script.cmd",
            "application/x-bat",
            "del /f /q *.*".getBytes()
        );

        assertThrows(SecurityException.class,
            () -> ingestionService.ingest(file, Department.ENTERPRISE));
    }

    @Test
    @DisplayName("Should apply PII redaction during ingestion")
    void shouldApplyPiiRedactionDuringIngestion() {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "document.txt",
            "text/plain",
            "SSN: 123-45-6789".getBytes()
        );

        when(piiRedactionService.redact(anyString(), any()))
            .thenReturn(new PiiRedactionService.RedactionResult(
                "SSN: [REDACTED-SSN]",
                java.util.Map.of(PiiRedactionService.PiiType.SSN, 1)
            ));

        assertDoesNotThrow(() -> ingestionService.ingest(file, Department.ENTERPRISE));

        verify(piiRedactionService, atLeastOnce()).redact(anyString(), any());
    }

    @Test
    @DisplayName("Should tag documents with department/sector")
    void shouldTagDocumentsWithDepartment() {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "classified.txt",
            "text/plain",
            "Classified government document content.".getBytes()
        );

        when(piiRedactionService.redact(anyString(), any()))
            .thenReturn(new PiiRedactionService.RedactionResult(
                "Classified government document content.",
                java.util.Collections.emptyMap()
            ));

        assertDoesNotThrow(() -> ingestionService.ingest(file, Department.GOVERNMENT));

        // Verify vector store was called (documents were added)
        verify(vectorStore, atLeastOnce()).add(anyList());
    }

    @Test
    @DisplayName("Should assign chunk indices to ingested documents")
    void shouldAssignChunkIndices() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2500; i++) {
            sb.append("word").append(i).append(' ');
        }
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "long.txt",
                "text/plain",
                sb.toString().getBytes(StandardCharsets.UTF_8)
        );

        when(piiRedactionService.redact(anyString(), any()))
                .thenAnswer(invocation -> new PiiRedactionService.RedactionResult(
                        invocation.getArgument(0),
                        java.util.Collections.emptyMap()
                ));

        AtomicReference<List<Document>> captured = new AtomicReference<>();
        doAnswer(invocation -> {
            List<Document> docs = invocation.getArgument(0);
            captured.set(docs);
            return null;
        }).when(vectorStore).add(anyList());

        assertDoesNotThrow(() -> ingestionService.ingest(file, Department.ENTERPRISE));

        List<Document> added = captured.get();
        assertNotNull(added);
        assertFalse(added.isEmpty());

        for (Document doc : added) {
            assertNotNull(doc.getMetadata());
            assertTrue(doc.getMetadata().containsKey("chunk_index"));
            assertTrue(doc.getMetadata().containsKey("page_chunk_index"));
        }
    }

    @Test
    @DisplayName("Should merge small chunks forward but respect max token guardrails")
    void shouldMergeSmallChunksWithMaxTokenGuardrails() {
        // Force many small chunks so mergeSmallChunks exercises forward-merge + max-token break paths.
        ReflectionTestUtils.setField(ingestionService, "chunkSizeTokens", 200);
        ReflectionTestUtils.setField(ingestionService, "chunkMergeEnabled", true);
        // Use a realistic configuration: min just above one chunk, max below the sum of two chunks.
        ReflectionTestUtils.setField(ingestionService, "chunkMergeMinTokens", 210);
        ReflectionTestUtils.setField(ingestionService, "chunkMergeMaxTokens", 380);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 450; i++) {
            sb.append("hello ");
        }

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "merge_test.txt",
                "text/plain",
                sb.toString().getBytes(StandardCharsets.UTF_8)
        );

        when(piiRedactionService.redact(anyString(), any()))
                .thenAnswer(invocation -> new PiiRedactionService.RedactionResult(
                        invocation.getArgument(0),
                        java.util.Collections.emptyMap()
                ));

        AtomicReference<List<Document>> captured = new AtomicReference<>();
        doAnswer(invocation -> {
            List<Document> docs = invocation.getArgument(0);
            captured.set(docs);
            return null;
        }).when(vectorStore).add(anyList());

        assertDoesNotThrow(() -> ingestionService.ingest(file, Department.ENTERPRISE));

        List<Document> added = captured.get();
        assertNotNull(added);
        assertFalse(added.isEmpty());

        // Our synthetic input contains no newlines; merged chunks will contain \n\n separators inserted by mergeSmallChunks.
        assertTrue(added.stream().anyMatch(d -> d.getContent() != null && d.getContent().contains("\n\n")));
    }

    @Test
    @DisplayName("Helper methods should tolerate null/empty inputs")
    void helperMethodsShouldTolerateNullOrEmptyInputs() {
        Integer tokens = ReflectionTestUtils.invokeMethod(ingestionService, "countTokens", " ");
        assertNotNull(tokens);
        assertEquals(0, tokens.intValue());

        String key = ReflectionTestUtils.invokeMethod(ingestionService, "chunkGroupKey", (Object) null);
        assertEquals("", key);

        // Should be no-op (not throw)
        ReflectionTestUtils.invokeMethod(ingestionService, "assignChunkIndices", (Object) null);
        ReflectionTestUtils.invokeMethod(ingestionService, "assignChunkIndices", List.of());
    }

    // ─── Fix #4: Magic byte detection for archive/container formats ───

    @Test
    @DisplayName("Fix #4: Should block ZIP archives disguised as .txt")
    void shouldBlockZipDisguisedAsTxt() {
        // ZIP magic bytes: PK\x03\x04
        byte[] zipMagic = new byte[]{0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        MockMultipartFile file = new MockMultipartFile(
            "file", "fake_zip.txt", "text/plain", zipMagic);

        assertThrows(SecurityException.class,
            () -> ingestionService.ingest(file, Department.ENTERPRISE),
            "ZIP archive disguised as .txt should be blocked");
    }

    @Test
    @DisplayName("Fix #4: Should block Java class files disguised as .txt")
    void shouldBlockJavaClassDisguisedAsTxt() {
        // Java class magic bytes: 0xCAFEBABE
        byte[] classMagic = new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE,
            0x00, 0x00, 0x00, 0x34, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        MockMultipartFile file = new MockMultipartFile(
            "file", "fake_class.txt", "text/plain", classMagic);

        assertThrows(SecurityException.class,
            () -> ingestionService.ingest(file, Department.ENTERPRISE),
            "Java class file disguised as .txt should be blocked");
    }

    @Test
    @DisplayName("Fix #4: Should block PDF files disguised as .txt")
    void shouldBlockPdfDisguisedAsTxt() {
        // PDF magic bytes: %PDF-1.4
        byte[] pdfMagic = "%PDF-1.4 fake content that is not actually a pdf".getBytes();
        MockMultipartFile file = new MockMultipartFile(
            "file", "fake_pdf.txt", "text/plain", pdfMagic);

        assertThrows(SecurityException.class,
            () -> ingestionService.ingest(file, Department.ENTERPRISE),
            "PDF file disguised as .txt should be blocked");
    }

    @Test
    @DisplayName("Fix #4: Should block RAR archives disguised as .txt")
    void shouldBlockRarDisguisedAsTxt() {
        // RAR magic bytes: Rar!\x1a\x07\x00
        byte[] rarMagic = new byte[]{0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        MockMultipartFile file = new MockMultipartFile(
            "file", "fake_rar.txt", "text/plain", rarMagic);

        assertThrows(SecurityException.class,
            () -> ingestionService.ingest(file, Department.ENTERPRISE),
            "RAR archive disguised as .txt should be blocked");
    }

    @Test
    @DisplayName("Fix #4: Should block shell scripts disguised as .txt")
    void shouldBlockShellScriptDisguisedAsTxt() {
        byte[] shebang = "#!/bin/bash\nrm -rf /\n".getBytes();
        MockMultipartFile file = new MockMultipartFile(
            "file", "fake_sh.txt", "text/plain", shebang);

        assertThrows(SecurityException.class,
            () -> ingestionService.ingest(file, Department.ENTERPRISE),
            "Shell script disguised as .txt should be blocked");
    }

    @Test
    @DisplayName("Fix #4: Should block 7-Zip archives")
    void shouldBlock7ZipArchives() {
        // 7z magic bytes: 7z\xBC\xAF\x27\x1C
        byte[] sevenZMagic = new byte[]{0x37, 0x7A, (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        MockMultipartFile file = new MockMultipartFile(
            "file", "archive.7z", "application/octet-stream", sevenZMagic);

        assertThrows(SecurityException.class,
            () -> ingestionService.ingest(file, Department.ENTERPRISE),
            "7-Zip archive should be blocked");
    }

    @Test
    @DisplayName("Fix #4: Should still accept legitimate text files")
    void shouldStillAcceptLegitimateTextFiles() {
        MockMultipartFile file = new MockMultipartFile(
            "file", "report.txt", "text/plain",
            "This is a legitimate text report with normal ASCII content.".getBytes());

        when(piiRedactionService.redact(anyString(), any()))
            .thenReturn(new PiiRedactionService.RedactionResult(
                "This is a legitimate text report with normal ASCII content.",
                java.util.Collections.emptyMap()));

        assertDoesNotThrow(() -> ingestionService.ingest(file, Department.ENTERPRISE),
            "Legitimate text files should still be accepted after blocklist expansion");
    }

    @Test
    @DisplayName("Phase 5.3: Retries ingestion once on transient vector-store failure")
    void retriesOnTransientVectorStoreFailure() throws Exception {
        Path checkpoint = Files.createTempFile("ingest-retry-", ".json");
        ReflectionTestUtils.setField(ingestionService, "ingestCheckpointPath", checkpoint.toString());
        ReflectionTestUtils.setField(ingestionService, "resilienceEnabled", true);
        ReflectionTestUtils.setField(ingestionService, "ingestMaxRetries", 1);

        MockMultipartFile file = new MockMultipartFile(
                "file", "retry.txt", "text/plain", "retry content".getBytes(StandardCharsets.UTF_8));
        when(piiRedactionService.redact(anyString(), any()))
                .thenAnswer(invocation -> new PiiRedactionService.RedactionResult(
                        invocation.getArgument(0), java.util.Collections.emptyMap()));
        doThrow(new RuntimeException("transient"))
                .doNothing()
                .when(vectorStore).add(anyList());

        assertDoesNotThrow(() -> ingestionService.ingest(file, Department.ENTERPRISE));
        verify(vectorStore, times(2)).add(anyList());

        Map<String, Object> state = new ObjectMapper().readValue(checkpoint.toFile(), new TypeReference<Map<String, Object>>() {});
        assertEquals(1, ((Number) state.get("processedCount")).intValue());
        assertEquals(0, ((Number) state.get("failedCount")).intValue());
    }

    @Test
    @DisplayName("Phase 5.3: Writes failed document checkpoint when retries are exhausted")
    void writesCheckpointForFailedIngestion() throws Exception {
        Path checkpoint = Files.createTempFile("ingest-fail-", ".json");
        ReflectionTestUtils.setField(ingestionService, "ingestCheckpointPath", checkpoint.toString());
        ReflectionTestUtils.setField(ingestionService, "resilienceEnabled", true);
        ReflectionTestUtils.setField(ingestionService, "ingestMaxRetries", 0);

        MockMultipartFile file = new MockMultipartFile(
                "file", "failed.txt", "text/plain", "failure content".getBytes(StandardCharsets.UTF_8));
        when(piiRedactionService.redact(anyString(), any()))
                .thenAnswer(invocation -> new PiiRedactionService.RedactionResult(
                        invocation.getArgument(0), java.util.Collections.emptyMap()));
        doThrow(new RuntimeException("persistent")).when(vectorStore).add(anyList());

        assertThrows(RuntimeException.class, () -> ingestionService.ingest(file, Department.ENTERPRISE));

        Map<String, Object> state = new ObjectMapper().readValue(checkpoint.toFile(), new TypeReference<Map<String, Object>>() {});
        assertEquals(0, ((Number) state.get("processedCount")).intValue());
        assertEquals(1, ((Number) state.get("failedCount")).intValue());
        @SuppressWarnings("unchecked")
        List<String> failedDocs = (List<String>) state.get("failedDocs");
        assertTrue(failedDocs.contains("failed.txt"));
    }

    @Test
    @DisplayName("Phase 5.3: Failure threshold blocks ingestion when exceeded")
    void failureThresholdBlocksFurtherIngestion() {
        ReflectionTestUtils.setField(ingestionService, "resilienceEnabled", true);
        ReflectionTestUtils.setField(ingestionService, "ingestFailureThresholdPercent", 10.0);
        ReflectionTestUtils.setField(ingestionService, "ingestFailureThresholdMinSamples", 1);
        ReflectionTestUtils.setField(ingestionService, "processedCount", 0);
        ReflectionTestUtils.setField(ingestionService, "failedCount", 1);

        MockMultipartFile file = new MockMultipartFile(
                "file", "blocked.txt", "text/plain", "blocked content".getBytes(StandardCharsets.UTF_8));

        assertThrows(SecureIngestionException.class, () -> ingestionService.ingest(file, Department.ENTERPRISE));
        verify(vectorStore, never()).add(anyList());
    }

    private static byte[] buildPdfWithText(String text) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] buildBlankPdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] buildPdfWithEmbeddedImage() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);

            BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            img.setRGB(0, 0, 0x00FF00);
            img.setRGB(1, 1, 0x00FF00);
            PDImageXObject pdImage = LosslessFactory.createFromImage(doc, img);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(pdImage, 50, 650, 50, 50);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
