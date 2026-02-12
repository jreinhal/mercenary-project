# SENTINEL RAG Evolution: Comprehensive Implementation Plan

> INTERNAL: Engineering planning document. Not customer-facing.

> **Document Version**: 1.0
> **Date**: 2026-01-21
> **Based On**: Analysis of 12 cutting-edge RAG research papers + existing codebase audit
> **Co-Authored-By**: Claude Opus 4.5 <noreply@anthropic.com>

---

## Executive Summary

This document consolidates recommendations from 12 state-of-the-art RAG research papers against SENTINEL's active codebase. The analysis reveals that powerful capabilities exist (MegaRAG, MiA-RAG, AgenticRagOrchestrator, RagPartService) but remain **dormant or partially wired**.

**Current State**: Text-only search with regex-based routing
**Target State**: Multimodal Intelligence Platform with defense-in-depth, hierarchical retrieval, and hallucination detection

### The "Core Five" Implementation Priorities

| Priority | Feature | Security Impact | Implementation Effort |
|----------|---------|-----------------|----------------------|
| 1 | RAGPart Defense Activation | **CRITICAL** | Low |
| 2 | Agentic Routing Activation | Medium | Low |
| 3 | Multimodal Ingestion | Medium | Medium |
| 4 | Hallucination Detection Activation | High | **Low** ⚡ |
| 5 | Hierarchical Context (Mindscape) | Low | Medium |

> **📝 Note (Per Gemini Code Audit)**: Priority 4 (Hallucination Detection) was promoted from Priority 5 because `QuCoRagService` is **already fully implemented** and injected into `MercenaryController`. It currently runs in **PASSIVE MODE** (logs warnings only). Activation requires minimal code changes to switch from logging to action.

---

## Part I: Security Foundation

### 1. Activate RAGPart Corpus Poisoning Defense

**Research Basis**: RAGPart & RAGMask (arXiv:2512.24268)

#### The Threat

Corpus poisoning attacks inject malicious documents designed to be retrieved for specific queries, manipulating LLM outputs. Attack success rates against undefended systems: **87-91%**.

#### Current Gap

`RagPartService` exists in the codebase but is **never called**. The retrieval pipeline directly queries the vector store without validation.

```
Current Flow:
Query → vectorStore.similaritySearch() → LLM Generation

Vulnerable to:
- HotFlip gradient attacks
- Query-as-Poison attacks
- AdvRAGgen interpretable attacks
```

#### Implementation

**File**: `src/main/java/com/jreinhal/mercenary/rag/RagPartService.java`

```java
/**
 * RAGPart Defense Implementation
 *
 * Two-stage defense:
 * 1. Document Partitioning: Fragment documents, embed separately, average combinations
 * 2. Majority Vote Aggregation: Retrieve from multiple fragment combinations
 *
 * Key insight: Poison tokens lose disproportionate influence when embeddings are averaged
 */
@Service
public class RagPartService {

    private static final int PARTITION_COUNT = 5;      // N fragments per document
    private static final int COMBINATION_SIZE = 3;     // k fragments to average
    private static final double ANOMALY_THRESHOLD = 2.5; // Standard deviations

    /**
     * Validate retrieved documents against poisoning attacks
     *
     * @param query Original query
     * @param candidates Raw retrieval results
     * @return Sanitized results with poison documents filtered
     */
    public List<Document> validate(String query, List<Document> candidates) {
        // Stage 1: Partition-based validation
        List<Document> partitionValidated = validateByPartitioning(candidates);

        // Stage 2: Embedding anomaly detection
        List<Document> anomalyFiltered = filterEmbeddingAnomalies(partitionValidated);

        // Stage 3: Majority vote aggregation
        return aggregateByMajorityVote(query, anomalyFiltered);
    }

    /**
     * RAGMask: Token-level sanitization for suspicious documents
     * Masks segments and checks if similarity increases (indicates poison tokens)
     */
    public Document sanitizeDocument(String query, Document doc) {
        // Implementation per RAGMask algorithm
        // If masked version has higher similarity than original, keep masked tokens
    }
}
```

**Integration Point**: `MercenaryController.java` or retrieval service layer

```java
// BEFORE (vulnerable)
List<Document> results = vectorStore.similaritySearch(query, k);

// AFTER (defended)
List<Document> rawResults = vectorStore.similaritySearch(query, k * 2); // Over-retrieve
List<Document> results = ragPartService.validate(query, rawResults);
```

#### Configuration

```yaml
# application.yml
sentinel:
  security:
    ragpart:
      enabled: true
      partition-count: 5
      combination-size: 3
      anomaly-threshold: 2.5
      # For government edition: stricter thresholds
      govcloud:
        anomaly-threshold: 2.0
        require-majority-consensus: true
```

#### Expected Impact

| Attack Type | Before | After |
|-------------|--------|-------|
| HotFlip | 87% success | 0-2% success |
| Query-as-Poison | 78% success | 1-3% success |
| AdvRAGgen | 91% success | 6-10% success |

#### Limitations

RAGPart cannot defend against **semantically coherent misinformation** (documents that are legitimately similar but factually wrong). This requires the Hallucination Detection Layer (Section 5).

---

## Part II: Quality & Routing

### 2. Activate Agentic Routing (Graph-O1 Logic)

**Research Basis**: Graph-O1 (arXiv:2512.17912)

#### Current Gap

`AdaptiveRagService` uses simple regex patterns (`^what is`, `^how do`) to route queries. This fails on:
- Complex analytical questions
- Multi-hop reasoning requirements
- Ambiguous query intent

```java
// Current naive routing (AdaptiveRagService.java)
if (query.matches("^what is.*")) {
    return RouteType.SIMPLE_LOOKUP;
} else if (query.matches(".*compare.*")) {
    return RouteType.MULTI_HOP;
}
// Misroutes nuanced queries like "Explain the implications of X on Y"
```

#### Solution: Wire AgenticRagOrchestrator

The existing `AgenticRagOrchestrator` implements Graph-O1 principles:
- **MCTS-guided planning**: Explores multiple retrieval paths
- **Self-correction**: Backtracks from dead ends
- **MDP formulation**: Models query answering as sequential decisions

#### Implementation

**Step 1**: Enable agentic mode

```yaml
# application.yml
sentinel:
  agentic:
    enabled: true
    search-depth: 8          # Optimal per Graph-O1 ablation
    search-width: 3          # Balance accuracy vs. compute
    ucb-exploration: 1.41    # sqrt(2) - standard UCB constant
```

**Step 2**: Update controller routing

```java
// MercenaryController.java
@PostMapping("/ask")
public ResponseEntity<AskResponse> askEnhanced(@RequestBody AskRequest request) {

    if (agenticProperties.isEnabled()) {
        // Use MCTS-guided orchestrator for complex queries
        AgenticResult result = agenticRagOrchestrator.process(
            request.getQuery(),
            request.getSector(),
            AgenticConfig.builder()
                .maxDepth(agenticProperties.getSearchDepth())
                .width(agenticProperties.getSearchWidth())
                .build()
        );
        return ResponseEntity.ok(mapToResponse(result));
    }

    // Fallback to adaptive routing for simple queries
    return adaptiveRagService.process(request);
}
```

