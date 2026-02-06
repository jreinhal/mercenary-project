package com.jreinhal.mercenary.controller;

import com.github.benmanes.caffeine.cache.Cache;
import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.config.SectorConfig;
import com.jreinhal.mercenary.core.license.LicenseService;
import com.jreinhal.mercenary.dto.EnhancedAskResponse;
import com.jreinhal.mercenary.filter.SecurityContext;
import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.UserRole;
import com.jreinhal.mercenary.professional.memory.ConversationMemoryService;
import com.jreinhal.mercenary.professional.memory.SessionPersistenceService;
import com.jreinhal.mercenary.rag.ModalityRouter;
import com.jreinhal.mercenary.rag.adaptiverag.AdaptiveRagService;
import com.jreinhal.mercenary.rag.agentic.AgenticRagOrchestrator;
import com.jreinhal.mercenary.rag.birag.BidirectionalRagService;
import com.jreinhal.mercenary.rag.crag.RewriteService;
import com.jreinhal.mercenary.rag.hifirag.HiFiRagService;
import com.jreinhal.mercenary.rag.hgmem.HGMemQueryEngine;
import com.jreinhal.mercenary.rag.hybridrag.HybridRagService;
import com.jreinhal.mercenary.rag.megarag.MegaRagService;
import com.jreinhal.mercenary.rag.miarag.MiARagService;
import com.jreinhal.mercenary.rag.qucorag.QuCoRagService;
import com.jreinhal.mercenary.rag.ragpart.RagPartService;
import com.jreinhal.mercenary.reasoning.ReasoningStep;
import com.jreinhal.mercenary.reasoning.ReasoningTrace;
import com.jreinhal.mercenary.reasoning.ReasoningTracer;
import com.jreinhal.mercenary.service.AuditService;
import com.jreinhal.mercenary.service.PiiRedactionService;
import com.jreinhal.mercenary.service.PromptGuardrailService;
import com.jreinhal.mercenary.service.QueryDecompositionService;
import com.jreinhal.mercenary.service.RagOrchestrationService;
import com.jreinhal.mercenary.service.SecureIngestionService;
import com.jreinhal.mercenary.util.FilterExpressionBuilder;
import com.jreinhal.mercenary.util.LogSanitizer;
import com.jreinhal.mercenary.constant.StopWords;
import com.jreinhal.mercenary.util.DocumentMetadataUtils;
import com.jreinhal.mercenary.workspace.WorkspaceContext;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping(value={"/api"})
public class MercenaryController {
    private static final Logger log = LoggerFactory.getLogger(MercenaryController.class);
    private static final int MAX_ACTIVE_FILES = 25;
    private static final String DOC_SEPARATOR = "\n\n---\n\n";
    private static final Set<String> BOOST_STOP_WORDS = StopWords.QUERY_BOOST;
    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final SecureIngestionService ingestionService;
    private final MongoTemplate mongoTemplate;
    private final AuditService auditService;
    private final QueryDecompositionService queryDecompositionService;
    private final ReasoningTracer reasoningTracer;
    private final QuCoRagService quCoRagService;
    private final AdaptiveRagService adaptiveRagService;
    private final RewriteService rewriteService;
    private final RagPartService ragPartService;
    private final HybridRagService hybridRagService;
    private final HiFiRagService hiFiRagService;
    private final MiARagService miARagService;
    private final MegaRagService megaRagService;
    private final HGMemQueryEngine hgMemQueryEngine;
    private final AgenticRagOrchestrator agenticRagOrchestrator;
    private final BidirectionalRagService bidirectionalRagService;
    private final ModalityRouter modalityRouter;
    private final SectorConfig sectorConfig;
    private final PromptGuardrailService guardrailService;
    private final PiiRedactionService piiRedactionService;
    private final ConversationMemoryService conversationMemoryService;
    private final SessionPersistenceService sessionPersistenceService;
    private final RagOrchestrationService ragOrchestrationService;
    private final Cache<String, String> secureDocCache;
    private final LicenseService licenseService;
    private final AtomicInteger docCount = new AtomicInteger(0);
    private final OllamaOptions llmOptions;
    private static final String NO_RELEVANT_RECORDS = "No relevant records found.";
    private static final Pattern STRICT_CITATION_PATTERN = Pattern.compile("\\[(?:Citation:\\s*)?(?:IMAGE:\\s*)?[^\\]]+\\.(pdf|txt|md|csv|xlsx|xls|png|jpg|jpeg|gif|tif|tiff|bmp)\\]", 2);
    private static final Pattern RELATIONSHIP_PATTERN = Pattern.compile("\\b(relationship|relate|comparison|compare|versus|between|difference|differ|impact|effect|align|alignment|correlat|dependency|tradeoff|link)\\b", 2);
    @Value("${sentinel.llm.timeout-seconds:60}")
    private int llmTimeoutSeconds;
    @Value("${sentinel.rag.max-context-chars:16000}")
    private int maxContextChars;
    @Value("${sentinel.rag.max-doc-chars:2500}")
    private int maxDocChars;
    @Value("${sentinel.rag.max-visual-chars:1500}")
    private int maxVisualChars;
    @Value("${sentinel.rag.max-overview-chars:3000}")
    private int maxOverviewChars;
    @Value("${sentinel.rag.max-docs:16}")
    private int maxDocs;
    @Value("${sentinel.rag.max-visual-docs:8}")
    private int maxVisualDocs;

