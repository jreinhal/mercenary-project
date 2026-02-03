package com.jreinhal.mercenary.reporting;

import com.jreinhal.mercenary.connectors.ConnectorPolicy;
import com.jreinhal.mercenary.connectors.ConnectorService;
import com.jreinhal.mercenary.core.license.LicenseService;
import com.jreinhal.mercenary.model.AuditEvent;
import com.jreinhal.mercenary.model.Feedback;
import com.jreinhal.mercenary.repository.FeedbackRepository;
import com.jreinhal.mercenary.service.HipaaPolicy;
import com.jreinhal.mercenary.workspace.WorkspaceContext;
import com.jreinhal.mercenary.workspace.WorkspacePolicy;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

@Service
public class ExecutiveReportService {
    private static final String AUDIT_COLLECTION = "audit_log";
    private static final String HIPAA_AUDIT_COLLECTION = "hipaa_audit_log";
    private static final String VECTOR_COLLECTION = "vector_store";
    private static final String ACTIVE_SESSIONS_COLLECTION = "active_sessions";
    private static final String TRACES_COLLECTION = "reasoning_traces";

    private final MongoTemplate mongoTemplate;
    private final FeedbackRepository feedbackRepository;
    private final LicenseService licenseService;
    private final ConnectorService connectorService;
    private final ConnectorPolicy connectorPolicy;
    private final WorkspacePolicy workspacePolicy;
    private final HipaaPolicy hipaaPolicy;

    public ExecutiveReportService(MongoTemplate mongoTemplate,
                                  FeedbackRepository feedbackRepository,
                                  LicenseService licenseService,
                                  ConnectorService connectorService,
                                  ConnectorPolicy connectorPolicy,
                                  WorkspacePolicy workspacePolicy,
                                  HipaaPolicy hipaaPolicy) {
        this.mongoTemplate = mongoTemplate;
        this.feedbackRepository = feedbackRepository;
        this.licenseService = licenseService;
        this.connectorService = connectorService;
        this.connectorPolicy = connectorPolicy;
        this.workspacePolicy = workspacePolicy;
        this.hipaaPolicy = hipaaPolicy;
    }

    public ExecutiveReport buildExecutiveReport(int days) {
        int windowDays = Math.max(1, days);
        Instant now = Instant.now();
        Instant since = now.minus(windowDays, ChronoUnit.DAYS);
        String workspaceId = WorkspaceContext.getCurrentWorkspaceId();
        LicenseService.Edition edition = licenseService.getEdition();

        long documents = countVectorDocs(workspaceId);
        long activeSessions = countCollection(ACTIVE_SESSIONS_COLLECTION, workspaceId);
        long traces = countCollection(TRACES_COLLECTION, workspaceId);
        long queries = countAuditEvents(AuditEvent.EventType.QUERY_EXECUTED, workspaceId, since);
        long ingestions = countAuditEvents(AuditEvent.EventType.DOCUMENT_INGESTED, workspaceId, since);

        ExecutiveReport.UsageStats usage = new ExecutiveReport.UsageStats(documents, activeSessions, traces, queries, ingestions);

        long accessDenied = countAuditEvents(AuditEvent.EventType.ACCESS_DENIED, workspaceId, since);
        long authFailures = countAuditEvents(AuditEvent.EventType.AUTH_FAILURE, workspaceId, since);
        long securityAlerts = countAuditEvents(AuditEvent.EventType.SECURITY_ALERT, workspaceId, since);
        long promptInjection = countAuditEvents(AuditEvent.EventType.PROMPT_INJECTION_DETECTED, workspaceId, since);
        long hipaaEvents = countHipaaEvents(workspaceId, since);
        long breakGlassEvents = countBreakGlassEvents(workspaceId, since);

        ExecutiveReport.SecurityStats security = new ExecutiveReport.SecurityStats(accessDenied, authFailures, securityAlerts, promptInjection, hipaaEvents, breakGlassEvents);

        ExecutiveReport.FeedbackStats feedback = buildFeedbackStats(workspaceId, since);

        ExecutiveReport.ConnectorStats connectors = new ExecutiveReport.ConnectorStats(connectorService.getStatuses());

        boolean hipaaStrict = hipaaPolicy.isStrict("MEDICAL");
        boolean connectorsAllowed = connectorPolicy.allowConnectors();
        boolean workspaceIsolationEnabled = workspacePolicy.allowWorkspaceSwitching();
        ExecutiveReport.SystemStats system = new ExecutiveReport.SystemStats(hipaaStrict, connectorsAllowed, workspaceIsolationEnabled);

        return new ExecutiveReport(workspaceId, edition.name(), now, windowDays, usage, security, feedback, connectors, system);
    }

