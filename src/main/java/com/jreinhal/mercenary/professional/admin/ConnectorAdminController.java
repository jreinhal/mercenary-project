package com.jreinhal.mercenary.professional.admin;

import com.jreinhal.mercenary.connectors.ConnectorService;
import com.jreinhal.mercenary.connectors.ConnectorStatus;
import com.jreinhal.mercenary.connectors.ConnectorSyncResult;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value={"/api/admin/connectors"})
@PreAuthorize(value="hasRole('ADMIN')")
public class ConnectorAdminController {
    private final ConnectorService connectorService;

    public ConnectorAdminController(ConnectorService connectorService) {
        this.connectorService = connectorService;
    }

    @GetMapping(value={"/status"})
    public ResponseEntity<List<ConnectorStatus>> getStatuses() {
        return ResponseEntity.ok(connectorService.getStatuses());
    }

    @PostMapping(value={"/sync"})
    public ResponseEntity<List<ConnectorSyncResult>> syncAll() {
        return ResponseEntity.ok(connectorService.syncAll());
    }
}