    public MercenaryController(ChatClient.Builder builder, VectorStore vectorStore, SecureIngestionService ingestionService, MongoTemplate mongoTemplate, AuditService auditService, QueryDecompositionService queryDecompositionService, ReasoningTracer reasoningTracer, QuCoRagService quCoRagService, AdaptiveRagService adaptiveRagService, RewriteService rewriteService, RagPartService ragPartService, HybridRagService hybridRagService, HiFiRagService hiFiRagService, MiARagService miARagService, MegaRagService megaRagService, HGMemQueryEngine hgMemQueryEngine, AgenticRagOrchestrator agenticRagOrchestrator, BidirectionalRagService bidirectionalRagService, ModalityRouter modalityRouter, SectorConfig sectorConfig, PromptGuardrailService guardrailService, PiiRedactionService piiRedactionService, ConversationMemoryService conversationMemoryService, SessionPersistenceService sessionPersistenceService, RagOrchestrationService ragOrchestrationService, Cache<String, String> secureDocCache, LicenseService licenseService,
                               @Value(value="${spring.ai.ollama.chat.options.model:llama3.1:8b}") String llmModel,
                               @Value(value="${spring.ai.ollama.chat.options.temperature:0.0}") double llmTemperature,
                               @Value(value="${spring.ai.ollama.chat.options.num-predict:256}") int llmNumPredict) {
        this.chatClient = builder.defaultFunctions(new String[]{"calculator", "currentDate"}).build();
        this.vectorStore = vectorStore;
        this.ingestionService = ingestionService;
        this.sectorConfig = sectorConfig;
        this.mongoTemplate = mongoTemplate;
        this.auditService = auditService;
        this.queryDecompositionService = queryDecompositionService;
        this.reasoningTracer = reasoningTracer;
        this.quCoRagService = quCoRagService;
        this.adaptiveRagService = adaptiveRagService;
        this.rewriteService = rewriteService;
        this.ragPartService = ragPartService;
        this.hybridRagService = hybridRagService;
        this.hiFiRagService = hiFiRagService;
        this.miARagService = miARagService;
        this.megaRagService = megaRagService;
        this.hgMemQueryEngine = hgMemQueryEngine;
        this.agenticRagOrchestrator = agenticRagOrchestrator;
        this.bidirectionalRagService = bidirectionalRagService;
        this.modalityRouter = modalityRouter;
        this.guardrailService = guardrailService;
        this.piiRedactionService = piiRedactionService;
        this.conversationMemoryService = conversationMemoryService;
        this.sessionPersistenceService = sessionPersistenceService;
        this.ragOrchestrationService = ragOrchestrationService;
        this.secureDocCache = secureDocCache;
        this.licenseService = licenseService;
        this.llmOptions = OllamaOptions.create()
                .withModel(llmModel)
                .withTemperature(llmTemperature)
                .withNumPredict(Integer.valueOf(llmNumPredict));
        try {
            long count = mongoTemplate.getCollection("vector_store").countDocuments();
            this.docCount.set((int)count);
        }
        catch (Exception e) {
            if (log.isErrorEnabled()) {
                log.error("Failed to initialize doc count", (Throwable)e);
            }
        }
        // R-08: LLM config at debug level to avoid leaking infrastructure details in production logs
        if (log.isDebugEnabled()) {
            log.debug("=== LLM Configuration ===");
            log.debug("  Model: {}", this.llmOptions.getModel());
            log.debug("  Temperature: {}", this.llmOptions.getTemperature());
            log.debug("  Max Tokens (num_predict): {}", this.llmOptions.getNumPredict());
            log.debug("  Ollama Base URL: {}", System.getProperty("spring.ai.ollama.base-url", "http://localhost:11434"));
            log.debug("=========================");
        }
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getSystemStatus() {
        // R-03: Require authentication — status exposes operational metrics
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            this.auditService.logAccessDenied(null, "/api/status", "Unauthenticated access attempt", null);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        long avgLat = this.ragOrchestrationService.getAverageLatencyMs();
        int qCount = this.ragOrchestrationService.getQueryCount();
        boolean dbOnline = true;
        try {
            SearchRequest.query((String)"ping");
        }
        catch (Exception e) {
            dbOnline = false;
        }
        return ResponseEntity.ok(Map.of("vectorDb", dbOnline ? "ONLINE" : "OFFLINE", "docsIndexed", this.docCount.get(), "avgLatency", avgLat + "ms", "queriesToday", qCount, "systemStatus", "NOMINAL"));
    }

    @GetMapping("/telemetry")
    public ResponseEntity<TelemetryResponse> getTelemetry() {
        // R-02: Require authentication — telemetry exposes system metrics
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            this.auditService.logAccessDenied(null, "/api/telemetry", "Unauthenticated access attempt", null);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        long avgLat = this.ragOrchestrationService.getAverageLatencyMs();
        int qCount = this.ragOrchestrationService.getQueryCount();
        boolean dbOnline = true;
        long liveDocCount = 0L;
        try {
            liveDocCount = this.mongoTemplate.getCollection("vector_store").countDocuments();
        }
        catch (Exception e) {
            dbOnline = false;
        }
        boolean llmOnline = true;
        try {
            this.chatClient.prompt().user("ping").call();
        }
        catch (Exception e) {
            llmOnline = false;
            if (log.isDebugEnabled()) {
                log.debug("LLM health check failed: {}", e.getMessage());
            }
        }
        return ResponseEntity.ok(new TelemetryResponse((int)liveDocCount, qCount, avgLat, dbOnline, llmOnline));
    }

    @GetMapping(value={"/user/context"})
    public UserContextResponse getUserContext() {
        User user = SecurityContext.getCurrentUser();
        String edition = this.licenseService.getEdition().name();
        if (user == null) {
            return new UserContextResponse("Anonymous", "UNCLASSIFIED", List.of(), false, edition);
        }
        List<String> sectors = user.getAllowedSectors().stream().map(Enum::name).sorted().collect(Collectors.toList());
        return new UserContextResponse(user.getDisplayName(), user.getClearance().name(), sectors, user.hasRole(UserRole.ADMIN), edition);
    }

    @GetMapping(value={"/inspect"})
    public InspectResponse inspectDocument(@RequestParam(value="fileName") String fileName, @RequestParam(value="query", required=false) String query, @RequestParam(value="dept", defaultValue="ENTERPRISE") String deptParam) {
        String content = "";
        String header = "";
        String body = "";
        boolean redacted = false;
        int redactionCount = 0;
        // R-01: Auth check BEFORE input validation to avoid leaking info to unauthenticated users
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            this.auditService.logAccessDenied(null, "/api/inspect", "Unauthenticated access attempt", null);
            return new InspectResponse("ACCESS DENIED: Authentication required.", List.of(), false, 0);
        }
        if (!user.hasPermission(UserRole.Permission.QUERY)) {
            this.auditService.logAccessDenied(user, "/api/inspect", "Missing QUERY permission", null);
            return new InspectResponse("ACCESS DENIED: Insufficient permissions.", List.of(), false, 0);
        }
        String dept = deptParam.toUpperCase(Locale.ROOT);
        if (!Set.of("GOVERNMENT", "MEDICAL", "FINANCE", "ACADEMIC", "ENTERPRISE").contains(dept)) {
            if (log.isWarnEnabled()) {
                log.warn("SECURITY: Invalid department in inspect request: {}", deptParam);
            }
            return new InspectResponse("ERROR: Invalid sector.", List.of(), false, 0);
        }
        Department department = Department.valueOf(dept);
        if (this.sectorConfig.requiresElevatedClearance(department) && !user.canAccessClassification(department.getRequiredClearance())) {
            this.auditService.logAccessDenied(user, "/api/inspect", "Insufficient clearance for " + dept, null);
            return new InspectResponse("ACCESS DENIED: Insufficient clearance for " + dept, List.of(), false, 0);
        }
        if (!user.canAccessSector(department)) {
            this.auditService.logAccessDenied(user, "/api/inspect", "Not authorized for sector " + dept, null);
            return new InspectResponse("ACCESS DENIED: Unauthorized sector access.", List.of(), false, 0);
        }
        String normalizedFileName = fileName.replaceFirst("(?i)^filename:\\s*", "").replaceFirst("(?i)^source:\\s*", "").replaceFirst("(?i)^citation:\\s*", "").trim();
        if (normalizedFileName.contains("..") || normalizedFileName.contains("/") || normalizedFileName.contains("\\") || normalizedFileName.contains("\u0000") || !normalizedFileName.matches("^[a-zA-Z0-9._\\-\\s]+$")) {
            log.warn("SECURITY: Path traversal attempt detected in filename: {}", fileName);
            return new InspectResponse("ERROR: Invalid filename. Path traversal not allowed.", List.of(), false, 0);
        }
        String workspaceId = WorkspaceContext.getCurrentWorkspaceId();
        String cacheKey = workspaceId + ":" + dept + ":" + normalizedFileName;
        String cachedContent = (String)this.secureDocCache.getIfPresent(cacheKey);
        if (cachedContent != null) {
            header = "--- SECURE DOCUMENT VIEWER (CACHE) ---\nFILE: " + fileName + "\nSTATUS: DECRYPTED [RAM]\n----------------------------------\n\n";
            body = cachedContent;
        } else {
            try {
                List<Document> potentialDocs = this.vectorStore.similaritySearch(SearchRequest.query((String)normalizedFileName)
                        .withTopK(20)
                        .withFilterExpression(FilterExpressionBuilder.forDepartmentAndWorkspace(dept, workspaceId)));
                log.info("INSPECT DEBUG: Searching for '{}'. Found {} potential candidates.", normalizedFileName, potentialDocs.size());
                potentialDocs.forEach(d -> log.info("  >> Candidate Meta: {}", d.getMetadata()));
                Optional<Document> match = potentialDocs.stream().filter(doc -> normalizedFileName.equals(doc.getMetadata().get("source")) || this.apiKeyMatch(normalizedFileName, doc.getMetadata())).findFirst();
                if (!match.isPresent()) {
                    return new InspectResponse("ERROR: Document archived in Deep Storage.\nPlease re-ingest file to refresh active cache.", List.of(), false, 0);
                }
                String recoveredContent = match.get().getContent();
                header = "--- SECURE DOCUMENT VIEWER (ARCHIVE) ---\nFILE: " + normalizedFileName + "\nSECTOR: " + dept + "\nSTATUS: RECONSTRUCTED FROM VECTOR STORE\n----------------------------------\n\n";
                body = recoveredContent;
            }
            catch (Exception e) {
                log.error(">> RECOVERY FAILED: {}", e.getMessage(), e);
                return new InspectResponse("ERROR: Document recovery failed. Please contact support if this persists. [ERR-1001]", List.of(), false, 0);
            }
        }
        if (body == null) {
            body = "";
        }
        PiiRedactionService.RedactionResult redactionResult = this.piiRedactionService.redact(body);
        if (redactionResult != null) {
            body = redactionResult.getRedactedContent();
            redacted = redactionResult.hasRedactions();
            redactionCount = redactionResult.getTotalRedactions();
        }
        if (cachedContent == null || redacted) {
            this.secureDocCache.put(cacheKey, body);
        }
        content = header + body;
        ArrayList<String> highlights = new ArrayList<String>();
        if (query != null && !query.isEmpty() && !content.isEmpty()) {
            String[] paragraphs;
            String[] keywords = query.toLowerCase().split("\\s+");
            for (String p : paragraphs = content.split("\n\n")) {
                if (p.startsWith("---")) continue;
                int matches = 0;
                String lowerP = p.toLowerCase();
                for (String k : keywords) {
                    if (!lowerP.contains(k) || k.length() <= 3) continue;
                    ++matches;
                }
                if (matches < 1) continue;
                highlights.add(p.trim());
                if (highlights.size() >= 3) break;
            }
        }
        return new InspectResponse(content, highlights, redacted, redactionCount);
    }

