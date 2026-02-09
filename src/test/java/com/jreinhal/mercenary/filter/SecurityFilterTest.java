package com.jreinhal.mercenary.filter;

import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.UserRole;
import com.jreinhal.mercenary.service.AuditService;
import com.jreinhal.mercenary.service.AuthenticationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SecurityFilterTest {

    @Mock
    private AuthenticationService authService;

    @Mock
    private AuditService auditService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    private SecurityFilter securityFilter;

    @BeforeEach
    void setUp() {
        when(authService.getAuthMode()).thenReturn("DEV");
        securityFilter = new SecurityFilter(authService, auditService);
    }

    @Test
    void shouldAllowPublicPathsWithoutAuthentication() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/health");

        securityFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(authService, never()).authenticate(any());
    }

    @Test
    void shouldAllowStaticResources() throws Exception {
        when(request.getRequestURI()).thenReturn("/css/style.css");

        securityFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void shouldBypassFilterForStaticVendorAssetsOnGet() {
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/vendor/d3.v7.min.js");

        assertTrue(securityFilter.shouldNotFilter(request));
    }

    @Test
    void shouldNotBypassFilterForApiRequests() {
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/ask");

        assertFalse(securityFilter.shouldNotFilter(request));
    }

    @Test
    void shouldNotBypassFilterForNonGetStaticRequests() {
        when(request.getMethod()).thenReturn("POST");

        assertFalse(securityFilter.shouldNotFilter(request));
    }

    @Test
    void shouldAuthenticateProtectedPaths() throws Exception {
        User mockUser = User.devUser("testuser");
        when(request.getRequestURI()).thenReturn("/api/ask");
        when(authService.authenticate(request)).thenReturn(mockUser);

        securityFilter.doFilterInternal(request, response, filterChain);

        verify(authService).authenticate(request);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void shouldRejectUnauthenticatedRequests() throws Exception {
        StringWriter stringWriter = new StringWriter();
        PrintWriter writer = new PrintWriter(stringWriter);

        when(request.getRequestURI()).thenReturn("/api/ask");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(authService.authenticate(request)).thenReturn(null);
        when(authService.getAuthMode()).thenReturn("STANDARD");
        when(response.getWriter()).thenReturn(writer);

        // Reinitialize filter with STANDARD mode
        when(authService.getAuthMode()).thenReturn("STANDARD");
        securityFilter = new SecurityFilter(authService, auditService);
        when(authService.getAuthMode()).thenReturn("STANDARD");

        securityFilter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(401);
        verify(auditService).logAuthFailure(anyString(), anyString(), any());
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void shouldAllowDevModeWithoutCredentials() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/ask");
        when(authService.authenticate(request)).thenReturn(null);
        when(authService.getAuthMode()).thenReturn("DEV");

        securityFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }
}
