package com.jreinhal.mercenary.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jreinhal.mercenary.core.license.LicenseService;
import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.repository.ReportExportRepository;
import com.jreinhal.mercenary.repository.ReportScheduleRepository;
import com.jreinhal.mercenary.workspace.WorkspaceContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ReportScheduleService {
    private static final Logger log = LoggerFactory.getLogger(ReportScheduleService.class);
    private final ReportScheduleRepository scheduleRepository;
    private final ReportExportRepository exportRepository;
    private final ExecutiveReportService executiveReportService;
    private final AuditExportService auditExportService;
    private final SlaReportService slaReportService;
    private final ObjectMapper objectMapper;
    private final LicenseService licenseService;

    @Value("${sentinel.reporting.schedules.enabled:false}")
    private boolean schedulesEnabled;

    @Value("${sentinel.reporting.schedules.allow-regulated:false}")
    private boolean schedulesAllowRegulated;

    public ReportScheduleService(ReportScheduleRepository scheduleRepository,
                                 ReportExportRepository exportRepository,
                                 ExecutiveReportService executiveReportService,
                                 AuditExportService auditExportService,
                                 SlaReportService slaReportService,
                                 ObjectMapper objectMapper,
                                 LicenseService licenseService) {
        this.scheduleRepository = scheduleRepository;
        this.exportRepository = exportRepository;
        this.executiveReportService = executiveReportService;
        this.auditExportService = auditExportService;
        this.slaReportService = slaReportService;
        this.objectMapper = objectMapper;
        this.licenseService = licenseService;
    }

    public List<ReportSchedule> listSchedules() {
        String workspaceId = WorkspaceContext.getCurrentWorkspaceId();
        return scheduleRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
    }

    public ReportSchedule createSchedule(ReportScheduleRequest request, User actor) {
        String workspaceId = WorkspaceContext.getCurrentWorkspaceId();
        Instant now = Instant.now();
        ReportSchedule.ReportType type = request.type();
        ReportSchedule.ReportFormat format = request.format();
        ReportSchedule.Cadence cadence = request.cadence();
        int windowDays = request.windowDays() != null ? Math.max(1, request.windowDays()) : defaultWindow(type);
        int limit = request.limit() != null ? Math.max(1, request.limit()) : defaultLimit(type);
        Instant nextRun = computeNextRun(now, cadence);
        ReportSchedule schedule = new ReportSchedule(
                UUID.randomUUID().toString(),
                workspaceId,
                type,
                format,
                cadence,
                windowDays,
                limit,
                nextRun,
                null,
                true,
                actor != null ? actor.getUsername() : "system",
                now,
                now
        );
        return scheduleRepository.save(schedule);
    }

    public ReportSchedule updateSchedule(String scheduleId, boolean enabled) {
        Optional<ReportSchedule> existing = scheduleRepository.findById(scheduleId);
        if (existing.isEmpty()) {
            throw new IllegalArgumentException("Schedule not found");
        }
        ReportSchedule schedule = existing.get();
        ReportSchedule updated = new ReportSchedule(schedule.id(), schedule.workspaceId(), schedule.type(), schedule.format(),
                schedule.cadence(), schedule.windowDays(), schedule.limit(), schedule.nextRunAt(), schedule.lastRunAt(),
                enabled, schedule.createdBy(), schedule.createdAt(), Instant.now());
        return scheduleRepository.save(updated);
    }

    public ReportExport runSchedule(String scheduleId, User actor) {
        Optional<ReportSchedule> existing = scheduleRepository.findById(scheduleId);
        if (existing.isEmpty()) {
            throw new IllegalArgumentException("Schedule not found");
        }
        return executeSchedule(existing.get(), actor != null ? actor.getUsername() : "system");
    }

    public List<ReportExport> listExports(int limit) {
        String workspaceId = WorkspaceContext.getCurrentWorkspaceId();
        List<ReportExport> exports = exportRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
        if (limit > 0 && exports.size() > limit) {
            return exports.subList(0, limit);
        }
        return exports;
    }

    public Optional<ReportExport> getExport(String id) {
        return exportRepository.findById(id);
    }

    @Scheduled(fixedDelayString = "${sentinel.reporting.schedules.interval-ms:300000}")
    public void runDueSchedules() {
        if (!schedulesAllowed()) {
            return;
        }
        Instant now = Instant.now();
        List<ReportSchedule> due = scheduleRepository.findByEnabledTrueAndNextRunAtBefore(now);
        for (ReportSchedule schedule : due) {
            try {
                executeSchedule(schedule, "system");
            } catch (Exception e) {
                log.warn("Scheduled report failed: {} -> {}", schedule.id(), e.getMessage());
            }
        }
    }

    public boolean schedulesAllowed() {
        if (!schedulesEnabled) {
            return false;
        }
        if (isRegulatedEdition() && !schedulesAllowRegulated) {
            return false;
        }
        return true;
    }

    private boolean isRegulatedEdition() {
        LicenseService.Edition edition = licenseService.getEdition();
        return edition == LicenseService.Edition.MEDICAL || edition == LicenseService.Edition.GOVERNMENT;
    }

    private ReportExport executeSchedule(ReportSchedule schedule, String actor) {
        String originalWorkspace = WorkspaceContext.getCurrentWorkspaceId();
        try {
            WorkspaceContext.setCurrentWorkspaceId(schedule.workspaceId());
            Instant now = Instant.now();
            String content = buildContent(schedule);
            String summary = schedule.type().name() + " report (" + schedule.windowDays() + "d)";
            ReportExport export = new ReportExport(UUID.randomUUID().toString(), schedule.workspaceId(),
                    schedule.type(), schedule.format(), now, actor, summary, content);
            exportRepository.save(export);

            Instant nextRun = computeNextRun(now, schedule.cadence());
            ReportSchedule updated = new ReportSchedule(schedule.id(), schedule.workspaceId(), schedule.type(), schedule.format(),
                    schedule.cadence(), schedule.windowDays(), schedule.limit(), nextRun, now, schedule.enabled(),
                    schedule.createdBy(), schedule.createdAt(), now);
            scheduleRepository.save(updated);
            return export;
        } finally {
            WorkspaceContext.setCurrentWorkspaceId(originalWorkspace);
        }
    }

    private String buildContent(ReportSchedule schedule) {
        try {
            switch (schedule.type()) {
                case EXECUTIVE -> {
                    ExecutiveReport report = executiveReportService.buildExecutiveReport(schedule.windowDays());
                    return schedule.format() == ReportSchedule.ReportFormat.CSV
                            ? executiveToCsv(report)
                            : objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
                }
                case SLA -> {
                    SlaReport report = slaReportService.buildReport(schedule.windowDays());
                    return schedule.format() == ReportSchedule.ReportFormat.CSV
                            ? slaToCsv(report)
                            : objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
                }
                case AUDIT -> {
                    AuditExportService.AuditExportResult result = auditExportService.buildExport(
                            Instant.now().minus(schedule.windowDays(), ChronoUnit.DAYS), null, schedule.limit());
                    return schedule.format() == ReportSchedule.ReportFormat.CSV
                            ? auditExportService.toCsv(result.events())
                            : auditExportService.toJson(result.events());
                }
                default -> throw new IllegalArgumentException("Unsupported report type");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build report content: " + e.getMessage(), e);
        }
    }

    private Instant computeNextRun(Instant from, ReportSchedule.Cadence cadence) {
        return switch (cadence) {
            case DAILY -> from.plus(1, ChronoUnit.DAYS);
            case WEEKLY -> from.plus(7, ChronoUnit.DAYS);
            case MONTHLY -> from.plus(30, ChronoUnit.DAYS);
        };
    }

    private int defaultWindow(ReportSchedule.ReportType type) {
        return switch (type) {
            case EXECUTIVE -> 30;
            case SLA -> 7;
            case AUDIT -> 7;
        };
    }

    private int defaultLimit(ReportSchedule.ReportType type) {
        return switch (type) {
            case AUDIT -> 1000;
            default -> 0;
        };
    }

    private String executiveToCsv(ExecutiveReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("generatedAt,workspaceId,edition,windowDays,documents,activeSessions,traces,queries,ingestions,accessDenied,authFailures,securityAlerts,promptInjection,hipaaEvents,breakGlassEvents\n");
        sb.append(report.generatedAt()).append(',')
          .append(report.workspaceId()).append(',')
          .append(report.edition()).append(',')
          .append(report.windowDays()).append(',')
          .append(report.usage().documents()).append(',')
          .append(report.usage().activeSessions()).append(',')
          .append(report.usage().reasoningTraces()).append(',')
          .append(report.usage().queries()).append(',')
          .append(report.usage().ingestions()).append(',')
          .append(report.security().accessDenied()).append(',')
          .append(report.security().authFailures()).append(',')
          .append(report.security().securityAlerts()).append(',')
          .append(report.security().promptInjectionDetections()).append(',')
          .append(report.security().hipaaEvents()).append(',')
          .append(report.security().breakGlassEvents()).append('\n');
        return sb.toString();
    }

    private String slaToCsv(SlaReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("generatedAt,workspaceId,edition,windowDays,totalTraces,totalQueries,avgLatencyMs,p50LatencyMs,p95LatencyMs,p99LatencyMs,maxLatencyMs\n");
        sb.append(report.generatedAt()).append(',')
          .append(report.workspaceId()).append(',')
          .append(report.edition()).append(',')
          .append(report.windowDays()).append(',')
          .append(report.totalTraces()).append(',')
          .append(report.totalQueries()).append(',')
          .append(String.format("%.2f", report.avgLatencyMs())).append(',')
          .append(report.p50LatencyMs()).append(',')
          .append(report.p95LatencyMs()).append(',')
          .append(report.p99LatencyMs()).append(',')
          .append(report.maxLatencyMs()).append('\n');
        return sb.toString();
    }

    public record ReportScheduleRequest(ReportSchedule.ReportType type,
                                        ReportSchedule.ReportFormat format,
                                        ReportSchedule.Cadence cadence,
                                        Integer windowDays,
                                        Integer limit) {
    }
}
