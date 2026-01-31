package com.jreinhal.mercenary.e2e;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
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
}
