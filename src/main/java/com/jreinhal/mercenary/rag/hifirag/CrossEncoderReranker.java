package com.jreinhal.mercenary.rag.hifirag;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jreinhal.mercenary.rag.hifirag.HiFiRagService;
import com.jreinhal.mercenary.constant.StopWords;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Component
public class CrossEncoderReranker {
    private static final Logger log = LoggerFactory.getLogger(CrossEncoderReranker.class);
    private static final Pattern SCORE_PATTERN = Pattern.compile("(0\\.\\d+|1\\.0|0|1)");
    private static final Set<String> STOP_WORDS = StopWords.RERANKER;
    private final ChatClient chatClient;
    private final ExecutorService executor;
    private final EmbeddingModel embeddingModel;
    @Value("${sentinel.hifirag.reranker.batch-size:5}")
    private int batchSize;
    @Value("${sentinel.hifirag.reranker.timeout-seconds:30}")
    private int timeoutSeconds;
    @Value("${sentinel.hifirag.reranker.mode:dedicated}")
    private String rerankerMode;
    @Value("${sentinel.hifirag.reranker.use-llm:true}")
    private boolean useLlm;
    @Value("${sentinel.hifirag.reranker.cache-size:2000}")
    private int cacheSize;
    @Value("${sentinel.hifirag.reranker.cache-ttl-seconds:900}")
    private long cacheTtlSeconds;
    private Cache<String, Double> scoreCache;

    public CrossEncoderReranker(ChatClient.Builder builder, @Qualifier("rerankerExecutor") ExecutorService executor, @Nullable EmbeddingModel embeddingModel) {
        this.chatClient = builder.build();
        this.executor = executor;
        this.embeddingModel = embeddingModel;
    }

    @PostConstruct
    public void init() {
        if (this.cacheSize > 0 && this.cacheTtlSeconds > 0) {
            this.scoreCache = Caffeine.newBuilder()
                    .maximumSize(this.cacheSize)
                    .expireAfterWrite(Duration.ofSeconds(this.cacheTtlSeconds))
                    .build();
        }
        if (log.isInfoEnabled()) {
            log.info("Cross-Encoder Reranker initialized (mode={}, useLlmFallback={}, embeddingModel={})",
                    this.rerankerMode, this.useLlm, this.embeddingModel != null);
        }
    }

    public List<HiFiRagService.ScoredDocument> rerank(String query, List<Document> documents) {
        if (documents.isEmpty()) {
            return List.of();
        }
        if (log.isDebugEnabled()) {
            log.debug("Cross-encoder reranking {} documents", documents.size());
        }
        long startTime = System.currentTimeMillis();
        List<HiFiRagService.ScoredDocument> scored = List.of();
        RerankerMode mode = this.resolveRerankerMode();
        switch (mode) {
            case DEDICATED -> {
                scored = this.rerankWithDedicatedModel(query, documents);
                if (scored.isEmpty()) {
                    scored = this.useLlm ? this.rerankWithLlm(query, documents) : this.rerankWithKeywords(query, documents);
                }
            }
            case LLM -> scored = this.rerankWithLlm(query, documents);
            case KEYWORD -> scored = this.rerankWithKeywords(query, documents);
        }
        if (scored.isEmpty() && !documents.isEmpty()) {
            scored = this.rerankWithKeywords(query, documents);
        }
        if (log.isDebugEnabled()) {
            log.debug("Reranking completed in {}ms", System.currentTimeMillis() - startTime);
        }
        return scored.stream().sorted((a, b) -> Double.compare(b.score(), a.score())).collect(Collectors.toList());
    }

    private RerankerMode resolveRerankerMode() {
        String mode = this.rerankerMode != null ? this.rerankerMode.trim().toUpperCase(Locale.ROOT) : "";
        if (mode.isEmpty() || "AUTO".equals(mode)) {
            return this.useLlm ? RerankerMode.LLM : RerankerMode.KEYWORD;
        }
        try {
            return RerankerMode.valueOf(mode);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown reranker mode '{}'; using AUTO behavior", this.rerankerMode);
            return this.useLlm ? RerankerMode.LLM : RerankerMode.KEYWORD;
        }
    }