**Step 3**: Implement query complexity classifier

```java
/**
 * Classify query complexity to determine routing strategy
 * Based on Graph-O1's observation that simple queries don't need MCTS overhead
 */
@Service
public class QueryComplexityClassifier {

    public enum Complexity { SIMPLE, MODERATE, COMPLEX }

    public Complexity classify(String query) {
        int hopEstimate = estimateRequiredHops(query);
        boolean hasTemporalReasoning = detectTemporalMarkers(query);
        boolean requiresComparison = detectComparisonIntent(query);

        if (hopEstimate <= 1 && !hasTemporalReasoning && !requiresComparison) {
            return Complexity.SIMPLE;  // Direct retrieval sufficient
        } else if (hopEstimate <= 3) {
            return Complexity.MODERATE; // Standard multi-hop
        } else {
            return Complexity.COMPLEX;  // Full MCTS exploration
        }
    }
}
```

#### Graph Functions (per Graph-O1)

Implement four core graph operations for the orchestrator:

| Function | Purpose | Implementation |
|----------|---------|----------------|
| `RetrieveNode(text)` | Find semantically related nodes | Vector similarity search |
| `NodeFeature(id, feature)` | Extract specific text attributes | Document field extraction |
| `NeighborCheck(id, type)` | Get neighbors by relation type | Graph traversal |
| `NodeDegree(id, type)` | Count neighbors (importance) | Degree centrality |

#### Reasoning Trace for Auditability

```java
/**
 * Explicit reasoning trace for government audit requirements
 * Format: Thought → Action → Observation (per Graph-O1)
 */
public class ReasoningTrace {
    private List<ReasoningStep> steps;

    @Data
    public static class ReasoningStep {
        private String thought;      // "I need to find documents about X"
        private String action;       // "RetrieveNode('X')"
        private String observation;  // "Found 3 relevant documents"
        private Instant timestamp;
    }

    public String toAuditLog() {
        // Format for STIG-compliant audit logging
    }
}
```

---

## Part III: Multimodal Capabilities

### 3. Activate Multimodal Ingestion (UniversalRAG + MegaRAG)

**Research Basis**: UniversalRAG (arXiv:2504.20734), Affordance RAG (arXiv:2512.18987)

#### Current Gap

`SecureIngestionService` treats all inputs as text/PDF streams, completely ignoring:
- Embedded images in documents
- Charts and diagrams
- Standalone visual intelligence assets

```java
// Current SecureIngestionService.java - text-only
public void ingest(InputStream content, String filename, IngestMetadata metadata) {
    // Magic byte detection for security
    String detected = tikaMimeDetector.detect(content);

    // But then... only text extraction
    String text = textExtractor.extract(content);  // Images discarded!
    vectorStore.add(chunk(text), metadata);
}
```

#### The Modality Gap Problem

UniversalRAG demonstrates that forcing all modalities into a unified embedding space causes **modality bias** - text queries cluster with text regardless of relevance:

```
Unified Embedding Retrieval:
- Query: "Show troop movements" (needs map image)
- Result: Text documents about "troop movements" (wrong modality)
- Reason: Text embeddings cluster together, ignoring image corpus
```

#### Solution: Modality-Aware Routing

**Architecture**:

```
                    ┌─────────────────┐
                    │  Query Router   │
                    │  (Classifier)   │
                    └────────┬────────┘
                             │
        ┌────────────────────┼────────────────────┐
        ▼                    ▼                    ▼
┌───────────────┐   ┌───────────────┐   ┌───────────────┐
│ Text Corpus   │   │ Image Corpus  │   │ Table Corpus  │
│ (Qwen3-Embed) │   │ (VLM2Vec-V2)  │   │ (Row-Level)   │
└───────────────┘   └───────────────┘   └───────────────┘
        │                    │                    │
        └────────────────────┼────────────────────┘
                             ▼
                    ┌─────────────────┐
                    │  Cross-Modal    │
                    │  Fusion Layer   │
                    └─────────────────┘
```

#### Implementation

**Step 1**: Update ingestion to detect and route by modality

```java
// SecureIngestionService.java - Enhanced
@Service
public class SecureIngestionService {

    @Autowired private MegaRagService megaRagService;
    @Autowired private TextIngestionService textIngestionService;
    @Autowired private TableIngestionService tableIngestionService;

    public IngestResult ingest(InputStream content, String filename, IngestMetadata metadata) {
        // Security: Magic byte detection (existing)
        ContentType detected = tikaMimeDetector.detectWithMagicBytes(content);
        validateNotExecutable(detected);

        // NEW: Route by modality
        switch (detected.getModalityType()) {
            case TEXT:
            case PDF:
                return ingestTextDocument(content, metadata);

            case IMAGE:
                return megaRagService.ingestVisualAsset(content, metadata);

            case SPREADSHEET:
            case CSV:
                return tableIngestionService.ingestTable(content, metadata);

            case COMPOUND_DOCUMENT:
                // PDF with embedded images - extract both
                return ingestCompoundDocument(content, metadata);

            default:
                throw new UnsupportedModalityException(detected);
        }
    }

    /**
     * Handle documents with mixed content (text + images)
     * Per UniversalRAG: Store in BOTH text and image corpora
     */
    private IngestResult ingestCompoundDocument(InputStream content, IngestMetadata metadata) {
        CompoundContent extracted = compoundExtractor.extract(content);

        // Ingest text portions
        IngestResult textResult = textIngestionService.ingest(
            extracted.getTextContent(),
            metadata.withModality(Modality.TEXT)
        );

        // Ingest each embedded image
        List<IngestResult> imageResults = extracted.getImages().stream()
            .map(img -> megaRagService.ingestVisualAsset(img, metadata.withModality(Modality.IMAGE)))
            .collect(toList());

        // Link text and images for cross-modal retrieval
        crossModalLinker.link(textResult, imageResults, extracted.getImageReferences());

        return IngestResult.compound(textResult, imageResults);
    }
}
```

**Step 2**: Implement query modality router

```java
/**
 * Modality-Aware Query Router
 * Based on UniversalRAG: Route to appropriate corpus BEFORE retrieval
 */
@Service
public class ModalityRouter {

    public enum ModalityTarget {
        NONE,           // No retrieval needed (parametric knowledge sufficient)
        TEXT_PARAGRAPH, // Simple factual lookup
        TEXT_DOCUMENT,  // Multi-hop reasoning across documents
        TABLE,          // Structured/numerical queries
        IMAGE,          // Visual appearance, spatial relationships
        CROSS_MODAL     // Requires evidence from multiple modalities
    }

    /**
     * Route query to appropriate corpus(es)
     * Returns multiple targets for cross-modal queries
     */
    public Set<ModalityTarget> route(String query, QueryContext context) {
        // Option 1: Training-free routing (prompt frontier model)
        if (routerConfig.useTrainingFreeRouter()) {
            return promptBasedRouting(query);
        }

        // Option 2: Trained classifier (higher accuracy, lower latency)
        return classifierBasedRouting(query);
    }

    private Set<ModalityTarget> classifierBasedRouting(String query) {
        // Lightweight classifier (per UniversalRAG: even 1B params achieves 90% accuracy)
        float[] scores = routerClassifier.predict(query);

        Set<ModalityTarget> targets = new HashSet<>();
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] > ROUTING_THRESHOLD) {
                targets.add(ModalityTarget.values()[i]);
            }
        }

        return targets.isEmpty() ? Set.of(ModalityTarget.TEXT_PARAGRAPH) : targets;
    }
}
```

