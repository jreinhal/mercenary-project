package com.jreinhal.mercenary.connectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.UpdateResult;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

class ConnectorLegacyMetadataMigrationServiceTest {

    @Test
    void reportsCompletedStateFromMigrationCollection() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        ConnectorLegacyMetadataMigrationService service = new ConnectorLegacyMetadataMigrationService(mongoTemplate);
        when(mongoTemplate.exists(any(Query.class), eq("migration_state"))).thenReturn(true);

        assertTrue(service.isCompleted());

        when(mongoTemplate.exists(any(Query.class), eq("migration_state"))).thenReturn(false);
        assertFalse(service.isCompleted());
    }

    @Test
    void markCompletedPersistsMigrationResult() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        ConnectorLegacyMetadataMigrationService service = new ConnectorLegacyMetadataMigrationService(mongoTemplate);
        ConnectorLegacyMetadataMigrationService.MigrationResult result =
                new ConnectorLegacyMetadataMigrationService.MigrationResult(2, 1, 1, 10L, 0, 10L, false);

        service.markCompleted(result);

        verify(mongoTemplate).upsert(any(Query.class), any(Update.class), eq("migration_state"));
    }

    @Test
    void migrateReturnsEmptyResultWhenNoConnectorStateRowsExist() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        ConnectorLegacyMetadataMigrationService service = new ConnectorLegacyMetadataMigrationService(mongoTemplate);
        when(mongoTemplate.find(any(Query.class), eq(Document.class), eq("connector_sync_state"))).thenReturn(List.of());

        ConnectorLegacyMetadataMigrationService.MigrationResult result = service.migrateLegacyMetadata(false);

        assertEquals(0, result.sourceGroups());
        assertEquals(0, result.candidateSources());
        assertEquals(0L, result.updatedDocuments());
    }

    @Test
    void dryRunSkipsAmbiguousSourcesAndDoesNotUpdate() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        ConnectorLegacyMetadataMigrationService service = new ConnectorLegacyMetadataMigrationService(mongoTemplate);

        List<Document> states = List.of(
                new Document()
                        .append("connectorName", "S3")
                        .append("department", "ENTERPRISE")
                        .append("workspaceId", "ws")
                        .append("sourceName", "same.txt")
                        .append("sourceKey", "s3/same.txt")
                        .append("fingerprint", "fp1"),
                new Document()
                        .append("connectorName", "SharePoint")
                        .append("department", "ENTERPRISE")
                        .append("workspaceId", "ws")
                        .append("sourceName", "same.txt")
                        .append("sourceKey", "sp/same.txt")
                        .append("fingerprint", "fp2"),
                new Document()
                        .append("connectorName", "S3")
                        .append("department", "ENTERPRISE")
                        .append("workspaceId", "ws")
                        .append("sourceName", "unique.txt")
                        .append("sourceKey", "s3/unique.txt")
                        .append("fingerprint", "fp3")
        );

        when(mongoTemplate.find(any(Query.class), eq(Document.class), eq("connector_sync_state")))
                .thenReturn(states);
        when(mongoTemplate.count(any(Query.class), eq("vector_store")))
                .thenReturn(3L);

        ConnectorLegacyMetadataMigrationService.MigrationResult result = service.migrateLegacyMetadata(true);

        assertEquals(2, result.sourceGroups());
        assertEquals(1, result.ambiguousSources());
        assertEquals(1, result.candidateSources());
        assertEquals(1, result.updatedSources());
        assertEquals(3L, result.updatedDocuments());
        verify(mongoTemplate, never()).updateMulti(any(Query.class), any(Update.class), eq("vector_store"));
    }

    @Test
    void applyModeUpdatesLegacyChunks() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        ConnectorLegacyMetadataMigrationService service = new ConnectorLegacyMetadataMigrationService(mongoTemplate);

        List<Document> states = List.of(
                new Document()
                        .append("connectorName", "S3")
                        .append("department", "ENTERPRISE")
                        .append("workspaceId", "ws")
                        .append("sourceName", "unique.txt")
                        .append("sourceKey", "s3/unique.txt")
                        .append("fingerprint", "fp3")
        );

        when(mongoTemplate.find(any(Query.class), eq(Document.class), eq("connector_sync_state")))
                .thenReturn(states);
        when(mongoTemplate.count(any(Query.class), eq("vector_store")))
                .thenReturn(2L);
        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.getModifiedCount()).thenReturn(2L);
        when(mongoTemplate.updateMulti(any(Query.class), any(Update.class), eq("vector_store")))
                .thenReturn(updateResult);

        ConnectorLegacyMetadataMigrationService.MigrationResult result = service.migrateLegacyMetadata(false);

        assertEquals(1, result.sourceGroups());
        assertEquals(1, result.candidateSources());
        assertEquals(1, result.updatedSources());
        assertEquals(2L, result.updatedDocuments());
        verify(mongoTemplate).updateMulti(any(Query.class), any(Update.class), eq("vector_store"));
    }

    @Test
    void ignoresInvalidConnectorStateRows() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        ConnectorLegacyMetadataMigrationService service = new ConnectorLegacyMetadataMigrationService(mongoTemplate);

        List<Document> states = List.of(
                new Document().append("connectorName", "S3").append("department", "ENTERPRISE"),
                new Document()
                        .append("connectorName", "S3")
                        .append("department", "ENTERPRISE")
                        .append("workspaceId", "ws")
                        .append("sourceName", "unique.txt")
                        .append("sourceKey", "s3/unique.txt")
                        .append("fingerprint", "fp3")
        );

        when(mongoTemplate.find(any(Query.class), eq(Document.class), eq("connector_sync_state")))
                .thenReturn(states);
        when(mongoTemplate.count(any(Query.class), eq("vector_store")))
                .thenReturn(0L);

        ConnectorLegacyMetadataMigrationService.MigrationResult result = service.migrateLegacyMetadata(true);

        assertEquals(1, result.sourceGroups());
        assertEquals(0, result.candidateSources());
        verify(mongoTemplate, never()).updateMulti(any(Query.class), any(Update.class), eq("vector_store"));
    }
}
