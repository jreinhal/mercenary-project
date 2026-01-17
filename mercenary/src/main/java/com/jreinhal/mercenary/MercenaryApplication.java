package com.jreinhal.mercenary;

import com.jreinhal.mercenary.vector.LocalMongoVectorStore;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.MongoDBAtlasVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;

@SpringBootApplication
public class MercenaryApplication {

	private static final Logger log = LoggerFactory.getLogger(MercenaryApplication.class);

	private final Environment environment;

	public MercenaryApplication(Environment environment) {
		this.environment = environment;
	}

	public static void main(String[] args) {
		SpringApplication.run(MercenaryApplication.class, args);
	}

	/**
	 * SECURITY: Validate deployment configuration on startup.
	 * Prevents accidental deployment of DEV mode to production environments.
	 */
	@PostConstruct
	public void validateSecurityConfiguration() {
		String activeProfile = environment.getProperty("spring.profiles.active", "");
		String authMode = environment.getProperty("app.auth-mode", "DEV");
		String mongoUri = environment.getProperty("spring.data.mongodb.uri", "");

		// CRITICAL: Detect DEV mode with production MongoDB
		boolean isDevProfile = Arrays.stream(environment.getActiveProfiles())
				.anyMatch(profile -> "dev".equalsIgnoreCase(profile));
		boolean isDevMode = "DEV".equalsIgnoreCase(authMode) || isDevProfile;
		boolean isProductionDb = mongoUri.contains("mongodb+srv://") ||
				mongoUri.contains("mongodb.net") ||
				mongoUri.contains("atlas");

		if (isDevMode && isProductionDb) {
			log.error("=================================================================");
			log.error("  CRITICAL SECURITY ERROR: DEV MODE WITH PRODUCTION DATABASE");
			log.error("=================================================================");
			log.error("  Auth Mode: {}", authMode);
			log.error("  Profile: {}", activeProfile);
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

			String allowOverride = environment.getProperty("ALLOW_DEV_WITH_PRODUCTION_DB", "false");
			if (!"true".equalsIgnoreCase(allowOverride)) {
				throw new SecurityException(
						"DEV mode cannot be used with production database. " +
						"Set AUTH_MODE=OIDC or AUTH_MODE=CAC for production deployments.");
			} else {
				log.warn("!!! DEV MODE OVERRIDE ACTIVE - THIS IS EXTREMELY DANGEROUS !!!");
			}
		}

		// CRITICAL: Prevent DEV auth mode outside dev profile
		if ("DEV".equalsIgnoreCase(authMode) && !isDevProfile) {
			log.error("=================================================================");
			log.error("  CRITICAL SECURITY ERROR: DEV AUTH MODE OUTSIDE DEV PROFILE");
			log.error("=================================================================");
			log.error("  Auth Mode: {}", authMode);
			log.error("  Profile: {}", activeProfile);
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

			String allowOverride = environment.getProperty("ALLOW_DEV_AUTH", "false");
			if (!"true".equalsIgnoreCase(allowOverride)) {
				throw new SecurityException(
						"DEV auth mode is not allowed outside the dev profile. " +
						"Set AUTH_MODE=STANDARD/OIDC/CAC for non-dev deployments.");
			} else {
				log.warn("!!! DEV AUTH OVERRIDE ACTIVE - THIS IS EXTREMELY DANGEROUS !!!");
			}
		}

		// Warn if DEV mode is active (even with local DB)
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

		// Log successful security validation
		if (!isDevMode) {
			log.info("Security configuration validated:");
			log.info("  Auth Mode: {}", authMode);
			log.info("  Profile: {}", activeProfile);
		}
	}

	// MANUAL OVERRIDE: We define the VectorStore bean ourselves to force specific
	// settings.
	@Bean
	public VectorStore vectorStore(MongoTemplate mongoTemplate, EmbeddingModel embeddingModel,
			@org.springframework.beans.factory.annotation.Value("${app.auth-mode:DEV}") String authMode) {

		// If we are in CAC (GovCloud) or DEV mode locally, use LocalMongoVectorStore.
		// This provides persistence without requiring MongoDB Atlas Search ($vectorSearch).
		if ("CAC".equalsIgnoreCase(authMode) || "DEV".equalsIgnoreCase(authMode)) {
			return new LocalMongoVectorStore(mongoTemplate, embeddingModel);
		}

		// Explicitly configure the store config
		MongoDBAtlasVectorStore.MongoDBVectorStoreConfig config = MongoDBAtlasVectorStore.MongoDBVectorStoreConfig
				.builder()
				.withCollectionName("vector_store")
				.withVectorIndexName("vector_index")
				.withPathName("embedding")
				.withMetadataFieldsToFilter(List.of("dept", "source"))
				.build();

		// The 'false' boolean at the end is the "initializeSchema" flag.
		return new MongoDBAtlasVectorStore(mongoTemplate, embeddingModel, config, false);
	}
}
