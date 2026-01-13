package com.jreinhal.mercenary.rag.hybridrag;

import com.jreinhal.mercenary.reasoning.ReasoningTracer;
import com.jreinhal.mercenary.reasoning.ReasoningStep.StepType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Hybrid RAG: Robust Multilingual Document Question Answering
 * Based on research paper arXiv:2512.12694
 *
 * This service combines multiple retrieval strategies with fusion:
 * 1. SEMANTIC RETRIEVAL: Vector similarity search
 * 2. KEYWORD RETRIEVAL: BM25-style keyword matching
 * 3. MULTI-QUERY: Query expansion and reformulation
 * 4. RRF FUSION: Reciprocal Rank Fusion to combine results
 *
 * Handles:
 * - OCR errors and noisy text
 * - Multilingual documents
 * - Vocabulary mismatches
 * - Temporal language drift
 */
@Service
public class HybridRagService {

    private static final Logger log = LoggerFactory.getLogger(HybridRagService.class);

    private final VectorStore vectorStore;
    private final QueryExpander queryExpander;
    private final ReasoningTracer reasoningTracer;

    @Value("${sentinel.hybridrag.enabled:true}")
    private boolean enabled;

    @Value("${sentinel.hybridrag.rrf-k:60}")
    private int rrfK;

    @Value("${sentinel.hybridrag.semantic-weight:0.6}")
    private double semanticWeight;

    @Value("${sentinel.hybridrag.keyword-weight:0.4}")
    private double keywordWeight;

    @Value("${sentinel.hybridrag.multi-query-count:3}")
    private int multiQueryCount;

    @Value("${sentinel.hybridrag.ocr-tolerance:true}")
    private boolean ocrTolerance;

    public HybridRagService(VectorStore vectorStore,
                            QueryExpander queryExpander,
                            ReasoningTracer reasoningTracer) {
        this.vectorStore = vectorStore;
        this.queryExpander = queryExpander;
        this.reasoningTracer = reasoningTracer;
    }

    @PostConstruct
    public void init() {
        log.info("Hybrid RAG initialized (enabled={}, rrfK={}, semanticWeight={}, ocrTolerance={})",
                enabled, rrfK, semanticWeight, ocrTolerance);
    }

    /**
     * Perform hybrid retrieval with RRF fusion.
     *
     * @param query User's query
     * @param department Security department filter
     * @return Fused retrieval results
     */
    public HybridRetrievalResult retrieve(String query, String department) {
        if (!enabled) {
            // Fallback to standard retrieval
            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.query(query)
                            .withTopK(10)
                            .withSimilarityThreshold(0.3)
                            .withFilterExpression("dept == '" + department + "'"));
            return new HybridRetrievalResult(docs, Map.of("mode", "fallback"));
        }

        long startTime = System.currentTimeMillis();

        // Step 1: Generate query variants for multi-query retrieval
        List<String> queryVariants = generateQueryVariants(query);

        // Step 2: Semantic retrieval for each variant
        Map<String, List<RankedDoc>> semanticResults = new LinkedHashMap<>();
        for (String variant : queryVariants) {
            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.query(variant)
                            .withTopK(15)
                            .withSimilarityThreshold(0.2)
                            .withFilterExpression("dept == '" + department + "'"));

