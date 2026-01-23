package com.jreinhal.mercenary.dto;

import java.util.List;
import java.util.Map;

public record EnhancedAskResponse(String answer,
                                  List<Map<String, Object>> reasoning,
                                  List<String> sources,
                                  Map<String, Object> metrics,
                                  String traceId,
                                  String sessionId) {
    public EnhancedAskResponse(String answer,
                               List<Map<String, Object>> reasoning,
                               List<String> sources,
                               Map<String, Object> metrics,
                               String traceId) {
        this(answer, reasoning, sources, metrics, traceId, null);
    }
}
