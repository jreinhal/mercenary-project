package com.jreinhal.mercenary.config;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.service.HipaaPolicy;
import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MedicalSecurityValidator {
    private static final Logger log = LoggerFactory.getLogger(MedicalSecurityValidator.class);
    private final HipaaPolicy hipaaPolicy;

    @Value(value="${app.auth-mode:DEV}")
    private String authMode;
    @Value(value="${app.oidc.issuer:}")
    private String oidcIssuer;
    @Value(value="${spring.ai.ollama.base-url:http://localhost:11434}")
    private String ollamaUrl;
    @Value(value="${sentinel.ocr.service-url:http://localhost:8090}")
    private String ocrUrl;
    @Value(value="${spring.data.mongodb.uri:mongodb://localhost:27017/mercenary}")
    private String mongoUri;
    @Value(value="${app.cors.allowed-origins:http://localhost:8080}")
    private String[] allowedOrigins;

    public MedicalSecurityValidator(HipaaPolicy hipaaPolicy) {
        this.hipaaPolicy = hipaaPolicy;
    }

    @PostConstruct
    public void validateMedicalSecurity() {
        if (!this.hipaaPolicy.isStrict(Department.MEDICAL)) {
            return;
        }

        enforceStrongAuth();
        enforceTlsUrl("Ollama base URL", this.ollamaUrl);
        enforceTlsUrl("OCR service URL", this.ocrUrl);
        enforceMongoTls(this.mongoUri);
        enforceHttpsCorsOrigins(this.allowedOrigins);
    }

    private void enforceStrongAuth() {
        if (this.authMode == null || this.authMode.isBlank()) {
            throw new IllegalStateException("HIPAA medical deployments require explicit OIDC or CAC authentication.");
        }
        if ("STANDARD".equalsIgnoreCase(this.authMode) || "DEV".equalsIgnoreCase(this.authMode)) {
            throw new IllegalStateException("HIPAA medical deployments require OIDC or CAC authentication. STANDARD/DEV auth is not permitted.");
        }
        if ("OIDC".equalsIgnoreCase(this.authMode)) {
            enforceTlsUrl("OIDC issuer", this.oidcIssuer);
        }
    }

    private void enforceTlsUrl(String label, String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("HIPAA medical deployments require a TLS URL for " + label + ".");
        }
        if (!url.startsWith("https://")) {
            throw new IllegalStateException("HIPAA medical deployments require HTTPS for " + label + ": " + url);
        }
    }

    private void enforceMongoTls(String uri) {
        if (uri == null || uri.isBlank()) {
            throw new IllegalStateException("HIPAA medical deployments require an explicit MongoDB URI.");
        }
        String lower = uri.toLowerCase();
        boolean hasTls = lower.startsWith("mongodb+srv://") || lower.contains("tls=true") || lower.contains("ssl=true");
        if (!hasTls) {
            throw new IllegalStateException("HIPAA medical deployments require MongoDB TLS (mongodb+srv or tls=true).");
        }
    }

    private void enforceHttpsCorsOrigins(String[] origins) {
        if (origins == null || origins.length == 0) {
            throw new IllegalStateException("HIPAA medical deployments require explicit HTTPS CORS origins.");
        }
        if (Arrays.asList(origins).contains("*")) {
            throw new IllegalStateException("HIPAA medical deployments cannot use wildcard CORS origins.");
        }
        for (String origin : origins) {
            if (origin == null || origin.isBlank() || !origin.startsWith("https://")) {
                throw new IllegalStateException("HIPAA medical deployments require HTTPS CORS origins. Invalid: " + origin);
            }
        }
        log.info("HIPAA medical CORS origins validated: {}", Arrays.toString(origins));
    }
}
