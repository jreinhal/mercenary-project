package com.jreinhal.mercenary.controller;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.filter.SecurityContext;
import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.UserRole;
import com.jreinhal.mercenary.service.AuditService;
import com.jreinhal.mercenary.service.ConversationMemoryProvider;
import com.jreinhal.mercenary.service.HipaaPolicy;
import com.jreinhal.mercenary.service.SessionPersistenceProvider;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value={"/api/sessions"})
public class SessionController {
    private static final Logger log = LoggerFactory.getLogger(SessionController.class);
    private final SessionPersistenceProvider sessionPersistenceService;
    private final ConversationMemoryProvider conversationMemoryService;
    private final AuditService auditService;
    private final HipaaPolicy hipaaPolicy;

    public SessionController(SessionPersistenceProvider sessionPersistenceService, ConversationMemoryProvider conversationMemoryService, AuditService auditService, HipaaPolicy hipaaPolicy) {
        this.sessionPersistenceService = sessionPersistenceService;
        this.conversationMemoryService = conversationMemoryService;
        this.auditService = auditService;
        this.hipaaPolicy = hipaaPolicy;
    }

    @PostMapping(value={"/create"})
    public ResponseEntity<SessionResponse> createSession(@RequestParam(required=false) String department, HttpServletRequest request) {
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.UNAUTHORIZED).build();
        }
        String dept = department != null ? department : Department.ENTERPRISE.name();
        // S2-03: Validate department enum and sector access before creating session
        Department sector;
        try {
            sector = Department.valueOf(dept.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        if (!user.canAccessSector(sector)) {
            this.auditService.logAccessDenied(user, "/api/sessions/create", "Not authorized for sector " + sector.name(), request);
            return ResponseEntity.status((HttpStatusCode)HttpStatus.FORBIDDEN).build();
        }
        String sessionId = this.sessionPersistenceService.generateSessionId();
        SessionPersistenceProvider.ActiveSession session = this.sessionPersistenceService.touchSession(user.getId(), sessionId, dept);
        this.auditService.logQuery(user, "session_create: " + sessionId, sector, "Session created", request);
        return ResponseEntity.ok(new SessionResponse(session.sessionId(), session.department(), session.createdAt().toString(), "Session created successfully"));
    }

    @GetMapping(value={"/{sessionId}"})
    public ResponseEntity<SessionPersistenceProvider.ActiveSession> getSession(@PathVariable String sessionId, HttpServletRequest request) {
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.UNAUTHORIZED).build();
        }
        Optional<SessionPersistenceProvider.ActiveSession> session = this.sessionPersistenceService.getSession(sessionId);
        if (session.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!session.get().userId().equals(user.getId())) {
            this.auditService.logQuery(user, "session_access_denied: " + sessionId, Department.ENTERPRISE, "Access denied", request);
            return ResponseEntity.status((HttpStatusCode)HttpStatus.FORBIDDEN).build();
        }
        // S4-14: HIPAA parity check — consistent with getConversationContext/getSessionTraces/clearSessionHistory
        Department dept = this.safeDepartment(session.get().department());
        if (dept != null && this.hipaaPolicy.shouldDisableSessionMemory(dept)) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.FORBIDDEN).build();
        }
        this.auditService.logQuery(user, "session_get: " + sessionId, dept != null ? dept : Department.ENTERPRISE, "Session retrieved", request);
        return ResponseEntity.ok(session.get());
    }

    @GetMapping
    public ResponseEntity<List<SessionPersistenceProvider.ActiveSession>> listSessions(HttpServletRequest request) {
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.UNAUTHORIZED).build();
        }
        List<SessionPersistenceProvider.ActiveSession> sessions = this.sessionPersistenceService.getUserSessions(user.getId());
        this.auditService.logQuery(user, "session_list", Department.ENTERPRISE, "Listed sessions", request);
        return ResponseEntity.ok(sessions);
    }

    @PostMapping(value={"/{sessionId}/touch"})
    public ResponseEntity<SessionPersistenceProvider.ActiveSession> touchSession(@PathVariable String sessionId, @RequestParam(required=false) String department, HttpServletRequest request) {
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.UNAUTHORIZED).build();
        }
        String dept = department != null ? department : Department.ENTERPRISE.name();
        // S4-01: Validate department enum and sector access — parity with createSession
        Department sector;
        try {
            sector = Department.valueOf(dept.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        if (!user.canAccessSector(sector)) {
            this.auditService.logAccessDenied(user, "/api/sessions/" + sessionId + "/touch",
                "Not authorized for sector " + sector.name(), request);
            return ResponseEntity.status((HttpStatusCode)HttpStatus.FORBIDDEN).build();
        }
        SessionPersistenceProvider.ActiveSession session = this.sessionPersistenceService.touchSession(user.getId(), sessionId, dept);
        this.auditService.logQuery(user, "session_touch: " + sessionId, sector, "Session touched", request);
        return ResponseEntity.ok(session);
    }

    @DeleteMapping(value={"/{sessionId}/history"})
    public ResponseEntity<Map<String, String>> clearSessionHistory(@PathVariable String sessionId, HttpServletRequest request) {
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.UNAUTHORIZED).build();
        }
        Optional<SessionPersistenceProvider.ActiveSession> session = this.sessionPersistenceService.getSession(sessionId);
        if (session.isEmpty() || !session.get().userId().equals(user.getId())) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.FORBIDDEN).build();
        }
        // S3-01: Sector/HIPAA check — parity with getConversationContext and getSessionTraces
        Department dept = this.safeDepartment(session.get().department());
        if (dept != null && this.hipaaPolicy.shouldDisableSessionMemory(dept)) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.FORBIDDEN).build();
        }
        this.conversationMemoryService.clearSession(user.getId(), sessionId);
        this.auditService.logQuery(user, "session_clear: " + sessionId, dept != null ? dept : Department.ENTERPRISE, "Session cleared", request);
        return ResponseEntity.ok(Map.of("status", "cleared", "sessionId", sessionId));
    }

    @GetMapping(value={"/{sessionId}/context"})
    public ResponseEntity<ConversationMemoryProvider.ConversationContext> getConversationContext(@PathVariable String sessionId, HttpServletRequest request) {
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.UNAUTHORIZED).build();
        }
        Optional<SessionPersistenceProvider.ActiveSession> session = this.sessionPersistenceService.getSession(sessionId);
        if (session.isEmpty() || !session.get().userId().equals(user.getId())) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.FORBIDDEN).build();
        }
        Department dept = this.safeDepartment(session.get().department());
        if (dept != null && this.hipaaPolicy.shouldDisableSessionMemory(dept)) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.FORBIDDEN).build();
        }
        ConversationMemoryProvider.ConversationContext context = this.conversationMemoryService.getContext(user.getId(), sessionId);
        this.auditService.logQuery(user, "session_context: " + sessionId, dept != null ? dept : Department.ENTERPRISE, "Session context accessed", request);
        return ResponseEntity.ok(context);
    }

    @GetMapping(value={"/{sessionId}/traces"})
    public ResponseEntity<List<SessionPersistenceProvider.PersistedTrace>> getSessionTraces(@PathVariable String sessionId, HttpServletRequest request) {
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.UNAUTHORIZED).build();
        }
        Optional<SessionPersistenceProvider.ActiveSession> session = this.sessionPersistenceService.getSession(sessionId);
        if (session.isEmpty() || !session.get().userId().equals(user.getId())) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.FORBIDDEN).build();
        }
        Department dept = this.safeDepartment(session.get().department());
        if (dept != null && this.hipaaPolicy.shouldDisableSessionMemory(dept)) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.FORBIDDEN).build();
        }
        List<SessionPersistenceProvider.PersistedTrace> traces = this.sessionPersistenceService.getSessionTraces(sessionId);
        this.auditService.logQuery(user, "session_traces: " + sessionId, dept != null ? dept : Department.ENTERPRISE, "Session traces accessed", request);
        return ResponseEntity.ok(traces);
    }

    @GetMapping(value={"/traces/{traceId}"})
    public ResponseEntity<SessionPersistenceProvider.PersistedTrace> getTrace(@PathVariable String traceId, HttpServletRequest request) {
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.UNAUTHORIZED).build();
        }
        Optional<SessionPersistenceProvider.PersistedTrace> trace = this.sessionPersistenceService.getPersistedTrace(traceId);
        if (trace.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!trace.get().userId().equals(user.getId())) {
            this.auditService.logQuery(user, "trace_access_denied: " + traceId, Department.ENTERPRISE, "Access denied", request);
            return ResponseEntity.status((HttpStatusCode)HttpStatus.FORBIDDEN).build();
        }
        // S5-01: HIPAA parity — consistent with getSession/clearSessionHistory/getConversationContext/getSessionTraces
        Department dept = this.safeDepartment(trace.get().department());
        if (dept != null && this.hipaaPolicy.shouldDisableSessionMemory(dept)) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.FORBIDDEN).build();
        }
        this.auditService.logQuery(user, "trace_get: " + traceId, dept != null ? dept : Department.ENTERPRISE, "Trace retrieved", request);
        return ResponseEntity.ok(trace.get());
    }

    @GetMapping(value={"/{sessionId}/export"}, produces={"application/json"})
    public ResponseEntity<String> exportSession(@PathVariable String sessionId, HttpServletRequest request) {
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.UNAUTHORIZED).build();
        }
        Optional<SessionPersistenceProvider.ActiveSession> sessionOpt = this.sessionPersistenceService.getSession(sessionId);
        if (sessionOpt.isPresent()) {
            Department dept = this.safeDepartment(sessionOpt.get().department());
            if (dept != null && this.hipaaPolicy.shouldDisableSessionExport(dept)) {
                return ResponseEntity.status((HttpStatusCode)HttpStatus.FORBIDDEN).body("{\"error\": \"Session export disabled for HIPAA medical deployments\"}");
            }
        }
        try {
            String json = this.sessionPersistenceService.exportSessionToJson(sessionId, user.getId());
            this.auditService.logQuery(user, "session_export: " + sessionId, Department.ENTERPRISE, "Session exported", request);
            return ResponseEntity.ok(json);
        }
        catch (SecurityException e) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.FORBIDDEN).body("{\"error\": \"Access denied\"}");
        }
        catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
        catch (IOException e) {
            log.error("Failed to export session {}: {}", sessionId, e.getMessage());
            return ResponseEntity.status((HttpStatusCode)HttpStatus.INTERNAL_SERVER_ERROR).body("{\"error\": \"Export failed\"}");
        }
    }

    @PostMapping(value={"/{sessionId}/export/file"})
    public ResponseEntity<Map<String, String>> exportSessionToFile(@PathVariable String sessionId, HttpServletRequest request) {
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.UNAUTHORIZED).build();
        }
        Optional<SessionPersistenceProvider.ActiveSession> sessionOpt = this.sessionPersistenceService.getSession(sessionId);
        if (sessionOpt.isPresent()) {
            Department dept = this.safeDepartment(sessionOpt.get().department());
            if (dept != null && this.hipaaPolicy.shouldDisableSessionExport(dept)) {
                return ResponseEntity.status((HttpStatusCode)HttpStatus.FORBIDDEN).body(Map.of("error", "Session export disabled for HIPAA medical deployments"));
            }
        }
        try {
            this.sessionPersistenceService.exportSession(sessionId, user.getId());
            this.auditService.logQuery(user, "session_export_file: " + sessionId, Department.ENTERPRISE, "Session exported to file", request);
            // L-10: Return opaque identifier instead of server-side filename
            return ResponseEntity.ok(Map.of("status", "exported", "sessionId", sessionId));
        }
        catch (SecurityException e) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied"));
        }
        catch (IllegalArgumentException e) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.NOT_FOUND).body(Map.of("error", "Session not found"));
        }
        catch (IOException e) {
            if (log.isErrorEnabled()) {
                log.error("Failed to export session to file {}: {}", sessionId, e.getMessage());
            }
            // L-09: Return generic error — details already logged server-side above
            return ResponseEntity.status((HttpStatusCode)HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Export failed"));
        }
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStatistics(HttpServletRequest request) {
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.UNAUTHORIZED).build();
        }
        // M-06: Only include global stats for admin users; non-admins see only their own
        Map<String, Object> stats;
        if (user.hasRole(UserRole.ADMIN)) {
            stats = this.sessionPersistenceService.getStatistics();
        } else {
            stats = new java.util.HashMap<>();
        }
        List<SessionPersistenceProvider.ActiveSession> userSessions = this.sessionPersistenceService.getUserSessions(user.getId());
        stats.put("userActiveSessions", userSessions.size());
        stats.put("userTotalMessages", userSessions.stream().mapToInt(SessionPersistenceProvider.ActiveSession::messageCount).sum());
        stats.put("userTotalTraces", userSessions.stream().mapToInt(SessionPersistenceProvider.ActiveSession::traceCount).sum());
        this.auditService.logQuery(user, "session_stats", Department.ENTERPRISE, "Session stats viewed", request);
        return ResponseEntity.ok(stats);
    }

    private Department safeDepartment(String dept) {
        if (dept == null || dept.isBlank()) {
            return null;
        }
        try {
            return Department.valueOf(dept.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public record SessionResponse(String sessionId, String department, String createdAt, String message) {
    }
}
