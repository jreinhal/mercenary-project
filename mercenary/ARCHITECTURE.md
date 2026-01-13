# SENTINEL Intelligence Platform - Architecture Overview

## End-to-End System Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                           SENTINEL INTELLIGENCE PLATFORM                                 │
│                    (Enterprise RAG for Regulated Industries)                            │
├─────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                          │
│  ┌─────────────────────────────────────────────────────────────────────────────────┐   │
│  │                           PRESENTATION LAYER                                      │   │
│  │  ┌─────────────────────────┐     ┌─────────────────────────────────────────┐    │   │
│  │  │     Web Dashboard       │     │              REST API                    │    │   │
│  │  │   (Thymeleaf/HTML)      │     │           (JSON/HTTP)                    │    │   │
│  │  └───────────┬─────────────┘     └─────────────────┬───────────────────────┘    │   │
│  └──────────────┼─────────────────────────────────────┼────────────────────────────┘   │
│                 │                                     │                                  │
│  ┌──────────────▼─────────────────────────────────────▼────────────────────────────┐   │
│  │                              API LAYER (Controllers)                              │   │
│  │  ┌─────────────────────────────────────┐  ┌────────────────────────────────┐    │   │
│  │  │        MercenaryController           │  │       AuditController          │    │   │
│  │  │           /api/*                     │  │        /api/audit/*            │    │   │
│  │  │  ┌────────────────────────────────┐ │  │  ┌──────────────────────────┐  │    │   │
│  │  │  │ POST /ingest/file              │ │  │  │ GET /events             │  │    │   │
│  │  │  │ GET  /ask?q=...&dept=...       │ │  │  │ GET /stats              │  │    │   │
│  │  │  │ GET  /ask/enhanced             │ │  │  └──────────────────────────┘  │    │   │
│  │  │  │ GET  /inspect                  │ │  └────────────────────────────────┘    │   │
│  │  │  │ GET  /status, /telemetry       │ │                                         │   │
│  │  │  │ GET  /reasoning/{traceId}      │ │                                         │   │
│  │  │  └────────────────────────────────┘ │                                         │   │
│  │  └─────────────────────────────────────┘                                         │   │
│  └──────────────────────────────────────────────────────────────────────────────────┘   │
│                 │                                                                        │
│  ┌──────────────▼───────────────────────────────────────────────────────────────────┐   │
│  │                              SECURITY LAYER                                       │   │
│  │  ┌────────────────┐  ┌─────────────────┐  ┌──────────────────────────────────┐  │   │
│  │  │ SecurityFilter │──│ SecurityContext │  │     Authentication Services      │  │   │
│  │  │ (Servlet)      │  │ (Thread-Local)  │  │  ┌────────┐ ┌──────┐ ┌────────┐ │  │   │
│  │  └───────┬────────┘  └─────────────────┘  │  │CAC/PKI │ │ OIDC │ │Password│ │  │   │
│  │          │                                 │  │X.509   │ │ JWT  │ │BCrypt  │ │  │   │
│  │          ▼                                 │  └────────┘ └──────┘ └────────┘ │  │   │
│  │  ┌───────────────────────────────────┐    └──────────────────────────────────┘  │   │
│  │  │ RBAC → Clearance → Sector Access  │                                          │   │
│  │  │ (Role)  (Level)    (Department)   │                                          │   │
│  │  └───────────────────────────────────┘                                          │   │
│  └──────────────────────────────────────────────────────────────────────────────────┘   │
│                 │                                                                        │
│  ┌──────────────▼───────────────────────────────────────────────────────────────────┐   │
│  │                              SERVICE LAYER                                        │   │
│  │  ┌──────────────────┐ ┌───────────────────┐ ┌─────────────┐ ┌─────────────────┐ │   │
│  │  │QueryDecomposition│ │ SecureIngestion   │ │ PiiRedaction│ │  AuditService   │ │   │
│  │  │    Service       │ │    Service        │ │   Service   │ │ (STIG-Compliant)│ │   │
│  │  │ ─────────────────│ │ ──────────────────│ │ ────────────│ │ ────────────────│ │   │
│  │  │ • Split compound │ │ • Parse PDF/TXT   │ │ • SSN       │ │ • AUTH events   │ │   │
│  │  │   queries        │ │ • Apply PII mask  │ │ • Email     │ │ • QUERY events  │ │   │
│  │  │ • Merge results  │ │ • Chunk documents │ │ • Phone     │ │ • INGEST events │ │   │
│  │  │                  │ │ • Generate embeds │ │ • CC/DoB/IP │ │ • ACCESS_DENIED │ │   │
│  │  └──────────────────┘ └───────────────────┘ └─────────────┘ └─────────────────┘ │   │
│  └──────────────────────────────────────────────────────────────────────────────────┘   │
│                 │                                                                        │
│  ┌──────────────▼───────────────────────────────────────────────────────────────────┐   │
│  │                           RAG PIPELINE LAYER                                      │   │
│  │                                                                                    │   │
│  │  ┌─────────────────────────────────────────────────────────────────────────────┐ │   │
│  │  │                    HiFi-RAG (arXiv:2512.22442v1)                            │ │   │
│  │  │  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐  │ │   │
│  │  │  │  Pass 1:    │───▶│ Cross-Enc.  │───▶│    Gap      │───▶│  Pass 2:    │  │ │   │
│  │  │  │  Broad      │    │  Reranker   │    │  Detector   │    │  Targeted   │  │ │   │
│  │  │  │  Retrieval  │    │  (Semantic) │    │  (Missing)  │    │  Retrieval  │  │ │   │
│  │  │  └─────────────┘    └─────────────┘    └─────────────┘    └─────────────┘  │ │   │
│  │  └─────────────────────────────────────────────────────────────────────────────┘ │   │
│  │                                        │                                          │   │
│  │  ┌─────────────────────────────────────▼───────────────────────────────────────┐ │   │
│  │  │                   RAG-PART (arXiv:2512.24268v1)                             │ │   │
│  │  │  ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────────────┐ │ │   │
│  │  │  │   Partition     │───▶│    Run Query    │───▶│   Suspicion Scorer      │ │ │   │
│  │  │  │   Assigner      │    │   k Partitions  │    │   (Anomaly Detection)   │ │ │   │
│  │  │  └─────────────────┘    └─────────────────┘    └─────────────────────────┘ │ │   │
│  │  └─────────────────────────────────────────────────────────────────────────────┘ │   │
│  │                                        │                                          │   │
│  │  ┌─────────────────────────────────────▼───────────────────────────────────────┐ │   │
│  │  │                    HG-Mem (arXiv:2512.23959v2)                              │ │   │
│  │  │  ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────────────┐ │ │   │
│  │  │  │    Entity       │───▶│   HyperGraph    │───▶│   Multi-Hop Reasoning   │ │ │   │
│  │  │  │   Extractor     │    │    Storage      │    │   (Graph Traversal)     │ │ │   │
│  │  │  └─────────────────┘    └─────────────────┘    └─────────────────────────┘ │ │   │
│  │  └─────────────────────────────────────────────────────────────────────────────┘ │   │
│  └──────────────────────────────────────────────────────────────────────────────────┘   │
│                 │                                                                        │
│  ┌──────────────▼───────────────────────────────────────────────────────────────────┐   │
│  │                         REASONING LAYER (Glass Box)                               │   │
│  │  ┌─────────────────────────────────────────────────────────────────────────────┐ │   │
│  │  │  ReasoningTracer (Thread-Local)                                             │ │   │
│  │  │  ───────────────────────────────────────────────────────────────────────    │ │   │
│  │  │  Step Types: QUERY_ANALYSIS → VECTOR_SEARCH → HYBRID_RETRIEVAL → RERANKING │ │   │
│  │  │              → POISON_DETECTION → GRAPH_TRAVERSAL → MCTS_REASONING         │ │   │
│  │  │              → CROSS_MODAL_RETRIEVAL → MINDSCAPE_RETRIEVAL                 │ │   │
│  │  │              → EXPERIENCE_VALIDATION → UNCERTAINTY_ANALYSIS                │ │   │
│  │  │              → GAP_DETECTION → CONTEXT_ASSEMBLY → LLM_GENERATION           │ │   │
│  │  └─────────────────────────────────────────────────────────────────────────────┘ │   │
│  └──────────────────────────────────────────────────────────────────────────────────┘   │
│                 │                                                                        │
│  ┌──────────────▼───────────────────────────────────────────────────────────────────┐   │
│  │                              DATA LAYER                                           │   │
│  │  ┌────────────────────────────────────┐  ┌─────────────────────────────────────┐ │   │
│  │  │         VectorStore Interface      │  │          Repositories               │ │   │
│  │  │  ┌────────────┐  ┌───────────────┐│  │  ┌─────────────┐ ┌────────────────┐ │ │   │
│  │  │  │LocalMongo  │  │MongoDBAtlas   ││  │  │UserRepository│ │ChatLogRepository│ │ │   │
│  │  │  │VectorStore │  │VectorStore    ││  │  └─────────────┘ └────────────────┘ │ │   │
│  │  │  │(Air-Gap)   │  │(Cloud)        ││  │                                     │ │   │
│  │  │  └────────────┘  └───────────────┘│  └─────────────────────────────────────┘ │   │
│  │  └────────────────────────────────────┘                                          │   │
│  └──────────────────────────────────────────────────────────────────────────────────┘   │
│                 │                                     │                                  │
├─────────────────┼─────────────────────────────────────┼──────────────────────────────────┤
│                 │      EXTERNAL SERVICES              │                                  │
│  ┌──────────────▼─────────────────────┐  ┌───────────▼─────────────────────────────┐   │
│  │           MongoDB                   │  │              Ollama LLM                  │   │
│  │  ┌───────────────────────────────┐ │  │  ┌─────────────────────────────────────┐│   │
│  │  │ users          │ chat_history │ │  │  │ Chat Generation: llama3             ││   │
│  │  │ audit_log      │ vector_store │ │  │  │ Embeddings: nomic-embed-text        ││   │
│  │  │ hypergraph_nodes/edges        │ │  │  └─────────────────────────────────────┘│   │
│  │  └───────────────────────────────┘ │  │                                          │   │
│  └────────────────────────────────────┘  └──────────────────────────────────────────┘   │
│                                                                                          │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Query Execution Flow

```
┌──────────┐
│   User   │
└────┬─────┘
     │ GET /api/ask/enhanced?q="What is X and Y"&dept=FINANCE
     ▼
┌─────────────────┐
│ SecurityFilter  │──────────────────────────────────────────────────────┐
│  ├─ Authenticate (CAC/OIDC/Password)                                   │
│  ├─ Check RBAC permissions                                             │
│  ├─ Verify clearance level                                             │
│  └─ Validate sector access                                             │
└────────┬────────┘                                                      │
         │                                                               │
         ▼                                                               ▼
┌─────────────────────────┐                                    ┌─────────────────┐
│ QueryDecompositionService│                                   │  AuditService   │
│  └─ Split: ["What is X",│                                    │  └─ Log event   │
│            "What is Y"] │                                    └─────────────────┘
└────────┬────────────────┘
         │
         ▼ (for each sub-query)
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                HiFiRagService                                    │
│  ┌────────────┐   ┌──────────────┐   ┌────────────┐   ┌─────────────────────┐  │
│  │  Pass 1    │──▶│ CrossEncoder │──▶│    Gap     │──▶│      Pass 2         │  │
│  │ Broad      │   │   Reranker   │   │  Detector  │   │ Targeted Retrieval  │  │
│  │ threshold  │   │ Score 0-1    │   │ Find gaps  │   │ for missing concepts│  │
│  │   = 0.1    │   │              │   │            │   │                     │  │
│  └────────────┘   └──────────────┘   └────────────┘   └─────────────────────┘  │
│         │                                                         │             │
│         └────────────────────────┬────────────────────────────────┘             │
│                                  ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐   │
│  │                           RagPartService                                 │   │
│  │    Run against k partition combinations → Score anomalies → Filter      │   │
│  └─────────────────────────────────────────────────────────────────────────┘   │
│                                  │                                              │
│                                  ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐   │
│  │                         HyperGraphMemory                                 │   │
│  │    Entity extraction → Graph traversal → Multi-hop reasoning            │   │
│  └─────────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────┐
│ Context Assembly + LLM Generation       │
│  └─ Ollama (llama3) generates response  │
└────────┬────────────────────────────────┘
         │
         ▼
┌────────────────────────────────────────────────────────────────┐
│                      Response to User                           │
│  {                                                              │
│    "answer": "...",                                            │
│    "sources": ["doc1.pdf", "doc2.pdf"],                        │
│    "reasoning_trace": {                                        │
│      "steps": [                                                │
│        { "type": "VECTOR_SEARCH", "duration_ms": 45 },         │
│        { "type": "RERANKING", "duration_ms": 120 },            │
│        { "type": "LLM_GENERATION", "duration_ms": 2300 }       │
│      ]                                                         │
│    }                                                           │
│  }                                                             │
└────────────────────────────────────────────────────────────────┘
```

---

## Document Ingestion Flow

```
┌──────────┐
│   User   │
└────┬─────┘
     │ POST /api/ingest/file {file: document.pdf, dept: FINANCE}
     ▼
┌─────────────────┐
│ SecurityFilter  │ → Authenticate + Authorize (INGEST permission)
└────────┬────────┘
         │
         ▼
┌────────────────────────────────────────────────────────────────────────────────┐
│                          SecureIngestionService                                 │
│                                                                                 │
│  ┌───────────────┐   ┌─────────────────┐   ┌───────────────┐   ┌────────────┐ │
│  │  Apache Tika  │──▶│ PiiRedaction    │──▶│ TokenText     │──▶│  Ollama    │ │
│  │  Parse PDF    │   │ Service         │   │ Splitter      │   │  Embed     │ │
│  │               │   │ ────────────────│   │               │   │            │ │
│  │               │   │ • SSN → [REDACT]│   │ chunk=800     │   │ nomic-     │ │
│  │               │   │ • Email → [RED] │   │ overlap=100   │   │ embed-text │ │
│  │               │   │ • Phone → [RED] │   │               │   │            │ │
│  └───────────────┘   └─────────────────┘   └───────────────┘   └────────────┘ │
│                                                                        │        │
│                                                                        ▼        │
│  ┌────────────────────────────────────────────────────────────────────────────┐│
│  │                              VectorStore                                    ││
│  │  Store: { content, embedding[768], metadata: {source, dept, timestamp} }   ││
│  └────────────────────────────────────────────────────────────────────────────┘│
│                                            │                                    │
│                                            ▼                                    │
│  ┌────────────────────────────────────────────────────────────────────────────┐│
│  │                           HyperGraphMemory                                  ││
│  │  EntityExtractor → hypergraph_nodes + hypergraph_edges                     ││
│  └────────────────────────────────────────────────────────────────────────────┘│
└────────────────────────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────┐
│  AuditService   │ → Log DOCUMENT_INGESTED event
└────────┬────────┘
         │
         ▼
┌────────────────────────────────────────┐
│  Response: { success: true,            │
│              chunks: 15,               │
│              entities: 42 }            │
└────────────────────────────────────────┘
```

---

## Security Architecture (Defense in Depth)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         SECURITY LAYERS                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Layer 1: AUTHENTICATION                                                    │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐            │
│  │ CAC/PIV    │  │   OIDC     │  │  Password  │  │    Dev     │            │
│  │ X.509 Cert │  │  JWT/OAuth │  │   BCrypt   │  │ Auto-User  │            │
│  └────────────┘  └────────────┘  └────────────┘  └────────────┘            │
│                                                                              │
│  Layer 2: AUTHORIZATION (RBAC)                                              │
│  ┌───────────────────────────────────────────────────────────────────────┐ │
│  │  ADMIN     → QUERY, INGEST, DELETE, MANAGE_USERS, VIEW_AUDIT, CONFIG  │ │
│  │  ANALYST   → QUERY, INGEST                                             │ │
│  │  VIEWER    → QUERY                                                     │ │
│  │  AUDITOR   → QUERY, VIEW_AUDIT                                         │ │
│  └───────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
│  Layer 3: CLEARANCE LEVELS                                                  │
│  ┌────────────────────────────────────────────────────────────────────────┐│
│  │  UNCLASSIFIED (0) → CUI (1) → SECRET (2) → TOP_SECRET (3) → SCI (4)   ││
│  └────────────────────────────────────────────────────────────────────────┘│
│                                                                              │
│  Layer 4: SECTOR/DEPARTMENT ACCESS                                          │
│  ┌────────────────────────────────────────────────────────────────────────┐│
│  │  User.allowedSectors must include requested Department                 ││
│  │  OPERATIONS | FINANCE | LEGAL | ENTERPRISE | MEDICAL | DEFENSE         ││
│  └────────────────────────────────────────────────────────────────────────┘│
│                                                                              │
│  Layer 5: PII REDACTION (Ingestion)                                         │
│  ┌────────────────────────────────────────────────────────────────────────┐│
│  │  NIST 800-122 | GDPR | HIPAA | PCI-DSS compliant                       ││
│  └────────────────────────────────────────────────────────────────────────┘│
│                                                                              │
│  Layer 6: CORPUS POISONING DEFENSE (Query)                                  │
│  ┌────────────────────────────────────────────────────────────────────────┐│
│  │  RAG-PART: Partition voting + anomaly detection                        ││
│  └────────────────────────────────────────────────────────────────────────┘│
│                                                                              │
│  Layer 7: AUDIT LOGGING (STIG-Compliant)                                    │
│  ┌────────────────────────────────────────────────────────────────────────┐│
│  │  Every event logged: who, what, when, where, outcome                   ││
│  └────────────────────────────────────────────────────────────────────────┘│
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Data Models

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                                User                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│  id: String                       │  roles: Set<UserRole>                   │
│  username: String                 │  clearance: ClearanceLevel              │
│  displayName: String              │  allowedSectors: Set<Department>        │
│  email: String                    │  authProvider: AuthProvider             │
│  passwordHash: String (BCrypt)    │  externalId: String (OIDC/CAC)          │
│  createdAt: Instant               │  lastLoginAt: Instant                   │
│  active: boolean                  │                                         │
└─────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────┐  ┌──────────────────────┐  ┌──────────────────────┐
│      UserRole        │  │   ClearanceLevel     │  │     Department       │
├──────────────────────┤  ├──────────────────────┤  ├──────────────────────┤
│  ADMIN               │  │  UNCLASSIFIED (0)    │  │  OPERATIONS          │
│  ANALYST             │  │  CUI (1)             │  │  FINANCE             │
│  VIEWER              │  │  SECRET (2)          │  │  LEGAL               │
│  AUDITOR             │  │  TOP_SECRET (3)      │  │  ENTERPRISE          │
│                      │  │  SCI (4)             │  │  MEDICAL             │
│                      │  │                      │  │  DEFENSE             │
└──────────────────────┘  └──────────────────────┘  └──────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                              AuditEvent                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│  id: String               │  eventType: EventType (AUTH_SUCCESS, QUERY_...)│
│  timestamp: Instant       │  outcome: Outcome (SUCCESS, FAILURE, DENIED)   │
│  userId: String           │  action: String                                │
│  username: String         │  resourceType: String                          │
│  userClearance: Level     │  resourceId: String                            │
│  sourceIp: String         │  responseSummary: String (truncated)           │
│  userAgent: String        │  metadata: Map<String, Object>                 │
│  sessionId: String        │                                                │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                            ReasoningTrace                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│  traceId: String          │  steps: List<ReasoningStep>                    │
│  query: String            │    ├─ type: StepType                           │
│  department: String       │    ├─ label: String                            │
│  startTime: long          │    ├─ detail: String                           │
│  endTime: long            │    ├─ durationMs: long                         │
│                           │    └─ data: Map<String, Object>                │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Technology Stack

| Layer | Technology | Version |
|-------|-----------|---------|
| Runtime | Java | 21 LTS |
| Framework | Spring Boot | 3.3.0 |
| AI/ML | Spring AI | 1.0.0-M1 |
| Database | MongoDB | 7+ |
| Vector Search | MongoDB Atlas / LocalMongoVectorStore | - |
| LLM Inference | Ollama (llama3) | Latest |
| Embeddings | nomic-embed-text | Latest |
| Document Parsing | Apache Tika | 2.9.2 |
| Security | Spring Security | 6.2.1 |
| JWT | Nimbus JOSE+JWT | 9.37.3 |
| Caching | Caffeine | 3.1.8 |
| API Docs | SpringDoc OpenAPI | 2.5.0 |
| Build | Gradle | 8.x |
| Container | Docker | - |

---

## Deployment Modes

| Profile | Auth Mode | Use Case |
|---------|-----------|----------|
| `dev` | DEV (auto-user) | Local development |
| `standard` | Password (BCrypt) | Commercial/internal |
| `enterprise` | OIDC (Azure AD/Okta) | Enterprise SSO |
| `govcloud` | CAC/PIV (X.509) | Government/classified |

---

## UML Diagram Files

The following PlantUML files have been created for detailed diagrams:

1. **`architecture-uml.puml`** - Complete system component diagram
2. **`sequence-diagrams.puml`** - Contains:
   - Query execution sequence diagram
   - Document ingestion sequence diagram
   - Component diagram
   - Core class diagram
   - RAG pipeline class diagram
   - Deployment diagram

To render these diagrams:
- Use [PlantUML Online](https://www.plantuml.com/plantuml/uml/)
- Use VS Code with PlantUML extension
- Use IntelliJ IDEA with PlantUML integration
- Run: `java -jar plantuml.jar *.puml`
