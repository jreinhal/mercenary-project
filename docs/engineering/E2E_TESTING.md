# End-to-End (E2E) Testing

This guide documents the profile/sector E2E runner used for local validation.

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

## Full profile E2E run
```
pwsh -File tools/run_e2e_profiles.ps1
```
Starts each profile, ingests a test document per sector, and runs `/ask`, `/ask/enhanced`, and `/inspect`.

## CI-lite pipeline E2E
```
./gradlew ciE2eTest
```
Uses the `ci-e2e` + `dev` test profiles (`src/test/resources/application-ci-e2e.yml`) with an in-memory vector store and stubbed chat/embedding models.

## UI smoke tests (Playwright runner)
1) Start the app (dev or standard profile).
2) Run the Playwright checks:
```
cd tools/playwright-runner
npm ci
node run-ui-tests.js
```
Notes:
- Requires Node.js and Microsoft Edge (Playwright uses the Edge channel).
- For RBAC checks in the UI script, start the app with `APP_PROFILE=dev,test-users` to seed test users.

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
All E2E results are written to:
```
build/e2e-results/
```

Files per run:
- `e2e_results_<timestamp>.md`
- `e2e_results_<timestamp>.json`
- `boot_<profile>_<timestamp>.log`
- `boot_<profile>_<timestamp>.log.err`

Playwright UI outputs (local):
- `tools/playwright-runner/results_*.json`
- `tools/playwright-runner/screens/`

## Troubleshooting
- Govcloud 403 on ingest: confirm `APP_CSRF_BYPASS_INGEST=true` was set for the run.
- Connection refused: ensure MongoDB and Ollama are running.
- `pwsh` not found: install PowerShell 7 or run profiles individually without govcloud.

## GitHub Actions
The repo CI workflow is `.github/workflows/ci.yml` and runs unit tests plus `ciE2eTest`. There is no full E2E workflow in this repository.
