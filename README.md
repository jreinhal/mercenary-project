# SENTINEL Intelligence Platform

Secure, air-gap compatible RAG platform for enterprise and government deployments.

## Capabilities (Current)

Note: Capabilities are edition/profile dependent; some features are optional or disabled by default.

### Retrieval & Orchestration
- **Multiple RAG Strategies (Feature-Flagged)** — HybridRAG, HiFi-RAG, MegaRAG, MiA-RAG, QuCo-RAG, RAGPart, AdaptiveRAG, CRAG, and BidirectionalRAG are implemented and can be enabled/combined per deployment.
- **Adaptive Query Routing** — AdaptiveRAG classifies each query and routes to chunk-level or document-level retrieval based on complexity.
- **Corrective Retrieval** — CRAG detects low-confidence results and rewrites the query before re-searching.
- **Corpus Poisoning Defense** — RAGPart partitions the corpus to detect and isolate adversarial injections.
- **Uncertainty Quantification** — QuCo-RAG performs corpus-grounded uncertainty checks to flag potentially hallucinated or weakly supported answers.

### Document Processing
- **Metadata-Preserving Ingestion** — Upstream extractor metadata (e.g., PDF `page_number`) is preserved where available, and each ingested chunk is tagged with deterministic `chunk_index` / `page_chunk_index` for traceability.
- **Multimodal Visual Analysis** — MegaRAG classifies images into common visual types (charts/diagrams/tables/etc) and extracts text, entities, and descriptions for retrieval.

### Citations & Trust
- **Evidence Metadata** — Retrieved chunks carry `source` and (when available) `page_number` metadata to support page-level traceability and audits.

### Search Intelligence
- **Query Expansion** — HybridRAG can expand queries via deterministic reformulations/synonyms, with optional LLM-assisted expansion.
- **Hybrid Semantic + Keyword Retrieval** — HybridRAG fuses dense vector similarity with keyword-based heuristics via Reciprocal Rank Fusion, with OCR-tolerance heuristics for degraded scans.

### Security & Compliance
- **PII Redaction** — Automatic detection and redaction aligned to NIST, GDPR, HIPAA, and PCI-DSS standards.
- **HIPAA Strict Mode** — Medical edition enforces strict citation requirements, evidence excerpts, and controlled visual evidence handling (no persistent image storage, ephemeral rendering only).
- **Prompt Injection Detection** — Incoming queries are screened for injection attempts before reaching the retrieval pipeline.
- **Workspace Isolation** — Multi-tenant deployment with department and workspace-level document isolation.
- **Air-Gap Compatible** — Runs entirely on-premise with local LLM inference via Ollama. No outbound network calls required.
- **License Validation** — HMAC-based license keys with edition, expiry, and org binding. Tamper-evident and offline-verifiable.
- **Authentication Modes** — DEV, STANDARD (username/password with admin bootstrap), OIDC (browser-based Auth Code + PKCE flow), and CAC (smart card / certificate-based).

## Roadmap (Planned / In Progress)

- **PDF Table Extraction** — Route text-layer PDFs through a deterministic table extractor (e.g., Tabula) and scanned PDFs through PDF rendering + vision extraction for high-fidelity tables.
- **Visual Source Evidence for Tables** — Store table crops as evidence (or render on demand from source PDFs in strict environments) and use them as a fallback when extraction is uncertain.
- **Source Page Verification UI** — Click a citation to render the original PDF page on demand from the stored source document.
- **Decoupled Citation Verification** — Add an optional post-generation citation verification pass to validate that cited sources support claims.
- **Domain-Aware Query Expansion** — Per-edition thesaurus for abbreviations, synonyms, and unit normalization (e.g., LOX, PSI/MPa; medical brand to generic).
- **Temporal Filtering & Scoring** — Extract and index document dates to support time-range queries and temporal decay scoring.
- **Numeric/Tabular Answer Verification** — Add table-aware verification checklists (especially for MEDICAL/GOVERNMENT editions) before finalizing numeric claims.

## Quick start

Prerequisites:
- Java 21 (LTS)
- MongoDB 7.x or 8.x
- Ollama

PowerShell:
```
$env:APP_PROFILE="dev"
$env:MONGODB_URI="mongodb://localhost:27017/mercenary"
$env:OLLAMA_URL="http://localhost:11434"
$env:LLM_MODEL="llama3.1:8b"
$env:EMBEDDING_MODEL="nomic-embed-text"
.\gradlew bootRun
```

## Configuration
Key environment variables:
- APP_PROFILE (dev, standard, enterprise, govcloud)
- AUTH_MODE (DEV, STANDARD, OIDC, CAC)
- MONGODB_URI
- OLLAMA_URL
- LLM_MODEL
- EMBEDDING_MODEL

STANDARD profile bootstrap:
- SENTINEL_BOOTSTRAP_ENABLED=true
- SENTINEL_ADMIN_PASSWORD=<value>

## Editions
- trial
- enterprise
- medical
- government

Edition-based response policy:
- Enterprise/Trial: longer synthesized responses, evidence appended if citations are missing
- Medical: HIPAA-aligned redaction + strict citations + evidence excerpts
- Government: classified-environment posture with strict citations + evidence excerpts

Build:
```
./gradlew build -Pedition=government
```

## Testing
```
./gradlew test
./gradlew ciE2eTest
```
`ciE2eTest` uses the `ci-e2e` + `dev` test profiles with an in-memory vector store and stubbed chat/embedding models.

## Documentation
- docs/README.md
- In-app manual: /manual.html

## Supported file types
- Text: .txt, .md, .log
- Data: .csv, .json, .ndjson
- Office: .docx, .pptx, .xlsx, .xls
- PDF: .pdf

## Security notes
- DEV mode auto-provisions admin access for development only
- Do not expose MongoDB or Ollama ports publicly

## License
Proprietary - All rights reserved.
