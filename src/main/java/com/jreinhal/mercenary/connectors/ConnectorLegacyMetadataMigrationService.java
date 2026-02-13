package com.jreinhal.mercenary.connectors;

import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Backfills connector metadata for legacy vector_store chunks that predate
 * connector tagging (connectorName + connectorSourceKey).
 */
@Service
public class ConnectorLegacyMetadataMigrationService {
    private static final Logger log = LoggerFactory.getLogger(ConnectorLegacyMetadataMigrationService.class);

    private static final String VECTOR_COLLECTION = "vector_store";
    private static final String CONNECTOR_STATE_COLLECTION = "connector_sync_state";
    private static final String MIGRATION_STATE_COLLECTION = "migration_state";
    private static final String MIGRATION_ID = "connector_legacy_metadata_backfill_v1";

    private final MongoTemplate mongoTemplate;

    public ConnectorLegacyMetadataMigrationService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public boolean isCompleted() {
        Query query = new Query(Criteria.where("_id").is(MIGRATION_ID));
        return this.mongoTemplate.exists(query, MIGRATION_STATE_COLLECTION);
    }

    public void markCompleted(MigrationResult result) {
        Query query = new Query(Criteria.where("_id").is(MIGRATION_ID));
        Update update = new Update()
                .set("completedAtEpochMs", System.currentTimeMillis())
                .set("completedAtIso", Instant.now().toString())
                .set("result", Map.of(
                        "sourceGroups", result.sourceGroups(),
                        "candidateSources", result.candidateSources(),
                        "candidateDocuments", result.candidateDocuments(),
                        "updatedSources", result.updatedSources(),
                        "updatedDocuments", result.updatedDocuments(),
                        "ambiguousSources", result.ambiguousSources()
                ))
                .setOnInsert("createdAtEpochMs", System.currentTimeMillis());
        this.mongoTemplate.upsert(query, update, MIGRATION_STATE_COLLECTION);
    }

    public MigrationResult migrateLegacyMetadata(boolean dryRun) {
        List<org.bson.Document> states = this.mongoTemplate.find(new Query(), org.bson.Document.class, CONNECTOR_STATE_COLLECTION);
        if (states.isEmpty()) {
            return new MigrationResult(0, 0, 0, 0L, 0, 0, dryRun);
        }

        Map<String, List<ConnectorState>> bySource = new LinkedHashMap<>();
        for (org.bson.Document stateDoc : states) {
            ConnectorState state = ConnectorState.fromDocument(stateDoc);
            if (state == null) {
                continue;
            }
            bySource.computeIfAbsent(state.groupKey(), key -> new ArrayList<>()).add(state);
        }

        int ambiguousSources = 0;
        int candidateSources = 0;
        long candidateDocuments = 0L;
        int updatedSources = 0;
        long updatedDocuments = 0L;

        for (Map.Entry<String, List<ConnectorState>> entry : bySource.entrySet()) {
            List<ConnectorState> groupStates = entry.getValue();
            if (groupStates.size() != 1) {
                ambiguousSources++;
                continue;
            }

            ConnectorState state = groupStates.get(0);
            Query legacyDocsQuery = this.buildLegacyDocsQuery(state);
            long count = this.mongoTemplate.count(legacyDocsQuery, VECTOR_COLLECTION);
            if (count <= 0) {
                continue;
            }
            candidateSources++;
            candidateDocuments += count;

            if (dryRun) {
                updatedSources++;
                updatedDocuments += count;
                continue;
            }

            Update update = new Update()
                    .set("metadata.connectorName", state.connectorName())
                    .set("metadata.connectorSourceKey", state.sourceKey())
                    .set("metadata.connectorMigrationBackfilled", true);
            if (StringUtils.hasText(state.fingerprint())) {
                update.set("metadata.connectorFingerprint", state.fingerprint());
            }

            UpdateResult result = this.mongoTemplate.updateMulti(legacyDocsQuery, update, VECTOR_COLLECTION);
            long modified = result.getModifiedCount();
            if (modified > 0) {
                updatedSources++;
                updatedDocuments += modified;
            }
        }

        if (log.isInfoEnabled()) {
            log.info(
                    "Connector legacy metadata migration: sourceGroups={} candidateSources={} candidateDocs={} " +
                            "updatedSources={} updatedDocs={} ambiguousSources={} dryRun={}",
                    bySource.size(),
                    candidateSources,
                    candidateDocuments,
                    updatedSources,
                    updatedDocuments,
                    ambiguousSources,
                    dryRun
            );
        }

        return new MigrationResult(
                bySource.size(),
                candidateSources,
                updatedSources,
                updatedDocuments,
                ambiguousSources,
                candidateDocuments,
                dryRun
        );
    }

    private Query buildLegacyDocsQuery(ConnectorState state) {
        Criteria matchSource = new Criteria().andOperator(
                Criteria.where("metadata.workspaceId").is(state.workspaceId()),
                Criteria.where("metadata.dept").is(state.department()),
                Criteria.where("metadata.source").is(state.sourceName())
        );
        Criteria missingConnectorMetadata = new Criteria().orOperator(
                Criteria.where("metadata.connectorName").exists(false),
                Criteria.where("metadata.connectorName").is(null),
                Criteria.where("metadata.connectorName").is("")
        );
        return new Query(new Criteria().andOperator(matchSource, missingConnectorMetadata));
    }

    private record ConnectorState(
            String connectorName,
            String department,
            String workspaceId,
            String sourceName,
            String sourceKey,
            String fingerprint) {
        static ConnectorState fromDocument(org.bson.Document doc) {
            if (doc == null) {
                return null;
            }
            String connectorName = asString(doc.get("connectorName"));
            String department = asString(doc.get("department"));
            String workspaceId = asString(doc.get("workspaceId"));
            String sourceName = asString(doc.get("sourceName"));
            String sourceKey = asString(doc.get("sourceKey"));
            String fingerprint = asString(doc.get("fingerprint"));

            if (!StringUtils.hasText(connectorName)
                    || !StringUtils.hasText(department)
                    || !StringUtils.hasText(workspaceId)
                    || !StringUtils.hasText(sourceName)
                    || !StringUtils.hasText(sourceKey)) {
                return null;
            }
            return new ConnectorState(connectorName, department, workspaceId, sourceName, sourceKey, fingerprint);
        }

        String groupKey() {
            return workspaceId + "|" + department + "|" + sourceName;
        }

        private static String asString(Object value) {
            return value != null ? value.toString() : "";
        }
    }

    public record MigrationResult(
            int sourceGroups,
            int candidateSources,
            int updatedSources,
            long updatedDocuments,
            int ambiguousSources,
            long candidateDocuments,
            boolean dryRun) {
    }
}
