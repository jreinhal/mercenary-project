package com.jreinhal.mercenary.rag.hybridrag;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jreinhal.mercenary.reasoning.ReasoningTracer;
import com.jreinhal.mercenary.util.FilterExpressionBuilder;
import com.jreinhal.mercenary.util.TemporalQueryConstraints;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.mock;

class HybridRagServiceTemporalFilteringTest {

    @Test
    void buildFilterExpressionReturnsBaseWhenTemporalFilteringDisabled() {
        HybridRagService service = new HybridRagService(
                mock(VectorStore.class),
                mock(QueryExpander.class),
                mock(ReasoningTracer.class),
                mock(ExecutorService.class)
        );
        ReflectionTestUtils.setField(service, "temporalFilteringEnabled", false);

        String out = ReflectionTestUtils.invokeMethod(service,
                "buildFilterExpression",
                "between 2020 and 2021",
                "ENTERPRISE",
                "ws");

        assertEquals(FilterExpressionBuilder.forDepartmentAndWorkspace("ENTERPRISE", "ws"), out);
    }

    @Test
    void buildFilterExpressionAddsYearConstraintWhenEnabled() {
        HybridRagService service = new HybridRagService(
                mock(VectorStore.class),
                mock(QueryExpander.class),
                mock(ReasoningTracer.class),
                mock(ExecutorService.class)
        );
        ReflectionTestUtils.setField(service, "temporalFilteringEnabled", true);

        String query = "between 2020 and 2021";
        String base = FilterExpressionBuilder.forDepartmentAndWorkspace("ENTERPRISE", "ws");
        String yearFilter = TemporalQueryConstraints.buildDocumentYearFilter(query);
        String expected = FilterExpressionBuilder.and(base, yearFilter);

        String out = ReflectionTestUtils.invokeMethod(service,
                "buildFilterExpression",
                query,
                "ENTERPRISE",
                "ws");

        assertEquals(expected, out);
    }
}

