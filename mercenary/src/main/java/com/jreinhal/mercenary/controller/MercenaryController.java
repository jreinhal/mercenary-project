package com.jreinhal.mercenary.controller;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.UserRole;
import com.jreinhal.mercenary.service.SecureIngestionService;
import com.jreinhal.mercenary.service.AuditService;
import com.jreinhal.mercenary.service.QueryDecompositionService;
import com.jreinhal.mercenary.filter.SecurityContext;
import com.jreinhal.mercenary.reasoning.ReasoningTrace;
import com.jreinhal.mercenary.reasoning.ReasoningTracer;
import com.jreinhal.mercenary.reasoning.ReasoningStep.StepType;
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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
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
    private final QueryDecompositionService queryDecompositionService;
    private final ReasoningTracer reasoningTracer;

    // METRICS
    private final AtomicInteger docCount = new AtomicInteger(0);
    private final AtomicInteger queryCount = new AtomicInteger(0);
    private final AtomicLong totalLatencyMs = new AtomicLong(0);

    // FLASH CACHE - Bounded to prevent OOM (Security Fix: DoS mitigation)
    private static final int MAX_CACHE_ENTRIES = 100;
    private static final Duration CACHE_TTL = Duration.ofHours(1);
    private final Cache<String, String> secureDocCache = Caffeine.newBuilder()
            .maximumSize(MAX_CACHE_ENTRIES)
            .expireAfterWrite(CACHE_TTL)
            .build();

    // PROMPT INJECTION DETECTION - Defense-in-depth patterns
    // Note: This is a heuristic layer, not a complete solution. Consider model-based guardrails for production.
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            // Direct instruction overrides
            Pattern.compile("ignore\\s+(all\\s+)?(previous|prior|above)\\s+(instructions?|prompts?|rules?)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("disregard\\s+(all\\s+)?(previous|prior|above)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("forget\\s+(all\\s+)?(previous|prior|your)\\s+(instructions?|context|rules?)", Pattern.CASE_INSENSITIVE),
            // System prompt extraction
            Pattern.compile("(show|reveal|display|print|output)\\s+(me\\s+)?(the\\s+)?(system|initial)\\s+prompt", Pattern.CASE_INSENSITIVE),
            Pattern.compile("what\\s+(is|are)\\s+your\\s+(system\\s+)?(instructions?|rules?|prompt)", Pattern.CASE_INSENSITIVE),
            // Role manipulation
            Pattern.compile("you\\s+are\\s+now\\s+(a|an|in)\\s+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("act\\s+as\\s+(if|though)\\s+you", Pattern.CASE_INSENSITIVE),
            Pattern.compile("pretend\\s+(to\\s+be|you\\s+are)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("roleplay\\s+as", Pattern.CASE_INSENSITIVE),
            // Jailbreak patterns
            Pattern.compile("\\bDAN\\b.*mode", Pattern.CASE_INSENSITIVE),
            Pattern.compile("developer\\s+mode\\s+(enabled|on|activated)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("bypass\\s+(your\\s+)?(safety|security|restrictions?|filters?)", Pattern.CASE_INSENSITIVE),
            // Delimiter attacks
            Pattern.compile("```\\s*(system|assistant)\\s*:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\[INST\\]|\\[/INST\\]|<<SYS>>|<</SYS>>", Pattern.CASE_INSENSITIVE),
            // Output manipulation
            Pattern.compile("(start|begin)\\s+(your\\s+)?response\\s+with", Pattern.CASE_INSENSITIVE)
    );

    public MercenaryController(ChatClient.Builder builder, VectorStore vectorStore,
            SecureIngestionService ingestionService, MongoTemplate mongoTemplate,
            AuditService auditService,
            QueryDecompositionService queryDecompositionService, ReasoningTracer reasoningTracer) {
        this.chatClient = builder.build();
        this.vectorStore = vectorStore;
        this.ingestionService = ingestionService;
        this.mongoTemplate = mongoTemplate;
        this.auditService = auditService;
        this.queryDecompositionService = queryDecompositionService;
        this.reasoningTracer = reasoningTracer;

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

        // Normalize filename - strip common prefixes that LLMs sometimes add
        String normalizedFileName = fileName
                .replaceFirst("(?i)^filename:\\s*", "")
                .replaceFirst("(?i)^source:\\s*", "")
                .replaceFirst("(?i)^citation:\\s*", "")
                .trim();

        // 1. Retrieve Content (Cache or Vector Store)
        String cachedContent = secureDocCache.getIfPresent(normalizedFileName);
        if (cachedContent != null) {
            content = "--- SECURE DOCUMENT VIEWER (CACHE) ---\nFILE: " + fileName
                    + "\nSTATUS: DECRYPTED [RAM]\n----------------------------------\n\n"
                    + cachedContent;
        } else {
            try {
                // FALLBACK: Manual filtering if VectorStore doesn't support metadata filters
                // 1. Retrieve a broader set of documents using the normalized filename as context
                List<Document> potentialDocs = vectorStore.similaritySearch(
                        SearchRequest.query(normalizedFileName).withTopK(20));

                log.info("INSPECT DEBUG: Searching for '{}'. Found {} potential candidates.", normalizedFileName,
                        potentialDocs.size());
                potentialDocs.forEach(d -> log.info("  >> Candidate Meta: {}", d.getMetadata()));

                // 2. Filter in memory for exact source match (using normalized filename)
                Optional<Document> match = potentialDocs.stream()
                        .filter(doc -> normalizedFileName.equals(doc.getMetadata().get("source")) ||
                                apiKeyMatch(normalizedFileName, doc.getMetadata()))
                        .findFirst();

                if (match.isPresent()) {
                    String recoveredContent = match.get().getContent();
                    secureDocCache.put(normalizedFileName, recoveredContent);
                    content = "--- SECURE DOCUMENT VIEWER (ARCHIVE) ---\nFILE: " + normalizedFileName
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
            String status = "COMPLETE";

            // 1. Try Persistent Ingestion (Vector Store)
            try {
                ingestionService.ingest(file, department);
            } catch (Exception e) {
                log.warn("Persistence Failed (DB Offline). Proceeding with RAM Cache.", e);
                status = "Warning: RAM ONLY (DB Unreachable)";
            }

            // 2. ALWAYS Update In-Memory Cache (Gold Master Failover)
            String rawContent = new String(file.getBytes(), StandardCharsets.UTF_8);
            secureDocCache.put(filename, rawContent);
            docCount.incrementAndGet();
            long duration = System.currentTimeMillis() - startTime;

            // Audit log successful ingestion
            if (user != null) {
                auditService.logIngestion(user, filename, department, request);
            }

            return "SECURE INGESTION " + status + ": " + filename + " (" + duration + "ms)";
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
                List<Document> subResults = performHybridReranking(subQuery, dept);
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

            String response;
            try {
                // Warning suppression for deprecated call
                @SuppressWarnings("deprecation")
                String rawResponse = chatClient.call(prompt).getResult().getOutput().getContent();
                response = rawResponse;
            } catch (Exception llmError) {
                log.error("LLM Generation Failed (Offline/Misconfigured). Generating Simulation Response.", llmError);
                // Fallback: Construct a response from the retrieved documents directly
                StringBuilder sim = new StringBuilder("**System Offline Mode (LLM Unreachable)**\n\n");
                sim.append("Based on the retrieved intelligence:\n\n");

                for (Document d : topDocs) {
                    String src = (String) d.getMetadata().getOrDefault("source", "Unknown");
                    String preview = d.getContent().substring(0, Math.min(200, d.getContent().length())).replace("\n",
                            " ");
                    sim.append("- ").append(preview).append("... [").append(src).append("]\n");
                }
                if (topDocs.isEmpty()) {
                    sim.append("No relevant records found in the active context.");
                }
                response = sim.toString();
            }

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
     * Enhanced ask response with Glass Box reasoning trace.
     */
    public record EnhancedAskResponse(
            String answer,
            List<Map<String, Object>> reasoning,
            List<String> sources,
            Map<String, Object> metrics,
            String traceId
    ) {}

    /**
     * Enhanced /ask endpoint with full Glass Box reasoning transparency.
     * Returns the answer plus a detailed trace of each reasoning step.
     */
    @GetMapping("/ask/enhanced")
    public EnhancedAskResponse askEnhanced(@RequestParam("q") String query, @RequestParam("dept") String dept,
            HttpServletRequest request) {
        User user = SecurityContext.getCurrentUser();
        Department department;

        try {
            department = Department.valueOf(dept.toUpperCase());
        } catch (IllegalArgumentException e) {
            return new EnhancedAskResponse("INVALID SECTOR: " + dept, List.of(), List.of(), Map.of(), null);
        }

        // Security checks
        if (user == null) {
            auditService.logAccessDenied(null, "/api/ask/enhanced", "Unauthenticated access attempt", request);
            return new EnhancedAskResponse("ACCESS DENIED: Authentication required.", List.of(), List.of(), Map.of(), null);
        }

        if (!user.hasPermission(UserRole.Permission.QUERY)) {
            auditService.logAccessDenied(user, "/api/ask/enhanced", "Missing QUERY permission", request);
            return new EnhancedAskResponse("ACCESS DENIED: Insufficient permissions.", List.of(), List.of(), Map.of(), null);
        }

        if (!user.canAccessClassification(department.getRequiredClearance())) {
            auditService.logAccessDenied(user, "/api/ask/enhanced",
                    "Insufficient clearance for " + department.name(), request);
            return new EnhancedAskResponse("ACCESS DENIED: Insufficient clearance.", List.of(), List.of(), Map.of(), null);
        }

        // Start reasoning trace
        ReasoningTrace trace = reasoningTracer.startTrace(query, dept);

        try {
            long start = System.currentTimeMillis();

            // Step 1: Query Analysis & Injection Detection
            long stepStart = System.currentTimeMillis();
            if (isPromptInjection(query)) {
                reasoningTracer.addStep(StepType.SECURITY_CHECK, "Security Scan",
                        "BLOCKED: Prompt injection detected", System.currentTimeMillis() - stepStart,
                        Map.of("blocked", true, "reason", "injection_detected"));
                auditService.logPromptInjection(user, query, request);
                ReasoningTrace completed = reasoningTracer.endTrace();
                return new EnhancedAskResponse("SECURITY ALERT: Indirect Prompt Injection Detected.",
                        completed != null ? completed.getStepsAsMaps() : List.of(), List.of(), Map.of(),
                        completed != null ? completed.getTraceId() : null);
            }
            reasoningTracer.addStep(StepType.SECURITY_CHECK, "Security Scan",
                    "Query passed injection detection", System.currentTimeMillis() - stepStart,
                    Map.of("blocked", false));

            // Step 2: Query Decomposition
            stepStart = System.currentTimeMillis();
            List<String> subQueries = queryDecompositionService.decompose(query);
            boolean isCompoundQuery = subQueries.size() > 1;
            reasoningTracer.addStep(StepType.QUERY_DECOMPOSITION, "Query Analysis",
                    isCompoundQuery ? "Decomposed into " + subQueries.size() + " sub-queries" : "Single query detected",
                    System.currentTimeMillis() - stepStart,
                    Map.of("subQueries", subQueries, "isCompound", isCompoundQuery));

            // Step 3: Document Retrieval (per sub-query)
            Set<Document> allDocs = new LinkedHashSet<>();
            for (int i = 0; i < subQueries.size(); i++) {
                String subQuery = subQueries.get(i);
                stepStart = System.currentTimeMillis();
                List<Document> subResults = performHybridRerankingTracked(subQuery, dept);
                List<Document> limited = subResults.stream().limit(5).toList();
                allDocs.addAll(limited);

                reasoningTracer.addStep(StepType.VECTOR_SEARCH, "Vector Search" + (isCompoundQuery ? " [" + (i+1) + "/" + subQueries.size() + "]" : ""),
                        "Found " + subResults.size() + " candidates, kept " + limited.size(),
                        System.currentTimeMillis() - stepStart,
                        Map.of("query", subQuery, "candidateCount", subResults.size(), "keptCount", limited.size()));
            }
            List<Document> rawDocs = new ArrayList<>(allDocs);

            // Step 4: Reranking/Filtering
            stepStart = System.currentTimeMillis();
            List<Document> topDocs = rawDocs.stream().limit(15).toList();
            List<String> docSources = topDocs.stream()
                    .map(doc -> {
                        Object src = doc.getMetadata().get("source");
                        return src != null ? src.toString() : "unknown";
                    })
                    .distinct()
                    .toList();
            reasoningTracer.addStep(StepType.RERANKING, "Document Filtering",
                    "Selected top " + topDocs.size() + " documents from " + rawDocs.size() + " candidates",
                    System.currentTimeMillis() - stepStart,
                    Map.of("totalCandidates", rawDocs.size(), "selected", topDocs.size(), "sources", docSources));

            // Step 5: Context Assembly
            stepStart = System.currentTimeMillis();
            String information = topDocs.stream()
                    .map(doc -> {
                        Map<String, Object> meta = doc.getMetadata();
                        String filename = (String) meta.get("source");
                        if (filename == null) filename = (String) meta.get("filename");
                        if (filename == null) filename = "Unknown_Document.txt";
                        return "SOURCE: " + filename + "\nCONTENT: " + doc.getContent();
                    })
                    .collect(Collectors.joining("\n\n---\n\n"));
            int contextLength = information.length();
            reasoningTracer.addStep(StepType.CONTEXT_ASSEMBLY, "Context Assembly",
                    "Assembled " + contextLength + " characters from " + topDocs.size() + " documents",
                    System.currentTimeMillis() - stepStart,
                    Map.of("contextLength", contextLength, "documentCount", topDocs.size()));

            // Step 6: LLM Generation
            stepStart = System.currentTimeMillis();
            String systemText;
            if (information.isEmpty()) {
                systemText = "You are SENTINEL. Protocol: Answer based on general training. State 'No internal records found.'";
            } else {
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

            String response;
            boolean llmSuccess = true;
            try {
                @SuppressWarnings("deprecation")
                String rawResponse = chatClient.call(prompt).getResult().getOutput().getContent();
                response = rawResponse;
            } catch (Exception llmError) {
                llmSuccess = false;
                log.error("LLM Generation Failed", llmError);
                StringBuilder sim = new StringBuilder("**System Offline Mode (LLM Unreachable)**\n\n");
                sim.append("Based on the retrieved intelligence:\n\n");
                for (Document d : topDocs) {
                    String src = (String) d.getMetadata().getOrDefault("source", "Unknown");
                    String preview = d.getContent().substring(0, Math.min(200, d.getContent().length())).replace("\n", " ");
                    sim.append("- ").append(preview).append("... [").append(src).append("]\n");
                }
                if (topDocs.isEmpty()) {
                    sim.append("No relevant records found in the active context.");
                }
                response = sim.toString();
            }
            reasoningTracer.addStep(StepType.LLM_GENERATION, "Response Synthesis",
                    llmSuccess ? "Generated response (" + response.length() + " chars)" : "Fallback mode (LLM offline)",
                    System.currentTimeMillis() - stepStart,
                    Map.of("success", llmSuccess, "responseLength", response.length()));

            // Complete trace
            long timeTaken = System.currentTimeMillis() - start;
            totalLatencyMs.addAndGet(timeTaken);
            queryCount.incrementAndGet();

            reasoningTracer.addMetric("totalLatencyMs", timeTaken);
            reasoningTracer.addMetric("documentsRetrieved", topDocs.size());
            reasoningTracer.addMetric("subQueriesProcessed", subQueries.size());

            ReasoningTrace completedTrace = reasoningTracer.endTrace();

            // Audit log
            auditService.logQuery(user, query, department, response, request);

            // Extract sources from response - support both [filename] and (filename) formats
            List<String> sources = new ArrayList<>();
            // Pattern 1: [filename.ext] or [Citation: filename.ext] - standard format
            java.util.regex.Matcher matcher1 = java.util.regex.Pattern.compile("\\[(?:Citation:\\s*)?([^\\]]+\\.(pdf|txt|md))\\]", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(response);
            while (matcher1.find()) {
                String source = matcher1.group(1).trim();
                if (!sources.contains(source)) {
                    sources.add(source);
                }
            }
            // Pattern 2: (filename.ext) - LLM sometimes uses parentheses
            java.util.regex.Matcher matcher2 = java.util.regex.Pattern.compile("\\(([^)]+\\.(pdf|txt|md))\\)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(response);
            while (matcher2.find()) {
                String source = matcher2.group(1);
                if (!sources.contains(source)) {
                    sources.add(source);
                }
            }
            // Pattern 3: "Citation: filename.ext" or "Source: filename.ext"
            java.util.regex.Matcher matcher3 = java.util.regex.Pattern.compile("(?:Citation|Source|filename):\\s*([^\\s,]+\\.(pdf|txt|md))", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(response);
            while (matcher3.find()) {
                String source = matcher3.group(1);
                if (!sources.contains(source)) {
                    sources.add(source);
                }
            }

            // FALLBACK: If LLM didn't cite sources, use the retrieved document sources
            // This ensures users always see which documents were consulted
            if (sources.isEmpty() && !docSources.isEmpty()) {
                sources.addAll(docSources);
                log.info("LLM response lacked citations - using retrieved document sources: {}", docSources);
            }

            return new EnhancedAskResponse(
                    response,
                    completedTrace != null ? completedTrace.getStepsAsMaps() : List.of(),
                    sources,
                    Map.of(
                            "latencyMs", timeTaken,
                            "documentsRetrieved", topDocs.size(),
                            "subQueries", subQueries.size(),
                            "llmSuccess", llmSuccess
                    ),
                    completedTrace != null ? completedTrace.getTraceId() : null
            );

        } catch (Exception e) {
            log.error("Error in /ask/enhanced endpoint", e);
            ReasoningTrace errorTrace = reasoningTracer.endTrace();
            return new EnhancedAskResponse("Error: " + e.getMessage(),
                    errorTrace != null ? errorTrace.getStepsAsMaps() : List.of(), List.of(),
                    Map.of("error", e.getMessage()),
                    errorTrace != null ? errorTrace.getTraceId() : null);
        }
    }

    /**
     * Retrieve a reasoning trace by ID for debugging/audit.
     */
    @GetMapping("/reasoning/{traceId}")
    public Map<String, Object> getReasoningTrace(@PathVariable String traceId) {
        ReasoningTrace trace = reasoningTracer.getTrace(traceId);
        if (trace == null) {
            return Map.of("error", "Trace not found", "traceId", traceId);
        }
        return trace.toMap();
    }

    /**
     * Hybrid retrieval with tracing support.
     */
    private List<Document> performHybridRerankingTracked(String query, String dept) {
        return performHybridReranking(query, dept);
    }

    /**
     * Implements "HiFi-RAG" logic:
     * 1. Retrieval (Recall) via Vector Search
     * 2. Filtering (Precision) via Keyword Density
     * reduces hallucination by discarding low-confidence vectors.
     */
    private List<Document> performHybridReranking(String query, String dept) {
        List<Document> semanticResults = new ArrayList<>();
        List<Document> keywordResults = new ArrayList<>();

        try {
            // Step A: Semantic vector search with enterprise-tuned threshold
            // 0.15 prioritizes RECALL for enterprise (gov, legal, finance) - captures more
            // natural language variations
            semanticResults = vectorStore.similaritySearch(
                    SearchRequest.query(query)
                            .withTopK(10)
                            .withSimilarityThreshold(0.15)
                            .withFilterExpression("dept == '" + dept + "'"));

            log.info("Semantic search found {} results for '{}'", semanticResults.size(), query);

            // Step B: Keyword fallback (ALWAYS RUN to ensure high recall)
            // This acts as a safety net for terms that semantics might miss
            String lowerQuery = query.toLowerCase();
            keywordResults = vectorStore.similaritySearch(
                    SearchRequest.query(query)
                            .withTopK(50) // Increase candidate pool
                            .withSimilarityThreshold(0.01) // Effectively ignore similarity, just get top matches
                            .withFilterExpression("dept == '" + dept + "'"));

            log.info("Keyword fallback found {} documents for '{}'", keywordResults.size(), query);

            // Filter to documents where source name or content contains query terms
            // Exclude common stop words to prevent noise
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
                            if (term.length() >= 3 && !stopWords.contains(term)) {
                                if (source.contains(term) || content.contains(term)) {
                                    return true;
                                }
                            }
                        }
                        return false;
                    })
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("Vector/Keyword search FAILED (DB/Embedding Offline). Engaging RAM Cache Fallback.", e);
            return searchInMemoryCache(query);
        }

        // Step C: Merge results (Semantics first, then keywords)
        Set<Document> merged = new LinkedHashSet<>(semanticResults);
        merged.addAll(keywordResults);

        // Step D: GOLD MASTER FAILOVER (In-Memory Cache)
        if (merged.isEmpty()) {
            log.warn("Vector/Keyword search yielded 0 results. Engaging RAM Cache Fallback.");
            return searchInMemoryCache(query);
        }

        return new ArrayList<>(merged);
    }

    /**
     * FALLBACK: Search in-memory cache if vector store returns nothing.
     * Ensures demo stability even if embeddings/DB are unconfigured.
     *
     * Security: Requires at least 30% of query terms to match OR minimum 2 matches
     * to prevent information leakage from overly loose matching.
     */
    private List<Document> searchInMemoryCache(String query) {
        List<Document> results = new ArrayList<>();
        String[] terms = query.toLowerCase().split("\\s+");
        // Filter to meaningful terms (length > 3)
        String[] meaningfulTerms = Arrays.stream(terms)
                .filter(t -> t.length() > 3)
                .toArray(String[]::new);

        // Minimum matches required: at least 30% of terms OR minimum 2, whichever is higher
        int minMatchesRequired = Math.max(2, (int) Math.ceil(meaningfulTerms.length * 0.3));

        for (Map.Entry<String, String> entry : secureDocCache.asMap().entrySet()) {
            String filename = entry.getKey();
            String content = entry.getValue();
            String lowerContent = content.toLowerCase();

            long matches = Arrays.stream(meaningfulTerms)
                    .filter(lowerContent::contains).count();

            // Require minimum threshold OR exact filename match
            boolean filenameMatch = Arrays.stream(terms).anyMatch(t -> filename.toLowerCase().contains(t));
            if (matches >= minMatchesRequired || filenameMatch) {
                Document doc = new Document(content);
                doc.getMetadata().put("source", filename);
                doc.getMetadata().put("filename", filename);
                results.add(doc);
            }
        }
        return results;
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

    /**
     * Detect potential prompt injection attacks using pattern matching.
     *
     * NOTE: This is a defense-in-depth heuristic layer, not a complete solution.
     * Sophisticated attacks may bypass pattern matching. For high-security deployments,
     * consider integrating a model-based guardrail service (e.g., Rebuff, LLM Guard, NeMo Guardrails).
     *
     * @param query The user's input query
     * @return true if potential injection detected, false otherwise
     */
    private boolean isPromptInjection(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        return INJECTION_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(query).find());
    }
}