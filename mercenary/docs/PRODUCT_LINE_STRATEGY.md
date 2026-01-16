# SENTINEL Product Line Strategy

## Overview

SENTINEL is offered in four editions to serve different market segments:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        SENTINEL PRODUCT LINE                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐  ┌─────────┐│
│  │   ENTERPRISE    │  │  PROFESSIONAL   │  │    RESEARCH     │  │COMMUNITY││
│  │   $150K-250K    │  │   $25K-50K      │  │   $5K-15K       │  │  FREE   ││
│  │                 │  │                 │  │                 │  │         ││
│  │ Gov/Defense     │  │ Corporate       │  │ Academic        │  │ Open    ││
│  │ Medical/Legal   │  │ Legal/Finance   │  │ Startups        │  │ Source  ││
│  │                 │  │                 │  │ Researchers     │  │ Core    ││
│  └─────────────────┘  └─────────────────┘  └─────────────────┘  └─────────┘│
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Edition Comparison

### Feature Matrix

| Feature | Enterprise | Professional | Research | Community |
|---------|:----------:|:------------:|:--------:|:---------:|
| **RAG Technologies** |
| HiFi-RAG (Two-Pass Retrieval) | ✅ | ✅ | ✅ | ✅ |
| RAGPart (Poisoning Defense) | ✅ | ✅ | ✅ | ✅ |
| HGMem (Hypergraph Memory) | ✅ | ✅ | ✅ | ✅ |
| QuCo-RAG (Hallucination Defense) | ✅ | ✅ | ✅ | ✅ |
| MegaRAG (Multimodal) | ✅ | ✅ | ✅ | ❌ |
| MiA-RAG (Mindscape) | ✅ | ✅ | ✅ | ❌ |
| Bidirectional RAG (Experience Store) | ✅ | ✅ | ✅ | ❌ |
| Hybrid RAG (RRF Fusion) | ✅ | ✅ | ✅ | ❌ |
| Graph-O1 (MCTS Reasoning) | ✅ | ✅ | ✅ | ❌ |
| **Security** |
| CAC/PIV X.509 Authentication | ✅ | ❌ | ❌ | ❌ |
| OIDC/JWT SSO (Azure AD, Okta) | ✅ | ✅ | ❌ | ❌ |
| Username/Password (BCrypt) | ✅ | ✅ | ✅ | ✅ |
| 5-Tier Clearance Levels | ✅ | ❌ | ❌ | ❌ |
| Sector-Based Access Control | ✅ | ✅ | ❌ | ❌ |
| PII Redaction Engine | ✅ | ✅ | Optional | ❌ |
| STIG-Compliant Audit Logging | ✅ | ✅ | Basic | ❌ |
| Prompt Injection Defense (3-Layer) | ✅ | ✅ | ✅ | ✅ |
| Rate Limiting (Bucket4j) | ✅ | ✅ | ✅ | ❌ |
| Magic Byte File Validation | ✅ | ✅ | ✅ | ✅ |
| Fail-Closed Audit Mode | ✅ | ✅ | ❌ | ❌ |
| **Infrastructure** |
| Glass Box Reasoning | ✅ | ✅ | ✅ | ✅ |
| Citation Enforcement | ✅ | ✅ | ✅ | ✅ |
| Air-Gap Deployment | ✅ | ✅ | ✅ | ✅ |
| Multi-Query Decomposition | ✅ | ✅ | ✅ | ✅ |
| Docker Support | ✅ | ✅ | ✅ | ✅ |
| **Support** |
| Priority Support | ✅ | ✅ | Email | Community |
| Custom Integration | ✅ | ✅ | ❌ | ❌ |
| Training | ✅ | Optional | ❌ | ❌ |
| SLA | 99.9% | 99.5% | Best Effort | None |

---

## Security Differentiators

### Multi-Layer Prompt Injection Defense

SENTINEL implements a **3-layer defense** against prompt injection attacks - a key differentiator against "wrapper" competitors who rely solely on system prompts:

```
┌─────────────────────────────────────────────────────────────────┐
│                    PROMPT INJECTION DEFENSE                      │
├─────────────────────────────────────────────────────────────────┤
│  Layer 1: PATTERN DETECTION                                      │
│  ├── Regex-based suspicious pattern matching                    │
│  ├── Known injection signatures                                  │
│  └── Immediate blocking + audit logging                         │
├─────────────────────────────────────────────────────────────────┤
│  Layer 2: SEMANTIC ANALYSIS                                      │
│  ├── Embedding-based similarity detection                       │
│  ├── Context deviation scoring                                   │
│  └── Anomaly flagging                                            │
├─────────────────────────────────────────────────────────────────┤
│  Layer 3: LLM GUARDRAILS (Optional)                              │
│  ├── Secondary LLM review of suspicious prompts                 │
│  ├── Intent classification                                       │
│  └── Configurable: app.guardrails.llm-enabled=true              │
└─────────────────────────────────────────────────────────────────┘
```

