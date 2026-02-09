# Compliance Appendices

This appendix summarizes how SENTINEL supports common compliance frameworks. It is not legal advice or a certification.

## Enterprise (ENTERPRISE)
Common frameworks:
- SOC 2
- ISO 27001/27002

Recommended configuration:
- APP_PROFILE=enterprise
- AUTH_MODE=OIDC
- Centralized logging and retention policy for audit logs
- Strict least-privilege roles (VIEWER/ANALYST/ADMIN)

Notes:
- Use OIDC with your IdP for SSO and offboarding controls.
- Ensure backups and restores are tested and documented.

## Government / SCIF (GOVERNMENT)
Common frameworks:
- NIST SP 800-53
- DISA STIG
- FIPS 201 (PIV/CAC)

Recommended configuration:
- APP_PROFILE=govcloud
- AUTH_MODE=CAC
- Air-gap deployment (no external network access)
- CAC auto-provisioning disabled by default

Notes:
- Use local JWKS or CAC and keep all services on localhost or a protected enclave.
- Keep Swagger disabled in production profiles.
- Government edition enforces strict citations and evidence excerpts for auditability.

## Medical (MEDICAL)
Common frameworks:
- HIPAA
- HITECH (if applicable)

Recommended configuration:
- APP_PROFILE=enterprise or govcloud
- AUTH_MODE=OIDC or CAC
- Enable audit logging and retention policies
- Enable PII/PHI redaction

Notes:
- PHI access requires appropriate role assignments and clearance.
- Store logs and backups according to your retention policy.
- Medical edition enforces strict citations and evidence excerpts; summaries must remain within redaction policy.

## Disclaimer
SENTINEL provides technical controls that support these frameworks. Certification and compliance depend on your organization’s policies, procedures, and audits.
