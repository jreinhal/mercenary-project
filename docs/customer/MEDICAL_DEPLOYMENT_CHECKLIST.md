# Medical Deployment Checklist (HIPAA Strict)

Use this checklist before running a medical edition in production. It assumes HIPAA strict mode is enabled and the deployment will handle ePHI.

## 1) Identity and access
- Set APP_PROFILE=enterprise (OIDC) or govcloud (CAC). Avoid STANDARD/DEV for medical.
- Ensure OIDC issuer uses HTTPS and is reachable by the app.
- Enable approval gate and MFA for medical:
  - HIPAA_OIDC_AUTO_PROVISION=false
  - HIPAA_OIDC_REQUIRE_APPROVAL=true
  - HIPAA_OIDC_REQUIRE_MFA=true
- Configure MFA claim values to match your IdP (if needed):
  - OIDC_MFA_CLAIMS=mfa,otp,pwd+otp,hwk
  - OIDC_MFA_ACR=<your IdP acr values>
- Confirm AUDITOR role assignment for audit review/export.

## 2) HIPAA strict mode
- HIPAA_ENFORCE_MEDICAL=true
- HIPAA_STRICT=false (optional override; leave false unless you want strict across non-medical)
- HIPAA_REDACT_RESPONSES=true
- HIPAA_DISABLE_FEEDBACK=true
- HIPAA_DISABLE_SESSION_MEMORY=true
- HIPAA_DISABLE_SESSION_EXPORT=true
- HIPAA_DISABLE_VISUAL=true
- HIPAA_DISABLE_EXPERIENCE=true
- HIPAA_SUPPRESS_LOGS=true
- HIPAA_ENFORCE_TLS=true
- HIPAA_SESSION_TIMEOUT_MINUTES=15

## 3) TLS and transport security
- Ensure HTTPS at the reverse proxy and HSTS enabled.
- Ensure MongoDB URI is TLS:
  - MONGODB_URI=mongodb+srv://... or mongodb://...?tls=true
- Ensure LLM and OCR endpoints are HTTPS:
  - OLLAMA_URL=https://...
  - OCR_SERVICE_URL=https://...

## 4) Key management and rotation
Choose one of the two approaches:

### A) KMS-backed keyrings (recommended)
- KMS_ENABLED=true
- KMS_REGION=<region>
- INTEGRITY_KEYS_KMS=<keyId:ciphertextBase64,...>
- INTEGRITY_ACTIVE_KEY_ID=<keyId>
- TOKENIZATION_HMAC_KEYS_KMS=<keyId:ciphertextBase64,...>
- TOKENIZATION_AES_KEYS_KMS=<keyId:ciphertextBase64,...>
- TOKENIZATION_ACTIVE_KEY_ID=<keyId>

KMS ciphertext should decrypt to a base64-encoded key value.

### B) Local keyrings
- INTEGRITY_KEYS=<keyId:base64,...>
- INTEGRITY_ACTIVE_KEY_ID=<keyId>
- TOKENIZATION_HMAC_KEYS=<keyId:base64,...>
- TOKENIZATION_AES_KEYS=<keyId:base64,...>
- TOKENIZATION_ACTIVE_KEY_ID=<keyId>

## 5) Storage and backups
- Encrypt MongoDB storage volumes at rest.
- Encrypt backup storage at rest.
- Restrict backup access to least privilege.
- Verify retention and purge windows for audit and session data.

## 6) Audit and monitoring
- Verify audit fail-closed is enabled (medical strict sets this at runtime).
- Validate audit endpoints:
  - GET /api/hipaa/audit/events
  - GET /api/hipaa/audit/export?format=json|csv
- Ensure logs exclude raw PHI (strict mode redaction).

## 7) Auth lockout (standard profile only)
- AUTH_LOCKOUT_ENABLED=true
- AUTH_LOCKOUT_MAX_ATTEMPTS=5
- AUTH_LOCKOUT_WINDOW_MINUTES=15
- AUTH_LOCKOUT_DURATION_MINUTES=15

## 8) UI and Swagger
- Keep Swagger UI disabled in production (SWAGGER_ENABLED=false).

## 9) Validation checks
- Run smoke tests for OIDC auth, MFA claim validation, and audit export.
- Confirm HIPAA validator rejects non-TLS endpoints and non-HTTPS CORS.
- Confirm tokenization and integrity keys load successfully on startup.
