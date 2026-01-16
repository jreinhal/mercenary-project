# SECURITY GATES & AUDIT PROTOCOL
**Status:** Mandatory Reference
**Trigger:** Before any "Scan" or "Release" task.

This checklist institutionalizes the lessons learned from the "External Scan 2026-01-15" (The "Inspect Gap" Incident).

## 1. Controller & Endpoint Security (The "Inspect Gap")
**Rule:** Input validation (e.g., checking enums) is NOT Authorization.
- [ ] **Method-Level Auth:** Does *every* public method (`@GetMapping`, `@PostMapping`) have an explicit `user.hasPermission()` or `user.canAccessSector()` check?
    -   *Red Flag:* Methods that valid inputs but ignore the `User` object.
- [ ] **Parameter Parity:** If `/ask` requires permissions X, Y, and Z, does `/inspect` (or any other secondary endpoint) require the exact same permissions?
- [ ] **Fail-Secure:** If `SecurityContext.getCurrentUser()` is null, does the method explicitly invoke `auditService.logAccessDenied` and return 401/403?

## 2. Configuration & Filter Ordering (The "Rate Limit" Bug)
**Rule:** Spring annotations (`@Order`) are relative and must be verified explicitly.
- [ ] **Filter Topology:** List all Filters. Verify `SecurityFilter` (Auth) runs *before* `RateLimitFilter` (Throttling) or any other logic that depends on user identity.
    -   *Current Order:* `CspNonceFilter(0)` → `LicenseFilter(1)` → `SecurityFilter(2)` → `RateLimitFilter(3)`
    -   *Rule:* Auth filters must precede any filter that reads `SecurityContext`.
- [ ] **Bean Precendence:** Verify `order` values are distinct.

## 3. Data Safety & Caching (The "Raw Byte" Leak)
**Rule:** Never cache what you haven't sanitized.
- [ ] **Cache Inspection:** Identify every `cache.put()`. Trace the value back to its source.
    -   *Violation:* `cache.put(key, file.getBytes())` (Raw data).
    -   *Requirement:* `cache.put(key, sanitize(parse(file)))`.
- [ ] **Serialization:** Ensure binary formats (PDF, Docx) are converted to safe text *before* storage in text-based caches.

## 4. Adversarial Mental Model
- [ ] **"The Curious Analyst":** Can I access data from a different sector just by guessing a filename?
- [ ] **"The Impersonator":** Can I trigger a logic path intended for a different role (e.g., anonymous vs admin)?
