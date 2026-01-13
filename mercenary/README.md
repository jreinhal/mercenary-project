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
| **Clearance-Based Access** | Five-tier classification model (UNCLASSIFIED → SCI) |
| **Glass Box Reasoning** | Real-time pipeline transparency with step-by-step timing and metrics |
| **Citation Enforcement** | Every AI response anchored to source documents with clickable verification |
| **Multi-Query Decomposition** | Compound queries split into sub-queries for comprehensive retrieval |
| **HiFi-RAG** | Iterative two-pass retrieval with cross-encoder reranking (arXiv:2512.22442v1) |
| **RAGPart Poisoning Defense** | Corpus poisoning detection via document partitioning (arXiv:2512.24268v1) |
| **HGMem Graph Memory** | Hypergraph memory for multi-hop reasoning (arXiv:2512.23959v2) |
| **QuCo-RAG Hallucination Defense** | Entity-based uncertainty quantification to prevent false claims (arXiv:2512.19134) |
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
| Security | Spring Security | 6.2.1 |
| JWT Validation | Nimbus JOSE+JWT | 9.37.3 |

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
| `standard` | Username/Password (BCrypt) | — | Standalone deployments |
| `dev` | Auto-provisioned Demo User | None | Development / Testing |

### Clearance Levels

| Level | Government Label | Commercial Label |
|-------|------------------|------------------|
| 4 | SCI | Sensitive Compartmented Information |
| 3 | TOP SECRET | Highly Restricted |
| 2 | SECRET | Restricted (HIPAA, Privileged) |
| 1 | CUI | Confidential / Internal |
| 0 | UNCLASSIFIED | Public |

### Role-Based Access Control (RBAC)

| Role | Permissions |
|------|-------------|
| `ADMIN` | QUERY, INGEST, DELETE, MANAGE_USERS, VIEW_AUDIT, CONFIGURE |
| `OPERATOR` | QUERY, INGEST, DELETE, CACHE_CLEAR |
| `ANALYST` | QUERY, INGEST |
| `VIEWER` | QUERY |
| `AUDITOR` | QUERY, VIEW_AUDIT |

### PII Redaction

Automatic detection and redaction of sensitive data during ingestion:
- Social Security Numbers (SSN)
- Credit Card Numbers (PCI-DSS compliant, with Luhn validation)
- Email Addresses
- Phone Numbers
- Dates of Birth (context-aware detection)
- Medical Record Numbers (MRN)
- IP Addresses (IPv4 and IPv6)
- Passport Numbers
- Driver's License Numbers
- Names (with context/honorific detection)
- Physical Addresses

### Audit Events

All security-relevant events are persisted to `audit_log` collection:

- `AUTH_SUCCESS` / `AUTH_FAILURE` — Login attempts
- `QUERY_EXECUTED` — Every query with sector, response summary, latency
- `DOCUMENT_INGESTED` — File uploads with metadata
- `ACCESS_DENIED` — Blocked requests (clearance/permission failures)
- `PROMPT_INJECTION_DETECTED` — Attempted LLM manipulation

---

## Glass Box Reasoning Engine

Unlike black-box AI systems, Sentinel exposes its complete reasoning pipeline in real-time:

### Pipeline Steps (with timing)

| Step | Type | Description |
|------|------|-------------|
| Query Analysis | `QUERY_ANALYSIS` | Parse and understand user intent |
| Query Decomposition | `QUERY_DECOMPOSITION` | Split compound queries into sub-queries |
| Vector Search | `VECTOR_SEARCH` | Semantic similarity search |
| Keyword Search | `KEYWORD_SEARCH` | Fallback keyword matching |
| HiFi Reranking | `RERANKING` | Cross-encoder semantic scoring |
| RAGPart Defense | `POISON_DETECTION` | Corpus poisoning anomaly detection |
| HGMem Traversal | `GRAPH_TRAVERSAL` | Hypergraph multi-hop reasoning |
| Gap Detection | `GAP_DETECTION` | Identify missing information |
| Uncertainty Analysis | `UNCERTAINTY_ANALYSIS` | QuCo-RAG hallucination risk detection |
| Context Assembly | `CONTEXT_ASSEMBLY` | Build LLM context window |
| Response Generation | `LLM_GENERATION` | Generate answer with citations |

### Enhanced API Response

