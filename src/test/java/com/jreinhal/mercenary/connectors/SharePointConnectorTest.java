package com.jreinhal.mercenary.connectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.service.SecureIngestionService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

class SharePointConnectorTest {

    @Test
    void syncFailsWhenPolicyDisallowsConnectors() {
        ConnectorPolicy policy = Mockito.mock(ConnectorPolicy.class);
        when(policy.allowConnectors()).thenReturn(false);
        SharePointConnector connector = new SharePointConnector(policy, Mockito.mock(SecureIngestionService.class), Mockito.mock(RestTemplate.class));
        ReflectionTestUtils.setField(connector, "enabled", true);

        ConnectorSyncResult result = connector.sync();

        assertFalse(result.success());
        assertTrue(result.message().contains("disabled by policy"));
    }

    @Test
    void syncFailsWhenRequiredConfigMissing() {
        ConnectorPolicy policy = Mockito.mock(ConnectorPolicy.class);
        when(policy.allowConnectors()).thenReturn(true);
        SharePointConnector connector = new SharePointConnector(policy, Mockito.mock(SecureIngestionService.class), Mockito.mock(RestTemplate.class));
        ReflectionTestUtils.setField(connector, "enabled", true);
        ReflectionTestUtils.setField(connector, "driveId", "");
        ReflectionTestUtils.setField(connector, "bearerToken", "");

        ConnectorSyncResult result = connector.sync();

        assertFalse(result.success());
        assertTrue(result.message().contains("Missing SharePoint configuration"));
    }

    @Test
    void syncFailsWhenGraphQueryFails() {
        ConnectorPolicy policy = Mockito.mock(ConnectorPolicy.class);
        RestTemplate restTemplate = Mockito.mock(RestTemplate.class);
        when(policy.allowConnectors()).thenReturn(true);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(500).body(""));

        SharePointConnector connector = new SharePointConnector(policy, Mockito.mock(SecureIngestionService.class), restTemplate);
        ReflectionTestUtils.setField(connector, "enabled", true);
        ReflectionTestUtils.setField(connector, "driveId", "drive-1");
        ReflectionTestUtils.setField(connector, "bearerToken", "token");
        ReflectionTestUtils.setField(connector, "graphBase", "https://graph.microsoft.com/v1.0");
        ReflectionTestUtils.setField(connector, "folderPath", "");

        ConnectorSyncResult result = connector.sync();

