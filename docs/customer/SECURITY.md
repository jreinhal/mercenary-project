# Security

This document describes authentication modes, roles, access controls, and security hardening features.

## Key Terminology

**Edition** and **Profile** are independent concepts:

| Concept | What It Controls | Values |
|---------|-----------------|--------|
| **Edition** | What you license — compile-time feature inclusion | TRIAL, ENTERPRISE, MEDICAL, GOVERNMENT |
| **Profile** | How you deploy — runtime auth mode and security posture | dev, standard, enterprise, govcloud |

They are independent: you can run the ENTERPRISE edition with the `standard` profile (username/password auth) or with the `enterprise` profile (OIDC/SSO auth). The edition determines which features are available; the profile determines how users authenticate.

## Profiles and Auth Modes

SENTINEL uses Spring profiles to select the authentication mode:
- APP_PROFILE=dev -> auth mode DEV
- APP_PROFILE=standard -> auth mode STANDARD
- APP_PROFILE=enterprise -> auth mode OIDC
- APP_PROFILE=govcloud -> auth mode CAC

You can override with AUTH_MODE if needed (DEV, STANDARD, OIDC, CAC).

### Profile/Edition Compatibility Matrix

| Profile | Compatible Editions | Auth Method | Use Case |
|---------|-------------------|-------------|----------|
| dev | Any | None (auto-login) | Development and testing |
| standard | Any | Username/password | Internal pilots, small teams |
| enterprise | TRIAL, ENTERPRISE, MEDICAL, GOVERNMENT | OIDC/SSO (JWT) | Production with SSO |
| govcloud | GOVERNMENT | CAC/PIV (X.509) | Air-gapped/SCIF deployments |

## Auth Modes

- DEV: Development only. Auto-provisions a demo user with ADMIN and TOP_SECRET. Do not use in production.
- STANDARD: Local users with sessions. Optional HTTP Basic if app.standard.allow-basic=true (env: APP_STANDARD_ALLOW_BASIC).
- OIDC: JWT-based via an external provider (Azure AD, Okta). Uses OIDC_* settings.
- CAC: X.509 client certificates. Requires HTTPS and CAC/PIV infrastructure.

## OIDC Configuration (enterprise profile)

Required:
- OIDC_ISSUER
- OIDC_CLIENT_ID

Optional:
- OIDC_JWKS_URI
- OIDC_LOCAL_JWKS
- OIDC_JWKS_CACHE_TTL
- OIDC_ALGORITHMS
- OIDC_VALIDATE_ISSUER
- OIDC_VALIDATE_AUDIENCE
- OIDC_REQUIRE_APPROVAL
- OIDC_REQUIRE_MFA
- OIDC_MFA_CLAIMS
- OIDC_MFA_ACR

## CAC Configuration (govcloud profile)

- CAC_AUTO_PROVISION=false by default
- CAC_DEFAULT_ROLE and CAC_DEFAULT_CLEARANCE apply only if auto-provisioning is enabled

## Standard Auth Lockout

- AUTH_LOCKOUT_ENABLED (default true)
- AUTH_LOCKOUT_MAX_ATTEMPTS (default 5)
- AUTH_LOCKOUT_WINDOW_MINUTES (default 15)
- AUTH_LOCKOUT_DURATION_MINUTES (default 15)

## Roles and Permissions

Roles are defined in UserRole:
- ADMIN: QUERY, INGEST, DELETE, MANAGE_USERS, VIEW_AUDIT, CONFIGURE
- ANALYST: QUERY, INGEST
- VIEWER: QUERY
- AUDITOR: QUERY, VIEW_AUDIT
- PHI_ACCESS: QUERY (medical access control gate)

## Clearance Levels

- UNCLASSIFIED
- CUI
- SECRET
- TOP_SECRET
- SCI

Users must meet or exceed a sector's required clearance.

## Sectors (Departments)

- GOVERNMENT
- MEDICAL
- ENTERPRISE

## License Validation

SENTINEL evaluates license information using the configured license key.

**Behavior matrix:**

| License Key (`sentinel.license.key`) | Result |
|--------------------------------------|--------|
| Not configured or blank | Unlicensed mode (application runs with default features) |
| Configured, valid, not expired | Licensed mode (features gated by edition and expiry) |
| Configured, expired | Invalid (HTTP 402) |

Configuration:
- `sentinel.license.key`: License key containing edition, expiry, and customer ID information

The license filter returns HTTP 402 when `licenseService.isValid()` returns false (e.g., when a license has expired).

## Connector Security

### SSRF Protection

SharePoint and Confluence connectors validate external endpoints to prevent Server-Side Request Forgery (SSRF):

