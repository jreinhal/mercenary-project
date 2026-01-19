/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.jreinhal.mercenary.filter.CspNonceFilter
 *  com.jreinhal.mercenary.filter.CspNonceFilter$CspResponseWrapper
 *  jakarta.servlet.Filter
 *  jakarta.servlet.FilterChain
 *  jakarta.servlet.ServletException
 *  jakarta.servlet.ServletRequest
 *  jakarta.servlet.ServletResponse
 *  jakarta.servlet.http.HttpServletRequest
 *  jakarta.servlet.http.HttpServletResponse
 *  org.springframework.core.annotation.Order
 *  org.springframework.stereotype.Component
 */
package com.jreinhal.mercenary.filter;

import com.jreinhal.mercenary.filter.CspNonceFilter;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(value=0)
public class CspNonceFilter
implements Filter {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int NONCE_LENGTH = 16;
    public static final String CSP_NONCE_ATTRIBUTE = "cspNonce";

    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest)request;
        HttpServletResponse httpResponse = (HttpServletResponse)response;
        String nonce = this.generateNonce();
        httpRequest.setAttribute(CSP_NONCE_ATTRIBUTE, (Object)nonce);
        CspResponseWrapper wrappedResponse = new CspResponseWrapper(httpResponse, nonce);
        chain.doFilter(request, (ServletResponse)wrappedResponse);
    }

    private String generateNonce() {
        byte[] nonceBytes = new byte[16];
        SECURE_RANDOM.nextBytes(nonceBytes);
        return Base64.getEncoder().encodeToString(nonceBytes);
    }
}

