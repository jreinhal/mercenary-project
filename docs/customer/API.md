# API Reference (Customer)

Base path: /api

## Query
- GET or POST /api/ask?q=<query>&dept=<sector>
- GET or POST /api/ask/enhanced?q=<query>&dept=<sector>
- GET /api/ask/stream?q=<query>&dept=<sector>

Optional query parameters:
- file (repeatable) or files (comma-separated) for active file focus
- sessionId for session-aware retrieval

Sectors (dept): GOVERNMENT, MEDICAL, FINANCE, ACADEMIC, ENTERPRISE

## Ingestion
- POST /api/ingest/file (multipart)
  - file: uploaded document
  - dept: target sector

## Document inspection
- GET /api/inspect?fileName=<name>&dept=<sector>&query=<optional>

## Reasoning
- GET /api/reasoning/{traceId}

## System
- GET /api/health
- GET /api/status
- GET /api/telemetry
- GET /api/user/context

## Audit
- GET /api/audit/events?limit=100
- GET /api/audit/stats

## Admin (ADMIN role)
- GET /api/admin/users
- GET /api/admin/users/pending
- POST /api/admin/users/{userId}/approve
- POST /api/admin/users/{userId}/activate
- POST /api/admin/users/{userId}/deactivate
- PUT /api/admin/users/{userId}/roles
- GET /api/admin/stats/usage
- GET /api/admin/stats/documents
- GET /api/admin/health
- GET /api/admin/dashboard

Swagger UI
- /swagger-ui.html is enabled only in dev profile by default
