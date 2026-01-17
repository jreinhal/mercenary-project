# SENTINEL Intelligence Platform - Development Guidelines

## Repository Structure

**Single repo, multiple build editions.** All development happens in `mercenary/`. The old sentinel-community, sentinel-professional, sentinel-research repos are deprecated.

## Package Organization

```
src/main/java/com/jreinhal/mercenary/
├── core/           ← All editions (shared functionality)
├── professional/   ← Paid features (trial, professional, medical, government)
├── medical/        ← HIPAA compliance (medical + government only)
└── government/     ← SCIF/CAC/clearance (government only)
```

## Build Editions

| Edition | Packages Included | Notes |
|---------|-------------------|-------|
| Trial | core + professional | 30-day time limit, full features |
| Professional | core + professional | Commercial/academic customers |
| Medical | core + professional + medical | HIPAA-compliant deployments |
| Government | all packages | SCIF/air-gapped, CAC auth, clearance levels |

Build command: `./gradlew build -Pedition=government`

## Security Audit Checklist

**When reviewing security, Claude MUST check for gaps, not just verify what exists.**

### Endpoint Authorization Audit
For EVERY `@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping`:
- [ ] Does it have `@PreAuthorize` or explicit auth check?
- [ ] If it accepts a `dept`/`sector` parameter, does it verify user has access to that sector?
- [ ] If it accepts a `userId` or resource ID, does it verify ownership or admin role?
- [ ] If it queries the database/vector store, does the query include sector filtering?

### Data Flow Audit
- [ ] Trace data from input to storage - is it redacted/sanitized before caching?
- [ ] Trace data from storage to output - is sector filtering applied at query time, not just in-memory?
- [ ] Are cache keys compound (include sector) to prevent cross-tenant leakage?

### Filter/Interceptor Audit
- [ ] List all `@Order` values - are there conflicts (same order = undefined execution)?
- [ ] Do filters that need auth context run AFTER authentication?
- [ ] Is `X-Forwarded-For` only trusted behind known proxies?

### Frontend Security Audit
- [ ] Do CSP policies match actual inline script/style usage?
- [ ] Are nonce attributes present on inline scripts if CSP requires them?
- [ ] Does the frontend expose data the backend should filter?

### Missing vs Existing
- [ ] Audit what's MISSING, not just what EXISTS
- [ ] List all endpoints and check each one - don't assume "if /ask is secure, /inspect must be too"
- [ ] Check for orphaned endpoints that bypass security patterns

---

## Critical Constraints

1. **SCIF/Air-Gap Compliance is Paramount**
   - No external API calls (OpenAI, Anthropic, etc.)
   - All LLM processing via local Ollama
   - All data stays local (MongoDB, local storage)
   - No telemetry or phone-home features

2. **Edition Isolation**
   - Government code must NEVER appear in non-government builds
   - Medical/HIPAA code must NEVER appear in trial/professional builds
   - Use Gradle source exclusions, not runtime feature flags for sensitive code

3. **Security Standards**
   - OWASP Top 10 compliance required
   - All security fixes apply to all editions (in core/)
   - CAC/PIV authentication is government-only
   - OIDC approval workflow enabled for all editions

## Commit Guidelines

- All commits go to this single mercenary repo
- Security fixes: prefix with "security:"
- Edition-specific features: note which package (e.g., "government: add X")
- Always include `Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>` when Claude assists

## Target Markets

- **Government**: DoD, Intel Community, Federal agencies (requires FedRAMP path)
- **Medical**: Hospitals, research institutions, pharma (requires HIPAA)
- **Professional**: Law firms, finance, enterprise, academia
- **Trial**: 30-day full-feature evaluation for all prospects

## Documentation Index

| Document | Purpose |
|----------|---------|
| `docs/MARKETING.md` | Sales messaging, competitive positioning, objection handling |
| `docs/PRODUCT_LINE_STRATEGY.md` | Edition breakdown, pricing, go-to-market |
| `docs/COMPLIANCE_MATRIX.md` | NIST 800-53, HIPAA, PCI-DSS control mapping |
| `docs/AdaptiveRAG_Implementation_Report.md` | Technical architecture deep-dive |
| `README.md` | Public-facing product description |

## Key Capabilities (Sales/Documentation Reference)

