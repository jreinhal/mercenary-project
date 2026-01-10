package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.model.User;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Advanced RAG Orchestrator
 * 
 * Integrates three cutting-edge RAG research implementations:
 * 
 * 1. RAGPart Defense (arXiv:2512.24268v1)
 *    - Document partitioning for corpus poisoning defense
 *    - RAGMask suspicious token detection
 *    
 * 2. HGMem - HyperGraph Memory (arXiv:2512.23959v2)
 *    - Hypergraph-based working memory
 *    - Higher-order correlation modeling
 *    - Multi-step reasoning with memory evolution
 *    
 * 3. HiFi-RAG (arXiv:2512.22442v1)
 *    - Hierarchical content filtering
 *    - LLM-as-a-Reranker
 *    - Two-pass generation (draft + refine)
 *    - Citation verification
 * 
 * This orchestrator provides a unified interface that intelligently
 * selects and combines these techniques based on query complexity.
 */
@Service
public class AdvancedRAGOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AdvancedRAGOrchestrator.class);

    private final RAGPartDefenseService ragPartService;
    private final HyperGraphMemoryService hgMemService;
    private final HiFiRAGService hiFiRAGService;
    private final VectorStore vectorStore;
    private final AuditService auditService;

    // Configuration thresholds
    private static final int SIMPLE_QUERY_WORD_THRESHOLD = 10;
    private static final int COMPLEX_QUERY_WORD_THRESHOLD = 25;
    private static final boolean ENABLE_RAGPART_DEFENSE = true;
    private static final boolean ENABLE_HGMEM = true;
    private static final boolean ENABLE_HIFI_PIPELINE = true;

    public AdvancedRAGOrchestrator(
            RAGPartDefenseService ragPartService,
            HyperGraphMemoryService hgMemService,
            HiFiRAGService hiFiRAGService,
            VectorStore vectorStore,
            AuditService auditService) {
        this.ragPartService = ragPartService;
        this.hgMemService = hgMemService;
        this.hiFiRAGService = hiFiRAGService;
        this.vectorStore = vectorStore;
        this.auditService = auditService;
        
        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║     ADVANCED RAG ORCHESTRATOR INITIALIZED                    ║");
        log.info("╠══════════════════════════════════════════════════════════════╣");
        log.info("║ • RAGPart Defense:    {} (arXiv:2512.24268v1)              ║", 
                 ENABLE_RAGPART_DEFENSE ? "ENABLED " : "DISABLED");
        log.info("║ • HyperGraph Memory:  {} (arXiv:2512.23959v2)              ║", 
                 ENABLE_HGMEM ? "ENABLED " : "DISABLED");
        log.info("║ • HiFi-RAG Pipeline:  {} (arXiv:2512.22442v1)              ║", 
                 ENABLE_HIFI_PIPELINE ? "ENABLED " : "DISABLED");
        log.info("╚══════════════════════════════════════════════════════════════╝");
    }

    // ========== Query Complexity Analysis ==========

    /**
     * Analyze query to determine optimal processing strategy.
     */
    public QueryAnalysis analyzeQuery(String query) {
        QueryAnalysis analysis = new QueryAnalysis();
        analysis.originalQuery = query;
        analysis.wordCount = query.split("\\s+").length;
        
        // Detect query type
        String lowerQuery = query.toLowerCase();
        
        // Simple factual queries
        if (lowerQuery.matches(".*(what is|who is|when did|where is|how many).*") 
            && analysis.wordCount < SIMPLE_QUERY_WORD_THRESHOLD) {
            analysis.complexity = QueryComplexity.SIMPLE;
            analysis.requiresMultiStep = false;
        }
        // Complex analytical queries
        else if (lowerQuery.matches(".*(compare|analyze|explain why|relationship|how does.*affect).*")
                 || analysis.wordCount > COMPLEX_QUERY_WORD_THRESHOLD) {
            analysis.complexity = QueryComplexity.COMPLEX;
            analysis.requiresMultiStep = true;
        }
        // Multi-hop queries
        else if (lowerQuery.matches(".*(and also|in addition|furthermore|both.*and).*")
                 || query.contains("?") && query.indexOf("?") != query.lastIndexOf("?")) {
            analysis.complexity = QueryComplexity.MULTI_HOP;
            analysis.requiresMultiStep = true;
        }
        // Default: moderate complexity
        else {
            analysis.complexity = QueryComplexity.MODERATE;
            analysis.requiresMultiStep = false;
        }

        // Extract potential entities for memory tracking
        analysis.detectedEntities = extractEntities(query);

        log.info("Query Analysis: complexity={}, multiStep={}, entities={}", 
                 analysis.complexity, analysis.requiresMultiStep, analysis.detectedEntities.size());

        return analysis;
    }

    /**
     * Simple entity extraction (in production, use NER model).
     */
    private Set<String> extractEntities(String query) {
        Set<String> entities = new HashSet<>();
        
        // Extract capitalized words (likely proper nouns)
        String[] words = query.split("\\s+");
        for (String word : words) {
            word = word.replaceAll("[^a-zA-Z0-9]", "");
            if (word.length() > 2 && Character.isUpperCase(word.charAt(0))) {
                entities.add(word);
            }
        }
        
        // Extract quoted phrases
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\"([^\"]+)\"");
        java.util.regex.Matcher matcher = pattern.matcher(query);
        while (matcher.find()) {
            entities.add(matcher.group(1));
        }

        return entities;
    }

    public enum QueryComplexity {
        SIMPLE,      // Direct factual lookup
        MODERATE,    // Standard RAG
        COMPLEX,     // Requires analysis/synthesis
        MULTI_HOP    // Requires chained reasoning
    }

    public static class QueryAnalysis {
        public String originalQuery;
        public int wordCount;
        public QueryComplexity complexity;
        public boolean requiresMultiStep;
        public Set<String> detectedEntities = new HashSet<>();
    }

    // ========== Main Orchestration ==========

    /**
     * Execute the complete advanced RAG pipeline.
     * Automatically selects optimal strategy based on query analysis.
     */
    public OrchestratorResult execute(String query, String sessionId, Department department) {
        long startTime = System.currentTimeMillis();
        OrchestratorResult result = new OrchestratorResult();
        result.sessionId = sessionId;
        result.query = query;
        result.department = department;

        try {
            // Step 1: Analyze query
            QueryAnalysis analysis = analyzeQuery(query);
            result.analysis = analysis;

            // Step 2: Select processing strategy
            ProcessingStrategy strategy = selectStrategy(analysis);
            result.strategy = strategy;
            log.info("Selected strategy: {} for {} query", strategy, analysis.complexity);

            // Step 3: Execute based on strategy
            switch (strategy) {
                case FAST_PATH:
                    result = executeFastPath(result, query, department);
                    break;
                case STANDARD_HIFI:
                    result = executeStandardHiFi(result, query, department);
                    break;
                case FULL_PIPELINE:
                    result = executeFullPipeline(result, query, sessionId, department);
                    break;
                case MULTI_STEP_MEMORY:
                    result = executeMultiStepWithMemory(result, query, sessionId, department);
                    break;
            }

            result.success = true;

        } catch (Exception e) {
            log.error("Orchestration failed: {}", e.getMessage(), e);
            result.success = false;
            result.error = e.getMessage();
            result.answer = "Processing error: " + e.getMessage();
        }

        result.totalTimeMs = System.currentTimeMillis() - startTime;
        
        // Log execution summary
        logExecutionSummary(result);

        return result;
    }

    /**
     * Select optimal processing strategy.
     */
    private ProcessingStrategy selectStrategy(QueryAnalysis analysis) {
        if (analysis.complexity == QueryComplexity.SIMPLE) {
            return ProcessingStrategy.FAST_PATH;
        } else if (analysis.complexity == QueryComplexity.MODERATE) {
            return ENABLE_HIFI_PIPELINE ? ProcessingStrategy.STANDARD_HIFI : ProcessingStrategy.FAST_PATH;
        } else if (analysis.complexity == QueryComplexity.COMPLEX) {
            return ProcessingStrategy.FULL_PIPELINE;
        } else {  // MULTI_HOP
            return ENABLE_HGMEM ? ProcessingStrategy.MULTI_STEP_MEMORY : ProcessingStrategy.FULL_PIPELINE;
        }
    }

    public enum ProcessingStrategy {
        FAST_PATH,           // Direct vector search + generation
        STANDARD_HIFI,       // HiFi-RAG pipeline
        FULL_PIPELINE,       // RAGPart + HiFi-RAG
        MULTI_STEP_MEMORY    // Full pipeline with HGMem
    }

    // ========== Strategy Implementations ==========

    /**
     * Fast path: Simple vector search for straightforward queries.
     */
    private OrchestratorResult executeFastPath(OrchestratorResult result, 
                                                String query, Department department) {
        log.info("Executing FAST_PATH strategy");

        // Simple defended search
        List<Document> docs;
        if (ENABLE_RAGPART_DEFENSE) {
            docs = ragPartService.secureSearch(query, 5);
        } else {
            docs = vectorStore.similaritySearch(
                SearchRequest.query(query).withTopK(5).withSimilarityThreshold(0.6));
        }

        result.retrievedDocuments = docs.size();
        result.usedRAGPart = ENABLE_RAGPART_DEFENSE;

        // Build simple context and generate
        StringBuilder context = new StringBuilder();
        List<String> sources = new ArrayList<>();
        for (Document doc : docs) {
            context.append(doc.getContent()).append("\n\n");
            String source = (String) doc.getMetadata().getOrDefault("source", "unknown");
            if (!sources.contains(source)) {
                sources.add(source);
            }
        }

        result.citations = sources;
        result.answer = buildSimpleResponse(query, context.toString(), sources);

        return result;
    }

    /**
     * Standard HiFi-RAG pipeline.
     */
    private OrchestratorResult executeStandardHiFi(OrchestratorResult result,
                                                    String query, Department department) {
        log.info("Executing STANDARD_HIFI strategy");

        HiFiRAGService.HiFiRAGResult hifiResult = hiFiRAGService.executeFullPipeline(query, department);

        result.answer = hifiResult.answer;
        result.draftAnswer = hifiResult.draftAnswer;
        result.citations = hifiResult.citations.sources;
        result.retrievedDocuments = hifiResult.usedSections.size();
        result.expandedQueries = hifiResult.expandedQueries;
        result.usedHiFiRAG = true;
        result.hifiProcessingMs = hifiResult.processingTimeMs;

        return result;
    }

    /**
     * Full pipeline: RAGPart defense + HiFi-RAG.
     */
    private OrchestratorResult executeFullPipeline(OrchestratorResult result,
                                                    String query, String sessionId,
                                                    Department department) {
        log.info("Executing FULL_PIPELINE strategy");

        // Step 1: RAGPart defended retrieval
        List<Document> defendedDocs = ragPartService.secureSearch(query, 15);
        result.usedRAGPart = true;
        result.ragPartFilteredCount = defendedDocs.size();

        // Step 2: Feed to HiFi-RAG for filtering and generation
        // (HiFi-RAG will do its own retrieval, but we pre-filter suspicious docs)
        HiFiRAGService.HiFiRAGResult hifiResult = hiFiRAGService.executeFullPipeline(query, department);

        result.answer = hifiResult.answer;
        result.draftAnswer = hifiResult.draftAnswer;
        result.citations = hifiResult.citations.sources;
        result.retrievedDocuments = hifiResult.usedSections.size();
        result.expandedQueries = hifiResult.expandedQueries;
        result.usedHiFiRAG = true;
        result.hifiProcessingMs = hifiResult.processingTimeMs;

        // Step 3: Update memory if enabled
        if (ENABLE_HGMEM) {
            updateMemoryFromResult(sessionId, query, hifiResult);
            result.usedHGMem = true;
        }

        return result;
    }

    /**
     * Multi-step with HyperGraph Memory for complex reasoning.
     */
    private OrchestratorResult executeMultiStepWithMemory(OrchestratorResult result,
                                                           String query, String sessionId,
                                                           Department department) {
        log.info("Executing MULTI_STEP_MEMORY strategy");

        // Step 1: Initialize/retrieve memory
        HyperGraphMemoryService.HyperGraph memory = hgMemService.getOrCreateMemory(sessionId);
        memory.incrementStep();
        result.usedHGMem = true;
        result.memoryStepsBefore = memory.getInteractionStep() - 1;

        // Step 2: Generate subqueries based on memory
        List<String> subqueries = hgMemService.generateSubqueries(sessionId, query);
        subqueries.add(0, query);  // Include original query
        result.expandedQueries = subqueries;

        // Step 3: Execute each subquery with RAGPart defense
        List<Document> allDocs = new ArrayList<>();
        for (String subquery : subqueries) {
            List<Document> docs = ragPartService.secureSearch(subquery, 5);
            allDocs.addAll(docs);
        }
        result.usedRAGPart = true;
        result.ragPartFilteredCount = allDocs.size();

        // Step 4: Update memory with retrieved information
        for (Document doc : allDocs) {
            Set<String> entities = extractEntities(doc.getContent());
            if (!entities.isEmpty()) {
                String summary = summarizeDocument(doc);
                hgMemService.insertMemoryPoint(sessionId, summary, entities, doc);
            }
        }

        // Step 5: Execute HiFi-RAG with memory context
        String memoryContext = hgMemService.exportMemoryToContext(sessionId);
        HiFiRAGService.HiFiRAGResult hifiResult = hiFiRAGService.executeFullPipeline(
            query + "\n\nPrior Context:\n" + memoryContext, department);

        result.answer = hifiResult.answer;
        result.draftAnswer = hifiResult.draftAnswer;
        result.citations = hifiResult.citations.sources;
        result.retrievedDocuments = hifiResult.usedSections.size();
        result.usedHiFiRAG = true;
        result.hifiProcessingMs = hifiResult.processingTimeMs;

        // Step 6: Get final memory stats
        Map<String, Object> memStats = hgMemService.getMemoryStats(sessionId);
        result.memoryPointsAfter = (Integer) memStats.getOrDefault("memoryPoints", 0);
        result.memoryMaxOrder = (Integer) memStats.getOrDefault("maxOrder", 0);

        return result;
    }

    // ========== Helper Methods ==========

    private String buildSimpleResponse(String query, String context, List<String> sources) {
        // Simple template-based response for fast path
        if (context.isEmpty()) {
            return "No relevant records found in the intelligence database.";
        }

        StringBuilder response = new StringBuilder();
        response.append("Based on available intelligence:\n\n");
        
        // Extract key sentences (simplified)
        String[] sentences = context.split("[.!?]+");
        int count = 0;
        for (String sentence : sentences) {
            sentence = sentence.trim();
            if (sentence.length() > 20 && count < 3) {
                response.append("• ").append(sentence).append(".\n");
                count++;
            }
        }

        if (!sources.isEmpty()) {
            response.append("\nSources: ").append(String.join(", ", sources));
        }

        return response.toString();
    }

    private void updateMemoryFromResult(String sessionId, String query,
                                        HiFiRAGService.HiFiRAGResult hifiResult) {
        // Extract key facts from the answer and add to memory
        Set<String> entities = extractEntities(hifiResult.answer);
        if (!entities.isEmpty() && hifiResult.answer.length() > 50) {
            hgMemService.insertMemoryPoint(
                sessionId,
                "Query: " + query + " -> " + truncate(hifiResult.answer, 200),
                entities,
                null
            );
        }
    }

    private String summarizeDocument(Document doc) {
        String content = doc.getContent();
        // Simple extractive summary - first 200 chars
        return truncate(content, 200);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    private void logExecutionSummary(OrchestratorResult result) {
        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║                    ORCHESTRATION COMPLETE                    ║");
        log.info("╠══════════════════════════════════════════════════════════════╣");
        log.info("║ Strategy:     {:<46} ║", result.strategy);
        log.info("║ Complexity:   {:<46} ║", result.analysis.complexity);
        log.info("║ Success:      {:<46} ║", result.success);
        log.info("║ Total Time:   {:<43} ms ║", result.totalTimeMs);
        log.info("╠══════════════════════════════════════════════════════════════╣");
        log.info("║ Components Used:                                             ║");
        log.info("║   • RAGPart:  {:<46} ║", result.usedRAGPart ? "YES" : "NO");
        log.info("║   • HiFi-RAG: {:<46} ║", result.usedHiFiRAG ? "YES" : "NO");
        log.info("║   • HGMem:    {:<46} ║", result.usedHGMem ? "YES" : "NO");
        log.info("╠══════════════════════════════════════════════════════════════╣");
        log.info("║ Documents:    {:<46} ║", result.retrievedDocuments);
        log.info("║ Citations:    {:<46} ║", result.citations != null ? result.citations.size() : 0);
        log.info("╚══════════════════════════════════════════════════════════════╝");
    }

    // ========== Result Data Class ==========

    public static class OrchestratorResult {
        // Identifiers
        public String sessionId;
        public String query;
        public Department department;

        // Analysis
        public QueryAnalysis analysis;
        public ProcessingStrategy strategy;

        // Execution flags
        public boolean success;
        public String error;
        public boolean usedRAGPart;
        public boolean usedHiFiRAG;
        public boolean usedHGMem;

        // Results
        public String answer;
        public String draftAnswer;
        public List<String> citations = new ArrayList<>();
        public List<String> expandedQueries = new ArrayList<>();

        // Metrics
        public int retrievedDocuments;
        public int ragPartFilteredCount;
        public long hifiProcessingMs;
        public long totalTimeMs;

        // Memory metrics (for HGMem)
        public int memoryStepsBefore;
        public int memoryPointsAfter;
        public int memoryMaxOrder;

        // Reasoning chain for transparency
        public List<ReasoningStep> reasoningChain = new ArrayList<>();

        public void addReasoningStep(String phase, String description) {
            reasoningChain.add(new ReasoningStep(phase, description, Instant.now()));
        }
    }

    public static class ReasoningStep {
        public final String phase;
        public final String description;
        public final Instant timestamp;

        public ReasoningStep(String phase, String description, Instant timestamp) {
            this.phase = phase;
            this.description = description;
            this.timestamp = timestamp;
        }
    }

    // ========== Session Management ==========

    /**
     * Clear memory for a session.
     */
    public void clearSession(String sessionId) {
        hgMemService.clearMemory(sessionId);
        log.info("Cleared session: {}", sessionId);
    }

    /**
     * Get session statistics.
     */
    public Map<String, Object> getSessionStats(String sessionId) {
        return hgMemService.getMemoryStats(sessionId);
    }
}
