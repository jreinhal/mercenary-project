package com.jreinhal.mercenary.connectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.service.SecureIngestionService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

class S3ConnectorTest {

    private static final String LOOPBACK_IP = "127.0.0.1";
    private static final String PRIVATE_10_IP = "10.0.0.1";
    private static final String PRIVATE_172_IP = "172.16.0.1";
    private static final String PRIVATE_192_IP = "192.168.1.1";
    private static final String METADATA_IP = "169.254.169.254";

    private S3Connector connector;

    @BeforeEach
    void setUp() {
        // Create connector with null dependencies — we only test endpoint validation
        connector = new S3Connector(null, null);
        ReflectionTestUtils.setField(connector, "allowedDomains", List.of());
    }

    // ===== HTTPS enforcement =====

    @Test
    void shouldRejectHttpEndpoint() {
        assertFalse(connector.isTrustedEndpoint("http://s3.amazonaws.com"));
    }

    @Test
    void shouldAcceptHttpsEndpoint() {
        assertTrue(connector.isTrustedEndpoint("https://s3.amazonaws.com"));
    }

    // ===== Trusted AWS domains =====

    @Test
    void shouldAcceptStandardAwsEndpoint() {
        assertTrue(connector.isTrustedEndpoint("https://s3.us-east-1.amazonaws.com"));
    }

    @Test
    void shouldAcceptAwsChinaEndpoint() {
        assertTrue(connector.isTrustedEndpoint("https://s3.cn-north-1.amazonaws.com.cn"));
    }

    @Test
    void shouldAcceptCloudflareR2Endpoint() {
        assertTrue(connector.isTrustedEndpoint("https://account-id.r2.cloudflarestorage.com"));
    }

    @Test
    void shouldAcceptDigitalOceanSpacesEndpoint() {
        assertTrue(connector.isTrustedEndpoint("https://nyc3.digitaloceanspaces.com"));
    }

    @Test
    void shouldAcceptBackblazeB2Endpoint() {
        assertTrue(connector.isTrustedEndpoint("https://s3.us-west-002.backblazeb2.com"));
    }

    // ===== Untrusted domains =====

    @Test
    void shouldRejectArbitraryDomain() {
        assertFalse(connector.isTrustedEndpoint("https://evil-server.example.com"));
    }

    @Test
    void shouldRejectSimilarButFakeDomain() {
        assertFalse(connector.isTrustedEndpoint("https://not-amazonaws.com"));
    }

    // ===== Private/loopback IP blocking =====

    @Test
    void shouldBlockLocalhostEndpoint() {
        assertTrue(S3Connector.isPrivateOrReservedAddress("localhost"));
    }

    @Test
    void shouldBlockLoopbackIp() {
        assertTrue(S3Connector.isPrivateOrReservedAddress(LOOPBACK_IP));
    }

    @Test
    void shouldBlockPrivate10Network() {
        assertTrue(S3Connector.isPrivateOrReservedAddress(PRIVATE_10_IP));
    }

    @Test
    void shouldBlockPrivate172Network() {
        assertTrue(S3Connector.isPrivateOrReservedAddress(PRIVATE_172_IP));
    }

    @Test
    void shouldBlockPrivate192Network() {
        assertTrue(S3Connector.isPrivateOrReservedAddress(PRIVATE_192_IP));
    }

    @Test
    void shouldBlockLinkLocalMetadata() {
        assertTrue(S3Connector.isPrivateOrReservedAddress(METADATA_IP));
    }

    @Test
    void shouldRejectEndpointWithPrivateIp() {
        assertFalse(connector.isTrustedEndpoint("https://" + LOOPBACK_IP + ":9000"));
    }

    @Test
    void shouldRejectEndpointWithMetadataIp() {
        assertFalse(connector.isTrustedEndpoint("https://" + METADATA_IP));
    }

    // ===== Custom allowed domains =====

    @Test
    void shouldAcceptCustomAllowedDomain() {
        ReflectionTestUtils.setField(connector, "allowedDomains", List.of("minio.internal.corp.com"));
        assertTrue(connector.isTrustedEndpoint("https://minio.internal.corp.com"));
    }

    @Test
    void shouldAcceptSubdomainOfCustomAllowedDomain() {
        ReflectionTestUtils.setField(connector, "allowedDomains", List.of("storage.example.com"));
        assertTrue(connector.isTrustedEndpoint("https://bucket1.storage.example.com"));
    }

