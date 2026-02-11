package com.jreinhal.mercenary.rag.hybridrag;

import com.jreinhal.mercenary.constant.StopWords;
import com.jreinhal.mercenary.rag.hybridrag.QueryExpander;
import com.jreinhal.mercenary.util.FilterExpressionBuilder;
import com.jreinhal.mercenary.util.TemporalQueryConstraints;
import com.jreinhal.mercenary.reasoning.ReasoningStep;
import com.jreinhal.mercenary.reasoning.ReasoningTracer;
import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.workspace.WorkspaceContext;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;

@Service
public class HybridRagService {
    private static final Logger log = LoggerFactory.getLogger(HybridRagService.class);
    private static final Set<String> KEYWORD_STOP_WORDS = StopWords.HYBRID_KEYWORDS;
    private static final Map<Character, char[]> OCR_SUBS = Map.of(Character.valueOf('0'), new char[]{'o', 'O'}, Character.valueOf('O'), new char[]{'0'}, Character.valueOf('o'), new char[]{'0'}, Character.valueOf('1'), new char[]{'l', 'I', 'i'}, Character.valueOf('l'), new char[]{'1', 'I'}, Character.valueOf('I'), new char[]{'1', 'l'}, Character.valueOf('5'), new char[]{'S', 's'}, Character.valueOf('S'), new char[]{'5'}, Character.valueOf('8'), new char[]{'B'}, Character.valueOf('B'), new char[]{'8'});
    private final VectorStore vectorStore;
    private final QueryExpander queryExpander;
    private final ReasoningTracer reasoningTracer;
    private final ExecutorService ragExecutor;
    @Value(value="${sentinel.hybridrag.enabled:true}")
    private boolean enabled;
    @Value(value="${sentinel.hybridrag.rrf-k:60}")
    private int rrfK;
    @Value(value="${sentinel.hybridrag.semantic-weight:0.6}")
    private double semanticWeight;
    @Value(value="${sentinel.hybridrag.keyword-weight:0.4}")
    private double keywordWeight;
    @Value(value="${sentinel.hybridrag.multi-query-count:3}")
    private int multiQueryCount;
    @Value(value="${sentinel.hybridrag.ocr-tolerance:true}")
    private boolean ocrTolerance;
    @Value(value="${sentinel.performance.rag-future-timeout-seconds:8}")
    private int futureTimeoutSeconds;
    @Value(value="${sentinel.rag.temporal-filtering.enabled:true}")
    private boolean temporalFilteringEnabled;

    public HybridRagService(VectorStore vectorStore, QueryExpander queryExpander, ReasoningTracer reasoningTracer, @Qualifier("ragExecutor") ExecutorService ragExecutor) {
        this.vectorStore = vectorStore;
        this.queryExpander = queryExpander;
        this.reasoningTracer = reasoningTracer;
        this.ragExecutor = ragExecutor;
    }

    @PostConstruct
    public void init() {
        log.info("Hybrid RAG initialized (enabled={}, rrfK={}, semanticWeight={}, ocrTolerance={})", new Object[]{this.enabled, this.rrfK, this.semanticWeight, this.ocrTolerance});
    }

