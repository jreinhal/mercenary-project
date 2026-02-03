package com.jreinhal.mercenary.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jreinhal.mercenary.model.AuditEvent;
import com.jreinhal.mercenary.workspace.WorkspaceContext;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

@Service
public class AuditExportService {
    private static final int MAX_LIMIT = 5000;
    private final MongoTemplate mongoTemplate;
    private final ObjectMapper objectMapper;

    public AuditExportService(MongoTemplate mongoTemplate, ObjectMapper objectMapper) {
        this.mongoTemplate = mongoTemplate;
        this.objectMapper = objectMapper;
    }

    public AuditExportResult buildExport(Instant since, Instant until, int limit) {
        String workspaceId = WorkspaceContext.getCurrentWorkspaceId();
        Query query = new Query(Criteria.where("workspaceId").is(workspaceId));
        if (since != null || until != null) {
            Criteria tsCriteria = Criteria.where("timestamp");
            if (since != null) {
                tsCriteria = tsCriteria.gte(since);
            }
            if (until != null) {
                tsCriteria = tsCriteria.lte(until);
            }
            query.addCriteria(tsCriteria);
        }
        query.with(Sort.by(Sort.Direction.DESC, "timestamp"));
        if (limit > 0) {
            query.limit(Math.min(limit, MAX_LIMIT));
        }
        List<AuditEvent> events = mongoTemplate.find(query, AuditEvent.class, "audit_log");
        return new AuditExportResult(workspaceId, events);
    }

    public String toJson(List<AuditEvent> events) throws Exception {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(events);
    }

    public String toCsv(List<AuditEvent> events) {
        StringBuilder sb = new StringBuilder();
        sb.append("timestamp,eventType,userId,username,workspaceId,action,resourceType,resourceId,outcome,outcomeReason,sourceIp,sessionId\n");
        for (AuditEvent event : events) {
            sb.append(csv(event.getTimestamp() != null ? event.getTimestamp().toString() : ""))
              .append(',')
              .append(csv(event.getEventType() != null ? event.getEventType().name() : ""))
              .append(',')
              .append(csv(event.getUserId()))
              .append(',')
              .append(csv(event.getUsername()))
              .append(',')
              .append(csv(event.getWorkspaceId()))
              .append(',')
              .append(csv(event.getAction()))
              .append(',')
              .append(csv(event.getResourceType()))
              .append(',')
              .append(csv(event.getResourceId()))
              .append(',')
              .append(csv(event.getOutcome() != null ? event.getOutcome().name() : ""))
              .append(',')
              .append(csv(event.getOutcomeReason()))
              .append(',')
              .append(csv(event.getSourceIp()))
              .append(',')
              .append(csv(event.getSessionId()))
              .append('\n');
        }
        return sb.toString();
    }

    private String csv(String value) {
        if (value == null) {
            return "";
        }
        String sanitized = value.replace("\r", " ").replace("\n", " ");
        if (sanitized.contains(",") || sanitized.contains("\"")) {
            sanitized = sanitized.replace("\"", "\"\"");
            return "\"" + sanitized + "\"";
        }
        return sanitized;
    }

    public record AuditExportResult(String workspaceId, List<AuditEvent> events) {
    }
}
