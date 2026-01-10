package com.jreinhal.mercenary;

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

		// If we are in CAC (GovCloud) or DEV mode locally, we use our custom OFF-GRID
		// store.
		// THIS ENSURES DATA PERSISTS to local Mongo, but searches run in Java (No Atlas
		// needed).
		if ("CAC".equalsIgnoreCase(authMode) || "DEV".equalsIgnoreCase(authMode)) {
			return new com.jreinhal.mercenary.vector.LocalMongoVectorStore(mongoTemplate, embeddingModel);
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