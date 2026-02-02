package com.jreinhal.mercenary.connectors;

public interface Connector {
    String getName();

    boolean isEnabled();

    ConnectorSyncResult sync();
}
