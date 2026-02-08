package com.jreinhal.mercenary.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.filter.SecurityContext;
import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.UserRole;
import com.jreinhal.mercenary.service.AuditService;
import com.jreinhal.mercenary.service.ConversationMemoryProvider;
import com.jreinhal.mercenary.service.HipaaPolicy;
import com.jreinhal.mercenary.service.SessionPersistenceProvider;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class SessionControllerUnitTest {

    @AfterEach
    void tearDown() {
        SecurityContext.clear();
    }

    @Test
    void createSessionRejectsInvalidDepartment() {
        SessionPersistenceProvider persistence = mock(SessionPersistenceProvider.class);
        ConversationMemoryProvider memory = mock(ConversationMemoryProvider.class);
        AuditService audit = mock(AuditService.class);
        HipaaPolicy hipaa = mock(HipaaPolicy.class);

        SessionController controller = new SessionController(persistence, memory, audit, hipaa);
        HttpServletRequest req = mock(HttpServletRequest.class);

        User user = new User();
        user.setId("u1");
        user.setUsername("user");
        user.setRoles(Set.of(UserRole.VIEWER));
        user.setAllowedSectors(Set.of(Department.ENTERPRISE));
        SecurityContext.setCurrentUser(user);

        ResponseEntity<SessionController.SessionResponse> res = controller.createSession("not-a-sector", req);
        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
        verifyNoInteractions(persistence);
    }

    @Test
    void createSessionRequiresSectorAccessAndAuditsDenied() {
        SessionPersistenceProvider persistence = mock(SessionPersistenceProvider.class);
        ConversationMemoryProvider memory = mock(ConversationMemoryProvider.class);
        AuditService audit = mock(AuditService.class);
        HipaaPolicy hipaa = mock(HipaaPolicy.class);

        SessionController controller = new SessionController(persistence, memory, audit, hipaa);
        HttpServletRequest req = mock(HttpServletRequest.class);

        User user = new User();
        user.setId("u1");
        user.setUsername("user");
        user.setRoles(Set.of(UserRole.VIEWER));
        user.setAllowedSectors(Set.of(Department.ENTERPRISE));
        SecurityContext.setCurrentUser(user);

        ResponseEntity<SessionController.SessionResponse> res = controller.createSession("GOVERNMENT", req);
        assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode());
        verify(audit, times(1)).logAccessDenied(eq(user), eq("/api/sessions/create"), contains("Not authorized"), eq(req));
        verifyNoInteractions(persistence);
    }

    @Test
    void getSessionRejectsCrossUserAccess() {
        SessionPersistenceProvider persistence = mock(SessionPersistenceProvider.class);
        ConversationMemoryProvider memory = mock(ConversationMemoryProvider.class);
        AuditService audit = mock(AuditService.class);
        HipaaPolicy hipaa = mock(HipaaPolicy.class);
        when(hipaa.shouldDisableSessionMemory(any())).thenReturn(false);

        SessionController controller = new SessionController(persistence, memory, audit, hipaa);
        HttpServletRequest req = mock(HttpServletRequest.class);

        User user = new User();
        user.setId("u1");
        user.setUsername("user");
        user.setRoles(Set.of(UserRole.VIEWER));
        user.setAllowedSectors(Set.of(Department.ENTERPRISE));
        SecurityContext.setCurrentUser(user);

        SessionPersistenceProvider.ActiveSession session = new SessionPersistenceProvider.ActiveSession(
                "s1", "other-user", "workspace_default", "ENTERPRISE",
                Instant.now(), Instant.now(), 0, 0, List.of(), Map.of());
        when(persistence.getSession("s1")).thenReturn(Optional.of(session));

        ResponseEntity<SessionPersistenceProvider.ActiveSession> res = controller.getSession("s1", req);
        assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode());
        verify(audit, times(1)).logQuery(eq(user), contains("session_access_denied"), eq(Department.ENTERPRISE), anyString(), eq(req));
    }

    @Test
    void exportSessionForbiddenWhenHipaaDisablesExport() throws Exception {
        SessionPersistenceProvider persistence = mock(SessionPersistenceProvider.class);
        ConversationMemoryProvider memory = mock(ConversationMemoryProvider.class);
        AuditService audit = mock(AuditService.class);
        HipaaPolicy hipaa = mock(HipaaPolicy.class);

        SessionController controller = new SessionController(persistence, memory, audit, hipaa);
        HttpServletRequest req = mock(HttpServletRequest.class);

        User user = new User();
        user.setId("u1");
        user.setUsername("user");
        user.setRoles(Set.of(UserRole.VIEWER));
        user.setAllowedSectors(Set.of(Department.MEDICAL));
        SecurityContext.setCurrentUser(user);

        SessionPersistenceProvider.ActiveSession session = new SessionPersistenceProvider.ActiveSession(
                "s1", "u1", "workspace_default", "MEDICAL",
                Instant.now(), Instant.now(), 0, 0, List.of(), Map.of());
        when(persistence.getSession("s1")).thenReturn(Optional.of(session));
        when(hipaa.shouldDisableSessionExport(Department.MEDICAL)).thenReturn(true);

        ResponseEntity<String> res = controller.exportSession("s1", req);
        assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode());
        assertNotNull(res.getBody());
        assertTrue(res.getBody().contains("Session export disabled"));
        verify(persistence, never()).exportSessionToJson(anyString(), anyString());
    }
}

