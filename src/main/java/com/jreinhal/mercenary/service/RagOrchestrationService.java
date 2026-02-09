package com.jreinhal.mercenary.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.config.SectorConfig;
import com.jreinhal.mercenary.core.license.LicenseService;
import com.jreinhal.mercenary.dto.EnhancedAskResponse;
import com.jreinhal.mercenary.filter.SecurityContext;
import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.UserRole;
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
import com.jreinhal.mercenary.workspace.WorkspaceContext;
import jakarta.servlet.http.HttpServletRequest;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
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
    private final ConversationMemoryProvider conversationMemoryService;
    private final SessionPersistenceProvider sessionPersistenceService;
    private final LicenseService licenseService;
    private final PiiRedactionService piiRedactionService;
    private final HipaaPolicy hipaaPolicy;
    private final HipaaAuditProvider hipaaAuditService;
    private final com.jreinhal.mercenary.workspace.WorkspaceQuotaService workspaceQuotaService;
    private final AtomicInteger queryCount = new AtomicInteger(0);
    private final AtomicLong totalLatencyMs = new AtomicLong(0L);
    private final Cache<String, String> secureDocCache;
    private final String llmModel;
    private final double llmTemperature;
    private final int llmNumPredict;
    private final OllamaOptions llmOptions;
    private static final String NO_RELEVANT_RECORDS = "No relevant records found.";
    private static final Pattern STRICT_CITATION_PATTERN = Pattern.compile("\\[(?:Citation:\\s*)?(?:IMAGE:\\s*)?[^\\]]+\\.(pdf|txt|md|csv|xlsx|xls|doc|docx|pptx|html?|json|ndjson|log|png|jpg|jpeg|gif|tif|tiff|bmp)\\]", 2);
    private static final Pattern STRICT_CITATION_FILENAME_PATTERN = Pattern.compile("\\[(?:Citation:\\s*)?(?:IMAGE:\\s*)?([^\\]]+\\.(pdf|txt|md|csv|xlsx|xls|doc|docx|pptx|html?|json|ndjson|log|png|jpg|jpeg|gif|tif|tiff|bmp))\\]", 2);
    private static final Pattern METRIC_HINT_PATTERN = Pattern.compile("\\b(metric|metrics|performance|availability|uptime|latency|sla|kpi|mttd|mttr|throughput|error rate|response time|accuracy|precision|recall|f1|cost|risk|budget|revenue|expense|income|profit|loss|spend|spending|amount|total|price|value|rate|percentage|count|number|quantity|allocation|funding|compliance)\\b", 2);
    private static final Pattern NAME_CONTEXT_PATTERN = Pattern.compile("\\b(sponsor|author|lead|director|manager|officer|chief|head|principal|coordinator|owner|contact|prepared by|reviewed by|approved by|submitted by)\\s*:?\\s*", 2);
    private static final Pattern SUMMARY_HINT_PATTERN = Pattern.compile("\\b(summarize|summary|overview|brief|synopsis|recap)\\b", 2);
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("\\d");
    private static final Pattern RELATIONSHIP_PATTERN = Pattern.compile("\\b(relationship|relate|comparison|compare|versus|between|difference|differ|impact|effect|align|alignment|correlat|dependency|tradeoff|link)\\b", 2);
    private static final Pattern FILE_MARKER_PATTERN = Pattern.compile("^=+\\s*FILE\\s*:\\s*.+?=+$", 2);
    private static final Pattern FILE_MARKER_INLINE_PATTERN = Pattern.compile("^FILE\\s*:\\s*.+$", 2);
    private static final Pattern DOC_ID_HINT_PATTERN = Pattern.compile("\\b(doc\\s*id|docid|document\\s*id)\\b", 2);
    private static final Pattern TITLE_HINT_PATTERN = Pattern.compile("\\btitle\\b", 2);
    private static final Pattern DATE_HINT_PATTERN = Pattern.compile("\\b(date|when|year|month)\\b", 2);
    private static final Pattern CLASSIFICATION_HINT_PATTERN = Pattern.compile("\\b(classification|classified|unclassified|confidential)\\b", 2);
    private static final Pattern AUTHOR_HINT_PATTERN = Pattern.compile("\\b(author|prepared by|reviewed by|approved by)\\b", 2);
    private static final Pattern DOC_ID_VALUE_PATTERN = Pattern.compile("\\b([A-Z]{2,}[A-Z0-9]*-\\d{4}(?:-[A-Z0-9]{2,})*)\\b");
    private static final List<String> DOC_ID_META_KEYS = List.of("doc_id", "docid", "docId", "document_id", "documentId", "documentid", "id", "record_id", "recordId");
    private static final List<String> TITLE_META_KEYS = List.of("title", "document_title", "documentTitle", "doc_title", "name");
    private static final Pattern TITLE_LINE_SKIP_PATTERN = Pattern.compile("^(classification|document date|version|report period|protocol)\\b", 2);
    private static final Pattern METADATA_LINE_PATTERN = Pattern.compile("^(doc[_\\s]?id|docid|document id|sector|title|date|author|author_role|classification|confidentiality notice|status)\\b", 2);
    private static final Pattern BOILERPLATE_LINE_PATTERN = Pattern.compile("^(confidentiality notice|this document contains|approved this release|approved on|approved by|release approved|distribution:|prepared for:|document metadata|document id:|doc id:|author:|review date:|classification:|report period:)\\b", 2);
    private static final Pattern TEST_ARTIFACT_SOURCE_PATTERN = Pattern.compile("(pii_test_|upload_valid|upload_spoofed|sample_)", 2);
    private static final Pattern PII_QUERY_PATTERN = Pattern.compile("\\b(pii|ssn|phi|redact|redaction|token|tokenize)\\b", 2);
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

    public RagOrchestrationService(ChatClient.Builder builder, VectorStore vectorStore, AuditService auditService, QueryDecompositionService queryDecompositionService, ReasoningTracer reasoningTracer, QuCoRagService quCoRagService, AdaptiveRagService adaptiveRagService, RewriteService rewriteService, RagPartService ragPartService, HybridRagService hybridRagService, HiFiRagService hiFiRagService, MiARagService miARagService, MegaRagService megaRagService, HGMemQueryEngine hgMemQueryEngine, AgenticRagOrchestrator agenticRagOrchestrator, BidirectionalRagService bidirectionalRagService, ModalityRouter modalityRouter, SectorConfig sectorConfig, PromptGuardrailService guardrailService, @org.springframework.lang.Nullable ConversationMemoryProvider conversationMemoryService, @org.springframework.lang.Nullable SessionPersistenceProvider sessionPersistenceService, LicenseService licenseService, PiiRedactionService piiRedactionService, HipaaPolicy hipaaPolicy, @org.springframework.lang.Nullable HipaaAuditProvider hipaaAuditService, Cache<String, String> secureDocCache, com.jreinhal.mercenary.workspace.WorkspaceQuotaService workspaceQuotaService,
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
        this.licenseService = licenseService;
        this.piiRedactionService = piiRedactionService;
        this.hipaaPolicy = hipaaPolicy;
        this.hipaaAuditService = hipaaAuditService;
        this.secureDocCache = secureDocCache;
        this.workspaceQuotaService = workspaceQuotaService;
        this.llmModel = llmModel;
        this.llmTemperature = llmTemperature;
        this.llmNumPredict = llmNumPredict;
        this.llmOptions = OllamaOptions.create()
                .withModel(llmModel)
                .withTemperature(llmTemperature)
                .withNumPredict(Integer.valueOf(llmNumPredict));
        // S4-09: LLM config at debug level — parity with MercenaryController R-08 fix
        if (log.isDebugEnabled()) {
            log.debug("=== LLM Configuration ===");
            log.debug("  Model: {}", this.llmOptions.getModel());
            log.debug("  Temperature: {}", this.llmOptions.getTemperature());
            log.debug("  Max Tokens (num_predict): {}", this.llmOptions.getNumPredict());
            log.debug("  Ollama Base URL: {}", System.getProperty("spring.ai.ollama.base-url", "http://localhost:11434"));
            log.debug("=========================");
        }
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
            return "INVALID SECTOR: unrecognized department value";
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
        try {
            this.workspaceQuotaService.enforceQueryQuota(com.jreinhal.mercenary.workspace.WorkspaceContext.getCurrentWorkspaceId());
        } catch (com.jreinhal.mercenary.workspace.WorkspaceQuotaExceededException e) {
            this.auditService.logAccessDenied(user, "/api/ask", "Workspace quota exceeded: " + e.getQuotaType(), request);
            // S2-05: Use safe quota type label instead of raw exception message
            return "ACCESS DENIED: " + e.getQuotaType() + " limit reached for this workspace.";
        }
        boolean hipaaStrict = this.hipaaPolicy.isStrict(department);
        List<String> activeFiles = this.parseActiveFiles(fileParams, filesParam);
        // Fix #3: Redact PII from user query BEFORE any pipeline processing.
        // All downstream stages (guardrail, routing, retrieval, LLM, audit) use the redacted query.
        query = this.piiRedactionService.redact(query, hipaaStrict ? Boolean.TRUE : null).getRedactedContent();
        try {
            String rescued;
            String response;
            List<String> subQueries;
            boolean isCompoundQuery;
            long start = System.currentTimeMillis();
            ResponsePolicy responsePolicy = responsePolicyForEdition(this.licenseService.getEdition());
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
            final boolean suppressSensitiveLogs =
                this.hipaaPolicy.shouldSuppressSensitiveLogs(department) ||
                this.licenseService.getEdition() == LicenseService.Edition.GOVERNMENT;

            // Never log document content previews (they may contain PHI/PII or classified text).
            log.info("Retrieved {} documents for query {}", rawDocs.size(), LogSanitizer.querySummary(query));
            if (suppressSensitiveLogs) {
                log.debug("Sensitive document previews suppressed (regulated mode).");
            }
            List<Document> topDocs = orderedDocs.stream().limit(15L).toList();
            if (hipaaStrict && this.hipaaAuditService != null) {
                List<String> docIds = topDocs.stream().map(doc -> String.valueOf(doc.getMetadata().getOrDefault("source", "unknown"))).distinct().toList();
                this.hipaaAuditService.logPhiQuery(user, query, topDocs.size(), docIds);
            }
            String globalContext = this.mergeGlobalContexts(globalContexts);
            String information = this.buildInformation(topDocs, globalContext);
            boolean allowVisual = !this.hipaaPolicy.shouldDisableVisual(department);
            if (!allowVisual) {
                visualDocs.clear();
            }
            boolean useVisual = allowVisual && this.megaRagService != null && this.megaRagService.isEnabled() && !visualDocs.isEmpty();
            String systemText = buildSystemPrompt(information, responsePolicy, department);
            String systemMessage = systemText.replace("{information}", information);
            List<Document> extractiveDocs = this.expandDocsFromCache(topDocs, dept, query);
            boolean llmSuccess = true;
            try {
                if (useVisual) {
                    String visualResponse = this.megaRagService.generateWithVisualContext(query, topDocs, visualDocs);
                    response = visualResponse != null ? cleanLlmResponse(visualResponse) : "";
                } else {
                     String sysMsg = systemMessage.replace("{", "[").replace("}", "]");
                     String userQuery = query.replace("{", "[").replace("}", "]");
                     ChatOptions options = optionsForPolicy(responsePolicy);
                     String rawResponse = CompletableFuture.supplyAsync(() -> this.chatClient.prompt().system(sysMsg).user(userQuery).options(options).call().content()).get(llmTimeoutSeconds, TimeUnit.SECONDS);
                    // Never log raw model output; it may contain sensitive data.
                    if (log.isDebugEnabled()) {
                        log.debug("LLM response received for /ask (len={})", rawResponse != null ? rawResponse.length() : 0);
                    }
                    response = cleanLlmResponse(rawResponse);
                 }
             }
            catch (TimeoutException te) {
                llmSuccess = false;
                if (log.isWarnEnabled()) {
                    log.warn("LLM response timed out after {}s for query {}", llmTimeoutSeconds, LogSanitizer.querySummary(query));
                }
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
            if (this.hipaaPolicy.shouldRedactResponses(department)) {
                response = this.piiRedactionService.redact(response, Boolean.TRUE).getRedactedContent();
            }
            boolean isTimeoutResponse = response != null && response.toLowerCase(Locale.ROOT).contains("response timeout");
            boolean hasEvidence = RagOrchestrationService.hasRelevantEvidence(extractiveDocs, query) || !visualDocs.isEmpty();
            int citationCount = RagOrchestrationService.countCitations(response);
            boolean rescueApplied = false;
            boolean citationRepairApplied = false;
            boolean excerptFallbackApplied = false;
            boolean evidenceAppendApplied = false;
            if (responsePolicy != null && responsePolicy.enforceCitations() && llmSuccess && citationCount == 0 && hasEvidence && !isTimeoutResponse && !RagOrchestrationService.isNoInfoResponse(response)) {
                String repaired = this.repairCitationsIfNeeded(response, query, information, responsePolicy, department, extractiveDocs, llmTimeoutSeconds);
                if (repaired != null && !repaired.equals(response)) {
                    int repairedCitations = RagOrchestrationService.countCitations(repaired);
                    if (repairedCitations > 0) {
                        response = repaired;
                        citationRepairApplied = true;
                        citationCount = repairedCitations;
                    }
                }
            }
            if (!citationRepairApplied && responsePolicy != null && responsePolicy.enforceCitations() && llmSuccess && citationCount == 0 && hasEvidence && !isTimeoutResponse && !NO_RELEVANT_RECORDS.equals(rescued = RagOrchestrationService.buildExtractiveResponse(extractiveDocs, query))) {
                response = rescued;
                rescueApplied = true;
                citationCount = RagOrchestrationService.countCitations(response);
            }
            boolean answerable = hasEvidence && !isTimeoutResponse;
            boolean usedAnswerabilityGate = false;
            if (!answerable || RagOrchestrationService.isNoInfoResponse(response)) {
                if (!isTimeoutResponse) {
                    String fallback = RagOrchestrationService.buildEvidenceFallbackResponse(extractiveDocs, query);
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
            } else if (llmSuccess && citationCount == 0 && responsePolicy != null && responsePolicy.enforceCitations()) {
                response = NO_RELEVANT_RECORDS;
                answerable = false;
                usedAnswerabilityGate = true;
                citationCount = 0;
            } else if (llmSuccess && citationCount == 0 && responsePolicy != null && !responsePolicy.enforceCitations()) {
                String augmented = appendEvidenceIfNeeded(response, extractiveDocs, query, responsePolicy, true);
                evidenceAppendApplied = !augmented.equals(response);
                response = augmented;
                citationCount = RagOrchestrationService.countCitations(response);
            }
            if (answerable && responsePolicy != null && responsePolicy.appendEvidenceAlways()) {
                String augmented = appendEvidenceIfNeeded(response, extractiveDocs, query, responsePolicy, false);
                evidenceAppendApplied = evidenceAppendApplied || !augmented.equals(response);
                response = augmented;
                citationCount = RagOrchestrationService.countCitations(response);
            }
            if (query != null && DOC_ID_HINT_PATTERN.matcher(query).find() && (response == null || !response.contains("DOC_ID"))) {
                String forced = RagOrchestrationService.buildExtractiveResponse(extractiveDocs, query);
                if (!NO_RELEVANT_RECORDS.equals(forced)) {
                    response = forced;
                    citationCount = RagOrchestrationService.countCitations(response);
                }
            }
            if (usedAnswerabilityGate) {
                log.info("Answerability gate applied (citations={}, answerable=false) for query {}", citationCount, LogSanitizer.querySummary(query));
            } else if (citationRepairApplied) {
                log.info("Citation repair applied (LLM rewrite with citations) for query {}", LogSanitizer.querySummary(query));
            } else if (rescueApplied) {
                log.info("Citation rescue applied (extractive fallback) for query {}", LogSanitizer.querySummary(query));
            } else if (excerptFallbackApplied) {
                log.info("Excerpt fallback applied (no direct answer) for query {}", LogSanitizer.querySummary(query));
            } else if (evidenceAppendApplied) {
                log.info("Evidence appendix applied for query {}", LogSanitizer.querySummary(query));
            }
            // Skip hallucination check for extractive responses or responses already grounded with citations
            boolean skipHallucinationCheckAsk = rescueApplied || excerptFallbackApplied
                || (citationCount > 0 && hasEvidence);
            if (!skipHallucinationCheckAsk) {
                QuCoRagService.HallucinationResult hallucinationResult = this.quCoRagService.detectHallucinationRisk(response, query);
                if (hallucinationResult.isHighRisk()) {
                    if (log.isWarnEnabled()) {
                        log.warn("QuCo-RAG: High hallucination risk detected (risk={:.3f}). Flagged entities: {}", hallucinationResult.riskScore(), hallucinationResult.flaggedEntities());
                    }
                    RetrievalContext retryContext = this.retrieveContext(query, dept, activeFiles, routing, true, false);
                    if (!retryContext.textDocuments().isEmpty()) {
                        String retryInfo = this.buildInformation(retryContext.textDocuments(), retryContext.globalContext());
                        String retrySystem = buildSystemPrompt(retryInfo, responsePolicy, department);
                        String retrySystemMessage = retrySystem.replace("{information}", retryInfo);
                        try {
                            String sysMsg = retrySystemMessage.replace("{", "[").replace("}", "]");
                            String userQuery = query.replace("{", "[").replace("}", "]");
                            ChatOptions options = optionsForPolicy(responsePolicy);
                            String rawRetry = CompletableFuture.supplyAsync(() -> this.chatClient.prompt().system(sysMsg).user(userQuery).options(options).call().content()).get(llmTimeoutSeconds, TimeUnit.SECONDS);
                            response = cleanLlmResponse(rawRetry);
                            citationCount = RagOrchestrationService.countCitations(response);
                            if (citationCount == 0 && responsePolicy != null && responsePolicy.enforceCitations()) {
                                response = NO_RELEVANT_RECORDS;
                            }
                        }
                        catch (Exception retryError) {
                            if (log.isWarnEnabled()) {
                                log.warn("QuCo-RAG retry generation failed: {}", retryError.getMessage());
                            }
                        }
                    } else {
                        response = NO_RELEVANT_RECORDS;
                    }
                }
            } else {
                log.debug("QuCo-RAG: Skipping hallucination check for extractive response");
            }
            if (this.bidirectionalRagService != null && this.bidirectionalRagService.isEnabled() && hasEvidence && !this.hipaaPolicy.shouldDisableExperienceLearning(department)) {
                String userId = user != null ? user.getUsername() : "unknown";
                this.bidirectionalRagService.validateAndLearn(query, response, topDocs, department.name(), userId);
            }
            long timeTaken = System.currentTimeMillis() - start;
            this.totalLatencyMs.addAndGet(timeTaken);
            this.queryCount.incrementAndGet();
            if (this.hipaaPolicy.shouldRedactResponses(department)) {
                response = this.piiRedactionService.redact(response, Boolean.TRUE).getRedactedContent();
            }
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
    /**
     * Fix #9: Accept per-request RAG engine overrides from frontend settings toggles.
     * When null, the server-side default (from application.yaml / env vars) is used.
     */
    public record RetrievalOverrides(Boolean useHyde, Boolean useGraphRag, Boolean useReranking) {
        static final RetrievalOverrides DEFAULTS = new RetrievalOverrides(null, null, null);
    }

    public EnhancedAskResponse askEnhanced(String query, String dept, List<String> fileParams, String filesParam, String sessionId, boolean deepAnalysis, Boolean useHyde, Boolean useGraphRag, Boolean useReranking, HttpServletRequest request) {
        return askEnhanced(query, dept, fileParams, filesParam, sessionId, deepAnalysis, new RetrievalOverrides(useHyde, useGraphRag, useReranking), request);
    }

    public EnhancedAskResponse askEnhanced(String query, String dept, List<String> fileParams, String filesParam, String sessionId, boolean deepAnalysis, HttpServletRequest request) {
        return askEnhanced(query, dept, fileParams, filesParam, sessionId, deepAnalysis, RetrievalOverrides.DEFAULTS, request);
    }

    private EnhancedAskResponse askEnhanced(String query, String dept, List<String> fileParams, String filesParam, String sessionId, boolean deepAnalysis, RetrievalOverrides overrides, HttpServletRequest request) {
        Department department;
        User user = SecurityContext.getCurrentUser();
        try {
            department = Department.valueOf(dept.toUpperCase());
        }
        catch (IllegalArgumentException e) {
            return new EnhancedAskResponse("INVALID SECTOR: unrecognized department value", List.of(), List.of(), Map.of(), null);
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
        try {
            this.workspaceQuotaService.enforceQueryQuota(com.jreinhal.mercenary.workspace.WorkspaceContext.getCurrentWorkspaceId());
        } catch (com.jreinhal.mercenary.workspace.WorkspaceQuotaExceededException e) {
            this.auditService.logAccessDenied(user, "/api/ask/enhanced", "Workspace quota exceeded: " + e.getQuotaType(), request);
            // S2-05: Use safe quota type label instead of raw exception message
            return new EnhancedAskResponse("ACCESS DENIED: " + e.getQuotaType() + " limit reached for this workspace.", List.of(), List.of(),
                    Map.of("error", "WORKSPACE_QUOTA", "quota", e.getQuotaType()), null);
        }
        boolean hipaaStrict = this.hipaaPolicy.isStrict(department);
        List<String> activeFiles = this.parseActiveFiles(fileParams, filesParam);
        // Fix #3: Redact PII from user query BEFORE any pipeline processing.
        query = this.piiRedactionService.redact(query, hipaaStrict ? Boolean.TRUE : null).getRedactedContent();
        String effectiveSessionId = sessionId;
        if (sessionId != null && !sessionId.isBlank()) {
            try {
                if (!this.hipaaPolicy.shouldDisableSessionMemory(department)
                        && this.conversationMemoryService != null
                        && this.sessionPersistenceService != null) {
                    this.sessionPersistenceService.touchSession(user.getId(), sessionId, dept);
                    this.conversationMemoryService.saveUserMessage(user.getId(), sessionId, query);
                    if (this.conversationMemoryService.isFollowUp(query)) {
                        ConversationMemoryProvider.ConversationContext context = this.conversationMemoryService.getContext(user.getId(), sessionId);
                        query = this.conversationMemoryService.expandFollowUp(query, context);
                        log.debug("Expanded follow-up query for session {}", sessionId);
                    }
                } else if (this.conversationMemoryService == null || this.sessionPersistenceService == null) {
                    log.debug("Session memory unavailable (edition does not include enterprise features)");
                } else {
                    log.info("HIPAA strict: conversation memory disabled for medical session {}", sessionId);
                }
            }
            catch (Exception e) {
                if (log.isWarnEnabled()) {
                    log.warn("Session operation failed, continuing without session: {}", e.getMessage());
                }
                effectiveSessionId = null;
            }
        }
        String traceQuery = hipaaStrict ? this.piiRedactionService.redact(query, Boolean.TRUE).getRedactedContent() : query;
        ReasoningTrace trace = this.reasoningTracer.startTrace(traceQuery, dept);
        try {
            Object source;
            String rescued;
            String response;
            long start = System.currentTimeMillis();
            ResponsePolicy responsePolicy = responsePolicyForEdition(this.licenseService.getEdition());
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
                    ChatOptions options = optionsForPolicy(responsePolicy);
                    String rawResponse = CompletableFuture.supplyAsync(() -> this.chatClient.prompt().system("You are SENTINEL, an intelligence assistant. Respond helpfully and concisely.").user(userQuery).options(options).call().content()).get(llmTimeoutSeconds, TimeUnit.SECONDS);
                    directResponse = cleanLlmResponse(rawResponse);
                }
                catch (TimeoutException te) {
                    if (log.isWarnEnabled()) {
                        log.warn("LLM response timed out after {}s for ZeroHop query {}", llmTimeoutSeconds, LogSanitizer.querySummary(query));
                    }
                    directResponse = "**Response Timeout**\n\nThe system is taking longer than expected. Please try again.";
                }
                catch (Exception llmError) {
                    directResponse = "I apologize, but I'm unable to process your request at the moment. Please try rephrasing your question.";
                }
                if (this.hipaaPolicy.shouldRedactResponses(department)) {
                    directResponse = this.piiRedactionService.redact(directResponse, Boolean.TRUE).getRedactedContent();
                }
                if (hipaaStrict && this.hipaaAuditService != null) {
                    this.hipaaAuditService.logPhiQuery(user, query, 0, List.of());
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
                RetrievalContext context = this.retrieveContext(subQuery, dept, activeFiles, routingResult, highUncertainty, deepAnalysis, overrides);
                if (context.textDocuments().isEmpty() && context.visualDocuments().isEmpty()) {
                    log.info("CRAG: Retrieval failed for '{}'. Initiating Corrective Loop.", subQuery);
                    String rewritten = this.rewriteService.rewriteQuery(subQuery);
                    if (!rewritten.equals(subQuery)) {
                        context = this.retrieveContext(rewritten, dept, activeFiles, routingResult, highUncertainty, deepAnalysis, overrides);
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
            if (hipaaStrict && this.hipaaAuditService != null) {
                List<String> docIds = topDocs.stream().map(doc -> String.valueOf(doc.getMetadata().getOrDefault("source", "unknown"))).distinct().toList();
                this.hipaaAuditService.logPhiQuery(user, query, topDocs.size(), docIds);
            }
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
            boolean allowVisual = !this.hipaaPolicy.shouldDisableVisual(department);
            if (!allowVisual) {
                visualDocs.clear();
            }
            boolean useVisual = allowVisual && this.megaRagService != null && this.megaRagService.isEnabled() && !visualDocs.isEmpty();
            String systemText = buildSystemPrompt(information, responsePolicy, department);
            String systemMessage = systemText.replace("{information}", information);
            List<Document> extractiveDocs = this.expandDocsFromCache(topDocs, dept, query);
            boolean llmSuccess = true;
            try {
                if (useVisual) {
                    String visualResponse = this.megaRagService.generateWithVisualContext(query, topDocs, visualDocs);
                    response = visualResponse != null ? cleanLlmResponse(visualResponse) : "";
                } else {
                    String sysMsg = systemMessage.replace("{", "[").replace("}", "]");
                    String userQuery = query.replace("{", "[").replace("}", "]");
                    ChatOptions options = optionsForPolicy(responsePolicy);
                    String rawResponse = CompletableFuture.supplyAsync(() -> this.chatClient.prompt().system(sysMsg).user(userQuery).options(options).call().content()).get(llmTimeoutSeconds, TimeUnit.SECONDS);
                    response = cleanLlmResponse(rawResponse);
                }
            }
            catch (TimeoutException te) {
                llmSuccess = false;
                if (log.isWarnEnabled()) {
                    log.warn("LLM response timed out after {}s", llmTimeoutSeconds);
                }
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
            if (this.hipaaPolicy.shouldRedactResponses(department)) {
                response = this.piiRedactionService.redact(response, Boolean.TRUE).getRedactedContent();
            }
            this.reasoningTracer.addStep(ReasoningStep.StepType.LLM_GENERATION, "Response Synthesis", (String)(llmSuccess ? "Generated response (" + response.length() + " chars)" : "Fallback mode (LLM offline)"), System.currentTimeMillis() - stepStart, Map.of("success", llmSuccess, "responseLength", response.length()));
            stepStart = System.currentTimeMillis();
            boolean isTimeoutResponse = response != null && response.toLowerCase(Locale.ROOT).contains("response timeout");
            boolean hasEvidence = RagOrchestrationService.hasRelevantEvidence(extractiveDocs, query) || !visualDocs.isEmpty();
            int citationCount = RagOrchestrationService.countCitations(response);
            boolean rescueApplied = false;
            boolean citationRepairApplied = false;
            boolean excerptFallbackApplied = false;
            boolean evidenceAppendApplied = false;
            if (responsePolicy != null && responsePolicy.enforceCitations() && llmSuccess && citationCount == 0 && hasEvidence && !isTimeoutResponse && !RagOrchestrationService.isNoInfoResponse(response)) {
                String repaired = this.repairCitationsIfNeeded(response, query, information, responsePolicy, department, extractiveDocs, llmTimeoutSeconds);
                if (repaired != null && !repaired.equals(response)) {
                    int repairedCitations = RagOrchestrationService.countCitations(repaired);
                    if (repairedCitations > 0) {
                        response = repaired;
                        citationRepairApplied = true;
                        citationCount = repairedCitations;
                    }
                }
            }
            if (!citationRepairApplied && responsePolicy != null && responsePolicy.enforceCitations() && llmSuccess && citationCount == 0 && hasEvidence && !isTimeoutResponse && !NO_RELEVANT_RECORDS.equals(rescued = RagOrchestrationService.buildExtractiveResponse(extractiveDocs, query))) {
                response = rescued;
                rescueApplied = true;
                citationCount = RagOrchestrationService.countCitations(response);
            }
            boolean answerable = hasEvidence && !isTimeoutResponse;
            boolean usedAnswerabilityGate = false;
            if (!answerable || RagOrchestrationService.isNoInfoResponse(response)) {
                if (!isTimeoutResponse) {
                    String fallback = RagOrchestrationService.buildEvidenceFallbackResponse(extractiveDocs, query);
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
            } else if (llmSuccess && citationCount == 0 && responsePolicy != null && responsePolicy.enforceCitations()) {
                response = NO_RELEVANT_RECORDS;
                answerable = false;
                usedAnswerabilityGate = true;
                citationCount = 0;
            } else if (llmSuccess && citationCount == 0 && responsePolicy != null && !responsePolicy.enforceCitations()) {
                String augmented = appendEvidenceIfNeeded(response, extractiveDocs, query, responsePolicy, true);
                evidenceAppendApplied = !augmented.equals(response);
                response = augmented;
                citationCount = RagOrchestrationService.countCitations(response);
            }
            if (answerable && responsePolicy != null && responsePolicy.appendEvidenceAlways()) {
                String augmented = appendEvidenceIfNeeded(response, extractiveDocs, query, responsePolicy, false);
                evidenceAppendApplied = evidenceAppendApplied || !augmented.equals(response);
                response = augmented;
                citationCount = RagOrchestrationService.countCitations(response);
            }
            if (query != null && DOC_ID_HINT_PATTERN.matcher(query).find() && (response == null || !response.contains("DOC_ID"))) {
                String forced = RagOrchestrationService.buildExtractiveResponse(extractiveDocs, query);
                if (!NO_RELEVANT_RECORDS.equals(forced)) {
                    response = forced;
                    citationCount = RagOrchestrationService.countCitations(response);
                }
            }
            String gateDetail = isTimeoutResponse
                ? "System response (timeout)"
                : (citationRepairApplied
                    ? "Citation repair applied (LLM rewrite with citations)"
                    : (rescueApplied
                        ? "Citation rescue applied (extractive evidence)"
                        : (excerptFallbackApplied
                            ? "No direct answer; showing excerpts"
                            : (usedAnswerabilityGate
                                ? "No answer returned (missing citations or no evidence)"
                                : "Answerable with citations"))));
            this.reasoningTracer.addStep(ReasoningStep.StepType.CITATION_VERIFICATION, "Answerability Gate", gateDetail, System.currentTimeMillis() - stepStart, Map.of("answerable", answerable, "citationCount", citationCount, "gateApplied", usedAnswerabilityGate, "rescueApplied", rescueApplied, "excerptFallbackApplied", excerptFallbackApplied));
            stepStart = System.currentTimeMillis();
            // Skip hallucination check for extractive or fully cited responses
            boolean skipHallucinationCheck = rescueApplied || excerptFallbackApplied
                || (citationCount > 0 && hasEvidence);
            QuCoRagService.HallucinationResult hallucinationResult = skipHallucinationCheck
                ? new QuCoRagService.HallucinationResult(0.0, List.of(), false)
                : this.quCoRagService.detectHallucinationRisk(response, query);
            boolean hasHallucinationRisk = hallucinationResult.isHighRisk();
            if (hasHallucinationRisk) {
                if (log.isWarnEnabled()) {
                    log.warn("QuCo-RAG: High hallucination risk detected (risk={:.3f}). Flagged entities: {}", hallucinationResult.riskScore(), hallucinationResult.flaggedEntities());
                }
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
            if (this.bidirectionalRagService != null && this.bidirectionalRagService.isEnabled() && hasEvidence && !this.hipaaPolicy.shouldDisableExperienceLearning(department)) {
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
            if (this.hipaaPolicy.shouldRedactResponses(department)) {
                response = this.piiRedactionService.redact(response, Boolean.TRUE).getRedactedContent();
            }
            this.auditService.logQuery(user, query, department, response, request);
            ArrayList<String> sources = new ArrayList<String>();
            Matcher matcher1 = Pattern.compile("\\[(?:Citation:\\s*)?(?:IMAGE:\\s*)?([^\\]]+\\.(pdf|txt|md|csv|xlsx|xls|doc|docx|pptx|html?|json|ndjson|log|png|jpg|jpeg|gif|tif|tiff|bmp))\\]", 2).matcher(response);
            while (matcher1.find()) {
                String source2 = matcher1.group(1).trim();
                if (sources.contains(source2)) continue;
                sources.add(source2);
            }
            Matcher matcher2 = Pattern.compile("\\(([^)]+\\.(pdf|txt|md|csv|xlsx|xls|doc|docx|pptx|html?|json|ndjson|log|png|jpg|jpeg|gif|tif|tiff|bmp))\\)", 2).matcher(response);
            while (matcher2.find()) {
                String source3 = matcher2.group(1);
                if (sources.contains(source3)) continue;
                sources.add(source3);
            }
            Matcher matcher3 = Pattern.compile("(?:Citation|Source|filename):\\s*([^\\s,]+\\.(pdf|txt|md|csv|xlsx|xls|doc|docx|pptx|html?|json|ndjson|log|png|jpg|jpeg|gif|tif|tiff|bmp))", 2).matcher(response);
            Matcher matcher4 = Pattern.compile("`([^`]+\\.(pdf|txt|md|csv|xlsx|xls|doc|docx|pptx|html?|json|ndjson|log|png|jpg|jpeg|gif|tif|tiff|bmp))`", 2).matcher(response);
            while (matcher4.find()) {
                String source4 = matcher4.group(1).trim();
                if (sources.contains(source4)) continue;
                sources.add(source4);
            }
            Matcher matcher5 = Pattern.compile("\\*{1,2}([^*]+\\.(pdf|txt|md|csv|xlsx|xls|doc|docx|pptx|html?|json|ndjson|log|png|jpg|jpeg|gif|tif|tiff|bmp))\\*{1,2}", 2).matcher(response);
            while (matcher5.find()) {
                String source5 = matcher5.group(1).trim();
                if (sources.contains(source5)) continue;
                sources.add(source5);
            }
            Matcher matcher6 = Pattern.compile("\"([^\"]+\\.(pdf|txt|md|csv|xlsx|xls|doc|docx|pptx|html?|json|ndjson|log|png|jpg|jpeg|gif|tif|tiff|bmp))\"", 2).matcher(response);
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
            if (responsePolicy != null) {
                metrics.put("editionPolicy", responsePolicy.edition().name());
                metrics.put("editionMaxTokens", responsePolicy.maxTokens());
                metrics.put("editionEnforceCitations", responsePolicy.enforceCitations());
                metrics.put("editionAppendEvidenceAlways", responsePolicy.appendEvidenceAlways());
                metrics.put("editionAppendEvidenceOnNoCitations", responsePolicy.appendEvidenceWhenNoCitations());
            }
            metrics.put("retrievalStrategies", retrievalStrategies);
            // Fix #8: Persist conversation memory (assistant response, message count, reasoning trace)
            if (effectiveSessionId != null && !this.hipaaPolicy.shouldDisableSessionMemory(department)
                    && this.conversationMemoryService != null
                    && this.sessionPersistenceService != null) {
                try {
                    List<String> responseSources = sources;
                    this.conversationMemoryService.saveAssistantMessage(user.getId(), effectiveSessionId, response, responseSources);
                    this.sessionPersistenceService.incrementMessageCount(effectiveSessionId);
                    if (completedTrace != null) {
                        this.sessionPersistenceService.persistTrace(completedTrace, effectiveSessionId);
                    }
                } catch (Exception memEx) {
                    if (log.isWarnEnabled()) {
                        log.warn("Session persistence failed (non-fatal): {}", memEx.getMessage());
                    }
                }
            }
            return new EnhancedAskResponse(response, completedTrace != null ? completedTrace.getStepsAsMaps() : List.of(), sources, metrics, completedTrace != null ? completedTrace.getTraceId() : null);
        }
        catch (Exception e) {
            log.error("Error in /ask/enhanced endpoint", (Throwable)e);
            ReasoningTrace errorTrace = this.reasoningTracer.endTrace();
            return new EnhancedAskResponse("An error occurred processing your query. Please try again or contact support. [ERR-1002]", errorTrace != null ? errorTrace.getStepsAsMaps() : List.of(), List.of(), Map.of("errorCode", "ERR-1002"), errorTrace != null ? errorTrace.getTraceId() : null);
        }
    }

    private record ResponsePolicy(LicenseService.Edition edition,
                                  int maxTokens,
                                  boolean enforceCitations,
                                  boolean appendEvidenceAlways,
                                  boolean appendEvidenceWhenNoCitations) {
    }

    private ResponsePolicy responsePolicyForEdition(LicenseService.Edition edition) {
        LicenseService.Edition resolved = edition != null ? edition : LicenseService.Edition.TRIAL;
        int baseTokens = Math.max(128, this.llmNumPredict);
        return switch (resolved) {
            case GOVERNMENT -> new ResponsePolicy(resolved, Math.max(baseTokens, 768), true, true, false);
            case MEDICAL -> new ResponsePolicy(resolved, Math.max(baseTokens, 768), true, true, false);
            case ENTERPRISE -> new ResponsePolicy(resolved, Math.max(baseTokens, 640), false, false, true);
            case TRIAL -> new ResponsePolicy(resolved, Math.max(baseTokens, 512), false, false, true);
        };
    }

    private ChatOptions optionsForPolicy(ResponsePolicy policy) {
        int tokens = policy != null ? policy.maxTokens : this.llmNumPredict;
        return OllamaOptions.create()
                .withModel(this.llmModel)
                .withTemperature(this.llmTemperature)
                .withNumPredict(Integer.valueOf(tokens));
    }

    private ChatOptions optionsForCitationRepair(ResponsePolicy policy) {
        int tokens = policy != null ? Math.max(256, policy.maxTokens) : this.llmNumPredict;
        // Citation repair should be as deterministic as possible to maximize adherence to format.
        double temperature = Math.min(this.llmTemperature, 0.2);
        return OllamaOptions.create()
                .withModel(this.llmModel)
                .withTemperature(temperature)
                .withNumPredict(Integer.valueOf(tokens));
    }

    private String repairCitationsIfNeeded(String draft,
                                          String query,
                                          String information,
                                          ResponsePolicy policy,
                                          Department department,
                                          List<Document> extractiveDocs,
                                          long llmTimeoutSeconds) {
        if (draft == null || draft.isBlank()) {
            return draft;
        }
        if (policy == null || !policy.enforceCitations()) {
            return draft;
        }
        if (RagOrchestrationService.countCitations(draft) > 0) {
            return draft;
        }
        if (information == null || information.isBlank()) {
            return draft;
        }

        Set<String> allowedSources = RagOrchestrationService.collectAllowedSources(extractiveDocs);

        String format = buildResponseFormat(policy, department);
        String repairSystem =
                "You are a citation repair assistant.\n\n" +
                "TASK: Rewrite the DRAFT ANSWER so that it is fully supported by the DOCUMENTS.\n\n" +
                "RULES:\n" +
                "- Use ONLY facts found in the DOCUMENTS section.\n" +
                "- After every sentence or bullet that contains a factual claim, append one or more citations in the exact format [filename].\n" +
                "- Only cite filenames that appear as document headers in the DOCUMENTS section.\n" +
                "- Do NOT invent citations. If a claim cannot be supported, remove it or rewrite it as uncertainty.\n" +
                "- Keep the answer informative and roughly the same structure.\n\n" +
                format + "\n" +
                "DOCUMENTS:\n" + information + "\n";

        String repairUser =
                "QUESTION:\n" + (query != null ? query : "") + "\n\n" +
                "DRAFT ANSWER:\n" + draft + "\n\n" +
                "Return only the revised answer.";

        try {
            String sysMsg = repairSystem.replace("{", "[").replace("}", "]");
            String userMsg = repairUser.replace("{", "[").replace("}", "]");
            long timeout = Math.max(5L, Math.min(llmTimeoutSeconds, 90L));
            ChatOptions options = optionsForCitationRepair(policy);
            String raw = CompletableFuture
                    .supplyAsync(() -> this.chatClient.prompt().system(sysMsg).user(userMsg).options(options).call().content())
                    .get(timeout, TimeUnit.SECONDS);

            if (raw == null || raw.isBlank()) {
                return draft;
            }
            String repaired = cleanLlmResponse(raw);
            if (RagOrchestrationService.countCitations(repaired) == 0) {
                return draft;
            }
            if (!allowedSources.isEmpty() && !RagOrchestrationService.citationsWithinScope(repaired, allowedSources)) {
                // Fail closed: if the model cited unknown sources, do not accept the repaired answer.
                if (log.isWarnEnabled()) {
                    log.warn("Citation repair produced out-of-scope citations; falling back to extractive evidence");
                }
                return draft;
            }
            return repaired;
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("Citation repair failed; falling back to extractive evidence: {}", e.getMessage());
            }
            return draft;
        }
    }

    private static Set<String> collectAllowedSources(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return Set.of();
        }
        HashSet<String> allowed = new HashSet<>();
        for (Document doc : docs) {
            if (doc == null || doc.getMetadata() == null) {
                continue;
            }
            Object source = doc.getMetadata().get("source");
            if (source == null) {
                source = doc.getMetadata().get("filename");
            }
            if (source == null) {
                continue;
            }
            String normalized = String.valueOf(source).trim().toLowerCase(Locale.ROOT);
            if (!normalized.isBlank()) {
                allowed.add(normalized);
            }
        }
        return allowed;
    }

    private static boolean citationsWithinScope(String response, Set<String> allowedSources) {
        if (response == null || response.isBlank() || allowedSources == null || allowedSources.isEmpty()) {
            return true;
        }
        Matcher matcher = STRICT_CITATION_FILENAME_PATTERN.matcher(response);
        while (matcher.find()) {
            String cited = matcher.group(1);
            if (cited == null) {
                continue;
            }
            String normalized = cited.trim().toLowerCase(Locale.ROOT);
            if (!allowedSources.contains(normalized)) {
                return false;
            }
        }
        return true;
    }

    private String buildResponseFormat(ResponsePolicy policy, Department department) {
        if (policy == null) {
            return "Return a concise summary with citations after each factual claim.";
        }
        String base = switch (policy.edition) {
            case GOVERNMENT -> """
                FORMAT:
                - Intelligence Summary (6-10 sentences, fully cited)
                - Key Facts (5-8 bullets with citations)
                - Operational Implications (3-5 bullets with citations)
                - Evidence Excerpts (3-6 short excerpts with citations)
                - If evidence is insufficient, state \"No relevant records found.\"
                """;
            case MEDICAL -> """
                FORMAT:
                - Clinical Summary (6-10 sentences, fully cited)
                - Key Facts (5-8 bullets with citations)
                - Compliance Notes (2-4 bullets; redactions already applied; do not reveal PHI)
                - Evidence Excerpts (3-6 short excerpts with citations)
                - If evidence is insufficient, state \"No relevant records found.\"
                """;
            case ENTERPRISE, TRIAL -> """
                FORMAT:
                - Executive Summary (6-10 sentences, fully cited where possible)
                - Key Findings (5-8 bullets with citations)
                - Implications / Next Steps (3-6 bullets, cite if factual)
                - If evidence is insufficient, state \"No relevant records found.\"
                """;
        };
        if (department == null) {
            return base;
        }
        return base + "\nSector: " + department.name() + "\n";
    }

    private String buildSystemPrompt(String information, ResponsePolicy policy, Department department) {
        if (information == null || information.isBlank()) {
            return "You are a helpful assistant. No documents are available. Respond: 'No internal records found for this query.'";
        }
        String format = buildResponseFormat(policy, department);
        return String.format(
            "You are a helpful assistant that answers questions based on the provided documents.\n\n" +
            "INSTRUCTIONS:\n" +
            "1. Read the documents below carefully\n" +
            "2. If an OVERVIEW section is present, use it for background only and do NOT cite it\n" +
            "3. Answer the question using ONLY information from the DOCUMENTS section\n" +
            "4. Include the exact numbers, names, dates, or facts as written in the documents\n" +
            "5. Cite sources using [filename] after each fact\n" +
            "6. If the answer is not in the documents, say \"No relevant records found.\"\n\n" +
            "%s\n" +
            "DOCUMENTS:\n%s\n\n" +
            "Answer the user's question based on the documents above.",
            format,
            information
        );
    }

    private String appendEvidenceIfNeeded(String response, List<Document> extractiveDocs, String query, ResponsePolicy policy, boolean missingCitations) {
        if (policy == null) {
            return response;
        }
        if (response == null || response.isBlank()) {
            return response;
        }
        if (policy.appendEvidenceAlways || (policy.appendEvidenceWhenNoCitations && missingCitations)) {
            if (response.startsWith("Based on the retrieved documents") || response.startsWith("No direct answer found")) {
                return response;
            }
            String evidence = RagOrchestrationService.buildExtractiveResponse(extractiveDocs, query);
            if (NO_RELEVANT_RECORDS.equals(evidence)) {
                return response;
            }
            return response + "\n\nEvidence Excerpts:\n" + evidence;
        }
        return response;
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
        // Normalize away common invisible prefix characters that can appear in some model responses
        // (e.g., BOM/zero-width spaces) so prefix checks don't fail silently.
        String normalized = trimmed.replace("\uFEFF", "");
        normalized = normalized.replaceAll("^[\\u200B\\u200C\\u200D\\u2060]+", "");

        // Some Ollama / tool-call misfires emit a Go-like placeholder such as:
        // "{object <nil> <nil> [] {\"expression\":{\"type\":\"string\"}}}"
        // This is not useful to end users and should never appear in the UI. Strip it early so
        // downstream gating can fall back to evidence excerpts instead of rendering garbage.
        if (normalized.startsWith("{object") && normalized.contains("<nil>")) {
            String[] lines = normalized.split("\\R");
            if (lines.length > 1) {
                String remainder = String.join("\n", Arrays.copyOfRange(lines, 1, lines.length)).trim();
                if (!remainder.isBlank()) {
                    if (log.isWarnEnabled()) {
                        log.warn("Stripped malformed tool-call placeholder from LLM response (kept remainder)");
                    }
                    return remainder;
                }
            }
            if (log.isWarnEnabled()) {
                log.warn("Stripped malformed tool-call placeholder from LLM response (no remainder)");
            }
            return "";
        }

        // Detect function call JSON pattern: {"name": "...", "parameters": {...}}
        if (normalized.startsWith("{\"name\"") && normalized.contains("\"parameters\"")) {
            try {
                // Try to extract the expression/content from the function call
                int exprStart = normalized.indexOf("\"expression\"");
                if (exprStart == -1) {
                    exprStart = normalized.indexOf("\"content\"");
                }
                if (exprStart == -1) {
                    exprStart = normalized.indexOf("\"value\"");
                }

                if (exprStart != -1) {
                    // Find the value after the key
                    int colonPos = normalized.indexOf(":", exprStart);
                    if (colonPos != -1) {
                        int valueStart = normalized.indexOf("\"", colonPos + 1);
                        if (valueStart != -1) {
                            int valueEnd = normalized.lastIndexOf("\"");
                            if (valueEnd > valueStart) {
                                String extracted = normalized.substring(valueStart + 1, valueEnd);
                                // Unescape JSON string
                                extracted = extracted.replace("\\\"", "\"")
                                                   .replace("\\n", "\n")
                                                   .replace("\\t", "\t");
                                if (log.isWarnEnabled()) {
                                    log.warn("Cleaned malformed function call response, extracted: {}...",
                                        extracted.substring(0, Math.min(100, extracted.length())));
                                }
                                return extracted;
                            }
                        }
                    }
                }

                // If we can't extract, return a warning message
                log.error("Unable to parse function call response: {}", normalized.substring(0, Math.min(200, normalized.length())));
                return "The system encountered a formatting issue. Please try rephrasing your question.";

            } catch (Exception e) {
                log.error("Error cleaning LLM response: {}", e.getMessage());
                return "An error occurred processing the response. Please try again.";
            }
        }

        // Also handle array of function calls: [{"name": ...}]
        if (normalized.startsWith("[{\"name\"")) {
            if (log.isWarnEnabled()) {
                log.warn("Detected array function call response, returning fallback");
            }
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
        boolean wantsDocId = query != null && DOC_ID_HINT_PATTERN.matcher(query).find();
        boolean wantsTitle = query != null && TITLE_HINT_PATTERN.matcher(query).find();
        boolean relationshipQuery = query != null && RELATIONSHIP_PATTERN.matcher(query).find();
        boolean wantsSummary = RagOrchestrationService.queryWantsSummary(query);
        int minKeywordHits = RagOrchestrationService.requiredKeywordHits(query, keywords);
        List<Document> orderedDocs = RagOrchestrationService.sortByKeywordPreference(docs, keywords);
        for (Document doc : orderedDocs) {
            String source = RagOrchestrationService.getDocumentSource(doc);
            boolean sourceHasKeywords = RagOrchestrationService.countKeywordHits(source, keywords) > 0;
            String snippet = RagOrchestrationService.buildDocIdTitleSnippet(doc, wantsDocId, wantsTitle);
            if (snippet.isBlank()) {
                snippet = RagOrchestrationService.extractSnippet(doc.getContent(), keywords, wantsMetrics, minKeywordHits, query);
            }
            boolean usedDerivedTitle = false;
            if (snippet.isBlank() && !wantsDocId && !wantsTitle && sourceHasKeywords) {
                String derived = RagOrchestrationService.deriveTitleFromSource(source);
                if (!derived.isBlank()) {
                    snippet = derived;
                    usedDerivedTitle = true;
                }
            }
            if (!snippet.isBlank() && sourceHasKeywords && RagOrchestrationService.countKeywordHits(snippet, keywords) == 0) {
                String derived = RagOrchestrationService.deriveTitleFromSource(source);
                if (!derived.isBlank()) {
                    snippet = derived;
                    usedDerivedTitle = true;
                }
            }
            if (usedDerivedTitle) {
                String extra = RagOrchestrationService.extractSnippetLenient(doc.getContent(), keywords);
                if (!extra.isBlank() && !extra.equals(snippet)) {
                    snippet = snippet + " — " + extra;
                }
            }
            if (relationshipQuery && !snippet.isBlank()) {
                List<String> missingKeywords = RagOrchestrationService.buildMissingKeywords(snippet, keywords);
                if (!missingKeywords.isEmpty()) {
                    String extra = RagOrchestrationService.extractSnippetForKeywords(doc.getContent(), missingKeywords);
                    if (!extra.isBlank() && !extra.equals(snippet)) {
                        snippet = snippet + " — " + extra;
                    }
                }
            }
            if (wantsSummary && snippet.length() < 160) {
                snippet = RagOrchestrationService.augmentSnippetForSummary(doc.getContent(), keywords, snippet);
            }
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
        StringBuilder summary = new StringBuilder("Relevant excerpts from the documents:\n\n");
        int added = 0;
        LinkedHashSet<String> seenSnippets = new LinkedHashSet<String>();
        Set<String> keywords = RagOrchestrationService.buildQueryKeywords(query);
        boolean wantsMetrics = RagOrchestrationService.queryWantsMetrics(query);
        boolean wantsDocId = query != null && DOC_ID_HINT_PATTERN.matcher(query).find();
        boolean wantsTitle = query != null && TITLE_HINT_PATTERN.matcher(query).find();
        boolean relationshipQuery = query != null && RELATIONSHIP_PATTERN.matcher(query).find();
        boolean wantsSummary = RagOrchestrationService.queryWantsSummary(query);
        int minKeywordHits = Math.max(1, RagOrchestrationService.requiredKeywordHits(query, keywords) - 1);
        List<Document> orderedDocs = RagOrchestrationService.sortByKeywordPreference(docs, keywords);
        for (Document doc : orderedDocs) {
            source = RagOrchestrationService.getDocumentSource(doc);
            boolean sourceHasKeywords = RagOrchestrationService.countKeywordHits(source, keywords) > 0;
            boolean usedDerivedTitle = false;
            snippet = RagOrchestrationService.buildDocIdTitleSnippet(doc, wantsDocId, wantsTitle);
            if (snippet.isBlank()) {
                snippet = RagOrchestrationService.extractSnippet(doc.getContent(), keywords, wantsMetrics, minKeywordHits, query);
            }
            if (snippet.isBlank()) {
                snippet = RagOrchestrationService.extractSnippetLenient(doc.getContent(), keywords);
            }
            if (snippet.isBlank() && !wantsDocId && !wantsTitle && sourceHasKeywords) {
                String derived = RagOrchestrationService.deriveTitleFromSource(source);
                if (!derived.isBlank()) {
                    snippet = derived;
                    usedDerivedTitle = true;
                }
            }
            if (!snippet.isBlank() && sourceHasKeywords && RagOrchestrationService.countKeywordHits(snippet, keywords) == 0) {
                String derived = RagOrchestrationService.deriveTitleFromSource(source);
                if (!derived.isBlank()) {
                    snippet = derived;
                    usedDerivedTitle = true;
                }
            }
            if (usedDerivedTitle) {
                String extra = RagOrchestrationService.extractSnippetLenient(doc.getContent(), keywords);
                if (!extra.isBlank() && !extra.equals(snippet)) {
                    snippet = snippet + " — " + extra;
                }
            }
            if (relationshipQuery && !snippet.isBlank()) {
                List<String> missingKeywords = RagOrchestrationService.buildMissingKeywords(snippet, keywords);
                if (!missingKeywords.isEmpty()) {
                    String extra = RagOrchestrationService.extractSnippetForKeywords(doc.getContent(), missingKeywords);
                    if (!extra.isBlank() && !extra.equals(snippet)) {
                        snippet = snippet + " — " + extra;
                    }
                }
            }
            if (wantsSummary && snippet.length() < 160) {
                snippet = RagOrchestrationService.augmentSnippetForSummary(doc.getContent(), keywords, snippet);
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

    private static boolean queryWantsSummary(String query) {
        return query != null && SUMMARY_HINT_PATTERN.matcher(query).find();
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
        boolean hasAnyContent = false;
        for (Document doc : docs) {
            String[] lines;
            String content = doc.getContent();
            if (content == null || content.isBlank()) continue;
            hasAnyContent = true;
            for (String rawLine : lines = content.split("\\R")) {
                String trimmed = rawLine.trim();
                if (trimmed.isEmpty() || RagOrchestrationService.isTableSeparator(trimmed) || RagOrchestrationService.isFileMarkerLine(trimmed) || !RagOrchestrationService.isRelevantLine(trimmed, keywords, wantsMetrics, minKeywordHits)) continue;
                return true;
            }
        }
        // Treat any non-empty retrieved document set as evidence for answerability.
        // Even if a query mentions "metrics", we may only have qualitative support and should still answer (with citations).
        return hasAnyContent;
    }

    private static List<Document> sortByKeywordPreference(List<Document> docs, Set<String> keywords) {
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        return docs.stream().sorted((a, b) -> {
            String aSource = RagOrchestrationService.getDocumentSource(a);
            String bSource = RagOrchestrationService.getDocumentSource(b);
            int aHits = RagOrchestrationService.countKeywordHits(aSource, keywords) + RagOrchestrationService.countKeywordHits(a.getContent(), keywords);
            int bHits = RagOrchestrationService.countKeywordHits(bSource, keywords) + RagOrchestrationService.countKeywordHits(b.getContent(), keywords);
            return Integer.compare(bHits, aHits);
        }).toList();
    }

    private static String extractSnippet(String content, Set<String> keywords, boolean wantsMetrics, int minKeywordHits, String query) {
        if (content == null) {
            return "";
        }
        String[] lines = content.split("\\R");
        String bestCandidate = "";
        int bestScore = -1;
        String bestMetricCandidate = "";
        int bestMetricScore = -1;
        String bestNameCandidate = "";
        int bestNameScore = -1;
        List<String> idTokens = RagOrchestrationService.extractIdentifierTokens(query);
        List<String> phraseHints = RagOrchestrationService.extractProperNounPhrases(query);
        boolean wantsDocId = query != null && DOC_ID_HINT_PATTERN.matcher(query).find();
        boolean wantsTitle = query != null && TITLE_HINT_PATTERN.matcher(query).find();
        boolean wantsMetadata = wantsDocId || wantsTitle || query != null && (DATE_HINT_PATTERN.matcher(query).find() || CLASSIFICATION_HINT_PATTERN.matcher(query).find() || AUTHOR_HINT_PATTERN.matcher(query).find());
        String docIdLine = null;
        String titleLine = null;
        if (wantsDocId || wantsTitle) {
            for (String rawLine : lines) {
                String trimmed = rawLine.trim();
                if (trimmed.isEmpty()) continue;
                String upper = trimmed.toUpperCase(Locale.ROOT);
                if (wantsDocId && docIdLine == null && (upper.startsWith("DOC_ID") || upper.startsWith("DOCID") || upper.startsWith("DOC ID") || upper.startsWith("DOCUMENT ID"))) {
                    docIdLine = RagOrchestrationService.sanitizeSnippet(trimmed);
                }
                if (wantsTitle && titleLine == null && upper.startsWith("TITLE")) {
                    titleLine = RagOrchestrationService.sanitizeSnippet(trimmed);
                }
                if ((!wantsDocId || docIdLine != null) && (!wantsTitle || titleLine != null)) {
                    break;
                }
            }
            if (wantsDocId && wantsTitle && docIdLine != null && titleLine != null) {
                return (docIdLine + " | " + titleLine).trim();
            }
            if (wantsDocId && docIdLine != null && titleLine == null) {
                return docIdLine.trim();
            }
            if (wantsTitle && titleLine != null && docIdLine == null) {
                return titleLine.trim();
            }
        }

        // Check if query is looking for a person/name (contains name-context keywords)
        boolean wantsName = keywords.stream().anyMatch(k ->
            NAME_CONTEXT_PATTERN.matcher(k).find() ||
            k.equalsIgnoreCase("sponsor") || k.equalsIgnoreCase("author") ||
            k.equalsIgnoreCase("lead") || k.equalsIgnoreCase("director") ||
            k.equalsIgnoreCase("officer") || k.equalsIgnoreCase("manager"));

        // Search all lines, prioritizing those with the strongest keyword + value match
        for (String rawLine : lines) {
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty() || RagOrchestrationService.isTableSeparator(trimmed) || RagOrchestrationService.isFileMarkerLine(trimmed)) continue;
            if (!wantsMetadata && RagOrchestrationService.isBoilerplateLine(trimmed)) {
                continue;
            }

            // Count keyword hits in this line
            int keywordHits = RagOrchestrationService.countKeywordHits(trimmed, keywords);
            boolean hasIdToken = RagOrchestrationService.containsAnyToken(trimmed, idTokens);
            boolean hasPhraseHint = RagOrchestrationService.containsAnyPhrase(trimmed, phraseHints);
            boolean hasDocId = trimmed.toUpperCase(Locale.ROOT).startsWith("DOC_ID")
                || trimmed.toUpperCase(Locale.ROOT).startsWith("DOCID")
                || trimmed.toUpperCase(Locale.ROOT).startsWith("DOC ID")
                || trimmed.toUpperCase(Locale.ROOT).startsWith("DOCUMENT ID");
            boolean hasTitle = trimmed.toUpperCase(Locale.ROOT).startsWith("TITLE");
            boolean isMetadataLine = RagOrchestrationService.isMetadataLine(trimmed);

            // Check if this line contains a metric value (dollar amounts, numbers, percentages)
            boolean hasMetric = NUMERIC_PATTERN.matcher(trimmed).find() ||
                               trimmed.matches(".*\\$[\\d,]+.*") ||
                               trimmed.matches(".*\\d+%.*") ||
                               trimmed.toLowerCase().contains("million") ||
                               trimmed.toLowerCase().contains("billion");

            // Check if line has name context (like "Executive Sponsor: Dr. Robert Chen")
            boolean hasNameContext = NAME_CONTEXT_PATTERN.matcher(trimmed.toLowerCase()).find();

            // Skip lines without keywords (unless they have metrics/names and we want those)
            if (keywordHits < minKeywordHits && !(wantsMetrics && hasMetric) && !(wantsName && hasNameContext) && !hasIdToken && !hasPhraseHint) {
                continue;
            }
            if (!wantsMetadata && isMetadataLine && keywordHits == 0 && !hasIdToken && !hasPhraseHint) {
                continue;
            }

            String candidate = trimmed;
            if (trimmed.contains("|")) {
                String summarized = RagOrchestrationService.summarizeTableRow(trimmed, keywords, wantsMetrics, minKeywordHits);
                if (summarized.isBlank()) continue;
                candidate = summarized;
            }

            int score = keywordHits * 10;
            if (hasMetric) {
                score += wantsMetrics ? 10 : 4;
            }
            if (hasNameContext) {
                score += wantsName ? 10 : 4;
            }
            if (hasIdToken) {
                score += 30;
            }
            if (hasPhraseHint) {
                score += 20;
            }
            if (wantsDocId && hasDocId) {
                score += 40;
            }
            if (wantsTitle && hasTitle) {
                score += 25;
            }
            if (keywordHits >= minKeywordHits && minKeywordHits > 1) {
                score += 2;
            }
            if (!candidate.isEmpty() && candidate.length() < 120) {
                score += 1;
            }
            if (score > bestScore) {
                bestScore = score;
                bestCandidate = candidate;
            }
            if (wantsMetrics && hasMetric) {
                int metricScore = score + 15;
                String lower = trimmed.toLowerCase(Locale.ROOT);
                if (trimmed.contains("$") || trimmed.contains("%") || lower.contains("million") || lower.contains("billion")) {
                    metricScore += 10;
                }
                if (metricScore > bestMetricScore) {
                    bestMetricScore = metricScore;
                    bestMetricCandidate = candidate;
                }
            }
            if (wantsName && hasNameContext) {
                int nameScore = score + 15;
                if (nameScore > bestNameScore) {
                    bestNameScore = nameScore;
                    bestNameCandidate = candidate;
                }
            }
        }

        if (bestCandidate.isEmpty()) {
            return "";
        }
        String result = RagOrchestrationService.sanitizeSnippet(bestCandidate);
        if (wantsMetrics && !RagOrchestrationService.hasMetricValue(result) && !bestMetricCandidate.isEmpty()) {
            result = RagOrchestrationService.sanitizeSnippet(bestMetricCandidate);
        }
        if (wantsName && !RagOrchestrationService.hasNameContext(result) && !bestNameCandidate.isEmpty()) {
            result = RagOrchestrationService.sanitizeSnippet(bestNameCandidate);
        }
        int maxLen = 240;
        if (result.length() > maxLen) {
            result = result.substring(0, maxLen - 3).trim() + "...";
        }
        return result;
    }

    private static String buildDocIdTitleSnippet(Document doc, boolean wantsDocId, boolean wantsTitle) {
        if (doc == null || (!wantsDocId && !wantsTitle)) {
            return "";
        }
        Map<String, Object> metadata = doc.getMetadata();
        String source = RagOrchestrationService.getDocumentSource(doc);
        String docId = null;
        String title = null;
        if (wantsDocId) {
            docId = RagOrchestrationService.findMetadataValue(metadata, DOC_ID_META_KEYS);
            if (docId == null || docId.isBlank()) {
                docId = RagOrchestrationService.extractDocIdFromContent(doc.getContent());
            }
            if (docId == null || docId.isBlank()) {
                docId = RagOrchestrationService.deriveDocIdFromSource(source);
            }
        }
        if (wantsTitle) {
            title = RagOrchestrationService.findMetadataValue(metadata, TITLE_META_KEYS);
            if (title == null || title.isBlank()) {
                title = RagOrchestrationService.extractTitleFromContent(doc.getContent());
            }
            if (title == null || title.isBlank()) {
                title = RagOrchestrationService.deriveTitleFromSource(source);
            }
        }
        String docPart = RagOrchestrationService.formatDocId(docId, wantsDocId);
        String titlePart = RagOrchestrationService.formatTitle(title, wantsTitle);
        if (!docPart.isBlank() && !titlePart.isBlank()) {
            return (docPart + " | " + titlePart).trim();
        }
        if (!docPart.isBlank()) {
            return docPart.trim();
        }
        if (!titlePart.isBlank()) {
            return titlePart.trim();
        }
        return "";
    }

    private static String findMetadataValue(Map<String, Object> metadata, List<String> keys) {
        if (metadata == null || metadata.isEmpty() || keys == null || keys.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            Object value = metadata.get(key);
            if (value instanceof String str && !str.isBlank()) {
                return str.trim();
            }
        }
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            String metaKey = entry.getKey();
            if (metaKey == null) {
                continue;
            }
            for (String key : keys) {
                if (!metaKey.equalsIgnoreCase(key)) continue;
                Object value = entry.getValue();
                if (value instanceof String str && !str.isBlank()) {
                    return str.trim();
                }
            }
        }
        return null;
    }

    private static String deriveDocIdFromSource(String source) {
        if (source == null) {
            return "";
        }
        String base = source.trim();
        if (base.isBlank()) {
            return "";
        }
        base = base.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0 && slash < base.length() - 1) {
            base = base.substring(slash + 1);
        }
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        return base.trim();
    }

    private static String deriveTitleFromSource(String source) {
        String base = RagOrchestrationService.deriveDocIdFromSource(source);
        if (base.isBlank()) {
            return "";
        }
        String cleaned = base.replaceAll("[_\\-]+", " ");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        return RagOrchestrationService.toTitleCase(cleaned);
    }

    private static String extractDocIdFromContent(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        for (String rawLine : content.split("\\R")) {
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty() || RagOrchestrationService.isTableSeparator(trimmed) || RagOrchestrationService.isFileMarkerLine(trimmed)) {
                continue;
            }
            Matcher matcher = DOC_ID_VALUE_PATTERN.matcher(trimmed);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return "";
    }

    private static String extractTitleFromContent(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        for (String rawLine : content.split("\\R")) {
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty() || RagOrchestrationService.isTableSeparator(trimmed) || RagOrchestrationService.isFileMarkerLine(trimmed)) {
                continue;
            }
            if (TITLE_LINE_SKIP_PATTERN.matcher(trimmed).find()) {
                continue;
            }
            if (trimmed.length() < 3) {
                continue;
            }
            return RagOrchestrationService.sanitizeSnippet(trimmed);
        }
        return "";
    }

    private static String formatDocId(String docId, boolean wantsDocId) {
        if (!wantsDocId || docId == null) {
            return "";
        }
        String trimmed = docId.trim();
        if (trimmed.isBlank()) {
            return "";
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (upper.startsWith("DOC_ID") || upper.startsWith("DOCID") || upper.startsWith("DOCUMENT ID")) {
            return trimmed;
        }
        return "DOC_ID: " + trimmed;
    }

    private static String formatTitle(String title, boolean wantsTitle) {
        if (!wantsTitle || title == null) {
            return "";
        }
        String trimmed = title.trim();
        if (trimmed.isBlank()) {
            return "";
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (upper.startsWith("TITLE")) {
            return trimmed;
        }
        return "TITLE: " + trimmed;
    }

    private static String toTitleCase(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String[] parts = value.trim().split("\\s+");
        ArrayList<String> output = new ArrayList<>();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            String token = part.trim();
            String upper = token.toUpperCase(Locale.ROOT);
            boolean hasDigit = token.chars().anyMatch(Character::isDigit);
            if (token.length() <= 2 || token.equals(upper) || hasDigit) {
                output.add(upper);
                continue;
            }
            String lower = token.toLowerCase(Locale.ROOT);
            output.add(Character.toUpperCase(lower.charAt(0)) + lower.substring(1));
        }
        return String.join(" ", output).trim();
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
            if (trimmed.isEmpty() || RagOrchestrationService.isTableSeparator(trimmed) || RagOrchestrationService.isFileMarkerLine(trimmed) || RagOrchestrationService.isMetadataLine(trimmed) || RagOrchestrationService.isBoilerplateLine(trimmed) || !keywords.isEmpty() && !RagOrchestrationService.containsKeyword(trimmed, keywords)) continue;
            candidate = trimmed;
            break;
        }
        if (candidate.isEmpty()) {
            for (String rawLine : lines) {
                trimmed = rawLine.trim();
                if (trimmed.isEmpty() || RagOrchestrationService.isTableSeparator(trimmed) || RagOrchestrationService.isFileMarkerLine(trimmed) || RagOrchestrationService.isMetadataLine(trimmed) || RagOrchestrationService.isBoilerplateLine(trimmed)) continue;
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

    private static boolean hasMetricValue(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return NUMERIC_PATTERN.matcher(text).find() || text.contains("$") || text.contains("%") || lower.contains("million") || lower.contains("billion");
    }

    private static boolean hasNameContext(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return NAME_CONTEXT_PATTERN.matcher(text.toLowerCase(Locale.ROOT)).find();
    }

    private static List<String> buildMissingKeywords(String snippet, Set<String> keywords) {
        if (snippet == null || snippet.isBlank() || keywords == null || keywords.isEmpty()) {
            return List.of();
        }
        String lower = snippet.toLowerCase(Locale.ROOT);
        return keywords.stream()
            .filter(k -> !lower.contains(k))
            .sorted((a, b) -> Integer.compare(b.length(), a.length()))
            .limit(5)
            .toList();
    }

    private static String extractSnippetForKeywords(String content, List<String> keywords) {
        if (content == null || keywords == null || keywords.isEmpty()) {
            return "";
        }
        String[] lines = content.split("\\R");
        String bestCandidate = "";
        int bestScore = -1;
        for (String rawLine : lines) {
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty() || RagOrchestrationService.isTableSeparator(trimmed) || RagOrchestrationService.isFileMarkerLine(trimmed) || RagOrchestrationService.isMetadataLine(trimmed) || RagOrchestrationService.isBoilerplateLine(trimmed)) {
                continue;
            }
            String lower = trimmed.toLowerCase(Locale.ROOT);
            int score = 0;
            for (String keyword : keywords) {
                if (!lower.contains(keyword)) continue;
                score += Math.max(1, keyword.length());
            }
            if (score <= 0) {
                continue;
            }
            if (score > bestScore) {
                bestScore = score;
                bestCandidate = trimmed;
            }
        }
        if (bestCandidate.isEmpty()) {
            return "";
        }
        String result = RagOrchestrationService.sanitizeSnippet(bestCandidate);
        int maxLen = 240;
        if (result.length() > maxLen) {
            result = result.substring(0, maxLen - 3).trim() + "...";
        }
        return result;
    }

    private static String extractSupplementalSnippet(String content, Set<String> keywords, String primarySnippet) {
        if (content == null) {
            return "";
        }
        String primaryLower = primarySnippet == null ? "" : primarySnippet.toLowerCase(Locale.ROOT);
        String[] lines = content.split("\\R");
        for (String rawLine : lines) {
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty() || RagOrchestrationService.isTableSeparator(trimmed) || RagOrchestrationService.isFileMarkerLine(trimmed) || RagOrchestrationService.isMetadataLine(trimmed) || RagOrchestrationService.isBoilerplateLine(trimmed)) {
                continue;
            }
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (!keywords.isEmpty() && !RagOrchestrationService.containsKeyword(trimmed, keywords)) {
                continue;
            }
            if (!primaryLower.isBlank() && lower.contains(primaryLower)) {
                continue;
            }
            String candidate = RagOrchestrationService.sanitizeSnippet(trimmed);
            if (candidate.isBlank()) {
                continue;
            }
            int maxLen = 240;
            if (candidate.length() > maxLen) {
                candidate = candidate.substring(0, maxLen - 3).trim() + "...";
            }
            return candidate;
        }
        return "";
    }

    /**
     * Builds a more substantive snippet for "summarize" style queries without relying on the LLM.
     * This is used as part of the extractive fallback path (e.g., when citations are required or the
     * model can't answer). Keep output reasonably bounded to avoid flooding the UI.
     */
    private static String augmentSnippetForSummary(String content, Set<String> keywords, String snippet) {
        if (content == null || content.isBlank()) {
            return snippet == null ? "" : snippet;
        }
        int targetLen = 180;
        int maxLen = 360;
        int maxParts = 5;

        LinkedHashSet<String> parts = new LinkedHashSet<String>();
        HashSet<String> seenLower = new HashSet<String>();
        if (snippet != null && !snippet.isBlank()) {
            parts.add(snippet);
            seenLower.add(snippet.toLowerCase(Locale.ROOT));
        }

        while (parts.size() < maxParts) {
            String combined = String.join((CharSequence)" - ", parts).trim();
            if (combined.length() >= targetLen && !combined.isBlank()) {
                break;
            }
            String extra = RagOrchestrationService.extractSupplementalSnippetForSummary(content, keywords, seenLower);
            if (extra.isBlank()) {
                break;
            }
            if (parts.add(extra)) {
                seenLower.add(extra.toLowerCase(Locale.ROOT));
            } else {
                break;
            }
        }

        String combined = String.join((CharSequence)" - ", parts).trim();
        if (combined.length() > maxLen) {
            combined = combined.substring(0, maxLen - 3).trim() + "...";
        }
        return combined;
    }

    private static String extractSupplementalSnippetForSummary(String content, Set<String> keywords, Set<String> seenLower) {
        if (content == null) {
            return "";
        }
        String[] lines = content.split("\\R");
        for (String rawLine : lines) {
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty() || RagOrchestrationService.isTableSeparator(trimmed) || RagOrchestrationService.isFileMarkerLine(trimmed) || RagOrchestrationService.isMetadataLine(trimmed) || RagOrchestrationService.isBoilerplateLine(trimmed)) {
                continue;
            }
            String candidate = RagOrchestrationService.sanitizeSnippet(trimmed);
            if (candidate.isBlank()) {
                continue;
            }
            String lower = candidate.toLowerCase(Locale.ROOT);
            if (seenLower != null && seenLower.contains(lower)) {
                continue;
            }

            boolean hasKeyword = keywords != null && !keywords.isEmpty() && RagOrchestrationService.containsKeyword(candidate, keywords);
            boolean hasMetric = NUMERIC_PATTERN.matcher(candidate).find()
                || candidate.contains("%")
                || candidate.contains("$")
                || lower.contains("p-value")
                || lower.contains("confidence interval");
            boolean hasKeyValue = candidate.contains(":") && candidate.length() <= 160;

            if (!hasKeyword && !hasMetric && !hasKeyValue) {
                continue;
            }
            return RagOrchestrationService.truncateSnippet(candidate);
        }
        return "";
    }

    private static String extractAnySnippet(String content) {
        String[] lines;
        if (content == null) {
            return "";
        }
        for (String rawLine : lines = content.split("\\R")) {
            String candidate;
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty() || RagOrchestrationService.isTableSeparator(trimmed) || RagOrchestrationService.isFileMarkerLine(trimmed) || RagOrchestrationService.isMetadataLine(trimmed) || RagOrchestrationService.isBoilerplateLine(trimmed) || (candidate = RagOrchestrationService.sanitizeSnippet(trimmed)).isBlank()) continue;
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

    private static boolean isFileMarkerLine(String line) {
        if (line == null) {
            return false;
        }
        String trimmed = line.trim();
        return FILE_MARKER_PATTERN.matcher(trimmed).matches() || FILE_MARKER_INLINE_PATTERN.matcher(trimmed).matches();
    }

    private static boolean isMetadataLine(String line) {
        if (line == null) {
            return false;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        return METADATA_LINE_PATTERN.matcher(trimmed).find();
    }

    private static boolean isBoilerplateLine(String line) {
        if (line == null) {
            return false;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        return BOILERPLATE_LINE_PATTERN.matcher(trimmed).find();
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
        if (isFileMarkerLine(cleaned)) {
            return "";
        }
        cleaned = cleaned.replaceAll("^\\s*[#>*-]+\\s*", "");
        cleaned = cleaned.replaceAll("\\*{1,2}", "");
        cleaned = cleaned.replaceAll("_{1,2}", "");
        cleaned = cleaned.replaceAll("`", "");
        cleaned = cleaned.replaceAll("(?i)^=+\\s*file\\s*:\\s*.+?=+$", "");
        cleaned = cleaned.replaceAll("(?i)^file\\s*:\\s*.+$", "");
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
        if (!Set.of("GOVERNMENT", "MEDICAL", "ENTERPRISE").contains(dept)) {
            if (log.isWarnEnabled()) {
                log.warn("SECURITY: Invalid department value in filter: {}", dept);
            }
            return List.of();
        }
        String workspaceId = WorkspaceContext.getCurrentWorkspaceId();
        Set<String> queryKeywords = RagOrchestrationService.buildQueryKeywords(query);
        if (this.hybridRagService != null && this.hybridRagService.isEnabled()) {
            try {
                HybridRagService.HybridRetrievalResult result = this.hybridRagService.retrieve(query, dept);
                List<Document> scoped = this.filterDocumentsByFiles(result.documents(), activeFiles);
                if (!scoped.isEmpty()) {
                    return this.sortDocumentsDeterministically(new ArrayList<>(scoped));
                }
            }
            catch (Exception e) {
                if (log.isWarnEnabled()) {
                    log.warn("HybridRAG failed, falling back to local reranking: {}", e.getMessage());
                }
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
            if (log.isWarnEnabled()) {
                log.warn("Vector/Keyword search FAILED (DB/Embedding Offline). Engaging RAM Cache Fallback.", (Throwable)e);
            }
            return this.searchInMemoryCache(query, dept, activeFiles);
        }
        LinkedHashSet<Document> merged = new LinkedHashSet<>(semanticResults);
        merged.addAll(keywordResults);
        List<Document> scoped = this.filterDocumentsByFiles(new ArrayList<>(merged), activeFiles);
        if (this.shouldAugmentWithCache(query)) {
            List<Document> cachedMatches = this.searchInMemoryCache(query, dept, activeFiles);
            if (!cachedMatches.isEmpty()) {
                scoped = new ArrayList<>(new LinkedHashSet<>(scoped));
                scoped.addAll(cachedMatches);
            }
        }
        if (scoped.isEmpty() && activeFiles != null && !activeFiles.isEmpty()) {
            List<Document> cacheScoped = this.loadActiveFilesFromCache(dept, activeFiles, queryKeywords);
            if (!cacheScoped.isEmpty()) {
                boostKeywordMatches(cacheScoped, query);
                return this.sortDocumentsDeterministically(cacheScoped);
            }
        }
        if (scoped.isEmpty()) {
            List<Document> keywordSweep = this.attemptKeywordSweep(query, dept, activeFiles);
            if (!keywordSweep.isEmpty()) {
                return keywordSweep;
            }
            if (log.isWarnEnabled()) {
                log.warn("Vector/Keyword search yielded 0 results. Engaging RAM Cache Fallback.");
            }
            return this.searchInMemoryCache(query, dept, activeFiles);
        }
        // Boost documents with exact phrase matches from query
        boostKeywordMatches(scoped, query);
        return this.sortDocumentsDeterministically(scoped);
    }

    private static List<String> extractIdentifierTokens(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\b[A-Z]{2,}[A-Z0-9]*-\\d{2,}(?:-\\d{1,})?\\b").matcher(query);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private static List<String> extractProperNounPhrases(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<String> phrases = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\b([A-Z][a-z0-9]+(?:\\s+[A-Z][a-z0-9]+)+)\\b").matcher(query);
        while (matcher.find()) {
            phrases.add(matcher.group());
        }
        return phrases;
    }

    private static boolean containsAnyToken(String text, List<String> tokens) {
        if (text == null || tokens == null || tokens.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            if (lower.contains(token.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAnyPhrase(String text, List<String> phrases) {
        if (text == null || phrases == null || phrases.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String phrase : phrases) {
            if (lower.contains(phrase.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldAugmentWithCache(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String lower = query.toLowerCase(Locale.ROOT);
        if (NAME_CONTEXT_PATTERN.matcher(lower).find()) {
            return true;
        }
        return !extractIdentifierTokens(query).isEmpty() || !extractProperNounPhrases(query).isEmpty();
    }

    private List<Document> attemptKeywordSweep(String query, String dept, List<String> activeFiles) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String workspaceId = WorkspaceContext.getCurrentWorkspaceId();
        Set<String> keywords = RagOrchestrationService.buildQueryKeywords(query);
        if (keywords.isEmpty()) {
            return List.of();
        }
        int minKeywordHits = Math.max(1, RagOrchestrationService.requiredKeywordHits(query, keywords));
        List<Document> sweepResults = List.of();
        try {
            sweepResults = this.vectorStore.similaritySearch(SearchRequest.query(query)
                    .withTopK(100)
                    .withSimilarityThreshold(-1.0)
                    .withFilterExpression(FilterExpressionBuilder.forDepartmentAndWorkspace(dept, workspaceId)));
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("Keyword sweep with negative threshold failed: {}", e.getMessage());
            }
            try {
                sweepResults = this.vectorStore.similaritySearch(SearchRequest.query(query)
                        .withTopK(100)
                        .withSimilarityThreshold(0.0)
                        .withFilterExpression(FilterExpressionBuilder.forDepartmentAndWorkspace(dept, workspaceId)));
            } catch (Exception retry) {
                if (log.isWarnEnabled()) {
                    log.warn("Keyword sweep fallback failed: {}", retry.getMessage());
                }
                return List.of();
            }
        }
        List<Document> scoped = this.filterDocumentsByFiles(sweepResults, activeFiles);
        scoped = this.filterTestArtifacts(scoped, query);
        if (scoped.isEmpty()) {
            return List.of();
        }
        List<Document> filtered = scoped.stream().filter(doc -> {
            String content = doc.getContent();
            String source = RagOrchestrationService.getDocumentSource(doc);
            int hits = RagOrchestrationService.countKeywordHits(content, keywords) + RagOrchestrationService.countKeywordHits(source, keywords);
            return hits >= minKeywordHits;
        }).collect(Collectors.toList());
        if (filtered.isEmpty()) {
            return List.of();
        }
        for (Document doc : filtered) {
            String content = doc.getContent();
            String source = RagOrchestrationService.getDocumentSource(doc);
            int hits = RagOrchestrationService.countKeywordHits(content, keywords) + RagOrchestrationService.countKeywordHits(source, keywords);
            doc.getMetadata().put("score", (double)Math.max(hits, 1));
        }
        boostKeywordMatches(filtered, query);
        if (log.isWarnEnabled()) {
            log.warn("Keyword sweep recovered {} documents for query {}", filtered.size(), LogSanitizer.querySummary(query));
        }
        return this.sortDocumentsDeterministically(filtered);
    }

    private RetrievalContext retrieveContext(String query, String dept, List<String> activeFiles, AdaptiveRagService.RoutingResult routing, boolean highUncertainty, boolean deepAnalysis) {
        return retrieveContext(query, dept, activeFiles, routing, highUncertainty, deepAnalysis, RetrievalOverrides.DEFAULTS);
    }

    // Fix #9: Overloaded method accepting per-request RAG engine overrides from frontend settings.
    // Per-request overrides can only DISABLE engines; they cannot force-enable server-disabled engines.
    private RetrievalContext retrieveContext(String query, String dept, List<String> activeFiles, AdaptiveRagService.RoutingResult routing, boolean highUncertainty, boolean deepAnalysis, RetrievalOverrides overrides) {
        boolean hydeAllowed = overrides.useHyde() == null || overrides.useHyde();
        boolean graphRagAllowed = overrides.useGraphRag() == null || overrides.useGraphRag();
        boolean rerankingAllowed = overrides.useReranking() == null || overrides.useReranking();
        boolean complexQuery = routing != null && routing.decision() == AdaptiveRagService.RoutingDecision.DOCUMENT;
        boolean relationshipQuery = RELATIONSHIP_PATTERN.matcher(query).find();
        boolean longQuery = query.split("\\s+").length > 15;
        boolean advancedNeeded = complexQuery || relationshipQuery || longQuery || highUncertainty;
        Set<ModalityRouter.ModalityTarget> modalities = this.modalityRouter != null ? this.modalityRouter.route(query) : Set.of(ModalityRouter.ModalityTarget.TEXT);
        boolean allowVisual = true;
        try {
            Department department = Department.valueOf(dept.toUpperCase());
            allowVisual = !this.hipaaPolicy.shouldDisableVisual(department);
        } catch (IllegalArgumentException ignored) {
        }
        if (!allowVisual) {
            modalities = Set.of(ModalityRouter.ModalityTarget.TEXT);
        }

        ArrayList<String> strategies = new ArrayList<>();
        List<Document> textDocs = new ArrayList<>();
        String globalContext = "";
        RagPartService.RagPartResult ragPartResult = null;

        if (activeFiles != null && !activeFiles.isEmpty()) {
            List<Document> cacheScoped = this.loadActiveFilesFromCache(dept, activeFiles, RagOrchestrationService.buildQueryKeywords(query));
            if (!cacheScoped.isEmpty()) {
                textDocs.addAll(cacheScoped);
                strategies.add("ActiveFileCache");
            }
        }

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

        if (textDocs.isEmpty() && rerankingAllowed) {
            textDocs.addAll(this.performHybridRerankingTracked(query, dept, activeFiles));
            if (!textDocs.isEmpty()) {
                strategies.add("FallbackRerank");
            }
        }

        // HGMem (GraphRAG): use deepAnalysis param or fallback to advancedNeeded heuristic
        // Fix #9: respect per-request graphRagAllowed override from frontend toggle
        if (graphRagAllowed && this.hgMemQueryEngine != null && (deepAnalysis || advancedNeeded)) {
            HGMemQueryEngine.HGMemResult hgResult = this.hgMemQueryEngine.query(query, dept, deepAnalysis);
            if (!hgResult.documents().isEmpty()) {
                textDocs.addAll(hgResult.documents());
                strategies.add(deepAnalysis ? "HGMem-Deep" : "HGMem");
            }
        }

        if (this.agenticRagOrchestrator != null && this.agenticRagOrchestrator.isEnabled() && advancedNeeded) {
            AgenticRagOrchestrator.AgenticResult agenticResult = this.agenticRagOrchestrator.process(query, dept, hydeAllowed);
            if (agenticResult.sources() != null && !agenticResult.sources().isEmpty()) {
                textDocs.addAll(agenticResult.sources());
                strategies.add("Agentic");
            }
        }

        ArrayList<Document> visualDocs = new ArrayList<>();
        ArrayList<MegaRagService.CrossModalEdge> edges = new ArrayList<>();
        if (allowVisual && this.megaRagService != null && this.megaRagService.isEnabled() && (modalities.contains(ModalityRouter.ModalityTarget.VISUAL) || modalities.contains(ModalityRouter.ModalityTarget.CROSS_MODAL))) {
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

        if (this.shouldAugmentWithCache(query)) {
            List<Document> cachedMatches = this.searchInMemoryCache(query, dept, activeFiles);
            if (!cachedMatches.isEmpty()) {
                textDocs.addAll(cachedMatches);
                strategies.add("CacheSweep");
            }
        }

        if (!textDocs.isEmpty() && !RagOrchestrationService.hasRelevantEvidence(textDocs, query)) {
            List<Document> keywordSweep = this.attemptKeywordSweep(query, dept, activeFiles);
            if (!keywordSweep.isEmpty()) {
                textDocs.addAll(keywordSweep);
                strategies.add("KeywordSweep");
            }
        }
        if (!textDocs.isEmpty()) {
            Set<String> keywords = RagOrchestrationService.buildQueryKeywords(query);
            int minKeywordHits = RagOrchestrationService.requiredKeywordHits(query, keywords);
            boolean hasKeywordMatch = textDocs.stream().anyMatch(doc -> {
                String source = RagOrchestrationService.getDocumentSource(doc);
                int sourceHits = RagOrchestrationService.countKeywordHits(source, keywords);
                int contentHits = RagOrchestrationService.countKeywordHits(doc.getContent(), keywords);
                return sourceHits >= Math.max(1, minKeywordHits) || contentHits >= Math.max(1, minKeywordHits);
            });
            if (!hasKeywordMatch && !keywords.isEmpty()) {
                List<Document> keywordSweep = this.attemptKeywordSweep(query, dept, activeFiles);
                if (!keywordSweep.isEmpty()) {
                    textDocs.addAll(keywordSweep);
                    strategies.add("KeywordSweep");
                }
            }
        }

        textDocs = new ArrayList<>(this.filterDocumentsByFiles(textDocs, activeFiles));
        textDocs = new ArrayList<>(this.filterTestArtifacts(textDocs, query));
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

    private List<Document> expandDocsFromCache(List<Document> docs, String dept, String query) {
        if (docs == null || docs.isEmpty()) {
            return docs;
        }
        if (!this.shouldAugmentWithCache(query) || dept == null || dept.isBlank()) {
            return docs;
        }
        String workspaceId = WorkspaceContext.getCurrentWorkspaceId();
        String prefix = dept.toUpperCase() + ":" + workspaceId + ":";
        ArrayList<Document> expanded = new ArrayList<>();
        for (Document doc : docs) {
            if (doc == null) {
                continue;
            }
            String source = RagOrchestrationService.getDocumentSource(doc);
            if (source.isBlank()) {
                expanded.add(doc);
                continue;
            }
            String cached = this.secureDocCache.getIfPresent(prefix + source);
            if (cached != null && !cached.isBlank()) {
                expanded.add(new Document(cached, doc.getMetadata()));
            } else {
                expanded.add(doc);
            }
        }
        return expanded;
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

    private List<Document> loadActiveFilesFromCache(String dept, List<String> activeFiles, Set<String> keywords) {
        if (activeFiles == null || activeFiles.isEmpty()) {
            return List.of();
        }
        String workspaceId = WorkspaceContext.getCurrentWorkspaceId();
        String sectorPrefix = dept.toUpperCase() + ":" + workspaceId + ":";
        ArrayList<Document> results = new ArrayList<>();
        for (Map.Entry<String, String> entry : this.secureDocCache.asMap().entrySet()) {
            String cacheKey = entry.getKey();
            if (!cacheKey.startsWith(sectorPrefix)) continue;
            String filename = cacheKey.substring(sectorPrefix.length());
            if (!this.isFilenameInScope(filename, activeFiles)) continue;
            String content = entry.getValue();
            Document doc = new Document(content);
            doc.getMetadata().put("source", filename);
            doc.getMetadata().put("filename", filename);
            doc.getMetadata().put("dept", dept.toUpperCase());
            doc.getMetadata().put("workspaceId", workspaceId);
            int hits = RagOrchestrationService.countKeywordHits(content, keywords)
                + RagOrchestrationService.countKeywordHits(filename, keywords);
            doc.getMetadata().put("score", (double)Math.max(hits, 1));
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

    private List<Document> filterTestArtifacts(List<Document> docs, String query) {
        if (docs == null || docs.isEmpty()) {
            return docs;
        }
        if (query != null && PII_QUERY_PATTERN.matcher(query).find()) {
            return docs;
        }
        List<Document> filtered = docs.stream().filter(doc -> {
            String source = RagOrchestrationService.getDocumentSource(doc).toLowerCase(Locale.ROOT);
            return !TEST_ARTIFACT_SOURCE_PATTERN.matcher(source).find();
        }).collect(Collectors.toList());
        return filtered.isEmpty() ? docs : filtered;
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
            String source = RagOrchestrationService.getDocumentSource(doc).toLowerCase(Locale.ROOT);
            String titleMeta = "";
            Object rawTitle = doc.getMetadata().get("title");
            if (rawTitle == null) {
                rawTitle = doc.getMetadata().get("document_title");
            }
            if (rawTitle != null) {
                titleMeta = rawTitle.toString().toLowerCase(Locale.ROOT);
            }
            String metaText = (source + " " + titleMeta).trim();
            double boost = 0.0;

            // Big boost for exact phrase matches
            for (String phrase : phrases) {
                if (content.contains(phrase)) {
                    boost += 0.5;  // Significant boost for phrase match
                    log.debug("Keyword boost +0.5 for phrase '{}' in doc", phrase);
                }
                if (!metaText.isEmpty() && metaText.contains(phrase)) {
                    boost += 0.8;
                    log.debug("Metadata boost +0.8 for phrase '{}' in doc source/title", phrase);
                }
            }

            // Smaller boost for individual keyword matches
            int wordMatches = 0;
            int metaMatches = 0;
            for (String word : importantWords) {
                if (content.contains(word)) {
                    wordMatches++;
                }
                if (!metaText.isEmpty() && metaText.contains(word)) {
                    metaMatches++;
                }
            }
            if (importantWords.size() > 0) {
                double wordBoost = 0.2 * ((double) wordMatches / importantWords.size());
                boost += wordBoost;
                double metaBoost = 0.3 * ((double) metaMatches / importantWords.size());
                boost += metaBoost;
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