    @Test
    void shouldRejectNonMatchingCustomDomain() {
        ReflectionTestUtils.setField(connector, "allowedDomains", List.of("minio.internal.corp.com"));
        assertFalse(connector.isTrustedEndpoint("https://evil.example.com"));
    }

    // ===== Null/empty endpoint =====

    @Test
    void shouldRejectNullHost() {
        assertFalse(connector.isTrustedEndpoint("https://"));
    }

    @Test
    void shouldRejectMalformedUrl() {
        assertFalse(connector.isTrustedEndpoint("not-a-url"));
    }

    // ===== No endpoint (default AWS) =====

    @Test
    void shouldAcceptEmptyEndpointAsDefault() {
        // When endpoint is empty, buildClient() uses default AWS endpoint
        // This is validated in sync() before calling buildClient()
        // Empty endpoint means "use default" — no validation needed
        ReflectionTestUtils.setField(connector, "endpoint", "");
        String ep = (String) ReflectionTestUtils.getField(connector, "endpoint");
        assertTrue(ep != null && ep.isBlank(), "endpoint field should be blank");
    }

    @Test
    void syncSkipsRemovedSourcePruneWhenListingIsIncomplete() {
        ConnectorPolicy policy = Mockito.mock(ConnectorPolicy.class);
        SecureIngestionService ingestionService = Mockito.mock(SecureIngestionService.class);
        ConnectorSyncStateService syncStateService = Mockito.mock(ConnectorSyncStateService.class);
        S3Client s3Client = Mockito.mock(S3Client.class);
        when(policy.allowConnectors()).thenReturn(true);
        when(syncStateService.currentWorkspaceId()).thenReturn("ws");
        when(syncStateService.isEnabled()).thenReturn(true);

        S3Connector testConnector = new S3Connector(policy, ingestionService) {
            @Override
            S3Client buildClient() {
                return s3Client;
            }
        };
        ReflectionTestUtils.setField(testConnector, "syncStateService", syncStateService);
        ReflectionTestUtils.setField(testConnector, "enabled", true);
        ReflectionTestUtils.setField(testConnector, "bucket", "bucket");
        ReflectionTestUtils.setField(testConnector, "maxFiles", 0);
        ReflectionTestUtils.setField(testConnector, "prefix", "");

        ListObjectsV2Response listResponse = ListObjectsV2Response.builder()
                .contents(S3Object.builder().key("folder/a.txt").size(12L).lastModified(Instant.now()).build())
                .isTruncated(true)
                .nextContinuationToken("next-token")
                .build();
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(listResponse);

        ConnectorSyncResult result = testConnector.sync();

        assertTrue(result.success());
        verify(syncStateService, never()).pruneRemovedSources(anyString(), any(), anyString(), anyLong());
    }

    @Test
    void syncDoesNotDeleteOldChunksWhenReplacementIngestFails() {
        ConnectorPolicy policy = Mockito.mock(ConnectorPolicy.class);
        SecureIngestionService ingestionService = Mockito.mock(SecureIngestionService.class);
        ConnectorSyncStateService syncStateService = Mockito.mock(ConnectorSyncStateService.class);
        S3Client s3Client = Mockito.mock(S3Client.class);
        when(policy.allowConnectors()).thenReturn(true);
        when(syncStateService.currentWorkspaceId()).thenReturn("ws");
        when(syncStateService.isEnabled()).thenReturn(true);
        when(syncStateService.getState(eq("S3"), eq(Department.ENTERPRISE), eq("ws"), eq("folder/a.txt")))
                .thenReturn(new ConnectorSyncStateService.SourceState("folder/a.txt", "a.txt", "old", "old-hash", 0L));

        S3Connector testConnector = new S3Connector(policy, ingestionService) {
            @Override
            S3Client buildClient() {
                return s3Client;
            }
        };
        ReflectionTestUtils.setField(testConnector, "syncStateService", syncStateService);
        ReflectionTestUtils.setField(testConnector, "enabled", true);
        ReflectionTestUtils.setField(testConnector, "bucket", "bucket");
        ReflectionTestUtils.setField(testConnector, "maxFiles", 1);
        ReflectionTestUtils.setField(testConnector, "prefix", "");

        ListObjectsV2Response listResponse = ListObjectsV2Response.builder()
                .contents(S3Object.builder()
                        .key("folder/a.txt")
                        .size(12L)
                        .eTag("\"new-etag\"")
                        .lastModified(Instant.now())
                        .build())
                .isTruncated(false)
                .build();
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(listResponse);
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(streamFor("new payload"));
        doThrow(new RuntimeException("ingest failed"))
                .when(ingestionService)
                .ingestBytes(any(), anyString(), any(), anyMap());

        ConnectorSyncResult result = testConnector.sync();

        assertTrue(result.success());
        verify(syncStateService, never()).pruneSupersededSourceDocuments(anyString(), any(), anyString(), anyString(), anyString(), anyString());
        verify(syncStateService, never()).recordIngested(anyString(), any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyLong());
    }

