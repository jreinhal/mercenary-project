# SENTINEL Intelligence Platform - Codebase Reference

**Version:** 2.0.0
**Build:** 2026.01
**Platform:** Java 21 / Spring Boot 3.3.0 / Spring AI 1.0.0-M1

---

## Project Structure

```
src/main/java/com/jreinhal/mercenary/
├── MercenaryApplication.java          # Main entry point
├── Department.java                     # Sector enum
├── config/
│   ├── DataInitializer.java           # Demo data seeding
│   ├── OpenApiConfig.java             # Swagger/OpenAPI configuration
│   ├── SecurityConfig.java            # Spring Security configuration
│   └── WebConfig.java                 # CORS and web settings
├── constant/
│   └── MetadataConstants.java         # Document metadata keys
├── controller/
│   ├── AuditController.java           # Audit log REST endpoints
│   └── MercenaryController.java       # Core RAG pipeline + Glass Box
├── filter/
│   ├── SecurityContext.java           # Thread-local security state
│   └── SecurityFilter.java            # Authentication filter
├── model/
│   ├── AuditEvent.java                # Audit log entity
│   ├── ChatLog.java                   # Query history
│   ├── ClearanceLevel.java            # Security clearances
│   ├── User.java                      # User entity with roles
│   └── UserRole.java                  # RBAC permissions
├── rag/
│   ├── hifirag/                       # HiFi-RAG (arXiv:2512.22442v1)
│   │   ├── CrossEncoderReranker.java  # LLM-based semantic scoring
│   │   ├── GapDetector.java           # Missing concept identification
│   │   └── HiFiRagService.java        # Two-pass iterative retrieval
│   ├── ragpart/                       # RAGPart (arXiv:2512.24268v1)
│   │   ├── PartitionAssigner.java     # SHA-256 document partitioning
│   │   ├── RagPartService.java        # Corpus poisoning defense
│   │   └── SuspicionScorer.java       # Anomaly detection
│   └── hgmem/                         # HGMem (arXiv:2512.23959v2)
│       ├── EntityExtractor.java       # Pattern-based NER
│       ├── HGMemQueryEngine.java      # Hybrid query engine
│       └── HyperGraphMemory.java      # MongoDB hypergraph storage
├── reasoning/                         # Glass Box Reasoning Engine
│   ├── ReasoningStep.java             # Individual step record
│   ├── ReasoningTrace.java            # Complete trace container
│   └── ReasoningTracer.java           # Thread-local trace collector
├── repository/
│   ├── ChatLogRepository.java         # Query history repository
│   └── UserRepository.java            # User repository
├── security/
│   ├── CacCertificateParser.java      # DoD CAC DN parsing
│   ├── CacUserDetailsService.java     # X.509 Spring Security integration
│   ├── JwksKeyProvider.java           # OIDC JWKS key management
│   └── JwtValidator.java              # JWT token validation
├── service/
│   ├── AuditService.java              # Compliance logging
│   ├── AuthenticationService.java     # Auth interface
│   ├── CacAuthenticationService.java  # CAC/PIV X.509 authentication
│   ├── DevAuthenticationService.java  # Development mode auth
│   ├── OidcAuthenticationService.java # OIDC JWT authentication
│   ├── PiiRedactionService.java       # Automatic PII masking
│   ├── QueryDecompositionService.java # Multi-query handling
│   ├── SecureIngestionService.java    # Document processing
│   └── StandardAuthenticationService.java  # Username/password auth
└── vector/
    └── LocalMongoVectorStore.java     # MongoDB vector operations

src/main/resources/
├── static/
│   ├── index.html                     # Dashboard with Glass Box UI
│   ├── manual.html                    # Operator Field Guide
│   └── images/
└── application.yaml                   # Configuration

src/test/java/
├── security/
│   ├── CacAuthenticationIntegrationTest.java
│   └── X509AuthenticationTest.java
└── service/
    └── ...
```

---

## Core Components

### 1. Authentication Services

#### OidcAuthenticationService.java
Enterprise OIDC authentication with JWT validation and JWKS key rotation.

