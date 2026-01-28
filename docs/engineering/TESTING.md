# Testing

## Run all tests
```
./gradlew test
```

## Run a specific test
```
./gradlew test --tests "*CacAuthentication*"
```

## CI-lite pipeline E2E
```
./gradlew ciE2eTest
```
Uses the `ci-e2e` + `dev` test profiles with stubbed LLM/vectorstore components.

## End-to-end runs
For full-profile/full-sector E2E validation, use:
```
pwsh -File tools/run_e2e_profiles.ps1
```
See E2E_TESTING.md for details and profile-specific notes.

## Full checklist (preflight + full run)
```
pwsh -File tools/run_full_checklist.ps1
```
Defaults to the same scope as the **Full E2E Checklist** workflow (dev/ENTERPRISE matrix).
Use `-FullMatrix` to run all profiles/sectors and include no-results UI checks.
Use `-IncludeNoResults` to run the empty-dataset UI checks without the full matrix.

## Bootstrap helper
```
pwsh -File tools/dev_bootstrap.ps1
```
Starts/validates MongoDB + Ollama and ensures required models are present.

## Tier0 quick checks
```
pwsh -File tools/run_tier0_checks.ps1
```
PII validation passes if redaction is observed via `/api/inspect` or in `vector_store` (storage-of-record).

## Notes
- The dev profile enables Swagger UI
- Integration tests require MongoDB
