# Optimization Changes (Build Phase)

Date: 2026-01-22

This document explains the optimization-focused changes applied across the RAG pipeline, retrieval services, and local vector store. The goal is to reduce latency, control prompt growth, and improve throughput while keeping behavior stable.

> INTERNAL: Engineering change log (not customer-facing).

---

## 1) Shared execution infrastructure

**File:** `src/main/java/com/jreinhal/mercenary/config/RagPerformanceConfig.java`

**What changed**
- Added a shared `ragExecutor` and a dedicated `rerankerExecutor`.
- Thread pools are bounded, named, and use `CallerRunsPolicy` to avoid unbounded queue growth.
- Core threads can time out to reduce idle resource usage.

**Why**
- Multiple RAG services (HybridRAG, MegaRAG, HiFiRAG) were creating their own executors or running sequentially.
- A shared executor prevents thread explosion and improves parallel throughput under load.

**Knobs**
- `sentinel.performance.rag-core-threads`
- `sentinel.performance.rag-max-threads`
- `sentinel.performance.rag-queue-capacity`
- `sentinel.performance.reranker-threads`

---

## 2) HybridRAG parallelization + allocation reductions

**File:** `src/main/java/com/jreinhal/mercenary/rag/hybridrag/HybridRagService.java`

**What changed**
- Query variants now run semantic retrieval in parallel via `ragExecutor`.
- Replaced per-call creation of OCR substitution maps and stop-word sets with static constants.
- Added interruption handling on futures to avoid silent thread interrupt loss.

**Why**
- Sequential semantic searches add latency proportional to query variant count.
- Reusing static sets/maps avoids repeated allocations and GC churn.

**Behavioral impact**
- Retrieval logic is the same, but returns faster under multi-query expansion.

---

## 3) Query expansion caching

**File:** `src/main/java/com/jreinhal/mercenary/rag/hybridrag/QueryExpander.java`

**What changed**
- Added a Caffeine cache for expanded query variants.
- Cache is keyed on normalized query + requested count.

**Why**
- Query expansion is deterministic for synonym/reformulation, and LLM expansion is expensive.
- Cache reduces repeated work in conversational or repeated prompts.

**Knobs**
- `sentinel.hybridrag.query-expansion-cache-size`
- `sentinel.hybridrag.query-expansion-cache-ttl-seconds`

---

## 4) MegaRAG parallel retrieval + edge scoring improvements

**File:** `src/main/java/com/jreinhal/mercenary/rag/megarag/MegaRagService.java`

**What changed**
- Text and visual searches run in parallel via `ragExecutor`.
- Visual node edge lookup uses a set for O(1) membership checks.
- Added explicit `InterruptedException` handling (preserve interrupt state).

**Why**
- Parallelizing text/visual retrieval lowers wall-clock time for multimodal queries.
- Set membership avoids repeated linear scans of edges.

---

## 5) HiFiRAG cross-encoder caching and thread reuse

**File:** `src/main/java/com/jreinhal/mercenary/rag/hifirag/CrossEncoderReranker.java`

**What changed**
- Uses shared `rerankerExecutor` instead of creating a local pool.
- Added Caffeine cache for reranker scores (query + source + content hash).
- Converted repeated patterns and stop-words into static constants.

**Why**
- Reranking is expensive and often re-evaluates the same documents.
- Cache improves latency for repeated questions and follow-ups.

**Knobs**
- `sentinel.hifirag.reranker.cache-size`
- `sentinel.hifirag.reranker.cache-ttl-seconds`

---

## 6) InfiniGram client: bounded IO without extra threads

**File:** `src/main/java/com/jreinhal/mercenary/rag/qucorag/InfiniGramClient.java`

**What changed**
- Removed ad-hoc cached thread pool.
- Use `RestTemplateBuilder` with connect/read timeouts set from config.
- Calls are synchronous and bounded by HTTP timeouts.

**Why**
- Avoids unbounded thread creation and simplifies timeout behavior.

---

## 7) Prompt/context bounding and reduced allocations in controller

**File:** `src/main/java/com/jreinhal/mercenary/controller/MercenaryController.java`

**What changed**
- Added strict caps for:
  - max context size
  - max doc size
  - max visual size
  - max overview size
  - max docs per prompt
  - max visual docs per prompt
- Introduced shared constants for separators and stop words.
- Context building now truncates content deterministically.

**Why**
- Prevents prompt bloat and unbounded token growth.
- Makes latency and cost more predictable.

**Knobs**
- `sentinel.rag.max-context-chars`
- `sentinel.rag.max-doc-chars`
- `sentinel.rag.max-visual-chars`
- `sentinel.rag.max-overview-chars`
- `sentinel.rag.max-docs`
- `sentinel.rag.max-visual-docs`

---

## 8) LocalMongoVectorStore performance improvements

