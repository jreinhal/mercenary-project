# Architecture

This document summarizes the runtime architecture and key modules.

## High-level flow

Client
  -> SecurityFilter + RateLimitFilter
  -> Controllers (MercenaryController, AdminController, AuditController)
  -> RagOrchestrationService
  -> RAG services (HybridRAG, MiARAG, MegaRAG, etc)
  -> VectorStore (LocalMongoVectorStore by default; Atlas when configured)
  -> Ollama (LLM + embeddings)
  -> Response + audit

## Major modules
- controller: HTTP endpoints and access checks
- service: orchestration, ingestion, audit, guardrails
- rag: RAG pipelines and routing services
- vector: LocalMongoVectorStore and embedding logic
- professional/medical/government: edition-specific features

## Data stores
- MongoDB collections:
  - vector_store (embeddings + metadata)
  - audit_log
  - chat_logs, feedback, users, sessions (edition-dependent)

## Security layers
- Auth mode selection via profile (DEV, STANDARD, OIDC, CAC)
- Role and clearance checks per request
- Audit logging for access, queries, and ingestion

## UI
- Single-page dashboard in src/main/resources/static
- In-app manual and README served as static files
