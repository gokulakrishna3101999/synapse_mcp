# SynapseMCP — MCP + RAG Spring Boot Application

> **Project Plan** · Multi-tenant Retrieval-Augmented Generation platform with a Model Context Protocol (MCP) server, built on Spring Boot + Spring AI, backed by PostgreSQL/PGVector, and released as an open-source project.

---

## Status

> Live development progress is **not tracked in this file**. See `MEMORY.md` at the repo root for current phase, completed tasks, in-progress work, and issue/decision history. `plan.md` stays the static roadmap; `MEMORY.md` is the living record of execution against it.

---

## 1. Project Overview

SynapseMCP is a multi-tenant document intelligence platform. Tenants upload documents in a variety of formats, the system extracts and chunks the content, generates embeddings using a user-configured provider (OpenAI, Gemini, or open-source alternatives like Ollama; Anthropic is additionally supported for chat), stores them in PostgreSQL with PGVector, and answers questions using hybrid retrieval (BM25 keyword + vector similarity, merged via Reciprocal Rank Fusion). Once the RAG core is stable, an MCP server exposes the platform's capabilities as tools consumable by AI clients.

**Delivery model:** API + MCP only (no frontend UI in scope).

### Goals

- [ ] Production-grade, multi-tenant RAG backend with pluggable LLM/embedding providers
- [ ] Hybrid retrieval (BM25 + vector similarity + RRF) with measurably better relevance than either alone
- [ ] Asynchronous, observable, fault-tolerant ingestion pipeline
- [ ] MCP server exposing tenant management (`create_tenant`, `get_tenant`, `configure_model`), knowledge base management (`create_knowledge_base`, `update_knowledge_base`, `delete_knowledge_base`, `list_knowledge_bases`), retrieval (`search`, `ask`), and ingestion tools (`ingest`, `job_status`, `get_document_status`, `evaluate`)
- [ ] Open-source release with cross-platform setup documentation

### Non-Goals (initial release)

- Frontend / web UI
- External source connectors (GitHub, Notion, Confluence) — deferred to post-Phase-1 evolution
- Fine-tuning or training of models
- Real-time collaborative features

---

## 2. Technology Stack

| Layer | Technology | Notes |
|---|---|---|
| Language / Runtime | Java 21 (LTS) | Virtual threads available for I/O-heavy ingestion |
| Framework | Spring Boot 4.x (Spring Framework 7) | Web, Validation, Actuator |
| AI Integration | **Spring AI** | ChatClient, EmbeddingModel abstractions, provider auto-config |
| Database | PostgreSQL 17+ | Primary datastore |
| Vector Store | PGVector extension | Via Spring AI `PgVectorStore` or custom repository |
| Keyword Search | Apache Lucene (BM25) | Local index per tenant/KB |
| Cache / Rate Limit | Redis | Embedding cache (7-day TTL), OCR cache (24-hour TTL), per-tenant rate limits |
| Async Processing | Spring `@Async` + `ThreadPoolTaskExecutor` | Consider virtual-thread executor |
| MCP | Official MCP Java SDK | Phase 2 |
| Migrations | Flyway | Versioned schema migrations from day one |
| Build | Maven (wrapper `./mvnw` committed) | Java 21+ toolchain |
| Containerization | Docker / Podman | Multi-stage builds |
| Orchestration | Kubernetes | Helm chart or Kustomize |
| IaC | Terraform | Cloud resource provisioning |
| Observability | Structured JSON logs, Micrometer metrics, correlation IDs | OpenTelemetry-compatible |

### Supported File Formats (Phase 1 ingestion)

| File Type | Extensions |
|---|---|
| PDF | `.pdf` |
| Images | `.jpg`, `.jpeg`, `.png`, `.gif`, `.bmp`, `.tiff` |
| Word | `.doc`, `.docx` |
| Excel | `.xls`, `.xlsx` |
| PowerPoint | `.ppt`, `.pptx` |
| HTML | `.html`, `.htm` |
| Plain Text | `.txt`, `.md` |

Text extraction strategy: **format-dependent**. Text-oriented formats (plain text, Markdown, HTML, Word, Excel, PowerPoint) use Java libraries (Apache Tika / POI / jsoup). PDFs and images use **LLM-first extraction** (vision-capable model), with Java libraries (PDFBox / OCR) as the fallback extractor when the LLM path fails or is unavailable.

---

## 3. Development Environment & Commands

> These are the canonical commands. Agentic tools (Claude Code) should use them exactly as written; if a command changes, update it here in the same commit.

### Prerequisites

- Java 21+ (LTS), Git. **No Docker anywhere** — not for running the app, not for the test suite
- Native Postgres 17 + `pgvector` + Redis must be running before `./mvnw verify` (`*IntegrationTest`s run against them directly) — see Commands below
- No global Maven required — use the committed wrapper `./mvnw`

### Commands

| Purpose | Command |
|---|---|
| Start local dependencies (native Postgres/pgvector + Redis) | `brew services start postgresql@17 redis@6.2` (or `./scripts/setup-environment.sh` — also installs services and resets the `synapsemcp` database/Redis to empty every run, destructively by design — see §9 Decision Log) |
| Stop local dependencies | `brew services stop postgresql@17 redis@6.2` |
| Full build + all tests (the gate for checking off tasks) — needs native Postgres/Redis already running | `./mvnw clean verify` |
| Unit tests only | `./mvnw test` |
| Run the application (local profile) | `./mvnw spring-boot:run -Dspring-boot.run.profiles=local` |
| Format code | `./mvnw spotless:apply` |
| Dependency vulnerability scan | `./mvnw org.owasp:dependency-check-maven:check` |

