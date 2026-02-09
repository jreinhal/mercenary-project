package com.jreinhal.mercenary.connectors;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.service.SecureIngestionService;
import java.net.URI;
import java.util.Locale;
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
                    log.warn("S3 ingestion failed for {}: {}", key, e.getMessage());
                }
            }
            return new ConnectorSyncResult(getName(), true, loaded, skipped, "S3 sync complete");
        } catch (Exception e) {
            log.warn("S3 connector failed: {}", e.getMessage());
            return new ConnectorSyncResult(getName(), false, loaded, skipped, "S3 sync failed: " + e.getMessage());
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
            builder.endpointOverride(URI.create(endpoint));
        }
        if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        }
        return builder.build();
    }
}
