# Enterprise Rename Audit Findings (Professional -> Enterprise)

Date: 2026-02-09

## Summary

The core rename from the **license/build edition** name `PROFESSIONAL` to `ENTERPRISE` is already present in the build system and license service. The only remaining uses of the word "Professional" in this checkout are:

- A small number of documentation strings describing tier behavior.
- An intentional legacy-compatibility mapping path and its unit test that still contain the literal string `PROFESSIONAL`.

Separately, the repo still has two different meanings for "Enterprise":

- `APP_PROFILE=enterprise` (runtime profile selecting **OIDC auth**).
- `sentinel.license.edition=ENTERPRISE` (license/build edition name).

That dual meaning can confuse packaging and customer messaging if not explicitly defined.

## Confirmed: Edition Renamed in Build + License Plumbing

- Gradle edition default is now `enterprise` and the allowed editions list includes `enterprise`:
  - `build.gradle` (edition selection and allowed list, jar base name `sentinel-${edition}`)
- License edition default is now `ENTERPRISE`, enum includes `ENTERPRISE`, and parsing supports mapping legacy `PROFESSIONAL`:
  - `src/main/java/com/jreinhal/mercenary/core/license/LicenseService.java`

## Remaining "Professional" References (Non-Legacy)

These are wording-only references (docs/UI copy), not enum values.

- ~~`README.md` (tier behavior copy: "Professional/Trial ...")~~ **FIXED**
- ~~`docs/engineering/ARCHITECTURE.md` (tier behavior copy: "Professional/Trial ...")~~ **FIXED**
- ~~`docs/engineering/plans/RAG_EVOLUTION_IMPLEMENTATION_PLAN.md` (copy: "Trial/Professional: ...")~~ **FIXED**
- `src/test/resources/test_docs/enterprise_earnings_q4.txt` (domain content: "Professional Services"; not a tier reference — kept as-is)
- `src/main/resources/static/css/sentinel.css` (style comments: "professional palette", "professional tone" — kept as-is)
- `src/main/resources/static/js/sentinel-app.js` (comments: "professional look", "professional typography" — kept as-is)

## Intentional Legacy References (Expected to Remain)

These uses of `PROFESSIONAL` are purposeful and should remain if you want backward compatibility with existing configs/tests.

- Legacy edition mapping:
  - `src/main/java/com/jreinhal/mercenary/core/license/LicenseService.java`
  - Behavior: when `sentinel.license.edition=PROFESSIONAL`, it maps to `ENTERPRISE`.
- Unit test verifying the mapping:
  - `src/test/java/com/jreinhal/mercenary/core/license/LicenseControllerTest.java` (`legacyProfessionalEditionMapsToEnterprise`)

## Packaging / Terminology Risks

Two different knobs currently use "enterprise":

- `APP_PROFILE=enterprise` selects **OIDC** auth mode:
  - `src/main/resources/application.yaml` (enterprise profile)
  - `docs/customer/SECURITY.md`
- `-Pedition=enterprise` selects **edition build packaging**:
  - `build.gradle`

This is workable, but the term "Enterprise" now refers to both:

- "Enterprise auth profile"
- "Enterprise license tier"

If you want clean customer-facing packaging, consider explicitly documenting the distinction (profile vs edition) or renaming one of them (for example, `APP_PROFILE=oidc` while keeping edition `ENTERPRISE`), but that is a product decision.

## Local Working Tree Note

This workspace currently has uncommitted local modifications unrelated to the rename audit (they were present before the audit work began):

- `src/main/java/com/jreinhal/mercenary/controller/MercenaryController.java`
- `src/main/java/com/jreinhal/mercenary/filter/SecurityFilter.java`
- `src/main/java/com/jreinhal/mercenary/service/PromptGuardrailService.java`
- `src/main/java/com/jreinhal/mercenary/service/RagOrchestrationService.java`
- `src/test/java/com/jreinhal/mercenary/filter/SecurityFilterTest.java`
- `tools/playwright-runner/run-ui-tests.js`
- Untracked: `src/main/java/com/jreinhal/mercenary/security/ContentSanitizer.java`

These changes do not affect the scan results above, but they will affect any future pull/merge operations in this workspace.