```json
{
  "answer": "The answer with [citations]...",
  "reasoning": [
    {"type": "query_analysis", "label": "Query Analysis", "durationMs": 15},
    {"type": "vector_search", "label": "Vector Search", "durationMs": 234, "data": {"resultsFound": 8}},
    {"type": "reranking", "label": "HiFi Reranking", "durationMs": 1823, "data": {"rerankedCount": 5}}
  ],
  "sources": ["document1.pdf", "document2.txt"],
  "metrics": {"totalDurationMs": 3421, "documentsRetrieved": 8},
  "traceId": "abc123"
}
```

---

## Advanced RAG Features

### HiFi-RAG (High-Fidelity Retrieval)

Based on [arXiv:2512.22442v1](https://arxiv.org/abs/2512.22442), implements:
- **Two-Pass Retrieval**: Initial broad retrieval followed by focused refinement
- **Cross-Encoder Reranking**: LLM-as-judge semantic scoring via Ollama
- **Iterative Gap Detection**: Identifies missing concepts and retrieves additional documents
- **Air-gap safe**: Falls back to keyword scoring when LLM unavailable

### RAGPart (Corpus Poisoning Defense)

Based on [arXiv:2512.24268v1](https://arxiv.org/abs/2512.24268), implements:
- **Document Partitioning**: SHA-256 based deterministic assignment to partitions
- **Consistency Scoring**: Detects anomalous documents via cross-partition agreement
- **Suspicion Flagging**: Documents with high variance scores are flagged for review
- **Combinatorial Retrieval**: Queries multiple partition combinations for robustness

### HGMem (Hypergraph Memory)

Based on [arXiv:2512.23959v2](https://arxiv.org/abs/2512.23959), implements:
- **Entity Extraction**: Pattern-based NER (PERSON, ORG, LOCATION, DATE, TECHNICAL)
- **Hypergraph Storage**: MongoDB-backed node and hyperedge collections
- **Multi-hop Traversal**: BFS graph exploration for relationship discovery
- **Hybrid Query**: Combines vector similarity with graph context

### QuCo-RAG (Hallucination Defense)

Based on [arXiv:2512.19134](https://arxiv.org/abs/2512.19134), implements:
- **Entity Extraction**: Pattern-based NER for PERSON, ORG, TECH, DATE, LOCATION
- **Frequency Analysis**: Checks entity frequency in corpus (local or Infini-gram API)
- **Co-occurrence Verification**: Validates entity relationships exist in training data
- **Uncertainty Scoring**: Computes hallucination risk score (0.0 - 1.0)
- **Adaptive Retrieval**: Triggers additional retrieval when uncertainty exceeds threshold
- **Air-Gap Safe**: Falls back to local corpus analysis when offline

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

# Run (standard profile - username/password)
APP_PROFILE=standard java -jar build/libs/mercenary-1.0.0.jar
```

### 4. Access Dashboard

Open `http://localhost:8080` in your browser.

---

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `APP_PROFILE` | `dev` | Deployment profile (`dev`, `enterprise`, `govcloud`, `standard`) |
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
  hifirag:
    reranker:
      model: ${RERANKER_MODEL:llama3}
      top-k: 5
      max-iterations: 3
```

---

## API Endpoints

| Endpoint | Method | Description | Auth Required |
|----------|--------|-------------|---------------|
| `/` | GET | Dashboard UI | Profile-dependent |
| `/api/ask` | POST | Execute query (legacy) | Yes |
| `/api/ask/enhanced` | POST | Execute query with full reasoning trace | Yes |
| `/api/reasoning/{traceId}` | GET | Retrieve reasoning trace by ID | Yes |
| `/api/ingest` | POST | Upload and ingest document | Yes (INGEST permission) |
| `/api/inspect/{filename}` | GET | View document content | Yes |
| `/api/audit` | GET | Retrieve audit logs | Yes (VIEW_AUDIT permission) |
| `/api/health` | GET | System health check | No |
| `/swagger-ui.html` | GET | Interactive API documentation | No (dev profile only) |

Full API documentation available at `/swagger-ui.html` when running in `dev` profile. Swagger UI is disabled by default in production profiles for security.

---

## Project Structure

```
src/main/java/com/jreinhal/mercenary/
├── config/                    # Application configuration
│   ├── DataInitializer.java   # Demo data seeding
│   ├── OpenApiConfig.java     # Swagger/OpenAPI setup
│   ├── SecurityConfig.java    # Spring Security config
│   └── WebConfig.java         # CORS and web settings
├── controller/
│   ├── AuditController.java   # Audit log endpoints
│   └── MercenaryController.java  # Core RAG pipeline + Glass Box
├── filter/
│   ├── SecurityContext.java   # Thread-local security state
│   └── SecurityFilter.java    # Authentication filter
├── model/
│   ├── AuditEvent.java        # Audit log entity
│   ├── ChatLog.java           # Query history
│   ├── ClearanceLevel.java    # Security clearances
│   ├── User.java              # User entity with roles
│   └── UserRole.java          # RBAC permissions
├── rag/
│   ├── hifirag/               # HiFi-RAG implementation
│   │   ├── CrossEncoderReranker.java
│   │   ├── GapDetector.java
│   │   └── HiFiRagService.java
│   ├── ragpart/               # RAGPart poisoning defense
│   │   ├── PartitionAssigner.java
│   │   ├── RagPartService.java
│   │   └── SuspicionScorer.java
│   ├── qucorag/               # QuCo-RAG hallucination defense
│   │   ├── EntityExtractor.java
│   │   ├── InfiniGramClient.java
│   │   └── QuCoRagService.java
│   └── hgmem/                 # HGMem hypergraph memory
│       ├── EntityExtractor.java
│       ├── HGMemQueryEngine.java
│       └── HyperGraphMemory.java
├── reasoning/                 # Glass Box Reasoning Engine
│   ├── ReasoningStep.java     # Individual step record
│   ├── ReasoningTrace.java    # Complete trace container
│   └── ReasoningTracer.java   # Thread-local trace collector
├── repository/
│   ├── ChatLogRepository.java
│   └── UserRepository.java
├── security/
│   ├── CacCertificateParser.java   # DoD CAC DN parsing
│   ├── CacUserDetailsService.java  # X.509 Spring Security integration
│   ├── JwksKeyProvider.java        # OIDC key management
│   └── JwtValidator.java           # JWT token validation
├── service/
│   ├── AuditService.java              # Compliance logging
│   ├── AuthenticationService.java     # Auth interface
│   ├── CacAuthenticationService.java  # CAC/PIV X.509 auth
│   ├── DevAuthenticationService.java  # Development mode
│   ├── OidcAuthenticationService.java # OIDC JWT auth
│   ├── PiiRedactionService.java       # Automatic PII masking
│   ├── QueryDecompositionService.java # Multi-query handling
│   ├── SecureIngestionService.java    # Document processing
│   └── StandardAuthenticationService.java  # Username/password auth
└── vector/
    └── LocalMongoVectorStore.java  # MongoDB vector operations

src/main/resources/
├── static/
│   ├── index.html             # Dashboard with Glass Box UI
│   └── manual.html            # Operator Field Guide
└── application.yaml           # Configuration

src/test/java/
├── security/
│   ├── CacAuthenticationIntegrationTest.java  # CAC/PIV integration tests
│   └── X509AuthenticationTest.java            # Certificate parsing tests
└── service/
    └── ...                    # Service unit tests
```

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

## Testing

### Run All Tests

```bash
./gradlew test
```

### CAC/PIV X.509 Tests

The CAC/PIV authentication can be tested without PKI infrastructure using mock certificates:

```bash
# Run CAC-specific tests
./gradlew test --tests "*CacAuthentication*"
./gradlew test --tests "*X509Authentication*"
```

### Generate Self-Signed Certificates (Manual Testing)

```bash
# Generate test CA
openssl genrsa -out ca.key 2048
openssl req -new -x509 -days 365 -key ca.key -out ca.crt \
  -subj "/CN=Test CA/O=Test Organization"

# Generate DoD-style client certificate
openssl genrsa -out client.key 2048
openssl req -new -key client.key -out client.csr \
  -subj "/CN=DOE.JOHN.M.1234567890/O=U.S. Government/OU=DoD"
openssl x509 -req -days 365 -in client.csr -CA ca.crt -CAkey ca.key \
  -set_serial 01 -out client.crt
```

---

## License

Proprietary. All rights reserved.

---

## What's Included

This sale includes:

- Complete source code (Java 21 / Spring Boot 3.3)
- Docker deployment configurations
- Web dashboard with Glass Box reasoning UI
- API documentation via Swagger
- Unit and integration tests
- Full transfer of intellectual property rights

---

## Documentation & Resources

| Resource | URL | Description |
|----------|-----|-------------|
| Dashboard | `/` | Main intelligence terminal |
| Operator Manual | `/manual.html` | 9-section field guide |
| API Reference | `/swagger-ui.html` | Interactive API documentation (dev profile only) |
| Deployment Guide | `DEPLOYMENT_CHECKLIST.md` | Production launch checklist |

---

*SENTINEL INTELLIGENCE PLATFORM v2.0.0 — Enterprise RAG for Regulated Industries*
