# SENTINEL // INTELLIGENCE PLATFORM

Secure, air-gap compatible RAG platform for regulated environments.

---

## Quick start (local)

Prerequisites:
- Java 21
- MongoDB
- Ollama

PowerShell:
```
$env:APP_PROFILE="dev"
$env:MONGODB_URI="mongodb://localhost:27017/mercenary"
$env:OLLAMA_URL="http://localhost:11434"
$env:LLM_MODEL="llama3.1:8b"
$env:EMBEDDING_MODEL="bge-m3"
.\gradlew bootRun
```

---

## Profiles and auth

| APP_PROFILE | Auth Mode | Use Case |
|-------------|-----------|----------|
| dev | DEV | Local development only |
| standard | STANDARD | Local users and sessions |
| enterprise | OIDC | SSO (JWT) |
| govcloud | CAC | CAC/PIV (X.509) |

You can override with AUTH_MODE if needed.

---

## Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| APP_PROFILE | dev | Spring profile |
| AUTH_MODE | DEV | Auth mode override |
| MONGODB_URI | mongodb://localhost:27017/mercenary | MongoDB connection |
| OLLAMA_URL | http://localhost:11434 | Ollama endpoint |
| LLM_MODEL | llama3.1:8b | Chat model |
| EMBEDDING_MODEL | bge-m3 | Embedding model |
| SWAGGER_ENABLED | false | Swagger UI toggle |

---

## Core endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| /api/ask | GET/POST | Query the system |
| /api/ask/enhanced | GET/POST | Query with reasoning trace |
| /api/ask/stream | GET | SSE streaming responses |
| /api/ingest/file | POST | Upload documents |
| /api/inspect | GET | View document content |
| /api/health | GET | Health check |
| /api/status | GET | Status summary |
| /api/telemetry | GET | Telemetry summary |

---

## Documentation

- Operator manual: /manual.html
- Repository docs: docs/README.md

---

## Security notes

- DEV mode auto-provisions an admin user for development only
- Do not expose MongoDB or Ollama ports publicly

---

SENTINEL Intelligence Platform v2.2.0