    public HybridRetrievalResult retrieve(String query, String department) {
        String normalizedDept = this.normalizeDepartment(department);
        if (normalizedDept == null) {
            log.warn("HybridRAG: Invalid department '{}'", department);
            return new HybridRetrievalResult(List.of(), Map.of("mode", "invalid-dept"));
        }
        String workspaceId = WorkspaceContext.getCurrentWorkspaceId();
        String filterExpression = this.buildFilterExpression(query, normalizedDept, workspaceId);
        if (!this.enabled) {
            List<Document> docs = this.vectorStore.similaritySearch(SearchRequest.query((String)query).withTopK(10).withSimilarityThreshold(0.3).withFilterExpression(filterExpression));
            return new HybridRetrievalResult(docs, Map.of("mode", "fallback"));
        }
        long startTime = System.currentTimeMillis();
        List<String> queryVariants = this.generateQueryVariants(query, normalizedDept);
        Map<String, List<RankedDoc>> semanticResults = new LinkedHashMap<String, List<RankedDoc>>();
        Map<String, CompletableFuture<List<RankedDoc>>> futures = new LinkedHashMap<>();
        for (String variant : queryVariants) {
            try {
                futures.put(variant, CompletableFuture.supplyAsync(() -> this.retrieveSemantic(variant, filterExpression), this.ragExecutor));
            } catch (RejectedExecutionException e) {
                if (log.isDebugEnabled()) {
                    log.debug("RAG thread pool overloaded; skipping variant '{}': {}", variant, e.getMessage());
                }
                futures.put(variant, CompletableFuture.completedFuture(List.of()));
            }
        }
        long semanticDeadlineMs = startTime + TimeUnit.SECONDS.toMillis(this.futureTimeoutSeconds);
        for (Map.Entry<String, CompletableFuture<List<RankedDoc>>> entry : futures.entrySet()) {
            long remainingMs = semanticDeadlineMs - System.currentTimeMillis();
            if (remainingMs <= 0L) {
                if (log.isWarnEnabled()) {
                    log.warn("HybridRAG semantic retrieval exceeded global timeout ({}s); canceling remaining variants", this.futureTimeoutSeconds);
                }
                entry.getValue().cancel(true);
                semanticResults.put(entry.getKey(), List.of());
                continue;
            }
            try {
                semanticResults.put(entry.getKey(), entry.getValue().get(remainingMs, TimeUnit.MILLISECONDS));
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (log.isWarnEnabled()) {
                    log.warn("HybridRAG semantic retrieval interrupted for variant '{}'", entry.getKey());
                }
                semanticResults.put(entry.getKey(), List.of());
            }
            catch (TimeoutException e) {
                if (log.isWarnEnabled()) {
                    log.warn("HybridRAG semantic retrieval timed out for variant '{}' (remaining {}ms)", entry.getKey(), remainingMs);
                }
                entry.getValue().cancel(true);
                semanticResults.put(entry.getKey(), List.of());
            }
            catch (Exception e) {
                if (log.isWarnEnabled()) {
                    log.warn("HybridRAG semantic retrieval failed for variant '{}': {}", entry.getKey(), e.getMessage());
                }
                semanticResults.put(entry.getKey(), List.of());
            }
        }
        List<RankedDoc> keywordResults = this.performKeywordRetrieval(query, filterExpression);
        if (this.ocrTolerance) {
            keywordResults = this.applyOcrTolerance(keywordResults, query);
        }
        List<Document> fusedResults = this.applyRrfFusion(semanticResults, keywordResults);
        long elapsed = System.currentTimeMillis() - startTime;
        int totalCandidates = semanticResults.values().stream().mapToInt(List::size).sum() + keywordResults.size();
        this.reasoningTracer.addStep(ReasoningStep.StepType.HYBRID_RETRIEVAL, "Hybrid RAG with RRF Fusion", String.format("%d query variants, %d candidates, %d fused results", queryVariants.size(), totalCandidates, fusedResults.size()), elapsed, Map.of("queryVariants", queryVariants.size(), "semanticCandidates", semanticResults.values().stream().mapToInt(List::size).sum(), "keywordCandidates", keywordResults.size(), "fusedResults", fusedResults.size()));
        log.info("HybridRAG: {} variants, {} candidates, {} fused in {}ms", new Object[]{queryVariants.size(), totalCandidates, fusedResults.size(), elapsed});
        return new HybridRetrievalResult(fusedResults, Map.of("queryVariants", queryVariants, "semanticCount", semanticResults.values().stream().mapToInt(List::size).sum(), "keywordCount", keywordResults.size(), "elapsed", elapsed));
    }

    private List<String> generateQueryVariants(String query, String department) {
        ArrayList<String> variants = new ArrayList<String>();
        variants.add(query);
        List<String> expansions = this.queryExpander.expand(query, this.multiQueryCount - 1, department);
        variants.addAll(expansions);
        return variants;
    }

    private List<RankedDoc> performKeywordRetrieval(String query, String filterExpression) {
        Set<String> keywords = this.extractKeywords(query);
        if (keywords.isEmpty()) {
            return List.of();
        }
        List<Document> candidates = this.vectorStore.similaritySearch(SearchRequest.query((String)query).withTopK(50).withSimilarityThreshold(0.1).withFilterExpression(filterExpression));
        ArrayList<RankedDoc> ranked = new ArrayList<RankedDoc>();
        for (Document doc : candidates) {
            int keywordScore = this.countKeywordMatches(doc.getContent(), keywords);
            if (keywordScore <= 0) continue;
            ranked.add(new RankedDoc(doc, 0, "keyword", keywordScore));
        }
        ranked.sort((a, b) -> Integer.compare(b.keywordScore, a.keywordScore));
        ArrayList<RankedDoc> result = new ArrayList<RankedDoc>();
        for (int i = 0; i < ranked.size(); ++i) {
            RankedDoc rd = (RankedDoc)ranked.get(i);
            result.add(new RankedDoc(rd.document, i + 1, "keyword", rd.keywordScore));
        }
        return result;
    }