```java
@Service
@ConditionalOnProperty(name = "app.auth.mode", havingValue = "OIDC")
public class OidcAuthenticationService implements AuthenticationService {
    // JWT validation via Nimbus JOSE+JWT
    // JWKS key caching with configurable rotation
    // Auto-provisioning of new users from claims
}
```

#### CacAuthenticationService.java
Government CAC/PIV X.509 certificate authentication.

```java
@Service
@ConditionalOnProperty(name = "app.auth.mode", havingValue = "CAC")
public class CacAuthenticationService implements AuthenticationService {
    // X.509 certificate validation
    // EDIPI extraction from DoD DNs
    // Clearance-based authorization
}
```

#### StandardAuthenticationService.java
Standalone username/password authentication with BCrypt hashing.

```java
@Service
@ConditionalOnProperty(name = "app.auth.mode", havingValue = "STANDARD")
public class StandardAuthenticationService implements AuthenticationService {
    // BCrypt password hashing
    // Session management
    // Account lockout protection
}
```

### 2. Glass Box Reasoning Engine

#### ReasoningStep.java
```java
public record ReasoningStep(
    StepType type,
    String label,
    String detail,
    long durationMs,
    Map<String, Object> data
) {
    public enum StepType {
        QUERY_ANALYSIS, QUERY_DECOMPOSITION, VECTOR_SEARCH,
        KEYWORD_SEARCH, RETRIEVAL, RERANKING, POISON_DETECTION,
        GRAPH_TRAVERSAL, GAP_DETECTION, CONTEXT_ASSEMBLY,
        PROMPT_CONSTRUCTION, GENERATION, LLM_GENERATION, FILTERING,
        CITATION_VERIFICATION, RESPONSE_FORMATTING, CACHE_OPERATION,
        SECURITY_CHECK, ERROR
    }
}
```

#### ReasoningTracer.java
Thread-local trace collection for request-scoped reasoning chains.

```java
@Component
@RequestScope
public class ReasoningTracer {
    public void addStep(StepType type, String label, String detail, long durationMs);
    public void addStep(StepType type, String label, String detail, long durationMs, Map<String, Object> data);
    public ReasoningTrace getTrace();
}
```

### 3. Advanced RAG Components

#### HiFiRagService.java (HiFi-RAG)
Iterative two-pass retrieval based on arXiv:2512.22442v1.

```java
@Service
public class HiFiRagService {
    public List<Document> retrieve(String query, int topK);
    // Pass 1: Broad retrieval
    // Pass 2: Gap detection + focused refinement
    // Cross-encoder reranking via Ollama
}
```

#### RagPartService.java (RAGPart)
Corpus poisoning defense based on arXiv:2512.24268v1.

```java
@Service
public class RagPartService {
    public List<Document> safeRetrieve(String query, int topK);
    // SHA-256 document partitioning
    // Cross-partition consistency scoring
    // Suspicion flagging for anomalous documents
}
```

#### HyperGraphMemory.java (HGMem)
Hypergraph memory for multi-hop reasoning based on arXiv:2512.23959v2.

```java
@Service
public class HyperGraphMemory {
    public void indexDocument(Document doc);
    public Set<String> traverseFrom(Set<String> entityIds, int maxHops);
    // Entity extraction via pattern-based NER
    // MongoDB hypergraph storage
    // BFS multi-hop traversal
}
```

### 4. Security Components

#### PiiRedactionService.java
Automatic PII detection and redaction during ingestion.

```java
@Service
public class PiiRedactionService {
    public String redact(String content);
    // SSN: XXX-XX-XXXX → [REDACTED-SSN]
    // Credit Card: XXXX-XXXX-XXXX-XXXX → [REDACTED-CC]
    // Email: user@example.com → [REDACTED-EMAIL]
    // Phone, DOB, MRN, IP addresses
}
```

#### JwtValidator.java
Pure Java JWT validation (air-gap safe).

```java
@Component
public class JwtValidator {
    public JWTClaimsSet validate(String token);
    // RS256/RS384/RS512 signature verification
    // Issuer and audience validation
    // Expiration checking
}
```

---

## API Endpoints

### Core Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/ask` | POST | Execute query (legacy) |
| `/api/ask/enhanced` | POST | Execute query with full reasoning trace |
| `/api/reasoning/{traceId}` | GET | Retrieve reasoning trace by ID |
| `/api/ingest` | POST | Upload and ingest document |
| `/api/inspect/{filename}` | GET | View document content |
| `/api/audit` | GET | Retrieve audit logs |
| `/api/health` | GET | System health check |
| `/swagger-ui.html` | GET | Interactive API documentation |

