package com.jreinhal.mercenary.connectors;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ConnectorService {
    private static final Logger log = LoggerFactory.getLogger(ConnectorService.class);

    private final List<Connector> connectors;
    private final Map<String, ConnectorStatus> statusMap = new ConcurrentHashMap<>();

    @Value("${sentinel.connectors.sync-enabled:false}")
    private boolean syncEnabled;

    public ConnectorService(List<Connector> connectors) {
        this.connectors = connectors;
    }

    public List<ConnectorStatus> getStatuses() {
        List<ConnectorStatus> results = new ArrayList<>();
        for (Connector connector : connectors) {
            ConnectorStatus status = statusMap.get(connector.getName());
            if (status == null) {
                status = new ConnectorStatus(connector.getName(), connector.isEnabled(), null, null);
            } else {
                status = new ConnectorStatus(connector.getName(), connector.isEnabled(), status.lastSync(), status.lastResult());
            }
            results.add(status);
        }
        return results;
    }

    public List<ConnectorSyncResult> syncAll() {
        List<ConnectorSyncResult> results = new ArrayList<>();
        for (Connector connector : connectors) {
            ConnectorSyncResult result = connector.sync();
            results.add(result);
            ConnectorStatus status = new ConnectorStatus(connector.getName(), connector.isEnabled(), Instant.now(), result);
            statusMap.put(connector.getName(), status);
        }
        return results;
    }

    /**
     * Scheduled connector sync. Disabled by default; enable via
     * {@code sentinel.connectors.sync-enabled=true}. Cron expression
     * controlled by {@code sentinel.connectors.sync-cron} (default: 2 AM daily).
     */
    @Scheduled(cron = "${sentinel.connectors.sync-cron:0 0 2 * * ?}")
    public void scheduledSync() {
        if (!syncEnabled) {
            return;
        }
        log.info("Scheduled connector sync starting");
        long startTime = System.currentTimeMillis();
        List<ConnectorSyncResult> results = syncAll();
        long duration = System.currentTimeMillis() - startTime;

        int totalLoaded = 0;
        int totalSkipped = 0;
        int successCount = 0;
        for (ConnectorSyncResult result : results) {
            totalLoaded += result.loaded();
            totalSkipped += result.skipped();
            if (result.success()) {
                successCount++;
            }
        }
        log.info("Scheduled connector sync completed in {}ms: {}/{} connectors succeeded, {} documents loaded, {} skipped",
                duration, successCount, results.size(), totalLoaded, totalSkipped);
    }

    public boolean isSyncEnabled() {
        return syncEnabled;
    }

    public List<ConnectorCatalogEntry> getCatalog() {
        List<ConnectorStatus> statuses = getStatuses();
        Map<String, ConnectorStatus> byName = new HashMap<>();
        for (ConnectorStatus status : statuses) {
            byName.put(status.name(), status);
        }
        List<ConnectorCatalogEntry> entries = new ArrayList<>();
        for (ConnectorCatalog.ConnectorDefinition def : ConnectorCatalog.definitions()) {
            ConnectorStatus status = byName.get(def.id());
            boolean enabled = status != null && status.enabled();
            entries.add(new ConnectorCatalogEntry(
                def.id(),
                def.name(),
                def.category(),
                def.description(),
                enabled,
                def.supportsRegulated(),
                def.configKeys(),
                status != null ? status.lastSync() : null,
                status != null ? status.lastResult() : null
            ));
        }
        return entries;
    }
}
