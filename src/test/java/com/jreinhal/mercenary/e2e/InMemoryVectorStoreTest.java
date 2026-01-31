package com.jreinhal.mercenary.e2e;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import com.jreinhal.mercenary.util.FilterExpressionBuilder;

class InMemoryVectorStoreTest {
    @Test
    void returnsEmptyWhenDeptFilterHasNoMatches() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.add(List.of(new Document("Program budget details.", Map.of(
                "dept", "ENTERPRISE",
                "source", "seed.txt"
        ))));

        SearchRequest request = SearchRequest.query("budget")
                .withTopK(5)
                .withFilterExpression(FilterExpressionBuilder.forDepartment("MEDICAL"));

        List<Document> results = store.similaritySearch(request);

        assertTrue(results.isEmpty(), "Expected empty results when dept filter matches no documents.");
    }

    @Test
    void returnsEmptyWhenExpressionFormatHasNoMatches() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.add(List.of(new Document("Program budget details.", Map.of(
                "dept", "ENTERPRISE",
                "source", "seed.txt"
        ))));

        Filter.Expression expression = new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key("dept"),
                new Filter.Value("MEDICAL")
        );
        SearchRequest request = SearchRequest.query("budget")
                .withTopK(5)
                .withFilterExpression(expression);

        List<Document> results = store.similaritySearch(request);

        assertTrue(results.isEmpty(), "Expected empty results when expression format matches no documents.");
    }
}
