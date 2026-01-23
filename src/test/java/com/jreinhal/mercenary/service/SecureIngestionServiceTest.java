package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.rag.hgmem.HyperGraphMemory;
import com.jreinhal.mercenary.rag.megarag.MegaRagService;
import com.jreinhal.mercenary.rag.miarag.MiARagService;
import com.jreinhal.mercenary.rag.ragpart.PartitionAssigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.mock.web.MockMultipartFile;

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

    private SecureIngestionService ingestionService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(miARagService.isEnabled()).thenReturn(false);
        when(megaRagService.isEnabled()).thenReturn(false);
        when(hyperGraphMemory.isEnabled()).thenReturn(false);
        ingestionService = new SecureIngestionService(vectorStore, piiRedactionService, partitionAssigner, miARagService, megaRagService, hyperGraphMemory);
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

        when(piiRedactionService.redact(anyString()))
            .thenReturn(new PiiRedactionService.RedactionResult(
                "This is a test document with normal content.",
                java.util.Collections.emptyMap()
            ));

        // Should not throw
        assertDoesNotThrow(() -> ingestionService.ingest(file, Department.ENTERPRISE));
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

        when(piiRedactionService.redact(anyString()))
            .thenReturn(new PiiRedactionService.RedactionResult(
                "SSN: [REDACTED-SSN]",
                java.util.Map.of(PiiRedactionService.PiiType.SSN, 1)
            ));

        assertDoesNotThrow(() -> ingestionService.ingest(file, Department.ENTERPRISE));

        verify(piiRedactionService, atLeastOnce()).redact(anyString());
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

        when(piiRedactionService.redact(anyString()))
            .thenReturn(new PiiRedactionService.RedactionResult(
                "Classified government document content.",
                java.util.Collections.emptyMap()
            ));

        assertDoesNotThrow(() -> ingestionService.ingest(file, Department.GOVERNMENT));

        // Verify vector store was called (documents were added)
        verify(vectorStore, atLeastOnce()).add(anyList());
    }
}
