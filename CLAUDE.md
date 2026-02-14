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

**Prerequisites:** Java 21 (Temurin), MongoDB 7.x/8.x, Ollama

```bash
# Build (default edition: enterprise)
./gradlew build
./gradlew build -Pedition=government    # government|medical|enterprise|trial

# Run locally (dev profile, no auth required)
# MONGODB_URI and OLLAMA_URL are expected as system env vars — do NOT override them inline
APP_PROFILE=dev ./gradlew bootRun

# Tests
./gradlew test                          # All unit + integration tests
./gradlew test --tests "com.jreinhal.mercenary.service.PiiRedactionServiceTest"  # Single test class
./gradlew test --tests "*PiiRedactionServiceTest.testSsnRedaction"               # Single test method
./gradlew ciE2eTest                     # E2E pipeline tests (in-memory vector store, stubbed models)
./gradlew ciOidcE2eTest                 # OIDC enterprise path E2E tests

# Lint (CI runs with -Plint -PlintWerror; warnings = errors)
./gradlew clean test -Plint -PlintWerror

# Clean build cache
./gradlew clean

# SonarCloud analysis
./gradlew sonar
```

The JVM arg `--add-opens java.base/java.nio.charset=ALL-UNNAMED` is automatically applied to `bootRun`.

---

## Architecture Overview

### Single Repo, Multi-Edition Build
The Gradle build uses `-Pedition=` to **exclude source directories at compile time** (not runtime flags). This is critical for security: government/medical code never ships in lower editions.

```
src/main/java/com/jreinhal/mercenary/
├── core/           ← All editions (license validation)
├── enterprise/     ← Trial + Enterprise + Medical + Government (admin, memory, enterprise RAG)
├── medical/        ← Medical + Government only (HIPAA audit, PHI detection, PII reveal gate)
└── government/     ← Government only (CAC/PIV auth, X.509 certificate parsing)
```

| Edition | Packages Included |
|---------|-------------------|
| Trial | core + enterprise |
| Enterprise | core + enterprise |
| Medical | core + enterprise + medical |
| Government | all packages |

### Spring Boot Entry Point & Profiles
- **Entry point:** `MercenaryApplication.java` — validates profile/auth mode combinations, configures vector store selection
- **Profiles:** `dev`, `standard`, `enterprise`, `govcloud` (set via `APP_PROFILE` env var)
- **Auth modes:** `DEV` (no auth), `STANDARD` (username/password), `OIDC` (JWT via IdP), `CAC` (X.509 smart card)
- **Config:** `application.yaml` contains all profiles in a single file using `---` separators; cloud AI autoconfiguration is explicitly excluded

### Request Processing Pipeline
Filters execute in this order:
1. `PreAuthRateLimitFilter` (Order 1) — IP-based rate limiting
2. `SecurityFilter` (Order 2) — Authentication routing (DEV/STANDARD/OIDC/CAC)
3. `CorrelationIdFilter` (Order 3) — X-Correlation-ID for request tracing via MDC
4. `RateLimitFilter` (Order 4) — Per-user rate limiting
5. `CspNonceFilter` (Order 5) — CSP nonce injection for inline scripts

Additional filters: `WorkspaceFilter` (ThreadLocal workspace context), `LicenseFilter` (core/), `CacAuthFilter` (government/).

`SecurityContext` is a ThreadLocal holder for the current User across the request lifecycle.

### RAG Pipeline Architecture
`RagOrchestrationService` routes queries to strategy implementations based on feature flags. Each strategy in `src/.../rag/` is independent and composable:

**Core strategies:** HybridRAG (RRF fusion), AdaptiveRAG (complexity routing), CRAG (corrective retrieval), RAGPart (corpus poisoning defense), QuCo-RAG (hallucination detection), HiFi-RAG (reranking), MegaRAG (multimodal), MiA-RAG (hierarchical summaries), BiRAG (bidirectional grounding), Self-RAG (self-critique), HyDE (hypothetical embeddings), Graph-O1 (MCTS reasoning), HGMem (entity hypergraph), Agentic (multi-hop with tools)

**Ingestion:** `SecureIngestionService` validates file magic bytes via Apache Tika, then chunks/embeds into `LocalMongoVectorStore`. Table extraction uses Tabula. Connectors (S3/SharePoint/Confluence) support incremental sync via `ConnectorSyncStateService`.

**Vector store:** `LocalMongoVectorStore` — custom MongoDB embedding store (not Atlas Vector Search). `FilterExpressionParser`/`FilterExpressionEvaluator` enforce department/sector filtering at query time.

### Key Services
| Service | Purpose |
|---------|---------|
| `RagOrchestrationService` | RAG strategy selection and execution |
| `PiiRedactionService` | PII/PHI auto-detection (SSN, CC, email, medical IDs) |
| `PromptGuardrailService` | Prompt injection detection with circuit breaker |
| `AuditService` | STIG-compliant logging; fail-closed in govcloud |
| `SecureIngestionService` | Tika magic-byte validation + chunking pipeline |
| `ConversationMemoryService` | Session-aware context (enterprise+) |
| `CitationVerificationService` | Source confidence scoring (enterprise+) |

