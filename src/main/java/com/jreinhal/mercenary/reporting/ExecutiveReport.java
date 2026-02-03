package com.jreinhal.mercenary.reporting;

import com.jreinhal.mercenary.connectors.ConnectorStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ExecutiveReport(
        String workspaceId,
        String edition,
        Instant generatedAt,
        int windowDays,
        UsageStats usage,
        SecurityStats security,
        FeedbackStats feedback,
        ConnectorStats connectors,
        SystemStats system) {

    public record UsageStats(
            long documents,
            long activeSessions,
            long reasoningTraces,
            long queries,
            long ingestions) {
    }

    public record SecurityStats(
            long accessDenied,
            long authFailures,
            long securityAlerts,
            long promptInjectionDetections,
            long hipaaEvents,
            long breakGlassEvents) {
    }

    public record FeedbackStats(
            long total,
            long positive,
            long negative,
            double satisfactionRate,
            Map<String, Long> categories,
            long openIssues,
            boolean enabled) {
    }

    public record ConnectorStats(List<ConnectorStatus> statuses) {
    }

    public record SystemStats(
            boolean hipaaStrict,
            boolean connectorsAllowed,
            boolean workspaceIsolationEnabled) {
    }
}