**Sales Messaging:**
> "Unlike wrapper solutions that rely on hope, SENTINEL actively defends against prompt injection with pattern matching, semantic analysis, and optional LLM-based review. Every blocked attempt is audit-logged for compliance."

### DoS Protection

Role-based rate limiting protects the API from abuse:

| Role | Requests/Minute | Use Case |
|------|-----------------|----------|
| ADMIN | 200 | System administration |
| ANALYST | 100 | Power users, analysts |
| VIEWER | 60 | Standard users |
| Anonymous | 30 | Unauthenticated access |

**Implementation:** Bucket4j with Caffeine cache backend (`RateLimitFilter.java`)

### File Ingestion Security

- **Magic byte detection** (Apache Tika) - blocks executables regardless of extension
- **MIME type validation** - rejects dangerous file types
- **Size limits** - prevents resource exhaustion
- **Audit logging** - all ingestion attempts logged

### Data Isolation

- **Compound cache keys** (`sector:filename`) prevent cross-department data leaks
- **Clearance-based filtering** - users only see sectors at or below their clearance
- **API-driven sector config** - sector names not exposed to unauthorized users

---

## Edition Details

### SENTINEL Enterprise

**Target Market:** Government, Defense, Intelligence, Medical (HIPAA), Legal (Privileged)

**Price:** $150,000 - $250,000 (perpetual license + annual support)

**Key Differentiators:**
- CAC/PIV X.509 mutual TLS authentication (FIPS 201 compliant)
- 5-tier clearance model (UNCLASSIFIED → SCI)
- Sector-based document partitioning
- Full PII redaction (NIST 800-122, GDPR, HIPAA, PCI-DSS)
- STIG-aligned audit logging for compliance
- Air-gap deployment support for classified environments

**Ideal Customer:**
- DoD contractors and agencies
- Intelligence community
- Healthcare systems with PHI
- Law firms with privileged documents
- Financial institutions with PCI requirements

---

### SENTINEL Professional

**Target Market:** Corporate, Legal, Financial, Enterprise IT

**Price:** $25,000 - $50,000 (annual subscription)

**Key Differentiators:**
- OIDC SSO integration (Azure AD, Okta, Auth0)
- Sector-based access control (without clearance levels)
- Full PII redaction
- Audit logging (simplified, non-STIG)
- All 9 RAG technologies

**Ideal Customer:**
- Mid-size law firms
- Corporate knowledge management
- Financial services (non-PCI)
- Healthcare (research, non-PHI)
- Enterprise IT departments

---

### SENTINEL Research

**Target Market:** Universities, Research Labs, AI Startups, Individual Researchers

**Price:** $5,000 - $15,000 (annual subscription) | $500 - $2,000 (individual)

**Key Differentiators:**
- Simple username/password authentication
- All 9 cutting-edge RAG technologies
- Full academic paper citations for methodology
- Glass Box reasoning for explainability research
- Self-hosted (data stays local)
- No GPU required (Ollama CPU inference)

**Ideal Customer:**
- University AI/ML research labs
- NLP/IR researchers
- AI startups building RAG products
- PhD students working on retrieval
- Corporate R&D teams

**Marketing Angle:**
> "The only RAG platform with 9 peer-reviewed academic papers implemented.
> Cite real arXiv papers in your research methodology."

---

### SENTINEL Community

**Target Market:** Open Source Community, Hobbyists, Evaluators

**Price:** Free (Apache 2.0 or similar)

**Key Differentiators:**
- Core RAG engine (HiFi-RAG, RAGPart, HGMem, QuCo-RAG)
- Basic authentication
- Glass Box reasoning
- Citation enforcement
- Community support only

**Ideal Customer:**
- Developers evaluating RAG solutions
- Open source contributors
- Students learning RAG
- Hobbyists building personal knowledge bases

**Strategic Purpose:**
- Funnel for paid editions
- Community building
- Bug discovery
- Brand awareness

---

## Technical Architecture by Edition

### Codebase Structure

