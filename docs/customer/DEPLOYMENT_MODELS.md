# Deployment Models

This document provides reference deployments and recommended settings.

## 1) Developer workstation (local)
Use this for development and testing only.

- APP_PROFILE=dev
- AUTH_MODE=DEV
- MongoDB and Ollama local

PowerShell:
```
$env:APP_PROFILE="dev"
$env:AUTH_MODE="DEV"
$env:MONGODB_URI="mongodb://localhost:27017/mercenary"
$env:OLLAMA_URL="http://localhost:11434"
.\gradlew bootRun
```

## 2) Single-node production (standard/enterprise)
Use this for internal pilots and customer installs with SSO.

- APP_PROFILE=standard or enterprise
- AUTH_MODE=STANDARD or OIDC
- MongoDB local or dedicated host
- Ollama local

Example (enterprise):
```
APP_PROFILE=enterprise
AUTH_MODE=OIDC
MONGODB_URI=mongodb://<user>:<pass>@<host>:27017/mercenary
OLLAMA_URL=http://localhost:11434
OIDC_ISSUER=<issuer>
OIDC_CLIENT_ID=<client-id>
```

Standard bootstrap for first admin:
```
SENTINEL_BOOTSTRAP_ENABLED=true
SENTINEL_ADMIN_PASSWORD=<value>
```

## 3) Air-gap / SCIF (govcloud)
Use this for disconnected environments.

- APP_PROFILE=govcloud
- AUTH_MODE=CAC
- MongoDB local
- Ollama local
- No external network access

Reference:
```
APP_PROFILE=govcloud
AUTH_MODE=CAC
MONGODB_URI=mongodb://localhost:27017/mercenary
OLLAMA_URL=http://localhost:11434
QUCORAG_INFINI_GRAM=false
```

See AIRGAP_SCIF.md for a full checklist.