**File:** `src/main/java/com/jreinhal/mercenary/vector/LocalMongoVectorStore.java`

**What changed**
- Stores embedding norm at ingest time (one-time cost).
- Query cosine similarity uses precomputed norms.
- Added Mongo prefiltering for simple metadata filters (dept/type/partition_id/source/filename/mimeType).
- Filter parsing is now compiled once per request to reduce repeated parsing cost.
- Numeric value normalization applied to match int/long/double filters consistently.

**Why**
- Full collection scans are expensive; prefiltering reduces candidate size early.
- Norm caching saves per-doc sqrt and dot-product preparation.
- Filter parsing once avoids repeated string work for each doc.

**Behavioral impact**
- Same result set for supported filters; faster for large collections.

---

## 9) Config updates

**File:** `src/main/resources/application.yaml`

**What changed**
- Added performance thread pool settings under `sentinel.performance`.
- Added context/prompt limits under `sentinel.rag`.
- Added reranker and query-expander cache settings.
- Added vector store selection flags and aligned Ollama output caps.

**Why**
- Exposes tuning controls for throughput vs. latency trade-offs without code changes.

---

## 10) Tuned defaults (current)

These defaults were set to align with a 16‑logical‑CPU workstation and to reduce prompt bloat while keeping retrieval quality stable:

- `sentinel.performance.rag-core-threads: 8`
- `sentinel.performance.rag-max-threads: 16`
- `sentinel.performance.rag-queue-capacity: 400`
- `sentinel.performance.rag-future-timeout-seconds: 8`
- `sentinel.rag.max-context-chars: 12000`
- `sentinel.rag.max-doc-chars: 2000`
- `sentinel.rag.max-visual-chars: 1200`
- `sentinel.rag.max-overview-chars: 2400`
- `sentinel.rag.max-docs: 12`
- `sentinel.rag.max-visual-docs: 6`
- `sentinel.hifirag.reranker.cache-size: 2000` (TTL 900s)
- `sentinel.hybridrag.query-expansion-cache-size: 1500` (TTL 900s)

---

## 11) Load test / profiling

**Script:** `tools/load_test.ps1`

**What changed**
- Added a simple, repeatable load test for quick latency profiling.

**How to run**
```
powershell -ExecutionPolicy Bypass -File tools/load_test.ps1 -Url http://localhost:8080/api/health -Requests 200 -Concurrency 20 -TimeoutSec 5
```

**Current status**
- Successful run (2026‑01‑22) against `http://localhost:8080/api/health`:
  - Requests: 200, Concurrency: 20, Timeout: 5s
  - Avg: 131.38ms, P50: 129.12ms, P95: 148.56ms, P99: 165.26ms
- Successful run (2026‑01‑23) against `http://localhost:8080/api/health`:
  - Requests: 200, Concurrency: 20, Timeout: 5s
  - Avg: 108.98ms, P50: 108.05ms, P95: 123.37ms, P99: 131.63ms

---

## 12) Build/test fixes

**What changed**
- Fixed Apache Tika import in `SecureIngestionService` for embedded image extraction.
- Updated unit tests to align with new constructor dependencies and `@Value` defaults.

**Why**
- Restore compile/test stability after RAG pipeline changes.

---

## 13) Audit remediations (security + robustness)

**What changed**
- Added defense‑in‑depth department validation in HybridRAG, MegaRAG, and HiFi‑RAG before building filters.
- Added bounded timeouts for HybridRAG/MegaRAG futures to avoid thread stalls.
- Included department in reranker cache keys and doc IDs to reduce cross‑tenant collisions.
- Fixed InfiniGram query escape order and force‑disabled it in `govcloud` profile.
- Added OR‑aware prefilter construction in LocalMongoVectorStore (now supports AND/OR when all conditions are supported).
- Added trusted proxy enforcement for X‑Forwarded‑For and unified client IP resolution.
- Enabled LLM guardrails by default via config (still fail‑open when LLM unavailable).
- Enforced fail‑closed audit logging in govcloud profile at runtime.
- Removed `@CrossOrigin` from AuditController so audit endpoints inherit the global CORS policy.
- Hardened dev compose port bindings and removed dev auth defaults from Dockerfile.

**Why**
- Prevents untrusted department inputs from widening scope.
- Ensures retrieval futures do not block indefinitely.
- Hardens cache isolation and improves traceability.
- Enforces air‑gapped behavior for govcloud.
- Prevents proxy spoofing in rate limits/audits and reduces accidental insecure defaults.

---

## 14) Guardrail hardening + resiliency

**What changed**
- Centralized prompt injection regexes in `PromptInjectionPatterns` for consistent detection.
- Added a lightweight circuit breaker around the guardrail LLM classifier to avoid repeated failures.

