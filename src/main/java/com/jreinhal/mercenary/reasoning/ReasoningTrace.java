package com.jreinhal.mercenary.reasoning;

import com.jreinhal.mercenary.reasoning.ReasoningStep;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ReasoningTrace {
    private final String traceId = UUID.randomUUID().toString().substring(0, 8);
    private final Instant timestamp = Instant.now();
    private final String query;
    private final String department;
    private final String userId;
    private final String workspaceId;
    private final List<ReasoningStep> steps;
    private final Map<String, Object> metrics;
    private long totalDurationMs;
    private boolean completed;

    public ReasoningTrace(String query, String department) {
        this(query, department, null, null);
    }

    public ReasoningTrace(String query, String department, String userId, String workspaceId) {
        this.query = query;
        this.department = department;
        this.userId = userId;
        this.workspaceId = workspaceId;
        this.steps = new ArrayList<ReasoningStep>();
        this.metrics = new LinkedHashMap<String, Object>();
        this.totalDurationMs = 0L;
        this.completed = false;
    }

    public String getUserId() {
        return this.userId;
    }

    public String getWorkspaceId() {
        return this.workspaceId;
    }

    public void addStep(ReasoningStep step) {
        this.steps.add(step);
        this.totalDurationMs += step.durationMs();
    }

    public void addMetric(String key, Object value) {
        this.metrics.put(key, value);
    }

    public void complete() {
        this.completed = true;
    }

    public String getTraceId() {
        return this.traceId;
    }

    public Instant getTimestamp() {
        return this.timestamp;
    }

    public String getQuery() {
        return this.query;
    }

    public String getDepartment() {
        return this.department;
    }

    public List<ReasoningStep> getSteps() {
        return Collections.unmodifiableList(this.steps);
    }

    public Map<String, Object> getMetrics() {
        return Collections.unmodifiableMap(this.metrics);
    }

    public long getTotalDurationMs() {
        return this.totalDurationMs;
    }

    public boolean isCompleted() {
        return this.completed;
    }

    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("traceId", this.traceId);
        map.put("timestamp", this.timestamp.toString());
        map.put("query", this.query);
        map.put("department", this.department);
        map.put("workspaceId", this.workspaceId);
        map.put("totalDurationMs", this.totalDurationMs);
        map.put("completed", this.completed);
        List<Map<String, Object>> stepMaps = new ArrayList<>();
        for (ReasoningStep step : this.steps) {
            LinkedHashMap<String, Object> stepMap = new LinkedHashMap<String, Object>();
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
        if (!this.metrics.isEmpty()) {
            map.put("metrics", this.metrics);
        }
        return map;
    }

    public String getSummary() {
        return String.format("Trace[%s]: %d steps, %dms total, %s", this.traceId, this.steps.size(), this.totalDurationMs, this.completed ? "COMPLETED" : "IN_PROGRESS");
    }

    public List<Map<String, Object>> getStepsAsMaps() {
        ArrayList<Map<String, Object>> stepMaps = new ArrayList<Map<String, Object>>();
        for (ReasoningStep step : this.steps) {
            LinkedHashMap<String, Object> stepMap = new LinkedHashMap<String, Object>();
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
