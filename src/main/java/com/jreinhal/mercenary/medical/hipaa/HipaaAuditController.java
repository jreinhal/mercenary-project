package com.jreinhal.mercenary.medical.hipaa;

import com.jreinhal.mercenary.filter.SecurityContext;
import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.UserRole;
import com.jreinhal.mercenary.service.AuditService;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/hipaa/audit")
public class HipaaAuditController {
    private static final Logger log = LoggerFactory.getLogger(HipaaAuditController.class);
    private final HipaaAuditService hipaaAuditService;
    private final AuditService auditService;

    public HipaaAuditController(HipaaAuditService hipaaAuditService, AuditService auditService) {
        this.hipaaAuditService = hipaaAuditService;
        this.auditService = auditService;
    }

    @GetMapping("/events")
    public ResponseEntity<?> getEvents(@RequestParam(defaultValue="200") int limit,
                            @RequestParam(required=false) String since,
                            @RequestParam(required=false) String until,
                            @RequestParam(required=false) String type,
                            HttpServletRequest request) {
        User user = SecurityContext.getCurrentUser();
        if (user == null || !user.hasPermission(UserRole.Permission.VIEW_AUDIT)) {
            if (log.isWarnEnabled()) {
                log.warn("Unauthorized HIPAA audit log access attempt from: {}", user != null ? user.getUsername() : "ANONYMOUS");
            }
            this.auditService.logAccessDenied(user, "/api/hipaa/audit/events", "Missing VIEW_AUDIT permission", request);
            return ResponseEntity.status(403).body(Map.of("error", "ACCESS DENIED: HIPAA audit log access requires AUDITOR role."));
        }
        if (limit > 2000) {
            limit = 2000;
        }
        Optional<Instant> sinceInstant = parseInstant(since);
        Optional<Instant> untilInstant = parseInstant(until);
        Optional<HipaaAuditService.AuditEventType> eventType = parseType(type);
        List<HipaaAuditService.HipaaAuditEvent> events = this.hipaaAuditService.queryEvents(sinceInstant, untilInstant, eventType, limit);
        return ResponseEntity.ok(Map.of("count", events.size(), "events", events, "requestedBy", user.getUsername()));
    }

    @GetMapping("/export")
    public ResponseEntity<?> exportEvents(@RequestParam(defaultValue="2000") int limit,
                                          @RequestParam(defaultValue="json") String format,
                                          @RequestParam(required=false) String since,
                                          @RequestParam(required=false) String until,
                                          @RequestParam(required=false) String type,
                                          HttpServletRequest request) {
        User user = SecurityContext.getCurrentUser();
        if (user == null || !user.hasPermission(UserRole.Permission.VIEW_AUDIT)) {
            this.auditService.logAccessDenied(user, "/api/hipaa/audit/export", "Missing VIEW_AUDIT permission", request);
            return ResponseEntity.status(403).body(Map.of("error", "ACCESS DENIED: HIPAA audit log export requires AUDITOR role."));
        }
        Optional<Instant> sinceInstant = parseInstant(since);
        Optional<Instant> untilInstant = parseInstant(until);
        Optional<HipaaAuditService.AuditEventType> eventType = parseType(type);
        List<HipaaAuditService.HipaaAuditEvent> events = this.hipaaAuditService.queryEvents(sinceInstant, untilInstant, eventType, limit);
        if ("csv".equalsIgnoreCase(format)) {
            String csv = toCsv(events);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"hipaa_audit_export.csv\"")
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(csv);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"hipaa_audit_export.json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("count", events.size(), "events", events));
    }

    private Optional<Instant> parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(value));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private Optional<HipaaAuditService.AuditEventType> parseType(String type) {
        if (type == null || type.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(HipaaAuditService.AuditEventType.valueOf(type.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private String toCsv(List<HipaaAuditService.HipaaAuditEvent> events) {
        StringBuilder builder = new StringBuilder();
        builder.append("timestamp,eventType,username,userId,ipAddress,details\n");
        for (HipaaAuditService.HipaaAuditEvent event : events) {
            StringJoiner joiner = new StringJoiner(",");
            joiner.add(safe(event.timestamp() != null ? event.timestamp().toString() : ""));
            joiner.add(safe(event.eventType() != null ? event.eventType().name() : ""));
            joiner.add(safe(event.username()));
            joiner.add(safe(event.userId()));
            joiner.add(safe(event.ipAddress()));
            joiner.add(safe(event.details() != null ? event.details().toString() : ""));
            builder.append(joiner).append("\n");
        }
        return builder.toString();
    }

    private String safe(String value) {
        if (value == null) {
            return "";
        }
        // S2-07: Strip null bytes that could bypass sanitization in some CSV parsers
        String normalized = value.replace("\0", "").replace("\"", "\"\"").replace("\r", " ").stripLeading();
        // H-06: Prefix formula-triggering characters to prevent CSV injection in spreadsheet apps
        // stripLeading() prevents whitespace-prefixed payloads like " =cmd|..." from bypassing
        if (!normalized.isEmpty()) {
            char first = normalized.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t') {
                normalized = "'" + normalized;
            }
        }
        if (normalized.contains(",") || normalized.contains("\n")) {
            return "\"" + normalized + "\"";
        }
        return normalized;
    }
}
