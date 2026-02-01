package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.Department;
import jakarta.annotation.PostConstruct;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.CriteriaDefinition;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

@Service
public class TokenizationVault {
    private static final Logger log = LoggerFactory.getLogger(TokenizationVault.class);
    private final MongoTemplate mongoTemplate;
    private final HipaaPolicy hipaaPolicy;
    private final KeyMaterialLoader keyMaterialLoader;
    private TokenizationKeyRing keyRing;
    @Value(value="${app.tokenization.enabled:true}")
    private boolean enabled;
    @Value(value="${app.tokenization.store-originals:true}")
    private boolean storeOriginals;
    @Value(value="${app.tokenization.secret-key:}")
    private String baseKey;
    @Value(value="${app.tokenization.hmac-key:}")
    private String configuredHmacKey;
    @Value(value="${app.tokenization.aes-key:}")
    private String configuredAesKey;
    @Value(value="${app.tokenization.active-key-id:}")
    private String activeKeyId;
    @Value(value="${app.tokenization.hmac-keys:}")
    private String hmacKeys;
    @Value(value="${app.tokenization.aes-keys:}")
    private String aesKeys;
    @Value(value="${app.tokenization.hmac-keys-kms:}")
    private String hmacKeysKms;
    @Value(value="${app.tokenization.aes-keys-kms:}")
    private String aesKeysKms;
    private static final String AES_GCM_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    public TokenizationVault(MongoTemplate mongoTemplate, HipaaPolicy hipaaPolicy, KeyMaterialLoader keyMaterialLoader) {
        this.mongoTemplate = mongoTemplate;
        this.hipaaPolicy = hipaaPolicy;
        this.keyMaterialLoader = keyMaterialLoader;
    }

    @PostConstruct
    public void init() {
        this.keyRing = this.buildKeyRing();
    }

    public String tokenize(String value, String piiType, String userId) {
        if (!this.enabled || value == null || value.isBlank()) {
            return value;
        }
        try {
            Query query;
            TokenizationKey activeKey = this.keyRing.activeKey();
            String token = this.generateToken(value, piiType, activeKey.hmacKey());
            if (this.storeOriginals && this.mongoTemplate.findOne(query = new Query((CriteriaDefinition)Criteria.where((String)"token").is(token)), TokenEntry.class) == null) {
                String encryptedValue = this.encryptValue(value, activeKey.aesKey());
                TokenEntry entry = new TokenEntry(token, encryptedValue, piiType, userId, this.keyRing.activeKeyId());
                this.mongoTemplate.save(entry);
                log.debug("Tokenized {} value, stored in vault", piiType);
            }
            return token;
        }
        catch (Exception e) {
            log.error("Tokenization failed for {}: {}", piiType, e.getMessage());
            return "[TOKENIZATION-FAILED-" + piiType + "]";
        }
    }

    public Optional<String> detokenize(String token, String userId) {
        if (!this.enabled || !this.storeOriginals || token == null) {
            return Optional.empty();
        }
        try {
            Query query = new Query((CriteriaDefinition)Criteria.where((String)"token").is(token));
            TokenEntry entry = (TokenEntry)this.mongoTemplate.findOne(query, TokenEntry.class);
            if (entry == null) {
                log.warn("Detokenization requested for unknown token by user {}", userId);
                return Optional.empty();
            }
            String originalValue = this.decryptEntry(entry);
            log.info("AUDIT: User {} detokenized {} value (created: {})", new Object[]{userId, entry.getPiiType(), entry.getCreatedAt()});
            return Optional.of(originalValue);
        }
        catch (Exception e) {
            log.error("Detokenization failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public boolean isToken(String value) {
        return value != null && value.startsWith("<<TOK:");
    }

    private String generateToken(String value, String piiType, byte[] hmacKey) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(hmacKey, "HmacSHA256");
        mac.init(keySpec);
        String input = piiType + ":" + value;
        byte[] hmac = mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        String base64Hmac = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac);
        String shortHash = base64Hmac.substring(0, Math.min(16, base64Hmac.length()));
        return "<<TOK:" + piiType + ":" + shortHash + ">>";
    }

