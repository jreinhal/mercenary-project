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

## Bootstrap (STANDARD profile)
- SENTINEL_BOOTSTRAP_ENABLED=true|false
- SENTINEL_ADMIN_PASSWORD=<password>

## Performance tuning
- RAG_CORE_THREADS
- RAG_MAX_THREADS
- RAG_QUEUE_CAPACITY
- RERANKER_THREADS
- RAG_FUTURE_TIMEOUT_SECONDS

## Prompt and context limits
- RAG_MAX_CONTEXT_CHARS
- RAG_MAX_DOC_CHARS
- RAG_MAX_VISUAL_CHARS
- RAG_MAX_OVERVIEW_CHARS
- RAG_MAX_DOCS
- RAG_MAX_VISUAL_DOCS

## Tokenization vault
- APP_TOKENIZATION_SECRET_KEY (maps to app.tokenization.secret-key)

## Feature flags (selected)
- RAGPART_ENABLED
- HGMEM_ENABLED
- HIFIRAG_ENABLED
- QUCORAG_ENABLED
- MEGARAG_ENABLED
- MIARAG_ENABLED
- BIRAG_ENABLED
- HYBRIDRAG_ENABLED
- GRAPHO1_ENABLED
- ADAPTIVERAG_ENABLED
- CRAG_ENABLED
- HYDE_ENABLED
- SELFRAG_ENABLED
- AGENTIC_ENABLED

For full detail, see src/main/resources/application.yaml.