**Step 3**: Modality-specific encoders

```yaml
# application.yml
sentinel:
  retrieval:
    modality-routing:
      enabled: true
      routing-threshold: 0.8

    encoders:
      text: "Qwen3-Embedding-4B"      # Best for text
      image: "VLM2Vec-V2"             # Best for vision
      table: "row-level-embedding"    # Dense row embedding

    corpora:
      text-paragraph:
        chunk-size: 100
        index: "sentinel-text-paragraph"
      text-document:
        chunk-size: 4096
        index: "sentinel-text-document"
      image:
        index: "sentinel-images"
      table:
        index: "sentinel-tables"
```

#### Cross-Modal Retrieval

For queries requiring multiple modalities (e.g., "What does the chart in the Q3 report show about revenue?"):

```java
/**
 * Cross-Modal Fusion Layer
 * Combines evidence from multiple modality-specific retrievals
 */
@Service
public class CrossModalFusionService {

    public List<Evidence> retrieve(String query, Set<ModalityTarget> targets) {
        // Parallel retrieval from each target corpus
        Map<ModalityTarget, CompletableFuture<List<Evidence>>> futures = targets.stream()
            .collect(toMap(
                target -> target,
                target -> CompletableFuture.supplyAsync(() ->
                    retrieveFromCorpus(query, target))
            ));

        // Wait for all retrievals
        Map<ModalityTarget, List<Evidence>> results = futures.entrySet().stream()
            .collect(toMap(Map.Entry::getKey, e -> e.getValue().join()));

        // Fuse results using RRF (Reciprocal Rank Fusion)
        return reciprocalRankFusion(results);
    }

    /**
     * RRF fusion - operates on ranks, not scores
     * Robust to different score calibrations across modalities
     */
    private List<Evidence> reciprocalRankFusion(Map<ModalityTarget, List<Evidence>> results) {
        Map<String, Double> rrfScores = new HashMap<>();

        for (List<Evidence> evidenceList : results.values()) {
            for (int rank = 0; rank < evidenceList.size(); rank++) {
                String id = evidenceList.get(rank).getId();
                double score = 1.0 / (RRF_K + rank + 1);  // RRF_K = 60
                rrfScores.merge(id, score, Double::sum);
            }
        }

        // Return sorted by RRF score
        return rrfScores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .map(e -> lookupEvidence(e.getKey()))
            .collect(toList());
    }
}
```

---

## Part IV: Context & Structure

### 4. Activate Hierarchical Context (MiA-RAG Mindscape + MedGraphRAG)

**Research Basis**: MedGraphRAG (arXiv:2506.02741), UniversalRAG granularity

#### Current Gap

Documents are indexed chunk-by-chunk without preserving:
- Document-level context ("What is this document about?")
- Structural hierarchy (sections, headers, relationships)
- Entity-level precision retrieval

This causes "Lost in the Middle" errors on broad questions like "Summarize the evolving threat landscape across these 50 reports."

#### Solution: Three-Tier Hierarchical Representation

```
┌─────────────────────────────────────────────────────────┐
│                    DOCUMENT LEVEL                        │
│  • Full document summary                                 │
│  • Metadata (date, source, classification)              │
│  • Key entities mentioned                                │
└─────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────┐
│                     CHUNK LEVEL                          │
│  • Paragraph/section embeddings                          │
│  • Preserved section headers                             │
│  • Links to parent document                              │
└─────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────┐
│                    ENTITY LEVEL                          │
│  • Named entities (people, orgs, locations)             │
│  • Entity relationships                                  │
│  • Co-occurrence statistics                              │
└─────────────────────────────────────────────────────────┘
```

#### Implementation

**Step 1**: Mindscape generation during ingestion

```java
// SecureIngestionService.java - Add mindscape building
@Autowired private MiARagService miARagService;

public IngestResult ingest(InputStream content, String filename, IngestMetadata metadata) {
    // ... existing ingestion logic ...

    // NEW: Build mindscape for document-level understanding
    if (mindscapeConfig.isEnabled()) {
        Mindscape mindscape = miARagService.buildMindscape(
            extractedContent,
            MindscapeConfig.builder()
                .extractEntities(true)
                .buildRelationships(true)
                .generateSummary(true)
                .build()
        );

        // Store mindscape alongside chunks
        mindscapeStore.save(documentId, mindscape);

        // Index document-level summary for broad queries
        documentLevelIndex.add(mindscape.getSummary(), metadata);
    }

    return result;
}
```

**Step 2**: U-Retrieve algorithm (top-down + bottom-up)

```java
/**
 * U-Retrieve: Hierarchical retrieval combining both directions
 * Based on MedGraphRAG
 */
@Service
public class HierarchicalRetrievalService {

    public List<Evidence> uRetrieve(String query, RetrievalConfig config) {
        // Top-down: Start from document summaries, drill into relevant chunks
        List<Evidence> topDown = topDownRetrieval(query, config);

        // Bottom-up: Start from entity matches, expand to containing chunks
        List<Evidence> bottomUp = bottomUpRetrieval(query, config);

        // Merge with configurable weighting
        return mergeResults(topDown, bottomUp, config.getTopDownWeight());
    }

    /**
     * Top-down: Document → Section → Paragraph
     * Good for broad analytical questions
     */
    private List<Evidence> topDownRetrieval(String query, RetrievalConfig config) {
        // Find relevant documents by summary
        List<DocumentSummary> relevantDocs = documentLevelIndex.search(query, config.getDocLimit());

        // For each relevant document, find best chunks
        return relevantDocs.stream()
            .flatMap(doc -> chunkIndex.searchWithinDocument(query, doc.getId(), config.getChunkLimit()).stream())
            .collect(toList());
    }

    /**
     * Bottom-up: Entity → Chunk → Document
     * Good for specific entity queries
     */
    private List<Evidence> bottomUpRetrieval(String query, RetrievalConfig config) {
        // Extract entities from query
        List<String> queryEntities = entityExtractor.extract(query);

        // Find chunks containing these entities
        List<Evidence> entityMatches = queryEntities.stream()
            .flatMap(entity -> entityIndex.findChunksContaining(entity).stream())
            .distinct()
            .collect(toList());

        // Expand to neighboring chunks for context
        return expandContext(entityMatches, config.getContextWindow());
    }
}
```

**Step 3**: Dual-granularity storage

