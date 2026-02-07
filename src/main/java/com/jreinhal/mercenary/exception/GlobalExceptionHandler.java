package com.jreinhal.mercenary.exception;

import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, Object>> handleSecurity(SecurityException ex) {
        // L-05: Log the real message server-side but return generic message to client
        if (log.isWarnEnabled()) {
            log.warn("Security exception: {}", ex.getMessage());
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Access denied", "timestamp", Instant.now().toString()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        // R-06: Sanitize exception message — don't expose internal details to client
        String safeMessage = sanitizeExceptionMessage(ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", safeMessage, "timestamp", Instant.now().toString()));
    }

    private static final java.util.regex.Pattern PACKAGE_PATTERN =
            java.util.regex.Pattern.compile("\\w+(\\.\\w+){2,}");

    private static String sanitizeExceptionMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Invalid request";
        }
        // Strip file paths, class names, package names, and stack-trace-like content
        if (message.contains("/") || message.contains("\\")
                || message.contains("Exception") || message.contains("at ")
                || PACKAGE_PATTERN.matcher(message).find()
                || message.length() > 200) {
            return "Invalid request";
        }
        return message;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnhandled(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error", "timestamp", Instant.now().toString()));
    }
}
