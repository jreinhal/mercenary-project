package com.jreinhal.mercenary.security;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ClientIpResolver {
    private static final Logger log = LoggerFactory.getLogger(ClientIpResolver.class);
    @Value("${app.security.trusted-proxies:}")
    private String trustedProxyList;
    private Set<String> trustedProxies = Set.of();

    @PostConstruct
    public void init() {
        if (this.trustedProxyList == null || this.trustedProxyList.isBlank()) {
            this.trustedProxies = Set.of();
            return;
        }
        HashSet<String> parsed = new HashSet<>();
        Arrays.stream(this.trustedProxyList.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .forEach(parsed::add);
        this.trustedProxies = Set.copyOf(parsed);
        log.info("Trusted proxies configured: {}", this.trustedProxies);
    }

    public String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return "UNKNOWN";
        }
        String remoteAddr = request.getRemoteAddr();
        if (remoteAddr != null && this.isTrustedProxy(remoteAddr)) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String candidate = xff.split(",")[0].trim();
                if (!candidate.isBlank()) {
                    return candidate;
                }
            }
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) {
                return realIp.trim();
            }
        } else if (request.getHeader("X-Forwarded-For") != null) {
            log.debug("Ignoring X-Forwarded-For from untrusted proxy: {}", remoteAddr);
        }
        return remoteAddr != null ? remoteAddr : "UNKNOWN";
    }

    private boolean isTrustedProxy(String remoteAddr) {
        return this.trustedProxies.contains(remoteAddr);
    }
}
