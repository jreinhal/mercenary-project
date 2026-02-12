# API Reference (Customer)

Base path: `/api`

## Auth and Scope

- Most `/api/**` endpoints require authentication.
- Query and ingestion endpoints are also permission-gated (`QUERY`, `INGEST`, admin roles).
- `dept` values: `GOVERNMENT`, `MEDICAL`, `ENTERPRISE`.
- Legacy aliases are accepted for compatibility: `FINANCE` -> `ENTERPRISE`, `ACADEMIC` -> `ENTERPRISE`.

## Query APIs

### `GET|POST /api/ask`

Primary query endpoint. Returns plain text.

Required query params:
- `q`: user query
- `dept`: sector/department

Optional query params:
- `file`: repeatable active-file filter
- `files`: comma-separated active-file filter

Compatibility query params (currently ignored by server logic):
- `modalityHint`: not interpreted by `/api/ask`
- `granularityHint`: not interpreted by `/api/ask`
- `requireVerification`: not interpreted by `/api/ask`

Response:
- `200 text/plain` (answer text)
- Error/denial cases also return text messages (for example `ACCESS DENIED`, `INVALID SECTOR`, `SECURITY ALERT`)

Contract notes:
- `/api/ask` is a plain-text endpoint and does not return top-level structured fields like `confidence`, `verificationDetails`, `groundingScore`, or `abstentionReason`.
- For structured telemetry, use `/api/ask/enhanced`.

### `GET|POST /api/ask/enhanced`

Enhanced query endpoint with reasoning trace and metrics.

Required query params:
- `q`: user query
- `dept`: sector/department

Optional query params:
- `file`: repeatable active-file filter
- `files`: comma-separated active-file filter
- `sessionId`: session context id
- `deepAnalysis`: `true|false` (default `false`)
- `useHyde`: retrieval override hint
- `useGraphRag`: retrieval override hint
- `useReranking`: retrieval override hint

Compatibility query params (currently ignored by server logic):
- `modalityHint`: not interpreted by `/api/ask/enhanced`
- `granularityHint`: not interpreted by `/api/ask/enhanced`
- `requireVerification`: not interpreted by `/api/ask/enhanced`

Response (`application/json`):

```json
{
  "answer": "string",
  "reasoning": [
    {
      "type": "query_analysis",
      "label": "Query Analysis",
      "detail": "Single query detected"
    }
  ],
  "sources": [
    "file1.pdf",
    "file2.md"
  ],
  "metrics": {
    "latencyMs": 412,
    "documentsRetrieved": 8,
    "visualDocuments": 1,
    "subQueries": 1,
    "llmSuccess": true,
    "routingDecision": "COMPLEX",
    "routingConfidence": 0.84,
    "citationCount": 4,
    "answerable": true
  },
  "traceId": "trace-uuid",
  "sessionId": null
}
```

`metrics` is an extensible map. Common keys include:
- `latencyMs`, `documentsRetrieved`, `visualDocuments`, `subQueries`
- `llmSuccess`, `routingDecision`, `routingConfidence`
- `activeFileCount`, `retrievalStrategies`
- `citationCount`, `answerable`, `answerabilityGate`
- `citationRescue`, `excerptFallbackApplied`
- `editionPolicy`, `editionMaxTokens`, `editionEnforceCitations`

`reasoning` entries are emitted with lowercase `type` values plus `label`, `detail`, and `durationMs`.

Field mapping notes:
- `confidence`: represented as `metrics.routingConfidence` (routing confidence, not a global factuality score).
- `verificationDetails`: no dedicated object; verification-related signals are exposed via metrics (for example `answerabilityGate`, `evidenceMatch`, `citationRescue`).
- `groundingScore`: not currently exposed as a dedicated API field.
- `abstentionReason`: not currently exposed as a dedicated API field; abstentions are conveyed in the `answer` text.

### Multimodal query examples

Enhanced query focused on visual evidence:

```
GET /api/ask/enhanced?q=Summarize+the+chart+trend+in+q4_report.pdf&dept=ENTERPRISE&file=q4_report.pdf
```

Render cited page or region for verification:

```
GET /api/source/page?fileName=q4_report.pdf&dept=ENTERPRISE&page=3
GET /api/source/region?fileName=q4_report.pdf&dept=ENTERPRISE&page=3&x=420&y=260&width=620&height=280
```

### `GET /api/ask/stream`

SSE endpoint for streaming steps and token output.

