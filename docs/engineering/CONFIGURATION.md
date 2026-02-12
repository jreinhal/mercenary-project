# Configuration Reference

This file summarizes the most important configuration keys.

## Profiles
- APP_PROFILE=dev|standard|enterprise|govcloud

## Test profiles (Spring)
- ci-e2e (tests only): disables Mongo auto-config and cloud AI auto-config, enables in-memory vector store.
  - Defined in `src/test/resources/application-ci-e2e.yml`

## Authentication
- AUTH_MODE=DEV|STANDARD|OIDC|CAC
- TRUSTED_PROXIES: comma-separated proxy IPs for X-Forwarded-For
- APP_STANDARD_ALLOW_BASIC=true|false

## Rate limiting
- APP_RATE_LIMIT_ENABLED=true|false (default: true)
- APP_RATE_LIMIT_VIEWER_RPM (default: 60)
- APP_RATE_LIMIT_ANALYST_RPM (default: 100)
- APP_RATE_LIMIT_ADMIN_RPM (default: 200)
- APP_RATE_LIMIT_ANONYMOUS_RPM (default: 30)

Notes:
- Pre-auth rate limiting should remain ON in normal runs.
- Disable it only for automated E2E runs that perform bulk uploads (set `APP_RATE_LIMIT_ENABLED=false`), then re-enable for day-to-day usage.

