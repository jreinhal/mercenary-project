package com.jreinhal.mercenary.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jreinhal.mercenary.filter.SecurityContext;
import com.jreinhal.mercenary.model.AuditEvent;
import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.UserRole;
import com.jreinhal.mercenary.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class AuditControllerTest {

    @AfterEach
    void tearDown() {
        SecurityContext.clear();
    }

    @Test
    void forbidsAnonymousAndAuditsAccessDenied() {
        AuditService auditService = mock(AuditService.class);
        AuditController controller = new AuditController(auditService);
        HttpServletRequest req = mock(HttpServletRequest.class);

        ResponseEntity<?> res = controller.getRecentEvents(100, req);
        assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.getBody();
        assertNotNull(body);
        assertTrue(body.get("error").toString().contains("ACCESS DENIED"));

        verify(auditService, times(1)).logAccessDenied(isNull(), eq("/api/audit/events"), contains("VIEW_AUDIT"), eq(req));
        verify(auditService, never()).getRecentEvents(anyInt());
    }

    @Test
    void capsLimitAt1000ForAuthorizedUser() {
        AuditService auditService = mock(AuditService.class);
        when(auditService.getRecentEvents(1000)).thenReturn(List.of());
        AuditController controller = new AuditController(auditService);
        HttpServletRequest req = mock(HttpServletRequest.class);

        User auditor = new User();
        auditor.setId("aud-1");
        auditor.setUsername("auditor");
        auditor.setRoles(Set.of(UserRole.AUDITOR));
        SecurityContext.setCurrentUser(auditor);

        ResponseEntity<?> res = controller.getRecentEvents(5000, req);
        assertEquals(HttpStatus.OK, res.getStatusCode());
        verify(auditService, times(1)).getRecentEvents(1000);
    }

    @Test
    void statsCountsEventTypes() {
        AuditService auditService = mock(AuditService.class);
        List<AuditEvent> events = List.of(
                AuditEvent.create(AuditEvent.EventType.AUTH_SUCCESS, "u1", "ok"),
                AuditEvent.create(AuditEvent.EventType.AUTH_FAILURE, "u1", "fail"),
                AuditEvent.create(AuditEvent.EventType.QUERY_EXECUTED, "u1", "q"),
                AuditEvent.create(AuditEvent.EventType.ACCESS_DENIED, "u1", "denied"),
                AuditEvent.create(AuditEvent.EventType.PROMPT_INJECTION_DETECTED, "u1", "inj"),
                AuditEvent.create(AuditEvent.EventType.SECURITY_ALERT, "u1", "alert")
        );
        when(auditService.getRecentEvents(500)).thenReturn(events);
        AuditController controller = new AuditController(auditService);
        HttpServletRequest req = mock(HttpServletRequest.class);

        User auditor = new User();
        auditor.setId("aud-1");
        auditor.setUsername("auditor");
        auditor.setRoles(Set.of(UserRole.AUDITOR));
        SecurityContext.setCurrentUser(auditor);

        ResponseEntity<?> res = controller.getAuditStats(req);
        assertEquals(HttpStatus.OK, res.getStatusCode());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.getBody();
        assertNotNull(body);
        assertEquals(6, body.get("totalEvents"));
        assertEquals(1L, body.get("authSuccess"));
        assertEquals(1L, body.get("authFailure"));
        assertEquals(1L, body.get("queries"));
        assertEquals(1L, body.get("accessDenied"));
        assertEquals(2L, body.get("securityAlerts"));
    }
}