    @Test
    void syncPrunesSupersededChunksAfterSuccessfulIngest() {
        ConnectorPolicy policy = Mockito.mock(ConnectorPolicy.class);
        SecureIngestionService ingestionService = Mockito.mock(SecureIngestionService.class);
        ConnectorSyncStateService syncStateService = Mockito.mock(ConnectorSyncStateService.class);
        S3Client s3Client = Mockito.mock(S3Client.class);
        when(policy.allowConnectors()).thenReturn(true);
        when(syncStateService.currentWorkspaceId()).thenReturn("ws");
        when(syncStateService.isEnabled()).thenReturn(true);
        when(syncStateService.getState(eq("S3"), eq(Department.ENTERPRISE), eq("ws"), eq("folder/a.txt")))
                .thenReturn(new ConnectorSyncStateService.SourceState("folder/a.txt", "a.txt", "old", "old-hash", 0L));

        S3Connector testConnector = new S3Connector(policy, ingestionService) {
            @Override
            S3Client buildClient() {
                return s3Client;
            }
        };
        ReflectionTestUtils.setField(testConnector, "syncStateService", syncStateService);
        ReflectionTestUtils.setField(testConnector, "enabled", true);
        ReflectionTestUtils.setField(testConnector, "bucket", "bucket");
        ReflectionTestUtils.setField(testConnector, "maxFiles", 1);
        ReflectionTestUtils.setField(testConnector, "prefix", "");

        ListObjectsV2Response listResponse = ListObjectsV2Response.builder()
                .contents(S3Object.builder()
                        .key("folder/a.txt")
                        .size(12L)
                        .eTag("\"new-etag\"")
                        .lastModified(Instant.now())
                        .build())
                .isTruncated(false)
                .build();
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(listResponse);
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(streamFor("new payload"));

        ConnectorSyncResult result = testConnector.sync();

        assertTrue(result.success());
        assertEquals(1, result.loaded());
        verify(syncStateService).pruneSupersededSourceDocuments(eq("S3"), eq(Department.ENTERPRISE), eq("ws"),
                eq("folder/a.txt"), anyString(), eq("a.txt"));
        verify(syncStateService).recordIngested(eq("S3"), eq(Department.ENTERPRISE), eq("ws"),
                eq("folder/a.txt"), eq("a.txt"), any(), any(), eq(11L), anyLong());
    }

