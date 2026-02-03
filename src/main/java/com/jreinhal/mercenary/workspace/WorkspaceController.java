package com.jreinhal.mercenary.workspace;

import com.jreinhal.mercenary.filter.SecurityContext;
import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.workspace.WorkspaceService.WorkspaceCreateRequest;
import com.jreinhal.mercenary.workspace.WorkspaceService.WorkspaceMember;
import com.jreinhal.mercenary.workspace.WorkspaceService.WorkspaceMemberRequest;
import com.jreinhal.mercenary.workspace.WorkspaceService.WorkspaceSummary;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value={"/api/workspaces"})
public class WorkspaceController {
    private final WorkspaceService workspaceService;
    private final WorkspacePolicy workspacePolicy;

    public WorkspaceController(WorkspaceService workspaceService, WorkspacePolicy workspacePolicy) {
        this.workspaceService = workspaceService;
        this.workspacePolicy = workspacePolicy;
    }

    @GetMapping
    public ResponseEntity<List<WorkspaceSummary>> listWorkspaces() {
        User user = SecurityContext.getCurrentUser();
        return ResponseEntity.ok(this.workspaceService.listWorkspaces(user));
    }

    @PostMapping
    @PreAuthorize(value="hasRole('ADMIN')")
    public ResponseEntity<?> createWorkspace(@RequestBody WorkspaceCreateRequest request) {
        if (!workspacePolicy.allowWorkspaceSwitching()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("Workspace management disabled for this edition"));
        }
        User user = SecurityContext.getCurrentUser();
        return ResponseEntity.ok(this.workspaceService.createWorkspace(request, user));
    }

    @GetMapping("/{workspaceId}/members")
    @PreAuthorize(value="hasRole('ADMIN')")
    public ResponseEntity<?> listMembers(@PathVariable String workspaceId) {
        if (!workspacePolicy.allowWorkspaceSwitching()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("Workspace membership disabled for this edition"));
        }
        List<WorkspaceMember> members = this.workspaceService.listMembers(workspaceId);
        return ResponseEntity.ok(members);
    }

    @PostMapping("/{workspaceId}/members")
    @PreAuthorize(value="hasRole('ADMIN')")
    public ResponseEntity<?> addMember(@PathVariable String workspaceId, @RequestBody WorkspaceMemberRequest request) {
        if (!workspacePolicy.allowWorkspaceSwitching()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("Workspace membership disabled for this edition"));
        }
        WorkspaceMember member = this.workspaceService.addMember(workspaceId, request);
        return ResponseEntity.ok(member);
    }

    @DeleteMapping("/{workspaceId}/members/{userId}")
    @PreAuthorize(value="hasRole('ADMIN')")
    public ResponseEntity<?> removeMember(@PathVariable String workspaceId, @PathVariable String userId) {
        if (!workspacePolicy.allowWorkspaceSwitching()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("Workspace membership disabled for this edition"));
        }
        boolean removed = this.workspaceService.removeMember(workspaceId, userId);
        return ResponseEntity.ok(new RemovalResponse(removed));
    }

    public record ErrorResponse(String error) {
    }

    public record RemovalResponse(boolean removed) {
    }
}
