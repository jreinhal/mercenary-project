package com.jreinhal.mercenary;

import com.jreinhal.mercenary.vector.LocalMongoVectorStore;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.MongoDBAtlasVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;

@SpringBootApplication
public class MercenaryApplication {

	public static void main(String[] args) {
		SpringApplication.run(MercenaryApplication.class, args);
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