package com.jreinhal.mercenary.workspace;

import com.jreinhal.mercenary.repository.WorkspaceRepository;
import com.jreinhal.mercenary.workspace.Workspace.WorkspaceQuota;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceQuotaService {
    private static final long BYTES_PER_MB = 1024L * 1024L;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceUsageService usageService;

    public WorkspaceQuotaService(WorkspaceRepository workspaceRepository, WorkspaceUsageService usageService) {
        this.workspaceRepository = workspaceRepository;
        this.usageService = usageService;
    }

    public void enforceQueryQuota(String workspaceId) {
        WorkspaceQuota quota = resolveQuota(workspaceId);
        if (quota.maxQueriesPerDay() <= 0) {
            return;
        }
        WorkspaceUsageService.WorkspaceUsage usage = usageService.getUsage(workspaceId);
        if (usage.queriesToday() + 1 > quota.maxQueriesPerDay()) {
            throw new WorkspaceQuotaExceededException("queries", "Workspace query quota exceeded.");
        }
    }

    public void enforceIngestionQuota(String workspaceId, long fileBytes) {
        WorkspaceQuota quota = resolveQuota(workspaceId);
        WorkspaceUsageService.WorkspaceUsage usage = usageService.getUsage(workspaceId);
        if (quota.maxDocuments() > 0 && usage.documents() + 1 > quota.maxDocuments()) {
            throw new WorkspaceQuotaExceededException("documents", "Workspace document quota exceeded.");
        }
        if (quota.maxStorageMb() > 0) {
            long maxBytes = quota.maxStorageMb() * BYTES_PER_MB;
            long projected = usage.storageBytes() + Math.max(fileBytes, 0);
            if (projected > maxBytes) {
                throw new WorkspaceQuotaExceededException("storage", "Workspace storage quota exceeded.");
            }
        }
    }

    private WorkspaceQuota resolveQuota(String workspaceId) {
        String resolved = workspaceId == null || workspaceId.isBlank()
                ? WorkspaceContext.getDefaultWorkspaceId()
                : workspaceId;
        Optional<Workspace> found = workspaceRepository.findById(resolved);
        Workspace workspace = found != null ? found.orElse(null) : null;
        if (workspace == null || workspace.quota() == null) {
            return WorkspaceQuota.unlimited();
        }
        return workspace.quota();
    }
}
