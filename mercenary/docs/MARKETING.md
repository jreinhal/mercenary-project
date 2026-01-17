# SENTINEL Marketing & Sales Reference

**Last Updated:** January 2026

This document consolidates all marketing messaging, competitive positioning, and sales talking points for SENTINEL.

---

## Elevator Pitch

**30-Second Version:**
> SENTINEL is an air-gap-ready intelligence platform with 9 peer-reviewed RAG technologies, multi-layer security defenses, and full NIST 800-53 compliance. Unlike wrapper solutions, we implement hallucination defense, prompt injection protection, and glass-box reasoning that your security team can audit.

**10-Second Version:**
> Air-gapped RAG with real security. Not another wrapper.

---

## Key Differentiators

### 1. Multi-Layer Prompt Injection Defense

**The Problem:** Most RAG solutions rely solely on system prompts for safety.

**Our Solution:** 3-layer defense architecture:
- Layer 1: Pattern detection (regex, known signatures)
- Layer 2: Semantic analysis (embedding similarity, context deviation)
- Layer 3: Optional LLM guardrails (secondary review)

**Proof Point:** `PromptGuardrailService.java` - auditable, not a black box

**Sales Line:**
> "We don't hope attackers play nice. We detect, block, and log every injection attempt."

---

### 2. Hallucination Defense (QuCo-RAG)

**The Problem:** LLMs confidently state false information.

**Our Solution:** Quantified Uncertainty with Conformal RAG (arXiv:2512.19134)
- Uncertainty quantification on every response
- Confidence scoring with statistical guarantees
- "I don't know" is a valid answer

**Proof Point:** Academic paper citation, not marketing claims

**Sales Line:**
> "SENTINEL tells you when it doesn't know. That's not a bug, it's the feature your auditors want."

---

### 3. Glass Box Reasoning

**The Problem:** Black-box AI can't be audited or explained.

**Our Solution:** Full reasoning chain exposed via `askEnhanced` endpoint
- Every retrieval step visible
- Citation verification built-in
- Auditor-friendly output format

**Sales Line:**
> "Your compliance team can trace every answer back to the source document."

---

### 4. True Air-Gap Capability

**The Problem:** "On-premise" solutions still phone home.

**Our Solution:** Zero external dependencies
- Local Ollama for inference
- Local MongoDB for storage
- No telemetry, no license servers, no API calls
- Verified with network traffic analysis

**Proof Point:** `docker-compose.local.yml` - fully self-contained

**Sales Line:**
> "Deploy in a SCIF. We mean it."

---

### 5. Academic Foundation (9 Papers)

**The Problem:** Most RAG is marketing hype without substance.

**Our Solution:** Implementations of peer-reviewed research:

| Technology | Paper | Benefit |
|------------|-------|---------|
| QuCo-RAG | arXiv:2512.19134 | Hallucination defense |
| HiFi-RAG | Two-pass retrieval | Higher precision |
| RAGPart | Poisoning defense | Adversarial robustness |
| HGMem | Hypergraph memory | Long-term context |
| Graph-O1 | MCTS reasoning | Complex queries |

**Sales Line:**
> "Cite arXiv papers in your methodology section, not vendor whitepapers."

---

## Competitive Positioning

### vs. OpenAI/Anthropic API Wrappers

| Factor | Wrappers | SENTINEL |
|--------|----------|----------|
| Data location | Their servers | Your servers |
| Air-gap capable | No | Yes |
| Injection defense | System prompt | 3-layer |
| Audit trail | Limited | Full STIG |
| Hallucination handling | Hope | Quantified |

**Objection Handler:**
> "But GPT-4 is smarter..."
>
> "For classified environments, the question isn't which model is smartest - it's which one can run in your SCIF. And when the answer is wrong, which one tells you it's uncertain?"

---

### vs. Enterprise RAG Vendors (Glean, Guru, etc.)

| Factor | Enterprise RAG | SENTINEL |
|--------|----------------|----------|
| Deployment | SaaS | On-premise/Air-gap |
| Security focus | SSO | Full stack |
| Academic rigor | Marketing | 9 papers |
| Customization | Limited | Source access |

**Objection Handler:**
> "Glean integrates with everything..."
>
> "Integration is great until you need to explain to the CISO why your classified documents are in someone else's cloud. We integrate with your security requirements first."

---

### vs. Open Source (LangChain, LlamaIndex)

