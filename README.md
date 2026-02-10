# SENTINEL Intelligence Platform

Secure, air-gap compatible RAG platform for enterprise and government deployments.

## Capabilities

### Retrieval & Orchestration
- **9 RAG Strategies** — HybridRAG, HiFi-RAG, MegaRAG, MiA-RAG, QuCo-RAG, RAGPart, AdaptiveRAG, CRAG, and BidirectionalRAG working in concert. Queries are routed, decomposed, rewritten, and cross-validated automatically.
- **Adaptive Query Routing** — AdaptiveRAG classifies each query and routes to chunk-level or document-level retrieval based on complexity.
- **Corrective Retrieval** — CRAG detects low-confidence results and rewrites the query before re-searching.
- **Corpus Poisoning Defense** — RAGPart partitions the corpus to detect and isolate adversarial injections.
- **Uncertainty Quantification** — QuCo-RAG uses entity co-occurrence analysis to flag answers the system is uncertain about.

### Document Processing
- **Intelligent Table Extraction** — Tables in PDFs are detected, cropped at high resolution, and extracted as structured markdown via vision language models. Dense tables with merged cells, spanning headers, and visual groupings are preserved accurately instead of being flattened to text.
- **Visual Source Evidence** — Extracted tables are stored alongside their original rendered images. When extraction confidence is low, the raw image is served as a fallback so users always have access to the ground truth.
- **Multimodal Visual Analysis** — MegaRAG classifies images across 11 types (charts, diagrams, flowcharts, architecture diagrams, tables, maps, photos, screenshots) and extracts structured data from each.
- **Numeric & Tabular Verification** — Generation prompts include structured verification checklists that validate column attribution, aggregate vs. detail row distinction, decimal precision, and unit consistency before producing answers from tabular data.

### Citations & Trust
- **Page-Level Citation Traceability** — Every answer cites the source document and exact page number. Audit trails track which page and chunk produced each claim.
- **Source Page Verification** — Click any citation to view the original PDF page rendered on demand. The bridge between automated extraction and human-verifiable trust.
- **Citation Verification Service** — A dedicated post-generation pass validates that cited sources actually support the claims made in the response.

### Search Intelligence
- **Domain-Aware Query Expansion** — Configurable per-edition thesaurus automatically expands abbreviations, synonyms, and unit conversions. "LOX" finds "Liquid Oxygen." "2000 PSI" finds "13.9 MPa." Medical edition maps drug brand names to generics. Government edition maps program designations to common names.
- **Temporal Document Filtering** — Documents are indexed with extracted dates. Queries like "failures in the last 5 years" filter by time range before semantic search, reducing noise and compute on large historical archives.
- **Hybrid Semantic + Keyword Search** — HybridRAG fuses dense vector similarity with BM25 keyword matching via Reciprocal Rank Fusion, with built-in OCR tolerance for degraded scans.

### Security & Compliance
- **PII Redaction** — Automatic detection and redaction aligned to NIST, GDPR, HIPAA, and PCI-DSS standards.
- **HIPAA Strict Mode** — Medical edition enforces strict citation requirements, evidence excerpts, and controlled visual evidence handling (no persistent image storage, ephemeral rendering only).
- **Prompt Injection Detection** — Incoming queries are screened for injection attempts before reaching the retrieval pipeline.
- **Workspace Isolation** — Multi-tenant deployment with department and workspace-level document isolation.
- **Air-Gap Compatible** — Runs entirely on-premise with local LLM inference via Ollama. No outbound network calls required.
- **License Validation** — HMAC-based license keys with edition, expiry, and org binding. Tamper-evident and offline-verifiable.
- **Authentication Modes** — DEV, STANDARD (username/password with admin bootstrap), OIDC (browser-based Auth Code + PKCE flow), and CAC (smart card / certificate-based).

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
