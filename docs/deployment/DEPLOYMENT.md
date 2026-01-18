# SENTINEL Deployment Guide

This document covers all deployment scenarios for the SENTINEL Intelligence Platform.

---

## Quick Start (Local Development)

**Prerequisites:** Java JDK 21, MongoDB running locally or via Atlas

### Windows
```batch
.\scripts\start_sentinel.bat
```

### Linux/macOS
```bash
chmod +x ./scripts/start_sentinel.sh
./scripts/start_sentinel.sh
```

### Manual Gradle
```bash
./gradlew bootRun --args='--spring.profiles.active=dev'
```

**Verify:** Open http://localhost:8080

---

## Docker Deployment (Standard)

**Prerequisites:** Docker, Docker Compose

```bash
# Start all services (MongoDB + SENTINEL)
docker-compose up -d

# View logs
docker-compose logs -f sentinel-app

# Stop
docker-compose down
```

**Environment Variables:**
| Variable | Required | Description |
|----------|----------|-------------|
| `MONGODB_URI` | No | MongoDB connection string (default: local) |
| `OLLAMA_HOST` | No | Ollama URL (default: http://localhost:11434) |

> **Note:** SENTINEL uses local Ollama for LLM inference - no external API keys required.

---

## Air-Gapped Deployment (Government/Classified)

For environments without internet access (DoD IL4/IL5, SCIF).

```bash
# Pre-install (while connected):
docker pull mongo:7.0.14
docker pull ollama/ollama:0.3.14
docker exec -it ollama ollama pull llama3
docker exec -it ollama ollama pull nomic-embed-text

# Deploy (air-gapped):
docker-compose -f docker-compose.local.yml up -d
```

**Features:**
- Local MongoDB (no cloud)
- Local Ollama for AI (no internet)
- CAC/PIV authentication enabled
- Fail-closed audit logging

---

## Cloud Deployment

### AWS (ECS/Fargate)
1. Push image to ECR: `docker tag sentinel:latest <account>.dkr.ecr.<region>.amazonaws.com/sentinel:latest`
2. Create ECS Task Definition with environment variables
3. Deploy to Fargate with ALB

### Azure (Container Apps)
1. Push to ACR: `az acr build --registry <registry> --image sentinel:latest .`
2. Deploy via `az containerapp create`

### GCP (Cloud Run)
1. Push to Artifact Registry
2. Deploy: `gcloud run deploy sentinel --image <image>`

**Required Secrets (all platforms):**
- `MONGODB_URI` - Atlas connection string
- `OLLAMA_HOST` - Ollama endpoint (if not co-located)

---

## Verification Checklist

After deployment, confirm:

- [ ] UI loads at `http://<host>:8080`
- [ ] `/api/status` returns `{"systemStatus":"NOMINAL"}`
- [ ] `/api/health` returns 200 OK
- [ ] Document upload works
- [ ] Query returns results

---

## Troubleshooting

| Issue | Cause | Solution |
|-------|-------|----------|
| Port 8080 in use | Another service | Change port in compose or use `-p 8081:8080` |
| MongoDB connection failed | Service not started | `docker-compose up mongo-db` first |
| Out of memory | Insufficient resources | Increase Docker memory limits |
| Slow startup | Model loading | Wait 60s for health check; Ollama needs time |

---

## Profiles Reference

| Profile | Auth Mode | Use Case |
|---------|-----------|----------|
| `dev` | DEV (demo user) | Local development |
| `govcloud` | CAC/PIV | Government deployments |
| `enterprise` | OIDC | Enterprise SSO |

---

## Ansible Deployment (Production)

For automated multi-server deployments, use the Ansible playbooks in `ansible/`:

```bash
# Full stack deployment (MongoDB + Ollama + SENTINEL)
ansible-playbook -i ansible/inventory/production.yml ansible/playbooks/site.yml

# SENTINEL-only (when MongoDB/Ollama already deployed)
ansible-playbook -i ansible/inventory/production.yml ansible/playbooks/sentinel.yml

# Air-gapped environments
ansible-playbook -i ansible/inventory/airgap.yml ansible/playbooks/site.yml
```

See `ansible/README.md` for full documentation.
