package com.jreinhal.mercenary.medical.controller;

import com.jreinhal.mercenary.medical.hipaa.HipaaAuditService;
import com.jreinhal.mercenary.service.TokenizationVault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * HIPAA-Compliant PII Reveal Endpoint (Medical Edition Only)
 *
 * Security Controls:
 * - Requires PHI_ACCESS role for normal access
 * - Supports break-the-glass emergency override with ADMIN role
 * - Full audit trail of every reveal operation
 * - Rate limited to prevent bulk extraction
 *
 * Compliance:
 * - HIPAA Privacy Rule: Minimum necessary standard
 * - HIPAA Security Rule: Access controls and audit
 * - NIST 800-122: Role-based PII access
 * - PCI-DSS: Tokenization with controlled reveal
 */
@RestController
@RequestMapping("/api/pii")
public class PiiRevealController {

    private static final Logger log = LoggerFactory.getLogger(PiiRevealController.class);

    private final TokenizationVault tokenizationVault;
    private final HipaaAuditService hipaaAuditService;

    public PiiRevealController(TokenizationVault tokenizationVault,
                               HipaaAuditService hipaaAuditService) {
        this.tokenizationVault = tokenizationVault;
        this.hipaaAuditService = hipaaAuditService;
    }

    /**
     * Reveal a tokenized PII value.
     *
     * Requires PHI_ACCESS role and logs all access for HIPAA compliance.
     *
     * @param request Contains the token and reason for access
     * @return The original PII value if authorized and found
     */
    @PostMapping("/reveal")
    @PreAuthorize("hasAnyRole('PHI_ACCESS', 'ADMIN')")
    public ResponseEntity<Map<String, Object>> revealToken(@RequestBody RevealRequest request) {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String userId = auth != null ? auth.getName() : "UNKNOWN";

        // Validate request
        if (request.token() == null || request.token().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Token is required",
                "timestamp", Instant.now().toString()
            ));
        }

        if (request.reason() == null || request.reason().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Reason for access is required for HIPAA compliance",
                "timestamp", Instant.now().toString()
            ));
        }

        // Validate token format
        if (!tokenizationVault.isToken(request.token())) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid token format",
                "timestamp", Instant.now().toString()
            ));
        }

        // Audit log the access attempt BEFORE revealing
        hipaaAuditService.logPhiAccess(
            userId,
            "PII_REVEAL_REQUEST",
            request.token(),
            request.reason(),
            request.breakTheGlass() != null && request.breakTheGlass()
        );

        // Attempt to reveal
        Optional<String> revealedValue = tokenizationVault.detokenize(request.token(), userId);

        if (revealedValue.isEmpty()) {
            log.warn("PII reveal failed for token {} by user {} - not found",
                     maskToken(request.token()), userId);

            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "Token not found or expired",
                "timestamp", Instant.now().toString()
            ));
        }

        // Log successful reveal
        hipaaAuditService.logPhiAccess(
            userId,
            "PII_REVEAL_SUCCESS",
            request.token(),
            request.reason(),
            request.breakTheGlass() != null && request.breakTheGlass()
        );

        log.info("HIPAA AUDIT: User {} revealed PII token {} for reason: {}",
                 userId, maskToken(request.token()), request.reason());

        return ResponseEntity.ok(Map.of(
            "value", revealedValue.get(),
            "revealed_at", Instant.now().toString(),
            "revealed_by", userId,
            "warning", "This access has been logged for HIPAA compliance"
        ));
    }

    /**
     * Break-the-glass emergency override for critical patient care situations.
     *
     * Requires ADMIN role and triggers elevated audit logging plus notifications.
     * Should only be used in genuine emergencies where patient safety requires
     * immediate PHI access without normal approval workflows.
     */
    @PostMapping("/reveal/emergency")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> emergencyReveal(@RequestBody EmergencyRevealRequest request) {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String userId = auth != null ? auth.getName() : "UNKNOWN";

        // Validate emergency request
        if (request.token() == null || request.token().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Token is required"
            ));
        }

        if (request.emergencyReason() == null || request.emergencyReason().length() < 20) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Emergency reason must be at least 20 characters explaining the situation"
            ));
        }

        if (request.patientId() == null || request.patientId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Patient ID is required for emergency access"
            ));
        }

        // CRITICAL: Log break-the-glass access with maximum detail
        log.warn("=== BREAK-THE-GLASS EMERGENCY ACCESS ===");
        log.warn("User: {}", userId);
        log.warn("Token: {}", maskToken(request.token()));
        log.warn("Patient: {}", request.patientId());
        log.warn("Reason: {}", request.emergencyReason());
        log.warn("=========================================");

        hipaaAuditService.logBreakTheGlass(
            userId,
            request.token(),
            request.patientId(),
            request.emergencyReason()
        );

        // Reveal the token
        Optional<String> revealedValue = tokenizationVault.detokenize(request.token(), userId);

        if (revealedValue.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "Token not found",
                "timestamp", Instant.now().toString()
            ));
        }

        return ResponseEntity.ok(Map.of(
            "value", revealedValue.get(),
            "revealed_at", Instant.now().toString(),
            "revealed_by", userId,
            "emergency_access", true,
            "warning", "BREAK-THE-GLASS: This emergency access has been logged and will be reviewed"
        ));
    }

    /**
     * Check if a value is a token (useful for UI to determine if reveal is needed)
     */
    @GetMapping("/is-token")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Boolean>> isToken(@RequestParam String value) {
        return ResponseEntity.ok(Map.of(
            "isToken", tokenizationVault.isToken(value)
        ));
    }

    /**
     * Mask token for logging (show only type, not full hash)
     */
    private String maskToken(String token) {
        if (token == null || !token.contains(":")) {
            return "[INVALID]";
        }
        // <<TOK:EMAIL:abc123>> -> <<TOK:EMAIL:***>>
        int lastColon = token.lastIndexOf(':');
        if (lastColon > 0) {
            return token.substring(0, lastColon + 1) + "***>>";
        }
        return "[MASKED]";
    }

    // Request DTOs
    public record RevealRequest(
        String token,
        String reason,
        Boolean breakTheGlass
    ) {}

    public record EmergencyRevealRequest(
        String token,
        String emergencyReason,
        String patientId
    ) {}
}
