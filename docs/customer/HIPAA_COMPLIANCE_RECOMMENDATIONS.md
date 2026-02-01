# HIPAA Compliance Recommendations (SENTINEL / mercenary)

Date: 2026-01-31
Reviewer: Codex (static code and docs review)

This document expands the prior assessment into (1) a HIPAA control-by-control gap analysis and (2) a hardened medical deployment checklist. It also notes which mitigations have been implemented in code as of the date above. Not legal advice.

## Executive summary
- Short answer: this codebase is not HIPAA-compliant out of the box. It includes supportive controls, but several high-risk gaps would block compliance unless remediated and paired with organizational safeguards.
- This is a static review of repository code/docs only; runtime configs, infrastructure, and policies are not validated.
- HIPAA compliance requires administrative, physical, and technical safeguards (45 CFR 164.308/310/312). This document focuses on software and deployment guidance.

## Implementation status (as of 2026-02-01)
The following remediations are implemented in code for HIPAA strict mode and medical edition hardening:
- HIPAA strict policy gating (sentinel.hipaa.*) with defaults for Medical edition.
- Redaction on responses and suppression of sensitive logs when strict mode is active.
- Disablement of session memory, session exports, and feedback persistence in strict mode.
- HIPAA query audit logging wired into the core RAG flow for medical workloads.
- Visual ingestion and experience learning disabled when strict mode is active.
- Expanded PII redaction patterns aligned with HIPAA Safe Harbor identifiers.
- Optional TLS enforcement (HSTS + HTTPS redirect) when HIPAA strict mode requires it.

The gaps and checklist below still apply and should be validated in deployment (TLS for external services, encryption at rest, key management, BAAs, and formal risk analysis).

## Scope and limitations
- Reviewed repository code and docs only. No runtime configuration, infrastructure, or policy review was performed.
- Administrative and physical safeguards are largely out of scope; they must be implemented by the deploying organization.
- Evidence references below point to the files where current behavior or configuration is defined.

## Sources reviewed (non-exhaustive)
- src/main/resources/application.yaml
- src/main/java/com/jreinhal/mercenary/config/SecurityConfig.java
- src/main/java/com/jreinhal/mercenary/filter/SecurityFilter.java
- src/main/java/com/jreinhal/mercenary/service/AuditService.java
- src/main/java/com/jreinhal/mercenary/medical/hipaa/HipaaAuditService.java
- src/main/java/com/jreinhal/mercenary/medical/controller/PiiRevealController.java
- src/main/java/com/jreinhal/mercenary/service/PiiRedactionService.java
- src/main/java/com/jreinhal/mercenary/medical/hipaa/PhiDetectionService.java
- src/main/java/com/jreinhal/mercenary/service/TokenizationVault.java
- src/main/java/com/jreinhal/mercenary/service/SecureIngestionService.java
- src/main/java/com/jreinhal/mercenary/service/RagOrchestrationService.java
- src/main/java/com/jreinhal/mercenary/professional/memory/ConversationMemoryService.java
- src/main/java/com/jreinhal/mercenary/professional/memory/SessionPersistenceService.java
- src/main/java/com/jreinhal/mercenary/controller/SessionController.java
- src/main/java/com/jreinhal/mercenary/service/FeedbackService.java
- src/main/java/com/jreinhal/mercenary/rag/birag/BidirectionalRagService.java
- docs/customer/SECURITY.md
- docs/customer/COMPLIANCE_APPENDICES.md
- docs/customer/DEPLOYMENT_MODELS.md
- docs/customer/MONGODB_WINDOWS.md
- docs/customer/MONGODB_LINUX.md
- docs/customer/OPERATIONS.md
- docs/customer/API.md

## Strengths present in the code
- Role-based auth with OIDC/CAC/standard modes, clearance/sector gating, and audited access denials (SecurityConfig.java, SecurityFilter.java, AuditService.java).
- Dedicated PHI/PII access workflow with break-the-glass logging (PiiRevealController.java, HipaaAuditService.java).
- PII redaction/tokenization applied during ingestion and inspection (SecureIngestionService.java, MercenaryController.java, PiiRedactionService.java).
- Security posture guidance and compliance disclaimers are documented (docs/customer/SECURITY.md, docs/customer/COMPLIANCE_APPENDICES.md).

## High-risk gaps (likely compliance blockers)
- PHI can be persisted and exported in clear text via conversation memory, reasoning traces, and session exports (ConversationMemoryService.java, SessionPersistenceService.java, SessionController.java).
- Feedback storage/export keeps raw queries and responses (potential PHI) and exposes them for training export (FeedbackService.java).
- De-identification is regex-based and incomplete vs HIPAA Safe Harbor; PHI detection is unused, and visual ingestion has no redaction pass (PiiRedactionService.java, PhiDetectionService.java, SecureIngestionService.java).
- Transmission security is not enforced in standard/enterprise profiles and defaults to HTTP for LLM/OCR; Mongo TLS is not documented, increasing risk of ePHI exposure in transit (SecurityConfig.java, application.yaml, DEPLOYMENT_MODELS.md, MONGODB_*.md).

