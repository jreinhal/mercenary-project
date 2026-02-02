# Medical Go-Live Runbook (HIPAA Strict)

Use this runbook for final validation and launch of a medical deployment.

## Pre-go-live (T-7 to T-1 days)
1) Confirm all HIPAA strict env vars are set (see MEDICAL_DEPLOYMENT_CHECKLIST.md).
2) Verify OIDC issuer, JWKS, and audience validation.
3) Validate MFA claims with your IdP (amr/acr).
4) Confirm MongoDB TLS and encryption at rest.
5) Confirm keyring configuration:
   - Integrity keys loaded with active key id.
   - Tokenization HMAC/AES keys loaded with active key id.
6) Validate audit endpoints and permissions:
   - /api/hipaa/audit/events
   - /api/hipaa/audit/export
7) Confirm Swagger UI disabled in prod.

## Go-live (T-0)
1) Deploy the release to production.
2) Run smoke tests:
   - OIDC login with MFA
   - Query in MEDICAL sector (redacted response)
   - Verify audit event is present
3) Export HIPAA audit (CSV) and validate access control.
4) Verify session timeout (15 minutes default) by idle session expiry.

## Post-go-live (T+1 to T+7 days)
1) Monitor authentication failures and lockouts.
2) Review HIPAA audit logs daily.
3) Verify no unexpected session exports or feedback artifacts.

## Key rotation procedure (monthly or per policy)
1) Generate new key material.
2) Add new keyId to the keyring (KMS or local).
3) Set *_ACTIVE_KEY_ID to the new keyId and redeploy.
4) Keep old keys in the keyring until all legacy data is re-signed or expires.

## Rollback plan
1) Revert to previous release.
2) Revert active key ids if needed.
3) Confirm audit endpoints remain accessible.

## Validation checklist (quick)
- HIPAA strict enabled and validated at startup
- OIDC MFA claims validated
- TLS enforced for LLM/OCR/Mongo
- Audit export works and is permission-gated
- Session auto-logoff verified
