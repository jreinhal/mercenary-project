package com.jreinhal.mercenary.reporting;

import com.jreinhal.mercenary.core.license.LicenseService;
import com.jreinhal.mercenary.model.AuditEvent;
import com.jreinhal.mercenary.workspace.WorkspaceContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

@Service
public class SlaReportService {
    private static final String TRACES_COLLECTION = "reasoning_traces";
    private static final String AUDIT_COLLECTION = "audit_log";
    private final MongoTemplate mongoTemplate;
    private final LicenseService licenseService;

    public SlaReportService(MongoTemplate mongoTemplate, LicenseService licenseService) {
        this.mongoTemplate = mongoTemplate;
        this.licenseService = licenseService;
    }

    public SlaReport buildReport(int days) {
        int windowDays = Math.max(1, days);
        Instant now = Instant.now();
        Instant since = now.minus(windowDays, ChronoUnit.DAYS);
        String workspaceId = WorkspaceContext.getCurrentWorkspaceId();

        List<Long> durations = fetchDurations(workspaceId, since);
        long totalTraces = durations.size();
        long totalQueries = countQueries(workspaceId, since);

        if (durations.isEmpty()) {
            return new SlaReport(workspaceId, licenseService.getEdition().name(), now, windowDays,
                    totalTraces, totalQueries, 0.0, 0L, 0L, 0L, 0L);
        }

        durations.sort(Comparator.naturalOrder());
        long p50 = percentile(durations, 0.50);
        long p95 = percentile(durations, 0.95);
        long p99 = percentile(durations, 0.99);
        long max = durations.get(durations.size() - 1);
        double avg = durations.stream().mapToLong(Long::longValue).average().orElse(0.0);

        return new SlaReport(workspaceId, licenseService.getEdition().name(), now, windowDays,
                totalTraces, totalQueries, avg, p50, p95, p99, max);
    }

    private List<Long> fetchDurations(String workspaceId, Instant since) {
        Query query = new Query(Criteria.where("workspaceId").is(workspaceId).and("timestamp").gte(since));
        List<Document> docs = mongoTemplate.find(query, Document.class, TRACES_COLLECTION);
        List<Long> durations = new ArrayList<>();
        for (Document doc : docs) {
            Object duration = doc.get("durationMs");
            if (duration instanceof Number num) {
                durations.add(num.longValue());
            } else if (duration != null) {
                try {
                    durations.add(Long.parseLong(String.valueOf(duration)));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return durations;
    }

    private long countQueries(String workspaceId, Instant since) {
        Query query = new Query(Criteria.where("workspaceId").is(workspaceId)
                .and("eventType").is(AuditEvent.EventType.QUERY_EXECUTED)
                .and("timestamp").gte(since));
        return mongoTemplate.count(query, AUDIT_COLLECTION);
    }

    private long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.ceil(percentile * values.size()) - 1;
        if (index < 0) {
            index = 0;
        }
        if (index >= values.size()) {
            index = values.size() - 1;
        }
        return values.get(index);
    }
}
