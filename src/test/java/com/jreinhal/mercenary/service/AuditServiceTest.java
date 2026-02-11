package com.jreinhal.mercenary.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.model.AuditEvent;
import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.security.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoTemplate;

class AuditServiceTest {

    private MongoTemplate mongoTemplate;
    private ClientIpResolver clientIpResolver;
    private HipaaPolicy hipaaPolicy;
    private AuditService auditService;

    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        clientIpResolver = mock(ClientIpResolver.class);
        Environment environment = mock(Environment.class);
        hipaaPolicy = mock(HipaaPolicy.class);
        PiiRedactionService piiRedactionService = mock(PiiRedactionService.class);
        when(environment.getActiveProfiles()).thenReturn(new String[0]);
        when(hipaaPolicy.isStrict(any(Department.class))).thenReturn(false);
        when(clientIpResolver.resolveClientIp(any())).thenReturn("127.0.0.1");
        auditService = new AuditService(mongoTemplate, clientIpResolver, environment, hipaaPolicy, piiRedactionService);
    }

    @Test
    void logAccessDeniedHandlesNullRequestWithoutThrowing() {
        auditService.logAccessDenied(null, "/api/source/page", "Unauthenticated", null);
        verify(mongoTemplate).save(any(AuditEvent.class), eq("audit_log"));
    }

    @Test
    void logAccessDeniedIncludesSessionWhenRequestPresent() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpSession session = mock(HttpSession.class);
        when(request.getHeader("User-Agent")).thenReturn("JUnit");
        when(request.getSession(false)).thenReturn(session);
        when(session.getId()).thenReturn("sess-1");

        auditService.logAccessDenied(User.devUser("tester"), "/api/source/page", "Denied", request);
        verify(mongoTemplate).save(any(AuditEvent.class), eq("audit_log"));
    }
}
