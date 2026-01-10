# SENTINEL // INTELLIGENCE PLATFORM

**Secure RAG infrastructure for regulated industries.**

CAC/PIV-ready authentication, clearance-based access control, air-gap deployment — built on enterprise Java.

---

## Overview

Sentinel is a Retrieval-Augmented Generation (RAG) platform designed for sensitive enterprise and government environments. It enables natural-language queries against ingested document collections while enforcing strict access controls and maintaining complete audit trails.

### Core Capabilities

| Capability | Description |
|------------|-------------|
| **Secure Document Ingestion** | Local vectorization of PDF, TXT, and MD files with automatic PII redaction |
| **Air-Gap Operation** | Zero external dependencies — runs entirely on local infrastructure |
| **Clearance-Based Access** | Four-tier classification model (UNCLASSIFIED → TOP SECRET) |
| **Glass Box Reasoning** | Full retrieval chain visibility with ANALYZE → VERIFY → CITE protocol |
| **Citation Enforcement** | Every AI response anchored to source documents with clickable verification |
| **Deep Storage Recovery** | Reconstruct document content from vector memory if source files are purged |
| **STIG-Aligned Audit Logging** | Every authentication, query, and access event persisted for compliance |

---

## Technical Stack

| Component | Technology | Version |
|-----------|------------|---------|
| Runtime | Java | 21 (LTS) |
| Framework | Spring Boot | 3.3.0 |
| AI Framework | Spring AI | 1.0.0-M1 |
| Vector Store | MongoDB Atlas Vector Search | — |
| LLM Interface | Ollama (local inference) | — |
| Document Parsing | Apache Tika | 2.9.2 |
| API Documentation | SpringDoc OpenAPI | 2.5.0 |

---

## Security Architecture

Sentinel implements defense-in-depth security with four enforcement layers:

```
Authentication → Role (RBAC) → Clearance → Sector → Audit Log
```

### Authentication Modes

| Profile | Auth Mechanism | Security Standard | Use Case |
|---------|----------------|-------------------|----------|
| `govcloud` | X.509 Mutual TLS (CAC/PIV) | FIPS 201 / DoDI 8520.02 | Government / Defense |
| `enterprise` | JWT Bearer Token (OIDC) | NIST SP 800-63C | Corporate SSO (Azure AD, Okta) |
| `dev` | Auto-provisioned Demo User | None | Development / Testing |

### Clearance Levels

| Level | Government Label | Commercial Label |
|-------|------------------|------------------|
| 3 | TOP SECRET | Highly Restricted |
| 2 | SECRET | Restricted (HIPAA, Privileged) |
| 1 | CUI | Confidential / Internal |
| 0 | UNCLASSIFIED | Public |

### Role-Based Access Control (RBAC)

| Role | Permissions |
|------|-------------|
| `ADMIN` | QUERY, INGEST, DELETE, MANAGE_USERS, VIEW_AUDIT, CONFIGURE |
| `ANALYST` | QUERY, INGEST |
| `VIEWER` | QUERY |
| `AUDITOR` | QUERY, VIEW_AUDIT |

### Audit Events

All security-relevant events are persisted to `audit_log` collection:

- `AUTH_SUCCESS` / `AUTH_FAILURE` — Login attempts
- `QUERY_EXECUTED` — Every query with sector, response summary, latency
- `DOCUMENT_INGESTED` — File uploads with metadata
- `ACCESS_DENIED` — Blocked requests (clearance/permission failures)
- `PROMPT_INJECTION_DETECTED` — Attempted LLM manipulation

---

## Glass Box Reasoning

Unlike black-box AI systems, Sentinel exposes its complete reasoning pipeline via the "VIEW REASONING CHAIN" panel:

| Step | Action |
|------|--------|
| 1. Query Analysis | Parse natural language query |
| 2. Vector Search | Search sector with similarity threshold |
| 3. Document Retrieval | Retrieve relevant chunks via HiFi-RAG |
| 4. Response Synthesis | Apply ANALYZE → VERIFY → CITE protocol |

**ANALYZE → VERIFY → CITE Protocol:**
1. **ANALYZE** — Parse retrieved chunks, identify relevant facts
2. **VERIFY** — Cross-check against source metadata, reject unsupported claims
3. **CITE** — Attach `[filename.ext]` to every fact in response

---

## Quick Start

### Prerequisites

- Java JDK 21+
- Ollama running on `localhost:11434`
- MongoDB running on `localhost:27017`

### 1. Start Dependencies

```bash
# Terminal 1: Start Ollama
ollama serve

# Terminal 2: Start MongoDB
mongod
```

### 2. Pull Required Models

```bash
ollama pull llama3
ollama pull nomic-embed-text
```

### 3. Build & Run

```bash
# Build
./gradlew bootJar

# Run (dev profile - no auth)
java -jar build/libs/mercenary-1.0.0.jar

# Run (enterprise profile - OIDC)
APP_PROFILE=enterprise java -jar build/libs/mercenary-1.0.0.jar

# Run (govcloud profile - CAC/PIV)
APP_PROFILE=govcloud java -jar build/libs/mercenary-1.0.0.jar
```

