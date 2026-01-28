# End-to-End (E2E) Testing

This guide documents the full-profile, full-sector E2E runner used during build validation.

## What it does
- Starts the app for each profile (dev, standard, enterprise, govcloud)
- Ingests one test document per sector
- Runs /ask, /ask/enhanced, and /inspect for each sector
- Writes a results summary and JSON artifact per run

## Prerequisites
- Java 21+ available on PATH (for `./gradlew bootRun`)
- MongoDB running and reachable via `MONGODB_URI`
- Ollama running at `OLLAMA_URL` (default http://localhost:11434)
- PowerShell 7 (`pwsh`) recommended (required for govcloud TLS handling)
- `keytool` available (bundled with JDK) for govcloud TLS bootstrap

## Quick start
```
$env:MONGODB_URI="mongodb://mercenary_app:***@localhost:27017/mercenary"
$env:SENTINEL_ADMIN_PASSWORD="***"
pwsh -File tools/run_e2e_profiles.ps1
```

## One-click full checklist
```
pwsh -File tools/run_full_checklist.ps1
```
Runs bootstrap + E2E profiles + query matrix + UI matrix.
Defaults to the same scope as the **Full E2E Checklist** workflow (dev/ENTERPRISE matrix).
Use `-FullMatrix` to run all profiles/sectors and include no-results UI checks.
Use `-IncludeNoResults` to run the empty-dataset UI checks without the full matrix.

## Bootstrap helper
```
pwsh -File tools/dev_bootstrap.ps1
```
Starts/validates MongoDB and Ollama, and pulls required models if missing.

## Docker-based local setup
```
docker compose -f docker-compose.e2e.yml up -d
```
Use `.env.example` as a starting point for local env vars.

## CI-lite pipeline E2E (no external services)
```
./gradlew ciE2eTest
```
Uses the `ci-e2e` + `dev` test profiles (`src/test/resources/application-ci-e2e.yml`) with in-memory vector store and stubbed LLM.

## Profile notes
### dev
- Auth mode: DEV
- No login required

### standard/enterprise
- Auth mode: STANDARD
- Uses bootstrap admin password to log in and obtain CSRF token

### govcloud
- Auth mode: CAC
- Uses `X-Client-Cert` header for CAC identity (trusted proxy path)
- Generates a local TLS keystore if missing
- Sets `APP_CSRF_BYPASS_INGEST=true` to bypass CSRF on `/api/ingest/**` during test runs
  - Do not enable this flag in production

## Output artifacts
All results are written to:
```
build/e2e-results/
```

Files per run:
- `e2e_results_<timestamp>.md`
- `e2e_results_<timestamp>.json`
- `boot_<profile>_<timestamp>.log`
- `boot_<profile>_<timestamp>.log.err`

## Troubleshooting
- Govcloud 403 on ingest: confirm `APP_CSRF_BYPASS_INGEST=true` was set for the run.
- Connection refused: ensure MongoDB and Ollama are running.
- `pwsh` not found: install PowerShell 7 or run profiles individually without govcloud.

## Full checklist workflow (self-hosted)
`.github/workflows/full-e2e.yml` runs the full checklist on a self-hosted Windows runner (manual + scheduled).
Prereqs for the runner:
- MongoDB + `mongosh`
- Ollama + required models
- Node.js + Microsoft Edge (for UI matrix)
- Secrets: `MONGODB_URI`, `SENTINEL_ADMIN_PASSWORD`, `OLLAMA_URL` (optional if default)

Artifacts uploaded by the workflow:
- `build/e2e-results`
- `build/query-matrix-results.json`
- `build/query-matrix-traces`
- `build/ui-matrix-results.json`
- `build/ui-matrix-screenshots`

### Merge gate (recommended)
Configure branch protection to require the **Full E2E Checklist** status check before merging to `master`.

## Tier0 PII note
The Tier0 PII check expects redaction in storage. If `/api/inspect` doesn't surface the redaction, verify `vector_store` directly (see `tools/run_tier0_checks.ps1`).
