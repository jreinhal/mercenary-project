# SENTINEL Intelligence Platform - Development Guidelines

## CRITICAL SAFETY RULES - READ FIRST

### NEVER DELETE SOURCE CODE
**ABSOLUTE PROHIBITION:** Claude must NEVER execute any of the following:
- `rm -rf` on any directory containing source code (`src/`, project root, etc.)
- `git clean -fdx` or any variation that removes untracked files
- Deleting `.git/` directory
- Any command that could delete `src/`, `gradle/`, `build.gradle`, or project configuration files

**If a build cache needs clearing:**
- ONLY delete specific build output directories: `build/classes/`, `build/libs/`, `build/tmp/`
- NEVER delete `build/` entirely without explicit user confirmation
- Use `./gradlew clean` which is designed to be safe
- If `./gradlew clean` fails, ask the user how to proceed

**Before ANY delete operation:**
1. List exactly what will be deleted
2. Get explicit user confirmation
3. Never combine delete operations with other commands

---

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

## Manual UI Testing Capability

- Manual UI testing is available via Playwright in headed browser mode.
- Use the local Playwright runner (`tools/playwright-runner`) to drive real UI interactions when requested.

## PR Workflow

- Create a branch for every change (use `feature/`, `fix/`, or `chore/` prefixes).
- Open a PR to `master` and enable auto-merge once CI passes.
- Avoid direct pushes to `master`.

## Commit Guidelines

- All commits go to this single mercenary repo
- Security fixes: prefix with "security:"
- Edition-specific features: note which package (e.g., "government: add X")
- Always include `Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>` when Claude assists

## Authentication & Login Credentials

### DEV Mode (Default)
When running with `APP_PROFILE=dev` or `AUTH_MODE=DEV`:
- **No credentials required** - login is bypassed
- Demo user: `DEMO_USER` with full ADMIN access and TOP_SECRET clearance
- Override username via `?operator=yourname` URL param or `X-Operator-Id` header

### STANDARD Mode
When running with `AUTH_MODE=STANDARD`:
- **Username:** `admin`
- **Password:** `h!iK*4WzdRehyd6ej^xHjZTPruuY`
- **Roles:** ADMIN

### External Auth Modes
| Mode | Profile | Auth Method |
|------|---------|-------------|
| OIDC | enterprise | External IdP, Bearer JWT required |
| CAC | govcloud | X.509 client certificate (PIV/CAC smart card) |

### Profile Selection
```bash
# Environment variables
APP_PROFILE=dev|standard|enterprise|govcloud
AUTH_MODE=DEV|STANDARD|OIDC|CAC
```

## Key Capabilities (Sales/Documentation Reference)

### Security & Compliance
- **Fail-Closed Auditing**: STIG-compliant audit logging that halts operations on failure (govcloud profile)
- **Magic Byte File Detection**: Apache Tika-based content analysis blocks executables regardless of extension
- **Prompt Injection Defense**: Multi-layer detection with suspicious pattern blocking and audit logging
- **PII/PHI Redaction**: Automatic detection and redaction of sensitive data (SSN, credit cards, emails)
- **NIST 800-53 Compliance**: Documented control mapping for ATO packages
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
- **Entity Explorer**: Interactive visualization of entity relationships extracted from documents (HyperGraph Memory)

### Deployment Options
- **Air-Gapped/SCIF**: Full functionality without internet (local Ollama + MongoDB)
- **CAC/PIV Authentication**: Smart card support for government deployments
- **Docker Hardened**: Pinned versions, resource limits, non-root containers
- **Multi-Edition Builds**: Single codebase, compile-time feature isolation