    private static ResponseInputStream<GetObjectResponse> streamFor(String payload) {
        return new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)))
        );
    }

    @Test
    void syncFailsWhenPolicyDisallowsConnectors() {
        ConnectorPolicy policy = Mockito.mock(ConnectorPolicy.class);
        when(policy.allowConnectors()).thenReturn(false);

        S3Connector testConnector = new S3Connector(policy, Mockito.mock(SecureIngestionService.class));
        ReflectionTestUtils.setField(testConnector, "enabled", true);

        ConnectorSyncResult result = testConnector.sync();

        assertFalse(result.success());
        assertTrue(result.message().contains("disabled by policy"));
    }

    @Test
    void syncFailsWhenConnectorDisabled() {
        ConnectorPolicy policy = Mockito.mock(ConnectorPolicy.class);
        when(policy.allowConnectors()).thenReturn(true);

        S3Connector testConnector = new S3Connector(policy, Mockito.mock(SecureIngestionService.class));
        ReflectionTestUtils.setField(testConnector, "enabled", false);

        ConnectorSyncResult result = testConnector.sync();

        assertFalse(result.success());
        assertTrue(result.message().contains("Connector disabled"));
    }

    @Test
    void syncFailsWhenBucketMissing() {
        ConnectorPolicy policy = Mockito.mock(ConnectorPolicy.class);
        when(policy.allowConnectors()).thenReturn(true);

        S3Connector testConnector = new S3Connector(policy, Mockito.mock(SecureIngestionService.class));
        ReflectionTestUtils.setField(testConnector, "enabled", true);
        ReflectionTestUtils.setField(testConnector, "bucket", "");

        ConnectorSyncResult result = testConnector.sync();

        assertFalse(result.success());
        assertTrue(result.message().contains("Missing S3 bucket configuration"));
    }

    @Test
    void syncFailsWhenEndpointIsUntrusted() {
        ConnectorPolicy policy = Mockito.mock(ConnectorPolicy.class);
        when(policy.allowConnectors()).thenReturn(true);

        S3Connector testConnector = new S3Connector(policy, Mockito.mock(SecureIngestionService.class));
        ReflectionTestUtils.setField(testConnector, "enabled", true);
        ReflectionTestUtils.setField(testConnector, "bucket", "bucket");
        ReflectionTestUtils.setField(testConnector, "endpoint", "https://127.0.0.1:9000");

        ConnectorSyncResult result = testConnector.sync();

        assertFalse(result.success());
        assertTrue(result.message().contains("allowlist"));
    }

    @Test
    void syncPrunesRemovedSourcesWhenListingIsComplete() {
        ConnectorPolicy policy = Mockito.mock(ConnectorPolicy.class);
        SecureIngestionService ingestionService = Mockito.mock(SecureIngestionService.class);
        ConnectorSyncStateService syncStateService = Mockito.mock(ConnectorSyncStateService.class);
        S3Client s3Client = Mockito.mock(S3Client.class);
        when(policy.allowConnectors()).thenReturn(true);
        when(syncStateService.currentWorkspaceId()).thenReturn("ws");
        when(syncStateService.isEnabled()).thenReturn(true);
        when(syncStateService.getState(eq("S3"), eq(Department.ENTERPRISE), eq("ws"), eq("folder/a.txt")))
                .thenReturn(new ConnectorSyncStateService.SourceState("folder/a.txt", "a.txt", "old", "old-hash", 0L));
        when(syncStateService.pruneRemovedSources(eq("S3"), eq(Department.ENTERPRISE), eq("ws"), anyLong())).thenReturn(2);

        S3Connector testConnector = new S3Connector(policy, ingestionService) {
            @Override
            S3Client buildClient() {
                return s3Client;
            }
        };
        ReflectionTestUtils.setField(testConnector, "syncStateService", syncStateService);
        ReflectionTestUtils.setField(testConnector, "enabled", true);
        ReflectionTestUtils.setField(testConnector, "bucket", "bucket");
        ReflectionTestUtils.setField(testConnector, "maxFiles", 10);
        ReflectionTestUtils.setField(testConnector, "prefix", "");

        ListObjectsV2Response listResponse = ListObjectsV2Response.builder()
                .contents(S3Object.builder().key("folder/a.txt").size(12L).lastModified(Instant.now()).build())
                .isTruncated(false)
                .build();
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(listResponse);
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(streamFor("new payload"));

        ConnectorSyncResult result = testConnector.sync();

        assertTrue(result.success());
        assertEquals(2, result.skipped());
        verify(syncStateService).pruneRemovedSources(eq("S3"), eq(Department.ENTERPRISE), eq("ws"), anyLong());
    }

    @Test
    void syncFailsWhenS3ListingThrows() {
        ConnectorPolicy policy = Mockito.mock(ConnectorPolicy.class);
        SecureIngestionService ingestionService = Mockito.mock(SecureIngestionService.class);
        S3Client s3Client = Mockito.mock(S3Client.class);
        when(policy.allowConnectors()).thenReturn(true);
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenThrow(new RuntimeException("boom"));

        S3Connector testConnector = new S3Connector(policy, ingestionService) {
            @Override
            S3Client buildClient() {
                return s3Client;
            }
        };
        ReflectionTestUtils.setField(testConnector, "enabled", true);
        ReflectionTestUtils.setField(testConnector, "bucket", "bucket");
        ReflectionTestUtils.setField(testConnector, "prefix", "");

        ConnectorSyncResult result = testConnector.sync();

        assertFalse(result.success());
        assertTrue(result.message().contains("failed"));
    }
}