```
sentinel/
├── sentinel-core/           # Shared core (all editions)
│   ├── rag/
│   │   ├── hifirag/
│   │   ├── ragpart/
│   │   ├── hgmem/
│   │   └── qucorag/
│   ├── reasoning/
│   └── vector/
│
├── sentinel-advanced/       # Advanced RAG (Research+)
│   ├── rag/
│   │   ├── megarag/
│   │   ├── miarag/
│   │   ├── birag/
│   │   ├── hybridrag/
│   │   └── grapho1/
│
├── sentinel-security/       # Security modules (Professional+)
│   ├── security/
│   │   ├── oidc/
│   │   └── audit/
│   ├── service/
│   │   └── PiiRedactionService.java
│   └── filter/
│
├── sentinel-enterprise/     # Enterprise-only (Enterprise)
│   ├── security/
│   │   ├── cac/
│   │   └── clearance/
│   └── compliance/
│
├── sentinel-community/      # Community edition build
├── sentinel-research/       # Research edition build
├── sentinel-professional/   # Professional edition build
└── sentinel-enterprise/     # Enterprise edition build
```

### Module Dependencies

```
                    ┌─────────────────────┐
                    │  sentinel-enterprise │
                    │  (CAC, Clearance)   │
                    └──────────┬──────────┘
                               │
                    ┌──────────▼──────────┐
                    │ sentinel-security   │
                    │ (OIDC, PII, Audit)  │
                    └──────────┬──────────┘
                               │
                    ┌──────────▼──────────┐
                    │  sentinel-advanced  │
                    │ (MegaRAG, MiA, etc) │
                    └──────────┬──────────┘
                               │
                    ┌──────────▼──────────┐
                    │    sentinel-core    │
                    │ (HiFi, RAGPart, etc)│
                    └─────────────────────┘
```

---

## Pricing Strategy

### Revenue Model

| Edition | License Type | Price Range | Target Volume |
|---------|-------------|-------------|---------------|
| Enterprise | Perpetual + Support | $150K-250K + $30K/yr | 5-15/year |
| Professional | Annual Subscription | $25K-50K/yr | 50-200/year |
| Research | Annual Subscription | $5K-15K/yr | 200-1000/year |
| Community | Free | $0 | Unlimited |

### Projected Revenue (Year 1)

| Edition | Avg Price | Volume | Revenue |
|---------|-----------|--------|---------|
| Enterprise | $180K | 8 | $1.44M |
| Professional | $35K | 100 | $3.5M |
| Research | $8K | 400 | $3.2M |
| Community | $0 | 5000 | $0 |
| **Total** | | | **$8.14M** |

### Conversion Funnel

```
Community (Free) → Research ($8K) → Professional ($35K) → Enterprise ($180K)
     5000              400              100                    8
      8%               25%              8%
```

---

## Implementation Roadmap

### Phase 1: Core Extraction (Week 1)
- [ ] Extract sentinel-core module
- [ ] Create module build system (Gradle multi-project)
- [ ] Verify core tests pass

### Phase 2: Advanced Module (Week 1-2)
- [ ] Extract sentinel-advanced module
- [ ] Create Research edition build
- [ ] Research edition documentation

### Phase 3: Security Module (Week 2)
- [ ] Extract sentinel-security module
- [ ] Create Professional edition build
- [ ] Professional edition documentation

### Phase 4: Enterprise Module (Week 2-3)
- [ ] Verify Enterprise edition (current codebase)
- [ ] Enterprise edition documentation
- [ ] Deployment guides per edition

### Phase 5: Community Edition (Week 3)
- [ ] Create stripped-down Community build
- [ ] Open source licensing review
- [ ] Community documentation
- [ ] GitHub repository setup

### Phase 6: Launch Preparation (Week 4)
- [ ] Marketing materials per edition
- [ ] Pricing page
- [ ] Sales collateral
- [ ] Demo environments

---

## Go-to-Market Strategy

### Enterprise
- Direct sales
- Government contracting vehicles (GSA, SEWP)
- Defense industry conferences
- Healthcare IT conferences

### Professional
- Inside sales + self-service
- Legal tech conferences
- Financial services conferences
- Partner channel (system integrators)

### Research
- Self-service purchase
- Academic conferences (ACL, EMNLP, NeurIPS)
- University licensing programs
- Startup accelerator partnerships

### Community
- GitHub
- Hacker News / Reddit
- AI/ML community forums
- YouTube tutorials

---

## Success Metrics

| Metric | Target (Year 1) |
|--------|-----------------|
| Enterprise deals closed | 8-15 |
| Professional subscriptions | 100-200 |
| Research subscriptions | 400-1000 |
| Community downloads | 5000+ |
| GitHub stars | 1000+ |
| Academic citations | 50+ |

---

*Document Version: 1.0*
*Last Updated: January 2026*
