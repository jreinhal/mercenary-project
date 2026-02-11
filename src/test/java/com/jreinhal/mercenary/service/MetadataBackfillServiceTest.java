package com.jreinhal.mercenary.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jreinhal.mercenary.workspace.WorkspaceContext;
import com.mongodb.client.result.UpdateResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.util.ReflectionTestUtils;

class MetadataBackfillServiceTest {

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Test
    void dryRunDoesNotPersistUpdates() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        MetadataBackfillService service = new MetadataBackfillService(mongoTemplate);
        ReflectionTestUtils.setField(service, "maxSources", 10);

        WorkspaceContext.setCurrentWorkspaceId("ws");

        List<Map> seeds = List.of(
                Map.of("metadata", Map.of("source", "a.txt")),
                Map.of("metadata", Map.of("source", "b.txt"))
        );
        List<Map> docsA = List.of(Map.of("content", "Report Date: 2021-05-03"));
        List<Map> docsB = List.of(Map.of("content", "no date here"));

        when(mongoTemplate.find(any(Query.class), eq(Map.class), eq("vector_store")))
                .thenReturn(seeds)
                .thenReturn(docsA)
                .thenReturn(docsB);

        MetadataBackfillService.BackfillResult result =
                service.backfillTemporalMetadata("MEDICAL", "ws", true);

        assertEquals(2, result.sourcesCandidate());
        assertEquals(2, result.sourcesScanned());
        assertEquals(1, result.sourcesUpdated());
        assertEquals(0L, result.documentsUpdated());

        verify(mongoTemplate, never()).updateMulti(any(Query.class), any(Update.class), eq("vector_store"));
    }

    @Test
    void persistsUpdatesWhenNotDryRun() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        MetadataBackfillService service = new MetadataBackfillService(mongoTemplate);
        ReflectionTestUtils.setField(service, "maxSources", 10);

        WorkspaceContext.setCurrentWorkspaceId("ws");

        List<Map> seeds = List.of(
                Map.of("metadata", Map.of("source", "a.txt"))
        );
        List<Map> docsA = List.of(Map.of("content", "Report Date: 2021-05-03"));

        when(mongoTemplate.find(any(Query.class), eq(Map.class), eq("vector_store")))
                .thenReturn(seeds)
                .thenReturn(docsA);
        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.getModifiedCount()).thenReturn(2L);
        when(mongoTemplate.updateMulti(any(Query.class), any(Update.class), eq("vector_store")))
                .thenReturn(updateResult);

        MetadataBackfillService.BackfillResult result =
                service.backfillTemporalMetadata("MEDICAL", "ws", false);

        assertEquals(1, result.sourcesCandidate());
        assertEquals(1, result.sourcesScanned());
        assertEquals(1, result.sourcesUpdated());
        assertEquals(2L, result.documentsUpdated());
    }
}
