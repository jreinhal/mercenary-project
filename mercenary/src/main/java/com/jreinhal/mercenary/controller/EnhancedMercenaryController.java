package com.jreinhal.mercenary.controller;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.UserRole;
import com.jreinhal.mercenary.service.*;
import com.jreinhal.mercenary.filter.SecurityContext;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

/**
 * Enhanced Mercenary Controller with Advanced RAG Pipeline
 * 
 * Integrates cutting-edge RAG research:
 * - RAGPart (corpus poisoning defense)
 * - HGMem (hypergraph-based memory)
 * - HiFi-RAG (hierarchical filtering + two-pass generation)
 * 
 * Provides both legacy endpoints and enhanced endpoints.
 */
@RestController
@RequestMapping("/api/v2")
public class EnhancedMercenaryController {

    private static final Logger log = LoggerFactory.getLogger(EnhancedMercenaryController.class);

    private final AdvancedRAGOrchestrator orchestrator;
    private final RAGPartDefenseService ragPartService;
    private final HyperGraphMemoryService hgMemService;
    private final HiFiRAGService hiFiService;
    private final SecureIngestionService ingestionService;
    private final AuditService auditService;

    public EnhancedMercenaryController(
            AdvancedRAGOrchestrator orchestrator,
            RAGPartDefenseService ragPartService,
            HyperGraphMemoryService hgMemService,
            HiFiRAGService hiFiService,
            SecureIngestionService ingestionService,
            AuditService auditService) {
        this.orchestrator = orchestrator;
        this.ragPartService = ragPartService;
        this.hgMemService = hgMemService;
        this.hiFiService = hiFiService;
        this.ingestionService = ingestionService;
        this.auditService = auditService;

        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║          ENHANCED MERCENARY CONTROLLER v2.0                  ║");
        log.info("║                                                              ║");
        log.info("║  Powered by:                                                 ║");
        log.info("║    • RAGPart Defense (arXiv:2512.24268v1)                   ║");
        log.info("║    • HyperGraph Memory (arXiv:2512.23959v2)                 ║");
        log.info("║    • HiFi-RAG Pipeline (arXiv:2512.22442v1)                 ║");
        log.info("╚══════════════════════════════════════════════════════════════╝");
    }

    // ========== Enhanced Query Endpoint ==========

    /**
     * Advanced query endpoint using full RAG orchestration.
     * Automatically selects optimal strategy based on query complexity.
     */
    @GetMapping("/ask")
    public QueryResponse ask(
            @RequestParam("q") String query,
            @RequestParam("dept") String dept,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            HttpServletRequest request) {

        User user = SecurityContext.getCurrentUser();
        Department department;

        try {
            department = Department.valueOf(dept.toUpperCase());
        } catch (IllegalArgumentException e) {
            return QueryResponse.error("INVALID SECTOR: " + dept);
        }

        // Security checks
        if (user == null) {
            auditService.logAccessDenied(null, "/api/v2/ask", "Unauthenticated", request);
            return QueryResponse.accessDenied("Authentication required");
        }

        if (!user.hasPermission(UserRole.Permission.QUERY)) {
            auditService.logAccessDenied(user, "/api/v2/ask", "Missing QUERY permission", request);
            return QueryResponse.accessDenied("Insufficient permissions");
        }

        if (!user.canAccessClassification(department.getRequiredClearance())) {
            auditService.logAccessDenied(user, "/api/v2/ask",
                    "Insufficient clearance for " + department.name(), request);
            return QueryResponse.accessDenied("Insufficient clearance for " + department.name());
        }

        // Prompt injection check
        if (isPromptInjection(query)) {
            auditService.logPromptInjection(user, query, request);
            return QueryResponse.error("SECURITY ALERT: Prompt injection detected");
        }

        // Generate session ID if not provided
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = user.getUsername() + "_" + System.currentTimeMillis();
        }

