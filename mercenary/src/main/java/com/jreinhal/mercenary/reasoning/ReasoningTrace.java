package com.jreinhal.mercenary.reasoning;

import java.time.Instant;
import java.util.*;

/**
 * Complete reasoning trace for a query execution.
 *
 * Contains all steps taken by the RAG pipeline, timing information,
 * and metrics for Glass Box transparency.
 */
public class ReasoningTrace {

    private final String traceId;
    private final Instant timestamp;
    private final String query;
    private final String department;
    private final String userId;  // SECURITY: Owner binding for access control
    private final List<ReasoningStep> steps;
    private final Map<String, Object> metrics;
    private long totalDurationMs;
    private boolean completed;

    public ReasoningTrace(String query, String department) {
        this(query, department, null);
    }

    public ReasoningTrace(String query, String department, String userId) {
        this.traceId = UUID.randomUUID().toString().substring(0, 8);
        this.timestamp = Instant.now();
        this.query = query;
        this.department = department;
        this.userId = userId;
        this.steps = new ArrayList<>();
        this.metrics = new LinkedHashMap<>();
        this.totalDurationMs = 0;
        this.completed = false;
    }

    /**
     * Get the user ID who created this trace (for access control).
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Add a reasoning step to the trace.
     */
    public void addStep(ReasoningStep step) {
        steps.add(step);
        totalDurationMs += step.durationMs();
    }

    /**
     * Add a metric to the trace.
     */
    public void addMetric(String key, Object value) {
        metrics.put(key, value);
    }

    /**
     * Mark the trace as completed.
     */
    public void complete() {
        this.completed = true;
    }

    /**
     * Get the trace ID for reference.
     */
    public String getTraceId() {
        return traceId;
    }

    /**
     * Get the timestamp when the trace started.
     */
    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * Get the original query.
     */
    public String getQuery() {
        return query;
    }

    /**
     * Get the department/sector queried.
     */
    public String getDepartment() {
        return department;
    }

    /**
     * Get all reasoning steps.
     */
    public List<ReasoningStep> getSteps() {
        return Collections.unmodifiableList(steps);
    }

    /**
     * Get all metrics.
     */
    public Map<String, Object> getMetrics() {
        return Collections.unmodifiableMap(metrics);
    }

    /**
     * Get total execution time.
     */
    public long getTotalDurationMs() {
        return totalDurationMs;
    }

    /**
     * Check if trace is completed.
     */
    public boolean isCompleted() {
        return completed;
    }

    /**
     * Convert to a map for JSON serialization.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("traceId", traceId);
        map.put("timestamp", timestamp.toString());
        map.put("query", query);
        map.put("department", department);
        map.put("totalDurationMs", totalDurationMs);
        map.put("completed", completed);

        List<Map<String, Object>> stepMaps = new ArrayList<>();
        for (ReasoningStep step : steps) {
            Map<String, Object> stepMap = new LinkedHashMap<>();
            stepMap.put("type", step.type().name());
            stepMap.put("label", step.label());
            stepMap.put("detail", step.detail());
            stepMap.put("durationMs", step.durationMs());
            if (!step.data().isEmpty()) {
                stepMap.put("data", step.data());
            }
            stepMaps.add(stepMap);
        }
        map.put("steps", stepMaps);

        if (!metrics.isEmpty()) {
            map.put("metrics", metrics);
        }

        return map;
    }

    /**
     * Get a summary string for logging.
     */
    public String getSummary() {
        return String.format("Trace[%s]: %d steps, %dms total, %s",
                traceId, steps.size(), totalDurationMs, completed ? "COMPLETED" : "IN_PROGRESS");
    }

    /**
     * Get steps as a list of maps for JSON serialization.
     * Used by the enhanced API endpoint.
     */
    public List<Map<String, Object>> getStepsAsMaps() {
        List<Map<String, Object>> stepMaps = new ArrayList<>();
        for (ReasoningStep step : steps) {
            Map<String, Object> stepMap = new LinkedHashMap<>();
            stepMap.put("type", step.type().name().toLowerCase());
            stepMap.put("label", step.label());
            stepMap.put("detail", step.detail());
            stepMap.put("durationMs", step.durationMs());
            if (!step.data().isEmpty()) {
                stepMap.put("data", step.data());
            }
            stepMaps.add(stepMap);
        }
        return stepMaps;
    }
}
