package com.jreinhal.mercenary.reporting;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection="report_schedules")
public record ReportSchedule(
    @Id String id,
    String workspaceId,
    ReportType type,
    ReportFormat format,
    Cadence cadence,
    Integer windowDays,
    Integer limit,
    Instant nextRunAt,
    Instant lastRunAt,
    boolean enabled,
    String createdBy,
    Instant createdAt,
    Instant updatedAt
) {
    public enum ReportType {
        EXECUTIVE,
        AUDIT,
        SLA
    }

    public enum ReportFormat {
        JSON,
        CSV
    }

    public enum Cadence {
        DAILY,
        WEEKLY,
        MONTHLY
    }
}
