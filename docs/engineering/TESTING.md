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
Uses the `ci-e2e` + `dev` test profiles with an in-memory vector store and stubbed chat/embedding models.

## CI-lite OIDC enterprise E2E
```
./gradlew ciOidcE2eTest
```
Uses `ci-e2e` + `enterprise` with OIDC mode and a locally generated JWT/JWKS test fixture to validate the enterprise auth path.

## End-to-end runs (local)
For full-profile/full-sector E2E validation, use:
```
pwsh -File tools/run_e2e_profiles.ps1
```
Requires MongoDB + Ollama. See E2E_TESTING.md for profile-specific notes.

## UI smoke tests (local)
```
cd tools/playwright-runner
npm ci
node run-ui-tests.js
```
Requires the app running locally and Microsoft Edge installed.

## Notes
- The dev profile enables Swagger UI
- Local E2E runs require MongoDB + Ollama