    private long countVectorDocs(String workspaceId) {
        try {
            if (!mongoTemplate.collectionExists(VECTOR_COLLECTION)) {
                return 0L;
            }
            Query query = new Query(Criteria.where("metadata.workspaceId").is(workspaceId));
            return mongoTemplate.count(query, VECTOR_COLLECTION);
        } catch (Exception e) {
            return 0L;
        }
    }

    private long countCollection(String collection, String workspaceId) {
        try {
            if (!mongoTemplate.collectionExists(collection)) {
                return 0L;
            }
            Query query = new Query(Criteria.where("workspaceId").is(workspaceId));
            return mongoTemplate.count(query, collection);
        } catch (Exception e) {
            return 0L;
        }
    }

    private long countAuditEvents(AuditEvent.EventType type, String workspaceId, Instant since) {
        try {
            if (!mongoTemplate.collectionExists(AUDIT_COLLECTION)) {
                return 0L;
            }
            Query query = new Query(Criteria.where("eventType").is(type)
                    .and("workspaceId").is(workspaceId)
                    .and("timestamp").gte(since));
            return mongoTemplate.count(query, AUDIT_COLLECTION);
        } catch (Exception e) {
            return 0L;
        }
    }

    private long countHipaaEvents(String workspaceId, Instant since) {
        try {
            if (!mongoTemplate.collectionExists(HIPAA_AUDIT_COLLECTION)) {
                return 0L;
            }
            Query query = new Query(Criteria.where("workspaceId").is(workspaceId)
                    .and("timestamp").gte(since));
            return mongoTemplate.count(query, HIPAA_AUDIT_COLLECTION);
        } catch (Exception e) {
            return 0L;
        }
    }

    private long countBreakGlassEvents(String workspaceId, Instant since) {
        try {
            if (!mongoTemplate.collectionExists(HIPAA_AUDIT_COLLECTION)) {
                return 0L;
            }
            Criteria base = Criteria.where("workspaceId").is(workspaceId)
                    .and("timestamp").gte(since);
            Criteria breakGlass = new Criteria().orOperator(
                    Criteria.where("details.breakTheGlass").is(true),
                    Criteria.where("details.requiresReview").is(true)
            );
            Query query = new Query(new Criteria().andOperator(base, breakGlass));
            return mongoTemplate.count(query, HIPAA_AUDIT_COLLECTION);
        } catch (Exception e) {
            return 0L;
        }
    }

    private ExecutiveReport.FeedbackStats buildFeedbackStats(String workspaceId, Instant since) {
        boolean enabled = !hipaaPolicy.shouldDisableFeedback("MEDICAL");
        if (!enabled) {
            return new ExecutiveReport.FeedbackStats(0L, 0L, 0L, 0.0, Map.of(), 0L, false);
        }
        List<Feedback> recent = feedbackRepository.findByTimestampBetweenAndWorkspaceId(since, Instant.now(), workspaceId);
        long positive = recent.stream().filter(f -> f.getFeedbackType() == Feedback.FeedbackType.POSITIVE).count();
        long negative = recent.stream().filter(f -> f.getFeedbackType() == Feedback.FeedbackType.NEGATIVE).count();
        long total = positive + negative;
        double satisfaction = total > 0 ? (double) positive / (double) total * 100.0 : 0.0;
        Map<String, Long> categories = recent.stream()
                .filter(f -> f.getFeedbackType() == Feedback.FeedbackType.NEGATIVE && f.getCategory() != null)
                .collect(Collectors.groupingBy(f -> f.getCategory().name(), Collectors.counting()));
        long openIssues = recent.stream().filter(f -> f.getResolutionStatus() == Feedback.ResolutionStatus.OPEN).count();
        return new ExecutiveReport.FeedbackStats(total, positive, negative, satisfaction, categories, openIssues, true);
    }
}
