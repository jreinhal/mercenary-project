package com.jreinhal.mercenary.professional.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.filter.SecurityContext;
import com.jreinhal.mercenary.medical.hipaa.HipaaAuditService;
import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.reporting.AuditExportService;
import com.jreinhal.mercenary.reporting.ExecutiveReport;
import com.jreinhal.mercenary.reporting.ExecutiveReportService;
import com.jreinhal.mercenary.reporting.ReportExport;
import com.jreinhal.mercenary.reporting.ReportSchedule;
import com.jreinhal.mercenary.reporting.ReportScheduleService;
import com.jreinhal.mercenary.reporting.SlaReport;
import com.jreinhal.mercenary.reporting.SlaReportService;
import com.jreinhal.mercenary.service.HipaaPolicy;
import com.jreinhal.mercenary.workspace.WorkspaceContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value={"/api/admin/reports"})
@PreAuthorize(value="hasRole('ADMIN')")
public class ReportingAdminController {
    private final ExecutiveReportService reportService;
    private final SlaReportService slaReportService;
    private final AuditExportService auditExportService;
    private final ReportScheduleService scheduleService;
    private final HipaaAuditService hipaaAuditService;
    private final HipaaPolicy hipaaPolicy;
    private final ObjectMapper objectMapper;

    public ReportingAdminController(ExecutiveReportService reportService,
                                    SlaReportService slaReportService,
                                    AuditExportService auditExportService,
                                    ReportScheduleService scheduleService,
                                    HipaaAuditService hipaaAuditService,
                                    HipaaPolicy hipaaPolicy,
                                    ObjectMapper objectMapper) {
        this.reportService = reportService;
        this.slaReportService = slaReportService;
        this.auditExportService = auditExportService;
        this.scheduleService = scheduleService;
        this.hipaaAuditService = hipaaAuditService;
        this.hipaaPolicy = hipaaPolicy;
        this.objectMapper = objectMapper;
    }

    @GetMapping(value={"/executive"})
    public ResponseEntity<ExecutiveReport> getExecutiveReport(
            @RequestParam(value = "days", defaultValue = "30") int days) {
        return ResponseEntity.ok(reportService.buildExecutiveReport(days));
    }

    @GetMapping(value={"/sla"})
    public ResponseEntity<SlaReport> getSlaReport(@RequestParam(value = "days", defaultValue = "7") int days) {
        return ResponseEntity.ok(slaReportService.buildReport(days));
    }

