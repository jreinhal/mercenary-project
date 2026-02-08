package com.jreinhal.mercenary.filter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.UserRole;
import com.jreinhal.mercenary.security.ClientIpResolver;
import com.jreinhal.mercenary.service.AuditService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

class RateLimitFilterTest {

    @AfterEach
    void tearDown() {
        SecurityContext.clear();
    }

    @Test
    void healthIsExemptFromRateLimiting() throws Exception {
        AuditService auditService = mock(AuditService.class);
        ClientIpResolver ipResolver = mock(ClientIpResolver.class);
        RateLimitFilter filter = new RateLimitFilter(auditService, ipResolver);
        ReflectionTestUtils.setField(filter, "enabled", true);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/health");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = (request, response) -> ((HttpServletResponse) response).setStatus(200);

        filter.doFilter(req, res, chain);

        assertEquals(200, res.getStatus());
        assertNull(res.getHeader("X-RateLimit-Limit"));
        verifyNoInteractions(auditService);
    }

    @Test
    void anonymousIsLimitedByIpAndReturns429() throws Exception {
        AuditService auditService = mock(AuditService.class);
        ClientIpResolver ipResolver = mock(ClientIpResolver.class);
        when(ipResolver.resolveClientIp(any(HttpServletRequest.class))).thenReturn("1.2.3.4");

        RateLimitFilter filter = new RateLimitFilter(auditService, ipResolver);
        ReflectionTestUtils.setField(filter, "enabled", true);
        ReflectionTestUtils.setField(filter, "anonymousRpm", 1);
        ReflectionTestUtils.setField(filter, "viewerRpm", 1);
        ReflectionTestUtils.setField(filter, "analystRpm", 1);
        ReflectionTestUtils.setField(filter, "adminRpm", 1);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/ask");

        MockHttpServletResponse res1 = new MockHttpServletResponse();
        FilterChain okChain = (request, response) -> ((HttpServletResponse) response).setStatus(200);
        filter.doFilter(req, res1, okChain);
        assertEquals(200, res1.getStatus());
        assertEquals("1", res1.getHeader("X-RateLimit-Limit"));

        MockHttpServletResponse res2 = new MockHttpServletResponse();
        filter.doFilter(req, res2, okChain);
        assertEquals(429, res2.getStatus());
        assertEquals("60", res2.getHeader("Retry-After"));
        assertTrue(res2.getContentAsString().contains("\"ERR-429\""));

        // RateLimitFilter only audits when a user is present
        verify(auditService, never()).logAccessDenied(any(), anyString(), anyString(), any());
    }

    @Test
    void authenticatedIsLimitedByUserIdAndAuditedOnExceed() throws Exception {
        AuditService auditService = mock(AuditService.class);
        ClientIpResolver ipResolver = mock(ClientIpResolver.class);

        RateLimitFilter filter = new RateLimitFilter(auditService, ipResolver);
        ReflectionTestUtils.setField(filter, "enabled", true);
        ReflectionTestUtils.setField(filter, "viewerRpm", 1);
        ReflectionTestUtils.setField(filter, "analystRpm", 1);
        ReflectionTestUtils.setField(filter, "adminRpm", 1);
        ReflectionTestUtils.setField(filter, "anonymousRpm", 1);

        User viewer = new User();
        viewer.setId("u-1");
        viewer.setUsername("viewer");
        viewer.setRoles(Set.of(UserRole.VIEWER));
        SecurityContext.setCurrentUser(viewer);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/ask");
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        FilterChain okChain = (request, response) -> ((HttpServletResponse) response).setStatus(200);
        filter.doFilter(req, res1, okChain);
        assertEquals(200, res1.getStatus());

        MockHttpServletResponse res2 = new MockHttpServletResponse();
        filter.doFilter(req, res2, okChain);
        assertEquals(429, res2.getStatus());

        verify(auditService, times(1)).logAccessDenied(eq(viewer), eq("/api/ask"), contains("Rate limit"), any());
        verify(ipResolver, never()).resolveClientIp(any());
    }
}

