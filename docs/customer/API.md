# API Reference (Customer)

Base path: /api

## Query
- GET or POST /api/ask?q=<query>&dept=<sector>
- GET or POST /api/ask/enhanced?q=<query>&dept=<sector>
- GET /api/ask/stream?q=<query>&dept=<sector>

Optional query parameters:
- file (repeatable) or files (comma-separated) for active file focus
- sessionId for session-aware retrieval
- deepAnalysis=true|false - Enable multi-hop entity graph traversal (slower but finds related documents through shared entities)

Sectors (dept): GOVERNMENT, MEDICAL, FINANCE, ACADEMIC, ENTERPRISE

## Ingestion
- POST /api/ingest/file (multipart)
  - file: uploaded document
  - dept: target sector

## Document inspection
- GET /api/inspect?fileName=<name>&dept=<sector>&query=<optional>
  - Response content is PII-redacted by default
  - Response fields: content, highlights, redacted, redactionCount

## Reasoning
- GET /api/reasoning/{traceId}

## System
- GET /api/health
- GET /api/status
- GET /api/telemetry
- GET /api/user/context

## Entity Explorer (HyperGraph Memory)
- GET /api/graph/entities?dept=<sector>&limit=100&type=<entityType>
  - Returns entity nodes for visualization
  - Optional type filter: PERSON, ORGANIZATION, LOCATION, TECHNOLOGY, DATE
- GET /api/graph/neighbors?nodeId=<id>&dept=<sector>
  - Returns connected nodes and edges for graph expansion
- GET /api/graph/search?q=<query>&dept=<sector>&limit=20
  - Search entities by name
- GET /api/graph/stats?dept=<sector>
  - Returns entity/edge counts and HGMem status

## Audit
- GET /api/audit/events?limit=100
- GET /api/audit/stats
- GET /api/hipaa/audit/events?limit=200&since=<ISO-8601>&until=<ISO-8601>&type=<PHI_ACCESS|PHI_QUERY|PHI_DISCLOSURE|...>
- GET /api/hipaa/audit/export?format=json|csv&since=<ISO-8601>&until=<ISO-8601>&type=<eventType>

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
- POST /api/admin/demo/load (loads synthetic demo dataset; disabled in regulated editions by default)
- GET /api/admin/connectors/status
- POST /api/admin/connectors/sync

## Case Collaboration (non-regulated editions)
- GET /api/cases
- POST /api/cases
- GET /api/cases/{caseId}
- POST /api/cases/{caseId}/share
- POST /api/cases/{caseId}/review
- POST /api/cases/{caseId}/review/decision

Swagger UI
- /swagger-ui.html is enabled only in dev profile by default
