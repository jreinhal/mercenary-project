package com.jreinhal.mercenary.connectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.service.SecureIngestionService;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class ConfluenceConnector implements Connector {
    private static final Logger log = LoggerFactory.getLogger(ConfluenceConnector.class);

    // M-08: Trusted Confluence/Atlassian domains for SSRF prevention
    private static final Set<String> TRUSTED_CONFLUENCE_DOMAINS = Set.of(
            ".atlassian.net",
            ".atlassian.com",
            ".jira.com"
    );

    private final ConnectorPolicy policy;
    private final SecureIngestionService ingestionService;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Tika tika = new Tika();

    private static RestTemplate createNoRedirectRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                // M-08: Disable automatic redirect following to prevent SSRF bypass via 3xx redirects
                connection.setInstanceFollowRedirects(false);
            }
        };
        return new RestTemplate(factory);
    }

    @Value("${sentinel.connectors.confluence.enabled:false}")
    private boolean enabled;
    @Value("${sentinel.connectors.confluence.base-url:}")
    private String baseUrl;
    @Value("${sentinel.connectors.confluence.email:}")
    private String email;
    @Value("${sentinel.connectors.confluence.api-token:}")
    private String apiToken;
    @Value("${sentinel.connectors.confluence.space-key:}")
    private String spaceKey;
    @Value("${sentinel.connectors.confluence.limit:25}")
    private int limit;
    @Value("${sentinel.connectors.confluence.max-pages:3}")
    private int maxPages;
    @Value("${sentinel.connectors.confluence.department:ENTERPRISE}")
    private String department;

    public ConfluenceConnector(ConnectorPolicy policy, SecureIngestionService ingestionService) {
        this.policy = policy;
        this.ingestionService = ingestionService;
        this.restTemplate = createNoRedirectRestTemplate();
    }

    @Override
    public String getName() {
        return "Confluence";
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
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(spaceKey)) {
            return new ConnectorSyncResult(getName(), false, 0, 0, "Missing Confluence configuration");
        }
        // M-08: Validate base URL points to a trusted Atlassian/Confluence domain (SSRF prevention)
        if (!isTrustedConfluenceUrl(baseUrl)) {
            if (log.isWarnEnabled()) {
                log.warn("Confluence base URL blocked (untrusted domain)");
            }
            return new ConnectorSyncResult(getName(), false, 0, 0, "Confluence URL not in trusted domain allowlist");
        }

        int loaded = 0;
        int skipped = 0;
        try {
            Department dept = resolveDepartment();
            String auth = email + ":" + apiToken;
            String basic = java.util.Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Basic " + basic);

            AtomicInteger start = new AtomicInteger(0);
            for (int page = 0; page < maxPages; page++) {
                String url = String.format("%s/rest/api/content?spaceKey=%s&limit=%d&start=%d&expand=body.storage",
                    baseUrl.replaceAll("/$", ""), spaceKey, limit, start.get());
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
                if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                    break;
                }
                JsonNode root = mapper.readTree(response.getBody());
                JsonNode results = root.path("results");
                if (!results.isArray() || results.size() == 0) {
                    break;
                }
                for (JsonNode item : results) {
                    String id = item.path("id").asText();
                    String title = item.path("title").asText("confluence-page");
                    String html = item.path("body").path("storage").path("value").asText();
                    if (!StringUtils.hasText(html)) {
                        skipped++;
                        continue;
                    }
                    String text = tika.parseToString(new java.io.ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
                    String filename = "confluence_" + id + "_" + sanitizeTitle(title) + ".txt";
                    ingestionService.ingestBytes(text.getBytes(StandardCharsets.UTF_8), filename, dept);
                    loaded++;
                }
                start.addAndGet(limit);
            }
            return new ConnectorSyncResult(getName(), true, loaded, skipped, "Confluence sync complete");
        } catch (Exception e) {
            log.warn("Confluence connector failed: {}", e.getMessage());
            return new ConnectorSyncResult(getName(), false, loaded, skipped, "Confluence sync failed: " + e.getMessage());
        }
    }

    private Department resolveDepartment() {
        try {
            return Department.valueOf(department.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return Department.ENTERPRISE;
        }
    }

    private String sanitizeTitle(String title) {
        if (title == null || title.isBlank()) return "page";
        return title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_");
    }

    private boolean isTrustedConfluenceUrl(String url) {
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
            for (String trusted : TRUSTED_CONFLUENCE_DOMAINS) {
                if (lowerHost.endsWith(trusted)) {
                    return true;
                }
            }
            return false;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
