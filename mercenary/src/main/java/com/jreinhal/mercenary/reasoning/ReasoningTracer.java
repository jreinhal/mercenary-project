package com.jreinhal.mercenary.reasoning;

import com.jreinhal.mercenary.reasoning.ReasoningStep.StepType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-local reasoning trace collector for Glass Box transparency.
 *
 * Usage:
 * <pre>
 * ReasoningTrace trace = tracer.startTrace(query, department);
 * try {
 *     // ... pipeline operations ...
 *     tracer.addStep(StepType.VECTOR_SEARCH, "Vector Search", "Found 10 documents", duration);
 *     // ... more operations ...
 * } finally {
 *     tracer.endTrace();
 * }
 * </pre>
 *
 * The trace is automatically scoped to the current thread, making it safe
 * for concurrent request handling.
 */
@Component
public class ReasoningTracer {

    private static final Logger log = LoggerFactory.getLogger(ReasoningTracer.class);

    @Value("${sentinel.reasoning.enabled:true}")
    private boolean enabled;

    @Value("${sentinel.reasoning.detailed-traces:false}")
    private boolean detailedTraces;

    /**
     * Thread-local storage for the current trace.
     */
    private final ThreadLocal<ReasoningTrace> currentTrace = new ThreadLocal<>();

    /**
     * In-memory cache of recent traces for retrieval.
     * In production, this would be stored in MongoDB with TTL.
     */
    private final Map<String, ReasoningTrace> traceCache = new ConcurrentHashMap<>();

    /**
     * Maximum traces to keep in memory.
     */
    private static final int MAX_CACHED_TRACES = 1000;

    /**
     * Start a new reasoning trace for the current thread.
     *
     * @param query The user's query
     * @param department The department being queried
     * @return The new trace, or null if tracing is disabled
     */
    public ReasoningTrace startTrace(String query, String department) {
        if (!enabled) {
            return null;
        }

        ReasoningTrace trace = new ReasoningTrace(query, department);
        currentTrace.set(trace);
        log.debug("Started reasoning trace: {}", trace.getTraceId());
        return trace;
    }

    /**
     * Get the current trace for this thread.
     */
    public ReasoningTrace getCurrentTrace() {
        return currentTrace.get();
    }

    /**
     * Add a step to the current trace.
     *
     * @param type The step type
     * @param label Human-readable label
     * @param detail Detailed description
     * @param durationMs Duration in milliseconds
     */
    public void addStep(StepType type, String label, String detail, long durationMs) {
        addStep(type, label, detail, durationMs, Map.of());
    }

    /**
     * Add a step with additional data to the current trace.
     *
     * @param type The step type
     * @param label Human-readable label
     * @param detail Detailed description
     * @param durationMs Duration in milliseconds
     * @param data Additional step-specific data
     */
    public void addStep(StepType type, String label, String detail, long durationMs, Map<String, Object> data) {
        ReasoningTrace trace = currentTrace.get();
        if (trace == null) {
            return;
        }

        ReasoningStep step = ReasoningStep.of(type, label, detail, durationMs, data);
        trace.addStep(step);

        if (detailedTraces) {
            log.debug("Trace[{}] Step: {} - {} ({}ms)", trace.getTraceId(), type, label, durationMs);
        }
    }

    /**
     * Add a metric to the current trace.
     */
    public void addMetric(String key, Object value) {
        ReasoningTrace trace = currentTrace.get();
        if (trace != null) {
            trace.addMetric(key, value);
        }
    }

    /**
     * End the current trace and cache it for retrieval.
     *
     * @return The completed trace, or null if none was active
     */
    public ReasoningTrace endTrace() {
        ReasoningTrace trace = currentTrace.get();
        if (trace == null) {
            return null;
        }

        trace.complete();
        currentTrace.remove();

        // Cache the trace for later retrieval
        cacheTrace(trace);

        log.debug("Completed reasoning trace: {}", trace.getSummary());
        return trace;
    }

    /**
     * Retrieve a cached trace by ID.
     */
    public ReasoningTrace getTrace(String traceId) {
        return traceCache.get(traceId);
    }

    /**
     * Helper to time an operation and add it as a step.
     */
    public <T> T timed(StepType type, String label, TimedOperation<T> operation) {
        long startTime = System.currentTimeMillis();
        T result;
        String detail;
        Map<String, Object> data = Map.of();

        try {
            TimedResult<T> timedResult = operation.execute();
            result = timedResult.result();
            detail = timedResult.detail();
            data = timedResult.data() != null ? timedResult.data() : Map.of();
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            addStep(StepType.ERROR, label + " (Failed)", e.getMessage(), duration);
            throw e;
        }

        long duration = System.currentTimeMillis() - startTime;
        addStep(type, label, detail, duration, data);
        return result;
    }

    /**
     * Functional interface for timed operations.
     */
    @FunctionalInterface
    public interface TimedOperation<T> {
        TimedResult<T> execute();
    }

    /**
     * Result from a timed operation.
     */
    public record TimedResult<T>(T result, String detail, Map<String, Object> data) {
        public static <T> TimedResult<T> of(T result, String detail) {
            return new TimedResult<>(result, detail, null);
        }

        public static <T> TimedResult<T> of(T result, String detail, Map<String, Object> data) {
            return new TimedResult<>(result, detail, data);
        }
    }

    /**
     * Cache a trace for later retrieval.
     */
    private void cacheTrace(ReasoningTrace trace) {
        // Simple eviction: remove oldest if over limit
        if (traceCache.size() >= MAX_CACHED_TRACES) {
            // Remove ~10% of oldest traces
            traceCache.keySet().stream()
                    .limit(MAX_CACHED_TRACES / 10)
                    .toList()
                    .forEach(traceCache::remove);
        }

        traceCache.put(trace.getTraceId(), trace);
    }

    /**
     * Clear all cached traces (for testing).
     */
    public void clearCache() {
        traceCache.clear();
    }

    /**
     * Check if tracing is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }
}
