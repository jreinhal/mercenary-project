package com.jreinhal.mercenary.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.jreinhal.mercenary.Department;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SourceDocumentService {
    private final Cache<String, byte[]> sourcePdfCache;
    private final HipaaPolicy hipaaPolicy;

    @Value("${sentinel.source-retention.pdf.enabled:true}")
    private boolean sourcePdfRetentionEnabled;

    @Value("${sentinel.source-retention.pdf.allow-hipaa-strict:false}")
    private boolean allowHipaaStrictRetention;

    @Value("${sentinel.source-retention.pdf.max-bytes:52428800}")
    private int maxPdfBytes;

    public SourceDocumentService(Cache<String, byte[]> sourcePdfCache, HipaaPolicy hipaaPolicy) {
        this.sourcePdfCache = sourcePdfCache;
        this.hipaaPolicy = hipaaPolicy;
    }

    public void storePdfSource(String workspaceId, Department department, String filename, byte[] fileBytes) {
        String safeFilename = this.sanitizeFilenameForCache(filename);
        if (!this.sourcePdfRetentionEnabled || workspaceId == null || workspaceId.isBlank() || department == null
                || safeFilename == null || safeFilename.isBlank() || fileBytes == null || fileBytes.length == 0) {
            return;
        }
        if (this.hipaaPolicy.shouldDisableVisual(department) && !this.allowHipaaStrictRetention) {
            return;
        }
        if (fileBytes.length > this.maxPdfBytes) {
            return;
        }
        this.sourcePdfCache.put(this.buildCacheKey(workspaceId, department.name(), safeFilename), fileBytes.clone());
    }

    public Optional<byte[]> getPdfSource(String workspaceId, String department, String filename) {
        String safeFilename = this.sanitizeFilenameForCache(filename);
        if (workspaceId == null || workspaceId.isBlank() || department == null || department.isBlank()
                || safeFilename == null || safeFilename.isBlank()) {
            return Optional.empty();
        }
        byte[] bytes = this.sourcePdfCache.getIfPresent(this.buildCacheKey(workspaceId, department, safeFilename));
        if (bytes == null || bytes.length == 0) {
            return Optional.empty();
        }
        return Optional.of(bytes.clone());
    }

    private String buildCacheKey(String workspaceId, String department, String filename) {
        return workspaceId + ":" + department.trim().toUpperCase(Locale.ROOT) + ":" + filename;
    }

    private String sanitizeFilenameForCache(String filename) {
        if (filename == null || filename.isBlank()) {
            return null;
        }
        String value = filename.trim();
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < value.length()) {
            value = value.substring(slash + 1);
        }
        value = value.replace(':', '_');
        return value.trim();
    }
}