    @GetMapping(value={"/health"})
    public String health() {
        return "SYSTEMS NOMINAL";
    }

    @PostMapping(value={"/ingest/file"})
    public ResponseEntity<String> ingestFile(@RequestParam(value="file") MultipartFile file, @RequestParam(value="dept") String dept, HttpServletRequest request) {
        Department department;
        User user = SecurityContext.getCurrentUser();
        try {
            department = Department.valueOf(dept.toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e) {
            // H-05: Do not reflect unsanitized user input in error responses
            return ResponseEntity.badRequest().body("INVALID SECTOR: unrecognized department value");
        }
        if (user == null) {
            this.auditService.logAccessDenied(null, "/api/ingest/file", "Unauthenticated access attempt", request);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("ACCESS DENIED: Authentication required.");
        }
        if (!user.hasPermission(UserRole.Permission.INGEST)) {
            this.auditService.logAccessDenied(user, "/api/ingest/file", "Missing INGEST permission", request);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("ACCESS DENIED: Insufficient permissions for document ingestion.");
        }
        if (this.sectorConfig.requiresElevatedClearance(department) && !user.canAccessClassification(department.getRequiredClearance())) {
            this.auditService.logAccessDenied(user, "/api/ingest/file", "Insufficient clearance for " + department.name(), request);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("ACCESS DENIED: Insufficient clearance for " + department.name() + " sector.");
        }
        if (!user.canAccessSector(department)) {
            this.auditService.logAccessDenied(user, "/api/ingest/file", "Not authorized for sector " + department.name(), request);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("ACCESS DENIED: You are not authorized to access the " + department.name() + " sector.");
        }
        try {
            long startTime = System.currentTimeMillis();
            String filename = file.getOriginalFilename();
            String status = "COMPLETE";
            try {
                this.ingestionService.ingest(file, department);
            }
            catch (com.jreinhal.mercenary.workspace.WorkspaceQuotaExceededException e) {
                if (log.isWarnEnabled()) {
                    log.warn("Workspace quota exceeded for {}: {}", filename, e.getMessage());
                }
                this.auditService.logAccessDenied(user, "/api/ingest/file", "Workspace quota exceeded: " + e.getQuotaType(), request);
                // S2-04: Use safe quota type label instead of raw exception message
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body("QUOTA EXCEEDED: " + e.getQuotaType() + " limit reached");
            }
            catch (SecurityException e) {
                if (log.isWarnEnabled()) {
                    log.warn("SECURITY: Ingestion blocked for {}: {}", filename, e.getMessage());
                }
                this.auditService.logAccessDenied(user, "/api/ingest/file", "Blocked file type: " + e.getMessage(), request);
                // S2-04: Generic message — details already logged server-side
                return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body("BLOCKED: File type not permitted for ingestion.");
            }
            catch (Exception e) {
                log.warn("Persistence Failed (DB Offline). Proceeding with RAM Cache.", (Throwable)e);
                status = "Warning: RAM ONLY (DB Unreachable)";
            }
            String rawContent = new String(file.getBytes(), StandardCharsets.UTF_8);
            PiiRedactionService.RedactionResult redactionResult = this.piiRedactionService.redact(rawContent);
            String redactedContent = redactionResult.getRedactedContent();
            String workspaceId = WorkspaceContext.getCurrentWorkspaceId();
            String cacheKey = workspaceId + ":" + dept.toUpperCase() + ":" + filename;
            this.secureDocCache.put(cacheKey, redactedContent);
            this.docCount.incrementAndGet();
            long duration = System.currentTimeMillis() - startTime;
            if (user != null) {
                this.auditService.logIngestion(user, filename, department, request);
            }
            return ResponseEntity.ok("SECURE INGESTION " + status + ": " + filename + " (" + duration + "ms)");
        }
        catch (Exception e) {
            log.error("CRITICAL FAILURE: Ingestion Protocol Failed.", (Throwable)e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("CRITICAL FAILURE: Ingestion Protocol Failed.");
        }
    }

    @RequestMapping(value={"/ask"}, method={RequestMethod.GET, RequestMethod.POST})
    public String ask(@RequestParam(value="q") String query, @RequestParam(value="dept") String dept, @RequestParam(value="file", required=false) List<String> fileParams, @RequestParam(value="files", required=false) String filesParam, HttpServletRequest request) {
        return this.ragOrchestrationService.ask(query, dept, fileParams, filesParam, request);
    }

    @RequestMapping(value={"/ask/enhanced"}, method={RequestMethod.GET, RequestMethod.POST})
    public EnhancedAskResponse askEnhanced(@RequestParam(value="q") String query, @RequestParam(value="dept") String dept, @RequestParam(value="file", required=false) List<String> fileParams, @RequestParam(value="files", required=false) String filesParam, @RequestParam(value="sessionId", required=false) String sessionId, @RequestParam(value="deepAnalysis", defaultValue="false") boolean deepAnalysis, HttpServletRequest request) {
        return this.ragOrchestrationService.askEnhanced(query, dept, fileParams, filesParam, sessionId, deepAnalysis, request);
    }

    @GetMapping(value={"/reasoning/{traceId}"})
    public Map<String, Object> getReasoningTrace(@PathVariable String traceId) {
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            this.auditService.logAccessDenied(null, "/api/reasoning/" + traceId, "Unauthenticated access", null);
            return Map.of("error", "Authentication required", "traceId", traceId);
        }
        ReasoningTrace trace = this.reasoningTracer.getTrace(traceId);
        if (trace == null) {
            return Map.of("error", "Trace not found", "traceId", traceId);
        }
        String workspaceId = WorkspaceContext.getCurrentWorkspaceId();
        if (trace.getWorkspaceId() != null && !trace.getWorkspaceId().equalsIgnoreCase(workspaceId)) {
            this.auditService.logAccessDenied(user, "/api/reasoning/" + traceId, "Cross-workspace trace access", null);
            return Map.of("error", "Access denied - wrong workspace", "traceId", traceId);
        }
        String traceOwnerId = trace.getUserId();
        boolean isOwner = traceOwnerId != null && traceOwnerId.equals(user.getId());
        boolean isAdmin = user.hasRole(UserRole.ADMIN);
        if (!isOwner && !isAdmin) {
            this.auditService.logAccessDenied(user, "/api/reasoning/" + traceId, "Attempted access to another user's trace", null);
            return Map.of("error", "Access denied - not trace owner", "traceId", traceId);
        }
        return trace.toMap();
    }

    /**
     * SSE Streaming endpoint for real-time query processing feedback.
     * Streams reasoning steps as they complete, then the final response.
     */
    @GetMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(
            @RequestParam("q") String query,
            @RequestParam("dept") String dept,
            @RequestParam(value = "file", required = false) List<String> fileParams,
            @RequestParam(value = "files", required = false) String filesParam,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "deepAnalysis", defaultValue = "false") boolean deepAnalysis,
            HttpServletRequest request) {

        SseEmitter emitter = new SseEmitter(180000L); // 3 minute timeout

        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            sendSseError(emitter, "Authentication required");
            return emitter;
        }

