package com.jreinhal.mercenary.controller;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.UserRole;
import com.jreinhal.mercenary.service.SecureIngestionService;
import com.jreinhal.mercenary.service.AuditService;
import com.jreinhal.mercenary.service.AuthenticationService;
import com.jreinhal.mercenary.service.QueryDecompositionService;
import com.jreinhal.mercenary.filter.SecurityContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class MercenaryController {

    private static final Logger log = LoggerFactory.getLogger(MercenaryController.class);

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final SecureIngestionService ingestionService;
    private final MongoTemplate mongoTemplate;
    private final AuditService auditService;
    private final AuthenticationService authService;
    private final QueryDecompositionService queryDecompositionService;

    // METRICS
    private final AtomicInteger docCount = new AtomicInteger(0);
    private final AtomicInteger queryCount = new AtomicInteger(0);
    private final AtomicLong totalLatencyMs = new AtomicLong(0);

    // FLASH CACHE
    private final Map<String, String> secureDocCache = new ConcurrentHashMap<>();

    public MercenaryController(ChatClient.Builder builder, VectorStore vectorStore,
            SecureIngestionService ingestionService, MongoTemplate mongoTemplate,
            AuditService auditService, AuthenticationService authService,
            QueryDecompositionService queryDecompositionService) {
        this.chatClient = builder.build();
        this.vectorStore = vectorStore;
        this.ingestionService = ingestionService;
        this.mongoTemplate = mongoTemplate;
        this.auditService = auditService;
        this.authService = authService;
        this.queryDecompositionService = queryDecompositionService;

        // Initialize doc count from DB
        try {
            long count = mongoTemplate.getCollection("vector_store").countDocuments();
            docCount.set((int) count);
        } catch (Exception e) {
            log.error("Failed to initialize doc count", e);
        }
    }

    @GetMapping("/status")
    public Map<String, Object> getSystemStatus() {
        long avgLat = 0;
        int qCount = queryCount.get();
        if (qCount > 0)
            avgLat = totalLatencyMs.get() / qCount;
        boolean dbOnline = true;
        try {
            SearchRequest.query("ping");
        } catch (Exception e) {
            dbOnline = false;
        }
        return Map.of("vectorDb", dbOnline ? "ONLINE" : "OFFLINE", "docsIndexed", docCount.get(), "avgLatency",
                avgLat + "ms", "queriesToday", qCount, "systemStatus", "NOMINAL");
    }

    public record TelemetryResponse(int documentCount, int queryCount, long avgLatencyMs, boolean dbOnline) {
    }

    @GetMapping("/telemetry")
    public TelemetryResponse getTelemetry() {
        long avgLat = 0;
        int qCount = queryCount.get();
        if (qCount > 0)
            avgLat = totalLatencyMs.get() / qCount;

        boolean dbOnline = true;
        long liveDocCount = 0;
        try {
            // Fetch live count directly from DB to avoid drift
            liveDocCount = mongoTemplate.getCollection("vector_store").countDocuments();
        } catch (Exception e) {
            dbOnline = false;
        }

        return new TelemetryResponse((int) liveDocCount, qCount, avgLat, dbOnline);
    }

    public record InspectResponse(String content, List<String> highlights) {
    }

    @GetMapping("/inspect")
    public InspectResponse inspectDocument(@RequestParam("fileName") String fileName,
            @RequestParam(value = "query", required = false) String query) {
        String content = "";

        // 1. Retrieve Content (Cache or Vector Store)
        if (secureDocCache.containsKey(fileName)) {
            content = "--- SECURE DOCUMENT VIEWER (CACHE) ---\nFILE: " + fileName
                    + "\nSTATUS: DECRYPTED [RAM]\n----------------------------------\n\n"
                    + secureDocCache.get(fileName);
        } else {
            try {
                // FALLBACK: Manual filtering if VectorStore doesn't support metadata filters
                // 1. Retrieve a broader set of documents using the filename as context
                List<Document> potentialDocs = vectorStore.similaritySearch(
                        SearchRequest.query(fileName).withTopK(20));

                log.info("INSPECT DEBUG: Searching for '{}'. Found {} potential candidates.", fileName,
                        potentialDocs.size());
                potentialDocs.forEach(d -> log.info("  >> Candidate Meta: {}", d.getMetadata()));

                // 2. Filter in memory for exact source match
                Optional<Document> match = potentialDocs.stream()
                        .filter(doc -> fileName.equals(doc.getMetadata().get("source")) ||
                                apiKeyMatch(fileName, doc.getMetadata()))
                        .findFirst();

                if (match.isPresent()) {
                    String recoveredContent = match.get().getContent();
                    secureDocCache.put(fileName, recoveredContent);
                    content = "--- SECURE DOCUMENT VIEWER (ARCHIVE) ---\nFILE: " + fileName
                            + "\nSTATUS: RECONSTRUCTED FROM VECTOR STORE\n----------------------------------\n\n"
                            + recoveredContent;
                } else {
                    return new InspectResponse(
                            "ERROR: Document archived in Deep Storage.\nPlease re-ingest file to refresh active cache.",
                            List.of());
                }
            } catch (Exception e) {
                log.error(">> RECOVERY FAILED: {}", e.getMessage());
                return new InspectResponse("ERROR: Recovery failed. " + e.getMessage(), List.of());
            }
        }

        // 2. Identify Highlights (Simulated Relevance)
        List<String> highlights = new ArrayList<>();
        if (query != null && !query.isEmpty() && !content.isEmpty()) {
            String[] keywords = query.toLowerCase().split("\\s+");
            // Simple heuristic directly in controller for demo purposes
            // In production, this would use the vector store's relevance scores
            String[] paragraphs = content.split("\n\n");
            for (String p : paragraphs) {
                if (p.startsWith("---"))
                    continue; // Skip header
                int matches = 0;
                String lowerP = p.toLowerCase();
                for (String k : keywords) {
                    if (lowerP.contains(k) && k.length() > 3)
                        matches++;
                }
                // If reasonably relevant, add to highlights
                if (matches >= 1) {
                    highlights.add(p.trim());
                    // Limit to top 3 highlights to avoid visual clutter
                    if (highlights.size() >= 3)
                        break;
                }
            }
        }

        return new InspectResponse(content, highlights);
    }

    @GetMapping("/health")
    public String health() {
        return "SYSTEMS NOMINAL";
    }

    @PostMapping("/ingest/file")
    public String ingestFile(@RequestParam("file") MultipartFile file, @RequestParam("dept") String dept,
            HttpServletRequest request) {
        User user = SecurityContext.getCurrentUser();
        Department department;

        try {
            department = Department.valueOf(dept.toUpperCase());
        } catch (IllegalArgumentException e) {
            return "INVALID SECTOR: " + dept;
        }

        // HARDENED SECURITY: Explicitly reject unauthenticated users
        if (user == null) {
            auditService.logAccessDenied(null, "/api/ingest/file", "Unauthenticated access attempt", request);
            return "ACCESS DENIED: Authentication required.";
        }

        // RBAC Check: User must have INGEST permission
        if (!user.hasPermission(UserRole.Permission.INGEST)) {
            auditService.logAccessDenied(user, "/api/ingest/file", "Missing INGEST permission", request);
            return "ACCESS DENIED: Insufficient permissions for document ingestion.";
        }

        // RBAC Check: User must have clearance for this sector
        if (!user.canAccessClassification(department.getRequiredClearance())) {
            auditService.logAccessDenied(user, "/api/ingest/file",
                    "Insufficient clearance for " + department.name(), request);
            return "ACCESS DENIED: Insufficient clearance for " + department.name() + " sector.";
        }

        try {
            long startTime = System.currentTimeMillis();
            String filename = file.getOriginalFilename();
            ingestionService.ingest(file, department);
            String rawContent = new String(file.getBytes(), StandardCharsets.UTF_8);
            secureDocCache.put(filename, rawContent);
            docCount.incrementAndGet();
            long duration = System.currentTimeMillis() - startTime;

            // Audit log successful ingestion
            if (user != null) {
                auditService.logIngestion(user, filename, department, request);
            }

            return "SECURE INGESTION COMPLETE: " + filename + " (" + duration + "ms)";
        } catch (Exception e) {
            log.error("CRITICAL FAILURE: Ingestion Protocol Failed.", e);
            return "CRITICAL FAILURE: Ingestion Protocol Failed.";
        }
    }

    @GetMapping("/ask")
    public String ask(@RequestParam("q") String query, @RequestParam("dept") String dept,
            HttpServletRequest request) {
        User user = SecurityContext.getCurrentUser();
        Department department;

        try {
            department = Department.valueOf(dept.toUpperCase());
        } catch (IllegalArgumentException e) {
            return "INVALID SECTOR: " + dept;
        }

        // HARDENED SECURITY: Explicitly reject unauthenticated users
        if (user == null) {
            auditService.logAccessDenied(null, "/api/ask", "Unauthenticated access attempt", request);
            return "ACCESS DENIED: Authentication required.";
        }

        // RBAC Check: User must have QUERY permission
        if (!user.hasPermission(UserRole.Permission.QUERY)) {
            auditService.logAccessDenied(user, "/api/ask", "Missing QUERY permission", request);
            return "ACCESS DENIED: Insufficient permissions for queries.";
        }

        // RBAC Check: User must have clearance for this sector
        if (!user.canAccessClassification(department.getRequiredClearance())) {
            auditService.logAccessDenied(user, "/api/ask",
                    "Insufficient clearance for " + department.name(), request);
            return "ACCESS DENIED: Insufficient clearance for " + department.name() + " sector.";
        }

        try {
            long start = System.currentTimeMillis();

            // 1. SECURITY - Prompt injection detection
            if (isPromptInjection(query)) {
                if (user != null) {
                    auditService.logPromptInjection(user, query, request);
                }
                return "SECURITY ALERT: Indirect Prompt Injection Detected. Access Denied.";
            }

            // 1.5 MULTI-QUERY DECOMPOSITION (Enterprise Feature)
            // Detect and handle compound queries like "What is X and what is Y"
            List<String> subQueries = queryDecompositionService.decompose(query);
            boolean isCompoundQuery = subQueries.size() > 1;
            if (isCompoundQuery) {
                log.info("Compound query detected. Decomposed into {} sub-queries.", subQueries.size());
            }

            // Collect documents from all sub-queries
            Set<Document> allDocs = new LinkedHashSet<>();
            for (String subQuery : subQueries) {
                List<Document> subResults = executeHybridSearch(subQuery, dept);
                // Limit contribution from each sub-query to prevent flooding
                allDocs.addAll(subResults.stream().limit(5).toList());
            }
            List<Document> rawDocs = new ArrayList<>(allDocs);

            log.info("Retrieved {} documents for query: {}", rawDocs.size(), query);
            rawDocs.forEach(doc -> log.info("  - Source: {}, Content preview: {}",
                    doc.getMetadata().get("source"),
                    doc.getContent().substring(0, Math.min(50, doc.getContent().length()))));

            // Semantic vector search handles relevance better than naive reranking
            // Increased limit to 15 to support multi-query decomposition context
            List<Document> topDocs = rawDocs.stream().limit(15).toList();

            String information = topDocs.stream()
                    .map(doc -> {
                        Map<String, Object> meta = doc.getMetadata();
                        String filename = (String) meta.get("source");
                        if (filename == null)
                            filename = (String) meta.get("filename");
                        if (filename == null)
                            filename = "Unknown_Document.txt";
                        return "SOURCE: " + filename + "\nCONTENT: " + doc.getContent();
                    })
                    .collect(Collectors.joining("\n\n---\n\n"));

            // 3. GENERATION (Strict Formatting)
            String systemText = "";
            if (information.isEmpty()) {
                systemText = "You are SENTINEL. Protocol: Answer based on general training. State 'No internal records found.'";
            } else {
                // STRICT CITATION PROMPT WITH PER-FACT ATTRIBUTION
                systemText = """
                        You are SENTINEL, an advanced intelligence agent assigned to %s.

                        PROTOCOL:
                        1. ANALYZE the provided CONTEXT DATA carefully.
                        2. SYNTHESIZE an answer based ONLY on that data.
                        3. CITE each source IMMEDIATELY after EACH fact from that source.

                        === CRITICAL: INDIVIDUAL CITATIONS REQUIRED ===

                        You MUST include a citation [filename] for EVERY fact you state from the documents.
                        Your response MUST contain at least one citation if you used the context.

                        === CITATION FORMAT ===
                        Use brackets with the full filename: [filename.ext]

                        === EXAMPLES ===
                        "The asset was located in Sector 7 [mission_log.txt]."

                        === IMPORTANT: ANTI-HALLUCINATION ===
                        1. DO NOT fabricate filenames.
                        2. ONLY cite files listed in "SOURCE:" sections below.
                        3. If you do not know the answer, say "No records found."

                        DO NOT say "According to the context". Just state the fact and cite it.

                        CONTEXT DATA:
                        {information}
                        """
                        .formatted(dept);

            }

            SystemPromptTemplate systemPrompt = new SystemPromptTemplate(systemText);
            UserMessage userMessage = new UserMessage(query);
            Prompt prompt = new Prompt(
                    List.of(systemPrompt.createMessage(Map.of("information", information)), userMessage));

            String response = chatClient.call(prompt).getResult().getOutput().getContent();

            long timeTaken = System.currentTimeMillis() - start;
            totalLatencyMs.addAndGet(timeTaken);
            queryCount.incrementAndGet();

            // Audit log successful query
            if (user != null) {
                auditService.logQuery(user, query, department, response, request);
            }

            return response;
        } catch (Exception e) {
            log.error("Error in /ask endpoint", e);
            throw e;
        }
    }

    /**
     * HYBRID SEARCH: Combines semantic vector search with keyword fallback.
     * Enterprise-grade retrieval optimized for government, legal, finance, and
     * medical.
     * Prioritizes RECALL over precision - better to retrieve and filter than to
     * miss.
     */
    private List<Document> executeHybridSearch(String query, String dept) {
        // Step A: Semantic vector search with enterprise-tuned threshold
        // 0.15 prioritizes RECALL for enterprise (gov, legal, finance) - captures more
        // natural language variations
        List<Document> semanticResults = vectorStore.similaritySearch(
                SearchRequest.query(query)
                        .withTopK(10)
                        .withSimilarityThreshold(0.15)
                        .withFilterExpression("dept == '" + dept + "'"));

        log.info("Semantic search found {} results for '{}'", semanticResults.size(), query);

        // Step B: Keyword fallback (ALWAYS RUN to ensure high recall)
        // This acts as a safety net for terms that semantics might miss
        String lowerQuery = query.toLowerCase();
        List<Document> keywordResults = vectorStore.similaritySearch(
                SearchRequest.query(query)
                        .withTopK(50) // Increase candidate pool
                        .withSimilarityThreshold(0.01) // Effectively ignore similarity, just get top matches
                        .withFilterExpression("dept == '" + dept + "'"));

        // Filter to documents where source name or content contains query terms
        // Exclude common stop words to prevent noise (matches "the", "and", "tell",
        // etc.)
        Set<String> stopWords = Set.of(
                "the", "and", "for", "was", "are", "is", "of", "to", "in",
                "what", "where", "when", "who", "how", "why",
                "tell", "me", "about", "describe", "find", "show", "give",
                "also");

        String[] queryTerms = lowerQuery.split("\\s+");
        keywordResults = keywordResults.stream()
                .filter(doc -> {
                    String source = String.valueOf(doc.getMetadata().get("source")).toLowerCase();
                    String content = doc.getContent().toLowerCase();
                    for (String term : queryTerms) {
                        // Check length AND stop words
                        if (term.length() >= 3 && !stopWords.contains(term)) {
                            if (source.contains(term) || content.contains(term)) {
                                return true;
                            }
                        }
                    }
                    return false;
                })
                .collect(Collectors.toList());

        log.info("Keyword fallback found {} documents for '{}'", keywordResults.size(), query);

        // Step C: Merge results (Semantics first, then keywords)
        Set<Document> merged = new LinkedHashSet<>(semanticResults);
        merged.addAll(keywordResults);

        return new ArrayList<>(merged);
    }

    private boolean apiKeyMatch(String targetName, Map<String, Object> meta) {
        if (targetName.equals(meta.get("source")))
            return true;
        if (targetName.equals(meta.get("filename")))
            return true;
        if (targetName.equals(meta.get("file_name")))
            return true;
        if (targetName.equals(meta.get("original_filename")))
            return true;
        return false;
    }

    private boolean isPromptInjection(String query) {
        String lower = query.toLowerCase();
        return lower.contains("ignore previous") || lower.contains("ignore all") || lower.contains("system prompt");
    }
}