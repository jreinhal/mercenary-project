package com.jreinhal.mercenary.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.config.SectorConfig;
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
import com.jreinhal.mercenary.util.FilterExpressionBuilder;
import com.jreinhal.mercenary.util.LogSanitizer;
import com.jreinhal.mercenary.constant.StopWords;
import com.jreinhal.mercenary.util.DocumentMetadataUtils;
import jakarta.servlet.http.HttpServletRequest;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RagOrchestrationService {
    private static final Logger log = LoggerFactory.getLogger(RagOrchestrationService.class);
    private static final int MAX_ACTIVE_FILES = 25;
    private static final String DOC_SEPARATOR = "\n\n---\n\n";
    private static final Set<String> BOOST_STOP_WORDS = StopWords.QUERY_BOOST;
    private final ChatClient chatClient;
    private final VectorStore vectorStore;
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
    private final ConversationMemoryService conversationMemoryService;
    private final SessionPersistenceService sessionPersistenceService;
    private final AtomicInteger queryCount = new AtomicInteger(0);
    private final AtomicLong totalLatencyMs = new AtomicLong(0L);
    private final Cache<String, String> secureDocCache;
    private final OllamaOptions llmOptions;
    private static final String NO_RELEVANT_RECORDS = "No relevant records found.";
    private static final Pattern STRICT_CITATION_PATTERN = Pattern.compile("\\[(?:Citation:\\s*)?(?:IMAGE:\\s*)?[^\\]]+\\.(pdf|txt|md|csv|xlsx|xls|png|jpg|jpeg|gif|tif|tiff|bmp)\\]", 2);
    private static final Pattern METRIC_HINT_PATTERN = Pattern.compile("\\b(metric|metrics|performance|availability|uptime|latency|sla|kpi|mttd|mttr|throughput|error rate|response time|accuracy|precision|recall|f1|cost|risk|budget|revenue|expense|income|profit|loss|spend|spending|amount|total|price|value|rate|percentage|count|number|quantity|allocation|funding|compliance)\\b", 2);
    private static final Pattern NAME_CONTEXT_PATTERN = Pattern.compile("\\b(sponsor|author|lead|director|manager|officer|chief|head|principal|coordinator|owner|contact|prepared by|reviewed by|approved by|submitted by)\\s*:?\\s*", 2);
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("\\d");
    private static final Pattern RELATIONSHIP_PATTERN = Pattern.compile("\\b(relationship|relate|comparison|compare|versus|between|difference|differ|impact|effect|align|alignment|correlat|dependency|tradeoff|link)\\b", 2);
    private static final List<Pattern> NO_INFO_PATTERNS = List.of(Pattern.compile("no relevant records found", 2), Pattern.compile("no relevant (?:information|data|documents)", 2), Pattern.compile("no specific (?:information|data|metrics)", 2), Pattern.compile("no internal records", 2), Pattern.compile("no information (?:available|found)", 2), Pattern.compile("unable to find", 2), Pattern.compile("couldn'?t find", 2), Pattern.compile("do not contain any (?:information|data|metrics)", 2), Pattern.compile("not mentioned in (?:the )?documents", 2));
    @Value("${sentinel.llm.timeout-seconds:60}")
    private int llmTimeoutSeconds;
    @Value("${sentinel.rag.max-context-chars:16000}")
    private int maxContextChars;
    @Value("${sentinel.rag.max-doc-chars:2500}")
    private int maxDocChars;
    @Value("${sentinel.rag.max-overview-chars:3000}")
    private int maxOverviewChars;
    @Value("${sentinel.rag.max-docs:16}")
    private int maxDocs;

    public RagOrchestrationService(ChatClient.Builder builder, VectorStore vectorStore, AuditService auditService, QueryDecompositionService queryDecompositionService, ReasoningTracer reasoningTracer, QuCoRagService quCoRagService, AdaptiveRagService adaptiveRagService, RewriteService rewriteService, RagPartService ragPartService, HybridRagService hybridRagService, HiFiRagService hiFiRagService, MiARagService miARagService, MegaRagService megaRagService, HGMemQueryEngine hgMemQueryEngine, AgenticRagOrchestrator agenticRagOrchestrator, BidirectionalRagService bidirectionalRagService, ModalityRouter modalityRouter, SectorConfig sectorConfig, PromptGuardrailService guardrailService, ConversationMemoryService conversationMemoryService, SessionPersistenceService sessionPersistenceService, Cache<String, String> secureDocCache,
                                  @Value(value="${spring.ai.ollama.chat.options.model:llama3.1:8b}") String llmModel,
                                  @Value(value="${spring.ai.ollama.chat.options.temperature:0.0}") double llmTemperature,
                                  @Value(value="${spring.ai.ollama.chat.options.num-predict:256}") int llmNumPredict) {
        this.chatClient = builder.defaultFunctions(new String[]{"calculator", "currentDate"}).build();
        this.vectorStore = vectorStore;
        this.sectorConfig = sectorConfig;
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
        this.conversationMemoryService = conversationMemoryService;
        this.sessionPersistenceService = sessionPersistenceService;
        this.secureDocCache = secureDocCache;
        this.llmOptions = OllamaOptions.create()
                .withModel(llmModel)
                .withTemperature(llmTemperature)
                .withNumPredict(Integer.valueOf(llmNumPredict));
        log.info("=== LLM Configuration ===");
        log.info("  Model: {}", this.llmOptions.getModel());
        log.info("  Temperature: {}", this.llmOptions.getTemperature());
        log.info("  Max Tokens (num_predict): {}", this.llmOptions.getNumPredict());
        log.info("  Ollama Base URL: {}", System.getProperty("spring.ai.ollama.base-url", "http://localhost:11434"));
        log.info("=========================");
    }

    public int getQueryCount() {
        return this.queryCount.get();
    }

    public long getAverageLatencyMs() {
        int qCount = this.queryCount.get();
        if (qCount == 0) {
            return 0L;
        }
        return this.totalLatencyMs.get() / (long) qCount;
    }
    public String ask(String query, String dept, List<String> fileParams, String filesParam, HttpServletRequest request) {
        Department department;
        User user = SecurityContext.getCurrentUser();
        try {
            department = Department.valueOf(dept.toUpperCase());
        }
        catch (IllegalArgumentException e) {
            return "INVALID SECTOR: " + dept;
        }
        if (user == null) {
            this.auditService.logAccessDenied(null, "/api/ask", "Unauthenticated access attempt", request);
            return "ACCESS DENIED: Authentication required.";
        }
        if (!user.hasPermission(UserRole.Permission.QUERY)) {
            this.auditService.logAccessDenied(user, "/api/ask", "Missing QUERY permission", request);
            return "ACCESS DENIED: Insufficient permissions for queries.";
        }
        if (this.sectorConfig.requiresElevatedClearance(department) && !user.canAccessClassification(department.getRequiredClearance())) {
            this.auditService.logAccessDenied(user, "/api/ask", "Insufficient clearance for " + department.name(), request);
            return "ACCESS DENIED: Insufficient clearance for " + department.name() + " sector.";
        }
        if (!user.canAccessSector(department)) {
            this.auditService.logAccessDenied(user, "/api/ask", "Not authorized for sector " + department.name(), request);
            return "ACCESS DENIED: You are not authorized to access the " + department.name() + " sector.";
        }
        List<String> activeFiles = this.parseActiveFiles(fileParams, filesParam);
        try {
            String rescued;
            String response;
            List<String> subQueries;
            boolean isCompoundQuery;
            long start = System.currentTimeMillis();
            if (this.isPromptInjection(query)) {
                if (user != null) {
                    this.auditService.logPromptInjection(user, query, request);
                }
                return "SECURITY ALERT: Indirect Prompt Injection Detected. Access Denied.";
            }
            if (this.isTimeQuery(query)) {
                String response2 = this.buildSystemTimeResponse();
                long timeTaken = System.currentTimeMillis() - start;
                this.totalLatencyMs.addAndGet(timeTaken);
                this.queryCount.incrementAndGet();
                if (user != null) {
                    this.auditService.logQuery(user, query, department, response2, request);
                }
                return response2;
            }
            QuCoRagService.UncertaintyResult uncertaintyResult = this.quCoRagService.analyzeQueryUncertainty(query);
            boolean highUncertainty = this.quCoRagService.shouldTriggerRetrieval(uncertaintyResult.uncertaintyScore());
            if (highUncertainty) {
                log.info("QuCo-RAG: High uncertainty detected ({}), expanding retrieval", String.format("%.3f", uncertaintyResult.uncertaintyScore()));
            }
            AdaptiveRagService.RoutingResult routing = this.adaptiveRagService.route(query);
            boolean bl = isCompoundQuery = (subQueries = this.queryDecompositionService.decompose(query)).size() > 1;
            if (isCompoundQuery) {
                log.info("Compound query detected. Decomposed into {} sub-queries.", subQueries.size());
            }
            LinkedHashSet<Document> allDocs = new LinkedHashSet<>();
            ArrayList<Document> visualDocs = new ArrayList<>();
            ArrayList<MegaRagService.CrossModalEdge> crossModalEdges = new ArrayList<>();
            ArrayList<String> globalContexts = new ArrayList<>();
            ArrayList<String> retrievalStrategies = new ArrayList<>();
            for (String subQuery : subQueries) {
                RetrievalContext context = this.retrieveContext(subQuery, dept, activeFiles, routing, highUncertainty, false);
                if (context.textDocuments().isEmpty() && context.visualDocuments().isEmpty()) {
                    log.info("CRAG: Retrieval failed for '{}'. Initiating Corrective Loop.", subQuery);
                    String rewritten = this.rewriteService.rewriteQuery(subQuery);
                    if (!rewritten.equals(subQuery)) {
                        context = this.retrieveContext(rewritten, dept, activeFiles, routing, highUncertainty, false);
                        log.info("CRAG: Retry with '{}' found {} docs.", rewritten, context.textDocuments().size());
                    }
                }
                allDocs.addAll(context.textDocuments());
                visualDocs.addAll(context.visualDocuments());
                crossModalEdges.addAll(context.crossModalEdges());
                if (context.globalContext() != null && !context.globalContext().isBlank()) {
                    globalContexts.add(context.globalContext());
                }
                retrievalStrategies.addAll(context.strategies());
            }
            ArrayList<Document> rawDocs = new ArrayList<Document>(allDocs);
            List<Document> orderedDocs = this.sortDocumentsDeterministically(rawDocs);
            log.info("Retrieved {} documents for query {}", rawDocs.size(), LogSanitizer.querySummary(query));
            rawDocs.forEach(doc -> log.info("  - Source: {}, Content preview: {}", doc.getMetadata().get("source"), doc.getContent().substring(0, Math.min(50, doc.getContent().length()))));
            List<Document> topDocs = orderedDocs.stream().limit(15L).toList();
            String globalContext = this.mergeGlobalContexts(globalContexts);
            String information = this.buildInformation(topDocs, globalContext);
            boolean useVisual = this.megaRagService != null && this.megaRagService.isEnabled() && !visualDocs.isEmpty();
            String systemText = "";
            systemText = information.isEmpty() ? "You are a helpful assistant. No documents are available. Respond: 'No internal records found for this query.'" : String.format(
                "You are a helpful assistant that answers questions based on the provided documents.\n\n" +
                "INSTRUCTIONS:\n" +
                "1. Read the documents below carefully\n" +
                "2. If an OVERVIEW section is present, use it for background only and do NOT cite it\n" +
                "3. Answer the question using ONLY information from the DOCUMENTS section\n" +
                "4. Include the exact numbers, names, dates, or facts as written in the documents\n" +
                "5. Cite sources using [filename] after each fact\n" +
                "6. If the answer is not in the documents, say \"No relevant records found.\"\n\n" +
                "DOCUMENTS:\n%s\n\n" +
                "Answer the user's question based on the documents above.", information);
            String systemMessage = systemText.replace("{information}", information);
            boolean llmSuccess = true;
            try {
                if (useVisual) {
                    String visualResponse = this.megaRagService.generateWithVisualContext(query, topDocs, visualDocs);
                    response = visualResponse != null ? cleanLlmResponse(visualResponse) : "";
                } else {
                    String sysMsg = systemMessage.replace("{", "[").replace("}", "]");
                    String userQuery = query.replace("{", "[").replace("}", "]");
                    String rawResponse = CompletableFuture.supplyAsync(() -> this.chatClient.prompt().system(sysMsg).user(userQuery).options((ChatOptions)this.llmOptions).call().content()).get(llmTimeoutSeconds, TimeUnit.SECONDS);
                    log.info("LLM RAW RESPONSE for /ask: {}", rawResponse != null ? rawResponse.substring(0, Math.min(500, rawResponse.length())) : "null");
                    response = cleanLlmResponse(rawResponse);
                }
            }
            catch (TimeoutException te) {
                llmSuccess = false;
                log.warn("LLM response timed out after {}s for query {}", llmTimeoutSeconds, LogSanitizer.querySummary(query));
                response = "**Response Timeout**\n\nThe system is taking longer than expected. Please try:\n- Simplifying your question\n- Asking about a more specific topic\n- Trying again in a moment";
            }
            catch (Exception llmError) {
                llmSuccess = false;
                log.error("LLM Generation Failed (Offline/Misconfigured). Generating Simulation Response.", (Throwable)llmError);
                StringBuilder sim = new StringBuilder("**System Offline Mode**\n\n");
                sim.append("The AI model is currently unavailable. Here are relevant excerpts from your documents:\n\n");
                int docNum = 1;
                for (Document d : topDocs) {
                    String src = String.valueOf(d.getMetadata().getOrDefault("source", "Unknown"));
                    String preview = d.getContent().substring(0, Math.min(300, d.getContent().length()))
                        .replace("|", " ")
                        .replace("---", "")
                        .replaceAll("\\s+", " ")
                        .trim();
                    sim.append(docNum++).append(". **").append(src).append("**\n\n");
                    sim.append("   ").append(preview).append("...\n\n");
                }
                if (topDocs.isEmpty()) {
                    sim.append("No relevant records found in the active context.");
                }
                response = sim.toString();
            }
            boolean isTimeoutResponse = response != null && response.toLowerCase(Locale.ROOT).contains("response timeout");
            boolean hasEvidence = RagOrchestrationService.hasRelevantEvidence(topDocs, query) || !visualDocs.isEmpty();
            int citationCount = RagOrchestrationService.countCitations(response);
            boolean rescueApplied = false;
            boolean excerptFallbackApplied = false;
            if (llmSuccess && citationCount == 0 && hasEvidence && !isTimeoutResponse && !NO_RELEVANT_RECORDS.equals(rescued = RagOrchestrationService.buildExtractiveResponse(topDocs, query))) {
                response = rescued;
                rescueApplied = true;
                citationCount = RagOrchestrationService.countCitations(response);
            }
            boolean answerable = hasEvidence && !isTimeoutResponse;
            boolean usedAnswerabilityGate = false;
            if (!answerable || RagOrchestrationService.isNoInfoResponse(response)) {
                if (!isTimeoutResponse) {
                    String fallback = RagOrchestrationService.buildEvidenceFallbackResponse(topDocs, query);
                    if (!NO_RELEVANT_RECORDS.equals(fallback)) {
                        response = fallback;
                        excerptFallbackApplied = true;
                        citationCount = RagOrchestrationService.countCitations(response);
                    } else {
                        response = NO_RELEVANT_RECORDS;
                        usedAnswerabilityGate = true;
                        citationCount = 0;
                    }
                }
                answerable = false;
            } else if (llmSuccess && citationCount == 0) {
                response = NO_RELEVANT_RECORDS;
                answerable = false;
                usedAnswerabilityGate = true;
                citationCount = 0;
            }
            if (usedAnswerabilityGate) {
                log.info("Answerability gate applied (citations={}, answerable=false) for query {}", citationCount, LogSanitizer.querySummary(query));
            } else if (rescueApplied) {
                log.info("Citation rescue applied (extractive fallback) for query {}", LogSanitizer.querySummary(query));
            } else if (excerptFallbackApplied) {
                log.info("Excerpt fallback applied (no direct answer) for query {}", LogSanitizer.querySummary(query));
            }
            // Skip hallucination check for extractive responses (they come directly from documents)
            boolean skipHallucinationCheckAsk = rescueApplied || excerptFallbackApplied;
            if (!skipHallucinationCheckAsk) {
                QuCoRagService.HallucinationResult hallucinationResult = this.quCoRagService.detectHallucinationRisk(response, query);
                if (hallucinationResult.isHighRisk()) {
                    log.warn("QuCo-RAG: High hallucination risk detected (risk={:.3f}). Flagged entities: {}", hallucinationResult.riskScore(), hallucinationResult.flaggedEntities());
                    RetrievalContext retryContext = this.retrieveContext(query, dept, activeFiles, routing, true, false);
                    if (!retryContext.textDocuments().isEmpty()) {
                        String retryInfo = this.buildInformation(retryContext.textDocuments(), retryContext.globalContext());
                        String retrySystem = retryInfo.isEmpty() ? "You are a helpful assistant. No documents are available. Respond: 'No internal records found for this query.'" : String.format(
                            "You are a helpful assistant that answers questions based on the provided documents.\n\n" +
                            "INSTRUCTIONS:\n" +
                            "1. Read the documents below carefully\n" +
                            "2. Answer the question using ONLY information from these documents\n" +
                            "3. Cite sources using [filename] after each fact\n" +
                            "4. If the answer is not in the documents, say \"No relevant records found.\"\n\n" +
                            "DOCUMENTS:\n%s\n\n" +
                            "Answer the user's question based on the documents above.", retryInfo);
                        String retrySystemMessage = retrySystem.replace("{information}", retryInfo);
                        try {
                            String sysMsg = retrySystemMessage.replace("{", "[").replace("}", "]");
                            String userQuery = query.replace("{", "[").replace("}", "]");
                            String rawRetry = CompletableFuture.supplyAsync(() -> this.chatClient.prompt().system(sysMsg).user(userQuery).options((ChatOptions)this.llmOptions).call().content()).get(llmTimeoutSeconds, TimeUnit.SECONDS);
                            response = cleanLlmResponse(rawRetry);
                            citationCount = RagOrchestrationService.countCitations(response);
                            if (citationCount == 0) {
                                response = NO_RELEVANT_RECORDS;
                            }
                        }
                        catch (Exception retryError) {
                            log.warn("QuCo-RAG retry generation failed: {}", retryError.getMessage());
                        }
                    } else {
                        response = NO_RELEVANT_RECORDS;
                    }
                }
            } else {
                log.debug("QuCo-RAG: Skipping hallucination check for extractive response");
            }
            if (this.bidirectionalRagService != null && this.bidirectionalRagService.isEnabled() && hasEvidence) {
                String userId = user != null ? user.getUsername() : "unknown";
                this.bidirectionalRagService.validateAndLearn(query, response, topDocs, department.name(), userId);
            }
            long timeTaken = System.currentTimeMillis() - start;
            this.totalLatencyMs.addAndGet(timeTaken);
            this.queryCount.incrementAndGet();
            if (user != null) {
                this.auditService.logQuery(user, query, department, response, request);
            }
            return response;
        }
        catch (Exception e) {
            log.error("Error in /ask endpoint", (Throwable)e);
            throw e;
        }
    }
    public EnhancedAskResponse askEnhanced(String query, String dept, List<String> fileParams, String filesParam, String sessionId, boolean deepAnalysis, HttpServletRequest request) {
        Department department;
        User user = SecurityContext.getCurrentUser();
        try {
            department = Department.valueOf(dept.toUpperCase());
        }
        catch (IllegalArgumentException e) {
            return new EnhancedAskResponse("INVALID SECTOR: " + dept, List.of(), List.of(), Map.of(), null);
        }
        if (user == null) {
            this.auditService.logAccessDenied(null, "/api/ask/enhanced", "Unauthenticated access attempt", request);
            return new EnhancedAskResponse("ACCESS DENIED: Authentication required.", List.of(), List.of(), Map.of(), null);
        }
        if (!user.hasPermission(UserRole.Permission.QUERY)) {
            this.auditService.logAccessDenied(user, "/api/ask/enhanced", "Missing QUERY permission", request);
            return new EnhancedAskResponse("ACCESS DENIED: Insufficient permissions.", List.of(), List.of(), Map.of(), null);
        }
        if (this.sectorConfig.requiresElevatedClearance(department) && !user.canAccessClassification(department.getRequiredClearance())) {
            this.auditService.logAccessDenied(user, "/api/ask/enhanced", "Insufficient clearance for " + department.name(), request);
            return new EnhancedAskResponse("ACCESS DENIED: Insufficient clearance.", List.of(), List.of(), Map.of(), null);
        }
        if (!user.canAccessSector(department)) {
            this.auditService.logAccessDenied(user, "/api/ask/enhanced", "Not authorized for sector " + department.name(), request);
            return new EnhancedAskResponse("ACCESS DENIED: Not authorized for " + department.name() + " sector.", List.of(), List.of(), Map.of(), null);
        }
        List<String> activeFiles = this.parseActiveFiles(fileParams, filesParam);
        String effectiveSessionId = sessionId;
        String effectiveQuery = query;
        if (sessionId != null && !sessionId.isBlank()) {
            try {
                this.sessionPersistenceService.touchSession(user.getId(), sessionId, dept);
                this.conversationMemoryService.saveUserMessage(user.getId(), sessionId, query);
                if (this.conversationMemoryService.isFollowUp(query)) {
                    ConversationMemoryService.ConversationContext context = this.conversationMemoryService.getContext(user.getId(), sessionId);
                    effectiveQuery = this.conversationMemoryService.expandFollowUp(query, context);
                    log.debug("Expanded follow-up query for session {}", sessionId);
                }
            }
            catch (Exception e) {
                log.warn("Session operation failed, continuing without session: {}", e.getMessage());
                effectiveSessionId = null;
            }
        }
        ReasoningTrace trace = this.reasoningTracer.startTrace(query, dept);
        try {
            Object source;
            String rescued;
            String response;
            long start = System.currentTimeMillis();
            long stepStart = System.currentTimeMillis();
            if (this.isPromptInjection(query)) {
                this.reasoningTracer.addStep(ReasoningStep.StepType.SECURITY_CHECK, "Security Scan", "BLOCKED: Prompt injection detected", System.currentTimeMillis() - stepStart, Map.of("blocked", true, "reason", "injection_detected"));
                this.auditService.logPromptInjection(user, query, request);
                ReasoningTrace completed = this.reasoningTracer.endTrace();
                return new EnhancedAskResponse("SECURITY ALERT: Indirect Prompt Injection Detected.", completed != null ? completed.getStepsAsMaps() : List.of(), List.of(), Map.of(), completed != null ? completed.getTraceId() : null);
            }
            this.reasoningTracer.addStep(ReasoningStep.StepType.SECURITY_CHECK, "Security Scan", "Query passed injection detection", System.currentTimeMillis() - stepStart, Map.of("blocked", false));
            if (this.isTimeQuery(query)) {
                stepStart = System.currentTimeMillis();
                String response2 = this.buildSystemTimeResponse();
                this.reasoningTracer.addStep(ReasoningStep.StepType.QUERY_ANALYSIS, "System Time", "Answered using local system clock", System.currentTimeMillis() - stepStart, Map.of("timezone", ZonedDateTime.now().getZone().toString()));
                long timeTaken = System.currentTimeMillis() - start;
                this.totalLatencyMs.addAndGet(timeTaken);
                this.queryCount.incrementAndGet();
                ReasoningTrace completedTrace = this.reasoningTracer.endTrace();
                this.auditService.logQuery(user, query, department, response2, request);
                return new EnhancedAskResponse(response2, completedTrace != null ? completedTrace.getStepsAsMaps() : List.of(), List.of(), Map.of("latencyMs", timeTaken, "routingDecision", "SYSTEM_TIME", "routingReason", "Local system clock", "documentsRetrieved", 0, "subQueries", 1, "activeFileCount", activeFiles.size()), completedTrace != null ? completedTrace.getTraceId() : null);
            }
            stepStart = System.currentTimeMillis();
            AdaptiveRagService.RoutingResult routingResult = this.adaptiveRagService.route(query);
            AdaptiveRagService.RoutingDecision routingDecision = routingResult.decision();
            if (this.adaptiveRagService.shouldSkipRetrieval(routingDecision)) {
                String directResponse;
                log.info("AdaptiveRAG: ZeroHop path - skipping retrieval for conversational query");
                try {
                    String userQuery = query.replace("{", "[").replace("}", "]");
                    String rawResponse = CompletableFuture.supplyAsync(() -> this.chatClient.prompt().system("You are SENTINEL, an intelligence assistant. Respond helpfully and concisely.").user(userQuery).options((ChatOptions)this.llmOptions).call().content()).get(llmTimeoutSeconds, TimeUnit.SECONDS);
                    directResponse = cleanLlmResponse(rawResponse);
                }
                catch (TimeoutException te) {
                    log.warn("LLM response timed out after {}s for ZeroHop query {}", llmTimeoutSeconds, LogSanitizer.querySummary(query));
                    directResponse = "**Response Timeout**\n\nThe system is taking longer than expected. Please try again.";
                }
                catch (Exception llmError) {
                    directResponse = "I apologize, but I'm unable to process your request at the moment. Please try rephrasing your question.";
                }
                long timeTaken = System.currentTimeMillis() - start;
                this.totalLatencyMs.addAndGet(timeTaken);
                this.queryCount.incrementAndGet();
                ReasoningTrace completedTrace = this.reasoningTracer.endTrace();
                this.auditService.logQuery(user, query, department, directResponse, request);
                return new EnhancedAskResponse(directResponse, completedTrace != null ? completedTrace.getStepsAsMaps() : List.of(), List.of(), Map.of("latencyMs", timeTaken, "routingDecision", "NO_RETRIEVAL", "zeroHop", true, "activeFileCount", activeFiles.size()), completedTrace != null ? completedTrace.getTraceId() : null);
            }
            stepStart = System.currentTimeMillis();
            List<String> subQueries = this.queryDecompositionService.decompose(query);
            boolean isCompoundQuery = subQueries.size() > 1;
            this.reasoningTracer.addStep(ReasoningStep.StepType.QUERY_DECOMPOSITION, "Query Analysis", (String)(isCompoundQuery ? "Decomposed into " + subQueries.size() + " sub-queries" : "Single query detected"), System.currentTimeMillis() - stepStart, Map.of("subQueries", subQueries, "isCompound", isCompoundQuery));
            stepStart = System.currentTimeMillis();
            String scopeDetail = activeFiles.isEmpty() ? "No file scope filter applied" : "Restricting retrieval to " + activeFiles.size() + " uploaded file(s)";
            this.reasoningTracer.addStep(ReasoningStep.StepType.FILTERING, "Scope Filter", scopeDetail, System.currentTimeMillis() - stepStart, Map.of("activeFiles", activeFiles, "fileCount", activeFiles.size()));
            stepStart = System.currentTimeMillis();
            QuCoRagService.UncertaintyResult uncertaintyResult = this.quCoRagService.analyzeQueryUncertainty(query);
            boolean highUncertainty = this.quCoRagService.shouldTriggerRetrieval(uncertaintyResult.uncertaintyScore());
            this.reasoningTracer.addStep(ReasoningStep.StepType.UNCERTAINTY_ANALYSIS, "QuCo-RAG Uncertainty", highUncertainty ? "High uncertainty; expanding retrieval" : "Low uncertainty", System.currentTimeMillis() - stepStart, Map.of("uncertaintyScore", uncertaintyResult.uncertaintyScore(), "highUncertainty", highUncertainty));

            LinkedHashSet<Document> allDocs = new LinkedHashSet<>();
            ArrayList<Document> visualDocs = new ArrayList<>();
            ArrayList<String> globalContexts = new ArrayList<>();
            ArrayList<String> retrievalStrategies = new ArrayList<>();
            for (int i = 0; i < subQueries.size(); ++i) {
                String subQuery = subQueries.get(i);
                stepStart = System.currentTimeMillis();
                RetrievalContext context = this.retrieveContext(subQuery, dept, activeFiles, routingResult, highUncertainty, deepAnalysis);
                if (context.textDocuments().isEmpty() && context.visualDocuments().isEmpty()) {
                    log.info("CRAG: Retrieval failed for '{}'. Initiating Corrective Loop.", subQuery);
                    String rewritten = this.rewriteService.rewriteQuery(subQuery);
                    if (!rewritten.equals(subQuery)) {
                        context = this.retrieveContext(rewritten, dept, activeFiles, routingResult, highUncertainty, deepAnalysis);
                    }
                }
                allDocs.addAll(context.textDocuments());
                visualDocs.addAll(context.visualDocuments());
                if (context.globalContext() != null && !context.globalContext().isBlank()) {
                    globalContexts.add(context.globalContext());
                }
                retrievalStrategies.addAll(context.strategies());
                this.reasoningTracer.addStep(ReasoningStep.StepType.RETRIEVAL, "Retrieval" + (String)(isCompoundQuery ? " [" + (i + 1) + "/" + subQueries.size() + "]" : ""), "Retrieved " + context.textDocuments().size() + " text docs and " + context.visualDocuments().size() + " visual docs", System.currentTimeMillis() - stepStart, Map.of("query", subQuery, "textDocs", context.textDocuments().size(), "visualDocs", context.visualDocuments().size(), "strategies", context.strategies()));
            }
            ArrayList<Document> rawDocs = new ArrayList<Document>(allDocs);
            stepStart = System.currentTimeMillis();
            List<Document> orderedDocs = this.sortDocumentsDeterministically(rawDocs);
            List<Document> topDocs = orderedDocs.stream().limit(15L).toList();
            List<String> docSources = topDocs.stream().map(doc -> {
                Object src = doc.getMetadata().get("source");
                return src != null ? src.toString() : "unknown";
            }).distinct().toList();
            if (!visualDocs.isEmpty()) {
                List<String> visualSources = visualDocs.stream().map(doc -> {
                    Object src = doc.getMetadata().get("source");
                    return src != null ? src.toString() : "unknown";
                }).distinct().toList();
                ArrayList<String> mergedSources = new ArrayList<>(docSources);
                for (String vs : visualSources) {
                    if (!mergedSources.contains(vs)) {
                        mergedSources.add(vs);
                    }
                }
                docSources = mergedSources;
            }
            this.reasoningTracer.addStep(ReasoningStep.StepType.RERANKING, "Document Filtering", "Selected top " + topDocs.size() + " documents from " + rawDocs.size() + " candidates", System.currentTimeMillis() - stepStart, Map.of("totalCandidates", rawDocs.size(), "selected", topDocs.size(), "sources", docSources, "strategies", retrievalStrategies));
            stepStart = System.currentTimeMillis();
            String globalContext = this.mergeGlobalContexts(globalContexts);
            String information = this.buildInformation(topDocs, globalContext);
            int contextLength = information.length();
            this.reasoningTracer.addStep(ReasoningStep.StepType.CONTEXT_ASSEMBLY, "Context Assembly", "Assembled " + contextLength + " characters from " + topDocs.size() + " documents", System.currentTimeMillis() - stepStart, Map.of("contextLength", contextLength, "documentCount", topDocs.size()));
            stepStart = System.currentTimeMillis();
            boolean useVisual = this.megaRagService != null && this.megaRagService.isEnabled() && !visualDocs.isEmpty();
            String systemText = information.isEmpty() ? "You are SENTINEL. No documents are available. Respond: 'No internal records found for this query.'" : String.format(
                "You are SENTINEL, an advanced intelligence analyst for %s sector.\n\n" +
                "INSTRUCTIONS:\n" +
                "- Analyze the provided source documents carefully\n" +
                "- If an OVERVIEW section is present, use it for background only and do NOT cite it\n" +
                "- Base your response ONLY on the provided documents\n" +
                "- Cite each source immediately after each fact using [filename] format\n\n" +
                "CONSTRAINTS:\n" +
                "- Never fabricate or guess filenames\n" +
                "- Only cite files that appear in the DOCUMENTS section below\n" +
                "- If information is not in the documents, respond: \"No relevant records found.\"\n\n" +
                "DOCUMENTS:\n%s\n", dept, information);
            String systemMessage = systemText.replace("{information}", information);
            boolean llmSuccess = true;
            try {
                if (useVisual) {
                    String visualResponse = this.megaRagService.generateWithVisualContext(query, topDocs, visualDocs);
                    response = visualResponse != null ? cleanLlmResponse(visualResponse) : "";
                } else {
                    String sysMsg = systemMessage.replace("{", "[").replace("}", "]");
                    String userQuery = query.replace("{", "[").replace("}", "]");
                    String rawResponse = CompletableFuture.supplyAsync(() -> this.chatClient.prompt().system(sysMsg).user(userQuery).options((ChatOptions)this.llmOptions).call().content()).get(llmTimeoutSeconds, TimeUnit.SECONDS);
                    response = cleanLlmResponse(rawResponse);
                }
            }
            catch (TimeoutException te) {
                llmSuccess = false;
                log.warn("LLM response timed out after {}s", llmTimeoutSeconds);
                response = "**Response Timeout**\n\nThe system is taking longer than expected. Please try:\n- Simplifying your question\n- Asking about a more specific topic\n- Trying again in a moment";
            }
            catch (Exception llmError) {
                llmSuccess = false;
                log.error("LLM Generation Failed", (Throwable)llmError);
                StringBuilder sim = new StringBuilder("**System Offline Mode**\n\n");
                sim.append("The AI model is currently unavailable. Here are relevant excerpts from your documents:\n\n");
                int docNum = 1;
                for (Document d : topDocs) {
                    String src = String.valueOf(d.getMetadata().getOrDefault("source", "Unknown"));
                    String preview = d.getContent().substring(0, Math.min(300, d.getContent().length()))
                        .replace("|", " ")
                        .replace("---", "")
                        .replaceAll("\\s+", " ")
                        .trim();
                    sim.append(docNum++).append(". **").append(src).append("**\n\n");
                    sim.append("   ").append(preview).append("...\n\n");
                }
                if (topDocs.isEmpty()) {
                    sim.append("No relevant records found in the active context.");
                }
                response = sim.toString();
            }
            this.reasoningTracer.addStep(ReasoningStep.StepType.LLM_GENERATION, "Response Synthesis", (String)(llmSuccess ? "Generated response (" + response.length() + " chars)" : "Fallback mode (LLM offline)"), System.currentTimeMillis() - stepStart, Map.of("success", llmSuccess, "responseLength", response.length()));
            stepStart = System.currentTimeMillis();
            boolean isTimeoutResponse = response != null && response.toLowerCase(Locale.ROOT).contains("response timeout");
            boolean hasEvidence = RagOrchestrationService.hasRelevantEvidence(topDocs, query) || !visualDocs.isEmpty();
            int citationCount = RagOrchestrationService.countCitations(response);
            boolean rescueApplied = false;
            boolean excerptFallbackApplied = false;
            if (llmSuccess && citationCount == 0 && hasEvidence && !isTimeoutResponse && !NO_RELEVANT_RECORDS.equals(rescued = RagOrchestrationService.buildExtractiveResponse(topDocs, query))) {
                response = rescued;
                rescueApplied = true;
                citationCount = RagOrchestrationService.countCitations(response);
            }
            boolean answerable = hasEvidence && !isTimeoutResponse;
            boolean usedAnswerabilityGate = false;
            if (!answerable || RagOrchestrationService.isNoInfoResponse(response)) {
                if (!isTimeoutResponse) {
                    String fallback = RagOrchestrationService.buildEvidenceFallbackResponse(topDocs, query);
                    if (!NO_RELEVANT_RECORDS.equals(fallback)) {
                        response = fallback;
                        excerptFallbackApplied = true;
                        citationCount = RagOrchestrationService.countCitations(response);
                    } else {
                        response = NO_RELEVANT_RECORDS;
                        usedAnswerabilityGate = true;
                        citationCount = 0;
                    }
                }
                answerable = false;
            } else if (llmSuccess && citationCount == 0) {
                response = NO_RELEVANT_RECORDS;
                answerable = false;
                usedAnswerabilityGate = true;
                citationCount = 0;
            }
            String gateDetail = isTimeoutResponse ? "System response (timeout)" : (rescueApplied ? "Citation rescue applied (extractive evidence)" : (excerptFallbackApplied ? "No direct answer; showing excerpts" : (usedAnswerabilityGate ? "No answer returned (missing citations or no evidence)" : "Answerable with citations")));
            this.reasoningTracer.addStep(ReasoningStep.StepType.CITATION_VERIFICATION, "Answerability Gate", gateDetail, System.currentTimeMillis() - stepStart, Map.of("answerable", answerable, "citationCount", citationCount, "gateApplied", usedAnswerabilityGate, "rescueApplied", rescueApplied, "excerptFallbackApplied", excerptFallbackApplied));
            stepStart = System.currentTimeMillis();
            // Skip hallucination check for extractive responses (they come directly from documents)
            boolean skipHallucinationCheck = rescueApplied || excerptFallbackApplied;
            QuCoRagService.HallucinationResult hallucinationResult = skipHallucinationCheck
                ? new QuCoRagService.HallucinationResult(0.0, List.of(), false)
                : this.quCoRagService.detectHallucinationRisk(response, query);
            boolean hasHallucinationRisk = hallucinationResult.isHighRisk();
            if (hasHallucinationRisk) {
                log.warn("QuCo-RAG: High hallucination risk detected (risk={:.3f}). Flagged entities: {}", hallucinationResult.riskScore(), hallucinationResult.flaggedEntities());
                response = NO_RELEVANT_RECORDS;
                answerable = false;
                citationCount = 0;
            }
            String hallucinationDetail = skipHallucinationCheck
                ? "Skipped (extractive response from documents)"
                : (hasHallucinationRisk
                    ? "High risk detected; response abstained (" + hallucinationResult.flaggedEntities().size() + " novel entities, risk=" + String.format("%.2f", hallucinationResult.riskScore()) + ")"
                    : "Passed (risk=" + String.format("%.2f", hallucinationResult.riskScore()) + ")");
            this.reasoningTracer.addStep(ReasoningStep.StepType.UNCERTAINTY_ANALYSIS, "Hallucination Check", hallucinationDetail, System.currentTimeMillis() - stepStart, Map.of("riskScore", hallucinationResult.riskScore(), "flaggedEntities", hallucinationResult.flaggedEntities(), "isHighRisk", hasHallucinationRisk, "skipped", skipHallucinationCheck));
            if (this.bidirectionalRagService != null && this.bidirectionalRagService.isEnabled() && hasEvidence) {
                String userId = user != null ? user.getUsername() : "unknown";
                this.bidirectionalRagService.validateAndLearn(query, response, topDocs, department.name(), userId);
            }
            long timeTaken = System.currentTimeMillis() - start;
            this.totalLatencyMs.addAndGet(timeTaken);
            this.queryCount.incrementAndGet();
            this.reasoningTracer.addMetric("totalLatencyMs", timeTaken);
            this.reasoningTracer.addMetric("documentsRetrieved", topDocs.size());
            this.reasoningTracer.addMetric("subQueriesProcessed", subQueries.size());
            ReasoningTrace completedTrace = this.reasoningTracer.endTrace();
            this.auditService.logQuery(user, query, department, response, request);
            ArrayList<String> sources = new ArrayList<String>();
            Matcher matcher1 = Pattern.compile("\\[(?:Citation:\\s*)?(?:IMAGE:\\s*)?([^\\]]+\\.(pdf|txt|md|csv|xlsx|xls|png|jpg|jpeg|gif|tif|tiff|bmp))\\]", 2).matcher(response);
            while (matcher1.find()) {
                String source2 = matcher1.group(1).trim();
                if (sources.contains(source2)) continue;
                sources.add(source2);
            }
            Matcher matcher2 = Pattern.compile("\\(([^)]+\\.(pdf|txt|md|csv|xlsx|xls|png|jpg|jpeg|gif|tif|tiff|bmp))\\)", 2).matcher(response);
            while (matcher2.find()) {
                String source3 = matcher2.group(1);
                if (sources.contains(source3)) continue;
                sources.add(source3);
            }
            Matcher matcher3 = Pattern.compile("(?:Citation|Source|filename):\\s*([^\\s,]+\\.(pdf|txt|md|csv|xlsx|xls|png|jpg|jpeg|gif|tif|tiff|bmp))", 2).matcher(response);
            Matcher matcher4 = Pattern.compile("`([^`]+\\.(pdf|txt|md|csv|xlsx|xls|png|jpg|jpeg|gif|tif|tiff|bmp))`", 2).matcher(response);
            while (matcher4.find()) {
                String source4 = matcher4.group(1).trim();
                if (sources.contains(source4)) continue;
                sources.add(source4);
            }
            Matcher matcher5 = Pattern.compile("\\*{1,2}([^*]+\\.(pdf|txt|md|csv|xlsx|xls|png|jpg|jpeg|gif|tif|tiff|bmp))\\*{1,2}", 2).matcher(response);
            while (matcher5.find()) {
                String source5 = matcher5.group(1).trim();
                if (sources.contains(source5)) continue;
                sources.add(source5);
            }
            Matcher matcher6 = Pattern.compile("\"([^\"]+\\.(pdf|txt|md|csv|xlsx|xls|png|jpg|jpeg|gif|tif|tiff|bmp))\"", 2).matcher(response);
            while (matcher6.find()) {
                source = matcher6.group(1).trim();
                if (sources.contains(source)) continue;
                sources.add((String)source);
            }
            while (matcher3.find()) {
                source = matcher3.group(1);
                if (sources.contains(source)) continue;
                sources.add((String)source);
            }
            for (String docSource : docSources) {
                if (sources.contains(docSource)) continue;
                sources.add(docSource);
            }
            log.debug("Final sources list (LLM cited + retrieved): {}", sources);
            LinkedHashMap<String, Object> metrics = new LinkedHashMap<String, Object>();
            metrics.put("latencyMs", timeTaken);
            metrics.put("documentsRetrieved", topDocs.size());
            metrics.put("visualDocuments", visualDocs.size());
            metrics.put("subQueries", subQueries.size());
            metrics.put("llmSuccess", llmSuccess);
            metrics.put("routingDecision", routingDecision.name());
            metrics.put("routingConfidence", routingResult.confidence());
            metrics.put("activeFileCount", activeFiles.size());
            metrics.put("evidenceMatch", hasEvidence);
            metrics.put("answerable", answerable);
            metrics.put("citationCount", citationCount);
            metrics.put("answerabilityGate", usedAnswerabilityGate);
            metrics.put("citationRescue", rescueApplied);
            metrics.put("excerptFallbackApplied", excerptFallbackApplied);
            metrics.put("retrievalStrategies", retrievalStrategies);
            return new EnhancedAskResponse(response, completedTrace != null ? completedTrace.getStepsAsMaps() : List.of(), sources, metrics, completedTrace != null ? completedTrace.getTraceId() : null);
        }
        catch (Exception e) {
            log.error("Error in /ask/enhanced endpoint", (Throwable)e);
            ReasoningTrace errorTrace = this.reasoningTracer.endTrace();
            return new EnhancedAskResponse("An error occurred processing your query. Please try again or contact support. [ERR-1002]", errorTrace != null ? errorTrace.getStepsAsMaps() : List.of(), List.of(), Map.of("errorCode", "ERR-1002"), errorTrace != null ? errorTrace.getTraceId() : null);
        }
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
        if (trimmed.startsWith("[{\"name\"")) {
            log.warn("Detected array function call response, returning fallback");
            return "The system encountered a formatting issue. Please try rephrasing your question.";
        }

        return response;
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

    private static boolean isNoInfoResponse(String response) {
        if (response == null || response.isBlank()) {
            return true;
        }
        String normalized = response.trim();
        for (Pattern pattern : NO_INFO_PATTERNS) {
            if (!pattern.matcher(normalized).find()) continue;
            return true;
        }
        return false;
    }

    private static String buildExtractiveResponse(List<Document> docs, String query) {
        if (docs == null || docs.isEmpty()) {
            return NO_RELEVANT_RECORDS;
        }
        StringBuilder summary = new StringBuilder("Based on the retrieved documents, here are the most relevant excerpts:\n\n");
        int added = 0;
        LinkedHashSet<String> seenSnippets = new LinkedHashSet<String>();
        Set<String> keywords = RagOrchestrationService.buildQueryKeywords(query);
        boolean wantsMetrics = RagOrchestrationService.queryWantsMetrics(query);
        int minKeywordHits = RagOrchestrationService.requiredKeywordHits(query, keywords);
        for (Document doc : docs) {
            String source = String.valueOf(doc.getMetadata().getOrDefault("source", "Unknown_Document.txt"));
            String snippet = RagOrchestrationService.extractSnippet(doc.getContent(), keywords, wantsMetrics, minKeywordHits);
            if (snippet.isBlank() || !seenSnippets.add(snippet)) continue;
            summary.append(added + 1).append(". ").append(snippet).append(" [").append(source).append("]\n");
            if (++added < 5) continue;
            break;
        }
        if (added == 0) {
            return NO_RELEVANT_RECORDS;
        }
        return summary.toString().trim();
    }

    private static String buildEvidenceFallbackResponse(List<Document> docs, String query) {
        String snippet;
        String source;
        if (docs == null || docs.isEmpty()) {
            return NO_RELEVANT_RECORDS;
        }
        StringBuilder summary = new StringBuilder("No direct answer found in the documents. Closest excerpts:\n\n");
        int added = 0;
        LinkedHashSet<String> seenSnippets = new LinkedHashSet<String>();
        Set<String> keywords = RagOrchestrationService.buildQueryKeywords(query);
        boolean wantsMetrics = RagOrchestrationService.queryWantsMetrics(query);
        int minKeywordHits = Math.max(1, RagOrchestrationService.requiredKeywordHits(query, keywords) - 1);
        for (Document doc : docs) {
            source = String.valueOf(doc.getMetadata().getOrDefault("source", "Unknown_Document.txt"));
            snippet = RagOrchestrationService.extractSnippet(doc.getContent(), keywords, wantsMetrics, minKeywordHits);
            if (snippet.isBlank()) {
                snippet = RagOrchestrationService.extractSnippetLenient(doc.getContent(), keywords);
            }
            if (snippet.isBlank() || !seenSnippets.add(snippet)) continue;
            summary.append(added + 1).append(". ").append(snippet).append(" [").append(source).append("]\n");
            if (++added < 5) continue;
            break;
        }
        if (added == 0) {
            for (Document doc : docs) {
                source = String.valueOf(doc.getMetadata().getOrDefault("source", "Unknown_Document.txt"));
                snippet = RagOrchestrationService.extractAnySnippet(doc.getContent());
                if (snippet.isBlank()) continue;
                summary.append("1. ").append(snippet).append(" [").append(source).append("]\n");
                added = 1;
                break;
            }
        }
        if (added == 0) {
            return NO_RELEVANT_RECORDS;
        }
        return summary.toString().trim();
    }

    private static boolean queryWantsMetrics(String query) {
        return query != null && METRIC_HINT_PATTERN.matcher(query).find();
    }

    private static int requiredKeywordHits(String query, Set<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return 0;
        }
        int minHits = 1;
        if (query != null && RELATIONSHIP_PATTERN.matcher(query).find()) {
            minHits = 2;
        }
        if (keywords.size() >= 6) {
            minHits = Math.max(minHits, 2);
        }
        if (keywords.size() >= 10) {
            minHits = Math.max(minHits, 3);
        }
        return minHits;
    }

    private static int countKeywordHits(String text, Set<String> keywords) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        if (keywords == null || keywords.isEmpty()) {
            return 0;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        int hits = 0;
        for (String keyword : keywords) {
            if (!lower.contains(keyword)) continue;
            ++hits;
        }
        return hits;
    }

    private static boolean isRelevantLine(String text, Set<String> keywords, boolean wantsMetrics, int minKeywordHits) {
        if (text == null || text.isBlank()) {
            return false;
        }
        int hits = RagOrchestrationService.countKeywordHits(text, keywords);
        if (hits >= minKeywordHits && minKeywordHits > 0) {
            return true;
        }
        if (wantsMetrics) {
            return METRIC_HINT_PATTERN.matcher(text).find() || NUMERIC_PATTERN.matcher(text).find();
        }
        return hits > 0 && minKeywordHits == 0;
    }

    private static boolean hasRelevantEvidence(List<Document> docs, String query) {
        if (docs == null || docs.isEmpty()) {
            return false;
        }
        Set<String> keywords = RagOrchestrationService.buildQueryKeywords(query);
        boolean wantsMetrics = RagOrchestrationService.queryWantsMetrics(query);
        int minKeywordHits = RagOrchestrationService.requiredKeywordHits(query, keywords);
        if ((keywords.isEmpty() || minKeywordHits == 0) && !wantsMetrics) {
            return false;
        }
        for (Document doc : docs) {
            String[] lines;
            String content = doc.getContent();
            if (content == null || content.isBlank()) continue;
            for (String rawLine : lines = content.split("\\R")) {
                String trimmed = rawLine.trim();
                if (trimmed.isEmpty() || RagOrchestrationService.isTableSeparator(trimmed) || !RagOrchestrationService.isRelevantLine(trimmed, keywords, wantsMetrics, minKeywordHits)) continue;
                return true;
            }
        }
        return false;
    }

    private static String extractSnippet(String content, Set<String> keywords, boolean wantsMetrics, int minKeywordHits) {
        if (content == null) {
            return "";
        }
        String[] lines = content.split("\\R");
        String candidate = "";
        String metricCandidate = "";
        String bestMatchCandidate = ""; // Line with BOTH keywords AND metric/name value
        String nameContextCandidate = ""; // Line with name context pattern (Executive Sponsor:, Author:, etc.)

        // Check if query is looking for a person/name (contains name-context keywords)
        boolean wantsName = keywords.stream().anyMatch(k ->
            NAME_CONTEXT_PATTERN.matcher(k).find() ||
            k.equalsIgnoreCase("sponsor") || k.equalsIgnoreCase("author") ||
            k.equalsIgnoreCase("lead") || k.equalsIgnoreCase("director") ||
            k.equalsIgnoreCase("officer") || k.equalsIgnoreCase("manager"));

        // Search all lines, prioritizing those with actual metric/name values
        for (String rawLine : lines) {
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty() || RagOrchestrationService.isTableSeparator(trimmed)) continue;

            // Count keyword hits in this line
            int keywordHits = RagOrchestrationService.countKeywordHits(trimmed, keywords);

            // Check if this line contains a metric value (dollar amounts, numbers, percentages)
            boolean hasMetric = NUMERIC_PATTERN.matcher(trimmed).find() ||
                               trimmed.matches(".*\\$[\\d,]+.*") ||
                               trimmed.matches(".*\\d+%.*") ||
                               trimmed.toLowerCase().contains("million") ||
                               trimmed.toLowerCase().contains("billion");

            // Check if line has name context (like "Executive Sponsor: Dr. Robert Chen")
            boolean hasNameContext = NAME_CONTEXT_PATTERN.matcher(trimmed.toLowerCase()).find();

            // Skip lines without keywords (unless they have metrics/names and we want those)
            if (keywordHits < minKeywordHits && !(wantsMetrics && hasMetric) && !(wantsName && hasNameContext)) {
                continue;
            }

            if (trimmed.contains("|")) {
                String summarized = RagOrchestrationService.summarizeTableRow(trimmed, keywords, wantsMetrics, minKeywordHits);
                if (summarized.isBlank()) continue;
                if (keywordHits > 0 && hasMetric && bestMatchCandidate.isEmpty()) {
                    bestMatchCandidate = summarized;
                } else if (hasMetric && metricCandidate.isEmpty()) {
                    metricCandidate = summarized;
                } else if (candidate.isEmpty()) {
                    candidate = summarized;
                }
            } else {
                // Best: line has BOTH keywords AND relevant value (metric or name context)
                if (keywordHits > 0 && (hasMetric || hasNameContext)) {
                    // For metric queries, prefer lines where keywords AND metrics appear together
                    // For name queries, prefer lines where keywords AND name context appear together
                    if (bestMatchCandidate.isEmpty()) {
                        bestMatchCandidate = trimmed;
                    }
                }
                // Good for name queries: line has name context pattern
                else if (wantsName && hasNameContext && nameContextCandidate.isEmpty()) {
                    nameContextCandidate = trimmed;
                }
                // Good for metric queries: line has metric value
                else if (hasMetric && metricCandidate.isEmpty()) {
                    metricCandidate = trimmed;
                }
                // Fallback: line has keywords only
                else if (keywordHits > 0 && candidate.isEmpty()) {
                    candidate = trimmed;
                }
            }

            // If we found the ideal match (keywords + metric/name), stop searching
            if (!bestMatchCandidate.isEmpty()) {
                break;
            }
        }

        // Priority: best (keyword+value) > name context > metric only > keyword only
        String result;
        if (!bestMatchCandidate.isEmpty()) {
            result = bestMatchCandidate;
        } else if (wantsName && !nameContextCandidate.isEmpty()) {
            result = nameContextCandidate;
        } else if (!metricCandidate.isEmpty()) {
            result = metricCandidate;
        } else {
            result = candidate;
        }

        if (result.isEmpty()) {
            return "";
        }
        result = RagOrchestrationService.sanitizeSnippet(result);
        int maxLen = 240;
        if (result.length() > maxLen) {
            result = result.substring(0, maxLen - 3).trim() + "...";
        }
        return result;
    }

    private static String extractSnippetLenient(String content, Set<String> keywords) {
        String trimmed;
        if (content == null) {
            return "";
        }
        String[] lines = content.split("\\R");
        String candidate = "";
        for (String rawLine : lines) {
            trimmed = rawLine.trim();
            if (trimmed.isEmpty() || RagOrchestrationService.isTableSeparator(trimmed) || !keywords.isEmpty() && !RagOrchestrationService.containsKeyword(trimmed, keywords)) continue;
            candidate = trimmed;
            break;
        }
        if (candidate.isEmpty()) {
            for (String rawLine : lines) {
                trimmed = rawLine.trim();
                if (trimmed.isEmpty() || RagOrchestrationService.isTableSeparator(trimmed)) continue;
                candidate = trimmed;
                break;
            }
        }
        if (candidate.isEmpty()) {
            return "";
        }
        candidate = RagOrchestrationService.sanitizeSnippet(candidate);
        int maxLen = 240;
        if (candidate.length() > maxLen) {
            candidate = candidate.substring(0, maxLen - 3).trim() + "...";
        }
        return candidate;
    }

    private static String extractAnySnippet(String content) {
        String[] lines;
        if (content == null) {
            return "";
        }
        for (String rawLine : lines = content.split("\\R")) {
            String candidate;
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty() || RagOrchestrationService.isTableSeparator(trimmed) || (candidate = RagOrchestrationService.sanitizeSnippet(trimmed)).isBlank()) continue;
            return RagOrchestrationService.truncateSnippet(candidate);
        }
        String fallback = RagOrchestrationService.sanitizeSnippet(content.replaceAll("\\s+", " ").trim());
        if (fallback.isBlank()) {
            return "";
        }
        return RagOrchestrationService.truncateSnippet(fallback);
    }

    private static String truncateSnippet(String candidate) {
        int maxLen = 240;
        if (candidate.length() > maxLen) {
            return candidate.substring(0, maxLen - 3).trim() + "...";
        }
        return candidate;
    }

    private static boolean containsKeyword(String text, Set<String> keywords) {
        if (text == null || text.isBlank() || keywords.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (!lower.contains(keyword)) continue;
            return true;
        }
        return false;
    }

    private static boolean isTableSeparator(String line) {
        return line.matches("\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*");
    }

    private static String summarizeTableRow(String row, Set<String> keywords, boolean wantsMetrics, int minKeywordHits) {
        String trimmed = row.trim();
        if (!RagOrchestrationService.isRelevantLine(trimmed, keywords, wantsMetrics, minKeywordHits)) {
            return "";
        }
        String[] rawCells = trimmed.split("\\|");
        ArrayList<String> cells = new ArrayList<String>();
        for (String cell : rawCells) {
            String cleaned = RagOrchestrationService.sanitizeSnippet(cell);
            if (cleaned.isBlank()) continue;
            cells.add(cleaned);
        }
        if (cells.size() < 2) {
            return "";
        }
        List<String> selected = new ArrayList<String>();
        for (String cell : cells) {
            if (!RagOrchestrationService.containsKeyword(cell, keywords)) continue;
            selected.add(cell);
        }
        if (selected.isEmpty() && wantsMetrics) {
            for (String cell : cells) {
                if (!METRIC_HINT_PATTERN.matcher(cell).find() && !NUMERIC_PATTERN.matcher(cell).find()) continue;
                selected.add(cell);
            }
        }
        if (selected.isEmpty()) {
            return "";
        }
        if (selected.size() > 4) {
            selected = selected.subList(0, 4);
        }
        return String.join((CharSequence)" - ", selected);
    }

    private static String sanitizeSnippet(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text.trim();
        cleaned = cleaned.replaceAll("^\\s*[#>*-]+\\s*", "");
        cleaned = cleaned.replaceAll("\\*{1,2}", "");
        cleaned = cleaned.replaceAll("_{1,2}", "");
        cleaned = cleaned.replaceAll("`", "");
        cleaned = cleaned.replaceAll("\\s*\\|\\s*", " - ");
        cleaned = cleaned.replaceAll("\\s*[\\u00B7\\u2022]\\s*", " - ");
        cleaned = cleaned.replaceAll("\\s*-\\s*-\\s*", " - ");
        cleaned = cleaned.replaceAll("^\\s*-\\s*", "");
        cleaned = cleaned.replaceAll("\\s*-\\s*$", "");
        return cleaned.trim();
    }

    private static Set<String> buildQueryKeywords(String query) {
        if (query == null || query.isBlank()) {
            return Set.of();
        }
        Set<String> stopwords = Set.of("the", "and", "for", "with", "this", "that", "from", "what", "when", "where", "which", "about", "across", "between", "into", "your", "their", "compare", "over", "each", "only", "should", "would", "could", "more", "less", "than", "then");
        Matcher matcher = Pattern.compile("[A-Za-z0-9]+").matcher(query);
        LinkedHashSet<String> keywords = new LinkedHashSet<String>();
        while (matcher.find()) {
            boolean isAcronym;
            String raw = matcher.group();
            if (raw.chars().allMatch(Character::isDigit)) continue;
            String token = raw.toLowerCase(Locale.ROOT);
            boolean bl = isAcronym = raw.length() >= 2 && raw.equals(raw.toUpperCase(Locale.ROOT));
            if (token.length() < 4 && !isAcronym || stopwords.contains(token)) continue;
            keywords.add(token);
        }
        return keywords;
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
            semanticResults = this.vectorStore.similaritySearch(SearchRequest.query((String)query).withTopK(10).withSimilarityThreshold(threshold).withFilterExpression(FilterExpressionBuilder.forDepartment(dept)));
            log.info("Semantic search found {} results for query {}", semanticResults.size(), LogSanitizer.querySummary(query));
            String lowerQuery = query.toLowerCase();
            keywordResults = this.vectorStore.similaritySearch(SearchRequest.query((String)query).withTopK(50).withSimilarityThreshold(0.01).withFilterExpression(FilterExpressionBuilder.forDepartment(dept)));
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

        // HGMem: use deepAnalysis param or fallback to advancedNeeded heuristic
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
            Set<String> suspiciousIds = ragPartResult.suspiciousDocuments().stream().map(RagOrchestrationService::buildDocumentId).collect(Collectors.toSet());
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

    private String mergeGlobalContexts(List<String> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return "";
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String ctx : contexts) {
            if (ctx == null || ctx.isBlank()) {
                continue;
            }
            unique.add(ctx.trim());
        }
        if (unique.isEmpty()) {
            return "";
        }
        return String.join("\n\n---\n\n", unique);
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
        String sectorPrefix = dept.toUpperCase() + ":";
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
        docs.sort(Comparator.comparingDouble(RagOrchestrationService::getDocumentScore).reversed().thenComparing(RagOrchestrationService::getDocumentSource, String.CASE_INSENSITIVE_ORDER));
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

    private boolean isTimeQuery(String query) {
        if (query == null) {
            return false;
        }
        String q = query.toLowerCase();
        return q.matches(".*\\bwhat('?s)?\\s+time\\b.*") || q.matches(".*\\bcurrent\\s+time\\b.*") || q.matches(".*\\btime\\s+is\\s+it\\b.*") || q.matches(".*\\btime\\s+now\\b.*") || q.matches(".*\\btell\\s+me\\s+the\\s+time\\b.*");
    }

    private String buildSystemTimeResponse() {
        ZonedDateTime now = ZonedDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");
        return "Local system time: " + now.format(formatter);
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
}
