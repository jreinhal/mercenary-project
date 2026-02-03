package com.jreinhal.mercenary.workspace;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection="workspaces")
public record Workspace(
    @Id String id,
    String name,
    String description,
    String createdBy,
    Instant createdAt,
    Instant updatedAt,
    WorkspaceQuota quota,
    boolean active
) {
    public record WorkspaceQuota(
        int maxDocuments,
        int maxQueriesPerDay,
        int maxStorageMb
    ) {
        public static WorkspaceQuota unlimited() {
            return new WorkspaceQuota(0, 0, 0);
        }

        public boolean hasLimits() {
            return maxDocuments > 0 || maxQueriesPerDay > 0 || maxStorageMb > 0;
        }
    }
}
