# AGENTS.md

Instructions for all AI coding agents (Claude Code, GitHub Copilot, Codex, etc.) working in this repository.

**Primary reference: see `CLAUDE.md`** for full build commands, architecture, constraints, security audit checklist, and testing tiers.

## Quick Reference

```bash
./gradlew test                                    # All unit + integration tests
./gradlew test --tests "*SomeTest.someMethod"     # Single test method
./gradlew clean test -Plint -PlintWerror          # Lint-enforced (CI gate)
./gradlew ciE2eTest                               # Pipeline E2E (stubbed, no infra needed)
./gradlew ciOidcE2eTest                           # OIDC E2E
./gradlew build -Pedition=government              # Edition-specific build
```

## Rules for All Agents

1. **Never delete source code.** No `rm -rf`, no `git clean -fdx`. Use `./gradlew clean` for build cache.
2. **Edition isolation is compile-time.** Government/medical code is excluded via Gradle source sets, not runtime flags. Never import `government.*` from `enterprise.*` code.
3. **Air-gap compliance.** No external API calls. No cloud AI autoconfiguration. All LLM via local Ollama.
4. **Security fixes go in `core/`** so they apply to all editions.
5. **Commit messages:** `Co-Authored-By: Claude <noreply@anthropic.com>` (or equivalent for your agent).
6. **Branch naming:** `feature/`, `fix/`, `chore/` prefixes. PRs target `master`.
