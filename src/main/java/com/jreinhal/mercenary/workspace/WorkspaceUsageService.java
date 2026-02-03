package com.jreinhal.mercenary.workspace;

import com.jreinhal.mercenary.model.AuditEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceUsageService {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceUsageService.class);
    private final MongoTemplate mongoTemplate;

    public WorkspaceUsageService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public WorkspaceUsage getUsage(String workspaceId) {
        String resolved = workspaceId == null || workspaceId.isBlank()
                ? WorkspaceContext.getDefaultWorkspaceId()
                : workspaceId;
        long docs = countDistinctSources(resolved);
        long storageBytes = sumStorageBytes(resolved);
        long queriesToday = countQueriesToday(resolved);
        return new WorkspaceUsage(resolved, docs, storageBytes, queriesToday);
    }

    public long countDistinctSources(String workspaceId) {
        try {
            Document filter = new Document("metadata.workspaceId", workspaceId);
            List<String> sources = mongoTemplate.getCollection("vector_store")
                    .distinct("metadata.source", filter, String.class)
                    .into(new java.util.ArrayList<>());
            return sources.size();
        } catch (Exception e) {
            log.warn("Workspace usage: failed to count distinct sources for {}: {}", workspaceId, e.getMessage());
            return 0L;
        }
    }

    public long sumStorageBytes(String workspaceId) {
        try {
            Aggregation agg = Aggregation.newAggregation(
                    Aggregation.match(Criteria.where("metadata.workspaceId").is(workspaceId)),
                    Aggregation.group("metadata.source").max("metadata.fileSizeBytes").as("sizeBytes"),
                    Aggregation.group().sum("sizeBytes").as("totalBytes")
            );
            AggregationResults<Document> results = mongoTemplate.aggregate(agg, "vector_store", Document.class);
            Document result = results.getUniqueMappedResult();
            if (result == null || result.get("totalBytes") == null) {
                return 0L;
            }
            Object total = result.get("totalBytes");
            if (total instanceof Number num) {
                return num.longValue();
            }
            return Long.parseLong(String.valueOf(total));
        } catch (Exception e) {
            log.warn("Workspace usage: failed to sum storage bytes for {}: {}", workspaceId, e.getMessage());
            return 0L;
        }
    }

    public long countQueriesToday(String workspaceId) {
        try {
            Instant start = LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant();
            return mongoTemplate.count(
                    org.springframework.data.mongodb.core.query.Query.query(
                            Criteria.where("workspaceId").is(workspaceId)
                                    .and("eventType").is(AuditEvent.EventType.QUERY_EXECUTED)
                                    .and("timestamp").gte(start)),
                    "audit_log");
        } catch (Exception e) {
            log.warn("Workspace usage: failed to count queries for {}: {}", workspaceId, e.getMessage());
            return 0L;
        }
    }

    public record WorkspaceUsage(String workspaceId, long documents, long storageBytes, long queriesToday) {
    }
}
