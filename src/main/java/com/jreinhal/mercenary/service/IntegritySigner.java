package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.Department;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
    @Value(value="${sentinel.integrity.secret-key:}")
    private String configuredKey;
    private SecretKeySpec keySpec;

    public IntegritySigner(HipaaPolicy hipaaPolicy) {
        this.hipaaPolicy = hipaaPolicy;
    }

    @PostConstruct
    public void init() {
        if (this.configuredKey == null || this.configuredKey.isBlank()) {
            if (this.hipaaPolicy.isStrict(Department.MEDICAL)) {
                throw new IllegalStateException("HIPAA medical deployments require sentinel.integrity.secret-key for integrity signing.");
            }
            log.warn("Integrity signing disabled (no sentinel.integrity.secret-key configured).");
            return;
        }
        byte[] raw;
        if (this.configuredKey.startsWith("base64:")) {
            raw = Base64.getDecoder().decode(this.configuredKey.substring("base64:".length()));
        } else {
            raw = this.configuredKey.getBytes(StandardCharsets.UTF_8);
        }
        this.keySpec = new SecretKeySpec(raw, HMAC_ALGO);
        log.info("Integrity signer initialized.");
    }

    public boolean isEnabled() {
        return this.keySpec != null;
    }

    public String sign(String payload) {
        if (this.keySpec == null) {
            throw new IllegalStateException("Integrity signing requested but no key is configured.");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(this.keySpec);
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute integrity signature", e);
        }
    }
}
