/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.jreinhal.mercenary.service.TokenizationVault
 *  com.jreinhal.mercenary.service.TokenizationVault$TokenEntry
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.beans.factory.annotation.Value
 *  org.springframework.data.mongodb.core.MongoTemplate
 *  org.springframework.data.mongodb.core.query.Criteria
 *  org.springframework.data.mongodb.core.query.CriteriaDefinition
 *  org.springframework.data.mongodb.core.query.Query
 *  org.springframework.stereotype.Service
 */
package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.service.TokenizationVault;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.CriteriaDefinition;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

@Service
public class TokenizationVault {
    private static final Logger log = LoggerFactory.getLogger(TokenizationVault.class);
    private final MongoTemplate mongoTemplate;
    private final byte[] secretKey;
    @Value(value="${app.tokenization.enabled:true}")
    private boolean enabled;
    @Value(value="${app.tokenization.store-originals:true}")
    private boolean storeOriginals;
    private static final String AES_GCM_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    public TokenizationVault(MongoTemplate mongoTemplate, @Value(value="${app.tokenization.secret-key:}") String configuredKey) {
        this.mongoTemplate = mongoTemplate;
        if (configuredKey == null || configuredKey.isBlank()) {
            byte[] randomKey = new byte[32];
            new SecureRandom().nextBytes(randomKey);
            this.secretKey = randomKey;
            log.warn("=================================================================");
            log.warn("  TOKENIZATION VAULT: Using randomly generated key (DEV MODE)");
            log.warn("  Tokens will NOT be consistent across application restarts!");
            log.warn("  Set app.tokenization.secret-key for production use.");
            log.warn("=================================================================");
        } else {
            this.secretKey = Base64.getDecoder().decode(configuredKey);
            log.info("Tokenization Vault initialized with configured secret key");
        }
    }

    public String tokenize(String value, String piiType, String userId) {
        if (!this.enabled || value == null || value.isBlank()) {
            return value;
        }
        try {
            Query query;
            String token = this.generateToken(value, piiType);
            if (this.storeOriginals && this.mongoTemplate.findOne(query = new Query((CriteriaDefinition)Criteria.where((String)"token").is((Object)token)), TokenEntry.class) == null) {
                String encryptedValue = this.encryptValue(value);
                TokenEntry entry = new TokenEntry(token, encryptedValue, piiType, userId);
                this.mongoTemplate.save((Object)entry);
                log.debug("Tokenized {} value, stored in vault", (Object)piiType);
            }
            return token;
        }
        catch (Exception e) {
            log.error("Tokenization failed for {}: {}", (Object)piiType, (Object)e.getMessage());
            return "[TOKENIZATION-FAILED-" + piiType + "]";
        }
    }

    public Optional<String> detokenize(String token, String userId) {
        if (!this.enabled || !this.storeOriginals || token == null) {
            return Optional.empty();
        }
        try {
            Query query = new Query((CriteriaDefinition)Criteria.where((String)"token").is((Object)token));
            TokenEntry entry = (TokenEntry)this.mongoTemplate.findOne(query, TokenEntry.class);
            if (entry == null) {
                log.warn("Detokenization requested for unknown token by user {}", (Object)userId);
                return Optional.empty();
            }
            String originalValue = this.decryptValue(entry.getEncryptedValue());
            log.info("AUDIT: User {} detokenized {} value (created: {})", new Object[]{userId, entry.getPiiType(), entry.getCreatedAt()});
            return Optional.of(originalValue);
        }
        catch (Exception e) {
            log.error("Detokenization failed: {}", (Object)e.getMessage());
            return Optional.empty();
        }
    }

    public boolean isToken(String value) {
        return value != null && value.startsWith("<<TOK:");
    }

    private String generateToken(String value, String piiType) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(this.secretKey, "HmacSHA256");
        mac.init(keySpec);
        String input = piiType + ":" + value;
        byte[] hmac = mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        String base64Hmac = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac);
        String shortHash = base64Hmac.substring(0, Math.min(16, base64Hmac.length()));
        return "<<TOK:" + piiType + ":" + shortHash + ">>";
    }

    private String encryptValue(String value) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES_GCM_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(this.secretKey, "AES");
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
            log.error("AES-256-GCM encryption failed: {}", (Object)e.getMessage());
            throw new RuntimeException("Encryption failed", e);
        }
    }

    private String decryptValue(String encrypted) {
        try {
            byte[] encryptedBytes = Base64.getDecoder().decode(encrypted);
            ByteBuffer buffer = ByteBuffer.wrap(encryptedBytes);
            byte[] iv = new byte[12];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            Cipher cipher = Cipher.getInstance(AES_GCM_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(this.secretKey, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(128, iv);
            cipher.init(2, (Key)keySpec, gcmSpec);
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        }
        catch (Exception e) {
            log.error("AES-256-GCM decryption failed (possible tampering): {}", (Object)e.getMessage());
            throw new RuntimeException("Decryption failed - data may have been tampered with", e);
        }
    }

    public static String generateSecretKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}

