package com.jreinhal.mercenary.filter;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CspNonceFilterTest {

    @Test
    void setsNonceAttributeAndCspHeader() throws Exception {
        CspNonceFilter filter = new CspNonceFilter();

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/");
        MockHttpServletResponse res = new MockHttpServletResponse();

        FilterChain chain = (request, response) -> {
            ((HttpServletResponse) response).setContentType("text/html");
            ((HttpServletResponse) response).getWriter().write("<html><body>ok</body></html>");
        };

        filter.doFilter(req, res, chain);

        Object nonceObj = req.getAttribute(CspNonceFilter.CSP_NONCE_ATTRIBUTE);
        assertNotNull(nonceObj);
        String nonce = nonceObj.toString();
        assertFalse(nonce.isBlank());

        String csp = res.getHeader("Content-Security-Policy");
        assertNotNull(csp);
        assertTrue(csp.contains("default-src 'self'"));
        assertTrue(csp.contains("connect-src 'self'"));
        assertTrue(csp.contains("frame-ancestors 'none'"));
        assertTrue(csp.contains("'nonce-" + nonce + "'"), "CSP header should include request nonce");
    }

    @Test
    void addsCspHeaderEvenForNonHtmlWriters() throws Exception {
        CspNonceFilter filter = new CspNonceFilter();

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/status");
        MockHttpServletResponse res = new MockHttpServletResponse();

        FilterChain chain = (request, response) -> {
            ((HttpServletResponse) response).setContentType("application/json");
            ((HttpServletResponse) response).getWriter().write("{\"ok\":true}");
        };

        filter.doFilter(req, res, chain);

        assertNotNull(res.getHeader("Content-Security-Policy"));
    }
}

