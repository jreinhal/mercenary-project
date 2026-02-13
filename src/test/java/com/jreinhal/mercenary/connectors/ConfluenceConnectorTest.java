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
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

class ConfluenceConnectorTest {

    @Test
    void syncFailsWhenPolicyDisallowsConnectors() {
        ConnectorPolicy policy = Mockito.mock(ConnectorPolicy.class);
        when(policy.allowConnectors()).thenReturn(false);
        ConfluenceConnector connector = new ConfluenceConnector(policy, Mockito.mock(SecureIngestionService.class), Mockito.mock(RestTemplate.class));
        ReflectionTestUtils.setField(connector, "enabled", true);

        ConnectorSyncResult result = connector.sync();

        assertFalse(result.success());
        assertTrue(result.message().contains("disabled by policy"));
    }

    @Test
    void syncFailsWhenBaseUrlIsUntrusted() {
        ConnectorPolicy policy = Mockito.mock(ConnectorPolicy.class);
        when(policy.allowConnectors()).thenReturn(true);
        ConfluenceConnector connector = new ConfluenceConnector(policy, Mockito.mock(SecureIngestionService.class), Mockito.mock(RestTemplate.class));
        ReflectionTestUtils.setField(connector, "enabled", true);
        ReflectionTestUtils.setField(connector, "baseUrl", "https://evil.example.com");
        ReflectionTestUtils.setField(connector, "spaceKey", "SPACE");

        ConnectorSyncResult result = connector.sync();

        assertFalse(result.success());
        assertTrue(result.message().contains("allowlist"));
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
        when(syncStateService.getState(eq("Confluence"), eq(Department.ENTERPRISE), eq("ws"), eq("123")))
                .thenReturn(new ConnectorSyncStateService.SourceState("123", "confluence_123_test.txt", "old", "oldhash", 0L));

        ConfluenceConnector connector = new ConfluenceConnector(policy, ingestionService, restTemplate);
        ReflectionTestUtils.setField(connector, "syncStateService", syncStateService);
        ReflectionTestUtils.setField(connector, "enabled", true);
        ReflectionTestUtils.setField(connector, "baseUrl", "https://example.atlassian.net");
        ReflectionTestUtils.setField(connector, "spaceKey", "SPACE");
        ReflectionTestUtils.setField(connector, "email", "user@example.com");
        ReflectionTestUtils.setField(connector, "apiToken", "token");
        ReflectionTestUtils.setField(connector, "limit", 10);
        ReflectionTestUtils.setField(connector, "maxPages", 1);
        ReflectionTestUtils.setField(connector, "department", "ENTERPRISE");

        String json = "{"
                + "\"results\":[{"
                + "\"id\":\"123\","
                + "\"title\":\"Test Page\","
                + "\"body\":{\"storage\":{\"value\":\"<p>Hello</p>\"}},"
                + "\"version\":{\"number\":1,\"when\":\"2026-01-01T00:00:00Z\",\"by\":{\"accountId\":\"acct\"}}"
                + "}],"
                + "\"_links\":{\"next\":\"/rest/api/content?start=10\"}"
                + "}";
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(json));

        ConnectorSyncResult result = connector.sync();

        assertTrue(result.success());
        assertEquals(1, result.loaded());
        verify(syncStateService).pruneSupersededSourceDocuments(eq("Confluence"), eq(Department.ENTERPRISE), eq("ws"),
                eq("123"), anyString(), eq("confluence_123_test_page.txt"));
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
        when(syncStateService.getState(eq("Confluence"), eq(Department.ENTERPRISE), eq("ws"), eq("123")))
                .thenReturn(new ConnectorSyncStateService.SourceState("123", "confluence_123_test.txt", "old", "oldhash", 0L));

        ConfluenceConnector connector = new ConfluenceConnector(policy, ingestionService, restTemplate);
        ReflectionTestUtils.setField(connector, "syncStateService", syncStateService);
        ReflectionTestUtils.setField(connector, "enabled", true);
        ReflectionTestUtils.setField(connector, "baseUrl", "https://example.atlassian.net");
        ReflectionTestUtils.setField(connector, "spaceKey", "SPACE");
        ReflectionTestUtils.setField(connector, "email", "user@example.com");
        ReflectionTestUtils.setField(connector, "apiToken", "token");
        ReflectionTestUtils.setField(connector, "limit", 10);
        ReflectionTestUtils.setField(connector, "maxPages", 1);
        ReflectionTestUtils.setField(connector, "department", "ENTERPRISE");

        String json = "{"
                + "\"results\":[{"
                + "\"id\":\"123\","
                + "\"title\":\"Test Page\","
                + "\"body\":{\"storage\":{\"value\":\"<p>Hello</p>\"}},"
                + "\"version\":{\"number\":1,\"when\":\"2026-01-01T00:00:00Z\",\"by\":{\"accountId\":\"acct\"}}"
                + "}],"
                + "\"_links\":{}"
                + "}";
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(json));
        doThrow(new RuntimeException("ingest failed"))
                .when(ingestionService)
                .ingestBytes(any(), anyString(), any(), any());

        ConnectorSyncResult result = connector.sync();

        assertFalse(result.success());
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
        when(syncStateService.getState(eq("Confluence"), eq(Department.ENTERPRISE), eq("ws"), eq("123")))
                .thenReturn(new ConnectorSyncStateService.SourceState("123", "confluence_123_test.txt", "old", "oldhash", 0L));
        when(syncStateService.pruneRemovedSources(eq("Confluence"), eq(Department.ENTERPRISE), eq("ws"), anyLong()))
                .thenReturn(1);

        ConfluenceConnector connector = new ConfluenceConnector(policy, ingestionService, restTemplate);
        ReflectionTestUtils.setField(connector, "syncStateService", syncStateService);
        ReflectionTestUtils.setField(connector, "enabled", true);
        ReflectionTestUtils.setField(connector, "baseUrl", "https://example.atlassian.net");
        ReflectionTestUtils.setField(connector, "spaceKey", "SPACE");
        ReflectionTestUtils.setField(connector, "email", "user@example.com");
        ReflectionTestUtils.setField(connector, "apiToken", "token");
        ReflectionTestUtils.setField(connector, "limit", 10);
        ReflectionTestUtils.setField(connector, "maxPages", 1);
        ReflectionTestUtils.setField(connector, "department", "ENTERPRISE");

        String json = "{"
                + "\"results\":[{"
                + "\"id\":\"123\","
                + "\"title\":\"Test Page\","
                + "\"body\":{\"storage\":{\"value\":\"<p>Hello</p>\"}},"
                + "\"version\":{\"number\":1,\"when\":\"2026-01-01T00:00:00Z\",\"by\":{\"accountId\":\"acct\"}}"
                + "}],"
                + "\"_links\":{}"
                + "}";
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(json));

        ConnectorSyncResult result = connector.sync();

        assertTrue(result.success());
        verify(syncStateService).pruneRemovedSources(eq("Confluence"), eq(Department.ENTERPRISE), eq("ws"), anyLong());
    }
}
