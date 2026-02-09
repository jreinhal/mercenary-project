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
| enterprise | ENTERPRISE, MEDICAL, GOVERNMENT | OIDC/SSO (JWT) | Production with SSO |
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

SENTINEL validates license keys using HMAC-SHA256 signature verification.

**Behavior matrix:**

| License Key | Signing Secret | Result |
|------------|---------------|--------|
| Not configured | Not configured | Unlicensed mode (valid, all features available) |
| Configured | Not configured | Invalid (cannot verify signature) |
| Not configured | Configured | Invalid (no key to validate) |
| Configured | Configured | Full HMAC validation (edition, expiry, customer ID) |

Configuration:
- `LICENSE_SIGNING_SECRET`: HMAC signing secret for license key validation
- `sentinel.license.key`: License key in format `BASE64(edition:expiry:customerId):HMAC_HEX`

The license filter returns HTTP 402 when the license is invalid.

## Connector Security

### SSRF Protection

All connectors validate external endpoints to prevent Server-Side Request Forgery (SSRF):

| Connector | Protection | Trusted Domains |
|-----------|-----------|-----------------|
| SharePoint | Domain allowlist + HTTPS + no-redirect | `.sharepoint.com`, `.sharepoint.cn`, `.sharepoint-df.com`, `.svc.ms` |
| Confluence | Domain allowlist + HTTPS + no-redirect | `.atlassian.net`, `.atlassian.com`, `.jira.com` |
| S3 | Domain allowlist + HTTPS + private IP blocking | `.amazonaws.com`, `.amazonaws.com.cn`, `.r2.cloudflarestorage.com`, `.digitaloceanspaces.com`, `.backblazeb2.com` |

**S3 custom endpoints**: For self-hosted S3-compatible storage (MinIO, etc.), add trusted domains via `SENTINEL_S3_ALLOWED_DOMAINS`.

**Private IP blocking**: S3 endpoints are checked against private, loopback, link-local, and cloud metadata service IP ranges to prevent internal network access.

### Connector Credential Management

Connector credentials (API tokens, access keys) are configured via environment variables and are never logged or exposed through the API. Recommended practices:

- Rotate credentials regularly (at minimum every 90 days)
- Use least-privilege service accounts for each connector
- For production deployments, consider a secret manager (HashiCorp Vault, AWS Secrets Manager, Azure Key Vault) to inject credentials at runtime
- Monitor connector audit logs for unexpected access patterns

### Scheduled Sync

Connectors support scheduled automatic synchronization:
- `CONNECTOR_SYNC_ENABLED`: Enable/disable (default: false)
- `CONNECTOR_SYNC_CRON`: Cron expression (default: `0 0 2 * * ?` = 2 AM daily)

When enabled, all configured connectors sync on the specified schedule with summary logging.

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

## Related Guidance

- Enterprise tuning: ENTERPRISE_TUNING.md
- Compliance mapping by sector: COMPLIANCE_APPENDICES.md
- Host hardening for Linux deployments: HARDENING_LINUX.md
- Medical deployment checklist: MEDICAL_DEPLOYMENT_CHECKLIST.md
- Medical go-live runbook: MEDICAL_GO_LIVE_RUNBOOK.md
