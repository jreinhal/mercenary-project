# Operations

This guide covers day-to-day operation and health checks.

## Startup order
1) MongoDB
2) Ollama
3) Application

## Health and telemetry
- GET /api/health
- GET /api/status
- GET /api/telemetry
- GET /api/admin/health (ADMIN only)

## Logs
- Application logs are written to stdout/stderr.
- MongoDB logs are stored at the path defined in mongod.cfg.
- Correlation IDs are included in log level pattern.

## Backups
Use MongoDB tools. See BACKUP_RESTORE.md for detailed steps.

## Hardening references
- Linux host hardening: HARDENING_LINUX.md
- Compliance expectations by sector: COMPLIANCE_APPENDICES.md

## Capacity and performance tuning
Key tuning controls (see docs/engineering/CONFIGURATION.md):
- sentinel.performance.rag-core-threads
- sentinel.performance.rag-max-threads
- sentinel.performance.rag-queue-capacity
- sentinel.rag.max-context-chars
- sentinel.rag.max-docs

## Routine checks
- Confirm MongoDB connectivity via /api/status
- Confirm Ollama is reachable via /api/telemetry
- Review audit logs for auth failures and access denials