## MongoDB
- MONGODB_URI (default: mongodb://localhost:27017/mercenary)

## Ollama
- OLLAMA_URL (default: http://localhost:11434)
- LLM_MODEL (default: llama3.1:8b)
- EMBEDDING_MODEL (default: nomic-embed-text)
- LLM_TEMPERATURE
- LLM_NUM_PREDICT
- LLM_NUM_CTX
- LLM_TIMEOUT_SECONDS

## Swagger
- SWAGGER_ENABLED (default false, true in dev profile)

## UI Testing (Manual, Headed)
- Manual UI testing is supported via Playwright (headed browser mode) and can be run against the local UI.
- Use `tools/playwright-runner/run-ui-tests.js` (or related scripts) to drive real UI interactions.

## Guardrails
- GUARDRAILS_ENABLED
- GUARDRAILS_LLM_ENABLED
- GUARDRAILS_LLM_TIMEOUT_MS
- GUARDRAILS_STRICT_MODE

## Bootstrap (STANDARD profile)
- SENTINEL_BOOTSTRAP_ENABLED=true|false
- SENTINEL_ADMIN_PASSWORD=<password>
- SENTINEL_BOOTSTRAP_RESET_ADMIN=true|false (test only; force admin password reset)

## CSRF testing helpers
- APP_CSRF_BYPASS_INGEST=true|false (test only; disables CSRF on /api/ingest/**)

## Performance tuning
- RAG_CORE_THREADS
- RAG_MAX_THREADS
- RAG_QUEUE_CAPACITY
- RERANKER_THREADS
- RAG_FUTURE_TIMEOUT_SECONDS

## LLM timeouts (ms)
- GUARDRAILS_LLM_TIMEOUT_MS
- BIRAG_LLM_TIMEOUT_MS
- ADAPTIVERAG_ROUTER_TIMEOUT_MS
- CRAG_REWRITE_TIMEOUT_MS

## Prompt and context limits
- RAG_MAX_CONTEXT_CHARS
- RAG_MAX_DOC_CHARS
- RAG_MAX_VISUAL_CHARS
- RAG_MAX_OVERVIEW_CHARS
- RAG_MAX_DOCS
- RAG_MAX_VISUAL_DOCS

## RAG upgrades (Phase 3-5)

### Domain and temporal retrieval
- RAG_TEMPORAL_FILTERING_ENABLED (default: false)
- THESAURUS_ENABLED (default: true)
- THESAURUS_UNIT_CONVERSION_ENABLED (default: false)
- THESAURUS_VECTOR_INDEX_ENABLED (default: false)
- THESAURUS_VECTOR_INDEX_CACHE_TTL_SECONDS (default: 21600)
- THESAURUS_MAX_QUERY_VARIANTS (default: 4)

### Source evidence retention and rendering
- SOURCE_RETENTION_PDF_ENABLED (default: true)
- SOURCE_RETENTION_ALLOW_HIPAA_STRICT (default: false)
- SOURCE_RETENTION_PDF_MAX_BYTES (default: 52428800)
- SOURCE_RETENTION_PDF_CACHE_TTL_HOURS (default: 1)
- SOURCE_RETENTION_PDF_CACHE_MAX_TOTAL_BYTES (default: 268435456)
- SOURCE_RENDER_DPI (default: 160)
- SOURCE_RENDER_MAX_OUTPUT_PIXELS (default: 3686400)

Notes:
- `/api/source/page` and `/api/source/region` require retained source bytes.
- HIPAA strict environments should keep `SOURCE_RETENTION_ALLOW_HIPAA_STRICT=false` unless policy explicitly allows retention.

### Ingestion resilience
- INGEST_RESILIENCE_ENABLED (default: true)
- INGEST_MAX_RETRIES (default: 1)
- INGEST_FAILURE_THRESHOLD_PERCENT (default: 50)
- INGEST_FAILURE_THRESHOLD_MIN_SAMPLES (default: 5)
- INGEST_CHECKPOINT_PATH (default: `${java.io.tmpdir}/sentinel-ingestion/session.json`)
- INGEST_FAILED_DOCS_MAX (default: 500)

### Reranker and embedding evaluation knobs
- HIFIRAG_RERANKER_MODE (default: `dedicated`; options: `dedicated`, `auto`, `llm`, `keyword`)
- HIFIRAG_RERANKER_BATCH (default: 5)
- HIFIRAG_RERANKER_TIMEOUT (default: 30)
- HIFIRAG_USE_LLM (default: true, fallback path)
- HIFIRAG_RERANKER_CACHE_SIZE (default: 2000)
- HIFIRAG_RERANKER_CACHE_TTL (default: 900)
- EMBEDDING_BATCH_SIZE (default: 128)
- EMBEDDING_TARGET_DIMENSIONS (default: 0; 0 disables strict dimension targeting)
- EMBEDDING_MULTIMODAL_ENABLED (default: false)

### Agentic orchestration tuning
- AGENTIC_ENABLED (default: false)
- AGENTIC_DOCUMENTS_PER_QUERY_CEILING (default: 40)
- AGENTIC_QUICK_LOOKUP_MAX_TERMS (default: 9)

## Recommended baselines by edition

These are operational starting points, not strict policy requirements.

- Trial / Enterprise (balanced):
  - THESAURUS_ENABLED=true
  - RAG_TEMPORAL_FILTERING_ENABLED=true
  - INGEST_RESILIENCE_ENABLED=true
  - HIFIRAG_RERANKER_MODE=auto
  - SOURCE_RETENTION_PDF_ENABLED=true
- Medical (strict-by-default posture):
  - SOURCE_RETENTION_ALLOW_HIPAA_STRICT=false
  - THESAURUS_UNIT_CONVERSION_ENABLED=false until benchmark-validated
  - AGENTIC_DOCUMENTS_PER_QUERY_CEILING=40
  - Keep citation-enforcing response policy enabled (default behavior in medical edition)
- Government (strict verification posture):
  - AGENTIC_ENABLED=true only after latency testing in target environment
  - AGENTIC_DOCUMENTS_PER_QUERY_CEILING=40
  - HIFIRAG_RERANKER_MODE=dedicated (or auto fallback in constrained deployments)
  - SOURCE_RETENTION_PDF_ENABLED=true (for page/region auditability) with environment-approved retention controls

## Tokenization vault
- APP_TOKENIZATION_SECRET_KEY (maps to app.tokenization.secret-key)

## LightOnOCR (Scanned Document Support)
- OCR_ENABLED=true|false (default: false)
- OCR_SERVICE_URL (default: http://localhost:8090)
- OCR_TIMEOUT_SECONDS (default: 60)
- OCR_MAX_TOKENS (default: 2048) - max tokens per page
- OCR_MAX_PAGES (default: 50) - max pages to process per PDF
- OCR_FALLBACK_SCANNED=true|false (default: true) - auto-detect scanned PDFs

### LightOnOCR Microservice Setup
The OCR service uses the LightOnOCR-2-1B vision-language model for extracting text from scanned documents and images.

```bash
# Start the microservice (from repo root)
cd services/lightonocr-service
python -m venv venv
.\venv\Scripts\activate  # Windows (or: source venv/bin/activate)
pip install -r requirements.txt
python ocr_service.py

# Or with Docker
docker build -t lightonocr-service services/lightonocr-service
docker run -p 8090:8090 --gpus all lightonocr-service
```

### Supported Formats
- Images: PNG, JPEG, WebP, TIFF
- PDFs: Both text-based and scanned (image-based)

### Scanned PDF Detection
When `OCR_FALLBACK_SCANNED=true`, SENTINEL automatically detects scanned PDFs (documents where Tika extracts minimal text) and routes them through LightOnOCR for visual text extraction.

## HyperGraph Memory (Entity Explorer)

HyperGraph Memory extracts entities and relationships from documents during ingestion.

### Configuration
- **HGMEM_INDEXING** (default: true) - Extract entities when documents are uploaded. Fast operation using NLP, no LLM calls.
- **HGMEM_QUERY** (default: false) - Enable multi-hop graph traversal at query time. Can be slow (minutes) for large corpora.
- HGMEM_MAX_POINTS (default: 50) - max entities returned per query
- HGMEM_MERGE_THRESHOLD (default: 0.7) - similarity threshold for entity deduplication
- HGMEM_MAX_HOPS (default: 3) - traversal depth for relationship discovery

### Deep Analysis Toggle (UI)
Users can enable **Deep Analysis** on a per-query basis using the toggle button in the chat input area.

- When **OFF** (default): Standard vector search only. Fast queries (~1-2 seconds).
- When **ON**: Multi-hop graph traversal finds documents connected through shared entities, even without similar text. Queries may take several minutes.

The **Entity Network** tab in the right panel only appears when Deep Analysis is enabled.

## Demo dataset loader (admin-only)
- SENTINEL_DEMO_ENABLED (default: true; disabled in govcloud profile)
- SENTINEL_DEMO_PATH (default: classpath:demo_docs/*.*)
- SENTINEL_DEMO_MAX_FILES (default: 50)

## Case collaboration (non-regulated by default)
- SENTINEL_CASEWORK_ENABLED (default: true)
- SENTINEL_CASEWORK_ALLOW_REGULATED (default: false)

## Workspace isolation (non-regulated by default)
- SENTINEL_WORKSPACE_ENABLED (default: true)
- SENTINEL_WORKSPACE_ALLOW_REGULATED (default: false)
- SENTINEL_WORKSPACE_DEFAULT_ID (default: workspace_default)
- Request header: X-Workspace-Id (ignored in Medical/Government unless allow-regulated=true)

Workspace management APIs (admin-only; non-regulated editions):
- GET /api/workspaces
- POST /api/workspaces
- GET /api/workspaces/{id}/members
- POST /api/workspaces/{id}/members
- DELETE /api/workspaces/{id}/members/{userId}

Workspace quotas (per workspace):
- Set on workspace creation via quota fields (maxDocuments, maxQueriesPerDay, maxStorageMb).
- Enforcement applies to ingestion and query endpoints.
- Storage tracking uses per-file `fileSizeBytes` metadata on vector store documents.

Workspace quota admin APIs (non-regulated editions):
- PUT /api/workspaces/{id}/quota
- GET /api/workspaces/{id}/usage

## Reporting & Scheduled Exports (admin-only)
- SENTINEL_REPORTING_SCHEDULES_ENABLED (default: false)
- SENTINEL_REPORTING_SCHEDULES_ALLOW_REGULATED (default: false)
- SENTINEL_REPORTING_SCHEDULES_INTERVAL_MS (default: 300000)

Reporting APIs:
- GET /api/admin/reports/executive?days=30
- GET /api/admin/reports/sla?days=7
- GET /api/admin/reports/audit/export?days=7&format=json|csv&limit=1000
- GET /api/admin/reports/hipaa/audit (Medical strict mode)
- GET /api/admin/reports/hipaa/export?days=7&format=json|csv&limit=1000
- GET /api/admin/reports/schedules
- POST /api/admin/reports/schedules
- PATCH /api/admin/reports/schedules/{id}
- POST /api/admin/reports/schedules/{id}/run
- GET /api/admin/reports/exports
- GET /api/admin/reports/exports/{id}

## Connectors (admin-only)
- SENTINEL_CONNECTORS_ENABLED (default: true; disabled in govcloud profile)
- SENTINEL_CONNECTORS_ALLOW_REGULATED (default: false)

### SharePoint (Microsoft Graph)
- SENTINEL_SHAREPOINT_ENABLED
- SENTINEL_SHAREPOINT_GRAPH
- SENTINEL_SHAREPOINT_DRIVE_ID
- SENTINEL_SHAREPOINT_FOLDER
- SENTINEL_SHAREPOINT_TOKEN
- SENTINEL_SHAREPOINT_MAX_FILES
- SENTINEL_SHAREPOINT_DEPT

### Confluence
- SENTINEL_CONFLUENCE_ENABLED
- SENTINEL_CONFLUENCE_URL
- SENTINEL_CONFLUENCE_EMAIL
- SENTINEL_CONFLUENCE_TOKEN
- SENTINEL_CONFLUENCE_SPACE
- SENTINEL_CONFLUENCE_LIMIT
- SENTINEL_CONFLUENCE_PAGES
- SENTINEL_CONFLUENCE_DEPT

### S3
- SENTINEL_S3_ENABLED
- SENTINEL_S3_BUCKET
- SENTINEL_S3_PREFIX
- SENTINEL_S3_REGION
- SENTINEL_S3_ENDPOINT
- SENTINEL_S3_ACCESS_KEY
- SENTINEL_S3_SECRET_KEY
- SENTINEL_S3_MAX_FILES
- SENTINEL_S3_DEPT

## Admin APIs (Reporting & Catalog)
- GET /api/admin/connectors/catalog

**Performance Note:** Entity extraction during document upload is fast and always enabled by default. The slow operation is the graph traversal at query time, which is now opt-in per query.

### Entity Explorer UI
The Entity Explorer provides interactive visualization of entity relationships extracted from documents:
- **Access:** Enable Deep Analysis toggle, then select "Entity Network" tab in Plot panel
- **Features:**
  - Force-directed graph layout showing entities as colored nodes
  - Node colors indicate entity type (blue=technical, orange=dates, etc.)
  - Node size reflects reference count (how often entity appears)
  - Search bar to filter entities by name
  - Refresh button to reload the graph
- **Security:** All endpoints enforce sector isolation and clearance checks

### Quick Start with Entity Explorer
Use the provided startup scripts to run SENTINEL with HGMem enabled:

**Windows (Batch):**
```batch
start-entity-explorer.bat
```

**Windows (PowerShell):**
```powershell
.\start-entity-explorer.ps1
```

These scripts set `APP_PROFILE=dev` and enable entity indexing automatically.

### API Endpoints
- GET /api/graph/entities?dept=<sector>&limit=100&type=<entityType>
- GET /api/graph/neighbors?nodeId=<id>&dept=<sector>
- GET /api/graph/search?q=<query>&dept=<sector>&limit=20
- GET /api/graph/stats?dept=<sector>

## Feature flags (selected)
- RAGPART_ENABLED
- HGMEM_INDEXING (default: true) - entity extraction during upload
- HGMEM_QUERY (default: false) - graph traversal at query time
- HIFIRAG_ENABLED
- QUCORAG_ENABLED
- MEGARAG_ENABLED
- MIARAG_ENABLED
- BIRAG_ENABLED
- BIRAG_LLM_VERIFICATION
- HYBRIDRAG_ENABLED
- GRAPHO1_ENABLED
- ADAPTIVERAG_ENABLED
- CRAG_ENABLED
- HYDE_ENABLED
- SELFRAG_ENABLED
- AGENTIC_ENABLED

For full detail, see src/main/resources/application.yaml.
