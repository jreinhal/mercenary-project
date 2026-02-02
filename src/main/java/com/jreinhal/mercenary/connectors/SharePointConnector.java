package com.jreinhal.mercenary.connectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.service.SecureIngestionService;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

@Component
public class SharePointConnector implements Connector {
    private static final Logger log = LoggerFactory.getLogger(SharePointConnector.class);

    private final ConnectorPolicy policy;
    private final SecureIngestionService ingestionService;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${sentinel.connectors.sharepoint.enabled:false}")
    private boolean enabled;
    @Value("${sentinel.connectors.sharepoint.graph-base:https://graph.microsoft.com/v1.0}")
    private String graphBase;
    @Value("${sentinel.connectors.sharepoint.drive-id:}")
    private String driveId;
    @Value("${sentinel.connectors.sharepoint.folder-path:}")
    private String folderPath;
    @Value("${sentinel.connectors.sharepoint.bearer-token:}")
    private String bearerToken;
    @Value("${sentinel.connectors.sharepoint.max-files:50}")
    private int maxFiles;
    @Value("${sentinel.connectors.sharepoint.department:ENTERPRISE}")
    private String department;

    public SharePointConnector(ConnectorPolicy policy, SecureIngestionService ingestionService) {
        this.policy = policy;
        this.ingestionService = ingestionService;
    }

    @Override
    public String getName() {
        return "SharePoint";
    }

    @Override
    public boolean isEnabled() {
        return enabled && policy.allowConnectors();
    }

    @Override
    public ConnectorSyncResult sync() {
        if (!policy.allowConnectors()) {
            return new ConnectorSyncResult(getName(), false, 0, 0, "Connectors disabled by policy");
        }
        if (!enabled) {
            return new ConnectorSyncResult(getName(), false, 0, 0, "Connector disabled");
        }
        if (!StringUtils.hasText(driveId) || !StringUtils.hasText(bearerToken)) {
            return new ConnectorSyncResult(getName(), false, 0, 0, "Missing SharePoint configuration");
        }

        int loaded = 0;
        int skipped = 0;
        try {
            Department dept = resolveDepartment();
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + bearerToken);

            String path = "";
            if (StringUtils.hasText(folderPath)) {
                path = String.format("/drives/%s/root:/%s:/children", driveId, folderPath);
            } else {
                path = String.format("/drives/%s/root/children", driveId);
            }
            String url = graphBase.replaceAll("/$", "") + path;

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return new ConnectorSyncResult(getName(), false, 0, 0, "SharePoint query failed");
            }
            JsonNode root = mapper.readTree(response.getBody());
            JsonNode items = root.path("value");
            if (!items.isArray()) {
                return new ConnectorSyncResult(getName(), false, 0, 0, "SharePoint response missing items");
            }

            for (JsonNode item : items) {
                if (loaded >= maxFiles) break;
                if (item.has("folder")) {
                    skipped++;
                    continue;
                }
                String downloadUrl = item.path("@microsoft.graph.downloadUrl").asText();
                String name = item.path("name").asText("sharepoint_file");
                if (!StringUtils.hasText(downloadUrl)) {
                    skipped++;
                    continue;
                }
                try {
                    byte[] bytes = restTemplate.getForObject(downloadUrl, byte[].class);
                    if (bytes == null || bytes.length == 0) {
                        skipped++;
                        continue;
                    }
                    ingestionService.ingestBytes(bytes, name, dept);
                    loaded++;
                } catch (Exception e) {
                    skipped++;
                    log.warn("SharePoint ingestion failed for {}: {}", name, e.getMessage());
                }
            }
            return new ConnectorSyncResult(getName(), true, loaded, skipped, "SharePoint sync complete");
        } catch (Exception e) {
            log.warn("SharePoint connector failed: {}", e.getMessage());
            return new ConnectorSyncResult(getName(), false, loaded, skipped, "SharePoint sync failed: " + e.getMessage());
        }
    }

    private Department resolveDepartment() {
        try {
            return Department.valueOf(department.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return Department.ENTERPRISE;
        }
    }
}
