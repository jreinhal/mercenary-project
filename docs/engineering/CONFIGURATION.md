# Configuration Reference

This file summarizes the most important configuration keys.

## Profiles
- APP_PROFILE=dev|standard|enterprise|govcloud

## Authentication
- AUTH_MODE=DEV|STANDARD|OIDC|CAC
- TRUSTED_PROXIES: comma-separated proxy IPs for X-Forwarded-For
- APP_STANDARD_ALLOW_BASIC=true|false

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
# Start the microservice
cd D:/Projects/lightonocr-service
pip install -r requirements.txt
python ocr_service.py

# Or with Docker
docker run -p 8090:8090 -v ./models:/app/models lightonocr-service
```

### Supported Formats
- Images: PNG, JPEG, WebP, TIFF
- PDFs: Both text-based and scanned (image-based)

### Scanned PDF Detection
When `OCR_FALLBACK_SCANNED=true`, SENTINEL automatically detects scanned PDFs (documents where Tika extracts minimal text) and routes them through LightOnOCR for visual text extraction.

## Feature flags (selected)
- RAGPART_ENABLED
- HGMEM_ENABLED
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