### Enhanced Response Format

```json
{
  "answer": "The answer with [citations]...",
  "reasoning": [
    {"type": "query_analysis", "label": "Query Analysis", "durationMs": 15},
    {"type": "vector_search", "label": "Vector Search", "durationMs": 234},
    {"type": "reranking", "label": "HiFi Reranking", "durationMs": 1823}
  ],
  "sources": ["document1.pdf", "document2.txt"],
  "metrics": {"totalDurationMs": 3421, "documentsRetrieved": 8},
  "traceId": "abc123"
}
```

---

## Configuration

### application.yaml

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
  auth:
    mode: ${AUTH_MODE:DEV}
  hifirag:
    reranker:
      model: ${RERANKER_MODEL:llama3}
      top-k: 5
      max-iterations: 3
  ragpart:
    partitions: 4
    suspicion-threshold: 0.7
  oidc:
    issuer: ${OIDC_ISSUER:}
    audience: ${OIDC_AUDIENCE:}
    jwks-uri: ${OIDC_JWKS_URI:}
  cac:
    auto-provision: true
    default-role: ANALYST
    default-clearance: SECRET

springdoc:
  api-docs:
    enabled: true
  swagger-ui:
    enabled: true
    path: /swagger-ui.html
```

---

## Dependencies

```groovy
dependencies {
    // Spring Boot
    implementation 'org.springframework.boot:spring-boot-starter-data-mongodb'
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-thymeleaf'

    // Spring AI
    implementation 'org.springframework.ai:spring-ai-ollama-spring-boot-starter'
    implementation 'org.springframework.ai:spring-ai-mongodb-atlas-store-spring-boot-starter'
    implementation 'org.springframework.ai:spring-ai-pdf-document-reader'

    // Security
    implementation 'org.springframework.security:spring-security-crypto:6.2.1'
    implementation 'org.springframework.security:spring-security-core:6.2.1'
    implementation 'com.nimbusds:nimbus-jose-jwt:9.37.3'

    // Document Processing
    implementation 'org.apache.tika:tika-core:2.9.2'
    implementation 'org.apache.tika:tika-parsers-standard-package:2.9.2'

    // API Documentation
    implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.5.0'

    // Utilities
    implementation 'org.apache.commons:commons-math3:3.6.1'

    // Testing
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.mockito:mockito-junit-jupiter:5.11.0'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}
```

---

## Security Model

### Authentication Modes

| Profile | Auth Mechanism | Security Standard |
|---------|----------------|-------------------|
| `govcloud` | X.509 Mutual TLS (CAC/PIV) | FIPS 201 / DoDI 8520.02 |
| `enterprise` | JWT Bearer Token (OIDC) | NIST SP 800-63C |
| `standard` | Username/Password (BCrypt) | — |
| `dev` | Auto-provisioned Demo User | None |

### Clearance Levels

| Level | Label | Numeric Value |
|-------|-------|---------------|
| TOP_SECRET | Top Secret / SCI | 3 |
| SECRET | Secret | 2 |
| CUI | Controlled Unclassified | 1 |
| UNCLASSIFIED | Unclassified | 0 |

### Role-Based Access Control

| Role | Permissions |
|------|-------------|
| ADMIN | QUERY, INGEST, DELETE, MANAGE_USERS, VIEW_AUDIT, CONFIGURE |
| ANALYST | QUERY, INGEST |
| VIEWER | QUERY |
| AUDITOR | QUERY, VIEW_AUDIT |

---

## Compliance

Sentinel's security architecture supports:

- **NIST AI RMF** — Risk management for AI systems
- **DoD AI Ethics Principles** — Responsible AI for defense
- **FIPS 201** — PIV card authentication
- **DoDI 8520.02** — PKI and certificate policy
- **NIST SP 800-63C** — Digital identity federation
- **STIG** — Security Technical Implementation Guides

---

*SENTINEL INTELLIGENCE PLATFORM v2.0.0 — Enterprise RAG for Regulated Industries*
