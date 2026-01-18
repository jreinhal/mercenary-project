package com.jreinhal.mercenary.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;

/**
 * Enterprise Web Configuration.
 *
 * SECURITY: Centralizes CORS management to prevent cross-origin attacks.
 *
 * Configuration:
 * - app.cors.allowed-origins: Comma-separated list of allowed origins
 * - app.cors.allow-credentials: Whether to allow credentials (default: false for security)
 *
 * IMPORTANT: In production, always specify explicit origins. Never use "*".
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

    @Value("${app.cors.allowed-origins:http://localhost:8080}")
    private String[] allowedOrigins;

    @Value("${app.cors.allow-credentials:false}")
    private boolean allowCredentials;

    @Value("${app.auth-mode:DEV}")
    private String authMode;

    /**
     * Validate CORS configuration on startup.
     */
    @PostConstruct
    public void validateCorsConfiguration() {
        boolean hasWildcard = Arrays.asList(allowedOrigins).contains("*");
        boolean isProduction = !"DEV".equalsIgnoreCase(authMode);

        if (hasWildcard && isProduction) {
            log.error("=================================================================");
            log.error("  SECURITY WARNING: CORS wildcard (*) in production!");
            log.error("=================================================================");
            log.error("  Allowed Origins: {}", Arrays.toString(allowedOrigins));
            log.error("  This allows ANY website to make requests to your API.");
            log.error("");
            log.error("  To fix: Set app.cors.allowed-origins to explicit domains");
            log.error("  Example: app.cors.allowed-origins=https://yourdomain.com");
            log.error("=================================================================");
        }

        if (allowCredentials && hasWildcard) {
            log.error("=================================================================");
            log.error("  CRITICAL: Cannot use credentials with wildcard origin!");
            log.error("=================================================================");
            log.error("  This is a browser security violation and will be rejected.");
            log.error("  Set explicit origins or disable credentials.");
            log.error("=================================================================");
        }

        log.info("CORS Configuration:");
        log.info("  Allowed Origins: {}", Arrays.toString(allowedOrigins));
        log.info("  Allow Credentials: {}", allowCredentials);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                // SECURITY: Restrict headers instead of allowing all
                .allowedHeaders(
                    "Authorization",
                    "Content-Type",
                    "Accept",
                    "Origin",
                    "X-Requested-With",
                    "X-CSRF-Token"
                )
                // SECURITY: Expose only necessary headers to JavaScript
                .exposedHeaders(
                    "X-Total-Count",
                    "X-Request-Id"
                )
                .allowCredentials(allowCredentials)
                .maxAge(3600); // Cache preflight checks for 1 hour
    }
}
