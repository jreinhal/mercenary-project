package com.jreinhal.mercenary.filter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.security.ClientIpResolver;
import com.jreinhal.mercenary.service.AuditService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

class PreAuthRateLimitFilterTest {

    @AfterEach
    void tearDown() {
        SecurityContext.clear();
    }

    @Test
    void bypassesWhenAuthenticated() throws Exception {
        AuditService auditService = mock(AuditService.class);
        ClientIpResolver ipResolver = mock(ClientIpResolver.class);
        PreAuthRateLimitFilter filter = new PreAuthRateLimitFilter(auditService, ipResolver);
        ReflectionTestUtils.setField(filter, "enabled", true);
        ReflectionTestUtils.setField(filter, "anonymousRpm", 1);

        SecurityContext.setCurrentUser(User.devUser("test"));

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/login");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain okChain = (request, response) -> ((HttpServletResponse) response).setStatus(200);

        filter.doFilter(req, res, okChain);

        assertEquals(200, res.getStatus());
        verifyNoInteractions(ipResolver);
        verifyNoInteractions(auditService);
    }

    @Test
    void anonymousIsRateLimitedAndAuditedOnExceed() throws Exception {
        AuditService auditService = mock(AuditService.class);
        ClientIpResolver ipResolver = mock(ClientIpResolver.class);
        when(ipResolver.resolveClientIp(any(HttpServletRequest.class))).thenReturn("9.9.9.9");

        PreAuthRateLimitFilter filter = new PreAuthRateLimitFilter(auditService, ipResolver);
        ReflectionTestUtils.setField(filter, "enabled", true);
        ReflectionTestUtils.setField(filter, "anonymousRpm", 1);

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/login");
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        FilterChain okChain = (request, response) -> ((HttpServletResponse) response).setStatus(200);

        filter.doFilter(req, res1, okChain);
        assertEquals(200, res1.getStatus());

        MockHttpServletResponse res2 = new MockHttpServletResponse();
        filter.doFilter(req, res2, okChain);
        assertEquals(429, res2.getStatus());
        assertEquals("60", res2.getHeader("Retry-After"));
        assertTrue(res2.getContentAsString().contains("\"ERR-429\""));

        verify(auditService, times(1)).logAccessDenied(isNull(), eq("/api/auth/login"), contains("Pre-auth rate limit"), any());
    }
}

