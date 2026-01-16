# SENTINEL Compliance Matrix

## NIST 800-53 Control Mapping

This document maps SENTINEL features to NIST 800-53 security controls for ATO (Authority to Operate) documentation.

### Access Control (AC)

| Control | Description | SENTINEL Implementation | Status |
|---------|-------------|------------------------|--------|
| AC-2 | Account Management | `AdminDashboardService` - user provisioning, approval workflow, role management | ✅ Implemented |
| AC-3 | Access Enforcement | `SecurityFilter` + `SecurityConfig` - role-based access, sector isolation | ✅ Implemented |
| AC-6 | Least Privilege | OIDC/CAC default to VIEWER role, UNCLASSIFIED clearance | ✅ Implemented |
| AC-7 | Unsuccessful Login Attempts | `AuditService.logAuthFailure()` - tracks failed attempts | ✅ Implemented |
| AC-17 | Remote Access | HTTPS required, CORS restrictions in `WebConfig` | ✅ Implemented |

### Audit and Accountability (AU)

| Control | Description | SENTINEL Implementation | Status |
|---------|-------------|------------------------|--------|
| AU-2 | Audit Events | `AuditService` - logs auth, queries, ingestion, access denied, injection attempts | ✅ Implemented |
| AU-3 | Content of Audit Records | Audit events include user, timestamp, action, resource, IP, user-agent | ✅ Implemented |
| AU-6 | Audit Review | `AuditController` + Admin Dashboard - audit log retrieval | ✅ Implemented |
| AU-9 | Protection of Audit Information | MongoDB collection, fail-closed mode in govcloud (`app.audit.fail-closed=true`) | ✅ Implemented |
| AU-12 | Audit Generation | All security events generate audit records via `AuditService` | ✅ Implemented |

### Configuration Management (CM)

| Control | Description | SENTINEL Implementation | Status |
|---------|-------------|------------------------|--------|
| CM-7 | Least Functionality | Swagger disabled in production, edition-based feature exclusion | ✅ Implemented |
| CM-8 | Component Inventory | `build.gradle` dependency management, Docker pinned versions | ✅ Implemented |

### Identification and Authentication (IA)

| Control | Description | SENTINEL Implementation | Status |
|---------|-------------|------------------------|--------|
| IA-2 | Identification and Authentication | Three auth modes: DEV, OIDC (enterprise), CAC/PIV (government) | ✅ Implemented |
| IA-5 | Authenticator Management | OIDC token validation, CAC certificate parsing | ✅ Implemented |
| IA-8 | Identification of Non-Org Users | OIDC approval workflow, CAC auto-provision controls | ✅ Implemented |

### System and Communications Protection (SC)

| Control | Description | SENTINEL Implementation | Status |
|---------|-------------|------------------------|--------|
| SC-8 | Transmission Confidentiality | HTTPS enforced, secure headers (HSTS, CSP with nonces) | ✅ Implemented |
| SC-13 | Cryptographic Protection | JWT validation, HMAC-SHA256 tokenization vault | ✅ Implemented |
| SC-28 | Protection of Information at Rest | MongoDB encryption at rest (deployment config) | ⚠️ Deployment |

### System and Information Integrity (SI)

| Control | Description | SENTINEL Implementation | Status |
|---------|-------------|------------------------|--------|
| SI-2 | Flaw Remediation | Dependency management, security hardening | ✅ Implemented |
| SI-3 | Malicious Code Protection | Magic byte file detection, blocked MIME types | ✅ Implemented |
| SI-4 | System Monitoring | Audit logging, usage analytics | ✅ Implemented |
| SI-10 | Information Input Validation | `PromptGuardrailService` - injection detection, input sanitization | ✅ Implemented |

---

## HIPAA Compliance (Medical Edition)

| Requirement | SENTINEL Implementation | Status |
|-------------|------------------------|--------|
| 164.312(b) - Audit Controls | `HipaaAuditService` - PHI access logging | ✅ Implemented |
| 164.312(c) - Integrity | Document checksums, audit trails | ✅ Implemented |
| 164.312(d) - Authentication | Multi-factor via OIDC, CAC/PIV | ✅ Implemented |
| 164.312(e) - Transmission Security | HTTPS, TLS 1.3 | ✅ Implemented |
| 164.502 - Uses and Disclosures | `PhiDetectionService` - PHI detection and redaction | ✅ Implemented |

---

## PCI-DSS Compliance (Finance Sector)

| Requirement | SENTINEL Implementation | Status |
|-------------|------------------------|--------|
| 3.4 - PAN Protection | `PiiRedactionService` - credit card detection/masking | ✅ Implemented |
| 8.2 - User Authentication | Role-based access, strong auth | ✅ Implemented |
| 10.2 - Audit Trail | Comprehensive audit logging | ✅ Implemented |

---

## FedRAMP Path

For FedRAMP authorization, the following additional items are recommended:

1. **Continuous Monitoring** - Implement automated vulnerability scanning
2. **Incident Response** - Document IR procedures
3. **Supply Chain** - Document third-party dependencies (Ollama, MongoDB)
4. **Boundary Protection** - Network segmentation documentation
5. **Personnel Security** - Background check requirements for administrators

---

## Edition Compliance Summary

| Edition | NIST 800-53 | HIPAA | PCI-DSS | FedRAMP Ready |
|---------|-------------|-------|---------|---------------|
| Trial | Partial | No | No | No |
| Professional | Partial | No | Partial | No |
| Medical | Partial | ✅ Yes | Partial | No |
| Government | ✅ Full | ✅ Yes | ✅ Yes | ⚠️ Path Available |