| Connector | Protection | Trusted Domains |
|-----------|-----------|-----------------|
| SharePoint | Domain allowlist + HTTPS + no-redirect | `.sharepoint.com`, `.sharepoint.cn`, `.sharepoint-df.com`, `.svc.ms` |
| Confluence | Domain allowlist + HTTPS + no-redirect | `.atlassian.net`, `.atlassian.com`, `.jira.com` |
| S3 | Custom endpoint via `sentinel.connectors.s3.endpoint` | Not domain-validated |

**S3 custom endpoints**: Self-hosted S3-compatible storage (MinIO, etc.) is supported via the `sentinel.connectors.s3.endpoint` setting. Exercise caution when configuring this value, as endpoint validation should be applied at the network level.

### Connector Credential Management

Connector credentials (API tokens, access keys) are configured via environment variables and are never logged or exposed through the API. Recommended practices:

- Rotate credentials regularly (at minimum every 90 days)
- Use least-privilege service accounts for each connector
- For production deployments, consider a secret manager (HashiCorp Vault, AWS Secrets Manager, Azure Key Vault) to inject credentials at runtime
- Monitor connector audit logs for unexpected access patterns

### Connector Sync

Connectors are synchronized via an authenticated admin API endpoint:

- `POST /api/admin/connectors/sync`: Triggers a sync for all configured connectors and writes summary logs.

To run syncs on a schedule, invoke this endpoint from your scheduling system (e.g., cron, Kubernetes CronJob) using appropriate admin credentials.

## Audit Logging

Security-relevant events are written to MongoDB (audit_log). Audit access requires VIEW_AUDIT.

Endpoints:
- /api/audit/events
- /api/audit/stats
- /api/hipaa/audit/events
- /api/hipaa/audit/export

## Additional Security Controls

- Guardrails: app.guardrails.enabled
- Fail-closed audit in govcloud
- Correlation IDs included in logs (%X{correlationId})
- PII/PHI automatic redaction in medical and government sectors
- Prompt injection detection and blocking
- Magic byte file type validation (blocks executables regardless of extension)

## RAG-Specific Security Controls

### Corpus poisoning defense (RAGPart)

SENTINEL includes retrieval-time corpus poisoning defense through RAGPart before generation paths are finalized.

RAGPart configuration keys:
- `RAGPART_ENABLED`
- `RAGPART_PARTITIONS`
- `RAGPART_COMBINATION_SIZE`
- `RAGPART_SUSPICION_THRESHOLD`

Operational intent:
- increase partitioning and lower suspicion thresholds for stricter environments
- validate thresholds against your benchmark corpus before production rollout

### Hallucination risk and abstention control

SENTINEL applies uncertainty/hallucination risk checks in the query orchestration flow. High-risk responses can be suppressed in favor of abstention-style fallback (`No relevant records found.`) instead of returning low-confidence synthesis.

### Evidence and source verification controls

- citation-aware response policies are edition-sensitive (stricter in regulated editions)
- `/api/source/page` and `/api/source/region` enforce authentication, sector authorization, and source-retention policy checks before returning any image data
- HIPAA strict deployments should keep source retention conservative by default (`SOURCE_RETENTION_ALLOW_HIPAA_STRICT=false`)

### Multi-tenant isolation and fail-closed filtering

- retrieval paths enforce workspace and sector constraints
- access denials are audited
- fail-closed prefiltering is applied for invalid/empty scope inputs to prevent accidental cross-tenant widening

## RAG Threat Model Additions

Security reviews should explicitly include:

- corpus poisoning via maliciously crafted documents
- prompt injection delivered through ingested content
- cross-workspace data leakage from weak filter composition
- sensitive visual evidence retention leakage (especially in regulated sectors)

Recommended mitigations:
- enable RAGPart and validate thresholds
- keep prompt-injection and guardrail enforcement enabled
- run cross-workspace authorization tests for query, reasoning, and source-render endpoints
- document source-retention policy exceptions and approvals

## NIST SP 800-53 Mapping Notes (RAG Controls)

- **SI-10 (Information Input Validation)**: ingestion checks, file-type validation, prompt-injection screening, RAGPart suspicious-content filtering
- **SI-4 (System Monitoring)**: audit logging of denied access, prompt-injection blocks, and security-relevant query behavior

## Security Testing Procedures (RAG)

At minimum, validate:

- poisoning-defense regression scenarios (malicious retrieval candidates are filtered)
- injection blocking in query and document-driven contexts
- workspace/sector isolation for `/api/ask*`, `/api/reasoning/*`, `/api/source/*`
- HIPAA strict behavior for source rendering when source retention is disabled
- citation/evidence fallback behavior under low-confidence retrieval conditions

## Related Guidance

- Enterprise tuning: ENTERPRISE_TUNING.md
- Compliance mapping by sector: COMPLIANCE_APPENDICES.md
- Host hardening for Linux deployments: HARDENING_LINUX.md
- Medical deployment checklist: MEDICAL_DEPLOYMENT_CHECKLIST.md
- Medical go-live runbook: MEDICAL_GO_LIVE_RUNBOOK.md
