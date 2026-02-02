package com.jreinhal.mercenary.connectors;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class ConnectorService {
    private final List<Connector> connectors;
    private final Map<String, ConnectorStatus> statusMap = new ConcurrentHashMap<>();

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
}
