# Contributing

## Branch naming
Use short, descriptive branch names:
- `feature/<short-scope>`
- `fix/<short-scope>`
- `chore/<short-scope>`

Examples:
- `feature/edition-response-policy`
- `fix/ui-header-overlap`

## PR-only workflow
- All changes must go through a Pull Request.
- Direct commits to `master` are discouraged.
- Auto-merge should be enabled after CI passes.

## Required checks
CI must pass before merge:
- `CI / build`

## Notes
If you need new checks or branch protection, update GitHub repo settings to require PR reviews and CI checks.
