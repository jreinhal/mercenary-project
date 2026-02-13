# SENTINEL Intelligence Platform

Secure, air-gap compatible RAG platform for enterprise and government deployments.

## RAG Capability Snapshot

SENTINEL includes the implemented Phase 0-5 RAG capabilities:

- **Corpus Poisoning Defense** via RAGPart validation before generation.
- **Agentic Multi-Hop Reasoning** with tool-augmented orchestration for complex queries.
- **Multimodal Ingestion + Retrieval** across text, tables, and visual assets.
- **Hierarchical and Hybrid Retrieval** with chunk/document routing, reranking, and query expansion.
- **Hallucination Risk Controls** with corpus-grounded uncertainty checks and abstention paths.

## Capabilities (Current)

Note: Capabilities are edition/profile dependent; some features are optional or disabled by default.

### Retrieval & Orchestration
- **Multiple RAG Strategies (Feature-Flagged)** — HybridRAG, HiFi-RAG, MegaRAG, MiA-RAG, QuCo-RAG, RAGPart, AdaptiveRAG, CRAG, and BidirectionalRAG are implemented and can be enabled/combined per deployment.
- **Adaptive Query Routing** — AdaptiveRAG classifies each query and routes to chunk-level or document-level retrieval based on complexity.
- **Agent Tool Suite** — Agentic orchestration can use document info lookup, adjacent-chunk expansion, and visual-region evidence tools for deeper investigations.
- **Corrective Retrieval** — CRAG detects low-confidence results and rewrites the query before re-searching.
- **Corpus Poisoning Defense** — RAGPart partitions the corpus to detect and isolate adversarial injections.
- **Uncertainty Quantification** — QuCo-RAG performs corpus-grounded uncertainty checks to flag potentially hallucinated or weakly supported answers.

### Document Processing
- **Metadata-Preserving Ingestion** — Upstream extractor metadata (e.g., PDF `page_number`) is preserved where available, and each ingested chunk is tagged with deterministic `chunk_index` / `page_chunk_index` for traceability.
- **Table-Aware Extraction Pipeline** — Ingestion includes table extraction paths and table-specific chunk handling to preserve tabular evidence fidelity.
- **Multimodal Visual Analysis** — MegaRAG classifies images into common visual types (charts/diagrams/tables/etc) and extracts text, entities, and descriptions for retrieval.
- **Ingestion Resilience** — Checkpoint/resume state, bounded retries, and failure thresholds help long-running ingestion complete reliably.
- **Incremental Connector Sync** — S3/SharePoint/Confluence connector syncs track per-source fingerprints/hashes, skip unchanged sources, and prune removed sources instead of re-ingesting entire connector corpora each run.
- **Legacy Connector Metadata Migration** — Optional one-time startup migration can backfill connector metadata on existing vector chunks so incremental sync cleanup can safely manage pre-existing connector data (run at least one connector sync first to seed connector state records).

### Citations & Trust
- **Evidence Metadata** — Retrieved chunks carry `source` and (when available) `page_number` metadata to support page-level traceability and audits.
- **Visual Evidence Endpoints** — Source page and cropped region rendering support direct citation verification workflows.

### Search Intelligence
- **Query Expansion** — HybridRAG can expand queries via deterministic reformulations/synonyms, with optional LLM-assisted expansion.
- **Hybrid Semantic + Keyword Retrieval** — HybridRAG fuses dense vector similarity with keyword-based heuristics via Reciprocal Rank Fusion, with OCR-tolerance heuristics for degraded scans.
- **Domain Thesaurus Expansion** — Department/workspace-aware acronym and synonym expansion improves recall on specialized vocabularies.
- **Temporal Filtering** — Query-time year constraints can prefilter retrieval when temporal expressions are detected.

### Security & Compliance
- **PII Redaction** — Automatic detection and redaction aligned to NIST, GDPR, HIPAA, and PCI-DSS standards.
- **HIPAA Strict Mode** — Medical edition enforces strict citation requirements, evidence excerpts, and controlled visual evidence handling (no persistent image storage, ephemeral rendering only).
- **Prompt Injection Detection** — Incoming queries are screened for injection attempts before reaching the retrieval pipeline.
- **Workspace Isolation** — Multi-tenant deployment with department and workspace-level document isolation.
- **Air-Gap Compatible** — Runs entirely on-premise with local LLM inference via Ollama. No outbound network calls required.
- **License Validation** — HMAC-based license keys with edition, expiry, and org binding. Tamper-evident and offline-verifiable.
- **Authentication Modes** — DEV, STANDARD (username/password with admin bootstrap), OIDC (browser-based Auth Code + PKCE flow), and CAC (smart card / certificate-based).

## Architecture (High Level)

```text
Ingestion (text/table/visual extraction + metadata + resilience checkpoints)
    ->
Vector + Hybrid Indexing (semantic, lexical, thesaurus, temporal metadata)
    ->
Retrieval Orchestration (HybridRAG / HiFi-RAG / Agentic / CRAG / RAGPart)
    ->
Grounding + Uncertainty Checks (QuCo-RAG + policy gates)
    ->
Response + Citations (+ optional source page/region evidence rendering)
```

## Roadmap (Next)

- Expand benchmark automation and hard-document regression packs for release gating.
- Continue optional model-infrastructure evaluation (multimodal embeddings and reranker tuning per deployment hardware).
- Broaden customer-facing docs (API, security, and operations runbooks) for newly shipped RAG capabilities.

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

Feature flags and tuning knobs commonly adjusted in production:
- AGENTIC_ENABLED
- THESAURUS_ENABLED
- RAG_TEMPORAL_FILTERING_ENABLED
- HIFIRAG_RERANKER_MODE (`dedicated`, `auto`, `llm`, `keyword`)
- EMBEDDING_BATCH_SIZE
- EMBEDDING_MULTIMODAL_ENABLED
- INGEST_RESILIENCE_ENABLED
- INGEST_MAX_RETRIES
- SOURCE_RETENTION_PDF_ENABLED
- SENTINEL_CONNECTORS_SYNC_ENABLED
- SENTINEL_CONNECTORS_INCREMENTAL_SYNC_ENABLED
- SENTINEL_CONNECTORS_LEGACY_MIGRATION_ENABLED
- SENTINEL_CONNECTORS_LEGACY_MIGRATION_DRY_RUN

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
./gradlew ciOidcE2eTest
```
`ciE2eTest` uses the `ci-e2e` + `dev` test profiles with an in-memory vector store and stubbed chat/embedding models.
`ciOidcE2eTest` validates the enterprise OIDC bearer-token path using local JWT/JWKS test fixtures.
Response-format quality is a release gate in UI/UAT runs:
- no mojibake/replacement characters (`�`)
- no binary-signature noise rendered as prose (`PK...`, `Rar!...`, class magic-byte gibberish)
- no mirrored duplicate clauses (`X - X`)

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