**Why**
- Keeps injection detection consistent across call sites.
- Prevents LLM guardrails from repeatedly hammering an unstable downstream.

**Knobs**
- `app.guardrails.llm-circuit-breaker.enabled`
- `app.guardrails.llm-circuit-breaker.failure-threshold`
- `app.guardrails.llm-circuit-breaker.open-seconds`
- `app.guardrails.llm-circuit-breaker.half-open-max-calls`

---

## 15) Correlation ID tracing

**What changed**
- Added `CorrelationIdFilter` to emit/propagate `X-Correlation-Id` and MDC entries.
- Added a default log pattern override to include `%X{correlationId}` in log output.

**Why**
- Enables request-level tracing across logs, rate limiting, and audit events.

---

## 16) Test coverage extensions

**What changed**
- Added cache isolation tests for query expansion and reranker caches.
- Added a lightweight `/api/health` controller test.
- Updated controller test mocks to use `@MockitoBean` to avoid deprecated `@MockBean`.

**Why**
- Ensures tenant/dept-scoped caches do not cross-contaminate results.
- Adds baseline web layer coverage for the health endpoint.

---

## 17) Operational notes

**Gradle**
- Gradle wrapper was regenerated; `.\gradlew.bat` works.
- `.\gradlew.bat -q compileJava` and `.\gradlew.bat -q test` now succeed.
- The deprecated Spring Security header API and `RestTemplateBuilder` timeout methods were updated.
- Other deprecated/unchecked warnings remain; run with `-Xlint:deprecation` for details.

**Tuning suggestions (build phase)**
- Start with defaults. Increase `rag-max-threads` only after observing CPU utilization.
- Keep `max-context-chars` conservative to avoid unnecessary LLM load.
- Increase reranker cache TTL if you see repeated follow-up queries.

---

## Summary of modified files

- `src/main/java/com/jreinhal/mercenary/MercenaryApplication.java`
- `src/main/java/com/jreinhal/mercenary/config/RagPerformanceConfig.java` (new)
- `src/main/java/com/jreinhal/mercenary/config/SecureDocCacheConfig.java` (new)
- `src/main/java/com/jreinhal/mercenary/config/SecurityConfig.java`
- `src/main/java/com/jreinhal/mercenary/dto/EnhancedAskResponse.java` (new)
- `src/main/java/com/jreinhal/mercenary/security/ClientIpResolver.java` (new)
- `src/main/java/com/jreinhal/mercenary/rag/hybridrag/HybridRagService.java`
- `src/main/java/com/jreinhal/mercenary/rag/hybridrag/QueryExpander.java`
- `src/main/java/com/jreinhal/mercenary/rag/megarag/MegaRagService.java`
- `src/main/java/com/jreinhal/mercenary/rag/hifirag/CrossEncoderReranker.java`
- `src/main/java/com/jreinhal/mercenary/rag/hifirag/HiFiRagService.java`
- `src/main/java/com/jreinhal/mercenary/rag/qucorag/InfiniGramClient.java`
- `src/main/java/com/jreinhal/mercenary/controller/MercenaryController.java`
- `src/main/java/com/jreinhal/mercenary/service/RagOrchestrationService.java` (new)
- `src/main/java/com/jreinhal/mercenary/service/PiiRedactionService.java`
- `src/main/java/com/jreinhal/mercenary/service/SecureIngestionService.java`
- `src/main/java/com/jreinhal/mercenary/service/SecureIngestionException.java` (new)
- `src/main/java/com/jreinhal/mercenary/filter/RateLimitFilter.java`
- `src/main/java/com/jreinhal/mercenary/filter/PreAuthRateLimitFilter.java`
- `src/main/java/com/jreinhal/mercenary/service/AuditService.java`
- `src/main/java/com/jreinhal/mercenary/vector/LocalMongoVectorStore.java`
- `src/main/java/com/jreinhal/mercenary/security/PromptInjectionPatterns.java` (new)
- `src/main/java/com/jreinhal/mercenary/util/SimpleCircuitBreaker.java` (new)
- `src/main/java/com/jreinhal/mercenary/filter/CorrelationIdFilter.java` (new)
- `src/main/resources/application.yaml`
- `docker-compose.yml`
- `Dockerfile`
- `src/test/java/com/jreinhal/mercenary/controller/MercenaryControllerTest.java` (new)
- `src/test/java/com/jreinhal/mercenary/service/SectorIsolationTest.java`
- `src/test/java/com/jreinhal/mercenary/service/SecureIngestionServiceTest.java`
- `src/test/java/com/jreinhal/mercenary/service/PiiRedactionServiceTest.java`
- `src/test/java/com/jreinhal/mercenary/service/PromptGuardrailServiceTest.java`
- `src/test/java/com/jreinhal/mercenary/rag/hybridrag/QueryExpanderTest.java` (new)
- `src/test/java/com/jreinhal/mercenary/rag/hifirag/CrossEncoderRerankerTest.java` (new)
- `src/test/resources/test_docs/operational_test.txt`
- `src/test/resources/test_docs/operations_report_alpha.txt`
- `src/test/resources/test_docs/operations_report_beta.txt`
- `tools/load_test.ps1`
- `docs/engineering/optimization/OPTIMIZATION_CHANGES.md`

