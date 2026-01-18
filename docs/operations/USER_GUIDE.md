# SENTINEL Intelligence Platform - User Guide

**Version:** 2.1.0
**Classification:** UNCLASSIFIED // FOR OFFICIAL USE ONLY

## 1. Quick Start

### Windows Users
1.  Double-click `scripts/start_sentinel.bat`.
2.  When prompted, select your deployment mode:
    *   **[1] Commercial:** For Medical, Legal, and Financial use.
    *   **[2] Government:** For DoD/IC use (Requires CAC/PIV).
3.  The application will launch in your default web browser at `http://localhost:8080`.

### Mac/Linux Users
1.  Open Terminal.
2.  Run `./scripts/start_sentinel.sh`.
3.  Follow the interactive prompts to select your mode.

---

## 2. Interface & Sectors

SENTINEL adapts its interface and terminology to your specific mission profile. Use the **Sector Selector** in the command bar to switch modes instantly.

| Sector | Theme | Terminology | Use Case |
| :--- | :--- | :--- | :--- |
| **DEFENSE** | **Dark / Terminal** | Intelligence Platform | Mission planning, threat analysis, tactical query. |
| **MEDICAL** | **Clinical (White/Blue)** | Clinical Assistant | Patient diagnostics, research summarization, drug interactions. |
| **LEGAL** | **Professional (Serif)** | Legal Research Aid | Case law retrieval, precedent analysis, contract review. |
| **FINANCIAL** | **Professional (Serif)** | Market Analyst | Market trend analysis, ticker research, risk assessment. |

---

## 3. How to Query

SENTINEL uses a **"Glass Box"** reasoning engine. It doesn't just give you an answer; it shows you *how* it found it.

### Best Practices
*   **Be Specific:** Instead of "Tell me about the patient," ask "Summarize the patient's history of cardiac issues from the uploaded PDF."
*   **Upload Context:** Drag and drop relevant files (PDF, TXT, MD) into the "Upload Intelligence" zone before asking questions.
*   **Check Citations:** Every fact in the response is followed by a citation like `[report.pdf]`. Click it to verify the source document.

### Uploaded This Session (Scope)
The "Uploaded this session" chips show files you added during the current session:
*   **Checked** chips are included in retrieval (in scope).
*   **Unchecked** chips are excluded.
*   **None checked:** SENTINEL searches all documents in the selected sector.
*   Clearing this list removes the session indicator only; it does not delete stored files.

### Understanding Response Confidence
*   **High (Green):** Answer is directly supported by multiple high-relevance documents.
*   **Medium (Yellow):** Answer is inferred or supported by fewer documents.
*   **Low (Red):** Information is scarce. Proceed with caution.

### Hallucination Defense (QuCo-RAG)
SENTINEL uses **QuCo-RAG** (Uncertainty Quantification) to detect potential hallucinations before they reach you:
*   The system extracts entities (names, organizations, dates, technical terms) from your query.
*   It checks whether these entities appear frequently in the knowledge base.
*   If an entity is rare or unknown, the system flags it as **high uncertainty** and retrieves additional context to improve accuracy.
*   You may see a warning like `⚠️ High uncertainty detected` if the system has low confidence in certain claims.

---

## 4. Advanced RAG Features (v2.1)

SENTINEL v2.1 includes 9 cutting-edge RAG technologies based on the latest academic research:

### Hybrid Retrieval (RRF Fusion)
Combines semantic vector search with keyword search using **Reciprocal Rank Fusion**:
*   **15-25% improved recall** compared to semantic-only search
*   **Multi-query expansion** generates query variants automatically
*   **OCR error tolerance** handles scanned document mistakes (0/O, 1/l/I substitutions)

### Graph-O1 MCTS Reasoning
Uses **Monte Carlo Tree Search** for intelligent knowledge graph traversal:
*   Strategic exploration of reasoning paths (not brute-force)
*   **UCB1 bandit algorithm** balances exploration vs. exploitation
*   **Early termination** when high-confidence path is found

### MegaRAG Multimodal
Enables **cross-modal retrieval** across text, images, and charts:
*   Automatically extracts data from embedded charts
*   OCR-enabled image text search
*   Visual entity linking connects images to text concepts

### MiA-RAG Mindscape
Provides **long-document coherence** through hierarchical summarization:
*   Builds multi-level summaries (chunk → paragraph → section → document)
*   Prevents "lost in the middle" problem in 100+ page documents
*   Global context conditioning maintains document-level understanding

### Bidirectional RAG Experience Store
Enables **continuous self-improvement**:
*   Stores verified Q&A pairs for future retrieval
*   **Grounding verification** validates response-to-source alignment
*   **Novelty detection** identifies new valuable information
*   Optional admin approval workflow for quality control

---

## 5. Troubleshooting

**"System Offline / LLM Unreachable"**
*   The system has entered "Offline Mode." It will still retrieve documents but may not be able to synthesize fluent answers. It will provide raw excerpts instead.

**"Access Denied"**
*   Ensure you are logged in to the correct mode (Gov vs Commercial).
*   Check your clearance level. You cannot access "TOP SECRET" documents from an "UNCLASSIFIED" terminal.

**Display Issues**
*   If the screen is blank, try refreshing the page (`Ctrl + R`) or clearing your browser cache.

**"Edge Extension in Chrome"**
*   If you are using the Microsoft Edge extension in Chrome, disable "Headless mode" in the extension settings for full functionality.

---
*Powered by Project Mercenary HyperGraph Engine v2.1*

### Academic Papers Implemented

| Feature | Paper | Citation |
|---------|-------|----------|
| HiFi-RAG | arXiv:2512.22442v1 | High-Fidelity Retrieval |
| RAGPart | arXiv:2512.24268v1 | Corpus Poisoning Defense |
| HGMem | arXiv:2512.23959v2 | Hypergraph Memory |
| QuCo-RAG | arXiv:2512.19134 | Hallucination Detection |
| MegaRAG | arXiv:2512.20626 | Multimodal Knowledge Graph |
| MiA-RAG | arXiv:2512.17220 | Mindscape-Aware Retrieval |
| BiRAG | arXiv:2512.22199 | Bidirectional Experience Store |
| Hybrid RAG | arXiv:2512.12694 | Reciprocal Rank Fusion |
| Graph-O1 | arXiv:2512.17912 | MCTS Graph Reasoning |
