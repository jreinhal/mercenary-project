package com.jreinhal.mercenary.enterprise.rag;

import com.jreinhal.mercenary.enterprise.rag.sparse.SparseEmbeddingService;
import com.jreinhal.mercenary.util.LogSanitizer;
import com.jreinhal.mercenary.vector.LocalMongoVectorStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class HybridSearchService {
    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);
    private final VectorStore vectorStore;
    private static final int RRF_K = 60;
    private static final double DEFAULT_VECTOR_WEIGHT = 0.6;
    private static final double DEFAULT_LEXICAL_WEIGHT = 0.4;

    @Autowired(required = false)
    private SparseEmbeddingService sparseEmbeddingService;

    @Autowired(required = false)
    private LocalMongoVectorStore localMongoVectorStore;

    public HybridSearchService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public List<HybridResult> search(String query, int topK) {
        return this.search(query, topK, 0.6, 0.4);
    }

    public List<HybridResult> search(String query, int topK, double vectorWeight, double lexicalWeight) {
        log.debug("Hybrid search: query={}, topK={}, weights=[vector={}, lexical={}]", LogSanitizer.querySummary(query), topK, vectorWeight, lexicalWeight);
        List<Document> vectorResults = this.performVectorSearch(query, topK * 2);
        List<Document> lexicalResults = this.performBm25Search(query, topK * 2);
        List<HybridResult> fusedResults = this.fuseResults(vectorResults, lexicalResults, vectorWeight, lexicalWeight);
        return fusedResults.stream().limit(topK).toList();
    }

    private List<Document> performVectorSearch(String query, int limit) {
        try {
            SearchRequest request = SearchRequest.query((String)query).withTopK(limit);
            return this.vectorStore.similaritySearch(request);
        }
        catch (Exception e) {
            log.error("Vector search failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Document> performBm25Search(String query, int limit) {
        // Try sparse retrieval first (learned lexical weights from BGE-M3 sidecar)
        List<Document> sparseResults = this.performSparseSearch(query, limit);
        if (!sparseResults.isEmpty()) {
            return sparseResults;
        }
        // Fallback: hand-coded BM25
        try {
            List<Document> candidates = this.performVectorSearch(query, limit * 3);
            ArrayList<Map.Entry<Document, Double>> scored = new ArrayList<Map.Entry<Document, Double>>();
            for (Document doc : candidates) {
                double score = this.calculateBm25Score(query, doc.getContent());
                scored.add(Map.entry(doc, score));
            }
            scored.sort((a, b) -> Double.compare((Double)b.getValue(), (Double)a.getValue()));
            return scored.stream().limit(limit).map(Map.Entry::getKey).toList();
        }
        catch (Exception e) {
            log.error("BM25 search failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Sparse retrieval using learned lexical weights from the FlagEmbedding sidecar.
     * Returns empty list if the sidecar is unavailable, allowing fallback to BM25.
     */
    private List<Document> performSparseSearch(String query, int limit) {
        if (this.sparseEmbeddingService == null || !this.sparseEmbeddingService.isEnabled()
                || this.localMongoVectorStore == null) {
            return List.of();
        }
        try {
            Map<String, Float> queryWeights = this.sparseEmbeddingService.embedQuery(query);
            if (queryWeights.isEmpty()) {
                return List.of();
            }
            List<Document> results = this.localMongoVectorStore.sparseSearch(queryWeights, null, limit, 0.01);
            if (log.isDebugEnabled()) {
                log.debug("Sparse search returned {} results (replacing BM25)", results.size());
            }
            return results;
        } catch (Exception e) {
            log.warn("Sparse search failed, falling back to BM25: {}", e.getMessage());
            return List.of();
        }
    }

    private double calculateBm25Score(String query, String document) {
        double k1 = 1.2;
        double b = 0.75;
        double avgDocLength = 500.0;
        Set<String> queryTerms = this.tokenize(query);
        ArrayList<String> docTerms = new ArrayList<String>(this.tokenize(document));
        int docLength = docTerms.size();
        double score = 0.0;
        for (String term : queryTerms) {
            long tf = docTerms.stream().filter(t -> t.equals(term)).count();
            if (tf == 0L) continue;
            double idf = Math.log(2.0);
            double numerator = (double)tf * (k1 + 1.0);
            double denominator = (double)tf + k1 * (1.0 - b + b * ((double)docLength / avgDocLength));
            score += idf * (numerator / denominator);
        }
        return score;
    }

    private Set<String> tokenize(String text) {
        if (text == null) {
            return Set.of();
        }
        return Arrays.stream(text.toLowerCase().replaceAll("[^a-z0-9\\s]", " ").split("\\s+")).filter(t -> t.length() > 2).collect(Collectors.toSet());
    }

    private List<HybridResult> fuseResults(List<Document> vectorResults, List<Document> lexicalResults, double vectorWeight, double lexicalWeight) {
        HashMap<String, Integer> vectorRanks = new HashMap<String, Integer>();
        HashMap<String, Double> vectorScores = new HashMap<String, Double>();
        for (int i = 0; i < vectorResults.size(); ++i) {
            String docId = this.getDocumentId(vectorResults.get(i));
            vectorRanks.put(docId, i + 1);
            vectorScores.put(docId, 1.0 / (double)(i + 1));
        }
        HashMap<String, Integer> lexicalRanks = new HashMap<String, Integer>();
        HashMap<String, Double> lexicalScores = new HashMap<String, Double>();
        for (int i = 0; i < lexicalResults.size(); ++i) {
            String docId = this.getDocumentId(lexicalResults.get(i));
            lexicalRanks.put(docId, i + 1);
            lexicalScores.put(docId, 1.0 / (double)(i + 1));
        }
        HashMap<String, Document> allDocs = new HashMap<String, Document>();
        for (Document doc : vectorResults) {
            allDocs.put(this.getDocumentId(doc), doc);
        }
        for (Document doc : lexicalResults) {
            allDocs.put(this.getDocumentId(doc), doc);
        }
        ArrayList<HybridResult> results = new ArrayList<HybridResult>();
        for (Map.Entry<String, Document> entry : allDocs.entrySet()) {
            String docId = entry.getKey();
            Document doc = entry.getValue();
            int vRank = vectorRanks.getOrDefault(docId, Integer.MAX_VALUE);
            int lRank = lexicalRanks.getOrDefault(docId, Integer.MAX_VALUE);
            double vScore = vectorScores.getOrDefault(docId, 0.0);
            double lScore = lexicalScores.getOrDefault(docId, 0.0);
            double rrfVector = vRank < Integer.MAX_VALUE ? 1.0 / (double)(60 + vRank) : 0.0;
            double rrfLexical = lRank < Integer.MAX_VALUE ? 1.0 / (double)(60 + lRank) : 0.0;
            double combinedScore = vectorWeight * rrfVector + lexicalWeight * rrfLexical;
            results.add(new HybridResult(doc, combinedScore, vScore, lScore, vRank, lRank));
        }
        results.sort((a, b) -> Double.compare(b.combinedScore(), a.combinedScore()));
        return results;
    }

    private String getDocumentId(Document doc) {
        if (doc.getMetadata().containsKey("id")) {
            return doc.getMetadata().get("id").toString();
        }
        if (doc.getMetadata().containsKey("source")) {
            return doc.getMetadata().get("source").toString();
        }
        return String.valueOf(doc.getContent().hashCode());
    }

    public double[] suggestWeights(String query) {
        double vectorWeight = 0.6;
        double lexicalWeight = 0.4;
        if (query.length() < 30) {
            lexicalWeight += 0.1;
            vectorWeight -= 0.1;
        }
        if (query.matches(".*\\b[A-Z]{2,}\\b.*")) {
            lexicalWeight += 0.15;
            vectorWeight -= 0.15;
        }
        if (query.contains("\"")) {
            lexicalWeight += 0.2;
            vectorWeight -= 0.2;
        }
        if (query.length() > 100) {
            vectorWeight += 0.1;
            lexicalWeight -= 0.1;
        }
        if (query.contains("?") || query.toLowerCase().startsWith("how") || query.toLowerCase().startsWith("what") || query.toLowerCase().startsWith("why")) {
            vectorWeight += 0.1;
            lexicalWeight -= 0.1;
        }
        double total = vectorWeight + lexicalWeight;
        return new double[]{vectorWeight / total, lexicalWeight / total};
    }

    public record HybridResult(Document document, double combinedScore, double vectorScore, double lexicalScore, int vectorRank, int lexicalRank) {
    }
}
