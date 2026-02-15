# Air-gap and SCIF Deployment

This guide is for disconnected environments where all dependencies must be local.

## Goals
- No external network access
- Local MongoDB and Ollama
- Models staged locally

## Model staging
On a connected machine:
```
ollama pull llama3.1:8b
ollama pull bge-m3
```
Copy the Ollama model directory to the SCIF system:
- Windows: %USERPROFILE%\.ollama
- Linux: ~/.ollama

## Environment variables
Recommended settings:
```
APP_PROFILE=govcloud
AUTH_MODE=CAC
MONGODB_URI=mongodb://localhost:27017/mercenary
OLLAMA_URL=http://localhost:11434
LLM_MODEL=llama3.1:8b
EMBEDDING_MODEL=bge-m3
QUCORAG_INFINI_GRAM=false
```

Note: AUTH_MODE is optional when APP_PROFILE=govcloud, but set it explicitly if you override profiles.

## Hardening checklist
- Bind MongoDB to localhost only
- Disable outbound network egress
- Use local JWKS or CAC as required
- Keep Swagger disabled in production profiles

## Verification
- http://localhost:8080 loads the UI
- http://localhost:8080/api/health returns SYSTEMS NOMINAL
- http://localhost:8080/api/status returns ONLINE for vectorDb