| Factor | Open Source | SENTINEL |
|--------|-------------|----------|
| Security hardening | DIY | Built-in |
| Compliance docs | None | NIST 800-53 |
| Support | Community | Enterprise |
| Time to production | Months | Days |

**Objection Handler:**
> "We'll just build it ourselves..."
>
> "You can. Budget 6-12 months and a security audit. Or deploy SENTINEL this quarter with compliance docs ready for your ATO package."

---

## Compliance Messaging

### NIST 800-53 (Federal)

**Implemented Controls:**
- AC-2, AC-3, AC-6, AC-7, AC-17 (Access Control)
- AU-2, AU-3, AU-6, AU-9, AU-12 (Audit)
- IA-2, IA-5, IA-8 (Authentication)
- SC-5, SC-8, SC-13 (Communications)
- SI-2, SI-3, SI-4, SI-10 (Integrity)

**Sales Line:**
> "Pre-mapped to NIST 800-53. Your ATO package is half-written."

### HIPAA (Healthcare)

- PHI detection and redaction
- Audit trails for access
- Role-based access control
- Encryption in transit and at rest

**Sales Line:**
> "HIPAA-ready. Your compliance officer will thank you."

### PCI-DSS (Finance)

- Credit card number detection/masking
- Audit logging
- Access controls

---

## Pricing Guidance

| Edition | Target | Price Range | Key Buyers |
|---------|--------|-------------|------------|
| Enterprise | Gov/Defense | $150K-250K | CISO, Program Manager |
| Professional | Corporate | $25K-50K | IT Director, Legal Ops |
| Research | Academic | $5K-15K | PI, Lab Director |
| Community | Evaluation | Free | Developers |

**Discount Authority:**
- 10% for multi-year
- 15% for government (GSA pricing)
- 20% for academic institutions

---

## Demo Scenarios

### 1. Security Demo (5 min)

1. Show prompt injection attempt being blocked
2. Show audit log entry for the attempt
3. Show rate limiting in action
4. Show file upload rejection (executable disguised as PDF)

### 2. Accuracy Demo (5 min)

1. Ask a question with clear answer in docs
2. Show citation and confidence score
3. Ask a question NOT in docs
4. Show uncertainty response ("insufficient evidence")

### 3. Compliance Demo (5 min)

1. Show COMPLIANCE_MATRIX.md with control mappings
2. Show audit log format (STIG-aligned)
3. Show clearance-based access filtering
4. Show sector isolation

---

## Objection Handling Quick Reference

| Objection | Response |
|-----------|----------|
| "Too expensive" | "What's the cost of a data breach? A failed audit? A hallucinated answer in a legal brief?" |
| "We use GPT-4" | "Great for public data. What about classified? Privileged? PHI?" |
| "We'll build it" | "Happy to help. Here's our 9-paper bibliography and NIST control matrix. Budget 12 months." |
| "No air-gap need" | "Today. What about your next contract? Your next acquisition?" |
| "Open source is free" | "The software is free. The security audit isn't. The compliance docs aren't. The support isn't." |

---

### 6. User Feedback & Continuous Improvement

**The Problem:** RAG systems deployed without feedback loops cannot improve over time.

**Our Solution:** Built-in feedback collection with RLHF-ready export:
- Thumbs up/down on every response
- Categorized negative feedback (hallucination, inaccurate citation, outdated info, wrong sources)
- Full RAG metadata captured for debugging
- Training data export for model fine-tuning
- Admin dashboard for quality metrics and issue triage

**Technical Features:**
- MongoDB persistence for all feedback
- Sector-scoped analytics (satisfaction rates by department)
- Hallucination report queue for high-priority review
- Export to RLHF training format (query/response/reward)
- Issue resolution tracking (open → in_progress → resolved)

**Compliance Value:**
- Audit trail of user feedback
- Evidence of continuous improvement process
- Documented issue resolution workflow

**Sales Line:**
> "Every thumbs down becomes training data. Your users teach the system to be better."

---

## Reference Documents

| Document | Location | Purpose |
|----------|----------|---------|
| Product Strategy | `docs/PRODUCT_LINE_STRATEGY.md` | Full edition breakdown |
| Compliance Matrix | `docs/COMPLIANCE_MATRIX.md` | NIST control mapping |
| Architecture | `docs/AdaptiveRAG_Implementation_Report.md` | Technical deep-dive |
| Development Guide | `CLAUDE.md` | Capability reference |
| README | `README.md` | Public-facing overview |

---

*For internal sales use. Do not distribute externally.*