## Medium-risk gaps
- HIPAA-specific audit logging is not wired to routine PHI queries/disclosures, and the HIPAA log is separate with no API access; this weakens auditability for ePHI access (HipaaAuditService.java, RagOrchestrationService.java, AuditController.java).
- Audit logging is fail-open outside govcloud; missed audit events can slip through without blocking access (AuditService.java, application.yaml).
- Tokenization vault reuses a single key for HMAC and AES-GCM and lacks rotation/KMS integration; encryption at rest depends on Mongo/disk setup, not enforced in app (TokenizationVault.java, MONGODB_*.md).
- OIDC auto-provision defaults on with approval off; in regulated environments you likely want require-approval and stricter defaults (OidcAuthenticationService.java, application.yaml).

## HIPAA Security Rule control mapping (45 CFR 164.308/310/312)

### 164.308 Administrative safeguards
- 164.308(a)(1)(ii)(A) Risk analysis: Out of scope (organizational requirement). Requires documented risk analysis covering data flows, vendors, infra, policies.
- 164.308(a)(1)(ii)(B) Risk management: Out of scope. Requires ongoing risk mitigation and evidence tracking.
- 164.308(a)(1)(ii)(D) Information system activity review: Partial. Audit logs exist but HIPAA audit is not wired to core PHI flows (AuditService.java, HipaaAuditService.java, RagOrchestrationService.java).
- 164.308(a)(2) Assigned security responsibility: Out of scope.
- 164.308(a)(3) Workforce security: Out of scope.
- 164.308(a)(4) Information access management: Partial. RBAC + clearance/sector gating are present, but auto-provision defaults are permissive and PHI is not uniformly gated (SecurityConfig.java, OidcAuthenticationService.java, RagOrchestrationService.java).
- 164.308(a)(5) Security awareness and training: Out of scope.
- 164.308(a)(6) Security incident procedures: Out of scope. Ops docs exist but no incident runbooks are enforced in code.
- 164.308(a)(7) Contingency plan: Out of scope. Backup/restore docs exist, but require org controls (BACKUP_RESTORE.md).
- 164.308(a)(8) Evaluation: Out of scope.
- 164.308(b)(1) Business associate contracts: Out of scope. BAAs required for any external service touching PHI.

### 164.310 Physical safeguards
- 164.310(a) Facility access controls: Out of scope.
- 164.310(b) Workstation use: Out of scope.
- 164.310(c) Workstation security: Out of scope.
- 164.310(d) Device and media controls: Out of scope.

### 164.312 Technical safeguards
- 164.312(a)(1) Access control: Partial. RBAC/clearance exist, but PHI can persist in memory, exports, and feedback stores without a uniform PHI gate (ConversationMemoryService.java, SessionPersistenceService.java, FeedbackService.java).
- 164.312(a)(2)(i) Unique user identification: Supported via OIDC/CAC/standard auth (SecurityConfig.java).
- 164.312(a)(2)(ii) Emergency access procedure: Partial. PII reveal workflow exists but is not enforced for all PHI data paths (PiiRevealController.java).
- 164.312(a)(2)(iii) Automatic logoff: Gap. No explicit session timeout or auto-logoff is configured (application.yaml).
- 164.312(a)(2)(iv) Encryption and decryption: Partial. Tokenization exists but key management is weak; encryption at rest and exports are not enforced by app (TokenizationVault.java, SessionPersistenceService.java).
- 164.312(b) Audit controls: Partial. Audit logging exists but is fail-open outside govcloud and HIPAA audit is not wired to routine PHI events (AuditService.java, HipaaAuditService.java).
- 164.312(c)(1) Integrity: Partial. Regex redaction is incomplete; no checksum or tamper-evident integrity for stored ePHI (PiiRedactionService.java).
- 164.312(c)(2) Mechanism to authenticate ePHI: Gap. No digital signature or hash chain for stored PHI artifacts.
- 164.312(d) Person or entity authentication: Partial. Auth modes exist, but auto-provision defaults and lack of MFA/lockout are not aligned with regulated expectations (OidcAuthenticationService.java, StandardAuthenticationService.java).
- 164.312(e)(1) Transmission security: Gap. TLS is not enforced for LLM/OCR/Mongo in standard/enterprise (SecurityConfig.java, application.yaml).
- 164.312(e)(2)(i) Integrity controls: Gap. Reliant on transport; no explicit integrity controls.
- 164.312(e)(2)(ii) Encryption: Gap. TLS not enforced across external services and data paths.

## Hardened medical deployment checklist (configuration + operational)