    private List<RankedDoc> applyOcrTolerance(List<RankedDoc> results, String query) {
        Set<String> queryTerms = this.extractKeywords(query);
        HashMap<String, Set<String>> ocrVariants = new HashMap<String, Set<String>>();
        for (String term : queryTerms) {
            Set<String> variants = this.generateOcrVariants(term);
            ocrVariants.put(term, variants);
        }
        ArrayList<RankedDoc> boosted = new ArrayList<RankedDoc>();
        for (RankedDoc rd : results) {
            String content = rd.document.getContent().toLowerCase();
            int variantMatches = 0;
            block2: for (Set<String> variants : ocrVariants.values()) {
                for (String variant : variants) {
                    if (!content.contains(variant)) continue;
                    ++variantMatches;
                    continue block2;
                }
            }
            int boostedScore = rd.keywordScore + variantMatches * 2;
            boosted.add(new RankedDoc(rd.document, rd.rank, rd.source, boostedScore));
        }
        return boosted;
    }

    private Set<String> generateOcrVariants(String term) {
        HashSet<String> variants = new HashSet<String>();
        variants.add(term);
        String lower = term.toLowerCase();
        for (int i = 0; i < lower.length(); ++i) {
            char c = lower.charAt(i);
            char[] subs = OCR_SUBS.get(Character.valueOf(c));
            if (subs == null) continue;
            for (char sub : subs) {
                String variant = lower.substring(0, i) + sub + lower.substring(i + 1);
                variants.add(variant);
            }
        }
        return variants;
    }

    private List<Document> applyRrfFusion(Map<String, List<RankedDoc>> semanticResults, List<RankedDoc> keywordResults) {
        HashMap<String, Double> rrfScores = new HashMap<String, Double>();
        HashMap<String, Document> docMap = new HashMap<String, Document>();
        for (List<RankedDoc> results : semanticResults.values()) {
            for (RankedDoc rd : results) {
                String docId = this.getDocId(rd.document);
                double rrfContribution = this.semanticWeight / (double)(this.rrfK + rd.rank);
                rrfScores.merge(docId, rrfContribution, Double::sum);
                docMap.putIfAbsent(docId, rd.document);
            }
        }
        for (RankedDoc rd : keywordResults) {
            String docId = this.getDocId(rd.document);
            double rrfContribution = this.keywordWeight / (double)(this.rrfK + rd.rank);
            rrfScores.merge(docId, rrfContribution, Double::sum);
            docMap.putIfAbsent(docId, rd.document);
        }
        ArrayList<Map.Entry<String, Double>> sorted = new ArrayList<>(rrfScores.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return sorted.stream().limit(15L).map(e -> docMap.get(e.getKey())).filter(Objects::nonNull).collect(Collectors.toList());
    }

    private String getDocId(Document doc) {
        Object source = doc.getMetadata().get("source");
        int contentHash = doc.getContent().hashCode();
        Object dept = doc.getMetadata().get("dept");
        String deptValue = dept != null ? dept.toString() : "UNKNOWN";
        return deptValue + "_" + String.valueOf(source) + "_" + contentHash;
    }

    private Set<String> extractKeywords(String text) {
        HashSet<String> keywords = new HashSet<String>();
        String lower = text.toLowerCase();
        Set<String> stopWords = KEYWORD_STOP_WORDS;
        for (String word : lower.split("\\s+")) {
            String cleaned = word.replaceAll("[^a-z0-9]", "");
            if (cleaned.length() < 3 || stopWords.contains(cleaned)) continue;
            keywords.add(cleaned);
        }
        return keywords;
    }

    private int countKeywordMatches(String content, Set<String> keywords) {
        String lower = content.toLowerCase();
        int count = 0;
        for (String keyword : keywords) {
            if (!lower.contains(keyword)) continue;
            ++count;
        }
        return count;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public record HybridRetrievalResult(List<Document> documents, Map<String, Object> metadata) {
    }

    private record RankedDoc(Document document, int rank, String source, int keywordScore) {
        RankedDoc(Document document, int rank, String source) {
            this(document, rank, source, 0);
        }
    }

    private List<RankedDoc> retrieveSemantic(String variant, String filterExpression) {
        List<Document> results = this.vectorStore.similaritySearch(SearchRequest.query((String)variant).withTopK(15).withSimilarityThreshold(0.2).withFilterExpression(filterExpression));
        ArrayList<RankedDoc> ranked = new ArrayList<RankedDoc>();
        for (int i = 0; i < results.size(); ++i) {
            ranked.add(new RankedDoc(results.get(i), i + 1, "semantic"));
        }
        return ranked;
    }

    private String buildFilterExpression(String query, String department, String workspaceId) {
        String base = FilterExpressionBuilder.forDepartmentAndWorkspace(department, workspaceId);
        if (!this.temporalFilteringEnabled) {
            return base;
        }
        String yearFilter = TemporalQueryConstraints.buildDocumentYearFilter(query);
        return FilterExpressionBuilder.and(base, yearFilter);
    }

    private String normalizeDepartment(String department) {
        if (department == null) {
            return null;
        }
        try {
            return Department.fromString(department.trim().toUpperCase()).name();
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }
}