### Frontend
Server-rendered HTML with Thymeleaf. Main files in `src/main/resources/static/`:
- `index.html` — RAG chat UI
- `admin.html` — Admin dashboard
- `js/sentinel-app.js` — Client-side SPA logic
- Frontend JS is excluded from SonarQube analysis

### Supporting Services & Tools
- `services/lightonocr-service/` — Python OCR microservice
- `tools/playwright-runner/` — Headed browser testing (Playwright/Node.js)
- `tools/generate-license-key.py` — HMAC license key generation
- `tools/ingest-corpus.ps1` / `tools/monitor-ingest.ps1` — Ingestion utilities

---

## Critical Constraints

1. **SCIF/Air-Gap Compliance is Paramount**
   - No external API calls (OpenAI, Anthropic, etc.) — cloud AI autoconfigs are excluded in `application.yaml`
   - All LLM processing via local Ollama
   - All data stays local (MongoDB, local storage)
   - No telemetry or phone-home features
   - Dependency locking (`gradle.lockfile`) ensures reproducible builds

2. **Edition Isolation**
   - Government code must NEVER appear in non-government builds
   - Medical/HIPAA code must NEVER appear in trial/enterprise builds
   - Use Gradle source exclusions, not runtime feature flags for sensitive code

3. **Security Standards**
   - OWASP Top 10 compliance required
   - All security fixes apply to all editions (in core/)
   - CAC/PIV authentication is government-only
   - OIDC approval workflow enabled for all editions

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

## Testing Tiers

### Tier 1 — CI (GitHub Actions, every PR)
The `CI / build` job runs these gates **sequentially** — a failure in any step blocks the rest:

1. `./gradlew clean test -Plint -PlintWerror` — all unit + integration tests with lint warnings as errors
2. `./gradlew -Pedition=enterprise build -x test` — verify enterprise edition packaging
3. `./gradlew ciE2eTest` — RAG pipeline E2E (`PipelineE2eTest`, in-memory vector store, stubbed models)
4. `./gradlew ciOidcE2eTest` — OIDC enterprise auth E2E (`OidcPipelineE2eTest`)
5. `./gradlew sonar` — SonarCloud analysis (when `SONAR_TOKEN` is set)

### Tier 2 — Local multi-profile E2E (requires MongoDB + Ollama)
```powershell
# Runs all 4 profiles (dev, standard, enterprise, govcloud) sequentially:
pwsh tools/run_e2e_profiles.ps1 -MongoUri mongodb://localhost:27017/mercenary -AdminPassword '<pw>'
```
Each profile boots the app on an isolated port (18080/18081/18082/18443), ingests test docs per sector (GOVERNMENT/MEDICAL/ENTERPRISE), and exercises `/api/ingest/file`, `/api/ask`, `/api/ask/enhanced`, `/api/inspect`. Govcloud profile auto-generates a TLS keystore and uses CAC auth headers. Results written to `build/e2e-results/`.

### Tier 3 — UAT (Playwright, headed browser, bounded timeouts)
```powershell
# Core UI suite (15 min timeout)
pwsh tools/playwright-runner/run-ui-suite.ps1 -Profile enterprise -AuthMode STANDARD -UiTimeoutSec 900

# Ops sign-off (10 min) — boots app, verifies auth gates, endpoint access controls
pwsh tools/playwright-runner/run-ops-signoff.ps1 -Profile enterprise -AuthMode STANDARD

# Additional specialized suites:
pwsh tools/playwright-runner/run-flag-matrix.ps1    # Feature flag combinations
pwsh tools/playwright-runner/run-govcloud-ui.ps1    # Govcloud profile UI
```
The UAT suite (`run-ui-tests.js`) runs in Edge via Playwright. The ops sign-off runner verifies endpoint auth gates (health public, status/telemetry/admin require auth, login flow, connector/dashboard access).

### Test categories
- **Unit tests:** `service/`, `controller/`, `filter/`, `config/`, `util/`, `security/` — mocked dependencies
- **Integration tests:** `enterprise/admin/`, `rag/`, `connectors/`, `vector/` — wired Spring context
- **E2E tests:** `e2e/PipelineE2eTest`, `e2e/OidcPipelineE2eTest` — `InMemoryVectorStore` + stubbed chat/embedding models (profile: `ci-e2e`)

### Quality gates
- No mojibake (`U+FFFD`), no binary-signature noise (`PK...`, `Rar!...`), no mirrored duplicate clauses

---

## PR Workflow & Commit Guidelines

- Branch per change: `feature/`, `fix/`, or `chore/` prefixes
- Open PR to `master`, enable auto-merge once CI passes (`CI / build` required check)
- No direct pushes to `master`
- Security fixes: prefix commit with `security:`
- Edition-specific features: note which package (e.g., `government: add X`)
- Always include `Co-Authored-By: Claude <noreply@anthropic.com>` when Claude assists

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

