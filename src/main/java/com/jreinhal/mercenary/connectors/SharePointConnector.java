package com.jreinhal.mercenary.connectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.service.SecureIngestionService;
import java.util.HashMap;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

@Component
public class SharePointConnector implements Connector {
    private static final Logger log = LoggerFactory.getLogger(SharePointConnector.class);

    private final ConnectorPolicy policy;
    private final SecureIngestionService ingestionService;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();
    @Autowired(required = false)
    private ConnectorSyncStateService syncStateService;

    private static RestTemplate createNoRedirectRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                // H-08: Disable automatic redirect following to prevent SSRF bypass via 3xx redirects
                connection.setInstanceFollowRedirects(false);
            }
        };
        return new RestTemplate(factory);
    }

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

    @Autowired
    public SharePointConnector(ConnectorPolicy policy, SecureIngestionService ingestionService) {
        this(policy, ingestionService, createNoRedirectRestTemplate());
    }

    SharePointConnector(ConnectorPolicy policy, SecureIngestionService ingestionService, RestTemplate restTemplate) {
        this.policy = policy;
        this.ingestionService = ingestionService;
        this.restTemplate = restTemplate;
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
        int removed = 0;
        try {
            Department dept = resolveDepartment();
            String workspaceId = this.syncStateService != null ? this.syncStateService.currentWorkspaceId() : "";
            long runStartedAtEpochMs = System.currentTimeMillis();
            String syncRunId = Long.toString(runStartedAtEpochMs);
            boolean incremental = this.syncStateService != null && this.syncStateService.isEnabled();
            boolean listingComplete = true;
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + bearerToken);

            String path = "";
            if (StringUtils.hasText(folderPath)) {
                path = String.format("/drives/%s/root:/%s:/children", driveId, folderPath);
            } else {
                path = String.format("/drives/%s/root/children", driveId);
            }
            int pageSize = Math.max(1, Math.min(maxFiles, 200));
            String nextUrl = graphBase.replaceAll("/$", "") + path + "?$top=" + pageSize;
            while (StringUtils.hasText(nextUrl)) {
                ResponseEntity<String> response =
                        restTemplate.exchange(nextUrl, HttpMethod.GET, new HttpEntity<>(headers), String.class);
                if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                    return new ConnectorSyncResult(getName(), false, 0, 0, "SharePoint query failed");
                }
                JsonNode root = mapper.readTree(response.getBody());
                JsonNode items = root.path("value");
                if (!items.isArray()) {
                    return new ConnectorSyncResult(getName(), false, 0, 0, "SharePoint response missing items");
                }

                for (JsonNode item : items) {
                    if (loaded >= maxFiles) {
                        listingComplete = false;
                        break;
                    }
                    if (item.has("folder")) {
                        skipped++;
                        continue;
                    }
                    String downloadUrl = item.path("@microsoft.graph.downloadUrl").asText();
                    String name = item.path("name").asText("sharepoint_file");
                    String sourceKey = item.path("id").asText(name);
                    String fingerprint = incremental ? this.buildFingerprint(item) : "";
                    ConnectorSyncStateService.SourceState state = null;
                    if (incremental) {
                        state = this.syncStateService.getState(getName(), dept, workspaceId, sourceKey);
                        this.syncStateService.markSeen(
                                getName(), dept, workspaceId, sourceKey, name, fingerprint, runStartedAtEpochMs);
                        if (this.syncStateService.matchesFingerprint(state, fingerprint)) {
                            skipped++;
                            continue;
                        }
                    }
                    if (!StringUtils.hasText(downloadUrl)) {
                        skipped++;
                        continue;
                    }
                    // H-08: Validate download URL points to a trusted Microsoft domain (SSRF prevention)
                    if (!isTrustedDownloadUrl(downloadUrl)) {
                        if (log.isWarnEnabled()) {
                            log.warn("SharePoint download URL blocked (untrusted domain) for file: {}", name);
                        }
                        skipped++;
                        continue;
                    }
                    try {
                        byte[] bytes = restTemplate.getForObject(downloadUrl, byte[].class);
                        if (bytes == null || bytes.length == 0) {
                            skipped++;
                            continue;
                        }
                        String contentHash = incremental ? this.syncStateService.sha256(bytes) : "";
                        if (incremental && this.syncStateService.matchesContentHash(state, contentHash)) {
                            this.syncStateService.recordIngested(getName(), dept, workspaceId, sourceKey, name,
                                    fingerprint, contentHash, bytes.length, runStartedAtEpochMs);
                            skipped++;
                            continue;
                        }
                        ingestionService.ingestBytes(
                                bytes,
                                name,
                                dept,
                                this.buildConnectorMetadata(sourceKey, fingerprint, syncRunId));
                        if (incremental) {
                            this.syncStateService.pruneSupersededSourceDocuments(
                                    getName(), dept, workspaceId, sourceKey, syncRunId, name);
                            this.syncStateService.recordIngested(getName(), dept, workspaceId, sourceKey, name,
                                    fingerprint, contentHash, bytes.length, runStartedAtEpochMs);
                        }
                        loaded++;
                    } catch (Exception e) {
                        skipped++;
                        log.warn("SharePoint ingestion failed for {}: {}", name, e.getMessage());
                    }
                }

                String resolvedNext = root.path("@odata.nextLink").asText("");
                if (!StringUtils.hasText(resolvedNext)) {
                    break;
                }
                if (loaded >= maxFiles) {
                    listingComplete = false;
                    break;
                }
                nextUrl = resolvedNext;
            }

            if (incremental && listingComplete) {
                removed = this.syncStateService.pruneRemovedSources(getName(), dept, workspaceId, runStartedAtEpochMs);
            } else if (incremental && log.isInfoEnabled()) {
                log.info("SharePoint sync skipped removed-source pruning because listing was incomplete.");
            }
            String message = removed > 0
                    ? "SharePoint sync complete (pruned " + removed + " removed sources)"
                    : "SharePoint sync complete";
            return new ConnectorSyncResult(getName(), true, loaded, skipped + removed, message);
        } catch (Exception e) {
            log.warn("SharePoint connector failed: {}", e.getMessage());
            return new ConnectorSyncResult(getName(), false, loaded, skipped, "SharePoint sync failed: " + e.getMessage());
        }
    }

    private static final Set<String> TRUSTED_DOWNLOAD_DOMAINS = Set.of(
            ".sharepoint.com",
            ".sharepoint.cn",
            ".sharepoint-df.com",
            ".svc.ms"
    );

    private boolean isTrustedDownloadUrl(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            String lowerHost = host.toLowerCase(Locale.ROOT);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            for (String trusted : TRUSTED_DOWNLOAD_DOMAINS) {
                if (lowerHost.endsWith(trusted)) {
                    return true;
                }
            }
            return false;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private Department resolveDepartment() {
        try {
            return Department.fromString(department.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return Department.ENTERPRISE;
        }
    }

    private String buildFingerprint(JsonNode item) {
        if (this.syncStateService == null || item == null) {
            return "";
        }
        return this.syncStateService.stableFingerprint(
                item.path("eTag").asText(""),
                item.path("cTag").asText(""),
                item.path("lastModifiedDateTime").asText(""),
                item.path("size").asLong(-1L)
        );
    }

    private java.util.Map<String, Object> buildConnectorMetadata(String sourceKey, String fingerprint, String runId) {
        HashMap<String, Object> metadata = new HashMap<>();
        metadata.put("connectorName", getName());
        metadata.put("connectorSourceKey", sourceKey);
        if (fingerprint != null && !fingerprint.isBlank()) {
            metadata.put("connectorFingerprint", fingerprint);
        }
        if (runId != null && !runId.isBlank()) {
            metadata.put("connectorSyncRunId", runId);
        }
        return metadata;
    }
}
