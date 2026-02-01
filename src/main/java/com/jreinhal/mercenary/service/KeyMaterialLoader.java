package com.jreinhal.mercenary.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class KeyMaterialLoader {
    private final KmsKeyDecryptor kmsKeyDecryptor;

    public KeyMaterialLoader(KmsKeyDecryptor kmsKeyDecryptor) {
        this.kmsKeyDecryptor = kmsKeyDecryptor;
    }

    public Map<String, byte[]> parseKeyMap(String raw, boolean encrypted) {
        Map<String, byte[]> keys = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return keys;
        }
        String[] entries = raw.split(",");
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String trimmed = entry.trim();
            int idx = trimmed.indexOf(':');
            if (idx <= 0 || idx == trimmed.length() - 1) {
                throw new IllegalArgumentException("Invalid key entry (expected keyId:value): " + trimmed);
            }
            String keyId = trimmed.substring(0, idx).trim();
            String value = trimmed.substring(idx + 1).trim();
            if (keyId.isBlank() || value.isBlank()) {
                throw new IllegalArgumentException("Invalid key entry (blank keyId/value): " + trimmed);
            }
            byte[] keyBytes = encrypted ? decodeKmsKey(value) : decodeBase64(value);
            keys.put(keyId, keyBytes);
        }
        return keys;
    }

    private byte[] decodeKmsKey(String ciphertext) {
        if (!this.kmsKeyDecryptor.isEnabled()) {
            throw new IllegalStateException("KMS key material provided but sentinel.kms.enabled is false.");
        }
        byte[] plaintext = this.kmsKeyDecryptor.decryptBase64Ciphertext(ciphertext);
        String decoded = new String(plaintext, StandardCharsets.UTF_8).trim();
        return decodeBase64(decoded);
    }

    private byte[] decodeBase64(String value) {
        if (value.startsWith("base64:")) {
            return Base64.getDecoder().decode(value.substring("base64:".length()));
        }
        return Base64.getDecoder().decode(value);
    }
}
