package com.jreinhal.mercenary.medical.controller;

import com.jreinhal.mercenary.medical.hipaa.HipaaAuditService;
import com.jreinhal.mercenary.service.PiiRedactionService;
import com.jreinhal.mercenary.service.TokenizationVault;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value={"/api/pii"})
public class PiiRevealController {
    private static final Logger log = LoggerFactory.getLogger(PiiRevealController.class);
    private final TokenizationVault tokenizationVault;
    private final HipaaAuditService hipaaAuditService;
    private final PiiRedactionService piiRedactionService;

    public PiiRevealController(TokenizationVault tokenizationVault, HipaaAuditService hipaaAuditService, PiiRedactionService piiRedactionService) {
        this.tokenizationVault = tokenizationVault;
        this.hipaaAuditService = hipaaAuditService;
        this.piiRedactionService = piiRedactionService;
    }

    @PostMapping(value={"/reveal"})
    @PreAuthorize(value="hasAnyRole('PHI_ACCESS', 'ADMIN')")
    public ResponseEntity<Map<String, Object>> revealToken(@RequestBody RevealRequest request) {
        String userId;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String string = userId = auth != null ? auth.getName() : "UNKNOWN";
        if (request.token() == null || request.token().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Token is required", "timestamp", Instant.now().toString()));
        }
        if (request.reason() == null || request.reason().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Reason for access is required for HIPAA compliance", "timestamp", Instant.now().toString()));
        }
        if (!this.tokenizationVault.isToken(request.token())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid token format", "timestamp", Instant.now().toString()));
        }
        String sanitizedReason = this.sanitizeReason(request.reason());
        this.hipaaAuditService.logPhiAccess(userId, "PII_REVEAL_REQUEST", request.token(), sanitizedReason, request.breakTheGlass() != null && request.breakTheGlass() != false);
        Optional<String> revealedValue = this.tokenizationVault.detokenize(request.token(), userId);
        if (revealedValue.isEmpty()) {
            log.warn("PII reveal failed for token {} by user {} - not found", this.maskToken(request.token()), userId);
            return ResponseEntity.status((HttpStatusCode)HttpStatus.NOT_FOUND).body(Map.of("error", "Token not found or expired", "timestamp", Instant.now().toString()));
        }
        this.hipaaAuditService.logPhiAccess(userId, "PII_REVEAL_SUCCESS", request.token(), sanitizedReason, request.breakTheGlass() != null && request.breakTheGlass() != false);
        log.info("HIPAA AUDIT: User {} revealed PII token {} for reason: {}", new Object[]{userId, this.maskToken(request.token()), sanitizedReason});
        return ResponseEntity.ok(Map.of("value", revealedValue.get(), "revealed_at", Instant.now().toString(), "revealed_by", userId, "warning", "This access has been logged for HIPAA compliance"));
    }

    @PostMapping(value={"/reveal/emergency"})
    @PreAuthorize(value="hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> emergencyReveal(@RequestBody EmergencyRevealRequest request) {
        String userId;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String string = userId = auth != null ? auth.getName() : "UNKNOWN";
        if (request.token() == null || request.token().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Token is required"));
        }
        if (request.emergencyReason() == null || request.emergencyReason().length() < 20) {
            return ResponseEntity.badRequest().body(Map.of("error", "Emergency reason must be at least 20 characters explaining the situation"));
        }
        if (request.patientId() == null || request.patientId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Patient ID is required for emergency access"));
        }
        log.warn("=== BREAK-THE-GLASS EMERGENCY ACCESS ===");
        log.warn("User: {}", userId);
        log.warn("Token: {}", this.maskToken(request.token()));
        log.warn("Patient: {}", this.sanitizeReason(request.patientId()));
        log.warn("Reason: {}", this.sanitizeReason(request.emergencyReason()));
        log.warn("=========================================");
        this.hipaaAuditService.logBreakTheGlass(userId, request.token(), this.sanitizeReason(request.patientId()), this.sanitizeReason(request.emergencyReason()));
        Optional<String> revealedValue = this.tokenizationVault.detokenize(request.token(), userId);
        if (revealedValue.isEmpty()) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.NOT_FOUND).body(Map.of("error", "Token not found", "timestamp", Instant.now().toString()));
        }
        return ResponseEntity.ok(Map.of("value", revealedValue.get(), "revealed_at", Instant.now().toString(), "revealed_by", userId, "emergency_access", true, "warning", "BREAK-THE-GLASS: This emergency access has been logged and will be reviewed"));
    }

    @GetMapping(value={"/is-token"})
    @PreAuthorize(value="isAuthenticated()")
    public ResponseEntity<Map<String, Boolean>> isToken(@RequestParam String value) {
        return ResponseEntity.ok(Map.of("isToken", this.tokenizationVault.isToken(value)));
    }

    private String maskToken(String token) {
        if (token == null || !token.contains(":")) {
            return "[INVALID]";
        }
        int lastColon = token.lastIndexOf(58);
        if (lastColon > 0) {
            return token.substring(0, lastColon + 1) + "***>>";
        }
        return "[MASKED]";
    }

    public record RevealRequest(String token, String reason, Boolean breakTheGlass) {
    }

    public record EmergencyRevealRequest(String token, String emergencyReason, String patientId) {
    }

    private String sanitizeReason(String reason) {
        if (reason == null) {
            return "";
        }
        String redacted = this.piiRedactionService.redact(reason, Boolean.TRUE).getRedactedContent();
        if (redacted.length() > 200) {
            return redacted.substring(0, 200) + "...";
        }
        return redacted;
    }
}