### Identity and access
- Use enterprise (OIDC) or govcloud (CAC) profile; avoid standard profile for medical deployments.
- Disable auto-provisioning and require approval for new accounts (OIDC_AUTO_PROVISION=false, CAC_AUTO_PROVISION=false, CAC_REQUIRE_APPROVAL=true).
- Enforce least-privilege defaults (OIDC_DEFAULT_ROLE=VIEWER, OIDC_DEFAULT_CLEARANCE=UNCLASSIFIED).
- Restrict sector switching if the instance is dedicated to medical (SENTINEL_ALLOW_SECTOR_SWITCH=false).
- Require PHI_ACCESS role for any PHI disclosure or inspection (code policy + role review).

### Transmission security
- Enforce HTTPS end-to-end (reverse proxy + HSTS). Set COOKIE_SECURE=true.
- Require TLS for MongoDB (use mongodb+srv or mongodb://.../?tls=true in MONGODB_URI).
- Ensure all external services (LLM/OCR/Infini-gram) use HTTPS and are covered by BAAs.

### Data handling and storage
- Disable or heavily restrict session exports and file backups for medical workloads (SessionController.java, SessionPersistenceService.java).
- Disable or redact conversation memory for medical sector (ConversationMemoryService.java).
- Disable or redact feedback/training exports for medical sector (FeedbackService.java).
- Store only redacted text and minimal metadata in vector store; verify SecureIngestionService redacts before storage.
- Enable encryption at rest at the storage layer (Mongo + disk) and verify key management/rotation.

### Redaction and de-identification
- Treat current redaction as heuristic, not Safe Harbor compliant. Expand to all 18 HIPAA identifiers or use a vetted PHI detection system.
- Set PII_MODE=REMOVE or TOKENIZE for medical deployments; audit redactions (PII_AUDIT=true).
- Ensure OCR and visual ingestion paths are redacted before persistence; otherwise disable MegaRAG/OCR.

### Audit and monitoring
- Set AUDIT_FAIL_CLOSED=true in medical deployments.
- Wire HIPAA audit logging to all PHI query/inspect/export flows (HipaaAuditService.java).
- Provide a restricted HIPAA audit log review/export path (AuditController.java or a dedicated endpoint).

### Features to disable or restrict for medical
- Disable external retrieval/calls without BAAs: QUCORAG_INFINI_GRAM=false, OCR_ENABLED=false, MEGARAG_ENABLED=false (or ensure redaction + BAA).
- Reduce or disable reasoning traces that can store PHI (REASONING_ENABLED=false or REASONING_DETAILED=false, REASONING_RETENTION=0).
- Disable BiRAG experience store or ensure redaction/encryption (BIRAG_ENABLED=false for medical).

### Session management
- Configure server session timeouts and automatic logoff.
- Restrict or disable Swagger UI in medical deployments.

### UI and assets
- Self-host UI assets to avoid external dependencies (CSP should not reference unpkg.com in HIPAA environments).

## Remediation backlog (code changes recommended)
1) Enforce TLS and HSTS for all production profiles; document TLS-only configs.
2) Add redaction or suppression for model outputs in medical sector.
3) Disable/limit PHI persistence in conversation memory, reasoning traces, feedback, and export paths.
4) Wire HipaaAuditService to all PHI access flows and make audit logs reviewable.
5) Expand redaction to cover all HIPAA identifiers; integrate vetted PHI detection.
6) Add key separation and rotation for tokenization vault; prefer KMS/HSM.
7) Enforce audit fail-closed in medical profile.
8) Add explicit medical profile or policy set to turn off unsafe features.

## HIPAA strict mode flags (now supported)
These configuration keys are available to enforce medical-only behavior:
- sentinel.hipaa.strict (bool; optional override)
- sentinel.hipaa.enable-for-medical-edition (bool; default true)
- sentinel.hipaa.redact-responses (bool; default true)
- sentinel.hipaa.disable-feedback (bool; default true)
- sentinel.hipaa.disable-session-memory (bool; default true)
- sentinel.hipaa.disable-session-export (bool; default true)
- sentinel.hipaa.disable-visual (bool; default true)
- sentinel.hipaa.disable-experience-learning (bool; default true)
- sentinel.hipaa.suppress-sensitive-logs (bool; default true)
- sentinel.hipaa.enforce-tls (bool; default false; enable for production)

## Notes and open questions
- Are external services (LLM/OCR/Infini-gram) ever used in medical deployments without BAAs?
- Are vector store, audit logs, and backups encrypted at rest in the target environment?
- Are session exports and trace files used in production or can they be disabled?
- Do any log aggregation tools capture request/response bodies?

## Next steps (if desired)
- Convert this into a formal remediation plan with priorities and staged milestones.
- Implement the code changes listed in the remediation backlog.
- Run a formal HIPAA risk analysis with compliance/legal stakeholders.
