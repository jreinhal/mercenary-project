# Pull Request Checklist

## Summary
- What does this change do?
- Why is it needed?

## Validation
- [ ] `./gradlew test` (or CI equivalent) is green
- [ ] `./gradlew ciE2eTest` is green (if touched UI/RAG)

## Risk / Rollback
- [ ] Low risk or documented risks
- [ ] Rollback plan (if applicable)

## Notes
- Auto-merge will be enabled once required checks pass.
