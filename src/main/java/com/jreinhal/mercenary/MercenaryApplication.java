/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.jreinhal.mercenary.MercenaryApplication
 *  com.jreinhal.mercenary.vector.LocalMongoVectorStore
 *  jakarta.annotation.PostConstruct
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.ai.embedding.EmbeddingModel
 *  org.springframework.ai.vectorstore.MongoDBAtlasVectorStore
 *  org.springframework.ai.vectorstore.MongoDBAtlasVectorStore$MongoDBVectorStoreConfig
 *  org.springframework.ai.vectorstore.VectorStore
 *  org.springframework.beans.factory.annotation.Value
 *  org.springframework.boot.SpringApplication
 *  org.springframework.boot.autoconfigure.SpringBootApplication
 *  org.springframework.context.annotation.Bean
 *  org.springframework.core.env.Environment
 *  org.springframework.data.mongodb.core.MongoTemplate
 */
package com.jreinhal.mercenary;

import com.jreinhal.mercenary.vector.LocalMongoVectorStore;
import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.MongoDBAtlasVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoTemplate;

@SpringBootApplication
public class MercenaryApplication {
    private static final Logger log = LoggerFactory.getLogger(MercenaryApplication.class);
    private final Environment environment;

    public MercenaryApplication(Environment environment) {
        this.environment = environment;
    }

    public static void main(String[] args) {
        SpringApplication.run(MercenaryApplication.class, (String[])args);
    }

    @PostConstruct
    public void validateSecurityConfiguration() {
        String allowOverride;
        boolean isProductionDb;
        String activeProfile = this.environment.getProperty("spring.profiles.active", "");
        String authMode = this.environment.getProperty("app.auth-mode", "DEV");
        String mongoUri = this.environment.getProperty("spring.data.mongodb.uri", "");
        boolean isDevProfile = Arrays.stream(this.environment.getActiveProfiles()).anyMatch(profile -> "dev".equalsIgnoreCase((String)profile));
        boolean isDevMode = "DEV".equalsIgnoreCase(authMode) || isDevProfile;
        boolean bl = isProductionDb = mongoUri.contains("mongodb+srv://") || mongoUri.contains("mongodb.net") || mongoUri.contains("atlas");
        if (isDevMode && isProductionDb) {
            log.error("=================================================================");
            log.error("  CRITICAL SECURITY ERROR: DEV MODE WITH PRODUCTION DATABASE");
            log.error("=================================================================");
            log.error("  Auth Mode: {}", (Object)authMode);
            log.error("  Profile: {}", (Object)activeProfile);
            log.error("  MongoDB URI appears to be production (Atlas/cloud)");
            log.error("");
            log.error("  DEV mode auto-provisions ADMIN users with TOP_SECRET clearance!");
            log.error("  This is a CRITICAL security vulnerability in production.");
            log.error("");
            log.error("  To fix:");
            log.error("    - Set APP_PROFILE=enterprise or APP_PROFILE=govcloud");
            log.error("    - Set AUTH_MODE=OIDC or AUTH_MODE=CAC");
            log.error("");
            log.error("  To override (NOT RECOMMENDED):");
            log.error("    - Set ALLOW_DEV_WITH_PRODUCTION_DB=true");
            log.error("=================================================================");
            allowOverride = this.environment.getProperty("ALLOW_DEV_WITH_PRODUCTION_DB", "false");
            if (!"true".equalsIgnoreCase(allowOverride)) {
                throw new SecurityException("DEV mode cannot be used with production database. Set AUTH_MODE=OIDC or AUTH_MODE=CAC for production deployments.");
            }
            log.warn("!!! DEV MODE OVERRIDE ACTIVE - THIS IS EXTREMELY DANGEROUS !!!");
        }
        if ("DEV".equalsIgnoreCase(authMode) && !isDevProfile) {
            log.error("=================================================================");
            log.error("  CRITICAL SECURITY ERROR: DEV AUTH MODE OUTSIDE DEV PROFILE");
            log.error("=================================================================");
            log.error("  Auth Mode: {}", (Object)authMode);
            log.error("  Profile: {}", (Object)activeProfile);
            log.error("");
            log.error("  DEV mode auto-provisions ADMIN users with TOP_SECRET clearance!");
            log.error("  This is a critical misconfiguration for non-dev environments.");
            log.error("");
            log.error("  To fix:");
            log.error("    - Set APP_PROFILE=standard/enterprise/govcloud");
            log.error("    - Set AUTH_MODE=STANDARD/OIDC/CAC");
            log.error("");
            log.error("  To override (NOT RECOMMENDED):");
            log.error("    - Set ALLOW_DEV_AUTH=true");
            log.error("=================================================================");
            allowOverride = this.environment.getProperty("ALLOW_DEV_AUTH", "false");
            if (!"true".equalsIgnoreCase(allowOverride)) {
                throw new SecurityException("DEV auth mode is not allowed outside the dev profile. Set AUTH_MODE=STANDARD/OIDC/CAC for non-dev deployments.");
            }
            log.warn("!!! DEV AUTH OVERRIDE ACTIVE - THIS IS EXTREMELY DANGEROUS !!!");
        }
        if (isDevMode) {
            log.warn("=================================================================");
            log.warn("  WARNING: DEV MODE ACTIVE");
            log.warn("=================================================================");
            log.warn("  All requests will be authenticated as DEMO_USER with:");
            log.warn("    - Role: ADMIN");
            log.warn("    - Clearance: TOP_SECRET");
            log.warn("    - Sectors: ALL");
            log.warn("");
            log.warn("  Do NOT deploy this configuration to production!");
            log.warn("=================================================================");
        }
        if (!isDevMode) {
            log.info("Security configuration validated:");
            log.info("  Auth Mode: {}", (Object)authMode);
            log.info("  Profile: {}", (Object)activeProfile);
        }
    }

    @Bean
    public VectorStore vectorStore(MongoTemplate mongoTemplate, EmbeddingModel embeddingModel, @Value(value="${app.auth-mode:DEV}") String authMode) {
        if ("CAC".equalsIgnoreCase(authMode) || "DEV".equalsIgnoreCase(authMode)) {
            return new LocalMongoVectorStore(mongoTemplate, embeddingModel);
        }
        MongoDBAtlasVectorStore.MongoDBVectorStoreConfig config = MongoDBAtlasVectorStore.MongoDBVectorStoreConfig.builder().withCollectionName("vector_store").withVectorIndexName("vector_index").withPathName("embedding").withMetadataFieldsToFilter(List.of("dept", "source")).build();
        return new MongoDBAtlasVectorStore(mongoTemplate, embeddingModel, config, false);
    }
}