### Security & Compliance
- **Fail-Closed Auditing**: STIG-compliant audit logging that halts operations on failure (govcloud profile)
- **Magic Byte File Detection**: Apache Tika-based content analysis blocks executables regardless of extension
- **Prompt Injection Defense**: Multi-layer detection with suspicious pattern blocking and audit logging
- **PII/PHI Redaction**: Automatic detection and redaction of sensitive data (SSN, credit cards, emails)
- **NIST 800-53 Compliance**: Documented control mapping for ATO packages (see docs/COMPLIANCE_MATRIX.md)
- **HIPAA Ready**: PHI handling, audit trails, access controls for medical deployments
- **FedRAMP Path**: Architecture supports eventual FedRAMP authorization

### RAG Intelligence Features
- **Self-Reflective RAG**: AI self-critique and refinement for improved answer quality
- **Citation Verification**: Automatic source verification with confidence scoring
- **Query Decomposition**: Complex questions broken into sub-queries for comprehensive answers
- **Hybrid Search**: Combines vector similarity with BM25 keyword search (RRF fusion)
- **Conversation Memory**: Context-aware follow-up questions with session persistence
- **Multi-Sector Support**: Department-based document isolation with clearance enforcement
- **User Feedback System**: Thumbs up/down collection with RLHF training data export

### Quality Improvement & Analytics
- **Feedback Collection**: Users can rate responses with thumbs up/down
- **Categorized Issues**: Negative feedback includes category (hallucination, inaccurate citation, outdated info, wrong sources, too slow)
- **RAG Metadata Capture**: Each feedback includes routing decision, sources, signals, response time for debugging
- **Training Data Export**: Export query/response pairs with reward signals for model fine-tuning
- **Admin Dashboard**: Satisfaction metrics, category breakdown, issue triage queue
- **Hallucination Reports**: Priority queue for high-risk issues requiring review

### Deployment Options
- **Air-Gapped/SCIF**: Full functionality without internet (local Ollama + MongoDB)
- **CAC/PIV Authentication**: Smart card support for government deployments
- **Docker Hardened**: Pinned versions, resource limits, non-root containers
- **Multi-Edition Builds**: Single codebase, compile-time feature isolation

## Session Start Reminder

**At the start of each session**, Claude should:

### 1. Read Key Status Files
```
Read: RECOMMENDATIONS.md (current security posture)
Read: SECURITY_GATES.md (audit checklist)
```

### 2. Current Architecture Context

**Filter Execution Order:**
```
CspNonceFilter(0) → LicenseFilter(1) → SecurityFilter(2) → RateLimitFilter(3)
```

**Key Security Services:**
| Service | Purpose |
|---------|---------|
| `SecurityFilter` | Authentication, sets `SecurityContext` |
| `RateLimitFilter` | Role-based rate limiting (bucket4j) |
| `PromptGuardrailService` | 3-layer injection defense |
| `PiiRedactionService` | SSN/CC/Email redaction |
| `TokenizationVault` | AES-256-GCM encrypted PII storage |
| `AuditService` | STIG-compliant logging, fail-closed mode |

**Critical Controller Endpoints:**
| Endpoint | Security | Notes |
|----------|----------|-------|
| `/api/ask` | Auth + Sector + Clearance | Main RAG query |
| `/api/inspect` | Auth + Sector filter on vector query | Document viewer |
| `/api/ingest/file` | Auth + INGEST permission + Clearance | Upload |
| `/api/reasoning/{id}` | Auth + Owner-scoped | Trace viewer |
| `/api/feedback/positive` | Auth | Submit thumbs up |
| `/api/feedback/negative` | Auth | Submit thumbs down with category |
| `/api/feedback/analytics` | Auth + ADMIN/ANALYST | Dashboard metrics |
| `/api/feedback/export/training` | Auth + ADMIN | RLHF training data |

### 3. Recent Security Hardening (2026-01-15)
All fixes verified and implemented:
- PR-1: `/api/inspect` sector filter + `/api/reasoning` owner scope
- PR-2: Filter ordering (0,1,2,3 sequence)
- PR-3: Ingest cache redacts PII before storage
- PR-4: Externalized JS to `js/sentinel-app.js` for CSP
- PR-5: `TokenizationVault` uses AES-256-GCM (not Base64)

### 4. Full Audit Macro Available
```
Run: Read .agent/macros/run-audit.md and execute it
```
Launches 5 parallel agents to generate `RECOMMENDATIONS.md`.

### 5. Skills Update Check
Fetch updates from: https://github.com/sickn33/antigravity-awesome-skills

**Macro location:** `.agent/macros/`
