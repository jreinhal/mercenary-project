package com.jreinhal.mercenary.connectors;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.service.SecureIngestionService;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

@Component
public class S3Connector implements Connector {
    private static final Logger log = LoggerFactory.getLogger(S3Connector.class);

    /**
     * Trusted S3-compatible endpoint domains. Custom endpoints must match one
     * of these suffixes or be added via the {@code allowed-domains} config.
     */
    private static final Set<String> TRUSTED_S3_DOMAINS = Set.of(
            ".amazonaws.com",
            ".amazonaws.com.cn",
            ".r2.cloudflarestorage.com",
            ".digitaloceanspaces.com",
            ".backblazeb2.com"
    );

    private final ConnectorPolicy policy;
    private final SecureIngestionService ingestionService;

    @Value("${sentinel.connectors.s3.enabled:false}")
    private boolean enabled;
    @Value("${sentinel.connectors.s3.bucket:}")
    private String bucket;
    @Value("${sentinel.connectors.s3.prefix:}")
    private String prefix;
    @Value("${sentinel.connectors.s3.region:us-east-1}")
    private String region;
    @Value("${sentinel.connectors.s3.department:ENTERPRISE}")
    private String department;
    @Value("${sentinel.connectors.s3.max-files:100}")
    private int maxFiles;
    @Value("${sentinel.connectors.s3.access-key:}")
    private String accessKey;
    @Value("${sentinel.connectors.s3.secret-key:}")
    private String secretKey;
    @Value("${sentinel.connectors.s3.endpoint:}")
    private String endpoint;
    @Value("${sentinel.connectors.s3.allowed-domains:}")
    private List<String> allowedDomains;

    public S3Connector(ConnectorPolicy policy, SecureIngestionService ingestionService) {
        this.policy = policy;
        this.ingestionService = ingestionService;
    }

    @Override
    public String getName() {
        return "S3";
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
        if (bucket == null || bucket.isBlank()) {
            return new ConnectorSyncResult(getName(), false, 0, 0, "Missing S3 bucket configuration");
        }
        if (endpoint != null && !endpoint.isBlank() && !isTrustedEndpoint(endpoint)) {
            URI parsed = URI.create(endpoint);
            if (log.isWarnEnabled()) {
                log.warn("S3 endpoint blocked (untrusted domain or private IP): host={}", parsed.getHost());
            }
            return new ConnectorSyncResult(getName(), false, 0, 0,
                    "S3 endpoint not in trusted domain allowlist");
        }

        int loaded = 0;
        int skipped = 0;
        try (S3Client client = buildClient()) {
            ListObjectsV2Request.Builder listBuilder = ListObjectsV2Request.builder()
                .bucket(bucket)
                .maxKeys(maxFiles);
            if (prefix != null && !prefix.isBlank()) {
                listBuilder.prefix(prefix);
            }
            ListObjectsV2Response listResponse = client.listObjectsV2(listBuilder.build());
            Department dept = resolveDepartment();
            for (S3Object obj : listResponse.contents()) {
                if (loaded >= maxFiles) break;
                if (obj.size() == null || obj.size() <= 0) {
                    skipped++;
                    continue;
                }
                String key = obj.key();
                try (ResponseInputStream<?> stream = client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
                    byte[] bytes = stream.readAllBytes();
                    String filename = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
                    ingestionService.ingestBytes(bytes, filename, dept);
                    loaded++;
                } catch (Exception e) {
                    skipped++;
                    if (log.isWarnEnabled()) {
                        log.warn("S3 ingestion failed for {}: {}", key, e.getMessage());
                    }
                }
            }
            return new ConnectorSyncResult(getName(), true, loaded, skipped, "S3 sync complete");
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("S3 connector failed: {}", e.getMessage());
            }
            return new ConnectorSyncResult(getName(), false, loaded, skipped, "S3 sync failed: " + e.getMessage());
        }
    }

    /**
     * Validates that an S3-compatible endpoint URL is trusted.
     *
     * <p>Checks performed:</p>
     * <ol>
     *   <li>HTTPS scheme required</li>
     *   <li>Host matches configured {@code allowed-domains} list (permits private IPs
     *       for on-prem MinIO/Ceph)</li>
     *   <li>Host matches a built-in trusted S3-compatible domain suffix</li>
     *   <li>Host must not resolve to a private/loopback/link-local IP address</li>
     * </ol>
     */
    boolean isTrustedEndpoint(String endpointUrl) {
        try {
            URI uri = URI.create(endpointUrl);
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            String lowerHost = host.toLowerCase(Locale.ROOT);

            // Enforce HTTPS
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                if (log.isWarnEnabled()) {
                    log.warn("S3 endpoint rejected: HTTPS required, got scheme '{}'", uri.getScheme());
                }
                return false;
            }

            // Check configurable allowed domains first — permits on-prem (private IP) endpoints
            if (allowedDomains != null) {
                for (String allowed : allowedDomains) {
                    if (allowed != null && !allowed.isBlank()) {
                        String normalizedAllowed = allowed.trim().toLowerCase(Locale.ROOT);
                        if (!normalizedAllowed.startsWith(".")) {
                            normalizedAllowed = "." + normalizedAllowed;
                        }
                        if (lowerHost.endsWith(normalizedAllowed) || lowerHost.equals(normalizedAllowed.substring(1))) {
                            return true;
                        }
                    }
                }
            }

            // Check built-in trusted domains
            for (String trusted : TRUSTED_S3_DOMAINS) {
                if (lowerHost.endsWith(trusted)) {
                    return true;
                }
            }

            // Block private/loopback/link-local IP addresses (SSRF prevention)
            if (isPrivateOrReservedAddress(lowerHost)) {
                if (log.isWarnEnabled()) {
                    log.warn("S3 endpoint rejected: private/reserved IP address detected");
                }
                return false;
            }

            return false;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Checks if the given host is a private, loopback, or link-local address.
     * Blocks SSRF attempts targeting internal infrastructure (metadata services, etc.).
     */
    static boolean isPrivateOrReservedAddress(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                if (addr.isLoopbackAddress()
                        || addr.isSiteLocalAddress()
                        || addr.isLinkLocalAddress()
                        || addr.isAnyLocalAddress()) {
                    return true;
                }
                // Also block cloud metadata service IP (169.254.169.254)
                byte[] bytes = addr.getAddress();
                if (bytes.length == 4
                        && (bytes[0] & 0xFF) == 169
                        && (bytes[1] & 0xFF) == 254) {
                    return true;
                }
            }
            return false;
        } catch (UnknownHostException e) {
            // Fail closed: if we can't resolve the host, treat it as potentially private
            return true;
        }
    }

    private Department resolveDepartment() {
        try {
            return Department.fromString(department.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return Department.ENTERPRISE;
        }
    }

    private S3Client buildClient() {
        S3ClientBuilder builder = S3Client.builder().region(Region.of(region));
        if (endpoint != null && !endpoint.isBlank()) {
            // Endpoint already validated in sync() before reaching here
            builder.endpointOverride(URI.create(endpoint));
        }
        if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        }
        return builder.build();
    }
}
