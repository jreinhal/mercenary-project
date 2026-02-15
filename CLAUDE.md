# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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

## Build & Development Commands

**Prerequisites:** Java 21, MongoDB 7.x/8.x, Ollama

### Build
```bash
./gradlew build                          # Default edition (enterprise)
./gradlew build -Pedition=government     # Specific edition
./gradlew clean build                    # Clean build
./gradlew bootRun                        # Run the app (port 8080)
```

### Test
```bash
./gradlew test                           # All unit tests
./gradlew test --tests "*.PiiRedactionServiceTest"           # Single test class
./gradlew test --tests "*.PiiRedactionServiceTest.testMethod" # Single test method
./gradlew test --tests "com.jreinhal.mercenary.filter.*"     # All tests in a package
```

### E2E Tests (no external services needed — uses in-memory mocks)
```bash
./gradlew ciE2eTest                      # Pipeline E2E (ci-e2e + dev profiles)
./gradlew ciOidcE2eTest                  # OIDC enterprise path E2E
./gradlew ciEnterpriseE2eTest            # Enterprise RAG strategies E2E
./gradlew ciCrossTenantE2eTest           # Cross-tenant isolation E2E
```

### Lint
```bash
./gradlew build -Plint                   # Enable deprecation + unchecked warnings
./gradlew build -Plint -PlintWerror      # Treat lint warnings as errors (CI default)
```

### Running Locally
```bash
# Minimal environment variables for dev mode:
export APP_PROFILE=dev
export MONGODB_URI=mongodb://localhost:27017/mercenary
export OLLAMA_URL=http://localhost:11434
export LLM_MODEL=llama3.1:8b
export EMBEDDING_MODEL=nomic-embed-text
./gradlew bootRun
```
Or use `docker-compose up` (dev) / `docker-compose -f docker-compose.prod.yml up` (prod).

### Coverage
Tests produce JaCoCo reports at `build/reports/jacoco/test/html/index.html`. SonarCloud analysis runs in CI tier 4.

---

## Architecture

### High-Level Request Flow
```
User → Thymeleaf SPA (index.html / admin.html)
     → Spring Security filter chain (auth, rate-limit, CSP nonce, correlation ID)
     → MercenaryController (/api/ask, /api/ingest, /api/inspect, etc.)
     → RagOrchestrationService (strategy selection & pipeline coordination)
     → RAG Strategy (HybridRAG, HiFi-RAG, AdaptiveRAG, CRAG, etc.)
     → LocalMongoVectorStore (custom impl replacing Spring AI default)
     → Ollama (local LLM inference, never external APIs)
     → Response with citations + evidence metadata
```

### Key Architectural Layers

**Controllers** (`controller/`) — REST endpoints. `MercenaryController` is the main entry point handling `/api/ask`, `/api/ingest`, `/api/inspect`, `/api/documents`. `AuthController` and `OidcBrowserFlowController` handle authentication.

**Service layer** (`service/`) — Business logic. Central services:
- `RagOrchestrationService` — coordinates which RAG strategy runs, applies guardrails
- `SecureIngestionService` — document upload with Tika validation, PII redaction, chunking
- `PiiRedactionService` — detects/redacts SSN, credit cards, emails, etc.
- `PromptGuardrailService` — prompt injection detection before retrieval
- `AuditService` — STIG-compliant audit logging

**RAG strategies** (`rag/`) — 16+ pluggable retrieval strategies, each in its own subpackage. Key ones:
- `hybridrag/` — vector + keyword fusion via RRF (the default workhorse)
- `adaptiverag/` — classifies query complexity, routes to chunk vs document retrieval
- `hifirag/` — hierarchical filtering + cross-encoder reranking
- `crag/` — corrective RAG, rewrites query on low-confidence results
- `agentic/` — tool-augmented multi-hop reasoning (government edition)
- `megarag/` — multimodal knowledge graph (images, charts, tables)
- `qucorag/` — corpus-grounded uncertainty quantification
- `ragpart/` — corpus poisoning defense

**Security filters** (`filter/`) — ordered filter chain: CORS → rate limiting → CSP nonce → authentication → sector isolation. Check `@Order` annotations when adding filters.

