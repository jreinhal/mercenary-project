# Testing

## Run all tests
```
./gradlew test
```

## Run a specific test
```
./gradlew test --tests "*CacAuthentication*"
```

## End-to-end runs
For full-profile/full-sector E2E validation, use:
```
pwsh -File tools/run_e2e_profiles.ps1
```
See E2E_TESTING.md for details and profile-specific notes.

## Notes
- The dev profile enables Swagger UI
- Integration tests require MongoDB
