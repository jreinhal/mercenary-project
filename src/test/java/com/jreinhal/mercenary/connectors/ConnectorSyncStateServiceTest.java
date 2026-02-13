package com.jreinhal.mercenary.connectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.service.SourceDocumentService;
import com.jreinhal.mercenary.workspace.WorkspaceContext;
import com.mongodb.client.result.DeleteResult;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.util.ReflectionTestUtils;

class ConnectorSyncStateServiceTest {

    @Test
    void markSeenDoesNotPersistFingerprintUntilIngestionSucceeds() {
        MongoTemplate mongoTemplate = org.mockito.Mockito.mock(MongoTemplate.class);
        SourceDocumentService sourceDocumentService = org.mockito.Mockito.mock(SourceDocumentService.class);
        ConnectorSyncStateService service = new ConnectorSyncStateService(mongoTemplate, sourceDocumentService);

        service.markSeen("S3", Department.ENTERPRISE, "ws", "folder/a.txt", "a.txt", "fp-new", 123L);

        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).upsert(any(Query.class), updateCaptor.capture(), eq("connector_sync_state"));
        Document updateObject = updateCaptor.getValue().getUpdateObject();
        Document set = (Document) updateObject.get("$set");
        assertNotNull(set);
        assertFalse(set.containsKey("fingerprint"));
        assertEquals("a.txt", set.get("sourceName"));
    }

    @Test
    void recordIngestedFallsBackToContentHashFingerprint() {
        MongoTemplate mongoTemplate = org.mockito.Mockito.mock(MongoTemplate.class);
        SourceDocumentService sourceDocumentService = org.mockito.Mockito.mock(SourceDocumentService.class);
        ConnectorSyncStateService service = new ConnectorSyncStateService(mongoTemplate, sourceDocumentService);

        service.recordIngested("S3", Department.ENTERPRISE, "ws", "folder/a.txt", "a.txt", "", "hash123", 12L, 456L);

        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).upsert(any(Query.class), updateCaptor.capture(), eq("connector_sync_state"));
        Document set = (Document) updateCaptor.getValue().getUpdateObject().get("$set");
        assertEquals("hash123", set.get("fingerprint"));
        assertEquals("hash123", set.get("contentHash"));
    }

    @Test
    void getStateMapsPersistedStateDocument() {
        MongoTemplate mongoTemplate = org.mockito.Mockito.mock(MongoTemplate.class);
        SourceDocumentService sourceDocumentService = org.mockito.Mockito.mock(SourceDocumentService.class);
        ConnectorSyncStateService service = new ConnectorSyncStateService(mongoTemplate, sourceDocumentService);

        Document stateDoc = new Document()
                .append("sourceName", "a.txt")
                .append("fingerprint", "fp")
                .append("contentHash", "hash")
                .append("lastSeenAtEpochMs", 987L);
        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("connector_sync_state"))).thenReturn(stateDoc);

        ConnectorSyncStateService.SourceState state =
                service.getState("S3", Department.ENTERPRISE, "ws", "folder/a.txt");

        assertNotNull(state);
        assertEquals("folder/a.txt", state.sourceKey());
        assertEquals("a.txt", state.sourceName());
        assertEquals("fp", state.fingerprint());
        assertEquals("hash", state.contentHash());
        assertEquals(987L, state.lastSeenAtEpochMs());
    }

    @Test
    void pruneSourceDocumentsInvalidatesCachedPdfWhenDeletionOccurs() {
        MongoTemplate mongoTemplate = org.mockito.Mockito.mock(MongoTemplate.class);
        SourceDocumentService sourceDocumentService = org.mockito.Mockito.mock(SourceDocumentService.class);
        ConnectorSyncStateService service = new ConnectorSyncStateService(mongoTemplate, sourceDocumentService);
        when(mongoTemplate.remove(any(Query.class), eq("vector_store"))).thenReturn(DeleteResult.acknowledged(2L));

        long deleted = service.pruneSourceDocuments("S3", Department.ENTERPRISE, "ws", "folder/a.txt", "a.txt");

        assertEquals(2L, deleted);
        verify(sourceDocumentService).removePdfSource("ws", Department.ENTERPRISE, "a.txt");
    }

    @Test
    void pruneSupersededSourceDocumentsInvalidatesCacheWhenDeletionOccurs() {
        MongoTemplate mongoTemplate = org.mockito.Mockito.mock(MongoTemplate.class);
        SourceDocumentService sourceDocumentService = org.mockito.Mockito.mock(SourceDocumentService.class);
        ConnectorSyncStateService service = new ConnectorSyncStateService(mongoTemplate, sourceDocumentService);
        when(mongoTemplate.remove(any(Query.class), eq("vector_store"))).thenReturn(DeleteResult.acknowledged(3L));

        long deleted = service.pruneSupersededSourceDocuments(
                "S3", Department.ENTERPRISE, "ws", "folder/a.txt", "run-1", "a.txt");

        assertEquals(3L, deleted);
        verify(sourceDocumentService).removePdfSource("ws", Department.ENTERPRISE, "a.txt");
    }

    @Test
    void pruneRemovedSourcesDeletesStateRowsAfterPruning() {
        MongoTemplate mongoTemplate = org.mockito.Mockito.mock(MongoTemplate.class);
        SourceDocumentService sourceDocumentService = org.mockito.Mockito.mock(SourceDocumentService.class);
        ConnectorSyncStateService service = new ConnectorSyncStateService(mongoTemplate, sourceDocumentService);

        List<Document> staleStates = List.of(
                new Document()
                        .append("_id", "state1")
                        .append("sourceKey", "folder/a.txt")
                        .append("sourceName", "a.txt"),
                new Document()
                        .append("_id", "state2")
                        .append("sourceKey", "folder/b.txt")
                        .append("sourceName", "b.txt")
        );
        when(mongoTemplate.find(any(Query.class), eq(Document.class), eq("connector_sync_state"))).thenReturn(staleStates);
        when(mongoTemplate.remove(any(Query.class), eq("vector_store"))).thenReturn(DeleteResult.acknowledged(1L));
        when(mongoTemplate.remove(any(Query.class), eq("connector_sync_state"))).thenReturn(DeleteResult.acknowledged(2L));

        int pruned = service.pruneRemovedSources("S3", Department.ENTERPRISE, "ws", 9999L);

        assertEquals(2, pruned);
        verify(mongoTemplate).remove(any(Query.class), eq("connector_sync_state"));
    }

    @Test
    void hashAndFingerprintHelpersAreStable() {
        MongoTemplate mongoTemplate = org.mockito.Mockito.mock(MongoTemplate.class);
        SourceDocumentService sourceDocumentService = org.mockito.Mockito.mock(SourceDocumentService.class);
        ConnectorSyncStateService service = new ConnectorSyncStateService(mongoTemplate, sourceDocumentService);

        String hash = service.sha256("abc".getBytes(StandardCharsets.UTF_8));
        String fp = service.stableFingerprint("a", "b", "c");
        String fpObjects = service.stableFingerprint("a", 42L, Instant.ofEpochMilli(1000L));

        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                hash);
        assertEquals("a|b|c", fp);
        assertTrue(fpObjects.contains("1000"));
    }

    @Test
    void enabledFlagReadsConfigurationValue() {
        MongoTemplate mongoTemplate = org.mockito.Mockito.mock(MongoTemplate.class);
        SourceDocumentService sourceDocumentService = org.mockito.Mockito.mock(SourceDocumentService.class);
        ConnectorSyncStateService service = new ConnectorSyncStateService(mongoTemplate, sourceDocumentService);
        ReflectionTestUtils.setField(service, "incrementalSyncEnabled", false);
        assertFalse(service.isEnabled());
        ReflectionTestUtils.setField(service, "incrementalSyncEnabled", true);
        assertTrue(service.isEnabled());
    }

    @Test
    void currentWorkspaceIdUsesWorkspaceContext() {
        MongoTemplate mongoTemplate = org.mockito.Mockito.mock(MongoTemplate.class);
        SourceDocumentService sourceDocumentService = org.mockito.Mockito.mock(SourceDocumentService.class);
        ConnectorSyncStateService service = new ConnectorSyncStateService(mongoTemplate, sourceDocumentService);
        WorkspaceContext.setCurrentWorkspaceId("workspace_test");

        assertEquals("workspace_test", service.currentWorkspaceId());

        WorkspaceContext.clear();
    }

    @Test
    void guardClausesShortCircuitInvalidInput() {
        MongoTemplate mongoTemplate = org.mockito.Mockito.mock(MongoTemplate.class);
        SourceDocumentService sourceDocumentService = org.mockito.Mockito.mock(SourceDocumentService.class);
        ConnectorSyncStateService service = new ConnectorSyncStateService(mongoTemplate, sourceDocumentService);

        assertNull(service.getState("", Department.ENTERPRISE, "ws", "key"));
        service.markSeen("", Department.ENTERPRISE, "ws", "key", "name", "fp", 1L);
        service.recordIngested("", Department.ENTERPRISE, "ws", "key", "name", "fp", "hash", 1L, 1L);
        assertEquals(0L, service.pruneSourceDocuments("", Department.ENTERPRISE, "ws", "key", "name"));
        assertEquals(0L, service.pruneSupersededSourceDocuments("", Department.ENTERPRISE, "ws", "key", "run", "name"));
        assertEquals(0, service.pruneRemovedSources("", Department.ENTERPRISE, "ws", 1L));

        verify(mongoTemplate, never()).upsert(any(Query.class), any(Update.class), eq("connector_sync_state"));
    }

    @Test
    void helperMethodsHandleNullAndMalformedValues() {
        MongoTemplate mongoTemplate = org.mockito.Mockito.mock(MongoTemplate.class);
        SourceDocumentService sourceDocumentService = org.mockito.Mockito.mock(SourceDocumentService.class);
        ConnectorSyncStateService service = new ConnectorSyncStateService(mongoTemplate, sourceDocumentService);

        assertFalse(service.matchesFingerprint(null, "fp"));
        assertFalse(service.matchesFingerprint(new ConnectorSyncStateService.SourceState("k", "n", "fp", "h", 0L), ""));
        assertFalse(service.matchesContentHash(null, "hash"));
        assertFalse(service.matchesContentHash(new ConnectorSyncStateService.SourceState("k", "n", "fp", "hash", 0L), ""));
        assertEquals("", service.sha256(null));
        assertEquals("", service.sha256(new byte[0]));
        assertEquals("", service.stableFingerprint((String[]) null));
        assertEquals("", service.stableFingerprint((Object[]) null));
        assertEquals("a|_b_", service.stableFingerprint("a", "|b|"));
        assertEquals("", service.stableFingerprint());
        assertEquals("", service.stableFingerprint(new Object[0]));
        assertEquals("unknown", ReflectionTestUtils.invokeMethod(service, "safeSourceName", ""));
        assertEquals(0L, (long) ReflectionTestUtils.invokeMethod(service, "asLong", "not-a-long"));
        assertEquals(0L, (long) ReflectionTestUtils.invokeMethod(service, "asLong", (Object) null));
        assertEquals("", ReflectionTestUtils.invokeMethod(service, "asString", (Object) null));
    }

    @Test
    void pruneRemovedSourcesSkipsEntriesWithMissingSourceKey() {
        MongoTemplate mongoTemplate = org.mockito.Mockito.mock(MongoTemplate.class);
        SourceDocumentService sourceDocumentService = org.mockito.Mockito.mock(SourceDocumentService.class);
        ConnectorSyncStateService service = new ConnectorSyncStateService(mongoTemplate, sourceDocumentService);

        List<Document> staleStates = List.of(
                new Document().append("_id", "state1").append("sourceKey", "").append("sourceName", "missing.txt")
        );
        when(mongoTemplate.find(any(Query.class), eq(Document.class), eq("connector_sync_state"))).thenReturn(staleStates);

        int pruned = service.pruneRemovedSources("S3", Department.ENTERPRISE, "ws", 9999L);

        assertEquals(0, pruned);
        verify(mongoTemplate, never()).remove(any(Query.class), eq("connector_sync_state"));
    }
}
