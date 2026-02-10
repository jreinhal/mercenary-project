package com.jreinhal.mercenary.connectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ConnectorServiceTest {

    private Connector mockConnector(String name, boolean enabled, boolean syncSuccess, int loaded) {
        Connector connector = mock(Connector.class);
        when(connector.getName()).thenReturn(name);
        when(connector.isEnabled()).thenReturn(enabled);
        when(connector.sync()).thenReturn(
                new ConnectorSyncResult(name, syncSuccess, loaded, 0, "test"));
        return connector;
    }

    @Test
    void syncAllCallsAllConnectors() {
        Connector c1 = mockConnector("S3", true, true, 5);
        Connector c2 = mockConnector("SharePoint", true, true, 3);
        ConnectorService service = new ConnectorService(List.of(c1, c2));

        List<ConnectorSyncResult> results = service.syncAll();

        assertEquals(2, results.size());
        verify(c1).sync();
        verify(c2).sync();
    }

    @Test
    void syncAllTracksStatus() {
        Connector c1 = mockConnector("S3", true, true, 5);
        ConnectorService service = new ConnectorService(List.of(c1));

        service.syncAll();
        List<ConnectorStatus> statuses = service.getStatuses();

        assertEquals(1, statuses.size());
        assertNotNull(statuses.get(0).lastSync());
        assertNotNull(statuses.get(0).lastResult());
        assertTrue(statuses.get(0).lastResult().success());
    }

    @Test
    void scheduledSyncDoesNothingWhenDisabled() {
        Connector c1 = mockConnector("S3", true, true, 5);
        ConnectorService service = new ConnectorService(List.of(c1));
        ReflectionTestUtils.setField(service, "syncEnabled", false);

        service.scheduledSync();

        verifyNoInteractions(c1);
    }

    @Test
    void scheduledSyncRunsWhenEnabled() {
        Connector c1 = mockConnector("S3", true, true, 5);
        Connector c2 = mockConnector("SharePoint", true, true, 3);
        ConnectorService service = new ConnectorService(List.of(c1, c2));
        ReflectionTestUtils.setField(service, "syncEnabled", true);

        service.scheduledSync();

        verify(c1).sync();
        verify(c2).sync();
    }

    @Test
    void scheduledSyncHandlesPartialFailure() {
        Connector c1 = mockConnector("S3", true, true, 5);
        Connector c2 = mockConnector("SharePoint", true, false, 0);
        ConnectorService service = new ConnectorService(List.of(c1, c2));
        ReflectionTestUtils.setField(service, "syncEnabled", true);

        // Should not throw even if one connector fails
        assertDoesNotThrow(service::scheduledSync);

        verify(c1).sync();
        verify(c2).sync();
    }

    @Test
    void getStatusesReturnsDisabledConnectorStatus() {
        Connector c1 = mockConnector("S3", false, false, 0);
        ConnectorService service = new ConnectorService(List.of(c1));

        List<ConnectorStatus> statuses = service.getStatuses();

        assertEquals(1, statuses.size());
        assertEquals("S3", statuses.get(0).name());
        assertFalse(statuses.get(0).enabled());
        assertNull(statuses.get(0).lastSync());
    }

    @Test
    void isSyncEnabledReflectsConfig() {
        ConnectorService service = new ConnectorService(List.of());
        ReflectionTestUtils.setField(service, "syncEnabled", false);
        assertFalse(service.isSyncEnabled());

        ReflectionTestUtils.setField(service, "syncEnabled", true);
        assertTrue(service.isSyncEnabled());
    }
}
