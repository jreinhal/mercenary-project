package com.jreinhal.mercenary.reporting;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection="report_exports")
public record ReportExport(
    @Id String id,
    String workspaceId,
    ReportSchedule.ReportType type,
    ReportSchedule.ReportFormat format,
    Instant createdAt,
    String createdBy,
    String summary,
    String content
) {
}
