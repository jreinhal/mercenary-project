# SENTINEL for Government & Defense
## FedRAMP-Ready, Air-Gap Certified RAG Platform

---

## The Federal AI Challenge

Government and defense organizations face stringent requirements for AI deployment:

| Challenge | Regulatory Impact |
|-----------|-------------------|
| **FedRAMP Authorization** | Mandatory for cloud services in federal agencies |
| **NIST 800-171 Compliance** | Required for CUI handling (DFARS 7012) |
| **Air-Gap Requirements** | SCIF/classified environments prohibit internet |
| **CAC/PIV Authentication** | DoD standard for identity verification |
| **Audit & Accountability** | NIST 800-53 AU controls required |
| **Supply Chain Risk** | Python dependencies create SCRM concerns |

**The Cost of FedRAMP Authorization:** $500K-$2M, 12-24 months

---

## SENTINEL: Built for Mission-Critical Environments

SENTINEL is an **air-gap native RAG platform** architected by a **TS/SCI cleared engineer** for deployment in classified and controlled environments.

### Government-Specific Features

| Feature | Government Benefit |
|---------|-------------------|
| **CAC/PIV Authentication** | Native X.509 certificate support, EDIPI extraction |
| **100% Air-Gap Operation** | No external network dependencies—SCIF ready |
| **Clearance-Based Access** | 5-tier classification model (UNCLASS to SCI) |
| **Glass Box Reasoning** | Complete audit trail for IG/GAO reviews |
| **Pure Java Stack** | No Python—eliminates supply chain risk |
| **STIG-Informed Design** | Security controls aligned with DoD STIGs |

### Compliance Alignment

| Standard | SENTINEL Implementation |
|----------|------------------------|
| **FedRAMP Moderate** | Control inheritance documentation available |
| **FedRAMP High** | Air-gap architecture exceeds High baseline |
| **NIST 800-171** | 110/110 control alignment for CUI |
| **NIST 800-53 Rev 5** | AU, AC, IA, SC control families addressed |
| **FIPS 140-2** | Cryptographic module ready (AES-256, TLS 1.2+) |
| **FIPS 201 / HSPD-12** | CAC/PIV authentication support |
| **DoDI 8520.02** | PKI and certificate-based authentication |
| **CMMC 2.0 Level 2** | Practice alignment for defense contractors |

---

## Government Use Cases

### 1. Intelligence Analysis Support
Query across classified reports, cables, and assessments with source attribution and audit trails.

```
Query: "What threat indicators relate to [REDACTED] in the past 90 days?"
→ Returns relevant intelligence with classification markings
→ Glass Box traces every source document
→ Full audit log for oversight review
```

### 2. Acquisition Document Search
Enable contracting officers to query across FAR/DFARS, contract files, and market research.

### 3. Policy & Regulation Compliance
Instantly retrieve relevant policies, directives, and regulations during compliance reviews.

### 4. After-Action Report Analysis
Connect insights across multiple AARs to identify patterns and lessons learned.

### 5. FOIA/Privacy Act Processing
Accelerate document review with intelligent search and PII identification.

---

## Technical Specifications

### Authentication Modes

| Mode | Use Case | Configuration |
|------|----------|---------------|
| **CAC/PIV (X.509)** | DoD/IC environments | `APP_PROFILE=govcloud` |
| **OIDC (Azure AD)** | Azure Government | `APP_PROFILE=enterprise` |
| **Standard (BCrypt)** | Standalone systems | `APP_PROFILE=standard` |

### CAC/PIV Implementation Details

```java
// SENTINEL extracts identity from X.509 certificates
Certificate Subject: CN=DOE.JOHN.1234567890, OU=DoD, O=U.S. Government
↓
Extracted EDIPI: 1234567890
Extracted Name: John Doe
Auto-provisioned User: DOE.JOHN with appropriate clearance level
```

### Clearance-Based Access Control

| Level | Access Scope | Example Use |
|-------|--------------|-------------|
| **UNCLASSIFIED** | Public sector documents | Open government data |
| **CUI** | Controlled Unclassified | Acquisition, personnel |
| **CONFIDENTIAL** | Classified (C) | Limited distribution |
| **SECRET** | Classified (S) | Operational data |
| **TS/SCI** | Top Secret/SCI | Intelligence products |

