# Agentic Reasoning Architecture Alignment

**Date:** 2026-01-23
**Status:** Validated - Current architecture aligns with research best practices
**Source:** "Agentic Reasoning for Large Language Models" (arXiv:2601.12538)

---

## Executive Summary

SENTINEL's RAG architecture implements the core patterns identified in current research on agentic reasoning systems. This document maps our implementation to published research for ATO documentation, technical discussions, and architecture reviews.

---

## Research Framework: Agentic Reasoning Components

The referenced paper identifies key components for effective agentic LLM systems:

| Research Component | Description |
|-------------------|-------------|
| Planning & Decomposition | Breaking complex tasks into manageable steps |
| Dynamic Retrieval | Deciding when/what to retrieve based on context |
| Tool Integration | Orchestrating multiple knowledge sources |
| Iterative Refinement | Feedback loops between retrieval and generation |
| Memory Systems | Maintaining context across interactions |
| Uncertainty Handling | Adapting behavior based on confidence |
| Multi-Agent Coordination | Specialized components for distinct subtasks |

---

## SENTINEL Implementation Mapping

### 1. Planning & Query Decomposition

**Research Requirement:** Agents should decompose complex tasks into sub-problems with defined dependencies.

**SENTINEL Implementation:**
- `QueryDecompositionService` (enterprise edition)
- Five decomposition strategies: SEQUENTIAL, PARALLEL, COMPARATIVE, TEMPORAL, HIERARCHICAL
- Sub-query extraction with purpose, dependencies, and required entities
- Strategy-specific synthesis prompts for answer combination

**Code Reference:** `src/main/java/com/jreinhal/mercenary/enterprise/rag/QueryDecompositionService.java`

---

### 2. Dynamic Retrieval Strategies

**Research Requirement:** Agents should decide retrieval approach based on query characteristics, not apply static patterns.

**SENTINEL Implementation:**
- `AdaptiveRagService` performs three-tier routing:
  - `NO_RETRIEVAL`: Conversational queries bypass retrieval entirely
  - `CHUNK`: Specific factual queries use targeted retrieval (topK=5, threshold=0.2)
  - `DOCUMENT`: Complex analysis uses broader retrieval (topK=3, threshold=0.1)
- Pattern detection for: conversational, definitional, comparative, temporal, multi-hop, HYDE-suitable queries
- Configurable parameters per routing decision

**Code Reference:** `src/main/java/com/jreinhal/mercenary/rag/adaptiverag/AdaptiveRagService.java`

---

### 3. Tool-Augmented Search & Multi-Source Orchestration

**Research Requirement:** Agents should orchestrate multiple retrieval operations and knowledge sources.

**SENTINEL Implementation:**
- `HybridRagService`: Vector similarity + BM25 keyword search with RRF fusion
- `ModalityRouter`: Routes to TEXT, VISUAL, TABLE, or CROSS_MODAL retrieval
- `MegaRagService`: Cross-modal retrieval for visual content
- `RagOrchestrationService`: Chains strategies based on query complexity
- Strategy cascade: RAGPart → MiA-RAG → HiFi-RAG → HybridRAG → Fallback

**Code References:**
- `src/main/java/com/jreinhal/mercenary/rag/hybridrag/HybridRagService.java`
- `src/main/java/com/jreinhal/mercenary/rag/ModalityRouter.java`

---

### 4. Iterative Refinement with Feedback Loops

**Research Requirement:** Systems should combine generation and retrieval based on intermediate outputs, not single-pass processing.

**SENTINEL Implementation:**
- `HiFiRagService`: Iterative retrieval with gap detection
  - Initial broad retrieval (K=20, threshold=0.1)
  - Cross-encoder reranking
  - Gap detection: identifies concepts in query missing from retrieved docs
  - Iterative retrieval (max 2 iterations) to fill gaps
  - Final filtering (K=5, threshold=0.5)
- `SelfRagService`: Self-reflective generation
  - Marks claims: `[SUPPORTED]`, `[INFERRED]`, `[UNCERTAIN]`, `[UNSUPPORTED]`
  - Iterates up to 3 times until confidence ≥ 0.75
  - Re-retrieves if uncertain claims exceed threshold

**Code References:**
- `src/main/java/com/jreinhal/mercenary/rag/hifirag/HiFiRagService.java`
- `src/main/java/com/jreinhal/mercenary/rag/selfrag/SelfRagService.java`

---

### 5. Memory Systems for Context Persistence

