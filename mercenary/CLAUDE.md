# SENTINEL Intelligence Platform - Development Guidelines

## Repository Structure

**Single repo, multiple build editions.** All development happens in `mercenary/`. The old sentinel-community, sentinel-professional, sentinel-research repos are deprecated.

## Package Organization

```
src/main/java/com/jreinhal/mercenary/
├── core/           ← All editions (shared functionality)
├── professional/   ← Paid features (trial, professional, medical, government)
├── medical/        ← HIPAA compliance (medical + government only)
└── government/     ← SCIF/CAC/clearance (government only)
```

## Build Editions

| Edition | Packages Included | Notes |
|---------|-------------------|-------|
| Trial | core + professional | 30-day time limit, full features |
| Professional | core + professional | Commercial/academic customers |
| Medical | core + professional + medical | HIPAA-compliant deployments |
| Government | all packages | SCIF/air-gapped, CAC auth, clearance levels |

Build command: `./gradlew build -Pedition=government`

## Critical Constraints

1. **SCIF/Air-Gap Compliance is Paramount**
   - No external API calls (OpenAI, Anthropic, etc.)
   - All LLM processing via local Ollama
   - All data stays local (MongoDB, local storage)
   - No telemetry or phone-home features

2. **Edition Isolation**
   - Government code must NEVER appear in non-government builds
   - Medical/HIPAA code must NEVER appear in trial/professional builds
   - Use Gradle source exclusions, not runtime feature flags for sensitive code

3. **Security Standards**
   - OWASP Top 10 compliance required
   - All security fixes apply to all editions (in core/)
   - CAC/PIV authentication is government-only
   - OIDC approval workflow enabled for all editions

## Commit Guidelines

- All commits go to this single mercenary repo
- Security fixes: prefix with "security:"
- Edition-specific features: note which package (e.g., "government: add X")
- Always include `Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>` when Claude assists

## Target Markets

- **Government**: DoD, Intel Community, Federal agencies (requires FedRAMP path)
- **Medical**: Hospitals, research institutions, pharma (requires HIPAA)
- **Professional**: Law firms, finance, enterprise, academia
- **Trial**: 30-day full-feature evaluation for all prospects

## Key Capabilities (Sales/Documentation Reference)

### Security & Compliance
- **Fail-Closed Auditing**: STIG-compliant audit logging that halts operations on failure (govcloud profile)
- **Magic Byte File Detection**: Apache Tika-based content analysis blocks executables regardless of extension
- **Prompt Injection Defense**: Multi-layer detection with suspicious pattern blocking and audit logging
- **PII/PHI Redaction**: Automatic detection and redaction of sensitive data (SSN, credit cards, emails)
- **NIST 800-53 Compliance**: Documented control mapping for ATO packages (see docs/COMPLIANCE_MATRIX.md)
- **HIPAA Ready**: PHI handling, audit trails, access controls for medical deployments
- **FedRAMP Path**: Architecture supports eventual FedRAMP authorization

### RAG Intelligence Features
- **Self-Reflective RAG**: AI self-critique and refinement for improved answer quality
- **Citation Verification**: Automatic source verification with confidence scoring
- **Query Decomposition**: Complex questions broken into sub-queries for comprehensive answers
- **Hybrid Search**: Combines vector similarity with BM25 keyword search (RRF fusion)
- **Conversation Memory**: Context-aware follow-up questions with session persistence
- **Multi-Sector Support**: Department-based document isolation with clearance enforcement

### Deployment Options
- **Air-Gapped/SCIF**: Full functionality without internet (local Ollama + MongoDB)
- **CAC/PIV Authentication**: Smart card support for government deployments
- **Docker Hardened**: Pinned versions, resource limits, non-root containers
- **Multi-Edition Builds**: Single codebase, compile-time feature isolation
