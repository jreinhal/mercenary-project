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

	// MANUAL OVERRIDE: We define the VectorStore bean ourselves to force specific settings.
	@Bean
	public VectorStore vectorStore(MongoTemplate mongoTemplate, EmbeddingModel embeddingModel) {
		// Explicitly configure the store config
		MongoDBAtlasVectorStore.MongoDBVectorStoreConfig config = MongoDBAtlasVectorStore.MongoDBVectorStoreConfig.builder()
				.withCollectionName("vector_store")
				.withVectorIndexName("vector_index")
				.withPathName("embedding")
				.withMetadataFieldsToFilter(List.of("dept", "source"))
				.build();

		// The 'false' boolean at the end is the "initializeSchema" flag.
		// passing 'false' here forces it to skip index creation.
		return new MongoDBAtlasVectorStore(mongoTemplate, embeddingModel, config, false);
	}
}