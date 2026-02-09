# Claude Long-Term Memory for mercenary Project

## CRITICAL DIRECTIVES

### Pre-Commit Verification (MANDATORY)
**ALWAYS run a full build/ops check before ANY commit.**

Before executing `git commit`:
1. Run `./gradlew build -x test` to verify compilation succeeds
2. If tests are relevant, run `./gradlew test`
3. Only proceed with commit if build passes
4. Never commit broken code

This applies to EVERY session, EVERY commit, no exceptions.

---

## Project Context
- Spring Boot 3.4.2 + Java 21
- Multi-edition build (trial, enterprise, medical, government)
- Air-gap compatible RAG platform
- See CLAUDE.md for full development guidelines
