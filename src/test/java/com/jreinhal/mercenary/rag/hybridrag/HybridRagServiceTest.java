package com.jreinhal.mercenary.rag.hybridrag;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jreinhal.mercenary.reasoning.ReasoningTracer;
import com.jreinhal.mercenary.workspace.WorkspaceContext;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;

class HybridRagServiceTest {

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Test
    void retrieveAppliesYearFilterWhenEnabled() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        QueryExpander queryExpander = mock(QueryExpander.class);
        when(queryExpander.expand(anyString(), anyInt(), anyString())).thenReturn(List.of());

        ReasoningTracer tracer = mock(ReasoningTracer.class);

        ExecutorService exec = Executors.newFixedThreadPool(1);
        try {
            WorkspaceContext.setCurrentWorkspaceId("ws");
            HybridRagService service = new HybridRagService(vectorStore, queryExpander, tracer, exec);
            ReflectionTestUtils.setField(service, "enabled", true);
            ReflectionTestUtils.setField(service, "multiQueryCount", 1);
            ReflectionTestUtils.setField(service, "temporalFilteringEnabled", true);
            ReflectionTestUtils.setField(service, "ocrTolerance", false);
            ReflectionTestUtils.setField(service, "futureTimeoutSeconds", 1);

            service.retrieve("budget between 2020 and 2022", "MEDICAL");

            ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
            verify(vectorStore, atLeastOnce()).similaritySearch(captor.capture());

            String joined = captor.getAllValues().stream()
                    .map(r -> String.valueOf(r.getFilterExpression()))
                    .reduce("", (a, b) -> a + "\n" + b);

            assertTrue(hasYearLowerBound(joined, 2020), joined);
            assertTrue(hasYearUpperBound(joined, 2022), joined);
            assertTrue(hasThesaurusExclusion(joined), joined);
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void retrieveOmitsYearFilterWhenDisabled() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        QueryExpander queryExpander = mock(QueryExpander.class);
        when(queryExpander.expand(anyString(), anyInt(), anyString())).thenReturn(List.of());

        ReasoningTracer tracer = mock(ReasoningTracer.class);

        ExecutorService exec = Executors.newFixedThreadPool(1);
        try {
            WorkspaceContext.setCurrentWorkspaceId("ws");
            HybridRagService service = new HybridRagService(vectorStore, queryExpander, tracer, exec);
            ReflectionTestUtils.setField(service, "enabled", true);
            ReflectionTestUtils.setField(service, "multiQueryCount", 1);
            ReflectionTestUtils.setField(service, "temporalFilteringEnabled", false);
            ReflectionTestUtils.setField(service, "ocrTolerance", false);
            ReflectionTestUtils.setField(service, "futureTimeoutSeconds", 1);

            service.retrieve("budget between 2020 and 2022", "MEDICAL");

            ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
            verify(vectorStore, atLeastOnce()).similaritySearch(captor.capture());

            String joined = captor.getAllValues().stream()
                    .map(r -> String.valueOf(r.getFilterExpression()))
                    .reduce("", (a, b) -> a + "\n" + b);

            assertFalse(hasAnyYearConstraint(joined), joined);
            assertTrue(hasThesaurusExclusion(joined), joined);
        } finally {
            exec.shutdownNow();
        }
    }

    private static boolean hasYearLowerBound(String joined, int year) {
        return joined.contains("documentYear >= " + year)
                || hasSpringExpression(joined, "documentYear", String.valueOf(year), "GE", "GTE");
    }

    private static boolean hasYearUpperBound(String joined, int year) {
        return joined.contains("documentYear <= " + year)
                || hasSpringExpression(joined, "documentYear", String.valueOf(year), "LE", "LTE");
    }

    private static boolean hasAnyYearConstraint(String joined) {
        return joined.contains("documentYear >=")
                || joined.contains("documentYear <=")
                || joined.contains("documentYear ==")
                || joined.contains("Key[key=documentYear]");
    }

    private static boolean hasThesaurusExclusion(String joined) {
        return joined.contains("type != 'thesaurus'")
                || hasSpringExpression(joined, "type", "thesaurus", "NE");
    }

    private static boolean hasSpringExpression(String joined, String key, String value, String... opTypes) {
        if (!joined.contains("Key[key=" + key + "]") || !joined.contains("Value[value=" + value + "]")) {
            return false;
        }
        for (String opType : opTypes) {
            Pattern p = Pattern.compile("Expression\\[type=" + Pattern.quote(opType) + ",\\s*left=Key\\[key="
                    + Pattern.quote(key) + "\\],\\s*right=Value\\[value=" + Pattern.quote(value) + "\\]\\]");
            if (p.matcher(joined).find()) {
                return true;
            }
        }
        return false;
    }
}
