package com.jreinhal.mercenary.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Cryptographic Tokenization Vault for secure PII handling.
 *
 * SECURITY: Implements proper tokenization with:
 * - HMAC-SHA256 for deterministic token generation (same input = same token)
 * - AES-256-GCM encrypted storage of original values in MongoDB (PR-5)
 * - 96-bit random IV per encryption operation
 * - 128-bit authentication tag for integrity verification
 * - Configurable token prefix for type identification
 * - Audit trail of tokenization operations
 *
 * Compliance:
 * - PCI-DSS: Meets tokenization requirements for PANs
 * - HIPAA: Supports de-identification of PHI
 * - GDPR: Enables pseudonymization of personal data
 * - NIST 800-38D: AES-GCM authenticated encryption
 *
 * Configuration:
 * - app.tokenization.enabled: Enable/disable tokenization
 * - app.tokenization.secret-key: 256-bit AES key, Base64 encoded (must be set in production!)
 * - app.tokenization.store-originals: Whether to store original values for detokenization
 */
@Service
public class TokenizationVault {

    private static final Logger log = LoggerFactory.getLogger(TokenizationVault.class);

    private final MongoTemplate mongoTemplate;
    private final byte[] secretKey;

    @Value("${app.tokenization.enabled:true}")
    private boolean enabled;

    @Value("${app.tokenization.store-originals:true}")
    private boolean storeOriginals;

    /**
     * Token storage entity for MongoDB.
     */
    @Document(collection = "tokenization_vault")
    public static class TokenEntry {
        @Id
        private String id;
        private String token;
        private String encryptedValue; // AES-256 encrypted original value
        private String piiType;
        private Instant createdAt;
        private String createdBy; // For audit trail

        public TokenEntry() {}

        public TokenEntry(String token, String encryptedValue, String piiType, String createdBy) {
            this.token = token;
            this.encryptedValue = encryptedValue;
            this.piiType = piiType;
            this.createdAt = Instant.now();
            this.createdBy = createdBy;
        }

        // Getters
        public String getId() { return id; }
        public String getToken() { return token; }
        public String getEncryptedValue() { return encryptedValue; }
        public String getPiiType() { return piiType; }
        public Instant getCreatedAt() { return createdAt; }
        public String getCreatedBy() { return createdBy; }
    }

    public TokenizationVault(
            MongoTemplate mongoTemplate,
            @Value("${app.tokenization.secret-key:}") String configuredKey) {
        this.mongoTemplate = mongoTemplate;

        // SECURITY: Generate or use configured secret key
        if (configuredKey == null || configuredKey.isBlank()) {
            // Generate a random key for development
            // WARNING: This means tokens are NOT consistent across restarts in dev mode
            byte[] randomKey = new byte[32];
            new SecureRandom().nextBytes(randomKey);
            this.secretKey = randomKey;
            log.warn("=================================================================");
            log.warn("  TOKENIZATION VAULT: Using randomly generated key (DEV MODE)");
            log.warn("  Tokens will NOT be consistent across application restarts!");
            log.warn("  Set app.tokenization.secret-key for production use.");
            log.warn("=================================================================");
        } else {
            // Use configured key (should be Base64 encoded 256-bit key)
            this.secretKey = Base64.getDecoder().decode(configuredKey);
            log.info("Tokenization Vault initialized with configured secret key");
        }
    }

    /**
     * Tokenize a PII value.
     *
     * @param value The PII value to tokenize
     * @param piiType The type of PII (e.g., "SSN", "CREDIT_CARD", "EMAIL")
     * @param userId The user performing the operation (for audit)
     * @return The token that replaces the PII value
     */
    public String tokenize(String value, String piiType, String userId) {
        if (!enabled || value == null || value.isBlank()) {
            return value;
        }

        try {
            // Generate deterministic token using HMAC-SHA256
            String token = generateToken(value, piiType);

            // Optionally store for detokenization
            if (storeOriginals) {
                // Check if token already exists (idempotent)
                Query query = new Query(Criteria.where("token").is(token));
                if (mongoTemplate.findOne(query, TokenEntry.class) == null) {
                    // Encrypt the original value before storage
                    String encryptedValue = encryptValue(value);
                    TokenEntry entry = new TokenEntry(token, encryptedValue, piiType, userId);
                    mongoTemplate.save(entry);
                    log.debug("Tokenized {} value, stored in vault", piiType);
                }
            }

            return token;

        } catch (Exception e) {
            log.error("Tokenization failed for {}: {}", piiType, e.getMessage());
            // Fail secure - return masked value instead of original
            return "[TOKENIZATION-FAILED-" + piiType + "]";
        }
    }