            List<RankedDoc> ranked = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                ranked.add(new RankedDoc(results.get(i), i + 1, "semantic"));
            }
            semanticResults.put(variant, ranked);
        }

        // Step 3: Keyword retrieval (simulated via low-threshold vector search + content filtering)
        List<RankedDoc> keywordResults = performKeywordRetrieval(query, department);

        // Step 4: Apply OCR tolerance if enabled
        if (ocrTolerance) {
            keywordResults = applyOcrTolerance(keywordResults, query);
        }

        // Step 5: RRF Fusion
        List<Document> fusedResults = applyRrfFusion(semanticResults, keywordResults);

        long elapsed = System.currentTimeMillis() - startTime;

        // Calculate metrics
        int totalCandidates = semanticResults.values().stream()
                .mapToInt(List::size).sum() + keywordResults.size();

        // Add reasoning step
        reasoningTracer.addStep(StepType.HYBRID_RETRIEVAL,
                "Hybrid RAG with RRF Fusion",
                String.format("%d query variants, %d candidates, %d fused results",
                        queryVariants.size(), totalCandidates, fusedResults.size()),
                elapsed,
                Map.of("queryVariants", queryVariants.size(),
                       "semanticCandidates", semanticResults.values().stream().mapToInt(List::size).sum(),
                       "keywordCandidates", keywordResults.size(),
                       "fusedResults", fusedResults.size()));

        log.info("HybridRAG: {} variants, {} candidates, {} fused in {}ms",
                queryVariants.size(), totalCandidates, fusedResults.size(), elapsed);

        return new HybridRetrievalResult(fusedResults, Map.of(
                "queryVariants", queryVariants,
                "semanticCount", semanticResults.values().stream().mapToInt(List::size).sum(),
                "keywordCount", keywordResults.size(),
                "elapsed", elapsed
        ));
    }

    /**
     * Generate query variants for multi-query retrieval.
     */
    private List<String> generateQueryVariants(String query) {
        List<String> variants = new ArrayList<>();
        variants.add(query); // Original query

        // Add expanded variants
        List<String> expansions = queryExpander.expand(query, multiQueryCount - 1);
        variants.addAll(expansions);

        return variants;
    }

    /**
     * Perform keyword-based retrieval.
     */
    private List<RankedDoc> performKeywordRetrieval(String query, String department) {
        // Extract keywords from query
        Set<String> keywords = extractKeywords(query);

        if (keywords.isEmpty()) {
            return List.of();
        }

        // Get broad candidate set
        List<Document> candidates = vectorStore.similaritySearch(
                SearchRequest.query(query)
                        .withTopK(50)
                        .withSimilarityThreshold(0.1)
                        .withFilterExpression("dept == '" + department + "'"));

        // Score by keyword overlap
        List<RankedDoc> ranked = new ArrayList<>();
        for (Document doc : candidates) {
            int keywordScore = countKeywordMatches(doc.getContent(), keywords);
            if (keywordScore > 0) {
                ranked.add(new RankedDoc(doc, 0, "keyword", keywordScore));
            }
        }

        // Sort by keyword score descending
        ranked.sort((a, b) -> Integer.compare(b.keywordScore, a.keywordScore));

        // Assign ranks
        List<RankedDoc> result = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            RankedDoc rd = ranked.get(i);
            result.add(new RankedDoc(rd.document, i + 1, "keyword", rd.keywordScore));
        }

        return result;
    }

    /**
     * Apply OCR tolerance by matching with common OCR error patterns.
     */
    private List<RankedDoc> applyOcrTolerance(List<RankedDoc> results, String query) {
        // Generate OCR-tolerant variants of query terms
        Set<String> queryTerms = extractKeywords(query);
        Map<String, Set<String>> ocrVariants = new HashMap<>();

        for (String term : queryTerms) {
            Set<String> variants = generateOcrVariants(term);
            ocrVariants.put(term, variants);
        }

        // Boost documents that match OCR variants
        List<RankedDoc> boosted = new ArrayList<>();
        for (RankedDoc rd : results) {
            String content = rd.document.getContent().toLowerCase();
            int variantMatches = 0;

            for (Set<String> variants : ocrVariants.values()) {
                for (String variant : variants) {
                    if (content.contains(variant)) {
                        variantMatches++;
                        break;
                    }
                }
            }

            int boostedScore = rd.keywordScore + (variantMatches * 2);
            boosted.add(new RankedDoc(rd.document, rd.rank, rd.source, boostedScore));
        }

        return boosted;
    }

    /**
     * Generate common OCR error variants for a term.
     */
    private Set<String> generateOcrVariants(String term) {
        Set<String> variants = new HashSet<>();
        variants.add(term);

        String lower = term.toLowerCase();

        // Common OCR substitutions
        Map<Character, char[]> ocrSubs = Map.of(
                '0', new char[]{'o', 'O'},
                'O', new char[]{'0'},
                'o', new char[]{'0'},
                '1', new char[]{'l', 'I', 'i'},
                'l', new char[]{'1', 'I'},
                'I', new char[]{'1', 'l'},
                '5', new char[]{'S', 's'},
                'S', new char[]{'5'},
                '8', new char[]{'B'},
                'B', new char[]{'8'}
        );

        // Generate single-character substitutions
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            char[] subs = ocrSubs.get(c);
            if (subs != null) {
                for (char sub : subs) {
                    String variant = lower.substring(0, i) + sub + lower.substring(i + 1);
                    variants.add(variant);
                }
            }
        }

        return variants;
    }

    /**
     * Apply Reciprocal Rank Fusion to combine results.
     */
    private List<Document> applyRrfFusion(Map<String, List<RankedDoc>> semanticResults,
                                           List<RankedDoc> keywordResults) {
        // Calculate RRF scores
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, Document> docMap = new HashMap<>();

        // Process semantic results
        for (List<RankedDoc> results : semanticResults.values()) {
            for (RankedDoc rd : results) {
                String docId = getDocId(rd.document);
                double rrfContribution = semanticWeight / (rrfK + rd.rank);

                rrfScores.merge(docId, rrfContribution, Double::sum);
                docMap.putIfAbsent(docId, rd.document);
            }
        }

        // Process keyword results
        for (RankedDoc rd : keywordResults) {
            String docId = getDocId(rd.document);
            double rrfContribution = keywordWeight / (rrfK + rd.rank);

            rrfScores.merge(docId, rrfContribution, Double::sum);
            docMap.putIfAbsent(docId, rd.document);
        }

        // Sort by RRF score
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(rrfScores.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        // Return top documents
        return sorted.stream()
                .limit(15)
                .map(e -> docMap.get(e.getKey()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Get unique identifier for a document.
     */
    private String getDocId(Document doc) {
        Object source = doc.getMetadata().get("source");
        int contentHash = doc.getContent().hashCode();
        return source + "_" + contentHash;
    }

    /**
     * Extract keywords from text.
     */
    private Set<String> extractKeywords(String text) {
        Set<String> keywords = new HashSet<>();
        String lower = text.toLowerCase();

        Set<String> stopWords = Set.of(
                "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
                "of", "with", "by", "from", "as", "is", "was", "are", "were", "been",
                "be", "have", "has", "had", "what", "where", "when", "who", "how", "why",
                "tell", "me", "about", "describe", "find", "show", "give", "also"
        );

        for (String word : lower.split("\\s+")) {
            String cleaned = word.replaceAll("[^a-z0-9]", "");
            if (cleaned.length() >= 3 && !stopWords.contains(cleaned)) {
                keywords.add(cleaned);
            }
        }

        return keywords;
    }

    /**
     * Count keyword matches in content.
     */
    private int countKeywordMatches(String content, Set<String> keywords) {
        String lower = content.toLowerCase();
        int count = 0;
        for (String keyword : keywords) {
            if (lower.contains(keyword)) {
                count++;
            }
        }
        return count;
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ==================== Record Types ====================

    public record HybridRetrievalResult(
            List<Document> documents,
            Map<String, Object> metadata) {}

    private record RankedDoc(
            Document document,
            int rank,
            String source,
            int keywordScore) {

        RankedDoc(Document document, int rank, String source) {
            this(document, rank, source, 0);
        }
    }
}