**Research Requirement:** Agents need memory to maintain context across multiple reasoning steps and interactions.

**SENTINEL Implementation:**
- `ConversationMemoryService`:
  - Maintains 10 most recent messages per session (24-hour window)
  - TF-based topic extraction (5 active topics from user messages)
  - Follow-up detection: pronouns, continuation phrases, short queries
  - Context expansion for follow-up questions
- `SessionPersistenceService`:
  - Active session tracking with user/session/department context
  - Reasoning trace persistence across requests
  - Configurable retention (default 24 hours)

**Code References:**
- `src/main/java/com/jreinhal/mercenary/enterprise/memory/ConversationMemoryService.java`
- `src/main/java/com/jreinhal/mercenary/enterprise/memory/SessionPersistenceService.java`

---

### 6. Uncertainty Quantification & Adaptive Behavior

**Research Requirement:** Agents should adapt retrieval and generation based on confidence/uncertainty signals.

**SENTINEL Implementation:**
- `QuCoRagService`: Query uncertainty quantification
  - Entity extraction and frequency analysis
  - Per-entity uncertainty contribution scoring
  - Aggregated uncertainty score (0.0-1.0)
  - Triggers additional retrieval when uncertainty ≥ 0.7 (configurable)
- Hallucination risk detection:
  - Compares generated entities vs. query entities
  - Checks co-occurrence in vector store
  - Flags novel entities not present in corpus

**Code Reference:** `src/main/java/com/jreinhal/mercenary/rag/qucorag/QuCoRagService.java`

---

### 7. Multi-Strategy Coordination

**Research Requirement:** Complex systems benefit from specialized agents/components handling distinct subtasks.

**SENTINEL Implementation:**
- `RagOrchestrationService` coordinates specialized strategies:
  - `RAGPartService`: Partition-based verification for adversarial robustness
  - `MiARagService`: Mindscape-based retrieval for complex queries
  - `HiFiRagService`: High-fidelity iterative retrieval
  - `HybridRagService`: Semantic + keyword fusion
  - `AgenticRagService`: Multi-step orchestration for complex reasoning
  - `CragService`: Corrective RAG with quality grading
- Each strategy has defined entry conditions and fallback behavior
- Reasoning traces log which strategies were invoked and why

**Code Reference:** `src/main/java/com/jreinhal/mercenary/rag/RagOrchestrationService.java`

---

## Coverage Summary

| Agentic Reasoning Pattern | SENTINEL Coverage | Implementation Maturity |
|--------------------------|-------------------|------------------------|
| Planning & Decomposition | ✅ Full | Production |
| Dynamic Retrieval | ✅ Full | Production |
| Tool/Multi-Source Orchestration | ✅ Full | Production |
| Iterative Refinement | ✅ Full | Production |
| Memory Systems | ✅ Full | Production |
| Uncertainty Handling | ✅ Full | Production |
| Multi-Strategy Coordination | ✅ Full | Production |
| Explicit Plan Generation | ⚠️ Partial | Implicit via routing |
| Agent Self-Critique | ⚠️ Partial | Citation-level only |
| Retrieval Backtracking | ⚠️ Partial | Cascade, not explicit backtrack |

---

## Potential Future Enhancements

Based on research patterns not yet fully implemented:

### 1. Explicit Plan Generation
Generate visible reasoning plan before execution for complex queries:
```
"To answer this, I will: 1) Find documents about X, 2) Cross-reference with Y data, 3) Synthesize findings"
```
**Value:** Increases transparency and user trust; enables plan correction before execution.

### 2. Broader Self-Critique
Extend beyond citation verification to holistic answer quality:
- "Did I answer the actual question asked?"
- "Am I missing an obvious angle?"
- "Would a domain expert find this complete?"

### 3. Explicit Retrieval Backtracking
When strategy cascade fails, model explicit "reconsider the query" step rather than just trying next strategy in queue.

---

## References

- Primary: "Agentic Reasoning for Large Language Models" (arXiv:2601.12538, January 2026)
- Supporting: SENTINEL Architecture Documentation
- Related: NIST 800-53 Control Mapping (for ATO context)

---

## Document Usage

| Context | How to Use This Document |
|---------|-------------------------|
| **ATO Package** | Reference Section "Coverage Summary" to demonstrate architecture aligns with published research best practices |
| **Sales/Technical** | Use "Implementation Mapping" to show concrete capabilities with code references |
| **Architecture Review** | Reference "Potential Future Enhancements" for roadmap discussions |