        assertFalse(result.success());
        assertTrue(result.message().contains("query failed"));
    }

    @Test
    void syncSkipsRemovedSourcePruneWhenListingIsIncomplete() {
        ConnectorPolicy policy = Mockito.mock(ConnectorPolicy.class);
        SecureIngestionService ingestionService = Mockito.mock(SecureIngestionService.class);
        RestTemplate restTemplate = Mockito.mock(RestTemplate.class);
        ConnectorSyncStateService syncStateService = Mockito.mock(ConnectorSyncStateService.class);
        when(policy.allowConnectors()).thenReturn(true);
        when(syncStateService.currentWorkspaceId()).thenReturn("ws");
        when(syncStateService.isEnabled()).thenReturn(true);
        when(syncStateService.getState(eq("SharePoint"), eq(Department.ENTERPRISE), eq("ws"), eq("item-1")))
                .thenReturn(new ConnectorSyncStateService.SourceState("item-1", "file1.txt", "old", "oldhash", 0L));

        SharePointConnector connector = new SharePointConnector(policy, ingestionService, restTemplate);
        ReflectionTestUtils.setField(connector, "syncStateService", syncStateService);
        ReflectionTestUtils.setField(connector, "enabled", true);
        ReflectionTestUtils.setField(connector, "driveId", "drive-1");
        ReflectionTestUtils.setField(connector, "bearerToken", "token");
        ReflectionTestUtils.setField(connector, "graphBase", "https://graph.microsoft.com/v1.0");
        ReflectionTestUtils.setField(connector, "maxFiles", 1);
        ReflectionTestUtils.setField(connector, "department", "ENTERPRISE");
        ReflectionTestUtils.setField(connector, "folderPath", "");

        String json = "{"
                + "\"value\":[{"
                + "\"id\":\"item-1\","
                + "\"name\":\"file1.txt\","
                + "\"eTag\":\"etag-1\","
                + "\"cTag\":\"ctag-1\","
                + "\"lastModifiedDateTime\":\"2026-01-01T00:00:00Z\","
                + "\"size\":3,"
                + "\"@microsoft.graph.downloadUrl\":\"https://tenant.sharepoint.com/file1.txt\""
                + "}],"
                + "\"@odata.nextLink\":\"https://graph.microsoft.com/v1.0/next\""
                + "}";
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(json));
        when(restTemplate.getForObject(eq("https://tenant.sharepoint.com/file1.txt"), eq(byte[].class)))
                .thenReturn("abc".getBytes(StandardCharsets.UTF_8));

        ConnectorSyncResult result = connector.sync();

        assertTrue(result.success());
        assertEquals(1, result.loaded());
        verify(syncStateService).pruneSupersededSourceDocuments(eq("SharePoint"), eq(Department.ENTERPRISE), eq("ws"),
                eq("item-1"), anyString(), eq("file1.txt"));
        verify(syncStateService, never()).pruneRemovedSources(anyString(), any(), anyString(), anyLong());
    }

    @Test
    void syncDoesNotDeleteOldChunksWhenReplacementIngestFails() {
        ConnectorPolicy policy = Mockito.mock(ConnectorPolicy.class);
        SecureIngestionService ingestionService = Mockito.mock(SecureIngestionService.class);
        RestTemplate restTemplate = Mockito.mock(RestTemplate.class);
        ConnectorSyncStateService syncStateService = Mockito.mock(ConnectorSyncStateService.class);
        when(policy.allowConnectors()).thenReturn(true);
        when(syncStateService.currentWorkspaceId()).thenReturn("ws");
        when(syncStateService.isEnabled()).thenReturn(true);
        when(syncStateService.getState(eq("SharePoint"), eq(Department.ENTERPRISE), eq("ws"), eq("item-1")))
                .thenReturn(new ConnectorSyncStateService.SourceState("item-1", "file1.txt", "old", "oldhash", 0L));

        SharePointConnector connector = new SharePointConnector(policy, ingestionService, restTemplate);
        ReflectionTestUtils.setField(connector, "syncStateService", syncStateService);
        ReflectionTestUtils.setField(connector, "enabled", true);
        ReflectionTestUtils.setField(connector, "driveId", "drive-1");
        ReflectionTestUtils.setField(connector, "bearerToken", "token");
        ReflectionTestUtils.setField(connector, "graphBase", "https://graph.microsoft.com/v1.0");
        ReflectionTestUtils.setField(connector, "maxFiles", 1);
        ReflectionTestUtils.setField(connector, "department", "ENTERPRISE");
        ReflectionTestUtils.setField(connector, "folderPath", "");

        String json = "{"
                + "\"value\":[{"
                + "\"id\":\"item-1\","
                + "\"name\":\"file1.txt\","
                + "\"eTag\":\"etag-1\","
                + "\"cTag\":\"ctag-1\","
                + "\"lastModifiedDateTime\":\"2026-01-01T00:00:00Z\","
                + "\"size\":3,"
                + "\"@microsoft.graph.downloadUrl\":\"https://tenant.sharepoint.com/file1.txt\""
                + "}]"
                + "}";
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(json));
        when(restTemplate.getForObject(eq("https://tenant.sharepoint.com/file1.txt"), eq(byte[].class)))
                .thenReturn("abc".getBytes(StandardCharsets.UTF_8));
        doThrow(new RuntimeException("ingest failed"))
                .when(ingestionService)
                .ingestBytes(any(), anyString(), any(), any());

        ConnectorSyncResult result = connector.sync();

        assertTrue(result.success());
        verify(syncStateService, never()).pruneSupersededSourceDocuments(anyString(), any(), anyString(), anyString(), anyString(), anyString());
        verify(syncStateService, never()).recordIngested(anyString(), any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyLong());
    }

    @Test
    void syncPrunesRemovedSourcesWhenListingIsComplete() {
        ConnectorPolicy policy = Mockito.mock(ConnectorPolicy.class);
        SecureIngestionService ingestionService = Mockito.mock(SecureIngestionService.class);
        RestTemplate restTemplate = Mockito.mock(RestTemplate.class);
        ConnectorSyncStateService syncStateService = Mockito.mock(ConnectorSyncStateService.class);
        when(policy.allowConnectors()).thenReturn(true);
        when(syncStateService.currentWorkspaceId()).thenReturn("ws");
        when(syncStateService.isEnabled()).thenReturn(true);
        when(syncStateService.getState(eq("SharePoint"), eq(Department.ENTERPRISE), eq("ws"), eq("item-1")))
                .thenReturn(new ConnectorSyncStateService.SourceState("item-1", "file1.txt", "old", "oldhash", 0L));
        when(syncStateService.pruneRemovedSources(eq("SharePoint"), eq(Department.ENTERPRISE), eq("ws"), anyLong()))
                .thenReturn(1);

        SharePointConnector connector = new SharePointConnector(policy, ingestionService, restTemplate);
        ReflectionTestUtils.setField(connector, "syncStateService", syncStateService);
        ReflectionTestUtils.setField(connector, "enabled", true);
        ReflectionTestUtils.setField(connector, "driveId", "drive-1");
        ReflectionTestUtils.setField(connector, "bearerToken", "token");
        ReflectionTestUtils.setField(connector, "graphBase", "https://graph.microsoft.com/v1.0");
        ReflectionTestUtils.setField(connector, "maxFiles", 10);
        ReflectionTestUtils.setField(connector, "department", "ENTERPRISE");
        ReflectionTestUtils.setField(connector, "folderPath", "");

        String json = "{"
                + "\"value\":[{"
                + "\"id\":\"item-1\","
                + "\"name\":\"file1.txt\","
                + "\"eTag\":\"etag-1\","
                + "\"cTag\":\"ctag-1\","
                + "\"lastModifiedDateTime\":\"2026-01-01T00:00:00Z\","
                + "\"size\":3,"
                + "\"@microsoft.graph.downloadUrl\":\"https://tenant.sharepoint.com/file1.txt\""
                + "}]"
                + "}";
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(json));
        when(restTemplate.getForObject(eq("https://tenant.sharepoint.com/file1.txt"), eq(byte[].class)))
                .thenReturn("abc".getBytes(StandardCharsets.UTF_8));

        ConnectorSyncResult result = connector.sync();

        assertTrue(result.success());
        verify(syncStateService).pruneRemovedSources(eq("SharePoint"), eq(Department.ENTERPRISE), eq("ws"), anyLong());
    }
}
