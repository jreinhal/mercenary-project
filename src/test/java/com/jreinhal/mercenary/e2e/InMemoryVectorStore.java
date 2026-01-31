package com.jreinhal.mercenary.e2e;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

public class InMemoryVectorStore implements VectorStore {
    private static final Pattern EQ_PATTERN = Pattern.compile("\\bdept\\b\\s*==\\s*'?([A-Za-z0-9_\\-]+)'?", Pattern.CASE_INSENSITIVE);
    private static final String KEY_TOKEN = "Key[key=dept]";
    private static final String VALUE_TOKEN = "Value[value=";
    private final List<Document> documents = new CopyOnWriteArrayList<>();

    @Override
    public void add(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return;
        }
        documents.addAll(docs);
    }

    @Override
    public Optional<Boolean> delete(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Optional.of(false);
        }
        boolean removed = documents.removeIf(doc -> {
            String docId = doc.getId();
            if (docId != null && ids.contains(docId)) {
                return true;
            }
            Object source = doc.getMetadata().get("source");
            return source != null && ids.contains(source.toString());
        });
        return Optional.of(removed);
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        if (request == null) {
            return List.of();
        }
        List<Document> scoped = filterByDept(request);
        String query = request.getQuery() == null ? "" : request.getQuery().toLowerCase();
        List<Document> matches = scoped.stream()
                .filter(doc -> matchesQuery(doc, query))
                .collect(Collectors.toList());
        if (matches.isEmpty()) {
            matches = scoped;
        }
        int topK = request.getTopK() > 0 ? request.getTopK() : matches.size();
        int limit = Math.min(topK, matches.size());
        List<Document> trimmed = matches.subList(0, limit);
        List<Document> results = new ArrayList<>();
        for (Document doc : trimmed) {
            Map<String, Object> metadata = new HashMap<>(doc.getMetadata());
            metadata.putIfAbsent("score", 0.9);
            results.add(new Document(doc.getContent(), metadata));
        }
        return results;
    }

    public void clear() {
        documents.clear();
    }

    private List<Document> filterByDept(SearchRequest request) {
        if (!request.hasFilterExpression()) {
            return new ArrayList<>(documents);
        }
        String filter = String.valueOf(request.getFilterExpression());
        String dept = extractDept(filter);
        if (dept == null || dept.isBlank()) {
            return new ArrayList<>(documents);
        }
        List<Document> scoped = documents.stream()
                .filter(doc -> dept.equalsIgnoreCase(String.valueOf(doc.getMetadata().get("dept"))))
                .collect(Collectors.toList());
        return scoped.isEmpty() ? new ArrayList<>(documents) : scoped;
    }

    private boolean matchesQuery(Document doc, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String content = doc.getContent() == null ? "" : doc.getContent().toLowerCase();
        if (content.contains(query)) {
            return true;
        }
        Object source = doc.getMetadata().getOrDefault("source", "");
        return source != null && source.toString().toLowerCase().contains(query);
    }

    private String extractDept(String filter) {
        if (filter == null || filter.isBlank()) {
            return null;
        }
        if (filter.contains(KEY_TOKEN) && filter.contains(VALUE_TOKEN)) {
            String value = extractBetween(filter, VALUE_TOKEN, "]");
            return stripQuotes(value);
        }
        Matcher matcher = EQ_PATTERN.matcher(filter);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private String extractBetween(String text, String startToken, String endToken) {
        int start = text.indexOf(startToken);
        if (start < 0) {
            return "";
        }
        start += startToken.length();
        int end = text.indexOf(endToken, start);
        if (end < 0) {
            end = text.length();
        }
        return text.substring(start, end).trim();
    }

    private String stripQuotes(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if ((trimmed.startsWith("'") && trimmed.endsWith("'")) || (trimmed.startsWith("\"") && trimmed.endsWith("\""))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }
}
