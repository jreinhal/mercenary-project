package com.jreinhal.mercenary.reasoning;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReasoningTraceTest {

    @Nested
    @DisplayName("Trace construction")
    class ConstructionTest {
        @Test
        @DisplayName("Should generate a traceId")
        void shouldGenerateTraceId() {
            ReasoningTrace trace = new ReasoningTrace("query", "ENTERPRISE");

            assertThat(trace.getTraceId()).isNotNull();
            assertThat(trace.getTraceId()).hasSize(8);
        }

        @Test
        @DisplayName("Should store query and department")
        void shouldStoreFields() {
            ReasoningTrace trace = new ReasoningTrace("test query", "GOVERNMENT");

            assertThat(trace.getQuery()).isEqualTo("test query");
            assertThat(trace.getDepartment()).isEqualTo("GOVERNMENT");
        }

        @Test
        @DisplayName("Should store userId and workspaceId")
        void shouldStoreUserAndWorkspace() {
            ReasoningTrace trace = new ReasoningTrace("query", "ENTERPRISE", "user-123", "ws-456");

            assertThat(trace.getUserId()).isEqualTo("user-123");
            assertThat(trace.getWorkspaceId()).isEqualTo("ws-456");
        }

        @Test
        @DisplayName("Should have null userId and workspaceId when not provided")
        void shouldHandleNullOptionalFields() {
            ReasoningTrace trace = new ReasoningTrace("query", "ENTERPRISE");

            assertThat(trace.getUserId()).isNull();
            assertThat(trace.getWorkspaceId()).isNull();
        }
    }

    @Nested
    @DisplayName("Step management")
    class StepManagementTest {
        @Test
        @DisplayName("Should start with empty steps")
        void shouldStartEmpty() {
            ReasoningTrace trace = new ReasoningTrace("query", "ENTERPRISE");

            assertThat(trace.getSteps()).isEmpty();
        }

        @Test
        @DisplayName("Should add steps and accumulate duration")
        void shouldAddSteps() {
            ReasoningTrace trace = new ReasoningTrace("query", "ENTERPRISE");
            ReasoningStep step1 = ReasoningStep.of(
                    ReasoningStep.StepType.QUERY_ROUTING, "Route", "Routing query", 50);
            ReasoningStep step2 = ReasoningStep.of(
                    ReasoningStep.StepType.QUERY_ANALYSIS, "Analyze", "Analyzing", 100);

            trace.addStep(step1);
            trace.addStep(step2);

            assertThat(trace.getSteps()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Metrics")
    class MetricsTest {
        @Test
        @DisplayName("Should add and retrieve metrics")
        void shouldAddMetrics() {
            ReasoningTrace trace = new ReasoningTrace("query", "ENTERPRISE");
            trace.addMetric("latencyMs", 250L);
            trace.addMetric("documentsRetrieved", 5);

            assertThat(trace.getMetrics()).containsEntry("latencyMs", 250L);
            assertThat(trace.getMetrics()).containsEntry("documentsRetrieved", 5);
        }
    }

    @Nested
    @DisplayName("toMap()")
    class ToMapTest {
        @Test
        @DisplayName("Should convert trace to map with all fields")
        void shouldConvertToMap() {
            ReasoningTrace trace = new ReasoningTrace("test query", "ENTERPRISE", "user-1", "ws-1");
            trace.complete();

            Map<String, Object> map = trace.toMap();

            assertThat(map).containsKey("traceId");
            assertThat(map).containsEntry("query", "test query");
            assertThat(map).containsEntry("department", "ENTERPRISE");
            assertThat(map).containsEntry("workspaceId", "ws-1");
            assertThat(map).containsEntry("completed", true);
        }

        @Test
        @DisplayName("Should include timestamp in map")
        void shouldIncludeTimestamp() {
            ReasoningTrace trace = new ReasoningTrace("query", "ENTERPRISE");

            Map<String, Object> map = trace.toMap();

            assertThat(map).containsKey("timestamp");
            assertThat(trace.getTimestamp()).isNotNull();
        }
    }

    @Nested
    @DisplayName("ReasoningStep record")
    class ReasoningStepTest {
        @Test
        @DisplayName("of() factory should create step with empty data")
        void shouldCreateWithEmptyData() {
            ReasoningStep step = ReasoningStep.of(
                    ReasoningStep.StepType.QUERY_ROUTING, "Route", "detail", 100);

            assertThat(step.type()).isEqualTo(ReasoningStep.StepType.QUERY_ROUTING);
            assertThat(step.label()).isEqualTo("Route");
            assertThat(step.detail()).isEqualTo("detail");
            assertThat(step.durationMs()).isEqualTo(100);
            assertThat(step.data()).isEmpty();
        }

        @Test
        @DisplayName("Full constructor should store data map")
        void shouldStoreDataMap() {
            Map<String, Object> data = Map.of("key", "value");
            ReasoningStep step = new ReasoningStep(
                    ReasoningStep.StepType.QUERY_ANALYSIS, "Analyze", "detail", 50, data);

            assertThat(step.data()).containsEntry("key", "value");
        }
    }
}