    private String encryptValue(String value, byte[] aesKey) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES_GCM_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(aesKey, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(128, iv);
            cipher.init(1, (Key)keySpec, gcmSpec);
            byte[] plaintext = value.getBytes(StandardCharsets.UTF_8);
            byte[] ciphertext = cipher.doFinal(plaintext);
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);
            return Base64.getEncoder().encodeToString(buffer.array());
        }
        catch (Exception e) {
            log.error("AES-256-GCM encryption failed: {}", e.getMessage());
            throw new RuntimeException("Encryption failed", e);
        }
    }

    private String decryptValue(String encrypted, byte[] aesKey) {
        try {
            byte[] encryptedBytes = Base64.getDecoder().decode(encrypted);
            ByteBuffer buffer = ByteBuffer.wrap(encryptedBytes);
            byte[] iv = new byte[12];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            Cipher cipher = Cipher.getInstance(AES_GCM_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(aesKey, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(128, iv);
            cipher.init(2, (Key)keySpec, gcmSpec);
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        }
        catch (Exception e) {
            log.error("AES-256-GCM decryption failed (possible tampering): {}", e.getMessage());
            throw new RuntimeException("Decryption failed - data may have been tampered with", e);
        }
    }

    private String decryptEntry(TokenEntry entry) {
        if (entry.getKeyId() != null && !entry.getKeyId().isBlank()) {
            TokenizationKey key = this.keyRing.key(entry.getKeyId());
            if (key == null) {
                throw new IllegalStateException("Unknown tokenization key id: " + entry.getKeyId());
            }
            return this.decryptValue(entry.getEncryptedValue(), key.aesKey());
        }
        return this.decryptWithAnyKey(entry.getEncryptedValue());
    }

    private String decryptWithAnyKey(String encryptedValue) {
        for (String keyId : this.keyRing.keyIds()) {
            TokenizationKey key = this.keyRing.key(keyId);
            try {
                return this.decryptValue(encryptedValue, key.aesKey());
            } catch (RuntimeException e) {
                // try next key
            }
        }
        throw new IllegalStateException("Unable to decrypt tokenized value with available keys.");
    }

    public static String generateSecretKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private TokenizationKeyRing buildKeyRing() {
        Map<String, byte[]> hmacKeyMap = this.loadKeyMap(this.hmacKeysKms, this.hmacKeys);
        Map<String, byte[]> aesKeyMap = this.loadKeyMap(this.aesKeysKms, this.aesKeys);

        if (!hmacKeyMap.isEmpty() || !aesKeyMap.isEmpty()) {
            if (hmacKeyMap.isEmpty() || aesKeyMap.isEmpty()) {
                throw new IllegalStateException("Tokenization key rotation requires both HMAC and AES key maps.");
            }
            if (!hmacKeyMap.keySet().equals(aesKeyMap.keySet())) {
                throw new IllegalStateException("Tokenization key ids must match between HMAC and AES key maps.");
            }
            Map<String, TokenizationKey> keys = new LinkedHashMap<>();
            for (String keyId : hmacKeyMap.keySet()) {
                keys.put(keyId, new TokenizationKey(hmacKeyMap.get(keyId), aesKeyMap.get(keyId)));
            }
            String active = resolveActiveKeyId(keys.keySet());
            log.info("Tokenization vault initialized with key id '{}'.", active);
            return new TokenizationKeyRing(active, keys);
        }

        TokenizationKey legacy = this.resolveLegacyKey();
        if (legacy != null) {
            Map<String, TokenizationKey> keys = new LinkedHashMap<>();
            keys.put("legacy", legacy);
            return new TokenizationKeyRing("legacy", keys);
        }

        if (this.hipaaPolicy.isStrict(Department.MEDICAL)) {
            throw new IllegalStateException("HIPAA medical deployments require configured tokenization keys.");
        }

        byte[] randomKey = new byte[32];
        new SecureRandom().nextBytes(randomKey);
        log.warn("=================================================================");
        log.warn("  TOKENIZATION VAULT: Using randomly generated key (DEV MODE)");
        log.warn("  Tokens will NOT be consistent across application restarts!");
        log.warn("  Set app.tokenization.secret-key for production use.");
        log.warn("=================================================================");
        Map<String, TokenizationKey> keys = new LinkedHashMap<>();
        keys.put("dev", new TokenizationKey(randomKey, randomKey));
        return new TokenizationKeyRing("dev", keys);
    }

    private Map<String, byte[]> loadKeyMap(String kmsKeys, String rawKeys) {
        if (kmsKeys != null && !kmsKeys.isBlank()) {
            return this.keyMaterialLoader.parseKeyMap(kmsKeys, true);
        }
        return this.keyMaterialLoader.parseKeyMap(rawKeys, false);
    }

    private TokenizationKey resolveLegacyKey() {
        boolean hasBase = this.baseKey != null && !this.baseKey.isBlank();
        boolean hasHmac = this.configuredHmacKey != null && !this.configuredHmacKey.isBlank();
        boolean hasAes = this.configuredAesKey != null && !this.configuredAesKey.isBlank();

        if (hasHmac || hasAes) {
            if (!(hasHmac && hasAes)) {
                throw new IllegalStateException("Tokenization vault requires both app.tokenization.hmac-key and app.tokenization.aes-key when using split keys.");
            }
            return new TokenizationKey(decodeKey(this.configuredHmacKey, "HMAC"), decodeKey(this.configuredAesKey, "AES"));
        }
        if (hasBase) {
            byte[] base = decodeKey(this.baseKey, "base");
            return new TokenizationKey(deriveKey(base, "HMAC"), deriveKey(base, "AES"));
        }
        return null;
    }

    private String resolveActiveKeyId(Set<String> keyIds) {
        if (this.activeKeyId == null || this.activeKeyId.isBlank()) {
            return keyIds.iterator().next();
        }
        if (!keyIds.contains(this.activeKeyId)) {
            throw new IllegalStateException("Active tokenization key id not found: " + this.activeKeyId);
        }
        return this.activeKeyId;
    }

    private byte[] deriveKey(byte[] baseKey, String label) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(baseKey, "HmacSHA256"));
            return mac.doFinal(("TOKENIZATION-" + label).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to derive tokenization " + label + " key", e);
        }
    }

    private byte[] decodeKey(String raw, String label) {
        try {
            return Base64.getDecoder().decode(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid base64 for tokenization " + label + " key", e);
        }
    }

    private record TokenizationKey(byte[] hmacKey, byte[] aesKey) {
    }

    private record TokenizationKeyRing(String activeKeyId, Map<String, TokenizationKey> keys) {
        TokenizationKey activeKey() {
            return this.keys.get(this.activeKeyId);
        }

        TokenizationKey key(String keyId) {
            return this.keys.get(keyId);
        }

        Set<String> keyIds() {
            return this.keys.keySet();
        }
    }

    @Document(collection="tokenization_vault")
    public static class TokenEntry {
        @Id
        private String id;
        private String token;
        private String encryptedValue;
        private String piiType;
        private String keyId;
        private Instant createdAt;
        private String createdBy;

        public TokenEntry() {
        }

        public TokenEntry(String token, String encryptedValue, String piiType, String createdBy, String keyId) {
            this.token = token;
            this.encryptedValue = encryptedValue;
            this.piiType = piiType;
            this.keyId = keyId;
            this.createdAt = Instant.now();
            this.createdBy = createdBy;
        }

        public String getId() {
            return this.id;
        }

        public String getToken() {
            return this.token;
        }

        public String getEncryptedValue() {
            return this.encryptedValue;
        }

        public String getPiiType() {
            return this.piiType;
        }

        public String getKeyId() {
            return this.keyId;
        }

        public Instant getCreatedAt() {
            return this.createdAt;
        }

        public String getCreatedBy() {
            return this.createdBy;
        }
    }
}
