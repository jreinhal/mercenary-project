# Compliance Appendices

This appendix summarizes how SENTINEL supports common compliance frameworks. It is not legal advice or a certification.

## Finance (FINANCE)
Common frameworks:
- PCI-DSS (if payment data is in scope)
- SOX
- GLBA
- FFIEC guidance

Recommended configuration:
- APP_PROFILE=enterprise or standard
- AUTH_MODE=OIDC or STANDARD
- Enable audit logging and review `/api/audit/*`
- Keep MongoDB bound to localhost or a private subnet
- Use TLS for all external connections

Notes:
- PII/PHI redaction can be enabled to reduce exposure of sensitive fields during ingestion.
- Sector isolation limits cross-department retrieval.

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

## Research / Academic (ACADEMIC)
Common frameworks:
- IRB policies (human subject data)
- Data management plan requirements

Recommended configuration:
- APP_PROFILE=standard or enterprise
- AUTH_MODE=STANDARD or OIDC
- Use sector isolation for research domains

Notes:
- Restrict access to sensitive datasets and log all access for auditability.
- For export-controlled data, use the GOVERNMENT or FINANCE sector depending on policy.

## Disclaimer
SENTINEL provides technical controls that support these frameworks. Certification and compliance depend on your organization’s policies, procedures, and audits.
