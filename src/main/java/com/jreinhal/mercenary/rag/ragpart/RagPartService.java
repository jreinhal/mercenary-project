package com.jreinhal.mercenary.rag.ragpart;

import com.jreinhal.mercenary.rag.ragpart.PartitionAssigner;
import com.jreinhal.mercenary.rag.ragpart.SuspicionScorer;
import com.jreinhal.mercenary.reasoning.ReasoningStep;
import com.jreinhal.mercenary.reasoning.ReasoningTracer;
import com.jreinhal.mercenary.util.FilterExpressionBuilder;
import com.jreinhal.mercenary.util.LogSanitizer;
import com.jreinhal.mercenary.workspace.WorkspaceContext;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RagPartService {
    private static final Logger log = LoggerFactory.getLogger(RagPartService.class);
    private final VectorStore vectorStore;
    private final PartitionAssigner partitionAssigner;
    private final SuspicionScorer suspicionScorer;
    private final ReasoningTracer reasoningTracer;
    @Value(value="${sentinel.ragpart.enabled:true}")
    private boolean enabled;
    @Value(value="${sentinel.ragpart.partitions:4}")
    private int numPartitions;
    @Value(value="${sentinel.ragpart.combination-size:3}")
    private int combinationSize;
    @Value(value="${sentinel.ragpart.suspicion-threshold:0.4}")
    private double suspicionThreshold;
    @Value(value="${sentinel.ragpart.retrieval-k:10}")
    private int retrievalK;

    public RagPartService(VectorStore vectorStore, PartitionAssigner partitionAssigner, SuspicionScorer suspicionScorer, ReasoningTracer reasoningTracer) {
        this.vectorStore = vectorStore;
        this.partitionAssigner = partitionAssigner;
        this.suspicionScorer = suspicionScorer;
        this.reasoningTracer = reasoningTracer;
    }

    @PostConstruct
    public void init() {
        log.info("RAGPart Service initialized (enabled={}, partitions={}, combinationSize={}, threshold={})", new Object[]{this.enabled, this.numPartitions, this.combinationSize, this.suspicionThreshold});
    }

    public RagPartResult retrieve(String query, String department) {
        if (!this.enabled) {
            log.debug("RAGPart disabled, performing undefended retrieval");
            List<Document> docs = this.standardRetrieval(query, department);
            return new RagPartResult(docs, List.of(), Map.of());
        }
        long startTime = System.currentTimeMillis();
        String workspaceId = WorkspaceContext.getCurrentWorkspaceId();
        log.info("RAGPart: Starting defended retrieval for query {}", LogSanitizer.querySummary(query));
        List<Set<Integer>> combinations = this.generateCombinations(this.numPartitions, this.combinationSize);
        log.debug("RAGPart: Generated {} partition combinations", combinations.size());
        HashMap<String, DocumentAppearance> documentAppearances = new HashMap<String, DocumentAppearance>();
        for (int i = 0; i < combinations.size(); ++i) {
            Set<Integer> partitionSet = combinations.get(i);
            String partitionFilter = this.buildPartitionFilter(partitionSet, department, workspaceId);
            List<Document> combinationResults = this.retrieveWithFilter(query, partitionFilter);
            log.debug("RAGPart: Combination {} ({}) returned {} documents", new Object[]{i + 1, partitionSet, combinationResults.size()});
            Iterator iterator = combinationResults.iterator();
            while (iterator.hasNext()) {
                Document doc = (Document)iterator.next();
                String docId = this.getDocumentId(doc);
                documentAppearances.computeIfAbsent(docId, k -> new DocumentAppearance(doc)).addAppearance(i, partitionSet);
            }
        }
        HashMap<String, Double> suspicionScores = new HashMap<String, Double>();
        ArrayList<Document> verifiedDocs = new ArrayList<Document>();
        ArrayList<Document> suspiciousDocs = new ArrayList<Document>();
        for (DocumentAppearance appearance : documentAppearances.values()) {
            double score = this.suspicionScorer.calculateScore(appearance.getAppearanceCount(), combinations.size(), appearance.getPartitionVariance());
            suspicionScores.put(this.getDocumentId(appearance.getDocument()), score);
            if (score < this.suspicionThreshold) {
                verifiedDocs.add(appearance.getDocument());
                continue;
            }
            suspiciousDocs.add(appearance.getDocument());
            log.warn("RAGPart: Suspicious document detected (score={:.2f}): {}", score, appearance.getDocument().getMetadata().get("source"));
        }
        long duration = System.currentTimeMillis() - startTime;
        this.reasoningTracer.addStep(ReasoningStep.StepType.FILTERING, "RAGPart Defense", String.format("Analyzed %d documents across %d combinations: %d verified, %d suspicious (threshold=%.2f)", documentAppearances.size(), combinations.size(), verifiedDocs.size(), suspiciousDocs.size(), this.suspicionThreshold), duration, Map.of("totalDocuments", documentAppearances.size(), "combinations", combinations.size(), "verified", verifiedDocs.size(), "suspicious", suspiciousDocs.size(), "suspicionThreshold", this.suspicionThreshold));
        log.info("RAGPart: Completed in {}ms - {} verified, {} suspicious documents", new Object[]{duration, verifiedDocs.size(), suspiciousDocs.size()});
        return new RagPartResult(verifiedDocs, suspiciousDocs, suspicionScores);
    }

    private List<Set<Integer>> generateCombinations(int n, int k) {
        ArrayList<Set<Integer>> result = new ArrayList<Set<Integer>>();
        this.generateCombinationsRecursive(result, new HashSet<Integer>(), 0, n, k);
        return result;
    }

    private void generateCombinationsRecursive(List<Set<Integer>> result, Set<Integer> current, int start, int n, int k) {
        if (current.size() == k) {
            result.add(new HashSet<Integer>(current));
            return;
        }
        for (int i = start; i < n; ++i) {
            current.add(i);
            this.generateCombinationsRecursive(result, current, i + 1, n, k);
            current.remove(i);
        }
    }

    private String buildPartitionFilter(Set<Integer> partitions, String department, String workspaceId) {
        String partitionIn = partitions.stream().map(String::valueOf).collect(Collectors.joining(","));
        return FilterExpressionBuilder.forDepartmentAndWorkspace(department, workspaceId) + " && partition_id in [" + partitionIn + "]";
    }

    private List<Document> retrieveWithFilter(String query, String filter) {
        try {
            return this.vectorStore.similaritySearch(SearchRequest.query((String)query).withTopK(this.retrievalK).withSimilarityThreshold(0.1).withFilterExpression(filter));
        }
        catch (Exception e) {
            log.debug("RAGPart: Partition filter failed, using standard retrieval: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Document> standardRetrieval(String query, String department) {
        try {
            String workspaceId = WorkspaceContext.getCurrentWorkspaceId();
            return this.vectorStore.similaritySearch(SearchRequest.query((String)query).withTopK(this.retrievalK).withSimilarityThreshold(0.15).withFilterExpression(FilterExpressionBuilder.forDepartmentAndWorkspace(department, workspaceId)));
        }
        catch (Exception e) {
            log.error("Standard retrieval failed", (Throwable)e);
            return List.of();
        }
    }

    private String getDocumentId(Document doc) {
        Object source = doc.getMetadata().get("source");
        if (source != null) {
            return source.toString() + "_" + Math.abs(doc.getContent().hashCode());
        }
        return String.valueOf(Math.abs(doc.getContent().hashCode()));
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public record RagPartResult(List<Document> verifiedDocuments, List<Document> suspiciousDocuments, Map<String, Double> suspicionScores) {
        public boolean hasSuspiciousDocuments() {
            return !this.suspiciousDocuments.isEmpty();
        }

        public int totalDocuments() {
            return this.verifiedDocuments.size() + this.suspiciousDocuments.size();
        }
    }

    private static class DocumentAppearance {
        private final Document document;
        private final List<Integer> combinationIndices = new ArrayList<Integer>();
        private final List<Set<Integer>> partitionSets = new ArrayList<Set<Integer>>();

        DocumentAppearance(Document document) {
            this.document = document;
        }

        void addAppearance(int combinationIndex, Set<Integer> partitionSet) {
            this.combinationIndices.add(combinationIndex);
            this.partitionSets.add(partitionSet);
        }

        Document getDocument() {
            return this.document;
        }

        int getAppearanceCount() {
            return this.combinationIndices.size();
        }

        double getPartitionVariance() {
            if (this.partitionSets.isEmpty()) {
                return 1.0;
            }
            HashMap<Integer, Integer> partitionFreq = new HashMap<Integer, Integer>();
            for (Set<Integer> set : this.partitionSets) {
                for (Integer p : set) {
                    partitionFreq.merge(p, 1, Integer::sum);
                }
            }
            double mean = partitionFreq.values().stream().mapToInt(Integer::intValue).average().orElse(0.0);
            if (mean == 0.0) {
                return 1.0;
            }
            double variance = partitionFreq.values().stream().mapToDouble(v -> Math.pow((double)v.intValue() - mean, 2.0)).average().orElse(0.0);
            return Math.min(1.0, variance / (mean * mean + 1.0));
        }
    }
}