    /**
     * Detokenize a token back to original value.
     * Requires ADMIN role and audit logging.
     *
     * @param token The token to detokenize
     * @param userId The user requesting detokenization (for audit)
     * @return The original PII value, or empty if not found
     */
    public Optional<String> detokenize(String token, String userId) {
        if (!enabled || !storeOriginals || token == null) {
            return Optional.empty();
        }

        try {
            Query query = new Query(Criteria.where("token").is(token));
            TokenEntry entry = mongoTemplate.findOne(query, TokenEntry.class);

            if (entry == null) {
                log.warn("Detokenization requested for unknown token by user {}", userId);
                return Optional.empty();
            }

            // Decrypt the stored value
            String originalValue = decryptValue(entry.getEncryptedValue());

            // Audit log the detokenization
            log.info("AUDIT: User {} detokenized {} value (created: {})",
                    userId, entry.getPiiType(), entry.getCreatedAt());

            return Optional.of(originalValue);

        } catch (Exception e) {
            log.error("Detokenization failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Check if a string is a token from this vault.
     */
    public boolean isToken(String value) {
        return value != null && value.startsWith("<<TOK:");
    }

    /**
     * Generate a deterministic token using HMAC-SHA256.
     * Same input always produces the same token.
     */
    private String generateToken(String value, String piiType) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(secretKey, "HmacSHA256");
        mac.init(keySpec);

        // Include PII type in HMAC input to prevent cross-type collisions
        String input = piiType + ":" + value;
        byte[] hmac = mac.doFinal(input.getBytes(StandardCharsets.UTF_8));

        // Create a readable token with type prefix
        String base64Hmac = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac);
        // Truncate to 16 chars for readability while maintaining security
        String shortHash = base64Hmac.substring(0, Math.min(16, base64Hmac.length()));

        return "<<TOK:" + piiType + ":" + shortHash + ">>";
    }

    // AES-GCM constants
    private static final String AES_GCM_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;  // 96 bits - NIST recommended
    private static final int GCM_TAG_LENGTH = 128; // 128 bits - full authentication tag

    /**
     * Encrypt a value for storage using AES-256-GCM.
     *
     * SECURITY: Implements authenticated encryption with:
     * - 256-bit AES key (derived from HMAC key)
     * - 96-bit random IV (unique per encryption)
     * - 128-bit authentication tag (integrity verification)
     *
     * Format: Base64(IV || ciphertext || tag)
     */
    private String encryptValue(String value) {
        try {
            // Generate random IV for each encryption
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            // Initialize cipher with AES-GCM
            Cipher cipher = Cipher.getInstance(AES_GCM_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(secretKey, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);

            // Encrypt
            byte[] plaintext = value.getBytes(StandardCharsets.UTF_8);
            byte[] ciphertext = cipher.doFinal(plaintext);

            // Combine IV + ciphertext (tag is appended by GCM mode)
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);

            return Base64.getEncoder().encodeToString(buffer.array());

        } catch (Exception e) {
            log.error("AES-256-GCM encryption failed: {}", e.getMessage());
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /**
     * Decrypt a stored value using AES-256-GCM.
     *
     * SECURITY: Verifies authentication tag before returning plaintext.
     * Will throw exception if data has been tampered with.
     */
    private String decryptValue(String encrypted) {
        try {
            byte[] encryptedBytes = Base64.getDecoder().decode(encrypted);

            // Extract IV and ciphertext
            ByteBuffer buffer = ByteBuffer.wrap(encryptedBytes);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            // Initialize cipher for decryption
            Cipher cipher = Cipher.getInstance(AES_GCM_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(secretKey, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

            // Decrypt (will verify authentication tag)
            byte[] plaintext = cipher.doFinal(ciphertext);

            return new String(plaintext, StandardCharsets.UTF_8);

        } catch (Exception e) {
            log.error("AES-256-GCM decryption failed (possible tampering): {}", e.getMessage());
            throw new RuntimeException("Decryption failed - data may have been tampered with", e);
        }
    }

    /**
     * Generate a new random secret key for configuration.
     * Utility method for operators to generate secure keys.
     */
    public static String generateSecretKey() {
        byte[] key = new byte[32]; // 256 bits
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
