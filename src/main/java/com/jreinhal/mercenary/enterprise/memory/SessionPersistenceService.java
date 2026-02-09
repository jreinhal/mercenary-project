package com.jreinhal.mercenary.enterprise.memory;

import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.reasoning.ReasoningTrace;
import com.jreinhal.mercenary.service.ConversationMemoryProvider;
import com.jreinhal.mercenary.service.HipaaPolicy;
import com.jreinhal.mercenary.service.IntegritySigner;
import com.jreinhal.mercenary.service.SessionPersistenceProvider;
import com.jreinhal.mercenary.workspace.WorkspaceContext;
import com.mongodb.client.result.DeleteResult;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.CriteriaDefinition;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SessionPersistenceService implements SessionPersistenceProvider {
    private static final Logger log = LoggerFactory.getLogger(SessionPersistenceService.class);
    private static final String TRACES_COLLECTION = "reasoning_traces";
    private static final String SESSIONS_COLLECTION = "active_sessions";
    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault());
    private final MongoTemplate mongoTemplate;
    private final ConversationMemoryProvider conversationMemoryService;
    private final HipaaPolicy hipaaPolicy;
    private final com.jreinhal.mercenary.service.IntegritySigner integritySigner;
    private final ObjectMapper objectMapper;
    @Value("${sentinel.sessions.data-dir:${user.home}/.sentinel/sessions}")
    private String sessionDataDir;
    @Value("${sentinel.sessions.file-backup-enabled:true}")
    private boolean fileBackupEnabled;
    @Value("${sentinel.sessions.trace-retention-hours:24}")
    private int traceRetentionHours;
    @Value("${sentinel.sessions.session-timeout-minutes:60}")
    private int sessionTimeoutMinutes;
    @Value("${sentinel.sessions.max-traces-per-session:100}")
    private int maxTracesPerSession;

    public SessionPersistenceService(MongoTemplate mongoTemplate, ConversationMemoryProvider conversationMemoryService, HipaaPolicy hipaaPolicy, com.jreinhal.mercenary.service.IntegritySigner integritySigner) {
        this.mongoTemplate = mongoTemplate;
        this.conversationMemoryService = conversationMemoryService;
        this.hipaaPolicy = hipaaPolicy;
        this.integritySigner = integritySigner;
        this.objectMapper = new ObjectMapper().registerModule((Module)new JavaTimeModule()).enable(SerializationFeature.INDENT_OUTPUT).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @PostConstruct
    public void init() {
        if (this.hipaaPolicy.isStrict(Department.MEDICAL) && this.fileBackupEnabled) {
            log.warn("HIPAA strict: disabling session file backups.");
            this.fileBackupEnabled = false;
        }
        if (this.fileBackupEnabled) {
            try {
                Path dataPath = Paths.get(this.sessionDataDir, new String[0]);
                Files.createDirectories(dataPath, new FileAttribute[0]);
                Files.createDirectories(dataPath.resolve("traces"), new FileAttribute[0]);
                Files.createDirectories(dataPath.resolve("sessions"), new FileAttribute[0]);
                Files.createDirectories(dataPath.resolve("exports"), new FileAttribute[0]);
                log.info("Session persistence initialized: {}", this.sessionDataDir);
            }
            catch (IOException e) {
                log.error("Failed to create session data directories: {}", e.getMessage());
            }
        }
    }

    public ActiveSession touchSession(String userId, String sessionId, String department) {
        ActiveSession session;
        String workspaceId = WorkspaceContext.getCurrentWorkspaceId();
        Query query = new Query((CriteriaDefinition)Criteria.where((String)"sessionId").is(sessionId).and("workspaceId").is(workspaceId));
        ActiveSession existing = (ActiveSession)this.mongoTemplate.findOne(query, ActiveSession.class, SESSIONS_COLLECTION);
        if (existing == null) {
            session = new ActiveSession(sessionId, userId, workspaceId, department, Instant.now(), Instant.now(), 0, 0, new ArrayList<String>(), new HashMap<String, Object>());
            log.info("Created new session: {} for user: {}", sessionId, userId);
        } else {
            session = new ActiveSession(existing.sessionId(), existing.userId(), existing.workspaceId(), existing.department(), existing.createdAt(), Instant.now(), existing.messageCount(), existing.traceCount(), existing.traceIds(), existing.metadata());
        }
        this.mongoTemplate.save(session, SESSIONS_COLLECTION);
        return session;
    }

    public String generateSessionId() {
        return UUID.randomUUID().toString().substring(0, 12);
    }

    public Optional<ActiveSession> getSession(String sessionId) {
        String workspaceId = WorkspaceContext.getCurrentWorkspaceId();
        Query query = new Query((CriteriaDefinition)Criteria.where((String)"sessionId").is(sessionId).and("workspaceId").is(workspaceId));
        ActiveSession session = (ActiveSession)this.mongoTemplate.findOne(query, ActiveSession.class, SESSIONS_COLLECTION);
        return Optional.ofNullable(session);
    }

    public List<ActiveSession> getUserSessions(String userId) {
        String workspaceId = WorkspaceContext.getCurrentWorkspaceId();
        Query query = new Query((CriteriaDefinition)Criteria.where((String)"userId").is(userId).and("workspaceId").is(workspaceId));
        return this.mongoTemplate.find(query, ActiveSession.class, SESSIONS_COLLECTION);
    }

    public void incrementMessageCount(String sessionId) {
        this.getSession(sessionId).ifPresent(session -> {
            ActiveSession updated = new ActiveSession(session.sessionId(), session.userId(), session.workspaceId(), session.department(), session.createdAt(), Instant.now(), session.messageCount() + 1, session.traceCount(), session.traceIds(), session.metadata());
            this.mongoTemplate.save(updated, SESSIONS_COLLECTION);
        });
    }

    public void persistTrace(ReasoningTrace trace, String sessionId) {
        if (trace == null) {
            return;
        }
        if (this.hipaaPolicy.isStrict(trace.getDepartment())) {
            log.info("HIPAA strict: skipping trace persistence for {}", trace.getTraceId());
            return;
        }
        PersistedTrace unsigned = new PersistedTrace(trace.getTraceId(), sessionId, trace.getUserId(), trace.getWorkspaceId(), trace.getDepartment(), trace.getQuery(), trace.getTimestamp(), trace.getTotalDurationMs(), trace.getSteps().size(), trace.getSteps().stream().map(step -> Map.of("type", step.type().name(), "label", step.label(), "detail", step.detail() != null ? step.detail() : "", "durationMs", step.durationMs(), "data", step.data() != null ? step.data() : Map.of())).toList(), trace.getMetrics(), trace.isCompleted(), null, null);
        PersistedTrace persisted = this.attachIntegrity(unsigned);
        try {
            this.mongoTemplate.save(persisted, TRACES_COLLECTION);
            log.debug("Persisted trace {} to MongoDB", trace.getTraceId());
        }
        catch (Exception e) {
            log.error("Failed to persist trace to MongoDB: {}", e.getMessage());
        }
        this.updateSessionTraceCount(sessionId, trace.getTraceId());
        if (this.fileBackupEnabled) {
            this.writeTraceToFile(persisted);
        }
    }

    private void updateSessionTraceCount(String sessionId, String traceId) {
        this.getSession(sessionId).ifPresent(session -> {
            List<String> traceIds = new ArrayList<String>(session.traceIds());
            traceIds.add(traceId);
            if (traceIds.size() > this.maxTracesPerSession) {
                traceIds = traceIds.subList(traceIds.size() - this.maxTracesPerSession, traceIds.size());
            }
            ActiveSession updated = new ActiveSession(session.sessionId(), session.userId(), session.workspaceId(), session.department(), session.createdAt(), Instant.now(), session.messageCount(), traceIds.size(), traceIds, session.metadata());
            this.mongoTemplate.save(updated, SESSIONS_COLLECTION);
        });
    }

    private void writeTraceToFile(PersistedTrace trace) {
        try {
            String datePrefix = FILE_DATE_FORMAT.format(trace.timestamp());
            String filename = String.format("%s_%s.json", datePrefix, trace.traceId());
            Path filePath = Paths.get(this.sessionDataDir, "traces", filename);
            String json = this.objectMapper.writeValueAsString(trace);
            Files.writeString(filePath, (CharSequence)json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.debug("Wrote trace to file: {}", filePath);
        }
        catch (IOException e) {
            log.warn("Failed to write trace to file: {}", e.getMessage());
        }
    }

    public Optional<PersistedTrace> getPersistedTrace(String traceId) {
        String workspaceId = WorkspaceContext.getCurrentWorkspaceId();
        Query query = new Query((CriteriaDefinition)Criteria.where((String)"traceId").is(traceId).and("workspaceId").is(workspaceId));
        PersistedTrace trace = (PersistedTrace)this.mongoTemplate.findOne(query, PersistedTrace.class, TRACES_COLLECTION);
        return Optional.ofNullable(trace);
    }

    public List<PersistedTrace> getSessionTraces(String sessionId) {
        String workspaceId = WorkspaceContext.getCurrentWorkspaceId();
        Query query = new Query((CriteriaDefinition)Criteria.where((String)"sessionId").is(sessionId).and("workspaceId").is(workspaceId));
        query.with(Sort.by((Sort.Direction)Sort.Direction.ASC, (String[])new String[]{"timestamp"}));
        return this.mongoTemplate.find(query, PersistedTrace.class, TRACES_COLLECTION);
    }

    public Path exportSession(String sessionId, String userId) throws IOException {
        Optional<ActiveSession> sessionOpt = this.getSession(sessionId);
        if (sessionOpt.isEmpty()) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        ActiveSession session = sessionOpt.get();
        if (this.hipaaPolicy.shouldDisableSessionExport(this.safeDepartment(session.department()))) {
            throw new SecurityException("Session export disabled for HIPAA medical deployments");
        }
        if (!session.userId().equals(userId)) {
            throw new SecurityException("User does not own this session");
        }
        ConversationMemoryProvider.ConversationContext context = this.conversationMemoryService.getContext(userId, sessionId);
        List<PersistedTrace> traces = this.getSessionTraces(sessionId);
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalDurationMs", traces.stream().mapToLong(PersistedTrace::durationMs).sum());
        summary.put("averageResponseTime", traces.isEmpty() ? 0.0 : traces.stream().mapToLong(PersistedTrace::durationMs).average().orElse(0.0));
        summary.put("topicsDiscussed", context.activeTopics());
        SessionExport unsigned = new SessionExport(sessionId, userId, session.workspaceId(), session.department(), session.createdAt(), session.lastActivityAt(), context.recentMessages().size(), traces.size(), context.recentMessages(), traces, summary, null, null);
        SessionExport export = this.attachIntegrity(unsigned);
        String filename = String.format("session_%s_%s.json", sessionId, FILE_DATE_FORMAT.format(Instant.now()));
        Path exportPath = Paths.get(this.sessionDataDir, "exports", filename);
        String json = this.objectMapper.writeValueAsString(export);
        Files.writeString(exportPath, (CharSequence)json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log.info("Exported session {} to {}", sessionId, exportPath);
        return exportPath;
    }

    public String exportSessionToJson(String sessionId, String userId) throws IOException {
        Optional<ActiveSession> sessionOpt = this.getSession(sessionId);
        if (sessionOpt.isEmpty()) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        ActiveSession session = sessionOpt.get();
        if (this.hipaaPolicy.shouldDisableSessionExport(this.safeDepartment(session.department()))) {
            throw new SecurityException("Session export disabled for HIPAA medical deployments");
        }
        if (!session.userId().equals(userId)) {
            throw new SecurityException("User does not own this session");
        }
        ConversationMemoryProvider.ConversationContext context = this.conversationMemoryService.getContext(userId, sessionId);
        List<PersistedTrace> traces = this.getSessionTraces(sessionId);
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalDurationMs", traces.stream().mapToLong(PersistedTrace::durationMs).sum());
        summary.put("topicsDiscussed", context.activeTopics());
        SessionExport unsigned = new SessionExport(sessionId, userId, session.workspaceId(), session.department(), session.createdAt(), session.lastActivityAt(), context.recentMessages().size(), traces.size(), context.recentMessages(), traces, summary, null, null);
        SessionExport export = this.attachIntegrity(unsigned);
        return this.objectMapper.writeValueAsString(export);
    }

    @Scheduled(fixedRate=3600000L)
    public void archiveInactiveSessions() {
        Instant cutoff = Instant.now().minusSeconds((long)this.sessionTimeoutMinutes * 60L);
        Query query = new Query((CriteriaDefinition)Criteria.where((String)"lastActivityAt").lt(cutoff)
                .and("workspaceId").is(WorkspaceContext.getCurrentWorkspaceId()));
        List<ActiveSession> inactiveSessions = this.mongoTemplate.find(query, ActiveSession.class, SESSIONS_COLLECTION);
        for (ActiveSession session : inactiveSessions) {
            try {
                if (this.fileBackupEnabled && !this.hipaaPolicy.shouldDisableSessionExport(this.safeDepartment(session.department()))) {
                    this.exportSession(session.sessionId(), session.userId());
                }
                this.mongoTemplate.remove(new Query((CriteriaDefinition)Criteria.where((String)"sessionId").is(session.sessionId())
                        .and("workspaceId").is(session.workspaceId())), SESSIONS_COLLECTION);
                log.info("Archived inactive session: {}", session.sessionId());
            }
            catch (Exception e) {
                log.error("Failed to archive session {}: {}", session.sessionId(), e.getMessage());
            }
        }
        if (!inactiveSessions.isEmpty()) {
            log.info("Archived {} inactive sessions", inactiveSessions.size());
        }
    }

    @Scheduled(cron="0 0 2 * * *")
    public void purgeOldTraces() {
        Instant cutoff = Instant.now().minusSeconds((long)this.traceRetentionHours * 3600L);
        Query query = new Query((CriteriaDefinition)Criteria.where((String)"timestamp").lt(cutoff)
                .and("workspaceId").is(WorkspaceContext.getCurrentWorkspaceId()));
        try {
            DeleteResult result = this.mongoTemplate.remove(query, TRACES_COLLECTION);
            long deleted = result.getDeletedCount();
            if (deleted > 0L) {
                log.info("Purged {} old reasoning traces (older than {} hours)", deleted, this.traceRetentionHours);
            }
        }
        catch (Exception e) {
            log.error("Failed to purge old traces: {}", e.getMessage());
        }
        this.conversationMemoryService.purgeOldConversations(this.traceRetentionHours / 24);
        if (this.fileBackupEnabled) {
            this.purgeOldTraceFiles(cutoff);
        }
    }

    private void purgeOldTraceFiles(Instant cutoff) {
        Path tracesDir = Paths.get(this.sessionDataDir, "traces");
        try (Stream<Path> files = Files.list(tracesDir);){
            files.filter(x$0 -> Files.isRegularFile(x$0, new LinkOption[0])).filter(path -> {
                try {
                    return Files.getLastModifiedTime(path, new LinkOption[0]).toInstant().isBefore(cutoff);
                }
                catch (IOException e) {
                    return false;
                }
            }).forEach(path -> {
                try {
                    Files.delete(path);
                    log.debug("Deleted old trace file: {}", path.getFileName());
                }
                catch (IOException e) {
                    log.warn("Failed to delete trace file: {}", e.getMessage());
                }
            });
        }
        catch (IOException e) {
            log.warn("Failed to list trace files for cleanup: {}", e.getMessage());
        }
    }

    public Map<String, Object> getStatistics() {
        HashMap<String, Object> stats = new HashMap<String, Object>();
        try {
            String workspaceId = WorkspaceContext.getCurrentWorkspaceId();
            long activeSessions = this.mongoTemplate.count(new Query(Criteria.where("workspaceId").is(workspaceId)), SESSIONS_COLLECTION);
            long totalTraces = this.mongoTemplate.count(new Query(Criteria.where("workspaceId").is(workspaceId)), TRACES_COLLECTION);
            stats.put("activeSessions", activeSessions);
            stats.put("totalPersistedTraces", totalTraces);
            stats.put("traceRetentionHours", this.traceRetentionHours);
            stats.put("sessionTimeoutMinutes", this.sessionTimeoutMinutes);
            stats.put("fileBackupEnabled", this.fileBackupEnabled);
            stats.put("dataDirectory", this.sessionDataDir);
        }
        catch (Exception e) {
            stats.put("error", "Failed to retrieve session statistics");
        }
        return stats;
    }


    private PersistedTrace attachIntegrity(PersistedTrace unsigned) {
        if (!this.integritySigner.isEnabled()) {
            return unsigned;
        }
        try {
            String payload = this.objectMapper.writeValueAsString(unsigned);
            IntegritySigner.Signature signature = this.integritySigner.signWithKeyId(payload);
            return new PersistedTrace(unsigned.traceId(), unsigned.sessionId(), unsigned.userId(), unsigned.workspaceId(), unsigned.department(), unsigned.query(), unsigned.timestamp(), unsigned.durationMs(), unsigned.stepCount(), unsigned.steps(), unsigned.metrics(), unsigned.completed(), signature.signature(), signature.keyId());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to sign trace integrity payload", e);
        }
    }

    private SessionExport attachIntegrity(SessionExport unsigned) {
        if (!this.integritySigner.isEnabled()) {
            return unsigned;
        }
        try {
            String payload = this.objectMapper.writeValueAsString(unsigned);
            IntegritySigner.Signature signature = this.integritySigner.signWithKeyId(payload);
            return new SessionExport(unsigned.sessionId(), unsigned.userId(), unsigned.workspaceId(), unsigned.department(), unsigned.startTime(), unsigned.endTime(), unsigned.totalMessages(), unsigned.totalTraces(), unsigned.messages(), unsigned.traces(), unsigned.summary(), signature.signature(), signature.keyId());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to sign session export integrity payload", e);
        }
    }

    private Department safeDepartment(String dept) {
        if (dept == null || dept.isBlank()) {
            return null;
        }
        try {
            return Department.valueOf(dept.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