```java
/**
 * Store both paragraph and document granularities
 * Per UniversalRAG: Different queries need different granularities
 */
@Service
public class DualGranularityIngestionService {

    public void ingest(Document document, IngestMetadata metadata) {
        // Granularity 1: Paragraphs (~100 tokens)
        // Good for: Simple factual lookups
        List<Chunk> paragraphs = paragraphChunker.chunk(document, 100);
        paragraphIndex.addAll(paragraphs, metadata);

        // Granularity 2: Sections/Documents (~4000 tokens)
        // Good for: Multi-hop reasoning, broad context
        List<Chunk> sections = sectionChunker.chunk(document, 4000);
        documentIndex.addAll(sections, metadata);

        // Link paragraphs to their parent sections
        linkChunks(paragraphs, sections);
    }
}
```

**Step 4**: Granularity-aware query routing

```java
/**
 * Select appropriate granularity based on query complexity
 */
public GranularityTarget selectGranularity(String query) {
    QueryComplexity complexity = complexityClassifier.classify(query);

    switch (complexity) {
        case SIMPLE:
            // "What is the capital of France?" → Paragraph sufficient
            return GranularityTarget.PARAGRAPH;

        case MULTI_HOP:
            // "Which events led to X?" → Need document context
            return GranularityTarget.DOCUMENT;

        case SYNTHESIS:
            // "Summarize threat landscape" → Need mindscape/summary level
            return GranularityTarget.MINDSCAPE;

        default:
            return GranularityTarget.PARAGRAPH;
    }
}
```

---

## Part V: Trust & Verification

### 4. Activate Hallucination Detection (QuCo-RAG) ⚡ QUICK WIN

**Research Basis**: QuCo-RAG (arXiv:2512.22442), Bidirectional RAG (arXiv:2512.22199), HiFi-RAG (arXiv:2512.24268)

> **⚡ QUICK WIN ALERT**: Per Gemini's code audit, `QuCoRagService` is **already fully implemented** (216 lines) and injected into `MercenaryController`. It currently operates in **PASSIVE MODE** - running hallucination checks but only logging warnings. This section focuses on **activation**, not implementation.

#### Current State (PASSIVE MODE)

**File**: `src/main/java/com/jreinhal/mercenary/rag/qucorag/QuCoRagService.java`

The service already implements:
- ✅ Pre-generation uncertainty analysis (`analyzeQueryUncertainty`)
- ✅ Runtime hallucination detection (`detectHallucinationRisk`)
- ✅ Entity frequency checking (InfiniGram + local corpus fallback)
- ✅ Co-occurrence verification for novel entities
- ✅ Configuration flags (`sentinel.qucorag.enabled`, `sentinel.qucorag.uncertainty-threshold`)

**Current Behavior** (MercenaryController.java lines 497-500, 728-732):
```java
// PASSIVE: Only logs warning, does not act
QuCoRagService.HallucinationResult hallucinationResult =
    this.quCoRagService.detectHallucinationRisk(response, query);
if (hallucinationResult.isHighRisk()) {
    log.warn("QuCo-RAG: High hallucination risk detected...");
    // ← Response is returned anyway!
}
```

#### Activation Options

**Option A: Trigger Regeneration with Additional Retrieval**
```java
if (hallucinationResult.isHighRisk()) {
    log.warn("QuCo-RAG: High hallucination risk, triggering regeneration");
    // Retrieve additional context for flagged entities
    List<Document> additionalContext = retrieveForEntities(hallucinationResult.flaggedEntities());
    // Regenerate with expanded context
    response = regenerateResponse(query, mergeContext(originalContext, additionalContext));
}
```

**Option B: Force Abstention**
```java
if (hallucinationResult.isHighRisk()) {
    log.warn("QuCo-RAG: High hallucination risk, forcing abstention");
    response = buildAbstentionResponse(query, hallucinationResult);
}
```

**Option C: Hybrid (Regenerate once, then abstain)**
```java
if (hallucinationResult.isHighRisk() && !isRegenerationAttempt) {
    // Try regeneration first
    response = regenerateWithMoreContext(query, hallucinationResult);
    HallucinationResult recheck = quCoRagService.detectHallucinationRisk(response, query);
    if (recheck.isHighRisk()) {
        // Still risky after regeneration - abstain
        response = buildAbstentionResponse(query, recheck);
    }
}
```

#### Implementation (Minimal Changes Required)

**Step 1**: Update MercenaryController to act on hallucination detection

```java
// MercenaryController.java - Replace passive logging with action

// Around line 497-500 and 728-732
QuCoRagService.HallucinationResult hallucinationResult =
    this.quCoRagService.detectHallucinationRisk(response, query);

if (hallucinationResult.isHighRisk()) {
    log.warn("QuCo-RAG: High hallucination risk (risk={:.3f}), flagged: {}",
             hallucinationResult.riskScore(), hallucinationResult.flaggedEntities());

    if (qucoragProperties.getActionMode() == ActionMode.REGENERATE) {
        // Option A: Regenerate
        response = handleHallucinationWithRegeneration(query, response, hallucinationResult, context);
    } else if (qucoragProperties.getActionMode() == ActionMode.ABSTAIN) {
        // Option B: Abstain
        response = buildAbstentionResponse(query, hallucinationResult);
    } else {
        // Option C: Hybrid
        response = handleHallucinationHybrid(query, response, hallucinationResult, context);
    }
}
```

**Step 2**: Add abstention response builder

```java
private String buildAbstentionResponse(String query, HallucinationResult result) {
    StringBuilder sb = new StringBuilder();
    sb.append("I cannot provide a reliable answer to this question based on the available evidence.\n\n");
    sb.append("**Reason**: The response contained claims about entities (");
    sb.append(String.join(", ", result.flaggedEntities().stream().limit(3).toList()));
    sb.append(") that could not be verified against the document corpus.\n\n");
    sb.append("**Recommendation**: Please rephrase your question or provide additional context.");
    return sb.toString();
}
```

**Step 3**: Add configuration for action mode

```yaml
# application.yml
sentinel:
  qucorag:
    enabled: true
    uncertainty-threshold: 0.7
    low-frequency-threshold: 1000
    # NEW: Action mode when hallucination detected
    action-mode: HYBRID  # Options: LOG_ONLY, REGENERATE, ABSTAIN, HYBRID
    max-regeneration-attempts: 1

    # Government edition: stricter
    govcloud:
      action-mode: ABSTAIN  # Always abstain on high risk
      uncertainty-threshold: 0.5  # Lower threshold = more cautious
```

#### The Problem (For Reference)

LLMs exhibit "confident hallucinations" - high confidence on factually incorrect answers. Model-internal signals (logits, entropy) are poorly calibrated. This is **unacceptable** for government/medical contexts.

#### The QuCo-RAG Insight (Already Implemented)

Use **objective corpus statistics** instead of model confidence.

```
Traditional (Unreliable):
  Model says "X relates to Y" with 95% confidence → Trust it? NO

QuCo-RAG (Reliable):
  Check corpus: Do X and Y ever co-occur in training data?
  - Yes (>0 co-occurrences) → Some evidential basis
  - No (zero co-occurrence) → HIGH HALLUCINATION RISK
```

