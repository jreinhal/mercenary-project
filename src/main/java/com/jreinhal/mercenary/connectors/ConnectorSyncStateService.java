package com.jreinhal.mercenary.connectors;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.service.SourceDocumentService;
import com.jreinhal.mercenary.workspace.WorkspaceContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ConnectorSyncStateService {
    private static final Logger log = LoggerFactory.getLogger(ConnectorSyncStateService.class);
    private static final String STATE_COLLECTION = "connector_sync_state";
    private static final String VECTOR_COLLECTION = "vector_store";

    private final MongoTemplate mongoTemplate;
    private final SourceDocumentService sourceDocumentService;

    @Value("${sentinel.connectors.incremental-sync-enabled:true}")
    private boolean incrementalSyncEnabled;

    public ConnectorSyncStateService(MongoTemplate mongoTemplate, SourceDocumentService sourceDocumentService) {
        this.mongoTemplate = mongoTemplate;
        this.sourceDocumentService = sourceDocumentService;
    }

    public boolean isEnabled() {
        return this.incrementalSyncEnabled;
    }

    public String currentWorkspaceId() {
        return WorkspaceContext.getCurrentWorkspaceId();
    }

    public SourceState getState(String connectorName, Department department, String workspaceId, String sourceKey) {
        String id = this.buildStateId(connectorName, department, workspaceId, sourceKey);
        if (id == null) {
            return null;
        }
        Query query = new Query(Criteria.where("_id").is(id));
        org.bson.Document state = this.mongoTemplate.findOne(query, org.bson.Document.class, STATE_COLLECTION);
        if (state == null) {
            return null;
        }
        return new SourceState(
                sourceKey,
                asString(state.get("sourceName")),
                asString(state.get("fingerprint")),
                asString(state.get("contentHash")),
                asLong(state.get("lastSeenAtEpochMs"))
        );
    }

    public void markSeen(String connectorName, Department department, String workspaceId, String sourceKey,
                         String sourceName, String fingerprint, long seenAtEpochMs) {
        String id = this.buildStateId(connectorName, department, workspaceId, sourceKey);
        if (id == null) {
            return;
        }
        Query query = new Query(Criteria.where("_id").is(id));
        Update update = new Update()
                .set("connectorName", connectorName)
                .set("department", department.name())
                .set("workspaceId", workspaceId)
                .set("sourceKey", sourceKey)
                .set("sourceName", safeSourceName(sourceName))
                .set("lastSeenAtEpochMs", seenAtEpochMs)
                .set("updatedAtEpochMs", System.currentTimeMillis())
                .setOnInsert("createdAtEpochMs", System.currentTimeMillis());
        if (StringUtils.hasText(fingerprint)) {
            update.set("fingerprint", fingerprint);
        }
        this.mongoTemplate.upsert(query, update, STATE_COLLECTION);
    }

    public void recordIngested(String connectorName, Department department, String workspaceId, String sourceKey,
                               String sourceName, String fingerprint, String contentHash, long contentBytes,
                               long seenAtEpochMs) {
        String id = this.buildStateId(connectorName, department, workspaceId, sourceKey);
        if (id == null) {
            return;
        }
        String resolvedFingerprint = StringUtils.hasText(fingerprint) ? fingerprint : contentHash;
        Query query = new Query(Criteria.where("_id").is(id));
        Update update = new Update()
                .set("connectorName", connectorName)
                .set("department", department.name())
                .set("workspaceId", workspaceId)
                .set("sourceKey", sourceKey)
                .set("sourceName", safeSourceName(sourceName))
                .set("fingerprint", resolvedFingerprint)
                .set("contentHash", contentHash)
                .set("contentBytes", Math.max(0L, contentBytes))
                .set("lastSeenAtEpochMs", seenAtEpochMs)
                .set("lastSyncedAtEpochMs", System.currentTimeMillis())
                .set("updatedAtEpochMs", System.currentTimeMillis())
                .setOnInsert("createdAtEpochMs", System.currentTimeMillis());
        this.mongoTemplate.upsert(query, update, STATE_COLLECTION);
    }

    public long pruneSourceDocuments(String connectorName, Department department, String workspaceId,
                                     String sourceKey, String sourceName) {
        if (!StringUtils.hasText(connectorName) || department == null || !StringUtils.hasText(workspaceId)
                || !StringUtils.hasText(sourceKey)) {
            return 0L;
        }
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("metadata.workspaceId").is(workspaceId),
                Criteria.where("metadata.dept").is(department.name()),
                Criteria.where("metadata.connectorName").is(connectorName),
                Criteria.where("metadata.connectorSourceKey").is(sourceKey)
        ));
        long deleted = this.mongoTemplate.remove(query, VECTOR_COLLECTION).getDeletedCount();
        if (deleted > 0) {
            this.sourceDocumentService.removePdfSource(workspaceId, department, sourceName);
        }
        return deleted;
    }

    public int pruneRemovedSources(String connectorName, Department department, String workspaceId, long runStartedAtEpochMs) {
        if (!StringUtils.hasText(connectorName) || department == null || !StringUtils.hasText(workspaceId)) {
            return 0;
        }
        Query staleQuery = new Query(new Criteria().andOperator(
                Criteria.where("connectorName").is(connectorName),
                Criteria.where("department").is(department.name()),
                Criteria.where("workspaceId").is(workspaceId),
                Criteria.where("lastSeenAtEpochMs").lt(runStartedAtEpochMs)
        ));
        List<org.bson.Document> staleStates = this.mongoTemplate.find(staleQuery, org.bson.Document.class, STATE_COLLECTION);
        if (staleStates.isEmpty()) {
            return 0;
        }
        int prunedSources = 0;
        long prunedDocuments = 0L;
        List<Object> stateIds = new ArrayList<>();
        for (org.bson.Document state : staleStates) {
            String sourceKey = asString(state.get("sourceKey"));
            String sourceName = asString(state.get("sourceName"));
            if (!StringUtils.hasText(sourceKey)) {
                continue;
            }
            prunedDocuments += this.pruneSourceDocuments(connectorName, department, workspaceId, sourceKey, sourceName);
            Object id = state.get("_id");
            if (id != null) {
                stateIds.add(id);
            }
            prunedSources++;
        }
        if (!stateIds.isEmpty()) {
            Query deleteStateQuery = new Query(Criteria.where("_id").in(stateIds));
            this.mongoTemplate.remove(deleteStateQuery, STATE_COLLECTION);
        }
        if (prunedSources > 0 && log.isInfoEnabled()) {
            log.info("Connector {} pruned {} stale sources ({} vector documents) for dept={} workspace={}",
                    connectorName, prunedSources, prunedDocuments, department.name(), workspaceId);
        }
        return prunedSources;
    }

    public boolean matchesFingerprint(SourceState state, String fingerprint) {
        return state != null && StringUtils.hasText(fingerprint)
                && fingerprint.equals(state.fingerprint());
    }

    public boolean matchesContentHash(SourceState state, String contentHash) {
        return state != null && StringUtils.hasText(contentHash)
                && contentHash.equals(state.contentHash());
    }

    public String sha256(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(data);
            return HexFormat.of().formatHex(hashed).toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public String stableFingerprint(String... parts) {
        if (parts == null || parts.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            String value = part != null ? part.trim() : "";
            sb.append(value.replace('|', '_'));
        }
        return sb.toString();
    }

    public String stableFingerprint(Object... parts) {
        if (parts == null || parts.length == 0) {
            return "";
        }
        String[] stringParts = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            Object part = parts[i];
            if (part instanceof Instant instant) {
                stringParts[i] = String.valueOf(instant.toEpochMilli());
            } else if (part instanceof byte[] bytes) {
                stringParts[i] = this.sha256(bytes);
            } else {
                stringParts[i] = Objects.toString(part, "");
            }
        }
        return this.stableFingerprint(stringParts);
    }

    private String buildStateId(String connectorName, Department department, String workspaceId, String sourceKey) {
        if (!StringUtils.hasText(connectorName) || department == null
                || !StringUtils.hasText(workspaceId) || !StringUtils.hasText(sourceKey)) {
            return null;
        }
        String base = connectorName + "|" + department.name() + "|" + workspaceId + "|" + sourceKey;
        return this.sha256(base.getBytes(StandardCharsets.UTF_8));
    }

    private static String safeSourceName(String sourceName) {
        if (!StringUtils.hasText(sourceName)) {
            return "unknown";
        }
        String value = sourceName.trim();
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < value.length()) {
            value = value.substring(slash + 1);
        }
        return value;
    }

    private static String asString(Object value) {
        return value != null ? value.toString() : "";
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    public record SourceState(String sourceKey, String sourceName, String fingerprint, String contentHash,
                              long lastSeenAtEpochMs) {
    }
}
