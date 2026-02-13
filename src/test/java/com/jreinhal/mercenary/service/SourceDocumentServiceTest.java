package com.jreinhal.mercenary.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jreinhal.mercenary.Department;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

class SourceDocumentServiceTest {

    @Mock
    private HipaaPolicy hipaaPolicy;

    private SourceDocumentService sourceDocumentService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        Cache<String, byte[]> cache = Caffeine.newBuilder().maximumSize(10).build();
        sourceDocumentService = new SourceDocumentService(cache, hipaaPolicy);
        ReflectionTestUtils.setField(sourceDocumentService, "sourcePdfRetentionEnabled", true);
        ReflectionTestUtils.setField(sourceDocumentService, "allowHipaaStrictRetention", false);
        ReflectionTestUtils.setField(sourceDocumentService, "maxPdfBytes", 1024);
    }

    @Test
    void storesAndLoadsPdfBytesWhenPolicyAllows() {
        when(hipaaPolicy.shouldDisableVisual(any(Department.class))).thenReturn(false);
        byte[] source = new byte[]{1, 2, 3, 4};

        sourceDocumentService.storePdfSource("ws", Department.ENTERPRISE, "file.pdf", source);
        Optional<byte[]> loaded = sourceDocumentService.getPdfSource("ws", "ENTERPRISE", "file.pdf");

        assertTrue(loaded.isPresent());
        assertArrayEquals(source, loaded.get());
    }

    @Test
    void doesNotStoreSourceWhenHipaaStrictRetentionDisabled() {
        when(hipaaPolicy.shouldDisableVisual(Department.MEDICAL)).thenReturn(true);
        sourceDocumentService.storePdfSource("ws", Department.MEDICAL, "phi.pdf", new byte[]{1, 2, 3});

        Optional<byte[]> loaded = sourceDocumentService.getPdfSource("ws", "MEDICAL", "phi.pdf");
        assertFalse(loaded.isPresent());
    }

    @Test
    void doesNotStoreSourceWhenPdfExceedsConfiguredMax() {
        when(hipaaPolicy.shouldDisableVisual(any(Department.class))).thenReturn(false);
        byte[] oversized = new byte[2048];

        sourceDocumentService.storePdfSource("ws", Department.ENTERPRISE, "big.pdf", oversized);
        Optional<byte[]> loaded = sourceDocumentService.getPdfSource("ws", "ENTERPRISE", "big.pdf");

        assertFalse(loaded.isPresent());
    }

    @Test
    void doesNotStoreSourceWhenRetentionDisabled() {
        ReflectionTestUtils.setField(sourceDocumentService, "sourcePdfRetentionEnabled", false);
        sourceDocumentService.storePdfSource("ws", Department.ENTERPRISE, "file.pdf", new byte[]{1, 2, 3});
        assertFalse(sourceDocumentService.getPdfSource("ws", "ENTERPRISE", "file.pdf").isPresent());
    }

    @Test
    void storesSourceInHipaaStrictWhenExplicitlyAllowed() {
        ReflectionTestUtils.setField(sourceDocumentService, "allowHipaaStrictRetention", true);
        when(hipaaPolicy.shouldDisableVisual(eq(Department.MEDICAL))).thenReturn(true);

        sourceDocumentService.storePdfSource("ws", Department.MEDICAL, "allowed.pdf", new byte[]{7, 8, 9});
        assertTrue(sourceDocumentService.getPdfSource("ws", "MEDICAL", "allowed.pdf").isPresent());
    }

    @Test
    void returnsEmptyForInvalidLookupInputs() {
        assertFalse(sourceDocumentService.getPdfSource("", "ENTERPRISE", "file.pdf").isPresent());
        assertFalse(sourceDocumentService.getPdfSource("ws", "", "file.pdf").isPresent());
        assertFalse(sourceDocumentService.getPdfSource("ws", "ENTERPRISE", "").isPresent());
    }

    @Test
    void returnedBytesAreDefensiveCopies() {
        when(hipaaPolicy.shouldDisableVisual(any(Department.class))).thenReturn(false);
        sourceDocumentService.storePdfSource("ws", Department.ENTERPRISE, "copy.pdf", new byte[]{5, 6, 7});

        byte[] firstRead = sourceDocumentService.getPdfSource("ws", "ENTERPRISE", "copy.pdf").orElseThrow();
        firstRead[0] = 99;
        byte[] secondRead = sourceDocumentService.getPdfSource("ws", "ENTERPRISE", "copy.pdf").orElseThrow();

        assertArrayEquals(new byte[]{5, 6, 7}, secondRead);
    }

    @Test
    void normalizesFilenameForCacheKeyConsistency() {
        when(hipaaPolicy.shouldDisableVisual(any(Department.class))).thenReturn(false);
        sourceDocumentService.storePdfSource("ws", Department.ENTERPRISE, "C:\\docs\\report:2026.pdf", new byte[]{4, 5, 6});

        Optional<byte[]> loaded = sourceDocumentService.getPdfSource("ws", "ENTERPRISE", "report_2026.pdf");
        assertTrue(loaded.isPresent());
        assertArrayEquals(new byte[]{4, 5, 6}, loaded.get());
    }

    @Test
    void removesCachedPdfSource() {
        when(hipaaPolicy.shouldDisableVisual(any(Department.class))).thenReturn(false);
        sourceDocumentService.storePdfSource("ws", Department.ENTERPRISE, "remove.pdf", new byte[]{1, 2, 3});
        assertTrue(sourceDocumentService.getPdfSource("ws", "ENTERPRISE", "remove.pdf").isPresent());

        sourceDocumentService.removePdfSource("ws", Department.ENTERPRISE, "remove.pdf");
        assertFalse(sourceDocumentService.getPdfSource("ws", "ENTERPRISE", "remove.pdf").isPresent());
    }

    @Test
    void removePdfSourceIsNoOpForInvalidInputs() {
        sourceDocumentService.removePdfSource("", Department.ENTERPRISE, "x.pdf");
        sourceDocumentService.removePdfSource("ws", null, "x.pdf");
        sourceDocumentService.removePdfSource("ws", Department.ENTERPRISE, "");
        assertTrue(true);
    }
}