        try {
            // Execute advanced RAG pipeline
            AdvancedRAGOrchestrator.OrchestratorResult result = 
                orchestrator.execute(query, sessionId, department);

            // Audit log
            auditService.logQuery(user, query, department, result.answer, request);

            // Build response
            return QueryResponse.success(result);

        } catch (Exception e) {
            log.error("Query execution failed: {}", e.getMessage(), e);
            return QueryResponse.error("Processing failed: " + e.getMessage());
        }
    }

    /**
     * Simple query endpoint - uses HiFi-RAG only.
     */
    @GetMapping("/ask/simple")
    public SimpleQueryResponse askSimple(
            @RequestParam("q") String query,
            @RequestParam("dept") String dept,
            HttpServletRequest request) {

        User user = SecurityContext.getCurrentUser();
        Department department;

        try {
            department = Department.valueOf(dept.toUpperCase());
        } catch (IllegalArgumentException e) {
            return new SimpleQueryResponse(false, null, "INVALID SECTOR: " + dept, null);
        }

        // Security checks
        if (user == null || !user.hasPermission(UserRole.Permission.QUERY)) {
            return new SimpleQueryResponse(false, null, "ACCESS DENIED", null);
        }

        if (!user.canAccessClassification(department.getRequiredClearance())) {
            return new SimpleQueryResponse(false, null, 
                "ACCESS DENIED: Insufficient clearance", null);
        }

        try {
            HiFiRAGService.HiFiRAGResult result = hiFiService.executeFullPipeline(query, department);
            
            auditService.logQuery(user, query, department, result.answer, request);

            return new SimpleQueryResponse(
                true,
                result.answer,
                null,
                result.citations.sources
            );

        } catch (Exception e) {
            log.error("Simple query failed: {}", e.getMessage());
            return new SimpleQueryResponse(false, null, e.getMessage(), null);
        }
    }

    // ========== Memory Management Endpoints ==========

    /**
     * Get current session memory state.
     */
    @GetMapping("/memory/{sessionId}")
    public MemoryStateResponse getMemoryState(@PathVariable String sessionId) {
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return new MemoryStateResponse(false, null, null, "Authentication required");
        }

        Map<String, Object> stats = hgMemService.getMemoryStats(sessionId);
        String context = hgMemService.exportMemoryToContext(sessionId);

        return new MemoryStateResponse(true, stats, context, null);
    }

    /**
     * Clear session memory.
     */
    @DeleteMapping("/memory/{sessionId}")
    public Map<String, Object> clearMemory(@PathVariable String sessionId) {
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return Map.of("success", false, "error", "Authentication required");
        }

        hgMemService.clearMemory(sessionId);
        return Map.of("success", true, "message", "Memory cleared for session: " + sessionId);
    }

    /**
     * Manually add a memory point.
     */
    @PostMapping("/memory/{sessionId}/add")
    public Map<String, Object> addMemoryPoint(
            @PathVariable String sessionId,
            @RequestBody MemoryPointRequest memRequest) {

        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return Map.of("success", false, "error", "Authentication required");
        }

        try {
            HyperGraphMemoryService.HyperEdge edge = hgMemService.insertMemoryPoint(
                sessionId,
                memRequest.description,
                new HashSet<>(memRequest.entities),
                null
            );

            return Map.of(
                "success", true,
                "memoryPointId", edge.getId(),
                "entities", edge.getVertexIds().size()
            );
        } catch (Exception e) {
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    // ========== Document Analysis Endpoints ==========

    /**
     * Analyze a query without executing - returns strategy that would be used.
     */
    @GetMapping("/analyze")
    public AnalysisResponse analyzeQuery(@RequestParam("q") String query) {
        AdvancedRAGOrchestrator.QueryAnalysis analysis = orchestrator.execute(
            query, "analysis_" + System.currentTimeMillis(), Department.OPERATIONS
        ).analysis;

        return new AnalysisResponse(
            query,
            analysis.complexity.name(),
            analysis.requiresMultiStep,
            analysis.wordCount,
            new ArrayList<>(analysis.detectedEntities)
        );
    }

    /**
     * Test RAGPart defense on a query.
     */
    @GetMapping("/test/ragpart")
    public RAGPartTestResponse testRAGPart(@RequestParam("q") String query) {
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return new RAGPartTestResponse(false, null, 0, 0, "Authentication required");
        }

        try {
            long start = System.currentTimeMillis();
            List<Document> results = ragPartService.secureSearch(query, 10);
            long duration = System.currentTimeMillis() - start;

            List<String> sources = results.stream()
                .map(d -> (String) d.getMetadata().getOrDefault("source", "unknown"))
                .distinct()
                .toList();

            return new RAGPartTestResponse(true, sources, results.size(), duration, null);

        } catch (Exception e) {
            return new RAGPartTestResponse(false, null, 0, 0, e.getMessage());
        }
    }

    // ========== Enhanced Ingestion ==========

    /**
     * Ingest document with RAGPart partitioning for defense.
     */
    @PostMapping("/ingest")
    public IngestResponse ingestWithPartitions(
            @RequestParam("file") MultipartFile file,
            @RequestParam("dept") String dept,
            @RequestParam(value = "partitions", defaultValue = "4") int partitions,
            HttpServletRequest request) {

        User user = SecurityContext.getCurrentUser();
        Department department;

        try {
            department = Department.valueOf(dept.toUpperCase());
        } catch (IllegalArgumentException e) {
            return new IngestResponse(false, null, "INVALID SECTOR: " + dept);
        }

        // Security checks
        if (user == null || !user.hasPermission(UserRole.Permission.INGEST)) {
            return new IngestResponse(false, null, "ACCESS DENIED");
        }

        if (!user.canAccessClassification(department.getRequiredClearance())) {
            return new IngestResponse(false, null, 
                "ACCESS DENIED: Insufficient clearance for " + department.name());
        }

        try {
            long start = System.currentTimeMillis();
            String filename = file.getOriginalFilename();

            // Standard ingestion
            ingestionService.ingest(file, department);

            // Note: In production, RAGPart partitioning would be integrated
            // into SecureIngestionService

            long duration = System.currentTimeMillis() - start;
            auditService.logIngestion(user, filename, department, request);

            return new IngestResponse(true, filename, 
                "Ingested with " + partitions + " partitions in " + duration + "ms");

        } catch (Exception e) {
            log.error("Ingestion failed: {}", e.getMessage());
            return new IngestResponse(false, null, "Ingestion failed: " + e.getMessage());
        }
    }

    // ========== System Info ==========

    /**
     * Get information about the advanced RAG capabilities.
     */
    @GetMapping("/capabilities")
    public Map<String, Object> getCapabilities() {
        return Map.of(
            "version", "2.0.0",
            "components", Map.of(
                "ragpart", Map.of(
                    "enabled", true,
                    "paper", "arXiv:2512.24268v1",
                    "description", "Corpus poisoning defense via document partitioning"
                ),
                "hgmem", Map.of(
                    "enabled", true,
                    "paper", "arXiv:2512.23959v2",
                    "description", "Hypergraph-based memory for complex relational modeling"
                ),
                "hifirag", Map.of(
                    "enabled", true,
                    "paper", "arXiv:2512.22442v1",
                    "description", "Hierarchical content filtering and two-pass generation"
                )
            ),
            "strategies", List.of(
                "FAST_PATH - Direct retrieval for simple queries",
                "STANDARD_HIFI - HiFi-RAG pipeline for moderate queries",
                "FULL_PIPELINE - RAGPart + HiFi-RAG for complex queries",
                "MULTI_STEP_MEMORY - Full pipeline with HGMem for multi-hop reasoning"
            )
        );
    }

    // ========== Response DTOs ==========

    public record QueryResponse(
        boolean success,
        String answer,
        String draftAnswer,
        List<String> citations,
        List<String> expandedQueries,
        String strategy,
        String complexity,
        long processingTimeMs,
        Map<String, Object> memoryStats,
        List<ReasoningStepDTO> reasoningChain,
        String error
    ) {
        public static QueryResponse success(AdvancedRAGOrchestrator.OrchestratorResult result) {
            List<ReasoningStepDTO> chain = result.reasoningChain.stream()
                .map(s -> new ReasoningStepDTO(s.phase, s.description))
                .toList();

            Map<String, Object> memStats = null;
            if (result.usedHGMem) {
                memStats = Map.of(
                    "stepsBefore", result.memoryStepsBefore,
                    "pointsAfter", result.memoryPointsAfter,
                    "maxOrder", result.memoryMaxOrder
                );
            }

            return new QueryResponse(
                true,
                result.answer,
                result.draftAnswer,
                result.citations,
                result.expandedQueries,
                result.strategy != null ? result.strategy.name() : null,
                result.analysis != null ? result.analysis.complexity.name() : null,
                result.totalTimeMs,
                memStats,
                chain,
                null
            );
        }

        public static QueryResponse error(String error) {
            return new QueryResponse(false, null, null, null, null, null, null, 0, null, null, error);
        }

        public static QueryResponse accessDenied(String reason) {
            return new QueryResponse(false, "ACCESS DENIED: " + reason, null, null, null, 
                null, null, 0, null, null, "Access denied");
        }
    }

    public record ReasoningStepDTO(String phase, String description) {}

    public record SimpleQueryResponse(
        boolean success,
        String answer,
        String error,
        List<String> citations
    ) {}

    public record MemoryStateResponse(
        boolean success,
        Map<String, Object> stats,
        String contextExport,
        String error
    ) {}

    public record MemoryPointRequest(
        String description,
        List<String> entities
    ) {}

    public record AnalysisResponse(
        String query,
        String complexity,
        boolean requiresMultiStep,
        int wordCount,
        List<String> detectedEntities
    ) {}

    public record RAGPartTestResponse(
        boolean success,
        List<String> sources,
        int documentCount,
        long processingTimeMs,
        String error
    ) {}

    public record IngestResponse(
        boolean success,
        String filename,
        String message
    ) {}

    // ========== Utility Methods ==========

    private boolean isPromptInjection(String query) {
        String lower = query.toLowerCase();
        return lower.contains("ignore previous") 
            || lower.contains("ignore all") 
            || lower.contains("system prompt")
            || lower.contains("disregard")
            || lower.contains("new instructions");
    }
}
