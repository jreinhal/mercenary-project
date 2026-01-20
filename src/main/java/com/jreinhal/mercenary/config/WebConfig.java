package com.jreinhal.mercenary.config;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig
implements WebMvcConfigurer {
    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);
    @Value(value="${app.cors.allowed-origins:http://localhost:8080}")
    private String[] allowedOrigins;
    @Value(value="${app.cors.allow-credentials:false}")
    private boolean allowCredentials;
    @Value(value="${app.auth-mode:DEV}")
    private String authMode;

    @PostConstruct
    public void validateCorsConfiguration() {
        boolean isProduction;
        boolean hasWildcard = Arrays.asList(this.allowedOrigins).contains("*");
        boolean bl = isProduction = !"DEV".equalsIgnoreCase(this.authMode);
        if (hasWildcard && isProduction) {
            log.error("=================================================================");
            log.error("  SECURITY WARNING: CORS wildcard (*) in production!");
            log.error("=================================================================");
            log.error("  Allowed Origins: {}", Arrays.toString(this.allowedOrigins));
            log.error("  This allows ANY website to make requests to your API.");
            log.error("");
            log.error("  To fix: Set app.cors.allowed-origins to explicit domains");
            log.error("  Example: app.cors.allowed-origins=https://yourdomain.com");
            log.error("=================================================================");
        }
        if (this.allowCredentials && hasWildcard) {
            log.error("=================================================================");
            log.error("  CRITICAL: Cannot use credentials with wildcard origin!");
            log.error("=================================================================");
            log.error("  This is a browser security violation and will be rejected.");
            log.error("  Set explicit origins or disable credentials.");
            log.error("=================================================================");
        }
        log.info("CORS Configuration:");
        log.info("  Allowed Origins: {}", Arrays.toString(this.allowedOrigins));
        log.info("  Allow Credentials: {}", this.allowCredentials);
    }

    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins(this.allowedOrigins).allowedMethods(new String[]{"GET", "POST", "PUT", "DELETE", "OPTIONS"}).allowedHeaders(new String[]{"Authorization", "Content-Type", "Accept", "Origin", "X-Requested-With", "X-CSRF-Token"}).exposedHeaders(new String[]{"X-Total-Count", "X-Request-Id"}).allowCredentials(this.allowCredentials).maxAge(3600L);
    }
}