Required query params:
- `q`: user query
- `dept`: sector/department

Optional query params:
- `file`: repeatable active-file filter
- `files`: comma-separated active-file filter
- `sessionId`: session context id
- `deepAnalysis`: `true|false` (default `false`)

SSE event names:
- `connected`: stream established
- `step`: pipeline step update
- `token`: incremental token output
- `complete`: final answer payload
- `error`: terminal error payload

Example `complete` payload:

```json
{
  "answer": "final synthesized answer",
  "sources": ["file1.pdf", "file2.csv"],
  "citationCount": 3
}
```

## Source Evidence APIs

### `GET /api/source/page`

Render a source PDF page as PNG.

Required query params:
- `fileName`
- `dept`

Optional query params:
- `page` (default `1`, must be `>= 1`)

Response:
- `200 image/png` with headers:
  - `X-Page-Number`
  - `X-Page-Count`
- `404 text/plain` if source bytes are unavailable by policy/retention settings

### `GET /api/source/region`

Render a cropped region of a source PDF page as PNG.

Required query params:
- `fileName`
- `dept`
- `x`, `y`, `width`, `height`

Optional query params:
- `page` (default `1`, must be `>= 1`)
- `expandAbove` (default `150`)
- `expandBelow` (default `150`)

Response:
- `200 image/png` with headers:
  - `X-Page-Number`
  - `X-Page-Count`
- `404 text/plain` if source bytes are unavailable by policy/retention settings

## Ingestion

### `POST /api/ingest/file` (multipart/form-data)

Form fields:
- `file`: uploaded document
- `dept`: target sector

Response:
- `200 text/plain` success message
- `400/401/403/500 text/plain` for validation/access/failure paths

## Document Inspection and Reasoning

- `GET /api/inspect?fileName=<name>&dept=<sector>&query=<optional>`
  - PII-redacted response by default
  - Response fields: `content`, `highlights`, `redacted`, `redactionCount`
- `GET /api/reasoning/{traceId}`
  - Returns reasoning trace map for the requesting user/workspace

## Sessions

Base path: `/api/sessions`

- `POST /create?department=<optional>`
- `GET /{sessionId}`
- `GET /`
- `POST /{sessionId}/touch?department=<optional>`
- `DELETE /{sessionId}/history`
- `GET /{sessionId}/context`
- `GET /{sessionId}/traces`
- `GET /traces/{traceId}`
- `GET /{sessionId}/export`
- `POST /{sessionId}/export/file`
- `GET /stats`

## Entity Explorer (HyperGraph Memory)

Base path: `/api/graph`

- `GET /entities?dept=<sector>&limit=100&type=<entityType>`
  - Optional `type`: `PERSON`, `ORGANIZATION`, `LOCATION`, `TECHNOLOGY`, `DATE`
- `GET /neighbors?nodeId=<id>&dept=<sector>`
- `GET /search?q=<query>&dept=<sector>&limit=20`
- `GET /stats?dept=<sector>`
- `GET /edges?dept=<sector>&limit=<n>`

## Audit

- `GET /api/audit/events?limit=100`
- `GET /api/audit/stats`
- `GET /api/hipaa/audit/events?limit=200&since=<ISO-8601>&until=<ISO-8601>&type=<eventType>`
- `GET /api/hipaa/audit/export?format=json|csv&since=<ISO-8601>&until=<ISO-8601>&type=<eventType>`

## System

- `GET /api/health`
- `GET /api/status`
- `GET /api/telemetry`
- `GET /api/user/context`
- `GET /api/config/sectors`
- `GET /api/config/current-sector`

Configuration API notes:
- The config endpoints expose sector availability/current-sector context.
- There is no public endpoint that returns the full runtime `sentinel.*` configuration map.

## Admin (ADMIN role)

- `GET /api/admin/users`
- `GET /api/admin/users/pending`
- `POST /api/admin/users/{userId}/approve`
- `POST /api/admin/users/{userId}/activate`
- `POST /api/admin/users/{userId}/deactivate`
- `PUT /api/admin/users/{userId}/roles`
- `GET /api/admin/stats/usage`
- `GET /api/admin/stats/documents`
- `GET /api/admin/health`
- `GET /api/admin/dashboard`
- `POST /api/admin/demo/load`
- `GET /api/admin/connectors/status`
- `POST /api/admin/connectors/sync`

## Swagger UI

- `/swagger-ui.html` is enabled only in `dev` profile by default.