**Vector store** (`vector/`) — `LocalMongoVectorStore` is a custom implementation (Spring AI's default MongoDB vector store auto-config is excluded). Includes filter expression parser/evaluator for sector-aware queries.

**Connectors** (`connectors/`) — S3, SharePoint, Confluence sync with incremental fingerprint tracking.

### Edition System (Compile-Time Isolation)

Edition isolation is enforced in `build.gradle` via `sourceSets` exclusion rules — not runtime feature flags. The `editionExcludes` closure removes entire package trees at compile time:
- `trial`/`enterprise` → excludes `**/medical/**` and `**/government/**`
- `medical` → excludes `**/government/**`
- `government` → includes everything

Security-sensitive code (CAC auth, HIPAA compliance) physically cannot exist in lower-edition JARs. Output: `sentinel-${edition}-${version}.jar`.

### Spring Profiles & Auth Modes

| Profile | Auth Mode | Purpose |
|---------|-----------|---------|
| `dev` | DEV | Development — auto-provisions ADMIN/TOP_SECRET, no login |
| `standard` | STANDARD | Commercial — username/password with bootstrap |
| `enterprise` | OIDC | Enterprise — external IdP, Bearer JWT |
| `govcloud` | CAC | Government — X.509 client certificate (smart card) |
| `foundation` | (any) | Adds cloud model support (OpenAI/Anthropic/VertexAI) |

### Test Profiles

- `test` — standard unit tests, needs local MongoDB + Ollama
- `ci-e2e` — **no external services**. Disables MongoDB auto-config, uses in-memory vector store, stubs chat/embedding models. All advanced RAG strategies disabled.
- `ci-e2e-enterprise` — extends ci-e2e with advanced RAG strategies enabled

### Frontend

Server-rendered Thymeleaf pages with vanilla JS (no framework). Key files:
- `src/main/resources/static/index.html` + `js/sentinel-app.js` — main chat UI
- `src/main/resources/static/admin.html` + `js/admin.js` — admin dashboard
- `src/main/resources/static/manual.html` — user manual
- Vendor libs: D3.js (graph visualization), force-graph.js (entity explorer)
- CSP enforced with per-request nonce (see `CspNonceFilter`)

### CI Pipeline (4 tiers in `.github/workflows/ci.yml`)

1. **Unit tests** — `./gradlew clean test -Plint -PlintWerror`
2. **Integration/E2E** — pipeline, OIDC, cross-tenant E2E tests
3. **Enterprise realism** — enterprise RAG strategy E2E
4. **Packaging & analysis** — enterprise build verification + SonarCloud

---

## Repository Structure

**Single repo, multiple build editions.** The old sentinel-community, sentinel-enterprise, sentinel-research repos are deprecated.

### Package Organization
```
src/main/java/com/jreinhal/mercenary/
├── core/           ← All editions (license validation)
├── enterprise/     ← Paid features (admin dashboard, memory, enterprise RAG)
├── medical/        ← HIPAA compliance (medical + government only)
├── government/     ← SCIF/CAC/clearance (government only)
├── controller/     ← REST endpoints
├── service/        ← Business logic
├── rag/            ← RAG strategy implementations
├── filter/         ← Security filter chain
├── security/       ← JWT/OIDC/CAC validation
├── config/         ← Spring configuration
├── connectors/     ← S3/SharePoint/Confluence sync
├── vector/         ← Custom MongoDB vector store
├── model/          ← Domain entities
├── repository/     ← MongoDB data access
└── tools/          ← Agent tool implementations
```

### Key Configuration Files
- `src/main/resources/application.yaml` — master config (all RAG flags, tuning knobs)
- `src/main/resources/application-enterprise.yaml` — high-scale thread pool overrides
- `src/main/resources/application-foundation.yaml` — cloud model provider config
- `src/test/resources/application-ci-e2e.yml` — CI E2E profile (no external deps)
- `docker-compose.yml` — dev stack (app + mongo + ollama)
- `docker-compose.prod.yml` — hardened production stack

---

## Security Audit Checklist

**When reviewing security, Claude MUST check for gaps, not just verify what exists.**

### Endpoint Authorization Audit
For EVERY `@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping`:
- [ ] Does it have `@PreAuthorize` or explicit auth check?
- [ ] If it accepts a `dept`/`sector` parameter, does it verify user has access to that sector?
- [ ] If it accepts a `userId` or resource ID, does it verify ownership or admin role?
- [ ] If it queries the database/vector store, does the query include sector filtering?

### Data Flow Audit
- [ ] Trace data from input to storage — is it redacted/sanitized before caching?
- [ ] Trace data from storage to output — is sector filtering applied at query time, not just in-memory?
- [ ] Are cache keys compound (include sector) to prevent cross-tenant leakage?

### Filter/Interceptor Audit
- [ ] List all `@Order` values — are there conflicts (same order = undefined execution)?
- [ ] Do filters that need auth context run AFTER authentication?
- [ ] Is `X-Forwarded-For` only trusted behind known proxies?

### Frontend Security Audit
- [ ] Do CSP policies match actual inline script/style usage?
- [ ] Are nonce attributes present on inline scripts if CSP requires them?
- [ ] Does the frontend expose data the backend should filter?

### Missing vs Existing
- [ ] Audit what's MISSING, not just what EXISTS
- [ ] List all endpoints and check each one — don't assume "if /ask is secure, /inspect must be too"
- [ ] Check for orphaned endpoints that bypass security patterns

---

## Critical Constraints

1. **SCIF/Air-Gap Compliance is Paramount**
   - No external API calls (OpenAI, Anthropic, etc.) unless `foundation` profile is active
   - All LLM processing via local Ollama by default
   - All data stays local (MongoDB, local storage)
   - No telemetry or phone-home features

2. **Edition Isolation**
   - Government code must NEVER appear in non-government builds
   - Medical/HIPAA code must NEVER appear in trial/enterprise builds
   - Use Gradle source exclusions, not runtime feature flags for sensitive code

3. **Security Standards**
   - OWASP Top 10 compliance required
   - All security fixes apply to all editions (in core/)
   - CAC/PIV authentication is government-only
   - OIDC approval workflow enabled for all editions

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
- **No credentials required** — login is bypassed
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

## Manual UI Testing

Playwright in headed browser mode via `tools/playwright-runner`.
