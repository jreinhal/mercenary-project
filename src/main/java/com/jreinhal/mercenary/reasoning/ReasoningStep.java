/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.jreinhal.mercenary.reasoning.ReasoningStep
 *  com.jreinhal.mercenary.reasoning.ReasoningStep$StepType
 */
package com.jreinhal.mercenary.reasoning;

import com.jreinhal.mercenary.reasoning.ReasoningStep;
import java.util.Map;

public record ReasoningStep(StepType type, String label, String detail, long durationMs, Map<String, Object> data) {
    private final StepType type;
    private final String label;
    private final String detail;
    private final long durationMs;
    private final Map<String, Object> data;

    public ReasoningStep(StepType type, String label, String detail, long durationMs, Map<String, Object> data) {
        this.type = type;
        this.label = label;
        this.detail = detail;
        this.durationMs = durationMs;
        this.data = data;
    }

    public static ReasoningStep of(StepType type, String label, String detail, long durationMs) {
        return new ReasoningStep(type, label, detail, durationMs, Map.of());
    }

    public static ReasoningStep of(StepType type, String label, String detail, long durationMs, Map<String, Object> data) {
        return new ReasoningStep(type, label, detail, durationMs, data);
    }

    public StepType type() {
        return this.type;
    }

    public String label() {
        return this.label;
    }

    public String detail() {
        return this.detail;
    }

    public long durationMs() {
        return this.durationMs;
    }

    public Map<String, Object> data() {
        return this.data;
    }
}