        Department department;
        try {
            department = Department.valueOf(dept.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            sendSseError(emitter, "INVALID SECTOR: unrecognized department value");
            return emitter;
        }

        if (!user.hasPermission(UserRole.Permission.QUERY)) {
            sendSseError(emitter, "Insufficient permissions");
            return emitter;
        }

        // S2-01: Sector clearance checks consistent with /api/ask and /api/ask/enhanced
        if (this.sectorConfig.requiresElevatedClearance(department) && !user.canAccessClassification(department.getRequiredClearance())) {
            this.auditService.logAccessDenied(user, "/api/ask/stream", "Insufficient clearance for " + department.name(), null);
            sendSseError(emitter, "Insufficient clearance for this sector");
            return emitter;
        }
        if (!user.canAccessSector(department)) {
            this.auditService.logAccessDenied(user, "/api/ask/stream", "Not authorized for sector " + department.name(), null);
            sendSseError(emitter, "Not authorized for this sector");
            return emitter;
        }

        List<String> activeFiles = this.parseActiveFiles(fileParams, filesParam);

        // Process in background thread
        CompletableFuture.runAsync(() -> {
            try {
                // Send initial connection event
                emitter.send(SseEmitter.event()
                    .name("connected")
                    .data("{\"status\":\"connected\",\"query\":\"" + escapeJson(query) + "\"}"));

                // Step 1: Security Check
                sendSseStep(emitter, "security_check", "Security Scan", "Analyzing query for threats...");
                boolean isInjection = this.isPromptInjection(query);
                if (isInjection) {
                    sendSseStep(emitter, "security_check", "Security Scan", "BLOCKED: Injection detected");
                    emitter.send(SseEmitter.event()
                        .name("complete")
                        .data("{\"answer\":\"SECURITY ALERT: Indirect Prompt Injection Detected.\",\"blocked\":true}"));
                    emitter.complete();
                    return;
                }
                sendSseStep(emitter, "security_check", "Security Scan", "Query passed security check");

                // Step 2: Query Routing
                sendSseStep(emitter, "query_routing", "Query Routing", "Analyzing query complexity...");
                Thread.sleep(100); // Small delay for visual feedback

                AdaptiveRagService.RoutingResult routing = this.adaptiveRagService.route(query);
                QuCoRagService.UncertaintyResult uncertaintyResult = this.quCoRagService.analyzeQueryUncertainty(query);
                boolean highUncertainty = this.quCoRagService.shouldTriggerRetrieval(uncertaintyResult.uncertaintyScore());

                // Step 3: Retrieval
                sendSseStep(emitter, "vector_search", "Retrieval", "Running hybrid retrieval...");
                RetrievalContext context = this.retrieveContext(query, dept, activeFiles, routing, highUncertainty, deepAnalysis);
                List<Document> docs = context.textDocuments();
                List<Document> visualDocs = context.visualDocuments();
                sendSseStep(emitter, "vector_search", "Retrieval", "Found " + docs.size() + " text documents and " + visualDocs.size() + " visual documents");

                // Step 4: Context Assembly
                sendSseStep(emitter, "context_assembly", "Context Assembly", "Building context from documents...");
                List<Document> topDocs = docs.stream().limit(10).toList();
                String information = this.buildInformation(topDocs, context.globalContext());
                String visualInfo = this.buildVisualInformation(visualDocs);
                if (!visualInfo.isBlank()) {
                    information = information.isBlank() ? visualInfo : information + "\n\n---\n\n" + "=== VISUAL SOURCES ===\n" + visualInfo;
                }
                sendSseStep(emitter, "context_assembly", "Context Assembly", "Assembled " + information.length() + " chars from " + topDocs.size() + " docs");

                // Step 5: LLM Generation with Token Streaming
                sendSseStep(emitter, "llm_generation", "Response Synthesis", "Generating response...");

                String systemMessage = information.isEmpty()
                    ? "You are SENTINEL. No documents found. Respond: 'No relevant records found.'"
                    : String.format("You are SENTINEL, an advanced intelligence analyst for %s sector.\n\n" +
                        "INSTRUCTIONS:\n" +
                        "- DIRECTLY ANSWER the user's question with specific facts, figures, and data from the documents\n" +
                        "- Use the OVERVIEW section (if present) for background only and do NOT cite it\n" +
                        "- Base your response ONLY on the provided documents\n" +
                        "- Cite each source using [filename] format after each fact\n" +
                        "- For visual sources, cite as [IMAGE: filename.ext]\n\n" +
                        "CONSTRAINTS:\n" +
                        "- Never fabricate information not in the documents\n" +
                        "- If information is not found, respond: 'No relevant records found.'\n\n" +
                        "DOCUMENTS:\n%s", dept, information);

                String sysMsg = systemMessage.replace("{", "[").replace("}", "]");
                String userQuery = query.replace("{", "[").replace("}", "]");

                // Stream tokens in real-time
                StringBuilder responseBuilder = new StringBuilder();
                AtomicBoolean streamComplete = new AtomicBoolean(false);
                AtomicBoolean hasError = new AtomicBoolean(false);
                AtomicReference<String> errorMsg = new AtomicReference<>("");

                try {
                    this.chatClient.prompt()
                        .system(sysMsg)
                        .user(userQuery)
                        .options((ChatOptions) this.llmOptions)
                        .stream()
                        .content()
                        .doOnNext(token -> {
                            try {
                                responseBuilder.append(token);
                                // Send token to client
                                emitter.send(SseEmitter.event()
                                    .name("token")
                                    .data("{\"token\":\"" + escapeJson(token) + "\"}"));
                            } catch (Exception e) {
                                log.warn("Error sending token: {}", e.getMessage());
                            }
                        })
                        .doOnComplete(() -> streamComplete.set(true))
                        .doOnError(e -> {
                            hasError.set(true);
                            errorMsg.set(e.getMessage());
                            log.error("Stream error: {}", e.getMessage());
                        })
                        .blockLast(Duration.ofSeconds(llmTimeoutSeconds));

                } catch (Exception e) {
                    hasError.set(true);
                    errorMsg.set(e.getMessage());
                    log.error("Streaming failed: {}", e.getMessage());
                }

                String response;
                if (hasError.get()) {
                    if (errorMsg.get().contains("timeout") || errorMsg.get().toLowerCase().contains("deadline")) {
                        response = "**Response Timeout**\n\nThe system is taking longer than expected. Please try simplifying your question.";
                        sendSseStep(emitter, "llm_generation", "Response Synthesis", "Timeout - using fallback response");
                    } else {
                        response = "An error occurred generating the response.";
                        sendSseStep(emitter, "llm_generation", "Response Synthesis", "Error: " + errorMsg.get());
                    }
                } else {
                    response = cleanLlmResponse(responseBuilder.toString());
                    sendSseStep(emitter, "llm_generation", "Response Synthesis", "Response generated successfully");
                }

                // Step 6: Citation Verification
                sendSseStep(emitter, "citation_verification", "Citation Check", "Verifying citations...");
                int citationCount = countCitations(response);
                sendSseStep(emitter, "citation_verification", "Citation Check", "Found " + citationCount + " citations");

                // Send final response with sources
                List<String> sources = topDocs.stream()
                    .map(d -> (String) d.getMetadata().get("source"))
                    .filter(s -> s != null)
                    .distinct()
                    .toList();

                emitter.send(SseEmitter.event()
                    .name("complete")
                    .data("{\"answer\":\"" + escapeJson(response) + "\",\"sources\":" + toJsonArray(sources) + ",\"citationCount\":" + citationCount + "}"));

                emitter.complete();

            } catch (Exception e) {
                log.error("SSE stream error", e);
                try {
                    emitter.send(SseEmitter.event()
                        .name("error")
                        .data("{\"error\":\"" + escapeJson(e.getMessage()) + "\"}"));
                    emitter.complete();
                } catch (Exception ex) {
                    emitter.completeWithError(ex);
                }
            }
        });

        return emitter;
    }

