package com.jreinhal.mercenary.filter;

import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.workspace.WorkspaceContext;
import com.jreinhal.mercenary.workspace.WorkspacePolicy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(3)
public class WorkspaceFilter extends OncePerRequestFilter {
    private static final String WORKSPACE_HEADER = "X-Workspace-Id";
    private final WorkspacePolicy workspacePolicy;

    public WorkspaceFilter(WorkspacePolicy workspacePolicy) {
        this.workspacePolicy = workspacePolicy;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        User user = SecurityContext.getCurrentUser();
        String requested = request.getHeader(WORKSPACE_HEADER);
        String workspaceId = workspacePolicy.resolveWorkspace(user, requested);
        WorkspaceContext.setCurrentWorkspaceId(workspaceId);
        request.setAttribute("workspaceId", workspaceId);
        try {
            chain.doFilter(request, response);
        } finally {
            WorkspaceContext.clear();
        }
    }
}