---

## 18) Cleanup pass (recommendations in progress)

**What changed**
- Replaced `MatchException` usage with `IllegalArgumentException` in routing/decomposition/PII code paths.
- Swapped PII redaction buffers to `StringBuilder`.
- Centralized stop-word sets in `StopWords` and reused across RAG helpers.
- Introduced `FilterExpressionBuilder` to standardize dept/type filter construction.

**Why**
- Removes misuse of internal match exceptions and clarifies error intent.
- Reduces duplication and makes filter/stop-word policy consistent.

**Additional**
- Added `GlobalExceptionHandler` to centralize 400/403/500 responses for common exception types.

---

## 19) RAG orchestration extraction (in progress)

**What changed**
- Added `RagOrchestrationService` to host `/ask` and `/ask/enhanced` logic.
- Added `SecureDocCacheConfig` to expose the secure in-memory cache as a shared bean.
- Updated `MercenaryController` to delegate `/ask` and `/ask/enhanced` and to read latency/query metrics from the orchestration service.
- Removed controller-only endpoints and web annotations from the orchestration service so it stays a pure service layer.
- Trimmed `/ask`-only helper methods and unused constants from `MercenaryController` now that orchestration lives in the service.

**Why**
- Shrinks the controller surface while keeping orchestration logic cohesive.
- Ensures the secure cache is shared between ingestion and retrieval paths.

---

## 20) Hardware alignment + load test follow‑up

**What changed**
- Verified host hardware (16 logical CPUs, ~32 GB RAM) and confirmed the current default thread/queue settings are aligned.
- Re-ran the health endpoint load test after refactors.

**Why**
- Ensures the current defaults remain a good fit for the actual dev machine.

---

## 21) Vector store selection + local fallback

**File:** `src/main/java/com/jreinhal/mercenary/MercenaryApplication.java`

**What changed**
- Auto-detects local Mongo URIs and prefers `LocalMongoVectorStore` for DEV/CAC or local Mongo.
- Added explicit overrides: `sentinel.vectorstore.force-local` and `sentinel.vectorstore.force-atlas`.
- Logs a warning if both overrides are set (local wins).

**Why**
- Prevents accidental Atlas vector store usage when running locally.
- Avoids `SearchNotEnabled` failures for dev/test when Atlas search indexes are unavailable.

**Knobs**
- `sentinel.vectorstore.force-local`
- `sentinel.vectorstore.force-atlas`

---

## 22) Ingestion hard‑fail on security violations

**File:** `src/main/java/com/jreinhal/mercenary/controller/MercenaryController.java`

**What changed**
- `SecurityException` from ingestion now returns `BLOCKED: ...`, logs a warning, and writes an audit denial.
- Only non‑security failures fall back to RAM‑only cache.

**Why**
- Prevents blocked file types from being cached or partially indexed.
- Keeps the security posture fail‑closed for ingest policy violations.

---

## 23) PII pattern refinements

**Files:** `src/main/java/com/jreinhal/mercenary/service/PiiRedactionService.java`, `src/test/java/com/jreinhal/mercenary/service/PiiRedactionServiceTest.java`

**What changed**
- Phone regex now requires 10‑digit patterns (area code) and avoids alphanumeric IDs.
- DOB pattern now supports ISO dates (`YYYY‑MM‑DD`).
- Medical ID pattern accepts dash‑separated IDs.
- Tests updated with ISO DOB, dashed MRN, and a false‑positive guard for ClinicalTrials IDs.

**Why**
- Reduces false positives while improving coverage of real‑world formats.

---

## 24) LLM options wired to configuration

**Files:** `src/main/java/com/jreinhal/mercenary/service/RagOrchestrationService.java`, `src/main/java/com/jreinhal/mercenary/controller/MercenaryController.java`

**What changed**
- LLM model, temperature, and `num-predict` now come from `spring.ai.ollama.chat.options.*`.
- Logs now reflect the configured values instead of hard‑coded defaults.

**Why**
- Enables tuning without code changes and keeps prompt caps consistent with config.

---

## 25) Added gov/ops test fixtures

**Files:** `src/test/resources/test_docs/operational_test.txt`, `src/test/resources/test_docs/operations_report_alpha.txt`, `src/test/resources/test_docs/operations_report_beta.txt`

**Why**
- Ensures gov/ops document tests have realistic fixtures for retrieval and redaction checks.
