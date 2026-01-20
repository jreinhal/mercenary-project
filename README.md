# SENTINEL Intelligence Platform

A secure, air-gap compatible RAG (Retrieval-Augmented Generation) platform for enterprise and government deployments.

## Features

### Security & Compliance
- **Air-Gap Compatible**: Full functionality without internet (local Ollama + MongoDB)
- **PII/PHI Redaction**: Automatic detection and redaction of sensitive data
- **Prompt Injection Defense**: Multi-layer detection with suspicious pattern blocking
- **HIPAA Ready**: PHI handling, audit trails, access controls (Medical edition)
- **CAC/PIV Authentication**: Smart card support (Government edition)
- **Sector Isolation**: Department-based document isolation with clearance enforcement

### RAG Intelligence
- **Self-Reflective RAG**: AI self-critique and refinement
- **Citation Verification**: Automatic source verification with confidence scoring
- **Query Decomposition**: Complex questions broken into sub-queries
- **Hybrid Search**: Vector similarity + BM25 keyword search (RRF fusion)
- **Conversation Memory**: Context-aware follow-up questions

## Quick Start

### Prerequisites
- Java 21+
- Docker & Docker Compose
- MongoDB 7.0+
- Ollama with llama3.1:8b model

### Running with Docker

```bash
# Development mode
docker-compose up -d

# Production mode
docker-compose -f docker-compose.prod.yml up -d
```

### Running Locally

```bash
# Build and run (default: government edition)
./gradlew bootRun

# Build specific edition
./gradlew build -Pedition=professional
./gradlew build -Pedition=medical
./gradlew build -Pedition=government
```

### Default Credentials

| Mode | Username | Password |
|------|----------|----------|
| DEV | (no login required) | - |
| STANDARD | admin | h!iK*4WzdRehyd6ej^xHjZTPruuY |

## Build Editions

| Edition | Packages | Use Case |
|---------|----------|----------|
| Trial | core + professional | 30-day evaluation |
| Professional | core + professional | Commercial/academic |
| Medical | core + professional + medical | HIPAA-compliant |
| Government | all packages | SCIF/air-gapped |

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| APP_PROFILE | dev | Spring profile (dev, standard, enterprise, govcloud) |
| AUTH_MODE | DEV | Authentication mode (DEV, STANDARD, OIDC, CAC) |
| SPRING_DATA_MONGODB_URI | mongodb://localhost:27017/sentinel | MongoDB connection |
| SPRING_AI_OLLAMA_BASE_URL | http://localhost:11434 | Ollama API endpoint |

### Profiles

- **dev**: Development mode, no auth required
- **standard**: Basic auth with credentials
- **enterprise**: OIDC/JWT authentication
- **govcloud**: CAC/PIV certificate authentication

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| /api/ask | POST | Submit a question to the RAG system |
| /api/ingest | POST | Upload documents for indexing |
| /api/health | GET | Health check |
| /api/admin/** | * | Admin operations (ADMIN role required) |

## Architecture

```
src/main/java/com/jreinhal/mercenary/
├── core/           ← Shared functionality (all editions)
├── professional/   ← Paid features (trial+)
├── medical/        ← HIPAA compliance (medical+)
└── government/     ← SCIF/CAC (government only)
```

## Testing

```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests "CacCertificateParserTest"
```

## Security Notes

- Never expose MongoDB or Ollama ports publicly in production
- Use `docker-compose.prod.yml` for production deployments
- CAC authentication requires mutual TLS configuration on the server
- Review `CLAUDE.md` for development security guidelines

## License

Proprietary - All rights reserved.
