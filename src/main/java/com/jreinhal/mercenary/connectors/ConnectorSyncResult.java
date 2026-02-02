package com.jreinhal.mercenary.connectors;

public record ConnectorSyncResult(String name, boolean success, int loaded, int skipped, String message) {
}
