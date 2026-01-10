package com.jreinhal.mercenary.config;

import com.jreinhal.mercenary.service.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration for Advanced RAG Services
 * 
 * Configures the research-backed RAG components:
 * - RAGPart Defense Service
 * - HyperGraph Memory Service  
 * - HiFi-RAG Service
 * - Advanced RAG Orchestrator
 */
@Configuration
public class AdvancedRAGConfig {

    private static final Logger log = LoggerFactory.getLogger(AdvancedRAGConfig.class);

    @Value("${sentinel.ragpart.enabled:true}")
    private boolean ragpartEnabled;

    @Value("${sentinel.hgmem.enabled:true}")
    private boolean hgmemEnabled;

    @Value("${sentinel.hifirag.enabled:true}")
    private boolean hifiragEnabled;

    @Value("${sentinel.ragpart.partitions:4}")
    private int defaultPartitions;

    @Value("${sentinel.ragpart.combination-size:3}")
    private int combinationSize;

    @Value("${sentinel.hgmem.max-memory-points:50}")
    private int maxMemoryPoints;

    @Value("${sentinel.hifirag.initial-retrieval-k:20}")
    private int initialRetrievalK;

    @Value("${sentinel.hifirag.filtered-top-k:5}")
    private int filteredTopK;

    /**
     * RAGPart Defense Service Bean
     * 
     * Implements corpus poisoning defense through document partitioning
     * and suspicious token detection (RAGMask).
     * 
     * Paper: arXiv:2512.24268v1
     */
    @Bean
    public RAGPartDefenseService ragPartDefenseService(
            VectorStore vectorStore,
            EmbeddingModel embeddingModel) {
        
        log.info("Initializing RAGPart Defense Service");
        log.info("  • Default partitions: {}", defaultPartitions);
        log.info("  • Combination size: {}", combinationSize);
        
        return new RAGPartDefenseService(vectorStore, embeddingModel);
    }

    /**
     * HyperGraph Memory Service Bean
     * 
     * Implements hypergraph-based working memory for multi-step RAG
     * with complex relational modeling.
     * 
     * Paper: arXiv:2512.23959v2
     */
    @Bean
    public HyperGraphMemoryService hyperGraphMemoryService(
            ChatClient.Builder chatClientBuilder) {
        
        log.info("Initializing HyperGraph Memory Service");
        log.info("  • Max memory points: {}", maxMemoryPoints);
        
        return new HyperGraphMemoryService(chatClientBuilder);
    }

    /**
     * HiFi-RAG Service Bean
     * 
     * Implements hierarchical content filtering, LLM-as-a-Reranker,
     * two-pass generation, and citation verification.
     * 
     * Paper: arXiv:2512.22442v1
     */
    @Bean
    public HiFiRAGService hiFiRAGService(
            ChatClient.Builder chatClientBuilder,
            VectorStore vectorStore) {
        
        log.info("Initializing HiFi-RAG Service");
        log.info("  • Initial retrieval K: {}", initialRetrievalK);
        log.info("  • Filtered top K: {}", filteredTopK);
        
        return new HiFiRAGService(chatClientBuilder, vectorStore);
    }

    /**
     * Advanced RAG Orchestrator Bean
     * 
     * Coordinates all RAG services and selects optimal processing
     * strategy based on query complexity.
     */
    @Bean
    public AdvancedRAGOrchestrator advancedRAGOrchestrator(
            RAGPartDefenseService ragPartService,
            HyperGraphMemoryService hgMemService,
            HiFiRAGService hiFiService,
            VectorStore vectorStore,
            AuditService auditService) {
        
        log.info("Initializing Advanced RAG Orchestrator");
        log.info("  • RAGPart enabled: {}", ragpartEnabled);
        log.info("  • HGMem enabled: {}", hgmemEnabled);
        log.info("  • HiFi-RAG enabled: {}", hifiragEnabled);
        
        return new AdvancedRAGOrchestrator(
            ragPartService,
            hgMemService,
            hiFiService,
            vectorStore,
            auditService
        );
    }
}