### 4. Access Dashboard

Open `http://localhost:8080` in your browser.

---

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `APP_PROFILE` | `dev` | Deployment profile (`dev`, `enterprise`, `govcloud`) |
| `OLLAMA_URL` | `http://localhost:11434` | Ollama inference endpoint |
| `MONGODB_URI` | `mongodb://localhost:27017/mercenary` | MongoDB connection string |
| `OLLAMA_MODEL` | `llama3` | LLM model for inference |
| `EMBEDDING_MODEL` | `nomic-embed-text` | Model for vector embeddings |

### Application Properties

Key settings in `application.yaml`:

```yaml
spring:
  ai:
    ollama:
      base-url: ${OLLAMA_URL:http://localhost:11434}
      chat:
        model: ${OLLAMA_MODEL:llama3}
      embedding:
        model: ${EMBEDDING_MODEL:nomic-embed-text}
    vectorstore:
      mongodb:
        collection-name: vector_store
        index-name: vector_index

app:
  profile: ${APP_PROFILE:dev}
```

---

## API Endpoints

| Endpoint | Method | Description | Auth Required |
|----------|--------|-------------|---------------|
| `/` | GET | Dashboard UI | Profile-dependent |
| `/api/query` | POST | Execute intelligence query | Yes |
| `/api/ingest` | POST | Upload and ingest document | Yes (INGEST permission) |
| `/api/inspect/{filename}` | GET | View document content | Yes |
| `/api/audit` | GET | Retrieve audit logs | Yes (VIEW_AUDIT permission) |
| `/api/health` | GET | System health check | No |

Full API documentation available at `/swagger-ui.html` when running.

---

## Deployment

### Air-Gap Deployment

For disconnected/SCIF environments:

1. Pre-download Ollama models on connected machine
2. Transfer models to air-gapped system
3. Configure all endpoints to `localhost`
4. No external network required

```bash
# Required environment for air-gap
export OLLAMA_URL=http://localhost:11434
export MONGODB_URI=mongodb://localhost:27017/mercenary
export APP_PROFILE=govcloud
```

### Docker Deployment

```bash
# Build image
docker build -t sentinel:latest .

# Run with docker-compose
docker-compose up -d
```

### System Requirements

| Resource | Minimum | Recommended |
|----------|---------|-------------|
| RAM | 16 GB | 32 GB |
| Storage | 20 GB | 50 GB+ |
| CPU | 4 cores | 8+ cores |
| GPU | Optional | NVIDIA (for faster inference) |

**Ports:**
- `8080` — Application
- `11434` — Ollama
- `27017` — MongoDB

---

## Project Structure

```
src/main/java/com/jreinhal/mercenary/
├── config/           # Security, MongoDB, Ollama configuration
├── controller/       # REST API endpoints
├── model/            # Domain entities (User, Document, AuditEvent)
│   ├── ClearanceLevel.java
│   ├── Department.java
│   └── UserRole.java
├── repository/       # MongoDB repositories
└── service/
    ├── AuditService.java           # Compliance logging
    ├── CacAuthenticationService.java  # CAC/PIV X.509 auth
    ├── MemoryEvolutionService.java # Document merging
    ├── MercenaryService.java       # Core RAG pipeline
    └── SecureIngestionService.java # PII redaction + vectorization

src/main/resources/
├── static/           # Dashboard UI (HTML/CSS/JS)
│   ├── index.html
│   ├── manual.html   # Operator Field Guide
│   └── images/
└── application.yaml  # Configuration
```

---

## Sectors

Documents are partitioned by sector, each with minimum clearance requirements:

| Sector | Required Clearance | Government Context | Commercial Context |
|--------|-------------------|--------------------|--------------------|
| `OPERATIONS` | UNCLASSIFIED | General Operations | Public |
| `FINANCE` | CUI | Financial Intelligence | PCI-DSS |
| `LEGAL` | CUI | Legal / Contracts | Attorney-Client |
| `ENTERPRISE` | CUI | Enterprise | Confidential |
| `MEDICAL` | SECRET | Medical / Clinical | HIPAA |
| `DEFENSE` | SECRET | Defense / Military | Classified |

---

## Compliance Alignment

Sentinel's security architecture supports compliance with:

- **NIST AI RMF** — Risk management for AI systems
- **DoD AI Ethics Principles** — Responsible AI for defense
- **FIPS 201** — PIV card authentication
- **DoDI 8520.02** — PKI and certificate policy
- **NIST SP 800-63C** — Digital identity federation
- **STIG** — Security Technical Implementation Guides (audit logging)

*Note: Sentinel provides security controls that support these frameworks. Full certification requires additional organizational controls and assessments.*

---

## License

Proprietary. All rights reserved.

---

## Support

For deployment assistance, integration support, or enterprise licensing:

- **Documentation:** `/manual.html` (built into application)
- **API Reference:** `/swagger-ui.html`
- **Contact:** [Your contact information]

---

*SENTINEL INTELLIGENCE PLATFORM — Powered by RAGPart & HyperGraph Memory*
