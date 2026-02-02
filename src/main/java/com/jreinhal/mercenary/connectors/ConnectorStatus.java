package com.jreinhal.mercenary.connectors;

import java.time.Instant;

public record ConnectorStatus(String name, boolean enabled, Instant lastSync, ConnectorSyncResult lastResult) {
}
