# Installation

This guide covers standard deployments. For disconnected or SCIF environments, see AIRGAP_SCIF.md.

## Prerequisites
- Java 21 (LTS)
- MongoDB 7.x or 8.x
- Ollama for local inference
- Optional: Docker and Docker Compose

## Default ports
- 8080: application
- 27017: MongoDB
- 11434: Ollama

## Quick start (local)
1) Start MongoDB and Ollama
2) Set environment variables
3) Run the app

PowerShell:
```
$env:APP_PROFILE="dev"
$env:MONGODB_URI="mongodb://localhost:27017/mercenary"
$env:OLLAMA_URL="http://localhost:11434"
$env:LLM_MODEL="llama3.1:8b"
$env:EMBEDDING_MODEL="nomic-embed-text"
.\gradlew bootRun
```

Bash:
```
export APP_PROFILE=dev
export MONGODB_URI=mongodb://localhost:27017/mercenary
export OLLAMA_URL=http://localhost:11434
export LLM_MODEL=llama3.1:8b
export EMBEDDING_MODEL=nomic-embed-text
./gradlew bootRun
```

You can override the auth mode with AUTH_MODE if needed (DEV, STANDARD, OIDC, CAC).

## Editions
Build a specific edition with the Gradle property:
```
./gradlew build -Pedition=trial
./gradlew build -Pedition=enterprise
./gradlew build -Pedition=medical
./gradlew build -Pedition=government
```

Run a specific edition:
```
./gradlew -Pedition=enterprise bootRun
```

## Standard auth bootstrap
STANDARD profile does not ship with a default password. If you need a local admin user:

- Enable bootstrap: SENTINEL_BOOTSTRAP_ENABLED=true
- Set password: SENTINEL_ADMIN_PASSWORD=<value>

These map to:
- sentinel.bootstrap.enabled
- sentinel.bootstrap.admin-password

## Docker (optional)
```
docker-compose up -d
docker-compose -f docker-compose.prod.yml up -d
```

## Next steps
- Configure authentication: SECURITY.md
- Configure MongoDB auth: MONGODB_WINDOWS.md (Windows) or MONGODB_LINUX.md (Linux)
- Apply Linux hardening (Linux deployments): HARDENING_LINUX.md
- Review compliance expectations by sector: COMPLIANCE_APPENDICES.md
- Review operations and monitoring: OPERATIONS.md
- Review deployment models: DEPLOYMENT_MODELS.md