#### Implementation

**Stage 1: Pre-Generation Entity Frequency Check**

```java
/**
 * Before generating, check if query entities are well-represented in corpus
 * Low frequency entities = high uncertainty = retrieve first
 */
@Service
public class PreGenerationUncertaintyService {

    private static final long LOW_FREQUENCY_THRESHOLD = 1000;

    public UncertaintyAssessment assessPreGeneration(String query) {
        // Extract entities from query
        List<Entity> entities = entityExtractor.extract(query);

        // Check frequency of each entity in corpus
        Map<Entity, Long> frequencies = entities.stream()
            .collect(toMap(
                e -> e,
                e -> corpusStatistics.getEntityFrequency(e.getText())
            ));

        // Flag low-frequency entities
        List<Entity> lowFrequency = frequencies.entrySet().stream()
            .filter(e -> e.getValue() < LOW_FREQUENCY_THRESHOLD)
            .map(Map.Entry::getKey)
            .collect(toList());

        if (!lowFrequency.isEmpty()) {
            return UncertaintyAssessment.builder()
                .level(UncertaintyLevel.HIGH)
                .reason("Low corpus coverage for entities: " + lowFrequency)
                .recommendation(Recommendation.RETRIEVE_BEFORE_GENERATE)
                .build();
        }

        return UncertaintyAssessment.low();
    }
}
```

**Stage 2: Runtime Co-occurrence Verification**

```java
/**
 * After generation, verify entity relationships have corpus support
 * Zero co-occurrence = hallucination risk
 */
@Service
public class RuntimeVerificationService {

    private static final int COOCCURRENCE_WINDOW = 1000; // tokens

    public VerificationResult verifyGeneration(String generatedResponse, List<Evidence> evidence) {
        // Extract entity pairs (triplets) from generated response
        List<EntityPair> claims = tripletExtractor.extract(generatedResponse);

        List<UnverifiedClaim> unverified = new ArrayList<>();

        for (EntityPair pair : claims) {
            // Check if this entity pair ever co-occurs in corpus
            long cooccurrences = corpusStatistics.getCooccurrence(
                pair.getEntity1(),
                pair.getEntity2(),
                COOCCURRENCE_WINDOW
            );

            if (cooccurrences == 0) {
                // ZERO co-occurrence = no evidential basis for this claim
                unverified.add(UnverifiedClaim.builder()
                    .claim(pair)
                    .reason("Entities never co-occur in corpus within " + COOCCURRENCE_WINDOW + " tokens")
                    .risk(HallucinationRisk.HIGH)
                    .build());
            }
        }

        return VerificationResult.builder()
            .verified(unverified.isEmpty())
            .unverifiedClaims(unverified)
            .confidenceScore(calculateConfidence(claims.size(), unverified.size()))
            .build();
    }
}
```

**Stage 3: NLI-Based Grounding Check (per Bidirectional RAG)**

```java
/**
 * Verify generated response is entailed by retrieved evidence
 * Uses Natural Language Inference (NLI) model
 */
@Service
public class NLIGroundingService {

    private static final double ENTAILMENT_THRESHOLD = 0.65;

    @Autowired private NLIModel nliModel; // DeBERTa-v3

    public GroundingResult checkGrounding(String response, List<Evidence> evidence) {
        // Concatenate evidence
        String evidenceText = evidence.stream()
            .map(Evidence::getContent)
            .collect(joining("\n"));

        // Check entailment: Is response entailed by evidence?
        NLIResult nliResult = nliModel.predict(evidenceText, response);

        if (nliResult.getEntailmentScore() < ENTAILMENT_THRESHOLD) {
            return GroundingResult.builder()
                .grounded(false)
                .score(nliResult.getEntailmentScore())
                .recommendation("Response contains claims not supported by evidence")
                .build();
        }

        return GroundingResult.grounded(nliResult.getEntailmentScore());
    }
}
```

**Stage 4: Explicit Abstention**

```java
/**
 * Force abstention when evidence is insufficient
 * Per Hybrid RAG: "I cannot answer based solely on the provided information"
 */
@Service
public class AbstentionService {

    public GenerationResult generateWithAbstention(
            String query,
            List<Evidence> evidence,
            VerificationResult verification,
            GroundingResult grounding) {

        // Check if we should abstain
        if (shouldAbstain(evidence, verification, grounding)) {
            return GenerationResult.abstention(
                "I cannot answer this question based solely on the available evidence. " +
                "The retrieved documents do not contain sufficient information to provide " +
                "a reliable response.",
                AbstentionReason.builder()
                    .evidenceGaps(identifyGaps(query, evidence))
                    .verificationIssues(verification.getUnverifiedClaims())
                    .groundingScore(grounding.getScore())
                    .build()
            );
        }

        // Proceed with generation
        return generateResponse(query, evidence);
    }

    private boolean shouldAbstain(List<Evidence> evidence,
                                   VerificationResult verification,
                                   GroundingResult grounding) {
        // Abstain if:
        // 1. No relevant evidence found
        if (evidence.isEmpty()) return true;

        // 2. Evidence relevance below threshold
        if (averageRelevance(evidence) < RELEVANCE_THRESHOLD) return true;

        // 3. High proportion of unverifiable claims
        if (verification.getUnverifiedRatio() > UNVERIFIED_THRESHOLD) return true;

        // 4. Poor grounding score
        if (grounding.getScore() < GROUNDING_THRESHOLD) return true;

        return false;
    }
}
```

#### Integration: End-to-End Pipeline

```java
/**
 * Complete RAG pipeline with hallucination detection
 */
@Service
public class TrustedRagPipeline {

    public TrustedResponse process(String query, QueryContext context) {
        // Step 1: Pre-generation uncertainty check
        UncertaintyAssessment preCheck = preGenerationService.assess(query);

        // Step 2: Retrieval (with RAGPart defense)
        List<Evidence> evidence = retrievalService.retrieve(query, context);
        evidence = ragPartService.validate(query, evidence);  // Defense

        // Step 3: Generation
        String response = generationService.generate(query, evidence);

        // Step 4: Runtime verification
        VerificationResult verification = runtimeVerificationService.verify(response, evidence);

        // Step 5: Grounding check
        GroundingResult grounding = nliGroundingService.check(response, evidence);

        // Step 6: Decide: return response or abstain
        if (shouldAbstain(verification, grounding)) {
            return TrustedResponse.abstention(query, evidence);
        }

        // Step 7: Attach confidence metadata
        return TrustedResponse.builder()
            .response(response)
            .evidence(evidence)
            .confidence(calculateOverallConfidence(verification, grounding))
            .verificationDetails(verification)
            .groundingDetails(grounding)
            .build();
    }
}
```

#### Corpus Statistics Infrastructure

