# Mercenary Security Guide (Zero to Hero)

This guide explains how security, authentication, and RBAC work in this codebase today. It is meant to be a practical reference you can use to configure, operate, and troubleshoot.

## 1) The big picture

There are three core ideas:

- Authentication: who the user is (DEV, STANDARD, OIDC, CAC).
- Authorization (RBAC): what the user is allowed to do (roles and permissions).
- Data protection: how sensitive data is gated (clearance levels, sectors, tokenization).

Everything flows through a small set of components:

- `SecurityFilter` attaches the authenticated user to the request.
- `SecurityConfig` defines profile-specific security policies and endpoint access.
- Controllers check `UserRole` permissions and `ClearanceLevel` where needed.

## 2) Deployment profiles and auth modes

The active profile and auth mode drive behavior:

- DEV (profile `dev`, auth `DEV`)
  - Auto-provisions a demo user with ADMIN and TOP_SECRET clearance.
  - Should only be used for local development.
  - The app will block DEV auth if you run it outside the dev profile (see `MercenaryApplication`).

- STANDARD (profile `standard`, auth `STANDARD`)
  - Uses session-based login with username/password.
  - Basic Auth headers are disabled by default; can be re-enabled for legacy clients.
  - CSRF protection enabled (cookie-based tokens).

- OIDC (auth `OIDC`)
  - Uses Bearer JWTs from an external identity provider.
  - Validates signatures, issuer, audience, and expiration.
  - Can auto-provision users with least-privilege defaults.

- CAC (auth `CAC`, profile `govcloud`)
  - Uses X.509 client certificates (PIV/CAC).
  - Requires mutual TLS configured at the server or reverse proxy.
  - User identity extracted from certificate subject DN.

Key config locations:

- `src/main/resources/application.yaml`
- `src/main/java/com/jreinhal/mercenary/MercenaryApplication.java`
- `src/main/java/com/jreinhal/mercenary/config/SecurityConfig.java`

## 3) STANDARD login flow (session-based)

STANDARD is now a session login (not Basic headers):

1. UI requests CSRF token:
   - `GET /api/auth/csrf`
2. UI logs in:
   - `POST /api/auth/login` with JSON `{ "username": "...", "password": "..." }`
   - CSRF header: `X-XSRF-TOKEN` from the CSRF token response
3. Server sets session cookie (`JSESSIONID`).
4. Subsequent API calls use the session cookie.

Legacy Basic Auth (optional):

- Set `APP_STANDARD_ALLOW_BASIC=true` to re-enable Basic Auth.
- This is less safe over plain HTTP. Use HTTPS if you must enable it.

## 4) OIDC flow (Bearer tokens)

OIDC expects a valid JWT in the Authorization header:

- `Authorization: Bearer <token>`

The token is validated in `JwtValidator` for:

- Issuer, audience, signature, expiration
- Clock skew bounds

Auto-provisioning controls (in config):

- `app.oidc.auto-provision`
- `app.oidc.default-role`
- `app.oidc.default-clearance`
- `app.oidc.require-approval`

Defaults are capped to prevent over-privileged users.

## 5) CAC flow (X.509)

CAC authentication uses client certificates:

- The server (or proxy) presents the cert to the app.
- The app extracts the subject DN or CN and matches a user.
- Users may be auto-provisioned with VIEWER role by default.

See:

- `src/main/java/com/jreinhal/mercenary/government/auth/CacAuthenticationService.java`

## 6) RBAC: roles and permissions

Roles are defined in `UserRole`:

| Role        | Permissions                               |
|-------------|--------------------------------------------|
| ADMIN       | QUERY, INGEST, DELETE, MANAGE_USERS, VIEW_AUDIT, CONFIGURE |
| ANALYST     | QUERY, INGEST                              |
| VIEWER      | QUERY                                      |
| PHI_ACCESS  | QUERY (used with PHI reveal endpoints)     |
| AUDITOR     | QUERY, VIEW_AUDIT                          |

Permissions are enforced in controllers and filters. Examples:

- Admin-only endpoints: `SecurityConfig` restricts `/api/admin/**`.
- Query endpoints check `UserRole.Permission.QUERY`.
- Ingest endpoints check `UserRole.Permission.INGEST`.
- Audit endpoints check `UserRole.Permission.VIEW_AUDIT`.
- PHI reveal requires `PHI_ACCESS` or `ADMIN`.

## 7) Clearance and sectors

Clearance levels (`ClearanceLevel`) are used for data sensitivity.

Departments (`Department`) define sector access and required clearance:

- GOVERNMENT and MEDICAL require SECRET
- FINANCE requires CUI
- ACADEMIC and ENTERPRISE are UNCLASSIFIED

Each user has:

- `allowedSectors` (which sectors they can access)
- `clearance` (which classification levels they can access)

Controllers and sector config endpoints use these to filter access.

## 8) Security layers and hardening

Key defenses in this app:

- CSRF protection in standard and govcloud profiles
- CSP headers via `CspNonceFilter` with strict `script-src 'self'` and `style-src 'self'`
- Rate limiting:
  - `PreAuthRateLimitFilter` for unauthenticated requests
  - `RateLimitFilter` for authenticated requests
- Session cookie hardening:
  - SameSite=Lax (CSRF defense)
  - HttpOnly=true (XSS defense)
  - Secure=configurable via `COOKIE_SECURE` env var (set true for HTTPS)
- DEV mode guardrails (rejects DEV auth outside dev profile)

## 9) Sensitive data handling

PII/PHI support includes:

- Tokenization via `TokenizationVault` (AES-GCM + HMAC token IDs)
- Optional detokenization with audit logging
- PHI reveal endpoints require elevated roles

Production must set:

- `app.tokenization.secret-key` (Base64 256-bit key)

## 10) Operational checklist (quick start)

STANDARD (session login):

1. Set:
   - `APP_PROFILE=standard`
   - `AUTH_MODE=STANDARD`
   - `SENTINEL_BOOTSTRAP_ENABLED=true`
   - `SENTINEL_ADMIN_PASSWORD=...`
2. Start the app.
3. Log in via the UI (sign-in modal).

OIDC:

1. Set:
   - `AUTH_MODE=OIDC`
   - `OIDC_ISSUER=...`
   - `OIDC_CLIENT_ID=...`
2. Ensure your clients send `Authorization: Bearer <token>`.

CAC (govcloud):

1. Set:
   - `APP_PROFILE=govcloud`
   - `AUTH_MODE=CAC`
2. Configure mutual TLS and pass client certs.

## 11) Troubleshooting

- 401 errors in STANDARD:
  - Verify `/api/auth/csrf` then `/api/auth/login` succeeded.
  - Check session cookies are present in the browser.

- 403 errors:
  - Check role permissions and clearance.
  - Verify the user is active and not pending approval.

- DEV mode blocked:
  - The app blocks DEV auth outside the dev profile.
  - Switch to STANDARD/OIDC/CAC or set explicit override flags (not recommended).