    private List<HiFiRagService.ScoredDocument> rerankWithLlm(String query, List<Document> documents) {
        List<HiFiRagService.ScoredDocument> results = new ArrayList<HiFiRagService.ScoredDocument>();
        boolean poolExhausted = false;
        for (int i = 0; i < documents.size(); i += this.batchSize) {
            if (poolExhausted) {
                break;
            }
            int end = Math.min(i + this.batchSize, documents.size());
            List<Document> batch = documents.subList(i, end);
            List<Future<HiFiRagService.ScoredDocument>> futures = new ArrayList<Future<HiFiRagService.ScoredDocument>>();
            for (Document document : batch) {
                try {
                    futures.add(this.executor.submit(() -> this.scoreDocument(query, document)));
                } catch (RejectedExecutionException e) {
                    if (log.isWarnEnabled()) {
                        log.warn("Reranker thread pool overloaded; skipping document scoring: {}", e.getMessage());
                    }
                    poolExhausted = true;
                    break;
                }
            }
            for (Future<HiFiRagService.ScoredDocument> future : futures) {
                try {
                    HiFiRagService.ScoredDocument sd = (HiFiRagService.ScoredDocument)future.get(this.timeoutSeconds, TimeUnit.SECONDS);
                    if (sd == null) continue;
                    results.add(sd);
                }
                catch (TimeoutException e) {
                    if (log.isWarnEnabled()) {
                        log.warn("Cross-encoder scoring timed out");
                    }
                }
                catch (Exception e) {
                    if (log.isWarnEnabled()) {
                        log.warn("Cross-encoder scoring failed: {}", e.getMessage());
                    }
                }
            }
        }
        return results;
    }

    private List<HiFiRagService.ScoredDocument> rerankWithDedicatedModel(String query, List<Document> documents) {
        if (this.embeddingModel == null) {
            if (log.isWarnEnabled()) {
                log.warn("Dedicated reranker mode requested but no EmbeddingModel is available");
            }
            return List.of();
        }
        float[] queryEmbedding = this.safeEmbed("query: " + query);
        if (queryEmbedding.length == 0) {
            return List.of();
        }
        List<HiFiRagService.ScoredDocument> results = new ArrayList<>(documents.size());
        for (Document document : documents) {
            results.add(this.scoreWithDedicatedModel(query, queryEmbedding, document));
        }
        return results;
    }