```java
/**
 * Build and maintain corpus statistics for hallucination detection
 * Per QuCo-RAG: Uses n-gram index for millisecond-latency queries
 */
@Service
public class CorpusStatisticsService {

    private final InfinigramIndex ngramIndex;  // Or equivalent

    /**
     * Called during document ingestion to update statistics
     */
    public void updateStatistics(Document document) {
        // Extract entities
        List<Entity> entities = entityExtractor.extract(document.getContent());

        // Update entity frequency counts
        for (Entity entity : entities) {
            entityFrequencyStore.increment(entity.getNormalizedText());
        }

        // Update co-occurrence matrix
        for (int i = 0; i < entities.size(); i++) {
            for (int j = i + 1; j < entities.size(); j++) {
                int distance = entities.get(j).getPosition() - entities.get(i).getPosition();
                if (distance <= COOCCURRENCE_WINDOW) {
                    cooccurrenceStore.increment(
                        entities.get(i).getNormalizedText(),
                        entities.get(j).getNormalizedText()
                    );
                }
            }
        }
    }

    public long getEntityFrequency(String entity) {
        return entityFrequencyStore.get(normalize(entity));
    }

    public long getCooccurrence(String entity1, String entity2, int window) {
        return cooccurrenceStore.get(normalize(entity1), normalize(entity2));
    }
}
```

---

## Part VI: Supporting Enhancements

### 6. Model Cascade for Cost Efficiency (HiFi-RAG)

**Research Basis**: HiFi-RAG (arXiv:2512.24268)

Use cheap models for filtering, expensive models only for final generation:

```java
/**
 * Model cascade: Flash (cheap) → Pro (expensive)
 * Per HiFi-RAG: LLM filtering > embedding filtering for relevance
 */
@Service
public class ModelCascadeService {

    @Autowired private LLMService flashModel;  // Fast, cheap (e.g., Ollama 7B)
    @Autowired private LLMService proModel;    // Powerful (e.g., Ollama 70B)

    public GenerationResult generateWithCascade(String query, List<Evidence> evidence) {
        // Stage 1: Use flash model to filter chunks (LLM-as-Reranker)
        List<Evidence> filtered = flashModel.rerankByRelevance(query, evidence);

        // Stage 2: Use pro model for final generation
        String response = proModel.generate(query, filtered);

        return GenerationResult.of(response, filtered);
    }
}
```

### 7. Decoupled Citation Verification (HiFi-RAG)

Don't ask the LLM to cite while generating - do it separately:

```java
/**
 * Separate citation from generation to prevent quality degradation
 */
@Service
public class DecoupledCitationService {

    public CitedResponse addCitations(String response, List<Evidence> evidence) {
        // Step 1: Generate response without citations (better quality)
        // (Already done by generation service)

        // Step 2: Post-hoc citation matching
        List<Sentence> sentences = sentenceTokenizer.tokenize(response);

        List<CitedSentence> cited = sentences.stream()
            .map(sentence -> {
                Evidence bestMatch = findBestEvidenceMatch(sentence, evidence);
                return CitedSentence.of(sentence, bestMatch);
            })
            .collect(toList());

        return CitedResponse.builder()
            .sentences(cited)
            .build();
    }
}
```

### 8. Temporal Decay Scoring (TV-RAG)

For intelligence documents, weight recent documents higher:

```java
/**
 * Time-decay scoring for temporal relevance
 * Per TV-RAG: score × e^(-λ × |t_query - t_document|)
 */
public double applyTemporalDecay(double semanticScore, Instant documentDate, Instant queryDate) {
    long daysDiff = ChronoUnit.DAYS.between(documentDate, queryDate);
    double decay = Math.exp(-LAMBDA * daysDiff);  // LAMBDA ≈ 0.01
    return semanticScore * decay;
}
```

### 9. Validated Write-Back (Bidirectional RAG)

Allow corpus to learn from analyst interactions:

```java
/**
 * Write validated responses back to corpus
 * Per Bidirectional RAG: Strict acceptance criteria to prevent pollution
 */
@Service
public class BidirectionalCorpusService {

    private static final double GROUNDING_THRESHOLD = 0.65;
    private static final double NOVELTY_THRESHOLD = 0.10;
    private static final double MAX_AI_CONTENT_RATIO = 0.20;

    public WriteBackResult considerForCorpus(
            String response,
            List<Evidence> sources,
            VerificationResult verification,
            GroundingResult grounding) {

        // Gate 1: Must be well-grounded
        if (grounding.getScore() < GROUNDING_THRESHOLD) {
            return WriteBackResult.rejected("Insufficient grounding");
        }

        // Gate 2: Must be novel (not duplicate)
        double novelty = calculateNovelty(response, sources);
        if (novelty < NOVELTY_THRESHOLD) {
            return WriteBackResult.rejected("Too similar to existing content");
        }

        // Gate 3: AI content ratio limit
        if (getAIContentRatio() >= MAX_AI_CONTENT_RATIO) {
            return WriteBackResult.rejected("AI content ratio exceeded");
        }

        // Gate 4: Full verification passed
        if (!verification.isFullyVerified()) {
            return WriteBackResult.rejected("Unverified claims present");
        }

        // All gates passed - add to corpus
        Document newDoc = Document.builder()
            .content(response)
            .sources(sources)
            .generationType(GenerationType.AI_VALIDATED)
            .verificationScore(verification.getScore())
            .build();

        corpusService.add(newDoc);

        return WriteBackResult.accepted(newDoc.getId());
    }
}
```

---

## Part VII: Configuration Reference

### Complete Application Configuration

```yaml
# application.yml - SENTINEL RAG Configuration

sentinel:
  # ===== SECURITY (Priority 1) =====
  security:
    ragpart:
      enabled: true
      partition-count: 5
      combination-size: 3
      anomaly-threshold: 2.5
      govcloud:
        anomaly-threshold: 2.0
        require-majority-consensus: true

  # ===== ROUTING (Priority 2) =====
  agentic:
    enabled: true
    search-depth: 8
    search-width: 3
    ucb-exploration: 1.41
    timeout-seconds: 30

  query-routing:
    complexity-classifier:
      enabled: true
      model: "distilbert-complexity"

  # ===== MULTIMODAL (Priority 3) =====
  multimodal:
    enabled: true
    routing-threshold: 0.8

    encoders:
      text: "Qwen3-Embedding-4B"
      image: "VLM2Vec-V2"
      table: "row-level-dense"

    ingestion:
      extract-images-from-pdf: true
      generate-image-captions: true

  # ===== HIERARCHICAL (Priority 4) =====
  hierarchy:
    mindscape:
      enabled: true
      generate-summary: true
      extract-entities: true
      build-relationships: true

    granularity:
      paragraph-size: 100
      document-size: 4096
      store-both: true

    u-retrieve:
      enabled: true
      top-down-weight: 0.6
      bottom-up-weight: 0.4

  # ===== HALLUCINATION DETECTION (Priority 5) =====
  verification:
    pre-generation:
      enabled: true
      entity-frequency-threshold: 1000

    runtime:
      enabled: true
      cooccurrence-window: 1000
      triplet-extractor: "gpt4o-mini-distilled"

    grounding:
      enabled: true
      nli-model: "deberta-v3-large"
      entailment-threshold: 0.65

    abstention:
      enabled: true
      force-on-low-confidence: true
      relevance-threshold: 0.5
      unverified-claim-threshold: 0.3

  # ===== SUPPORTING FEATURES =====
  cascade:
    enabled: true
    filter-model: "ollama/llama3-8b"
    generate-model: "ollama/llama3-70b"

  citations:
    decoupled: true

  temporal:
    decay-enabled: true
    decay-lambda: 0.01

  bidirectional:
    write-back-enabled: false  # Enable after validation
    grounding-threshold: 0.65
    novelty-threshold: 0.10
    max-ai-content-ratio: 0.20

# Edition-specific overrides
---
spring:
  config:
    activate:
      on-profile: govcloud

sentinel:
  security:
    ragpart:
      anomaly-threshold: 2.0
      require-majority-consensus: true

  verification:
    abstention:
      force-on-low-confidence: true
      # Stricter thresholds for government
      relevance-threshold: 0.6
      unverified-claim-threshold: 0.2
      entailment-threshold: 0.75
```

