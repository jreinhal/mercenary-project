package com.jreinhal.mercenary.reporting;

import java.time.Instant;

public record SlaReport(
    String workspaceId,
    String edition,
    Instant generatedAt,
    int windowDays,
    long totalTraces,
    long totalQueries,
    double avgLatencyMs,
    long p50LatencyMs,
    long p95LatencyMs,
    long p99LatencyMs,
    long maxLatencyMs
) {
}
