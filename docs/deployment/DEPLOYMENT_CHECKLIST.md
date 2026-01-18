# SENTINEL Production Launch Checklist

**Version:** 2.0.0
**Last Updated:** 2026-01-11

This checklist ensures SENTINEL is properly configured for production deployment as a turnkey product. Complete all items before going live.

---

## Prerequisites

### Required Software

| Software | Version | Purpose | Download |
|----------|---------|---------|----------|
| **Java JDK** | 21+ | Application runtime | [Adoptium](https://adoptium.net/) |
| **MongoDB** | 7.0+ | Document & vector storage | [MongoDB](https://www.mongodb.com/try/download/community) |
| **Ollama** | Latest | Local LLM inference | [Ollama](https://ollama.com/download) |

### Installation Verification

After installing, verify each tool is accessible from the command line:

```bash
# Verify Java
java -version
# Expected: openjdk version "21.x.x"

# Verify MongoDB
mongod --version
# Expected: db version v7.x.x

# Verify Ollama
ollama --version
# Expected: ollama version x.x.x
```

### Adding Tools to System PATH (If Commands Not Found)

If any command returns "not found", add the installation directory to your system PATH:

**Windows (PowerShell as Administrator):**
```powershell
# Find installation paths first, then add to PATH:
# MongoDB (typical location)
[Environment]::SetEnvironmentVariable("Path", $env:Path + ";C:\Program Files\MongoDB\Server\8.0\bin", "Machine")

# Ollama (typical location)
[Environment]::SetEnvironmentVariable("Path", $env:Path + ";C:\Users\<username>\AppData\Local\Programs\Ollama", "Machine")

# Restart terminal after changes
```

**Linux/macOS:**
```bash
# Add to ~/.bashrc or ~/.zshrc
export PATH="$PATH:/usr/local/bin"  # Typical location for MongoDB/Ollama
source ~/.bashrc
```

### Required Ollama Models

Download these models before starting SENTINEL:

```bash
# LLM for inference (required)
ollama pull llama3

# Embedding model for vector search (required)
ollama pull nomic-embed-text

# Verify models are available
ollama list
```

### Start Services

```bash
# Start MongoDB (if not running as a service)
mongod --dbpath /data/db

# Start Ollama (if not running as a service)
ollama serve

# Verify services are running
curl -s http://localhost:27017 >/dev/null && echo "MongoDB: OK" || echo "MongoDB: NOT RUNNING"
curl -s http://localhost:11434/api/tags >/dev/null && echo "Ollama: OK" || echo "Ollama: NOT RUNNING"
```

---

## Quick Launch Verification (5-Minute Check)

Run these commands to verify a healthy deployment:

```bash
# 1. Health Check - Should return "SYSTEMS NOMINAL"
curl -s http://localhost:8080/api/health

# 2. Telemetry Check - Should show document count and db status
curl -s http://localhost:8080/api/telemetry

# 3. System Status - Full system overview
curl -s http://localhost:8080/api/status

# 4. UI Access - Should load without errors (200 OK)
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080
```

**Expected Results:**
| Check | Expected |
|-------|----------|
| Health | `SYSTEMS NOMINAL` |
| Telemetry | `{"documentCount": N, "dbOnline": true}` |
| UI Access | HTTP 200 |

If all checks pass, proceed to full verification below.

---

## Pre-Launch Functional Testing

### Document Ingestion Test
```bash
# Upload a test document
echo "Test document content" > /tmp/test.txt
curl -X POST "http://localhost:8080/api/ingest/file" \
  -F "file=@/tmp/test.txt" \
  -F "dept=OPERATIONS"
# Expected: "SECURE INGESTION COMPLETE: test.txt (XXms)"
```

### Query Test
```bash
# Query the test document
curl "http://localhost:8080/api/ask?q=test+document&dept=OPERATIONS"
# Expected: Response mentioning test.txt
```

### Enhanced Query with Glass Box Reasoning
```bash
# Test Glass Box reasoning trace
curl "http://localhost:8080/api/ask/enhanced?q=test+document&dept=OPERATIONS"
# Expected: JSON with "reasoning" array showing pipeline steps
```

---

## Pre-Deployment Security Checklist

### 1. Authentication Configuration (CRITICAL)

| Item | Environment Variable | Required | Default | Notes |
|------|---------------------|----------|---------|-------|
| Authentication Mode | `APP_PROFILE` | **YES** | `dev` | Set to `enterprise`, `standard`, or `govcloud` |
| Admin Password | `SENTINEL_ADMIN_PASSWORD` | **YES** | Insecure default | Must be changed from default |

**Authentication Modes:**

| Mode | `APP_PROFILE` | Use Case |
|------|---------------|----------|
| Enterprise | `enterprise` | Corporate SSO via OIDC (Azure AD, Okta) |
| Standard | `standard` | Standalone username/password |
| GovCloud | `govcloud` | CAC/PIV X.509 certificates |
| Development | `dev` | **NEVER USE IN PRODUCTION** |

```bash
# Production startup example
export APP_PROFILE=enterprise
export SENTINEL_ADMIN_PASSWORD=$(openssl rand -base64 32)
```

### 2. OIDC Configuration (Enterprise Mode)

| Variable | Description | Example |
|----------|-------------|---------|
| `OIDC_ISSUER` | Identity provider URL | `https://login.microsoftonline.com/{tenant}/v2.0` |
| `OIDC_AUDIENCE` | Application client ID | `your-client-id` |
| `OIDC_JWKS_URI` | JWKS endpoint | `https://login.microsoftonline.com/{tenant}/discovery/v2.0/keys` |

### 3. Database Configuration

| Variable | Description | Default |
|----------|-------------|---------|
| `MONGODB_URI` | MongoDB connection string | `mongodb://localhost:27017/mercenary` |

**Production Requirements:**
- [ ] Enable MongoDB authentication
- [ ] Use TLS/SSL connections
- [ ] Configure replica set for high availability
- [ ] Set up automated backups

### 4. API Documentation

| Variable | Description | Recommended |
|----------|-------------|-------------|
| `SWAGGER_ENABLED` | Enable Swagger UI | `false` in production |

```bash
# Disable in production
export SWAGGER_ENABLED=false
```

### 5. Bootstrap Configuration (Standard Profile Only)

| Variable | Description | Recommended |
|----------|-------------|-------------|
| `sentinel.bootstrap.enabled` | Auto-create admin user (standard profile only) | `false` after initial setup |
| `SENTINEL_ADMIN_PASSWORD` | Admin password for bootstrap | **Required** if bootstrap enabled |

**Note:** Bootstrap is only available in the `standard` profile. For `enterprise` (OIDC) or `govcloud` (CAC/PIV) profiles, users are provisioned through the identity provider.

---

## Environment Variables Reference

### Required for All Deployments

```bash
# Authentication (REQUIRED)
APP_PROFILE=enterprise              # or 'standard' or 'govcloud'
SENTINEL_ADMIN_PASSWORD=<secure>    # Minimum 16 characters

# Database (REQUIRED)
MONGODB_URI=mongodb://user:pass@host:27017/sentinel?authSource=admin

# LLM Backend (REQUIRED)
OLLAMA_URL=http://localhost:11434
LLM_MODEL=llama3
EMBEDDING_MODEL=nomic-embed-text
```

### Required for Enterprise (OIDC) Mode

```bash
OIDC_ISSUER=https://your-idp.com
OIDC_AUDIENCE=your-client-id
OIDC_JWKS_URI=https://your-idp.com/.well-known/jwks.json
```

### Optional Configuration

```bash
# Security
SWAGGER_ENABLED=false               # Disable API docs
PII_ENABLED=true                    # Enable PII redaction
```

### Advanced RAG Features (Recommended for Production)

These features significantly improve response quality but require more compute resources:

| Feature | Variable | Default | Description |
|---------|----------|---------|-------------|
| **HiFi-RAG** | `HIFIRAG_ENABLED` | `true` | Two-pass retrieval with cross-encoder reranking. Reduces hallucinations by 60%+. |
| **HGMem** | `HGMEM_ENABLED` | `true` | Hypergraph memory for multi-hop reasoning across documents. |
| **RAGPart** | `RAGPART_ENABLED` | `true` | Corpus poisoning defense via document partitioning. |

**Note:** All advanced RAG features are enabled by default. To disable them (for lower resource environments), set the variables to `false`:
```bash
# Optional: Disable advanced features for resource-constrained environments
export HIFIRAG_ENABLED=false
export HGMEM_ENABLED=false
export RAGPART_ENABLED=false
```

**With these features enabled (default), SENTINEL provides:**
- Higher quality answers with better source matching
- Protection against adversarial document injection
- Multi-hop reasoning across related documents

---

## Docker Production Deployment

### docker-compose.prod.yml

```yaml
version: '3.8'
services:
  sentinel:
    image: sentinel:2.0.0
    ports:
      - "8080:8080"
    environment:
      - APP_PROFILE=enterprise
      - SENTINEL_ADMIN_PASSWORD=${SENTINEL_ADMIN_PASSWORD}
      - MONGODB_URI=${MONGODB_URI}
      - OLLAMA_URL=http://ollama:11434
      - OIDC_ISSUER=${OIDC_ISSUER}
      - OIDC_AUDIENCE=${OIDC_AUDIENCE}
      - OIDC_JWKS_URI=${OIDC_JWKS_URI}
      - SWAGGER_ENABLED=false
    depends_on:
      - ollama
      - mongodb
    restart: unless-stopped

  ollama:
    image: ollama/ollama:latest
    volumes:
      - ollama_data:/root/.ollama
    restart: unless-stopped

  mongodb:
    image: mongo:7
    environment:
      - MONGO_INITDB_ROOT_USERNAME=${MONGO_USER}
      - MONGO_INITDB_ROOT_PASSWORD=${MONGO_PASS}
    volumes:
      - mongo_data:/data/db
    restart: unless-stopped

volumes:
  ollama_data:
  mongo_data:
```

### .env.example

```bash
# SENTINEL Production Configuration
# Copy to .env and fill in values

# Authentication Mode (REQUIRED)
APP_PROFILE=enterprise

# Admin Password - MUST CHANGE (REQUIRED)
SENTINEL_ADMIN_PASSWORD=

# MongoDB Credentials (REQUIRED)
MONGO_USER=sentinel
MONGO_PASS=
MONGODB_URI=mongodb://sentinel:${MONGO_PASS}@mongodb:27017/sentinel?authSource=admin

# OIDC Settings (Required for enterprise mode)
OIDC_ISSUER=
OIDC_AUDIENCE=
OIDC_JWKS_URI=

# Security Settings
SWAGGER_ENABLED=false
```

---

## Post-Deployment Verification

### 1. Health Check

```bash
curl -s http://localhost:8080/api/health | jq .
```

Expected response:
```json
{
  "status": "UP",
  "components": {
    "mongo": "UP",
    "ollama": "UP"
  }
}
```

### 2. Authentication Test

```bash
# Should return 401 Unauthorized (not 200 with demo user)
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/telemetry
```

### 3. Admin Login Test

1. Navigate to `http://localhost:8080`
2. Verify login page appears (not auto-logged in as DEMO_USER)
3. Login with admin credentials
4. Change password immediately

---

## Security Hardening Checklist

- [ ] `APP_PROFILE` is NOT set to `dev`
- [ ] `SENTINEL_ADMIN_PASSWORD` is changed from default
- [ ] `SWAGGER_ENABLED` is `false`
- [ ] MongoDB authentication is enabled
- [ ] MongoDB connections use TLS
- [ ] HTTPS/TLS is configured (via reverse proxy)
- [ ] CORS origins are properly restricted
- [ ] Rate limiting is configured (via reverse proxy)
- [ ] Log aggregation is configured (no sensitive data in logs)
- [ ] Backup strategy is implemented

---

## Reverse Proxy Configuration (Recommended)

### Nginx Example

```nginx
server {
    listen 443 ssl http2;
    server_name sentinel.example.com;

    ssl_certificate /etc/ssl/certs/sentinel.crt;
    ssl_certificate_key /etc/ssl/private/sentinel.key;

    # Security headers
    add_header Strict-Transport-Security "max-age=31536000" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-Frame-Options "DENY" always;

    # Rate limiting
    limit_req_zone $binary_remote_addr zone=api:10m rate=10r/s;

    location /api/ {
        limit_req zone=api burst=20 nodelay;
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
    }
}
```

---

## Air-Gap Deployment

For disconnected/SCIF environments:

1. Pre-download Ollama models on connected machine:
   ```bash
   ollama pull llama3
   ollama pull nomic-embed-text
   ```

2. Transfer to air-gapped system

3. Configure all services to use localhost:
   ```bash
   OLLAMA_URL=http://localhost:11434
   MONGODB_URI=mongodb://localhost:27017/sentinel
   ```

4. No external network access required after initial setup

---

## Troubleshooting Common Issues

### MongoDB Connection Failed
```
Error: MongoTimeoutException
```
**Solution:** Verify MongoDB is running and `MONGODB_URI` is correct.
```bash
# Test MongoDB connection
mongosh "mongodb://localhost:27017" --eval "db.runCommand({ping:1})"
```

### Ollama Not Responding
```
Error: LLM Generation Failed (Offline/Misconfigured)
```
**Solution:** SENTINEL will fallback to offline mode (returns document excerpts). To fix:
```bash
# Verify Ollama is running
curl http://localhost:11434/api/tags
# Pull required models
ollama pull llama3
ollama pull nomic-embed-text
```

### Authentication Bypass in Production
```
Warning: DEV mode detected
```
**Solution:** Set `APP_PROFILE` environment variable:
```bash
export APP_PROFILE=enterprise  # or standard, govcloud
```

### Document Upload Returns "INVALID SECTOR"
**Solution:** Use valid sector names: `OPERATIONS`, `FINANCE`, `LEGAL`, `MEDICAL`, `DEFENSE`, `ENTERPRISE`

---

## Final Go-Live Checklist

Before announcing production availability:

- [ ] All Quick Launch Verification checks pass
- [ ] Pre-Launch Functional Testing complete
- [ ] Security Hardening Checklist complete
- [ ] Admin password changed from default
- [ ] Backup strategy tested and verified
- [ ] Monitoring/alerting configured
- [ ] Incident response plan documented
- [ ] User documentation shared with stakeholders

---

## Support

For deployment assistance:
- Operator Manual: `/manual.html`
- API Reference: `/swagger-ui.html` (when enabled)

---

*SENTINEL INTELLIGENCE PLATFORM v2.0.0 - Enterprise RAG for Regulated Industries*