---

## Part VIII: Implementation Roadmap

### Phase 1: Security Foundation (Week 1-2)

| Task | File | Effort |
|------|------|--------|
| Wire RagPartService into retrieval | `MercenaryController.java` | 2 days |
| Add partition-based validation | `RagPartService.java` | 3 days |
| Add embedding anomaly detection | `RagPartService.java` | 2 days |
| Integration tests | `RagPartServiceTest.java` | 2 days |
| Configuration & feature flag | `application.yml` | 1 day |

**Exit Criteria**: Corpus poisoning attacks reduced from 87% to <5% success rate

### Phase 2: Agentic Routing (Week 3-4)

| Task | File | Effort |
|------|------|--------|
| Enable agentic mode flag | `application.yml` | 0.5 days |
| Wire orchestrator to controller | `MercenaryController.java` | 1 day |
| Implement complexity classifier | `QueryComplexityClassifier.java` | 3 days |
| Add reasoning trace for audit | `ReasoningTrace.java` | 2 days |
| Performance tuning | - | 3 days |

**Exit Criteria**: Complex multi-hop queries show >15% accuracy improvement

### Phase 3: Multimodal Ingestion (Week 5-7)

| Task | File | Effort |
|------|------|--------|
| Add modality detection to ingestion | `SecureIngestionService.java` | 2 days |
| Implement compound document extraction | `CompoundExtractor.java` | 4 days |
| Wire MegaRagService for images | `SecureIngestionService.java` | 2 days |
| Implement modality router | `ModalityRouter.java` | 3 days |
| Cross-modal fusion (RRF) | `CrossModalFusionService.java` | 3 days |
| Integration tests | - | 3 days |

**Exit Criteria**: Images/charts in PDFs are searchable and retrievable

### Phase 4: Hallucination Detection Activation ⚡ QUICK WIN (Week 8-9)

> **⚡ Per Gemini Audit**: `QuCoRagService` already exists and is fully implemented. This phase activates existing code.

| Task | File | Effort |
|------|------|--------|
| Add action mode configuration | `application.yml` | 0.5 days |
| Implement abstention response builder | `MercenaryController.java` | 1 day |
| Wire action trigger (replace log.warn) | `MercenaryController.java` | 1 day |
| Implement regeneration handler | `MercenaryController.java` | 2 days |
| Add hybrid mode logic | `MercenaryController.java` | 1 day |
| Government edition stricter thresholds | `application-govcloud.yml` | 0.5 days |
| Testing & threshold calibration | - | 3 days |

**Exit Criteria**: High-risk hallucinations trigger regeneration or abstention instead of just logging

### Phase 5: Hierarchical Context (Week 10-12)

| Task | File | Effort |
|------|------|--------|
| Implement mindscape generation | `MiARagService.java` | 4 days |
| Dual-granularity storage | `DualGranularityIngestionService.java` | 3 days |
| U-Retrieve implementation | `HierarchicalRetrievalService.java` | 4 days |
| Entity extraction pipeline | `EntityExtractionService.java` | 3 days |
| Reindex existing corpus | Migration script | 3 days |

**Exit Criteria**: "Summarize across 50 reports" queries return coherent responses

### Phase 5b: Enhanced Hallucination Infrastructure (Week 13-14) [OPTIONAL]

> **Note**: These are enhancements beyond the existing QuCoRagService. Implement only if the quick-win activation proves insufficient.

| Task | File | Effort |
|------|------|--------|
| NLI grounding integration | `NLIGroundingService.java` | 3 days |
| Bidirectional write-back | `BidirectionalCorpusService.java` | 4 days |
| Enhanced corpus statistics | `CorpusStatisticsService.java` | 3 days |

**Exit Criteria**: Confident hallucinations reduced by >80%; explicit abstention on insufficient evidence

### Phase 6: Documentation Updates (Week 15-16)

| Task | File | Effort |
|------|------|--------|
| Update README with new capabilities | `README.md` | 2 days |
| Update User Manual - RAG features | `src/main/resources/static/manual.html` | 3 days |
| Update API documentation | `docs/customer/API.md` | 2 days |
| Add configuration guide | `docs/engineering/CONFIGURATION.md` | 2 days |
| Security documentation updates | `docs/customer/SECURITY.md` | 1 day |
| Release notes | `docs/engineering/CHANGELOG.md` | 1 day |

**Phase 6 Delivery Status (2026-02-12)**:
- Completed in incremental PRs:
  - `#96` Phase 6.1 README capability refresh
  - `#97` Phase 6.2 customer API reference refresh
  - `#98` Phase 6.3 configuration guide expansion
  - `#99` Phase 6.4 customer security documentation expansion
  - `#100` Phase 6.5 changelog update
  - `#101` Phase 6.6 in-app manual update
  - `#102` Phase 6.7 master-plan runtime/test hardening (`tools/run_e2e_profiles.ps1`, Mongo legacy sector conversion safeguards)
- Additional operator runbook updates were captured in `docs/engineering/E2E_TESTING.md` after Phase 6.7 merge.

**Documentation Update Checklist**:

#### README.md Updates
- [x] Add "RAG Capabilities" section describing new features
- [x] Update "Key Capabilities" with:
  - Corpus poisoning defense (RAGPart)
  - Agentic multi-hop reasoning (Graph-O1)
  - Multimodal retrieval (images, tables, text)
  - Hierarchical document understanding (Mindscape)
  - Hallucination detection & explicit abstention
- [x] Update architecture diagram showing new components (high-level text diagram in README)
- [x] Add configuration quick-start for new features
- [x] Update system requirements if any new dependencies

#### User Manual Updates
- [x] Chapter: "Understanding RAG in SENTINEL"
  - How retrieval-augmented generation works
  - Why SENTINEL uses multiple retrieval strategies
- [x] Chapter: "Working with Multimodal Documents"
  - Uploading documents with images/charts
  - How visual content is indexed and retrieved
  - Cross-modal query examples
- [x] Chapter: "Understanding Confidence Scores"
  - What confidence scores mean
  - When and why SENTINEL abstains from answering
  - How to interpret verification results