    private void sendSseStep(SseEmitter emitter, String type, String label, String detail) {
        try {
            String json = String.format(
                "{\"type\":\"%s\",\"label\":\"%s\",\"detail\":\"%s\",\"timestamp\":%d}",
                type, label, escapeJson(detail), System.currentTimeMillis()
            );
            emitter.send(SseEmitter.event().name("step").data(json));
        } catch (Exception e) {
            log.warn("Failed to send SSE step: {}", e.getMessage());
        }
    }

    private void sendSseError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event()
                .name("error")
                .data("{\"error\":\"" + escapeJson(message) + "\"}"));
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String toJsonArray(List<String> list) {
        if (list == null || list.isEmpty()) return "[]";
        return "[" + list.stream()
            .map(s -> "\"" + escapeJson(s) + "\"")
            .collect(Collectors.joining(",")) + "]";
    }

    /**
     * Cleans up LLM responses that may contain raw function call JSON.
     * This happens when the model outputs tool calls instead of plain text.
     */
    private static String cleanLlmResponse(String response) {
        if (response == null || response.isBlank()) {
            return response;
        }

        String trimmed = response.trim();

        // Detect function call JSON pattern: {"name": "...", "parameters": {...}}
        if (trimmed.startsWith("{\"name\"") && trimmed.contains("\"parameters\"")) {
            try {
                // Try to extract the expression/content from the function call
                int exprStart = trimmed.indexOf("\"expression\"");
                if (exprStart == -1) {
                    exprStart = trimmed.indexOf("\"content\"");
                }
                if (exprStart == -1) {
                    exprStart = trimmed.indexOf("\"value\"");
                }

                if (exprStart != -1) {
                    // Find the value after the key
                    int colonPos = trimmed.indexOf(":", exprStart);
                    if (colonPos != -1) {
                        int valueStart = trimmed.indexOf("\"", colonPos + 1);
                        if (valueStart != -1) {
                            int valueEnd = trimmed.lastIndexOf("\"");
                            if (valueEnd > valueStart) {
                                String extracted = trimmed.substring(valueStart + 1, valueEnd);
                                // Unescape JSON string
                                extracted = extracted.replace("\\\"", "\"")
                                                   .replace("\\n", "\n")
                                                   .replace("\\t", "\t");
                                log.warn("Cleaned malformed function call response, extracted: {}...",
                                    extracted.substring(0, Math.min(100, extracted.length())));
                                return extracted;
                            }
                        }
                    }
                }

                // If we can't extract, return a warning message
                log.error("Unable to parse function call response: {}", trimmed.substring(0, Math.min(200, trimmed.length())));
                return "The system encountered a formatting issue. Please try rephrasing your question.";

            } catch (Exception e) {
                log.error("Error cleaning LLM response: {}", e.getMessage());
                return "An error occurred processing the response. Please try again.";
            }
        }

        // Also handle array of function calls: [{"name": ...}]
        if (trimmed.startsWith("[{\"name\"") && trimmed.contains("\"parameters\"")) {
            int start = trimmed.indexOf("{");
            int end = trimmed.lastIndexOf("}");
            if (start >= 0 && end > start) {
                return cleanLlmResponse(trimmed.substring(start, end + 1));
            }
        }
        return trimmed;
    }

    private static int countCitations(String response) {
        if (response == null || response.isBlank()) {
            return 0;
        }
        int count = 0;
        Matcher matcher = STRICT_CITATION_PATTERN.matcher(response);
        while (matcher.find()) {
            ++count;
        }
        return count;
    }

    private List<Document> performHybridRerankingTracked(String query, String dept) {
        return this.performHybridReranking(query, dept, 0.15, List.of());
    }

    private List<Document> performHybridRerankingTracked(String query, String dept, List<String> activeFiles) {
        return this.performHybridReranking(query, dept, 0.15, activeFiles);
    }

    private List<Document> performHybridRerankingTracked(String query, String dept, double threshold) {
        return this.performHybridReranking(query, dept, threshold, List.of());
    }

    private List<Document> performHybridRerankingTracked(String query, String dept, double threshold, List<String> activeFiles) {
        return this.performHybridReranking(query, dept, threshold, activeFiles);
    }

    private List<Document> performHybridReranking(String query, String dept, double threshold, List<String> activeFiles) {
        if (!Set.of("GOVERNMENT", "MEDICAL", "FINANCE", "ACADEMIC", "ENTERPRISE").contains(dept)) {
            log.warn("SECURITY: Invalid department value in filter: {}", dept);
            return List.of();
        }
        String workspaceId = WorkspaceContext.getCurrentWorkspaceId();
        if (this.hybridRagService != null && this.hybridRagService.isEnabled()) {
            try {
                HybridRagService.HybridRetrievalResult result = this.hybridRagService.retrieve(query, dept);
                List<Document> scoped = this.filterDocumentsByFiles(result.documents(), activeFiles);
                if (!scoped.isEmpty()) {
                    return this.sortDocumentsDeterministically(new ArrayList<>(scoped));
                }
            }
            catch (Exception e) {
                log.warn("HybridRAG failed, falling back to local reranking: {}", e.getMessage());
            }
        }
        List<Document> semanticResults = new ArrayList<>();
        List<Document> keywordResults = new ArrayList<>();
        try {
            semanticResults = this.vectorStore.similaritySearch(SearchRequest.query((String)query).withTopK(10).withSimilarityThreshold(threshold).withFilterExpression(FilterExpressionBuilder.forDepartmentAndWorkspace(dept, workspaceId)));
            log.info("Semantic search found {} results for query {}", semanticResults.size(), LogSanitizer.querySummary(query));
            String lowerQuery = query.toLowerCase();
            keywordResults = this.vectorStore.similaritySearch(SearchRequest.query((String)query).withTopK(50).withSimilarityThreshold(0.01).withFilterExpression(FilterExpressionBuilder.forDepartmentAndWorkspace(dept, workspaceId)));
            log.info("Keyword fallback found {} documents for query {}", keywordResults.size(), LogSanitizer.querySummary(query));
            Set<String> stopWords = Set.of("the", "and", "for", "was", "are", "is", "of", "to", "in", "what", "where", "when", "who", "how", "why", "tell", "me", "about", "describe", "find", "show", "give", "also");
            String[] queryTerms = lowerQuery.split("\\s+");
            keywordResults = keywordResults.stream().filter(doc -> {
                String source = String.valueOf(doc.getMetadata().get("source")).toLowerCase();
                String content = doc.getContent().toLowerCase();
                for (String term : queryTerms) {
                    if (term.length() < 3 || stopWords.contains(term) || !source.contains(term) && !content.contains(term)) continue;
                    return true;
                }
                return false;
            }).collect(Collectors.toList());
        }
        catch (Exception e) {
            log.warn("Vector/Keyword search FAILED (DB/Embedding Offline). Engaging RAM Cache Fallback.", (Throwable)e);
            return this.searchInMemoryCache(query, dept, activeFiles);
        }
        LinkedHashSet<Document> merged = new LinkedHashSet<>(semanticResults);
        merged.addAll(keywordResults);
        List<Document> scoped = this.filterDocumentsByFiles(new ArrayList<>(merged), activeFiles);
        if (scoped.isEmpty()) {
            log.warn("Vector/Keyword search yielded 0 results. Engaging RAM Cache Fallback.");
            return this.searchInMemoryCache(query, dept, activeFiles);
        }
        // Boost documents with exact phrase matches from query
        boostKeywordMatches(scoped, query);
        return this.sortDocumentsDeterministically(scoped);
    }

    private RetrievalContext retrieveContext(String query, String dept, List<String> activeFiles, AdaptiveRagService.RoutingResult routing, boolean highUncertainty, boolean deepAnalysis) {
        boolean complexQuery = routing != null && routing.decision() == AdaptiveRagService.RoutingDecision.DOCUMENT;
        boolean relationshipQuery = RELATIONSHIP_PATTERN.matcher(query).find();
        boolean longQuery = query.split("\\s+").length > 15;
        boolean advancedNeeded = complexQuery || relationshipQuery || longQuery || highUncertainty;
        Set<ModalityRouter.ModalityTarget> modalities = this.modalityRouter != null ? this.modalityRouter.route(query) : Set.of(ModalityRouter.ModalityTarget.TEXT);

        ArrayList<String> strategies = new ArrayList<>();
        List<Document> textDocs = new ArrayList<>();
        String globalContext = "";
        RagPartService.RagPartResult ragPartResult = null;

        if (this.ragPartService != null && this.ragPartService.isEnabled()) {
            ragPartResult = this.ragPartService.retrieve(query, dept);
            if (!ragPartResult.verifiedDocuments().isEmpty()) {
                textDocs.addAll(ragPartResult.verifiedDocuments());
                strategies.add("RAGPart");
            }
        }

        if (textDocs.isEmpty() && this.miARagService != null && this.miARagService.isEnabled() && advancedNeeded) {
            MiARagService.MindscapeRetrievalResult mindscapeResult = this.miARagService.retrieve(query, dept);
            textDocs.addAll(mindscapeResult.localDocs());
            globalContext = mindscapeResult.globalContext();
            if (!mindscapeResult.mindscapes().isEmpty()) {
                strategies.add("MiA-RAG");
            }
        }

        if (textDocs.isEmpty() && this.hiFiRagService != null && this.hiFiRagService.isEnabled() && advancedNeeded) {
            textDocs.addAll(this.hiFiRagService.retrieve(query, dept));
            if (!textDocs.isEmpty()) {
                strategies.add("HiFi-RAG");
            }
        }

        if (textDocs.isEmpty() && this.hybridRagService != null && this.hybridRagService.isEnabled()) {
            HybridRagService.HybridRetrievalResult hybridResult = this.hybridRagService.retrieve(query, dept);
            textDocs.addAll(hybridResult.documents());
            if (!textDocs.isEmpty()) {
                strategies.add("HybridRAG");
            }
        }

        if (textDocs.isEmpty()) {
            textDocs.addAll(this.performHybridRerankingTracked(query, dept, activeFiles));
            if (!textDocs.isEmpty()) {
                strategies.add("FallbackRerank");
            }
        }

        // HGMem deep analysis: controlled by UI toggle (deepAnalysis parameter)
        // Only runs graph traversal when explicitly requested - otherwise just vector search
        if (this.hgMemQueryEngine != null && (deepAnalysis || advancedNeeded)) {
            HGMemQueryEngine.HGMemResult hgResult = this.hgMemQueryEngine.query(query, dept, deepAnalysis);
            if (!hgResult.documents().isEmpty()) {
                textDocs.addAll(hgResult.documents());
                strategies.add(deepAnalysis ? "HGMem-Deep" : "HGMem");
            }
        }

        if (this.agenticRagOrchestrator != null && this.agenticRagOrchestrator.isEnabled() && advancedNeeded) {
            AgenticRagOrchestrator.AgenticResult agenticResult = this.agenticRagOrchestrator.process(query, dept);
            if (agenticResult.sources() != null && !agenticResult.sources().isEmpty()) {
                textDocs.addAll(agenticResult.sources());
                strategies.add("Agentic");
            }
        }

        ArrayList<Document> visualDocs = new ArrayList<>();
        ArrayList<MegaRagService.CrossModalEdge> edges = new ArrayList<>();
        if (this.megaRagService != null && this.megaRagService.isEnabled() && (modalities.contains(ModalityRouter.ModalityTarget.VISUAL) || modalities.contains(ModalityRouter.ModalityTarget.CROSS_MODAL))) {
            MegaRagService.CrossModalRetrievalResult crossModal = this.megaRagService.retrieve(query, dept);
            edges.addAll(crossModal.crossModalEdges());
            visualDocs.addAll(crossModal.visualDocs());
            List<Document> mergedText = crossModal.mergedResults().stream().filter(doc -> !this.isVisualDoc(doc)).toList();
            textDocs.addAll(mergedText);
            strategies.add("MegaRAG");
        }

        if (ragPartResult != null && ragPartResult.hasSuspiciousDocuments()) {
            Set<String> suspiciousIds = ragPartResult.suspiciousDocuments().stream().map(MercenaryController::buildDocumentId).collect(Collectors.toSet());
            textDocs = textDocs.stream().filter(doc -> !suspiciousIds.contains(buildDocumentId(doc))).collect(Collectors.toCollection(ArrayList::new));
        }

        textDocs = new ArrayList<>(this.filterDocumentsByFiles(textDocs, activeFiles));
        visualDocs = new ArrayList<>(this.filterDocumentsByFiles(visualDocs, activeFiles));

        textDocs = this.sortDocumentsDeterministically(new ArrayList<>(new LinkedHashSet<>(textDocs)));
        visualDocs = new ArrayList<>(new LinkedHashSet<>(visualDocs));

        return new RetrievalContext(textDocs, globalContext, visualDocs, edges, strategies, modalities);
    }

    private boolean isVisualDoc(Document doc) {
        if (doc == null) {
            return false;
        }
        Object type = doc.getMetadata().get("type");
        return type != null && "visual".equalsIgnoreCase(type.toString());
    }

    private static String buildDocumentId(Document doc) {
        if (doc == null) {
            return "";
        }
        Object source = doc.getMetadata().get("source");
        String sourceStr = source != null ? source.toString() : "";
        return sourceStr + "_" + Math.abs(doc.getContent().hashCode());
    }

    private String buildInformation(List<Document> docs, String globalContext) {
        StringBuilder sb = new StringBuilder();
        int remaining = this.maxContextChars;
        if (globalContext != null && !globalContext.isBlank() && remaining > 0) {
            String header = "=== OVERVIEW (context only; do not cite) ===\n";
            if (header.length() < remaining) {
                sb.append(header);
                remaining -= header.length();
                String overview = this.truncateContent(globalContext.trim(), Math.min(this.maxOverviewChars, remaining));
                sb.append(overview);
                remaining -= overview.length();
                if (docs != null && !docs.isEmpty() && remaining >= DOC_SEPARATOR.length()) {
                    sb.append(DOC_SEPARATOR);
                    remaining -= DOC_SEPARATOR.length();
                }
            }
        }
        if (docs == null || docs.isEmpty() || remaining <= 0) {
            return sb.toString().trim();
        }
        int docCount = 0;
        for (Document doc : docs) {
            if (docCount >= this.maxDocs || remaining <= 0) {
                break;
            }
            Map<String, Object> meta = doc.getMetadata();
            String filename = (String) meta.get("source");
            if (filename == null) {
                filename = (String) meta.get("filename");
            }
            if (filename == null) {
                filename = "Unknown_Document.txt";
            }
            String header = "[" + filename + "]\n";
            if (header.length() >= remaining) {
                break;
            }
            int allowedContent = Math.min(this.maxDocChars, remaining - header.length());
            if (allowedContent <= 0) {
                break;
            }
            String content = doc.getContent() != null ? doc.getContent() : "";
            content = content.replace("{", "[").replace("}", "]");
            String trimmed = this.truncateContent(content, allowedContent);
            sb.append(header).append(trimmed);
            remaining -= header.length() + trimmed.length();
            docCount++;
            if (docCount < this.maxDocs && remaining >= DOC_SEPARATOR.length()) {
                sb.append(DOC_SEPARATOR);
                remaining -= DOC_SEPARATOR.length();
            }
        }
        return sb.toString().trim();
    }

    private String buildVisualInformation(List<Document> visualDocs) {
        if (visualDocs == null || visualDocs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int remaining = this.maxContextChars;
        int docCount = 0;
        for (Document doc : visualDocs) {
            if (docCount >= this.maxVisualDocs || remaining <= 0) {
                break;
            }
            Map<String, Object> meta = doc.getMetadata();
            String filename = (String) meta.get("source");
            if (filename == null) {
                filename = (String) meta.get("filename");
            }
            if (filename == null) {
                filename = "Unknown_Image.png";
            }
            String header = "[IMAGE: " + filename + "]\n";
            if (header.length() >= remaining) {
                break;
            }
            int allowedContent = Math.min(this.maxVisualChars, remaining - header.length());
            if (allowedContent <= 0) {
                break;
            }
            String description = doc.getContent() != null ? doc.getContent() : "";
            String extractedText = String.valueOf(meta.getOrDefault("extractedText", ""));
            StringBuilder entry = new StringBuilder();
            entry.append(header);
            String descriptionTrimmed = this.truncateContent(description, allowedContent);
            entry.append(descriptionTrimmed);
            if (extractedText != null && !extractedText.isBlank()) {
                String suffix = "\nEXTRACTED TEXT: ";
                int remainingText = allowedContent - descriptionTrimmed.length();
                if (remainingText > suffix.length()) {
                    String extractedTrimmed = this.truncateContent(extractedText, remainingText - suffix.length());
                    entry.append(suffix).append(extractedTrimmed);
                }
            }
            String entryText = entry.toString();
            if (entryText.length() > remaining) {
                entryText = entryText.substring(0, remaining);
            }
            sb.append(entryText);
            remaining -= entryText.length();
            docCount++;
            if (docCount < this.maxVisualDocs && remaining >= DOC_SEPARATOR.length()) {
                sb.append(DOC_SEPARATOR);
                remaining -= DOC_SEPARATOR.length();
            }
        }
        return sb.toString().trim();
    }

    private String truncateContent(String text, int maxChars) {
        if (text == null || maxChars <= 0) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        String suffix = "...";
        if (maxChars <= suffix.length()) {
            return text.substring(0, maxChars);
        }
        return text.substring(0, maxChars - suffix.length()) + suffix;
    }

    private List<Document> searchInMemoryCache(String query, String dept, List<String> activeFiles) {
        ArrayList<Document> results = new ArrayList<Document>();
        String[] terms = query.toLowerCase().split("\\s+");
        String workspaceId = WorkspaceContext.getCurrentWorkspaceId();
        String sectorPrefix = dept.toUpperCase() + ":" + workspaceId + ":";
        String[] meaningfulTerms = (String[])Arrays.stream(terms).filter(t -> t.length() > 3).toArray(String[]::new);
        int minMatchesRequired = Math.max(2, (int)Math.ceil((double)meaningfulTerms.length * 0.3));
        for (Map.Entry<String, String> entry : this.secureDocCache.asMap().entrySet()) {
            String filename;
            String cacheKey = (String)entry.getKey();
            if (!cacheKey.startsWith(sectorPrefix) || !this.isFilenameInScope(filename = cacheKey.substring(sectorPrefix.length()), activeFiles)) continue;
            String content = (String)entry.getValue();
            String lowerContent = content.toLowerCase();
            long matches = Arrays.stream(meaningfulTerms).filter(lowerContent::contains).count();
            boolean filenameMatch = Arrays.stream(terms).anyMatch(t -> filename.toLowerCase().contains((CharSequence)t));
            if (matches < (long)minMatchesRequired && !filenameMatch) continue;
            Document doc = new Document(content);
            doc.getMetadata().put("source", filename);
            doc.getMetadata().put("filename", filename);
            doc.getMetadata().put("dept", dept.toUpperCase());
            doc.getMetadata().put("workspaceId", workspaceId);
            results.add(doc);
        }
        return results;
    }

    private List<String> parseActiveFiles(List<String> fileParams, String filesParam) {
        LinkedHashSet<String> files = new LinkedHashSet<String>();
        if (fileParams != null) {
            for (String file : fileParams) {
                String trimmed;
                if (file == null || (trimmed = file.trim()).isEmpty()) continue;
                files.add(trimmed);
            }
        }
        if (filesParam != null && !filesParam.isBlank()) {
            String[] parts;
            for (String part : parts = filesParam.split(",")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) continue;
                files.add(trimmed);
            }
        }
        if (files.size() > 25) {
            return files.stream().limit(25L).toList();
        }
        return files.stream().toList();
    }

    private List<Document> filterDocumentsByFiles(List<Document> docs, List<String> activeFiles) {
        if (activeFiles == null || activeFiles.isEmpty()) {
            return docs;
        }
        return docs.stream().filter(doc -> {
            Map<String, Object> meta = doc.getMetadata();
            for (String file : activeFiles) {
                if (!this.apiKeyMatch(file, meta)) continue;
                return true;
            }
            return false;
        }).collect(Collectors.toList());
    }

    private List<Document> sortDocumentsDeterministically(List<Document> docs) {
        docs.sort(Comparator.comparingDouble(MercenaryController::getDocumentScore).reversed().thenComparing(MercenaryController::getDocumentSource, String.CASE_INSENSITIVE_ORDER));
        return docs;
    }

    /**
     * Boost document scores for chunks that contain important keyword phrases from the query.
     * This ensures that documents with exact or near-exact matches rank higher than those
     * with only semantic similarity.
     */
    private static void boostKeywordMatches(List<Document> docs, String query) {
        Set<String> stopWords = BOOST_STOP_WORDS;
        if (docs == null || docs.isEmpty() || query == null) return;

        String lowerQuery = query.toLowerCase(Locale.ROOT);
        // Extract meaningful phrase pairs (adjacent non-stop words)
        String[] words = lowerQuery.split("\\s+");
        List<String> phrases = new ArrayList<>();
        for (int i = 0; i < words.length - 1; i++) {
            String w1 = words[i];
            String w2 = words[i + 1];
            if (!stopWords.contains(w1) && !stopWords.contains(w2) && w1.length() >= 3 && w2.length() >= 3) {
                phrases.add(w1 + " " + w2);  // e.g., "executive sponsor"
            }
        }
        // Also add single important words
        List<String> importantWords = new ArrayList<>();
        for (String w : words) {
            if (!stopWords.contains(w) && w.length() >= 4) {
                importantWords.add(w);
            }
        }

        for (Document doc : docs) {
            String content = doc.getContent().toLowerCase(Locale.ROOT);
            double boost = 0.0;

            // Big boost for exact phrase matches
            for (String phrase : phrases) {
                if (content.contains(phrase)) {
                    boost += 0.5;  // Significant boost for phrase match
                    log.debug("Keyword boost +0.5 for phrase '{}' in doc", phrase);
                }
            }

            // Smaller boost for individual keyword matches
            int wordMatches = 0;
            for (String word : importantWords) {
                if (content.contains(word)) {
                    wordMatches++;
                }
            }
            if (importantWords.size() > 0) {
                double wordBoost = 0.2 * ((double) wordMatches / importantWords.size());
                boost += wordBoost;
            }

            // Apply boost to score
            if (boost > 0) {
                Object existingScore = doc.getMetadata().get("score");
                double currentScore = 0.0;
                if (existingScore != null) {
                    try {
                        currentScore = Double.parseDouble(existingScore.toString());
                    } catch (NumberFormatException ignored) {}
                }
                doc.getMetadata().put("score", currentScore + boost);
            }
        }
    }

    private static double getDocumentScore(Document doc) {
        if (doc == null) {
            return 0.0;
        }
        Object score = doc.getMetadata().get("score");
        if (score == null) {
            score = doc.getMetadata().get("similarity");
        }
        if (score == null) {
            score = doc.getMetadata().get("relevance");
        }
        if (score == null) {
            score = doc.getMetadata().get("confidence");
        }
        try {
            return score != null ? Double.parseDouble(score.toString()) : 0.0;
        }
        catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static String getDocumentSource(Document doc) {
        if (doc == null) {
            return "";
        }
        Object src = doc.getMetadata().get("source");
        if (src == null) {
            src = doc.getMetadata().get("filename");
        }
        return src != null ? src.toString() : "";
    }

    private boolean isFilenameInScope(String filename, List<String> activeFiles) {
        if (activeFiles == null || activeFiles.isEmpty()) {
            return true;
        }
        if (filename == null) {
            return false;
        }
        String targetLower = filename.toLowerCase();
        for (String file : activeFiles) {
            if (file == null) continue;
            String fileLower = file.toLowerCase();
            if (targetLower.equals(fileLower)) {
                return true;
            }
            if (targetLower.endsWith("/" + fileLower) || targetLower.endsWith("\\" + fileLower)) {
                return true;
            }
            if (!targetLower.contains(fileLower)) continue;
            return true;
        }
        return false;
    }

    private boolean apiKeyMatch(String targetName, Map<String, Object> meta) {
        return DocumentMetadataUtils.apiKeyMatch(targetName, meta);
    }

    private boolean isPromptInjection(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        PromptGuardrailService.GuardrailResult result = this.guardrailService.analyze(query);
        return result.blocked();
    }

    private record RetrievalContext(List<Document> textDocuments, String globalContext, List<Document> visualDocuments, List<MegaRagService.CrossModalEdge> crossModalEdges, List<String> strategies, Set<ModalityRouter.ModalityTarget> modalities) {
        boolean hasEvidence() {
            return (this.textDocuments != null && !this.textDocuments.isEmpty()) || (this.visualDocuments != null && !this.visualDocuments.isEmpty());
        }
    }

    public record TelemetryResponse(int documentCount, int queryCount, long avgLatencyMs, boolean dbOnline, boolean llmOnline) {
    }

    public record UserContextResponse(String displayName, String clearance, List<String> allowedSectors, boolean isAdmin, String edition) {
    }

    public record InspectResponse(String content, List<String> highlights, boolean redacted, int redactionCount) {
    }

}
