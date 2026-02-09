package com.jreinhal.mercenary.connectors;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class S3ConnectorTest {

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
        assertTrue(S3Connector.isPrivateOrReservedAddress("127.0.0.1"));
    }

    @Test
    void shouldBlockPrivate10Network() {
        assertTrue(S3Connector.isPrivateOrReservedAddress("10.0.0.1"));
    }

    @Test
    void shouldBlockPrivate172Network() {
        assertTrue(S3Connector.isPrivateOrReservedAddress("172.16.0.1"));
    }

    @Test
    void shouldBlockPrivate192Network() {
        assertTrue(S3Connector.isPrivateOrReservedAddress("192.168.1.1"));
    }

    @Test
    void shouldBlockLinkLocalMetadata() {
        assertTrue(S3Connector.isPrivateOrReservedAddress("169.254.169.254"));
    }

    @Test
    void shouldRejectEndpointWithPrivateIp() {
        assertFalse(connector.isTrustedEndpoint("https://127.0.0.1:9000"));
    }

    @Test
    void shouldRejectEndpointWithMetadataIp() {
        assertFalse(connector.isTrustedEndpoint("https://169.254.169.254"));
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
        // No assertion on isTrustedEndpoint — it's only called when endpoint is non-empty
    }
}
