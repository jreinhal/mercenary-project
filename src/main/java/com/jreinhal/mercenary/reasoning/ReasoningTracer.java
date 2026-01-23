package com.jreinhal.mercenary.reasoning;

import com.jreinhal.mercenary.reasoning.ReasoningStep;
import com.jreinhal.mercenary.reasoning.ReasoningTrace;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ReasoningTracer {
    private static final Logger log = LoggerFactory.getLogger(ReasoningTracer.class);
    @Value(value="${sentinel.reasoning.enabled:true}")
    private boolean enabled;
    @Value(value="${sentinel.reasoning.detailed-traces:false}")
    private boolean detailedTraces;
    private final ThreadLocal<ReasoningTrace> currentTrace = new ThreadLocal<>();
    private final Map<String, ReasoningTrace> traceCache = new ConcurrentHashMap<>();
    private static final int MAX_CACHED_TRACES = 1000;

    public ReasoningTrace startTrace(String query, String department) {
        return this.startTrace(query, department, null);
    }

    public ReasoningTrace startTrace(String query, String department, String userId) {
        if (!this.enabled) {
            return null;
        }
        ReasoningTrace trace = new ReasoningTrace(query, department, userId);
        this.currentTrace.set(trace);
        log.debug("Started reasoning trace: {} for user: {}", trace.getTraceId(), userId);
        return trace;
    }

    public ReasoningTrace getCurrentTrace() {
        return this.currentTrace.get();
    }

    public void addStep(ReasoningStep.StepType type, String label, String detail, long durationMs) {
        this.addStep(type, label, detail, durationMs, Map.of());
    }

    public void addStep(ReasoningStep.StepType type, String label, String detail, long durationMs, Map<String, Object> data) {
        ReasoningTrace trace = this.currentTrace.get();
        if (trace == null) {
            return;
        }
        ReasoningStep step = ReasoningStep.of(type, label, detail, durationMs, data);
        trace.addStep(step);
        if (this.detailedTraces) {
            log.debug("Trace[{}] Step: {} - {} ({}ms)", new Object[]{trace.getTraceId(), type, label, durationMs});
        }
    }

    public void addMetric(String key, Object value) {
        ReasoningTrace trace = this.currentTrace.get();
        if (trace != null) {
            trace.addMetric(key, value);
        }
    }

    public ReasoningTrace endTrace() {
        ReasoningTrace trace = this.currentTrace.get();
        if (trace == null) {
            return null;
        }
        trace.complete();
        this.currentTrace.remove();
        this.cacheTrace(trace);
        log.debug("Completed reasoning trace: {}", trace.getSummary());
        return trace;
    }

    public ReasoningTrace getTrace(String traceId) {
        return this.traceCache.get(traceId);
    }

    public <T> T timed(ReasoningStep.StepType type, String label, TimedOperation<T> operation) {
        String detail;
        T result;
        long startTime = System.currentTimeMillis();
        Map<String, Object> data = Map.of();
        try {
            TimedResult<T> timedResult = operation.execute();
            result = timedResult.result();
            detail = timedResult.detail();
            data = timedResult.data() != null ? timedResult.data() : Map.of();
        }
        catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            this.addStep(ReasoningStep.StepType.ERROR, label + " (Failed)", e.getMessage(), duration);
            throw e;
        }
        long duration = System.currentTimeMillis() - startTime;
        this.addStep(type, label, detail, duration, data);
        return result;
    }

    private void cacheTrace(ReasoningTrace trace) {
        if (this.traceCache.size() >= 1000) {
            this.traceCache.keySet().stream().limit(100L).toList().forEach(this.traceCache::remove);
        }
        this.traceCache.put(trace.getTraceId(), trace);
    }

    public void clearCache() {
        this.traceCache.clear();
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    @FunctionalInterface
    public static interface TimedOperation<T> {
        public TimedResult<T> execute();
    }

    public record TimedResult<T>(T result, String detail, Map<String, Object> data) {
        public static <T> TimedResult<T> of(T result, String detail) {
            return new TimedResult<T>(result, detail, null);
        }

        public static <T> TimedResult<T> of(T result, String detail, Map<String, Object> data) {
            return new TimedResult<T>(result, detail, data);
        }
    }
}
