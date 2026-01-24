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
