# End-to-End (E2E) Testing

This guide documents the profile/sector E2E runner used for local validation.

## What it does
- Starts the app for each profile (dev, standard, enterprise, govcloud)
- Uses profile-compatible build editions automatically (`govcloud` -> `-Pedition=government`, others default to `enterprise`)
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

### Build edition control
The profile runner is edition-aware:
- `govcloud` runs with Gradle `-Pedition=government`
- `dev`, `standard`, and `enterprise` run with a default non-govcloud edition (`enterprise`)

You can override the default non-govcloud edition:
```
$env:SENTINEL_E2E_BUILD_EDITION="enterprise"
pwsh -File tools/run_e2e_profiles.ps1
```

## CI-lite pipeline E2E
```
./gradlew ciE2eTest
```
Uses the `ci-e2e` + `dev` test profiles (`src/test/resources/application-ci-e2e.yml`) with an in-memory vector store and stubbed chat/embedding models.

## CI-lite OIDC enterprise E2E
```
./gradlew ciOidcE2eTest
```
Uses `ci-e2e` + `enterprise` with OIDC mode and local signed JWT/JWKS fixtures to validate the enterprise bearer-token auth path in CI.

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

### Enterprise UI/UAT (bounded, phased)
Use the suite wrapper so runs are time-bounded and do not hang indefinitely.

Core phase (all enterprise checks except dedicated PII phase):
```powershell
$env:RUN_LABEL="ENTERPRISE_UAT_CORE"
$env:TEST_SCOPE="full"
$env:SKIP_PII="true"
pwsh -File tools/playwright-runner/run-ui-suite.ps1 `
  -BaseUrl "http://127.0.0.1:18080" `
  -Profile "enterprise" `
  -AuthMode "STANDARD" `
  -RunLabel $env:RUN_LABEL `
  -UiTimeoutSec 780
```

PII phase (dedicated redaction validation):
```powershell
$env:RUN_LABEL="ENTERPRISE_UAT_PII"
$env:TEST_SCOPE="pii"
$env:SKIP_PII="false"
pwsh -File tools/playwright-runner/run-ui-suite.ps1 `
  -BaseUrl "http://127.0.0.1:18080" `
  -Profile "enterprise" `
  -AuthMode "STANDARD" `
  -RunLabel $env:RUN_LABEL `
  -UiTimeoutSec 420
```

Notes:
- `run-ui-suite.ps1` enforces a hard timeout (`-UiTimeoutSec`) for `run-ui-tests.js`.
- Default local enterprise UAT behavior sets `DEEP_ANALYSIS_MODE=off` in the suite wrapper to keep runs stable and bounded.
- PII scope is handled via `TEST_SCOPE=pii` in `run-ui-tests.js`.

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
- Sets `AIRGAP_MODEL_VALIDATION_ENABLED=false` for local/CI smoke runs so govcloud profile tests do not require preloaded local model artifacts

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
- Govcloud health timeout before API checks: inspect `build/e2e-results/boot_govcloud_<timestamp>.log` for startup failures (TLS, auth mode, or air-gap model validation).
- Connection refused: ensure MongoDB and Ollama are running.
- 429 rate limit exceeded during automated bulk uploads: temporarily set `APP_RATE_LIMIT_ENABLED=false` for the run, then re-enable.
- `pwsh` not found: install PowerShell 7 or run profiles individually without govcloud.

## GitHub Actions
The repo CI workflow is `.github/workflows/ci.yml` and runs unit tests, enterprise packaging (`-Pedition=enterprise build -x test`), `ciE2eTest`, and `ciOidcE2eTest`. There is no full E2E workflow in this repository.
