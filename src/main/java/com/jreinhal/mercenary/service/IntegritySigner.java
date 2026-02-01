package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.Department;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class IntegritySigner {
    private static final Logger log = LoggerFactory.getLogger(IntegritySigner.class);
    private static final String HMAC_ALGO = "HmacSHA256";
    private final HipaaPolicy hipaaPolicy;
    private final KeyMaterialLoader keyMaterialLoader;
    @Value(value="${sentinel.integrity.secret-key:}")
    private String configuredKey;
    @Value(value="${sentinel.integrity.keys:}")
    private String configuredKeys;
    @Value(value="${sentinel.integrity.keys-kms:}")
    private String configuredKeysKms;
    @Value(value="${sentinel.integrity.active-key-id:}")
    private String activeKeyId;
    private SecretKeySpec keySpec;
    private Map<String, byte[]> keyRing;

    public IntegritySigner(HipaaPolicy hipaaPolicy, KeyMaterialLoader keyMaterialLoader) {
        this.hipaaPolicy = hipaaPolicy;
        this.keyMaterialLoader = keyMaterialLoader;
    }

    @PostConstruct
    public void init() {
        Map<String, byte[]> keys = new LinkedHashMap<>();
        if (this.configuredKeysKms != null && !this.configuredKeysKms.isBlank()) {
            keys.putAll(this.keyMaterialLoader.parseKeyMap(this.configuredKeysKms, true));
        }
        if (keys.isEmpty() && this.configuredKeys != null && !this.configuredKeys.isBlank()) {
            keys.putAll(this.keyMaterialLoader.parseKeyMap(this.configuredKeys, false));
        }
        if (keys.isEmpty() && this.configuredKey != null && !this.configuredKey.isBlank()) {
            byte[] raw = this.decodeLegacyKey(this.configuredKey);
            keys.put("legacy", raw);
        }
        if (keys.isEmpty()) {
            if (this.hipaaPolicy.isStrict(Department.MEDICAL)) {
                throw new IllegalStateException("HIPAA medical deployments require integrity keys (sentinel.integrity.keys or sentinel.integrity.keys-kms).");
            }
            log.warn("Integrity signing disabled (no keys configured).");
            return;
        }
        this.keyRing = keys;
        if (this.activeKeyId == null || this.activeKeyId.isBlank()) {
            this.activeKeyId = keys.keySet().iterator().next();
        }
        if (!keys.containsKey(this.activeKeyId)) {
            throw new IllegalStateException("Active integrity key id not found: " + this.activeKeyId);
        }
        this.keySpec = new SecretKeySpec(keys.get(this.activeKeyId), HMAC_ALGO);
        log.info("Integrity signer initialized with key id '{}'.", this.activeKeyId);
    }

    public boolean isEnabled() {
        return this.keySpec != null;
    }

    public Signature signWithKeyId(String payload) {
        if (this.keySpec == null) {
            throw new IllegalStateException("Integrity signing requested but no key is configured.");
        }
        return new Signature(this.activeKeyId, this.sign(payload, this.keySpec));
    }

    public boolean verify(String payload, String signature, String keyId) {
        if (this.keyRing == null || this.keyRing.isEmpty()) {
            return false;
        }
        if (signature == null || signature.isBlank()) {
            return false;
        }
        if (keyId != null && !keyId.isBlank()) {
            byte[] key = this.keyRing.get(keyId);
            if (key == null) {
                return false;
            }
            return signature.equals(this.sign(payload, new SecretKeySpec(key, HMAC_ALGO)));
        }
        for (Map.Entry<String, byte[]> entry : this.keyRing.entrySet()) {
            if (signature.equals(this.sign(payload, new SecretKeySpec(entry.getValue(), HMAC_ALGO)))) {
                return true;
            }
        }
        return false;
    }

    public String getActiveKeyId() {
        return this.activeKeyId;
    }

    private String sign(String payload, SecretKeySpec key) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(key);
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute integrity signature", e);
        }
    }

    private byte[] decodeLegacyKey(String rawKey) {
        if (rawKey.startsWith("base64:")) {
            return Base64.getDecoder().decode(rawKey.substring("base64:".length()));
        }
        return rawKey.getBytes(StandardCharsets.UTF_8);
    }

    public record Signature(String keyId, String signature) {
    }
}