### Testing setup

- **Unit tests** (`*Test`): no external dependencies, run in plain JVM
- **Integration tests** (`*IT`): run against a real, **isolated** `synapsemcp_test` Postgres database + Redis DB index 1 (`application-test.yaml`, `AbstractIntegrationTest`, since 2026-07-14 §9 Decision Log) — not the same database/index the app itself uses (`synapsemcp`/index 0); no ephemeral containers, no Docker, no data synced between the two; start native services first (`scripts/setup-environment.sh` or `brew services start`)
- Flyway migrations run automatically on application and integration-test startup; never modify an applied migration, always add a new versioned one

### Manual PostgreSQL Setup (native install)

> Local dev runs on a **natively installed** PostgreSQL 17 + `pgvector` + Redis (no Docker involved in running the app — see `CLAUDE.md`'s Commands section). `./scripts/setup-environment.sh`/`.ps1` automate everything below end-to-end for **two isolated databases** — `synapsemcp` (the app) and `synapsemcp_test` (the test suite only, since 2026-07-14 §9 Decision Log): the `synapsemcp` role is created idempotently, but both databases are reset destructively on every run (dropped and recreated empty, Redis flushed across all DB indexes — a dev-reset tool, not a preserve-my-data one), with the `vector` extension re-enabled each time in both, matching `application-local.yaml`/`application-test.yaml` respectively (see §1.1). No data is ever copied between the two databases. This manual guide is the step-by-step equivalent, useful for troubleshooting or a first-time setup without the script.

**Step 1: Log into PostgreSQL (OS-specific)**

- **macOS:** `psql -U postgres` (Homebrew installs without a password can also use `psql -d postgres`)
- **Linux (Ubuntu/Debian):** `sudo -u postgres psql` (default `postgres` user uses peer auth)
- **Windows:** `psql -U postgres` from Command Prompt, PowerShell, or the bundled "SQL Shell (psql)" (prompts for the password set at install time)

**Step 2: Execute the setup commands**

Once at the `postgres=#` prompt:

```sql
-- 1. Create the application user
CREATE ROLE synapsemcp WITH LOGIN PASSWORD 'synapsemcp' SUPERUSER;

-- 2. Create the database and assign ownership
CREATE DATABASE synapsemcp OWNER synapsemcp;

-- 3. List all databases to verify 'synapsemcp' was created
\l

-- 4. List all roles to verify 'synapsemcp' was created
\du

-- 5. Switch connection to the new 'synapsemcp' database
\c synapsemcp

-- 6. Enable the pgvector extension for vector storage
CREATE EXTENSION IF NOT EXISTS vector;

-- 7. List installed extensions to verify pgvector is active
\dx

-- 8. Exit
\q
```

> `SUPERUSER` is used here for local-dev convenience only — do not carry this into `dev`/`prod` profiles or shared environments; those should use a least-privilege role scoped to the `synapsemcp` database.

---

## 4. Conventions & Guardrails

> Rules for all contributors, human or agentic. These are non-negotiable unless changed via an entry in the Decision Log.

### Workflow

- One checkbox in this plan ≈ one commit-sized unit of work; a task may only be checked off after `./mvnw clean verify` passes
- Work phases strictly in order; within a phase, respect task order unless a task is independent
- After each session, append an entry to `MEMORY.md` (phase, task completed, decisions/fixes made, next task, blockers) — this file, not `plan.md`, is the resume point for agentic development
- Commit messages follow Conventional Commits (`feat:`, `fix:`, `test:`, `refactor:`, `docs:`, `chore:`), scoped by module, e.g. `feat(retrieval): add RRF merge`
- Branching: trunk-based; short-lived branches named `phase-<n>/<task-slug>`

### Code

- Packages live under `com.synapsemcp.<module>` per the structure in §1.1; test packages mirror source packages
- Persistence layering: JPA `@Entity` classes + Spring Data JPA repositories live in each domain package (e.g. `tenant/Tenant.java`) and are the only things that talk to the database; DTOs (REST/MCP request/response shapes) live in `common/` (shared/generic) or alongside their domain (domain-specific) and never touch the database directly — entity↔DTO mapping is manual (no MapStruct or similar) unless revisited via a Decision Log entry
- Every REST endpoint and MCP tool gets at least one integration test; every extractor and chunking strategy gets unit tests against fixture files in `src/test/resources/fixtures/`
- **Never weaken, skip, or delete tenant-isolation tests** — if one fails, the code is wrong, not the test
- All API errors use RFC 7807 problem-details; all endpoints are under `/api/v1`
- Configuration via `application-<profile>.yaml`; secrets only via environment variables — never committed, never logged
- No major-version dependency upgrades without a Decision Log entry

### Definition of task-done

1. Code compiles and `./mvnw clean verify` is green
2. New behavior is covered by tests at the appropriate level
3. Checkbox ticked in this file, corresponding entry appended to `MEMORY.md`
4. Any decision made along the way appended to the Decision Log

---

## 5. High-Level Architecture

```
                        ┌───────────────────────────────────┐
   AI Clients (MCP) ───▶│ MCP Server (Phase 2)              │
 (Auth: Basic, mcp_user)│ tenant · knowledgebase · search · │
                        │ ask · ingest · status · evaluate  │
                        └─────────────────┬─────────────────┘
                                          │
   REST Clients ───────▶┌─────────────────▼────────────┐
                        │  REST API Layer               │
                        │  (Auth: API keys, per-tenant) │
                        └──────────────┬───────────────┘
                                       │
        ┌──────────────────────────────┼──────────────────────────────┐
        ▼                              ▼                              ▼
┌───────────────┐          ┌────────────────────┐          ┌──────────────────┐
│ Ingestion     │          │ Retrieval Service  │          │ RAG / Chat       │
│ Pipeline      │          │ BM25 (Lucene) +    │          │ Service          │
│ (@Async)      │          │ Vector (PGVector)  │          │ (Spring AI       │
│ extract →     │          │ → RRF merge        │          │  ChatClient)     │
│ chunk → embed │          └─────────┬──────────┘          └────────┬─────────┘
└──────┬────────┘                    │                              │
       │                             ▼                              │
       │              ┌────────────────────────────┐                │
       └─────────────▶│ PostgreSQL + PGVector      │◀───────────────┘
                      │ (multi-tenant, namespaced) │
                      └────────────────────────────┘
                                     ▲
                      ┌──────────────┴─────────────┐
                      │ Redis                      │
                      │ embedding cache · OCR      │
                      │ cache · rate limits        │
                      └────────────────────────────┘
```

---

## Phase 1 — Core RAG Application

> **Note:** The detailed plan, schema definitions, and task checkboxes for the core RAG platform have been moved to a dedicated sub-plan.
> 
> 👉 **See [`rag_plan.md`](./rag_plan.md) for the authoritative Phase 1 tasks, DB schema, and architectural decisions.**

---

## Phase 2 — MCP Integration & API Management

> **Note:** The detailed plan and task checkboxes for this phase have been moved to a dedicated sub-plan.
> 
> 👉 **See [`mcp_plan.md`](./mcp_plan.md) for the authoritative MCP phase tasks and grooming decisions.**

## Phase 3 — Infrastructure, Observability & Open-Source Release

### 3.1 Containerization & Deployment

- [ ] Multi-stage Dockerfile (JDK build stage → JRE/distroless runtime); Podman-compatible
- [ ] Docker Compose for full local stack (app + Postgres/pgvector + Redis)
- [ ] Kubernetes manifests or Helm chart: Deployment, Service, ConfigMap, Secret, HPA, liveness/readiness probes (Actuator health groups)
- [ ] Lucene index persistence: PVC per pod or rebuild-on-start strategy — decide and document (stateless pods + rebuild from Postgres is simplest)
- [ ] Terraform modules: managed PostgreSQL, Redis, Kubernetes cluster, DNS/TLS
- [ ] CI/CD pipeline: build → test → image scan → publish → deploy (GitHub Actions)

### 3.2 Observability & Production Hardening

- [ ] Structured JSON logging (Logback + JSON encoder); correlation ID in every log line
- [ ] Micrometer metrics: request latency, ingestion stage durations, retrieval latency split (BM25 vs vector vs fusion), cache hit rates, provider call latency/errors, rate-limit rejections
- [ ] OpenTelemetry traces (optional but recommended) spanning REST/MCP → retrieval → provider calls
- [ ] Actuator hardened: health/info public, everything else authenticated
- [ ] Load test: concurrent ingestion + query mix; document throughput and bottlenecks
- [ ] Security pass: dependency scan, secrets never logged, upload size limits, content-type allowlist, SQL injection review on any dynamic search SQL

### 3.3 Open-Source Release

- [ ] Choose license (Apache-2.0 or MIT) and add `LICENSE`
- [ ] `README.md`: what/why, architecture diagram, quickstart (Docker Compose one-liner), configuration reference, MCP client setup examples
- [ ] Platform setup guides: Windows, Linux, macOS (including Ollama local setup path)
- [ ] `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, issue/PR templates
- [ ] Example corpus + demo script ("ingest these docs, ask these questions") for instant evaluation
- [ ] Versioning: SemVer; tag `v0.1.0` for first public release; changelog
- [ ] Publish container images (GHCR/Docker Hub)

### 3.4 Phase 3 Quality Gate (exit criteria)

- [ ] `docker compose up` gives a working system from a clean machine on all three OS platforms
- [ ] K8s deployment verified on at least one managed cluster
- [ ] Dashboards/metrics demonstrate the observability story end-to-end
- [ ] A newcomer can go from README to first successful `ask` in under 15 minutes

---

## 6. Post-Release Roadmap (out of scope for v0.1.0, tracked for later)

- External document sources: GitHub repositories, GitHub Wikis, Notion exports, Confluence exports (via the `DocumentSource` SPI from 1.3)
- Reranking stage (cross-encoder or LLM-as-reranker) after RRF
- Streaming answers (SSE) for the `ask` endpoint
- Webhook/callback notifications on job completion
- Schema-per-tenant isolation option for high-compliance tenants
- Evaluation dashboard with historical relevance trends

---

## 7. Key Risks & Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Lucene ↔ Postgres drift | Stale/missing search results | Write ordering rule + reconciliation/rebuild job (1.6) |
| Mixed embedding dimensions per tenant | Index/insert failures | Lock `embedding_dim` per KB at creation (1.2) |
| Async context loss (`tenant_id`, correlation ID) | Cross-tenant bugs, unusable logs | TaskDecorator propagation + guardrail tests (Stage 0, Stage 5) |
| Ollama latency/throughput | Ingestion timeouts | Per-provider timeout/retry/batch tuning (Stage 5c) |
| LLM-first extraction cost/latency (PDFs & images) | Slow/expensive ingestion of large docs | Page caps, batching, content-hash caching, library fallback (Stage 5a) |
| Long-running ingest over synchronous MCP | Client timeouts | Job-ID + `job_status` polling design (`mcp_plan.md`) |
| Scope creep into connectors/UI | Delayed core release | SPI-only in Phase 1; connectors post-release (§6) |

---

## 8. Definition of Done (project level)

- [ ] All three phase quality gates passed
- [ ] Open-source repository public with docs, license, and demo
- [ ] At least one real MCP client verified against the deployed server
- [ ] Evaluation harness shows hybrid retrieval superiority documented in the README

---

## 9. Decision Log

> Append-only. Every significant **architectural** decision gets a dated entry here so future sessions (human or agentic) inherit the reasoning instead of re-litigating it. Day-to-day implementation decisions and bug fixes made *during* development belong in `MEMORY.md`, not here — this log is for decisions that shape the plan itself.

| Date | Decision | Rationale |
|---|---|---|
| 2026-07-12 | Spring Boot 4.x (Spring Framework 7), PostgreSQL 17+ | Latest stable baselines chosen at project start |
| 2026-07-12 | Spring AI for LLM/embedding integration | First-class Spring Boot integration, provider abstraction out of the box |
| 2026-07-12 | Maven with committed wrapper | Team familiarity; single canonical build path for agentic tooling |
| 2026-07-12 | API + MCP only, no frontend UI | Scope control for v0.1.0 |
| 2026-07-12 | Shared schema + `tenant_id` column for multi-tenancy | Simplest correct starting point; schema-per-tenant documented as upgrade path |
| 2026-07-12 | `embedding_dim` locked per knowledge base | PGVector indexes require fixed dimension; avoids per-dimension table sprawl |
| 2026-07-12 | LLM-first extraction for PDFs/images, library fallback; library-only for text formats | Extraction quality on scanned/visual content; guardrails in §1.3 |
| 2026-07-12 | External source connectors (GitHub/Notion/Confluence) deferred post-release | SPI designed in Phase 1; implementations in §6 roadmap |
| 2026-07-12 | Chat providers: OpenAI, Anthropic, Gemini, Ollama; embedding providers: OpenAI, Gemini, Ollama | Anthropic added for chat quality; it has no embeddings API. Ollama kept as the zero-cost local path |
| 2026-07-12 | Spring Boot pinned to 4.1.0 (bumped from 4.0.7); Spring AI pinned to 2.0.0 GA | Verified via Maven Central that Spring AI 2.0.0 GA's starter/autoconfigure POMs require Spring Boot 4.1.0 exactly (transitively pin spring-boot-starter-webclient/restclient to 4.1.0); staying on 4.0.7 would mean building on a pre-GA Spring AI milestone |
| 2026-07-12 | `ai.chat.provider`/`ai.embedding.provider` are a platform-wide default/fallback only, implemented via Spring AI's real `spring.ai.model.chat`/`spring.ai.model.embedding` selector properties (`@ConditionalOnProperty`, one active provider at a time, defaults to `none`) | True per-tenant dynamic provider switching still requires the `model_configs`-driven factory in §1.5; a single global yaml/env selector cannot give different tenants different providers simultaneously |
| 2026-07-12 | Entity/DTO layering: JPA `@Entity` + Spring Data JPA repositories (per domain package) are the only DB access path; DTOs (`common/` or per-domain) are the REST/MCP boundary; entity↔DTO mapping is manual for now, no MapStruct | Standard layered architecture; manual mapping avoids a new dependency/annotation-processor at this project's current size — revisit (with a new Decision Log entry) if hand-written mapping boilerplate becomes a real problem |
| 2026-07-12 | Tenant & knowledge-base management (`create_tenant`, `get_tenant`, `configure_model`, `create_knowledge_base`, `update_knowledge_base`, `delete_knowledge_base`, `list_knowledge_bases`) gets real REST endpoints in Phase 1 §1.2, moved ahead of their originally MCP-only (Phase 2, §2.1) home | Lets the RAG app be built and tested standalone via REST without waiting for MCP; §2.1's MCP tools become thin adapters over the same service layer later, consistent with §2.3's existing exit criterion ("shared service layer, thin MCP adapter") |
| 2026-07-12 | Admin API-key bootstrap via a required `ADMIN_BOOTSTRAP_KEY` env var (hashed into an `ADMIN` `api_keys` row on first boot if none exists), not an auto-generated-and-logged key | Fits the project's existing "secrets via env vars only, never logged" convention; gives the operator full control over the actual credential value (useful for IaC/secret managers) rather than relying on capturing transient console output |
| 2026-07-12 | Client API keys are sent as `Authorization: Bearer <key>` (not a custom header) | Standard HTTP convention (matches Stripe/OpenAI-style APIs); works with standard HTTP client libraries' built-in bearer-auth support |
| 2026-07-12 | Discovered (not previously documented anywhere in the plan): Spring Boot 4.1's default/auto-configured JSON stack is **Jackson 3** (`tools.jackson.*` packages, groupId `tools.jackson.core`), not classic Jackson 2 (`com.fasterxml.jackson.*`) — both are present on the classpath (Jackson 2 pulled in transitively by other libraries), but only a `tools.jackson.databind.ObjectMapper` bean is auto-configured. Any code needing `@Autowired ObjectMapper`/`JsonNode` must import from `tools.jackson.databind`, not `com.fasterxml.jackson.databind` | First hit when `ApiKeyAuthenticationFilter` failed to autowire `com.fasterxml.jackson.databind.ObjectMapper` — no bean of that specific (legacy) type exists. Worth remembering for all future code/tests that touch JSON directly rather than through Spring MVC's message converters (which work regardless, since HttpMessageConvertersAutoConfiguration wires either Jackson version transparently for request/response bodies) |
| 2026-07-12 | `OfficeExtractor` uses POI's `ExtractorFactory.createExtractor(InputStream)` (one unified dispatch across doc/docx/xls/xlsx/ppt/pptx) rather than six format-specific code paths | Verified via `javap` that `ExtractorFactory` (in the base `poi` module) reflectively delegates to OOXML-specific extractors when `poi-ooxml` is present on the classpath (which it is) — far less code than branching per format, and every format shares the same `POITextExtractor.getText()` contract |
| 2026-07-12 | Office-format extractor unit tests generate their `.docx`/`.xlsx` fixtures at test-time via POI's own writer API (`XWPFDocument`, `XSSFWorkbook`), rather than checking in binary fixture files under `src/test/resources/fixtures/` | Binary Office files can't be meaningfully reviewed in a git diff and need external tooling to regenerate; generating them in test setup code is fully reproducible and the exact fixture content is visible as plain Java. Plain-text/Markdown/HTML fixtures *are* real checked-in files, since those formats are natively readable and match the project's established fixture convention |
| 2026-07-12 | `DocumentExtractor.extract()` takes the already-detected `mimeType` as a parameter (added retroactively) rather than each extractor re-detecting it | `OfficeExtractor`'s first draft called `new FileTypeDetector().detect(...)` internally, duplicating work `DocumentExtractionService` had already done and instantiating a collaborator manually instead of receiving it via DI; threading the known value through the interface avoids both problems for every current and future extractor |
| 2026-07-12 | Token counting for chunking uses an approximate ~4-characters-per-token heuristic (`TokenEstimator`), not a real BPE tokenizer library | User-confirmed choice: no embedding/chat provider is locked in yet (§1.5), so an exact provider-specific tokenizer would be premature; ~4 chars/token is OpenAI's own stated rule of thumb and close enough for chunk-sizing purposes. Revisit once a provider is fixed if exact context-window budgeting (§1.8) needs precision |
| 2026-07-12 | `OfficeExtractor` retroactively rebuilt to preserve Word heading styles and Excel row/table structure, ahead of building §1.4's chunking strategies | User-confirmed choice: Structure-aware and Table-aware chunking (as specified in §1.4) need structural signal the original flat-text `ExtractorFactory`-based extraction didn't preserve; built the prerequisite first rather than shipping an incomplete chunking layer |
| 2026-07-13 | `model_configs` gets a real REST endpoint now (`PUT`/`GET /api/v1/tenants/{tenantId}/model-config`), added to §1.2 alongside §1.5's embedding factory | User-confirmed choice: no endpoint existed anywhere in the plan to actually populate the `model_configs` row the per-tenant embedding factory reads from — without one, §1.5's factory would have nothing to resolve. Same "give it a real, testable surface" reasoning as the Session 13 tenant/KB endpoint addition |
| 2026-07-13 | Provider credentials (`model_configs.provider_credentials`) encrypted at rest via AES-GCM with an app-level key now, not deferred to a Phase 3 secrets manager | User-confirmed choice, resolving the open decision this checkbox had flagged since Session 11: real protection now (12-byte random IV per encryption, key from `RAGFORGE_CREDENTIALS_KEY`) rather than storing credentials in the clear in the interim; key rotation/KMS integration remains real Phase 3 work |
| 2026-07-13 | Per-tenant `EmbeddingModel` instances are constructed directly via each provider's Spring AI builder (`OpenAiEmbeddingModel.builder()`, etc.), not via Spring Boot's auto-configured `spring.ai.model.embedding` bean | The auto-configured bean is a single global instance selected once at boot (see §1.5's own note) — it can't vary per tenant/credentials, which is the entire point of this factory. Verified via `javap` against the actual Spring AI 2.0.0 jars (not assumed) that every provider's model class exposes a builder or public constructor taking plain values (API key, base URL, model name) independent of Spring's environment-property binding |
| 2026-07-13 | `EmbeddingModelFactory`'s per-tenant cache is invalidated via a Spring application event (`ModelConfigUpdatedEvent`, published by `ModelConfigService`), not a direct cross-package method call | Keeps `com.synapsemcp.tenant` from depending on `com.synapsemcp.embedding` (wrong direction per the package structure in §1.1) while still giving a config update — via the new §1.2 endpoint — an immediate effect, rather than requiring an app restart the way the global `spring.ai.model.embedding` selector does |
| 2026-07-13 | Built Stage 5's ingestion pipeline before Stage 6's retrieval endpoint, reversing the plan's own section order | User-confirmed choice: §1.6 (search/RRF) assumes chunks with embeddings already exist in Postgres and are indexed in Lucene — that's §1.7's job, which the plan lists *after* §1.6. Building retrieval first would mean either faking persisted data or duplicating the entity/pipeline work; building §1.7 first gives §1.6 real data to search when it's built next |
| 2026-07-13 | `chunks.embedding` requires the separate `org.hibernate.orm:hibernate-vector` Maven module (pinned to the exact `hibernate-core` version, `7.4.1.Final`) — not bundled with `hibernate-core` itself | Verified empirically via a throwaway spike test: without it, Hibernate's `SqlTypes.VECTOR` constant exists but has no registered PostgreSQL DDL/JDBC type mapping at all (`MappingException: no type mapping for SqlTypes code 10000`). `pgvector-java`'s own maintainers point to this exact module for Hibernate 6.4+/7.x rather than their own (now Hibernate-UserType-less) library |
| 2026-07-13 | `ChunkEntity.embedding` has no `@Array(length=N)` annotation | Verified empirically (second spike test): `@Array(length=N)` makes Hibernate generate a fixed-dimension `vector(N)` DDL type and Postgres then rejects any embedding whose length differs from N — incompatible with this project's per-KB variable `embedding_dim`. Omitting it works correctly because Flyway (not Hibernate `ddl-auto`) owns the `chunks` table's actual schema; Hibernate only needs to read/write the column, never generate its DDL |
| 2026-07-13 | Lucene index granularity: one index directory per **knowledge base**, not per tenant | A KB (not a tenant) is the natural scope of a single search request (`POST .../knowledgebase/{id}/search`, §1.6); every KB belongs to exactly one tenant, so per-KB indexes are also automatically tenant-isolated |
| 2026-07-13 | Upload idempotency: re-uploading identical content to the same knowledge base **short-circuits** to the existing document/job, rather than versioning | Simpler default matching plan.md's own "pick one, document it" framing; a new `documents.content_hash` column + `UNIQUE(tenant_id, knowledge_base_id, content_hash)` index (`V3` migration) gives a hard DB-level guarantee, not just an app-layer check-then-insert. Revisit if a real need for document version history emerges |
| 2026-07-13 | Ingestion pipeline executor: a bounded platform-thread `ThreadPoolTaskExecutor` (core 4 / max 8 / queue 100, `CallerRunsPolicy`), not a virtual-thread executor | Plan.md explicitly asks for a bounded queue with backpressure via a rejection policy — Spring's virtual-thread executors are designed to scale near-unbounded rather than apply backpressure, so they don't fit this specific requirement even though the pipeline's stages are I/O-bound (plan's own stated case for considering virtual threads) |
| 2026-07-13 | PGVector query results bind the query embedding as a `String` native-query parameter (`[v1,v2,...]`, cast via `CAST(:queryVector AS vector)`), not a typed `float[]`/`PGvector` JDBC binding | Simpler and avoids adding the `com.pgvector:pgvector` JDBC helper library just for one query direction (write-side already solved via `hibernate-vector`, §1.7); every value in the literal is a Java-formatted float, never concatenated user input, so there's no injection risk despite being string-built |
| 2026-07-13 | **`AbstractIntegrationTest`'s shared Testcontainers Postgres/Redis no longer use `@Testcontainers`/`@Container`** — plain static fields started in a static initializer (Testcontainers' own documented "singleton container" pattern) | Root-caused a reproducible full-suite failure: `@Testcontainers`/`@Container` manage container lifecycle **per test class** (including `stop()` in each class's `afterAll`), even for a field only *inherited* from a common base — so once enough `*IT` classes existed (5, after this session's `RelevanceSmokeTestIT`), each new class's cleanup was stopping the shared container out from under an earlier, still-cached Spring context's live connection pool ("connection has been closed" / pool-exhaustion timeouts). Confirmed via logs: 5 separate `pgvector` containers were being created per `./mvnw verify` run before the fix, 1 after. This was a latent, pre-existing risk in every prior session's test setup — it simply hadn't been tripped until the suite reached this size |
| 2026-07-13 | Per-tenant `ChatModel` instances (§1.8) are built with connection-level options only (API key/base URL/timeout/retries); the model name is attached per-call via a generic `ChatOptions.builder().model(name)` on each `Prompt`, not baked into the provider-specific options at construction time | Verified via `javap` across all four providers (OpenAI, Anthropic, Ollama, Google GenAI): none of their own `*ChatOptions` builders accept a plain-string model name — each only exposes a closed, provider-specific enum type (e.g. `OllamaModel`, `com.anthropic.models.messages.Model`) unsuitable for tenant-configured arbitrary model names. The generic `ChatOptions` interface's own builder does accept a `String`, and every provider's `ChatModel.call(Prompt)` reads the per-request `ChatOptions` off the `Prompt` — the officially-intended mechanism for exactly this |
| 2026-07-13 | RAG answering calls the lower-level `ChatModel.call(Prompt)` directly rather than wrapping it in Spring AI's `ChatClient` fluent API | `ChatClient` is a convenience builder over the same `ChatModel`; since per-tenant model resolution already requires custom `Prompt`/`ChatOptions` assembly (§1.8's own decision above), calling `ChatModel` directly was simpler than also introducing `ChatClient` for no added capability in this case |
| 2026-07-13 | Image OCR fallback (§1.3) uses `io.github.mymonstercat:rapidocr-java` (pure-JVM, ONNX-based PP-OCRv4), not `tess4j`/Tesseract | User-confirmed choice, redirected from an initial tess4j proposal: Tesseract has no bundled native binaries for macOS/Linux (`brew install tesseract` required, Windows-only bundled), a real deployment-portability risk. RapidOCR has no first-party Java binding, but this dormant third-party port was verified via a real spike test (English single-word, multi-line, and punctuation-heavy invoice-style text all correctly recognized) before adopting — chosen over standing up RapidOCR's official Python/Docker service over HTTP to avoid a second runtime dependency |
| 2026-07-13 | `DocumentExtractor.extract()` gained a `UUID tenantId` parameter | LLM-first extractors (`PdfExtractor`/`ImageExtractor`) need it to resolve the tenant's configured chat model via `ChatModelFactory`; `IngestionPipelineService.run()` already had `tenantId` available, so threading it through the existing SPI was simpler than introducing a new mechanism. Ignored by the library-only extractors (text/HTML/Office) |
| 2026-07-13 | LLM-first extraction resilience: any exception *or* blank/empty LLM result falls back to the library path (PDFBox/RapidOCR), plus a hard page-count cap (`MAX_LLM_PAGES = 30` in `PdfExtractor`) that skips the LLM path entirely for larger PDFs | Cost/latency guardrail from §1.3; verified end-to-end that a tenant with no `model_configs` row (the common case for freshly-created test/trial tenants) deterministically and silently falls back rather than failing ingestion |
| 2026-07-13 | Integration-test suffix renamed project-wide from Failsafe's default `*IT` to `*IntegrationTest` | User-directed convention change. Required explicit `<excludes>**/*IntegrationTest.java</excludes>` on `maven-surefire-plugin` (its own defaults would otherwise also match `*IntegrationTest.java`, since it ends in `Test.java`) and explicit `<includes>**/*IntegrationTest.java</includes>` on `maven-failsafe-plugin` (whose defaults don't recognize this suffix at all) — a rename alone was not sufficient |
| 2026-07-13 | JaCoCo wired with three separate report executions — unit-only (`jacoco.exec`), integration-only (`jacoco-it.exec`), and a merged combined report (`jacoco-merged.exec`, via `jacoco:merge`) — rather than a single report | §1.9's "≥70% coverage" gate is measured against real combined usage: most services/controllers in this codebase are exercised by both `*Test` and `*IntegrationTest` suites, so either report alone understates true coverage. The merged report is what §1.9's checkbox coverage numbers are taken from |
| 2026-07-13 | `com.google.genai:google-genai` declared as an explicit `pom.xml` dependency, pinned to the version already resolved transitively via `spring-ai-google-genai-embedding` | `ChatModelFactory`/`EmbeddingModelFactory` import `com.google.genai.Client`/`HttpOptions`/`HttpRetryOptions` directly — code that imports a library's classes directly should declare it explicitly rather than ride on another artifact's transitive dependency, which could change or disappear independent of this code |
| 2026-07-13 | `ChunkEntity.content` and `IngestionJob.errorDetail` given explicit `@Column(columnDefinition = "TEXT")` | Bug found via the new 100-page-PDF fixture test (the first test to ever produce chunk content over 255 characters): both fields previously had no explicit length, so Hibernate's implicit `varchar(255)` default silently narrowed the real Flyway-owned `TEXT` columns under `ddl-auto: update` (active in the `local` profile) — breaking persistence for any chunk over 255 characters in any environment using that profile. Completely invisible until this session because every prior test used short fixture content |
| 2026-07-13 | **Reverses the 2026-07-12 admin-bootstrap decision above**: removed the `ADMIN`/`TENANT` API-key tier entirely. `POST /api/v1/tenants` is now fully open/unauthenticated (`ApiKeyAuthenticationFilter` bypasses it by exact method+path match, alongside `/actuator/*`); `ApiKeyType` enum, `AdminBootstrapRunner`, and the `api_keys.key_type` column/`api_keys_tenant_scope_chk` constraint are all deleted (`V5__remove_admin_key_tier.sql`, which also `DELETE`s any existing ADMIN row before setting `tenant_id NOT NULL`, since an already-booted environment would otherwise violate that constraint) | User-confirmed choice: the project's goal shifted to being freely self-hostable/public — anyone should be able to create their own tenant with no gate, since tenant-to-tenant isolation is enforced entirely by `tenant_id` scoping and is completely independent of who is allowed to create a tenant. The `GET /api/v1/tenants/{id}` admin-can-fetch-any-tenant bypass is gone too as a direct consequence — there is no longer any privileged caller, by design |
| 2026-07-13 | **Reverses the 2026-07-13 credential-encryption decision above**: removed `CredentialEncryption` (AES-GCM). `model_configs.provider_credentials` now stores plaintext JSON (`TEXT`, via `V6__model_configs_credentials_plaintext.sql`, which drops and recreates the column rather than casting — any existing row is real ciphertext, not valid UTF-8) instead of encrypted bytes | User-confirmed choice, explicitly acknowledging the tradeoff: tenants' real third-party provider keys (OpenAI/Anthropic/etc.) are now readable by anyone with DB access. Accepted knowingly in exchange for removing `RAGFORGE_CREDENTIALS_KEY` as a mandatory boot-time secret, consistent with the same public/self-hostable-by-anyone goal above |
| 2026-07-13 | Added `TenantCreationRateLimitFilter`: a hand-rolled per-IP fixed-window counter on `StringRedisTemplate` (no new dependency), limiting `POST /api/v1/tenants` to `synapsemcp.rate-limit.tenant-creation.limit` (default 5) per `...window-seconds` (default 3600), keyed `rate_limit:tenant-create:{request.getRemoteAddr()}` — deliberately not `X-Forwarded-For`, which a client could spoof to defeat the limit. Fails **open** (allows the request, logs a warning) if Redis is unreachable | Direct consequence of the admin-tier removal above: the endpoint has zero auth gating now, so an unbounded-signup abuse vector needed at least a basic guard before shipping. Fail-open here is the opposite of the fail-closed posture planned for §2.2's future per-tenant/provider cost-control limiter — blocking the one public onboarding endpoint over a transient Redis blip would be worse than a temporary unlimited-signup window; §2.2's limiter protects real provider spend instead, where fail-closed is the safer default. Left `Bucket4j` undecided/unadopted for this narrow one-endpoint guard, so §2.2 remains free to choose its own library for the larger, separate rate-limiting need |
| 2026-07-13 | Removed Docker Compose as a local-dev backend entirely (`docker-compose.yml` deleted); local dev now runs exclusively on natively installed Postgres 17 + `pgvector` + Redis, automated by rewritten `scripts/setup-environment.sh`/`.ps1` (installs via Homebrew/apt if missing, starts services, idempotently creates the `synapsemcp` role/database/`vector` extension — no longer pulls images or runs a standalone Flyway CLI container, since Flyway is already an embedded main-scope dependency that migrates automatically on `spring-boot:run`) | User-directed: explicit goal of avoiding Docker for running the app locally. **Testcontainers is unaffected and unchanged** — `./mvnw clean verify`'s `*IntegrationTest` suite still needs a reachable Docker daemon for its own ephemeral containers, a separate concern from how the app itself is run day-to-day. Phase 3's `§3.1` production containerization roadmap (Dockerfile, container image publishing) is also unaffected — that is a future deployment concern, distinct from this local-dev backend |
| 2026-07-14 | `DocumentChunkingService`'s `SINGLE_CHUNK_TOKEN_THRESHOLD` and `FixedSizeChunkingStrategy`'s window size/overlap ratio changed from flat constants to a 3-tier dynamic scale based on the document's total estimated tokens: `<1,000` tokens → 256-token window / 10% overlap; `1,000–50,000` → 512-token window / 15% overlap (today's prior default, unchanged); `>50,000` → 1,024-token window / 20% overlap. The single-chunk threshold is now derived from whichever window size was selected (`windowTokens × 1.2`) instead of an unrelated flat `2000` | User-reported bug: a short (~4-page, ~750-1000 token) PDF was indexed as a single giant chunk, so hybrid search returned the same whole-document chunk for every query regardless of relevance — root cause was the old flat threshold (2000 tokens) being disconnected from the window size (512 tokens) it was meant to represent, letting a document dodge chunking entirely at ~4x the size of the window it should have been split into. User explicitly chose token-count-only scaling over also threading page count through (`ExtractionResult`/`DocumentExtractor` SPI have no page-count field today, and page count is only real data for PDFs — meaningless/fake for images, Office docs, HTML, and plain text). Exposed a latent test fragility while fixing this: `IngestionEndToEndIntegrationTest`'s 100-page-PDF test mocked `EmbeddingService.embed()` to always return exactly one embedding regardless of input size, which only "worked" because that fixture's actual (sparse) text content happened to fall under the old flat threshold; fixed to mock one embedding per input text (matching the pattern already used in `AllSupportedFormatsIngestionIntegrationTest`), across all four `embed()` stubs in that file for consistency |
| 2026-07-14 | **Extends the 2026-07-13 Docker-removal decision above to the test suite**: removed Testcontainers entirely. `AbstractIntegrationTest` no longer starts ephemeral Postgres/Redis containers — all 5 Testcontainers `pom.xml` dependencies (`spring-boot-testcontainers`, `testcontainers`, `testcontainers-junit-jupiter`, `testcontainers-postgresql`, `com.redis:testcontainers-redis`) removed. `*IntegrationTest`s now run against the same real native Postgres/Redis used to run the app; this works with zero extra wiring because `spring.profiles.default: local` (`application.yaml`) means a plain `@SpringBootTest` with no active profile already falls back to `application-local.yaml`'s connection details, which is exactly the native services' config | User-directed, after initially scoping the Docker removal (2026-07-13) to exclude Testcontainers specifically because of the tradeoff (loses ephemeral per-run isolation) — user later asked for it anyway. Verified the tradeoff is low-risk here: grepped the whole test suite for unscoped `findAll()`/`count()` assertions that would break against a persistent, shared database — found none; every test scopes its own queries by a freshly-created tenant/document/KB id, so sharing one real database across runs is safe, just means the database accumulates rows over repeated `./mvnw verify` runs (mitigated by `scripts/setup-environment.sh`'s destructive reset, 2026-07-13 above, if a clean slate is wanted) rather than each run getting a truly fresh throwaway database. `./mvnw verify` now requires native Postgres/Redis already running (a change to the previous "self-contained, no manually started services" guarantee) |
| 2026-07-14 | `*IntegrationTest`s now run against an **isolated** `synapsemcp_test` Postgres database + Redis DB index 1, not the same `synapsemcp`/index-0 the app uses — new `application-test.yaml` (`src/test/resources`, gitignored-jar-safe since it's test-scope-only), `AbstractIntegrationTest` activates it via `@ActiveProfiles("test")`. Both `scripts/setup-environment.sh`/`.ps1` extended to create/reset `synapsemcp_test` alongside `synapsemcp` (same destructive-reset treatment — drop/recreate empty, `vector` extension enabled — for consistency, since resetting it loses nothing tests need to keep). **Explicitly did not implement a data-sync mechanism from `synapsemcp`/Redis index 0 into the test instances**, despite that being requested — user confirmed after being shown the tradeoffs (would copy tenants' real plaintext provider API keys, §9 2026-07-13 credential-plaintext entry above, into test infra; would make tests non-deterministic based on sync timing; this project has zero existing scheduler/cron infrastructure to build it on) that an isolated-but-empty test database, with tests creating their own fixtures exactly as they already do, fully satisfies the actual need | User-requested isolated test databases (originally specified as "ragforce_test" — confirmed a typo for "synapsemcp_test" before implementing) to stop test runs from touching real dev data. Verified for real: ran a live `*IntegrationTest`, confirmed via its Flyway log that it connected to `jdbc:postgresql://localhost:5432/synapsemcp_test` and replayed all 6 migrations into a genuinely `<< Empty Schema >>`, and confirmed via `redis-cli -n 1 keys '*'` vs `-n 0 keys '*'` that its rate-limiter key landed only in the test index while the app's index stayed empty. Full suite re-run afterward: 113 `*Test` + 33 `*IntegrationTest` = 146 tests, 0 failures, unchanged |
