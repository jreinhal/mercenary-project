# Security

This document describes authentication modes, roles, and access controls.

## Profiles and auth modes
SENTINEL uses Spring profiles to select the authentication mode:
- APP_PROFILE=dev -> auth mode DEV
- APP_PROFILE=standard -> auth mode STANDARD
- APP_PROFILE=enterprise -> auth mode OIDC
- APP_PROFILE=govcloud -> auth mode CAC

You can override with AUTH_MODE if needed (DEV, STANDARD, OIDC, CAC).

## Auth modes
- DEV: Development only. Auto-provisions a demo user with ADMIN and TOP_SECRET. Do not use in production.
- STANDARD: Local users with sessions. Optional HTTP Basic if app.standard.allow-basic=true (env: APP_STANDARD_ALLOW_BASIC).
- OIDC: JWT-based via an external provider (Azure AD, Okta). Uses OIDC_* settings.
- CAC: X.509 client certificates. Requires HTTPS and CAC/PIV infrastructure.

## OIDC configuration (enterprise profile)
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

## CAC configuration (govcloud profile)
- CAC_AUTO_PROVISION=false by default
- CAC_DEFAULT_ROLE and CAC_DEFAULT_CLEARANCE apply only if auto-provisioning is enabled

## Standard auth lockout
- AUTH_LOCKOUT_ENABLED (default true)
- AUTH_LOCKOUT_MAX_ATTEMPTS (default 5)
- AUTH_LOCKOUT_WINDOW_MINUTES (default 15)
- AUTH_LOCKOUT_DURATION_MINUTES (default 15)

## Roles and permissions
Roles are defined in UserRole:
- ADMIN: QUERY, INGEST, DELETE, MANAGE_USERS, VIEW_AUDIT, CONFIGURE
- ANALYST: QUERY, INGEST
- VIEWER: QUERY
- AUDITOR: QUERY, VIEW_AUDIT
- PHI_ACCESS: QUERY (medical access control gate)

## Clearance levels
- UNCLASSIFIED
- CUI
- SECRET
- TOP_SECRET
- SCI

Users must meet or exceed a sector's required clearance.

## Sectors (departments)
- GOVERNMENT
- MEDICAL
- ENTERPRISE

## Audit logging
Security-relevant events are written to MongoDB (audit_log). Audit access requires VIEW_AUDIT.
Endpoints:
- /api/audit/events
- /api/audit/stats
- /api/hipaa/audit/events
- /api/hipaa/audit/export

## Additional security controls
- Guardrails: app.guardrails.enabled
- Fail-closed audit in govcloud
- Correlation IDs included in logs (%X{correlationId})

## Related guidance
- Compliance mapping by sector: COMPLIANCE_APPENDICES.md
- Host hardening for Linux deployments: HARDENING_LINUX.md
- Medical deployment checklist: MEDICAL_DEPLOYMENT_CHECKLIST.md
- Medical go-live runbook: MEDICAL_GO_LIVE_RUNBOOK.md
