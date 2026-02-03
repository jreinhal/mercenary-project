package com.jreinhal.mercenary.connectors;

import java.time.Instant;
import java.util.List;

public record ConnectorCatalogEntry(
        String id,
        String name,
        String category,
        String description,
        boolean enabled,
        boolean supportsRegulated,
        List<String> configKeys,
        Instant lastSync,
        ConnectorSyncResult lastResult) {
}