    private HiFiRagService.ScoredDocument scoreWithDedicatedModel(String query, float[] queryEmbedding, Document doc) {
        try {
            String cacheKey = this.buildCacheKey(query, doc);
            if (this.scoreCache != null) {
                Double cachedScore = this.scoreCache.getIfPresent(cacheKey);
                if (cachedScore != null) {
                    return new HiFiRagService.ScoredDocument(doc, cachedScore.doubleValue());
                }
            }
            String content = doc.getContent() != null ? doc.getContent() : "";
            if (content.length() > 1000) {
                content = content.substring(0, 1000) + "...";
            }
            float[] pairEmbedding = this.safeEmbed("query: " + query + "\ndocument: " + content);
            if (pairEmbedding.length == 0 || pairEmbedding.length != queryEmbedding.length) {
                if (log.isDebugEnabled()) {
                    log.debug(
                            "Dedicated reranker produced invalid embedding for query-document pair; using keyword fallback (query len={}, pair len={})",
                            queryEmbedding.length, pairEmbedding.length);
                }
                return this.scoreWithKeywords(query, doc);
            }
            double cosine = this.calculateCosineSimilarity(queryEmbedding, pairEmbedding);
            double score = Math.max(0.0, Math.min(1.0, (cosine + 1.0) / 2.0));
            if (this.scoreCache != null) {
                this.scoreCache.put(cacheKey, score);
            }
            return new HiFiRagService.ScoredDocument(doc, score);
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("Dedicated model scoring failed, using keyword fallback: {}", e.getMessage());
            }
            return this.scoreWithKeywords(query, doc);
        }
    }

    private HiFiRagService.ScoredDocument scoreDocument(String query, Document doc) {
        try {
            String cacheKey = this.buildCacheKey(query, doc);
            if (this.scoreCache != null) {
                Double cachedScore = this.scoreCache.getIfPresent(cacheKey);
                if (cachedScore != null) {
                    return new HiFiRagService.ScoredDocument(doc, cachedScore.doubleValue());
                }
            }
            String content = doc.getContent();
            if (content == null) {
                content = "";
            }
            if (content.length() > 1000) {
                content = content.substring(0, 1000) + "...";
            }
            String promptText = String.format("Rate the relevance of this document to the query on a scale of 0.0 to 1.0.\n\nQUERY: %s\n\nDOCUMENT:\n%s\n\nRespond with ONLY a number between 0.0 and 1.0, nothing else.\n0.0 = completely irrelevant\n0.5 = somewhat relevant\n1.0 = highly relevant\n\nScore:", query, content);
            String response = this.chatClient.prompt().user(promptText).call().content();
            double score = this.parseScore(response);
            if (this.scoreCache != null) {
                this.scoreCache.put(cacheKey, score);
            }
            return new HiFiRagService.ScoredDocument(doc, score);
        }
        catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("LLM scoring failed for document, using fallback: {}", e.getMessage());
            }
            return this.scoreWithKeywords(query, doc);
        }
    }

    private double parseScore(String response) {
        if (response == null || response.isEmpty()) {
            return 0.5;
        }
        Matcher matcher = SCORE_PATTERN.matcher(response.trim());
        if (matcher.find()) {
            try {
                double score = Double.parseDouble(matcher.group(1));
                return Math.max(0.0, Math.min(1.0, score));
            }
            catch (NumberFormatException numberFormatException) {
                if (log.isDebugEnabled()) {
                    log.debug("Cross-encoder score parse failed: {}", response);
                }
            }
        }
        return 0.5;
    }

    private float[] safeEmbed(String text) {
        if (text == null || text.isBlank() || this.embeddingModel == null) {
            return new float[0];
        }
        try {
            float[] embedding = this.embeddingModel.embed(text);
            return embedding != null ? embedding : new float[0];
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("Dedicated reranker embedding failed: {}", e.getMessage());
            }
            return new float[0];
        }
    }

    private List<HiFiRagService.ScoredDocument> rerankWithKeywords(String query, List<Document> documents) {
        return documents.stream().map(doc -> this.scoreWithKeywords(query, (Document)doc)).collect(Collectors.toList());
    }

    private HiFiRagService.ScoredDocument scoreWithKeywords(String query, Document doc) {
        String cacheKey = this.buildCacheKey(query, doc);
        if (this.scoreCache != null) {
            Double cachedScore = this.scoreCache.getIfPresent(cacheKey);
            if (cachedScore != null) {
                return new HiFiRagService.ScoredDocument(doc, cachedScore.doubleValue());
            }
        }
        String lowerQuery = query.toLowerCase();
        String lowerContent = doc.getContent() != null ? doc.getContent().toLowerCase() : "";
        Set<String> stopWords = STOP_WORDS;
        String[] queryTerms = lowerQuery.split("\\W+");
        int totalTerms = 0;
        int matchedTerms = 0;
        for (String term : queryTerms) {
            if (term.length() <= 2 || stopWords.contains(term)) continue;
            ++totalTerms;
            if (!lowerContent.contains(term)) continue;
            ++matchedTerms;
        }
        double score = totalTerms > 0 ? (double)matchedTerms / (double)totalTerms : 0.0;
        Object source = doc.getMetadata().get("source");
        if (source != null) {
            String lowerSource = source.toString().toLowerCase();
            for (String term : queryTerms) {
                if (term.length() <= 2 || !lowerSource.contains(term)) continue;
                score = Math.min(1.0, score + 0.1);
            }
        }
        if (this.scoreCache != null) {
            this.scoreCache.put(cacheKey, score);
        }
        return new HiFiRagService.ScoredDocument(doc, score);
    }

    private double calculateCosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * (double) b[i];
            normA += (double) a[i] * (double) a[i];
            normB += (double) b[i] * (double) b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private String buildCacheKey(String query, Document doc) {
        Object dept = doc.getMetadata().get("dept");
        String deptValue = dept != null ? dept.toString() : "UNKNOWN";
        Object source = doc.getMetadata().get("source");
        String sourceStr = source != null ? source.toString() : "";
        String content = doc.getContent() != null ? doc.getContent() : "";
        return deptValue + "|" + query + "|" + sourceStr + "|" + content.hashCode();
    }

    private enum RerankerMode {
        DEDICATED,
        LLM,
        KEYWORD
    }
}
