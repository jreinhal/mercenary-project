package com.jreinhal.mercenary.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.KmsClientBuilder;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptResponse;

@Component
public class KmsKeyDecryptor {
    private static final Logger log = LoggerFactory.getLogger(KmsKeyDecryptor.class);
    @Value("${sentinel.kms.enabled:false}")
    private boolean enabled;
    @Value("${sentinel.kms.region:}")
    private String region;
    @Value("${sentinel.kms.endpoint:}")
    private String endpoint;

    private KmsClient client;

    @PostConstruct
    public void init() {
        if (!this.enabled) {
            return;
        }
        KmsClientBuilder builder = KmsClient.builder();
        if (this.region != null && !this.region.isBlank()) {
            builder.region(Region.of(this.region));
        }
        if (this.endpoint != null && !this.endpoint.isBlank()) {
            builder.endpointOverride(URI.create(this.endpoint));
        }
        this.client = builder.build();
        log.info("KMS key decryptor initialized.");
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public byte[] decryptBase64Ciphertext(String base64Ciphertext) {
        if (!this.enabled) {
            throw new IllegalStateException("KMS decryption requested but sentinel.kms.enabled is false.");
        }
        if (base64Ciphertext == null || base64Ciphertext.isBlank()) {
            throw new IllegalArgumentException("KMS ciphertext is blank.");
        }
        if (this.client == null) {
            throw new IllegalStateException("KMS client is not initialized.");
        }
        byte[] cipherBytes = Base64.getDecoder().decode(base64Ciphertext);
        DecryptRequest request = DecryptRequest.builder()
                .ciphertextBlob(SdkBytes.fromByteArray(cipherBytes))
                .build();
        DecryptResponse response = this.client.decrypt(request);
        return response.plaintext().asByteArray();
    }

    @PreDestroy
    public void shutdown() {
        if (this.client != null) {
            this.client.close();
        }
    }
}
