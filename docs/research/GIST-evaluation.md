# GIST (Greedy Independent Set Thresholding) - Evaluation for Mercenary RAG

**Date:** 2026-01-23
**Status:** Deferred - No immediate application
**Sources:**
- https://research.google/blog/introducing-gist-the-next-stage-in-smart-sampling/
- https://arxiv.org/pdf/2405.18754

---

## What is GIST?

GIST (Greedy Independent Set Thresholding) is a data subset selection algorithm from NeurIPS 2025 that balances two competing objectives:

- **Diversity**: Ensuring selected data points aren't redundant (maximizing minimum distances)
- **Utility**: Selecting data that is relevant and informationally valuable

### How It Works

1. **Thresholding**: Fixes a minimum distance threshold, constructs a graph where points closer than this threshold are connected (too similar for joint selection)
2. **Independent Set**: Identifies maximum-utility subset where no two selected points are connected
3. **Iteration**: Tests multiple distance thresholds, selects optimal balance

### Key Guarantees

- Finds subsets with utility at least half the optimal value
- Proven NP-hardness bound (can't do better efficiently)
- Negligible runtime compared to model training (but not negligible for real-time RAG)

---

## Evaluation Against Current Mercenary Implementation

### Current RAG Diversity Mechanisms

| Component | How It Provides Diversity |
|-----------|---------------------------|
| Multi-Query Expansion | 3 query variants sample different embedding regions |
| RRF Fusion | Combines vector + BM25 rankings (semantic vs lexical) |
| HiFi-RAG Gap Detection | Iteratively fills uncovered concepts |
| RAGPart Partitions | Verifies docs across multiple partition combinations |
| Department Filtering | Scopes to authorized sectors |

### Why GIST Is Not Needed Now

1. **Scale mismatch**: GIST designed for selecting 10K items from 1M. We select 15 from 50.
2. **Latency cost**: O(n²) distance computation + graph algorithms vs current O(1) `.limit(15)`
3. **Problem not observed**: No user feedback indicating redundant/duplicate retrieval issues
4. **Multi-query already diversifies**: Query variants implicitly sample diverse embedding regions

### Potential Future Applications

| Use Case | When to Revisit |
|----------|-----------------|
| RLHF Training Data Export | When building training data curation pipeline |
| Cross-Sector Queries | If users access 10+ departments, 500+ candidates |
| Redundant Corpora | If ingesting versioned docs, templates, legal boilerplate |
| Document Deduplication | As preprocessing during ingestion, not retrieval |

---

## Revisit Triggers

Consider implementing GIST if any of these conditions emerge:

- [ ] User complaints about repetitive/redundant retrieved content
- [ ] Scaling to 100+ candidates before final selection
- [ ] Building RLHF training data export feature
- [ ] Ingesting corpora with known high redundancy
- [ ] Metrics showing answers miss aspects due to duplicate doc coverage

---

## Implementation Notes (If Needed Later)

### Primary Integration Point

`HybridRagService.java` → after RRF fusion, before returning results

```java
// Sketch - not production code
public List<Document> selectWithGIST(List<Document> candidates, int targetK) {
    // Build distance graph: edge if cosine(doc_i, doc_j) > threshold
    double diversityThreshold = 0.85;

    // Iterate thresholds, find max-utility independent set
    List<Document> best = null;
    double bestScore = 0;

    for (double t = 0.7; t <= 0.95; t += 0.05) {
        Set<Integer> independentSet = greedyIndependentSet(candidates, t);
        double utility = sumRrfScores(independentSet, candidates);
        if (utility > bestScore && independentSet.size() >= targetK) {
            best = extract(independentSet, candidates);
            bestScore = utility;
        }
    }
    return best.subList(0, Math.min(targetK, best.size()));
}
```

### Secondary Applications

- `QueryExpander.java` - Select diverse query variants
- RLHF export utility - Curate training data
- Ingestion pipeline - Deduplicate before vectorization

---

## Decision

**Deferred.** Current RRF + multi-query approach is sufficient for real-time RAG with small result sets. Revisit when scaling requirements or user feedback indicate diversity problems.