    @GetMapping(value={"/audit/export"}, produces={"application/json", "text/csv"})
    public ResponseEntity<String> exportAudit(
            @RequestParam(value = "format", defaultValue = "json") String format,
            @RequestParam(value = "days", required = false) Integer days,
            @RequestParam(value = "since", required = false) String since,
            @RequestParam(value = "until", required = false) String until,
            @RequestParam(value = "limit", defaultValue = "1000") int limit) {
        ReportSchedule.ReportFormat resolvedFormat = resolveFormat(format);
        Instant sinceInstant;
        Instant untilInstant;
        try {
            sinceInstant = resolveSince(days, since);
            untilInstant = resolveInstant(until);
        } catch (IllegalArgumentException e) {
            // S2-06: Generic message — don't reflect user input in error response
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"error\": \"Invalid timestamp parameter\"}");
        }
        AuditExportService.AuditExportResult result = auditExportService.buildExport(sinceInstant, untilInstant, limit);
        String content;
        try {
            content = resolvedFormat == ReportSchedule.ReportFormat.CSV
                    ? auditExportService.toCsv(result.events())
                    : auditExportService.toJson(result.events());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\": \"Failed to build audit export\"}");
        }
        String filename = "audit_export_" + result.workspaceId() + "." + (resolvedFormat == ReportSchedule.ReportFormat.CSV ? "csv" : "json");
        return ResponseEntity.ok()
                .headers(exportHeaders(resolvedFormat, filename))
                .body(content);
    }

    @GetMapping(value={"/hipaa/audit"})
    public ResponseEntity<?> getHipaaAudit(
            @RequestParam(value = "since", required = false) String since,
            @RequestParam(value = "until", required = false) String until,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "limit", defaultValue = "500") int limit) {
        if (!hipaaPolicy.isStrict(Department.MEDICAL)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "HIPAA audit log is only available for Medical strict mode"));
        }
        Optional<Instant> sinceInstant;
        Optional<Instant> untilInstant;
        Optional<HipaaAuditService.AuditEventType> eventType;
        try {
            sinceInstant = Optional.ofNullable(resolveInstant(since));
            untilInstant = Optional.ofNullable(resolveInstant(until));
            eventType = resolveHipaaType(type);
        } catch (IllegalArgumentException e) {
            // S2-06: Generic message — don't reflect user input in error response
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "Invalid timestamp or event type parameter"));
        }
        return ResponseEntity.ok(hipaaAuditService.queryEvents(sinceInstant, untilInstant, eventType, limit));
    }

    @GetMapping(value={"/hipaa/export"}, produces={"application/json", "text/csv"})
    public ResponseEntity<String> exportHipaaAudit(
            @RequestParam(value = "format", defaultValue = "json") String format,
            @RequestParam(value = "days", required = false) Integer days,
            @RequestParam(value = "since", required = false) String since,
            @RequestParam(value = "until", required = false) String until,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "limit", defaultValue = "1000") int limit) {
        if (!hipaaPolicy.isStrict(Department.MEDICAL)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("{\"error\": \"HIPAA audit export is only available for Medical strict mode\"}");
        }
        ReportSchedule.ReportFormat resolvedFormat = resolveFormat(format);
        Instant sinceInstant;
        Instant untilInstant;
        Optional<HipaaAuditService.AuditEventType> eventType;
        try {
            sinceInstant = resolveSince(days, since);
            untilInstant = resolveInstant(until);
            eventType = resolveHipaaType(type);
        } catch (IllegalArgumentException e) {
            // S2-06: Generic message — don't reflect user input in error response
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"error\": \"Invalid timestamp or event type parameter\"}");
        }
        List<HipaaAuditService.HipaaAuditEvent> events = hipaaAuditService.queryEvents(
                Optional.ofNullable(sinceInstant), Optional.ofNullable(untilInstant), eventType, limit);
        String content;
        try {
            content = resolvedFormat == ReportSchedule.ReportFormat.CSV
                    ? hipaaToCsv(events)
                    : objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(events);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\": \"Failed to build HIPAA audit export\"}");
        }
        String filename = "hipaa_audit_export_" + WorkspaceContext.getCurrentWorkspaceId() + "." + (resolvedFormat == ReportSchedule.ReportFormat.CSV ? "csv" : "json");
        return ResponseEntity.ok()
                .headers(exportHeaders(resolvedFormat, filename))
                .body(content);
    }

    @GetMapping(value={"/schedules"})
    public ResponseEntity<?> listSchedules() {
        if (!scheduleService.schedulesAllowed()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Report schedules are disabled for this edition"));
        }
        return ResponseEntity.ok(scheduleService.listSchedules());
    }

    @PostMapping(value={"/schedules"})
    public ResponseEntity<?> createSchedule(@RequestBody ScheduleRequest request) {
        if (!scheduleService.schedulesAllowed()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Report schedules are disabled for this edition"));
        }
        if (request == null || request.type() == null || request.format() == null || request.cadence() == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "Schedule type, format, and cadence are required"));
        }
        User actor = SecurityContext.getCurrentUser();
        ReportSchedule schedule = scheduleService.createSchedule(new ReportScheduleService.ReportScheduleRequest(
                request.type(), request.format(), request.cadence(), request.windowDays(), request.limit()), actor);
        return ResponseEntity.ok(schedule);
    }

    @PatchMapping(value={"/schedules/{scheduleId}"})
    public ResponseEntity<?> updateSchedule(@PathVariable String scheduleId, @RequestBody ScheduleToggleRequest request) {
        if (!scheduleService.schedulesAllowed()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Report schedules are disabled for this edition"));
        }
        if (request == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "Schedule toggle payload required"));
        }
        ReportSchedule schedule = scheduleService.updateSchedule(scheduleId, request.enabled());
        return ResponseEntity.ok(schedule);
    }

    @PostMapping(value={"/schedules/{scheduleId}/run"})
    public ResponseEntity<?> runSchedule(@PathVariable String scheduleId) {
        if (!scheduleService.schedulesAllowed()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Report schedules are disabled for this edition"));
        }
        User actor = SecurityContext.getCurrentUser();
        ReportExport export = scheduleService.runSchedule(scheduleId, actor);
        return ResponseEntity.ok(export);
    }

    @GetMapping(value={"/exports"})
    public ResponseEntity<List<ExportSummary>> listExports(@RequestParam(value = "limit", defaultValue = "25") int limit) {
        List<ReportExport> exports = scheduleService.listExports(limit);
        List<ExportSummary> summaries = exports.stream()
                .map(this::toSummary)
                .toList();
        return ResponseEntity.ok(summaries);
    }

    @GetMapping(value={"/exports/{exportId}"})
    public ResponseEntity<?> getExport(@PathVariable String exportId) {
        String workspaceId = WorkspaceContext.getCurrentWorkspaceId();
        Optional<ReportExport> export = scheduleService.getExport(exportId);
        if (export.isEmpty() || !workspaceId.equals(export.get().workspaceId())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Export not found"));
        }
        return ResponseEntity.ok(export.get());
    }

    private ReportSchedule.ReportFormat resolveFormat(String format) {
        if (format == null) {
            return ReportSchedule.ReportFormat.JSON;
        }
        try {
            return ReportSchedule.ReportFormat.valueOf(format.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ReportSchedule.ReportFormat.JSON;
        }
    }

    private Instant resolveSince(Integer days, String since) {
        if (days != null && days > 0) {
            return Instant.now().minus(days, ChronoUnit.DAYS);
        }
        return resolveInstant(since);
    }

    private Instant resolveInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid timestamp: " + value);
        }
    }

    private Optional<HipaaAuditService.AuditEventType> resolveHipaaType(String type) {
        if (type == null || type.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(HipaaAuditService.AuditEventType.valueOf(type.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid HIPAA audit type: " + type);
        }
    }

    private HttpHeaders exportHeaders(ReportSchedule.ReportFormat format, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(format == ReportSchedule.ReportFormat.CSV
                ? MediaType.valueOf("text/csv")
                : MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename);
        return headers;
    }

    private String hipaaToCsv(List<HipaaAuditService.HipaaAuditEvent> events) {
        StringBuilder sb = new StringBuilder();
        sb.append("timestamp,eventType,username,userId,workspaceId,details\n");
        for (HipaaAuditService.HipaaAuditEvent event : events) {
            sb.append(csv(event.timestamp() != null ? event.timestamp().toString() : ""))
              .append(',')
              .append(csv(event.eventType() != null ? event.eventType().name() : ""))
              .append(',')
              .append(csv(event.username()))
              .append(',')
              .append(csv(event.userId()))
              .append(',')
              .append(csv(event.workspaceId()))
              .append(',')
              .append(csv(event.details() != null ? event.details().toString() : ""))
              .append('\n');
        }
        return sb.toString();
    }

    private String csv(String value) {
        if (value == null) {
            return "";
        }
        // S2-08: Strip null bytes that could bypass sanitization in some CSV parsers
        String sanitized = value.replace("\0", "").replace("\r", " ").replace("\n", " ").stripLeading();
        // H-06: Prefix formula-triggering characters to prevent CSV injection in spreadsheet apps
        // stripLeading() prevents whitespace-prefixed payloads like " =cmd|..." from bypassing
        if (!sanitized.isEmpty()) {
            char first = sanitized.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t') {
                sanitized = "'" + sanitized;
            }
        }
        if (sanitized.contains(",") || sanitized.contains("\"")) {
            sanitized = sanitized.replace("\"", "\"\"");
            return "\"" + sanitized + "\"";
        }
        return sanitized;
    }

    public record ScheduleRequest(ReportSchedule.ReportType type,
                                  ReportSchedule.ReportFormat format,
                                  ReportSchedule.Cadence cadence,
                                  Integer windowDays,
                                  Integer limit) {
    }

    public record ScheduleToggleRequest(boolean enabled) {
    }

    public record ExportSummary(String id,
                                String workspaceId,
                                ReportSchedule.ReportType type,
                                ReportSchedule.ReportFormat format,
                                Instant createdAt,
                                String createdBy,
                                String summary,
                                int contentLength) {
    }

    private ExportSummary toSummary(ReportExport export) {
        int contentLength = export.content() != null ? export.content().length() : 0;
        return new ExportSummary(export.id(), export.workspaceId(), export.type(), export.format(),
                export.createdAt(), export.createdBy(), export.summary(), contentLength);
    }
}
