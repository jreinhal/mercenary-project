package com.jreinhal.mercenary.workspace;

import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.repository.UserRepository;
import com.jreinhal.mercenary.repository.WorkspaceRepository;
import com.jreinhal.mercenary.workspace.Workspace.WorkspaceQuota;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceService {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);
    private static final int MAX_ID_LENGTH = 64;
    private static final String DEFAULT_WORKSPACE_NAME = "Default Workspace";

    private final WorkspaceRepository workspaceRepository;
    private final UserRepository userRepository;
    private final WorkspacePolicy workspacePolicy;

    @Value("${sentinel.workspace.default-id:workspace_default}")
    private String defaultWorkspaceId;

    public WorkspaceService(WorkspaceRepository workspaceRepository, UserRepository userRepository, WorkspacePolicy workspacePolicy) {
        this.workspaceRepository = workspaceRepository;
        this.userRepository = userRepository;
        this.workspacePolicy = workspacePolicy;
    }

    public void ensureDefaultWorkspace() {
        if (this.defaultWorkspaceId == null || this.defaultWorkspaceId.isBlank()) {
            this.defaultWorkspaceId = WorkspaceContext.getDefaultWorkspaceId();
        }
        if (this.workspaceRepository.existsById(this.defaultWorkspaceId)) {
            return;
        }
        Workspace workspace = new Workspace(this.defaultWorkspaceId, DEFAULT_WORKSPACE_NAME, "Default workspace", "system",
                Instant.now(), Instant.now(), WorkspaceQuota.unlimited(), true);
        this.workspaceRepository.save(workspace);
        log.info("Workspace default created: {}", this.defaultWorkspaceId);
    }

    public List<WorkspaceSummary> listWorkspaces(User user) {
        this.ensureDefaultWorkspace();
        if (user == null) {
            return List.of(toSummary(this.workspaceRepository.findById(this.defaultWorkspaceId).orElse(null), 0));
        }
        if (!this.workspacePolicy.allowWorkspaceSwitching()) {
            Workspace defaultWorkspace = this.workspaceRepository.findById(this.defaultWorkspaceId).orElse(null);
            return List.of(toSummary(defaultWorkspace, this.userRepository.countByWorkspaceIdsContaining(this.defaultWorkspaceId)));
        }
        if (user.hasRole(com.jreinhal.mercenary.model.UserRole.ADMIN)) {
            return this.workspaceRepository.findAll().stream()
                    .map(workspace -> toSummary(workspace, this.userRepository.countByWorkspaceIdsContaining(workspace.id())))
                    .toList();
        }
        Set<String> workspaceIds = user.getWorkspaceIds() == null ? Set.of() : user.getWorkspaceIds();
        if (workspaceIds.isEmpty()) {
            Workspace defaultWorkspace = this.workspaceRepository.findById(this.defaultWorkspaceId).orElse(null);
            return List.of(toSummary(defaultWorkspace, this.userRepository.countByWorkspaceIdsContaining(this.defaultWorkspaceId)));
        }
        return this.workspaceRepository.findByIdIn(workspaceIds).stream()
                .map(workspace -> toSummary(workspace, this.userRepository.countByWorkspaceIdsContaining(workspace.id())))
                .toList();
    }

    public Workspace createWorkspace(WorkspaceCreateRequest request, User actor) {
        assertWorkspaceManagementEnabled();
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("Workspace name is required");
        }
        String id = normalizeWorkspaceId(request.id(), request.name());
        if (this.workspaceRepository.existsById(id)) {
            throw new IllegalArgumentException("Workspace already exists: " + id);
        }
        Instant now = Instant.now();
        Workspace workspace = new Workspace(id, request.name().trim(), optionalText(request.description()),
                actor != null ? actor.getId() : "system", now, now,
                request.quota() != null ? request.quota() : WorkspaceQuota.unlimited(), true);
        Workspace saved = this.workspaceRepository.save(workspace);
        log.info("Workspace created: {} by {}", id, actor != null ? actor.getUsername() : "system");
        return saved;
    }

    public List<WorkspaceMember> listMembers(String workspaceId) {
        assertWorkspaceManagementEnabled();
        String resolvedId = requireWorkspace(workspaceId).id();
        return this.userRepository.findByWorkspaceIdsContaining(resolvedId).stream()
                .map(this::toMember)
                .toList();
    }

    public WorkspaceMember addMember(String workspaceId, WorkspaceMemberRequest request) {
        assertWorkspaceManagementEnabled();
        Workspace workspace = requireWorkspace(workspaceId);
        User user = requireUser(request);
        Set<String> workspaceIds = new HashSet<>(user.getWorkspaceIds() == null ? Set.of() : user.getWorkspaceIds());
        if (workspaceIds.add(workspace.id())) {
            user.setWorkspaceIds(workspaceIds);
            this.userRepository.save(user);
            log.info("Added user {} to workspace {}", user.getUsername(), workspace.id());
        }
        return toMember(user);
    }

    public boolean removeMember(String workspaceId, String userId) {
        assertWorkspaceManagementEnabled();
        Workspace workspace = requireWorkspace(workspaceId);
        return this.userRepository.findById(userId).map(user -> {
            Set<String> workspaceIds = new HashSet<>(user.getWorkspaceIds() == null ? Set.of() : user.getWorkspaceIds());
            if (workspaceIds.remove(workspace.id())) {
                user.setWorkspaceIds(workspaceIds);
                this.userRepository.save(user);
                log.info("Removed user {} from workspace {}", user.getUsername(), workspace.id());
            }
            return true;
        }).orElse(false);
    }

    public Workspace updateQuota(String workspaceId, WorkspaceQuota quota, User actor) {
        assertWorkspaceManagementEnabled();
        Workspace workspace = requireWorkspace(workspaceId);
        WorkspaceQuota sanitized = sanitizeQuota(quota);
        Instant now = Instant.now();
        Workspace updated = new Workspace(workspace.id(), workspace.name(), workspace.description(),
                workspace.createdBy(), workspace.createdAt(), now, sanitized, workspace.active());
        Workspace saved = this.workspaceRepository.save(updated);
        log.info("Workspace quota updated: {} by {}", workspace.id(), actor != null ? actor.getUsername() : "system");
        return saved;
    }

    public Workspace getWorkspace(String workspaceId) {
        assertWorkspaceManagementEnabled();
        return requireWorkspace(workspaceId);
    }

    private Workspace requireWorkspace(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("Workspace id is required");
        }
        return this.workspaceRepository.findById(workspaceId).orElseThrow(() -> new IllegalArgumentException("Workspace not found: " + workspaceId));
    }

    private User requireUser(WorkspaceMemberRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Member request required");
        }
        Optional<User> resolved = Optional.empty();
        if (request.userId() != null && !request.userId().isBlank()) {
            resolved = this.userRepository.findById(request.userId());
        } else if (request.username() != null && !request.username().isBlank()) {
            resolved = this.userRepository.findByUsername(request.username());
        } else if (request.email() != null && !request.email().isBlank()) {
            resolved = this.userRepository.findByEmail(request.email());
        }
        return resolved.orElseThrow(() -> new IllegalArgumentException("User not found for request"));
    }

    private WorkspaceSummary toSummary(Workspace workspace, long memberCount) {
        if (workspace == null) {
            return new WorkspaceSummary(this.defaultWorkspaceId, DEFAULT_WORKSPACE_NAME, "Default workspace", true, true, memberCount, WorkspaceQuota.unlimited());
        }
        return new WorkspaceSummary(workspace.id(), workspace.name(), workspace.description(), workspace.active(),
                workspace.id().equalsIgnoreCase(this.defaultWorkspaceId), memberCount,
                workspace.quota() != null ? workspace.quota() : WorkspaceQuota.unlimited());
    }

    private WorkspaceMember toMember(User user) {
        return new WorkspaceMember(user.getId(), user.getUsername(), user.getDisplayName(),
                user.getRoles(), user.isActive(), user.getLastLoginAt(), user.getWorkspaceIds());
    }

    private void assertWorkspaceManagementEnabled() {
        if (!this.workspacePolicy.allowWorkspaceSwitching()) {
            throw new IllegalStateException("Workspace management disabled for this edition");
        }
    }

    private String normalizeWorkspaceId(String providedId, String name) {
        String idSource = (providedId == null || providedId.isBlank())
                ? name
                : providedId;
        String normalized = idSource.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("(^-|-$)", "");
        if (normalized.isBlank()) {
            normalized = "workspace";
        }
        if (!normalized.startsWith("workspace")) {
            normalized = "workspace-" + normalized;
        }
        if (normalized.length() > MAX_ID_LENGTH) {
            normalized = normalized.substring(0, MAX_ID_LENGTH);
        }
        return normalized;
    }

    private String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private WorkspaceQuota sanitizeQuota(WorkspaceQuota quota) {
        if (quota == null) {
            return WorkspaceQuota.unlimited();
        }
        int maxDocs = Math.max(0, quota.maxDocuments());
        int maxQueries = Math.max(0, quota.maxQueriesPerDay());
        int maxStorage = Math.max(0, quota.maxStorageMb());
        return new WorkspaceQuota(maxDocs, maxQueries, maxStorage);
    }

    public record WorkspaceCreateRequest(String id, String name, String description, WorkspaceQuota quota) {
    }

    public record WorkspaceMemberRequest(String userId, String username, String email) {
    }

    public record WorkspaceSummary(String id, String name, String description, boolean active, boolean isDefault,
                                   long memberCount, WorkspaceQuota quota) {
    }

    public record WorkspaceMember(String id, String username, String displayName,
                                  Set<com.jreinhal.mercenary.model.UserRole> roles, boolean active,
                                  Instant lastLoginAt, Set<String> workspaceIds) {
    }
}
