package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.util.DocumentTemporalMetadataExtractor;
import com.jreinhal.mercenary.workspace.WorkspaceContext;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Metadata-only backfill pass for existing vector-store chunks.
 *
 * This enables shipping temporal filtering without forcing a full re-ingest.
 * Intentionally not wired to an endpoint by default (operators can call it from
 * a controlled maintenance path).
 */
@Service
public class MetadataBackfillService {
    private static final Logger log = LoggerFactory.getLogger(MetadataBackfillService.class);
    private static final String VECTOR_STORE_COLLECTION = "vector_store";

    private final MongoTemplate mongoTemplate;

    @Value("${sentinel.metadata-backfill.max-sources:250}")
    private int maxSources;

    public MetadataBackfillService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Backfills {@code metadata.documentYear} and (when available) {@code metadata.documentDateEpoch}
     * for documents missing them in the current workspace.
     *
     * @param department e.g. GOVERNMENT / MEDICAL / ENTERPRISE
     * @param dryRun when true, computes counts but does not persist updates
     */
    public BackfillResult backfillTemporalMetadata(String department, boolean dryRun) {
        String workspaceId = WorkspaceContext.getCurrentWorkspaceId();
        return backfillTemporalMetadata(department, workspaceId, dryRun);
    }

    public BackfillResult backfillTemporalMetadata(String department, String workspaceId, boolean dryRun) {
        if (department == null || department.isBlank() || workspaceId == null || workspaceId.isBlank()) {
            return new BackfillResult(0, 0, 0, 0, dryRun);
        }

        // Identify candidate sources by sampling chunks missing documentYear.
        Query seedQuery = new Query(Criteria.where("metadata.dept").is(department)
                .and("metadata.workspaceId").is(workspaceId)
                .and("metadata.documentYear").exists(false));
        seedQuery.limit(Math.max(1, this.maxSources));

        List<Map> seeds = this.mongoTemplate.find(seedQuery, Map.class, VECTOR_STORE_COLLECTION);
        Set<String> sources = new HashSet<>();
        for (Map seed : seeds) {
            Object meta = seed.get("metadata");
            if (!(meta instanceof Map<?, ?> metaMap)) {
                continue;
            }
            Object source = metaMap.get("source");
            if (source != null && !source.toString().isBlank()) {
                sources.add(source.toString());
            }
        }

        int sourcesScanned = 0;
        int sourcesUpdated = 0;
        long docsUpdated = 0;
        int sourcesSkipped = 0;

        for (String source : sources) {
            sourcesScanned++;
            Query docQuery = new Query(Criteria.where("metadata.dept").is(department)
                    .and("metadata.workspaceId").is(workspaceId)
                    .and("metadata.source").is(source));
            docQuery.limit(5);
            List<Map> docs = this.mongoTemplate.find(docQuery, Map.class, VECTOR_STORE_COLLECTION);
            String textSample = docs.stream()
                    .map(d -> d.get("content"))
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .filter(c -> !c.isBlank())
                    .findFirst()
                    .orElse("");
            DocumentTemporalMetadataExtractor.TemporalMetadata extracted =
                    DocumentTemporalMetadataExtractor.extractFromText(textSample);
            if (extracted == null || extracted.isEmpty() || extracted.documentYear() == null) {
                sourcesSkipped++;
                continue;
            }

            if (dryRun) {
                sourcesUpdated++;
                continue;
            }

            Update update = new Update()
                    .set("metadata.documentYear", extracted.documentYear())
                    .set("metadata.documentDateSource", "backfill_text");
            if (extracted.documentDateEpoch() != null) {
                update.set("metadata.documentDateEpoch", extracted.documentDateEpoch());
            }

            Query updateQuery = new Query(Criteria.where("metadata.dept").is(department)
                    .and("metadata.workspaceId").is(workspaceId)
                    .and("metadata.source").is(source)
                    .and("metadata.documentYear").exists(false));

            long modified = this.mongoTemplate.updateMulti(updateQuery, update, VECTOR_STORE_COLLECTION).getModifiedCount();
            if (modified > 0) {
                sourcesUpdated++;
                docsUpdated += modified;
            }
        }

        if (sourcesScanned > 0) {
            log.info("MetadataBackfill: dept={}, workspace={} scannedSources={} updatedSources={} updatedDocs={} dryRun={}",
                    department, workspaceId, sourcesScanned, sourcesUpdated, docsUpdated, dryRun);
        }
        return new BackfillResult(sourcesScanned, sources.size(), sourcesUpdated, docsUpdated, dryRun);
    }

    public record BackfillResult(int sourcesScanned, int sourcesCandidate, int sourcesUpdated, long documentsUpdated, boolean dryRun) {
    }
}
