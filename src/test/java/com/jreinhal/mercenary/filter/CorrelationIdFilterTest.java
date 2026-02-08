package com.jreinhal.mercenary.filter;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void tearDown() {
        MDC.remove(CorrelationIdFilter.MDC_KEY);
    }

    @Test
    void preservesValidCorrelationId() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/health");
        req.addHeader(CorrelationIdFilter.HEADER_NAME, "abc-123_DEF.456");
        MockHttpServletResponse res = new MockHttpServletResponse();

        FilterChain chain = (request, response) -> {
            assertEquals("abc-123_DEF.456", MDC.get(CorrelationIdFilter.MDC_KEY));
            ((HttpServletResponse) response).setStatus(200);
        };

        filter.doFilter(req, res, chain);

        assertEquals("abc-123_DEF.456", res.getHeader(CorrelationIdFilter.HEADER_NAME));
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    @Test
    void replacesInvalidCorrelationIdAndCleansMdc() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/health");
        req.addHeader(CorrelationIdFilter.HEADER_NAME, "bad value with spaces");
        MockHttpServletResponse res = new MockHttpServletResponse();

        FilterChain chain = (request, response) -> {
            String cid = MDC.get(CorrelationIdFilter.MDC_KEY);
            assertNotNull(cid);
            assertTrue(cid.matches("^[0-9a-fA-F\\-]{36}$"), "Expected UUID correlation id");
            ((HttpServletResponse) response).setStatus(200);
        };

        filter.doFilter(req, res, chain);

        String header = res.getHeader(CorrelationIdFilter.HEADER_NAME);
        assertNotNull(header);
        assertTrue(header.matches("^[0-9a-fA-F\\-]{36}$"), "Expected UUID correlation id");
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }
}

