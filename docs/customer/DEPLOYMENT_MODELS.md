# Deployment Models

This document provides reference deployments and recommended settings.

## Edition vs Profile

SENTINEL has two independent configuration axes:

**Edition** (what you license):
| Edition | Packages Included | Target |
|---------|------------------|--------|
| TRIAL | core + enterprise | Evaluation (30-day limit) |
| ENTERPRISE | core + enterprise | Commercial customers |
| MEDICAL | core + enterprise + medical | HIPAA-compliant healthcare |
| GOVERNMENT | all packages | SCIF/air-gapped, CAC, clearance |

**Profile** (how you deploy):
| Profile | Auth Method | Security Posture | Use Case |
|---------|-------------|-----------------|----------|
| dev | Auto-login (no credentials) | Minimal | Development/testing |
| standard | Username/password | Standard | Internal pilots, small teams |
| enterprise | OIDC/SSO (JWT) | Enhanced | Production with IdP |
| govcloud | CAC/PIV (X.509) | Maximum | Air-gapped/SCIF |

These are independent. For example, a MEDICAL edition deployment might use the `standard` profile during initial setup, then switch to `enterprise` profile for production SSO.

## 1) Developer Workstation (local)

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

## 2) Single-node Production (standard/enterprise)

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

### Enterprise Performance Tuning

For high-concurrency deployments, set `APP_PROFILE=enterprise` to activate the enterprise profile (OIDC auth defaults). Performance tuning values must be set explicitly via environment variables:

Recommended production overrides:
- `RAG_CORE_THREADS=32` / `RAG_MAX_THREADS=64` / `RAG_QUEUE_CAPACITY=2000`
- `RERANKER_THREADS=16`
- Tomcat: configured via `server.tomcat.threads.max` and `server.tomcat.max-connections`

See ENTERPRISE_TUNING.md for detailed sizing guidance and the full list of tuning parameters.

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

## 4) Connector Configuration

External connectors (S3, SharePoint, Confluence) are available in all editions but disabled by default in MEDICAL and GOVERNMENT editions for compliance reasons.

To enable connectors in regulated editions:
```
SENTINEL_CONNECTORS_ALLOW_REGULATED=true
```

### S3 Custom Endpoints

For S3-compatible storage (MinIO, Ceph, etc.), set a custom endpoint:
```
SENTINEL_S3_ENDPOINT=https://minio.internal.corp.com
```

Exercise caution when configuring custom S3 endpoints. See SECURITY.md for SSRF protection details on other connectors (SharePoint, Confluence).

### Connector Sync

Connectors are synchronized via the admin API:
```
POST /api/admin/connectors/sync
```

To automate syncs, use an external scheduler (cron, Kubernetes CronJob) to call this endpoint with admin credentials on the desired schedule.
