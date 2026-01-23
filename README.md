# SENTINEL Intelligence Platform

Secure, air-gap compatible RAG platform for enterprise and government deployments.

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
- professional
- medical
- government

Build:
```
./gradlew build -Pedition=government
```

## Documentation
- docs/README.md
- In-app manual: /manual.html

## Security notes
- DEV mode auto-provisions admin access for development only
- Do not expose MongoDB or Ollama ports publicly

## License
Proprietary - All rights reserved.