- [x] Chapter: "Security Features"
  - Corpus poisoning protection explained (user-friendly)
  - How SENTINEL validates retrieved information
- [x] Update screenshots for new UI elements (if any) - N/A for current documentation-only UI changes
- [x] Add troubleshooting section for new features

#### API Documentation Updates
- [x] Document new endpoints (if any)
- [x] Update `/ask` and `/ask/enhanced` response contract notes, including confidence/verification field mapping and unsupported dedicated fields (`groundingScore`, `abstentionReason`)
- [x] Document query-hint parameter status:
  - `modalityHint` - reserved compatibility placeholder (currently ignored)
  - `granularityHint` - reserved compatibility placeholder (currently ignored)
  - `requireVerification` - reserved compatibility placeholder (currently ignored)
- [x] Add examples for multimodal queries
- [x] Document configuration options via API

#### Configuration Guide Updates
- [ ] Complete reference for all new `sentinel.*` properties
- [x] Recommended configurations by edition:
  - Trial/Enterprise: Balanced defaults
  - Medical: Higher grounding thresholds
  - Government: Strictest verification, mandatory abstention
- [x] Performance tuning guide:
  - Search depth/width trade-offs
  - Cascade model selection
  - Index optimization
- [x] Troubleshooting common configuration issues

#### Security Documentation Updates
- [x] Add "Corpus Poisoning Defense" section
- [x] Document RAGPart configuration for security auditors
- [x] Add to NIST 800-53 control mapping:
  - SI-10: Information Input Validation (RAGPart)
  - SI-4: System Monitoring (Verification logging)
- [x] Update threat model with RAG-specific attacks
- [x] Add security testing procedures for new features

#### CHANGELOG.md Entry Template
```markdown
## [X.Y.Z] - YYYY-MM-DD

### Added
- **RAGPart Corpus Poisoning Defense**: Protection against malicious document injection
- **Agentic Routing**: MCTS-based multi-hop reasoning for complex queries
- **Multimodal Retrieval**: Support for images, charts, and tables in documents
- **Hierarchical Context**: Document-level mindscape for broad analytical queries
- **Hallucination Detection**: Corpus-grounded verification with explicit abstention

### Changed
- Query routing now uses complexity classifier instead of regex patterns
- Document ingestion extracts and indexes embedded images
- API responses include confidence scores and verification details

### Security
- Added defense against corpus poisoning attacks (87% → <5% success rate)
- Implemented NLI-based grounding verification for generated responses

### Configuration
- New `sentinel.security.ragpart.*` properties
- New `sentinel.agentic.*` properties
- New `sentinel.multimodal.*` properties
- New `sentinel.verification.*` properties
```

**Exit Criteria**: All documentation updated, reviewed, and published

---

## Part IX: Deferred Items

The following were analyzed but deemed non-critical for current priorities:

| Feature | Paper | Reason |
|---------|-------|--------|
| TV-RAG (Video) | arXiv:2512.22199 | No video corpus requirement |
| HGMem (Hypergraph) | - | Graph-O1 meets current needs |
| RAG+ (App-Aware) | - | Requires synthetic corpus generation |
| Hybrid Multilingual OCR | arXiv:2512.12694 | Current hybrid reranking sufficient |

---

## Appendix A: Research Paper Summary

| # | Paper | Key Innovation | SENTINEL Application |
|---|-------|---------------|---------------------|
| 1 | Agentic RAG Survey | Patterns taxonomy | Architecture guidance |
| 2 | Modular RAG | Component separation | Service decomposition |
| 3 | QuCo-RAG | Corpus-based uncertainty | Hallucination detection |
| 4 | HiFi-RAG | Model cascade + decoupled citations | Cost efficiency |
| 5 | Bidirectional RAG | Validated write-back | Corpus learning |
| 6 | TV-RAG | Temporal decay | Time-sensitive queries |
| 7 | MedGraphRAG | Hierarchical graph | Document structure |
| 8 | Affordance RAG | Multi-level fusion | Cross-modal retrieval |
| 9 | Graph-O1 | MCTS reasoning | Agentic orchestrator |
| 10 | Hybrid Multilingual | Abstention + robustness | Constrained generation |
| 11 | RAGPart/RAGMask | Corpus poisoning defense | Security foundation |
| 12 | UniversalRAG | Modality-aware routing | Multi-corpus retrieval |

---

## Appendix B: Security Considerations

### Corpus Poisoning Attack Vectors

| Attack | Method | RAGPart Defense |
|--------|--------|-----------------|
| HotFlip | Gradient-based token manipulation | Partition averaging dilutes poison |
| Query-as-Poison | Insert query text as document | Anomaly detection flags |
| AdvRAGgen | LLM-generated coherent poison | Majority vote aggregation |

### Hallucination Risk Categories

| Risk Level | Indicator | Action |
|------------|-----------|--------|
| LOW | High entity frequency, high co-occurrence | Normal generation |
| MEDIUM | Some low-frequency entities | Retrieve before generate |
| HIGH | Zero co-occurrence claims | Flag + verification |
| CRITICAL | Failed NLI grounding | Force abstention |

### Government Edition Considerations

1. **Stricter thresholds**: Lower anomaly threshold, higher grounding requirements
2. **Mandatory abstention**: Force explicit "cannot answer" on uncertainty
3. **Audit trail**: Full reasoning traces for STIG compliance
4. **AI content limits**: Cap write-back ratio at 10% for government corpora

---

---

## Appendix C: Review & Critique Integration

### Gemini Code Audit Feedback (Incorporated)

**Source**: `~/.gemini/antigravity/brain/.../plan_critique.md`
**Status**: **STRONGLY ENDORSED** with tactical adjustment incorporated

#### Key Finding: QuCo-RAG Quick Win

Gemini's independent code audit identified that `QuCoRagService` (216 lines) is:
1. **Fully implemented** - not a stub
2. **Already injected** into `MercenaryController`
3. **Running in PASSIVE MODE** - logs warnings but doesn't act

This finding led to reordering priorities:
- **Before**: Hallucination Detection = Priority 5 (High Effort)
- **After**: Hallucination Detection = Priority 4 (Low Effort ⚡ Quick Win)

#### Validation

| Gemini Claim | Verified | Evidence |
|--------------|----------|----------|
| QuCoRagService exists | ✅ | 216-line implementation in `rag/qucorag/` |
| Injected into controller | ✅ | Lines 81, 116 of MercenaryController.java |
| Runs in PASSIVE MODE | ✅ | Lines 499, 731 only `log.warn()` |
| Low effort activation | ✅ | Change warning to action trigger |

#### Agreed Deferrals

Both audits agree the following are non-critical:
- TV-RAG (Video) - No current video corpus requirement
- HGMem (Hypergraph) - Graph-O1 meets current needs
- RAG+ (App-Aware) - Requires synthetic corpus generation

---

*Document generated from analysis of 12 RAG research papers against SENTINEL codebase*
*Tactical adjustment incorporated from Gemini code audit*
*Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>*