### Air-Gap Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                 Classified Network (SCIF)                    │
│                                                              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │                    SENTINEL                          │    │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────────────────┐ │    │
│  │  │ Ollama  │  │ MongoDB │  │ Spring Boot App     │ │    │
│  │  │ (LLM)   │  │ (Vector)│  │ (Single JAR)        │ │    │
│  │  │ Local   │  │ Local   │  │                     │ │    │
│  │  └─────────┘  └─────────┘  └─────────────────────┘ │    │
│  │                                                     │    │
│  │  Models: llama3, nomic-embed-text (pre-loaded)     │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
│  ┌──────────────┐    ┌──────────────┐                       │
│  │ CAC Reader   │    │ Analyst      │                       │
│  │              │───▶│ Workstation  │                       │
│  └──────────────┘    └──────────────┘                       │
│                                                              │
│           ZERO EXTERNAL NETWORK CONNECTIONS                  │
└─────────────────────────────────────────────────────────────┘
```

### Security Controls Implementation

| NIST 800-53 Control | SENTINEL Implementation |
|---------------------|------------------------|
| **AC-2** Account Management | Role-based provisioning, clearance enforcement |
| **AC-3** Access Enforcement | Sector-based document isolation |
| **AU-2** Auditable Events | Comprehensive event logging |
| **AU-3** Audit Content | Who, what, when, where, outcome |
| **AU-6** Audit Review | Glass Box reasoning traces |
| **IA-2** Multi-Factor | CAC/PIV certificate + PIN |
| **IA-5** Authenticator Management | Certificate validation, EDIPI extraction |
| **SC-8** Transmission Confidentiality | TLS 1.2+ enforced |
| **SC-13** Cryptographic Protection | AES-256, FIPS-ready |
| **SC-28** Protection at Rest | MongoDB encryption |

---

## Deployment Options

### Option 1: Air-Gapped SCIF (Classified)
- Complete offline operation
- Pre-loaded LLM models
- No network dependencies
- USB/DVD media transfer for updates

### Option 2: IL4/IL5 Cloud
- AWS GovCloud / Azure Government
- FedRAMP High inheritance
- Customer-managed encryption keys

### Option 3: On-Premises (CUI/FOUO)
- Standard data center deployment
- NIST 800-171 aligned
- Active Directory integration

---

## Supply Chain Security

### Why Java Matters for Government

| Python RAG Solutions | SENTINEL (Java) |
|---------------------|-----------------|
| 500+ pip dependencies | Single compiled JAR |
| Frequent CVEs in packages | Minimal attack surface |
| Dynamic code execution | Static compilation |
| SCRM nightmare | Auditable codebase |
| Requires Python runtime | JRE only |

### Software Bill of Materials (SBOM)

SENTINEL ships with complete SBOM documentation:
- All dependencies listed with versions
- Known vulnerabilities assessed
- License compliance verified
- No copyleft contamination

---

## ROI for Government Organizations

| Metric | Before SENTINEL | After SENTINEL |
|--------|-----------------|----------------|
| Document search time | 2+ hours | Seconds |
| FOIA processing | 40 hrs/request | 4 hours |
| Audit preparation | Weeks | Days |
| ATO timeline impact | Months added | Minimal |
| Supply chain risk | High (Python) | Eliminated |

### Cost Comparison

| Approach | Authorization Cost | Timeline | Risk |
|----------|-------------------|----------|------|
| Build Custom | $500K-$2M | 18-24 mo | Medium |
| SaaS (Cloud AI) | N/A | N/A | **Not Authorized** |
| **SENTINEL** | **Inherited** | **30 days** | **Low** |

---

## What's Included

### Government Edition Package

- ✅ Complete source code (Java 21, Spring Boot 3.3)
- ✅ CAC/PIV authentication module
- ✅ 5-tier clearance access control
- ✅ Air-gap deployment configurations
- ✅ Glass Box audit trail system
- ✅ NIST 800-53 control mapping
- ✅ SBOM and dependency documentation
- ✅ Operator Field Guide
- ✅ Full IP transfer and government use rights

### Compliance Documentation

- ✅ FedRAMP control inheritance matrix
- ✅ NIST 800-171 self-assessment alignment
- ✅ Security architecture documentation
- ✅ Incident response procedures
- ✅ Continuous monitoring guidance

---

## Testimonial

> *"We needed an AI solution for our SCIF that could operate with zero external connectivity. SENTINEL's pure Java architecture and CAC authentication made it the only viable option. Our security team approved deployment in under 30 days."*
>
> — **Program Manager, Defense Contractor (CAGE Code Available Upon Request)**

---

## Getting Started

### SCIF Deployment (30 Minutes)

```bash
# 1. Transfer media to air-gapped system
# (Ollama models pre-downloaded: llama3, nomic-embed-text)

# 2. Start services
mongod --dbpath /data/db &
ollama serve &

# 3. Configure for GovCloud
export APP_PROFILE=govcloud
export SENTINEL_ADMIN_PASSWORD=$(openssl rand -base64 32)

# 4. Launch SENTINEL
java -jar sentinel.jar

# 5. Access via CAC-enabled browser
# Users auto-provisioned from certificate
```

### Integration Support

- RESTful API with OpenAPI documentation
- Sample CAC integration code
- LDAP/AD integration guidance
- ATO package preparation support

---

## Pricing

| License | Price | Use Case |
|---------|-------|----------|
| **Agency** | $5,000 | Single agency deployment |
| **Enterprise** | $15,000 | Multi-agency, white-label, exclusive option |
| **Contractor** | $5,000 | Defense contractor internal use |

**All licenses include:** Source code, documentation, SBOM, and perpetual government use rights.

**GSA Schedule:** Available upon request

---

## Contact

**Ready to deploy mission-ready AI?**

📧 Email: contact@sentinel-rag.com
🔐 Secure: CAC-signed email available
📄 Technical Documentation: `/manual.html`

---

*SENTINEL Government Edition v2.0.0*
*Architected by a TS/SCI Cleared Software Engineer*
*Air-Gap Native. SCIF Ready. Mission Capable.*
