# Pull Request Checklist

## Summary
- What does this change do?
- Why is it needed?

## 📖 Documentation Review (if applicable)
If this PR includes documentation or code review documents:
- [ ] I have read the summary/navigation document
- [ ] I have reviewed the key findings
- [ ] I understand the action items (if any)

**For code review PRs:** Start with `CODE_REVIEW_README.md` or `SECURITY_POSTURE.md` for quick navigation.

## Validation
- [ ] `./gradlew test` (or CI equivalent) is green
- [ ] `./gradlew ciE2eTest` is green (if touched UI/RAG)

## Risk / Rollback
- [ ] Low risk or documented risks
- [ ] Rollback plan (if applicable)

## Notes
- Auto-merge will be enabled once required checks pass.
