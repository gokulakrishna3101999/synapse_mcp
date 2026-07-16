# SynapseMCP — RAG App Plan (Finalized)

> The authoritative, groomed plan for building **the core RAG application** of SynapseMCP.
> Derived from the master `PLAN.md` and the original draft `RAG_APP_SUBPLAN.md`.
> All previously open `⟶ To groom` items are now **resolved** — decisions are recorded inline as **`✅ Decision`** blocks.

---

## Table of Contents

1. [Document Relationships](#1-document-relationships)
2. [Scope](#2-scope)
3. [The Flow](#3-the-flow)
4. [Implementation Overview](#4-implementation-overview)
5. [Stage 0 — Foundations](#stage-0--foundations)
6. [Stage 0.5 — Data Model / Schema](#stage-05--data-model--schema)
7. [Stage 1 — Create Tenant](#stage-1--create-tenant)
8. [Stage 2 — Configure Model Config](#stage-2--configure-model-config)
9. [Stage 3 — Create Knowledge Base](#stage-3--create-knowledge-base)
10. [Stage 4 — Upload Document](#stage-4--upload-document)
11. [Stage 5 — Async Ingestion Pipeline](#stage-5--async-ingestion-pipeline)
12. [Stage 6 — Search & Answer](#stage-6--search--answer)
13. [Cross-Cutting Concerns](#cross-cutting-concerns)
14. [Build Order (Milestones)](#build-order-milestones)
15. [Acceptance Criteria](#acceptance-criteria)
16. [Resolved Grooming Decisions Summary](#resolved-grooming-decisions-summary)

---

## 1. Document Relationships

| File | Role |
|---|---|
| `plan.md` | Master roadmap — all three phases (RAG core, MCP, infra/OSS). The full picture. |
| `rag_plan.md` (this file) | The RAG app slice only, sequenced along the user-facing flow. The thing you build first. All grooming decisions finalized. |
| `implementation/memory.md` | Living execution log (current phase, completed tasks, decisions/fixes). Not a roadmap. |

This plan **does not restate** shared conventions (RFC 7807 errors, correlation IDs, `./mvnw clean verify` as the done-gate, Conventional Commits). Those live in `PLAN.md` §4 and `CLAUDE.md` and apply here unchanged. (Note: Package layout is defined in Stage 0).

---

## 2. Scope

### In Scope — The RAG App (the 6-step flow)

- Tenant provisioning
- Model config (Base64-encoded credential storage)
- Knowledge-base CRUD (create, list, update, delete)
- Async document ingestion (extract → chunk → embed → store → index)
- Hybrid retrieval with ANN-optimized vector search
- Grounded answer generation with reranking, streaming (SSE), and multi-language support
- Basic ingestion metrics (jobs by state, failure counts)
- Lucene ↔ Postgres reconciliation cron
- Basic relevance-evaluation endpoint
- SPI-only external connector interfaces (concrete implementations deferred until core RAG is fully working)

### Out of Scope (deferred to master plan phases)

- MCP server + tools (See `mcp_plan.md` Stage 2)
- Per-tenant rate limiting / Redis rate limits as a dedicated concern (See `mcp_plan.md` Stage 3) — *the ingestion-side embedding/OCR caches stay in scope*
- Containerization, K8s, Terraform, CI/CD (Phase 3.1–3.2) — and note the RAG app uses **no Docker/Compose/Testcontainers for local dev or tests either** (native services only; see Stage 0)
- Open-source release chores: license, README, contribution docs (Phase 3.3)

---

## 3. The Flow

```
  [1] Create Tenant
        │
        ▼
  [2] Configure Model Config          (chat model + embedding model + credentials)
        │
        ▼
  [3] Create Knowledge Base           (for the tenant; embedding_dim auto-derived here)
        │
        ▼
  [4] Upload Document                 (for the tenant, using knowledge_base_id)
        │
        ▼
  [5] ASYNC: chunk generation + store in DB
        │   (upload returns immediately; extract → chunk → embed → persist → index)
        │
        ▼
  [6] Question asked by the consumer
        │
        ├── get the relevant chunk details for the question (ANN-optimized + reranking)
        │
        └──▶ from the chunks, generate an answer in the user's language (streaming SSE)
```

Steps [1]–[3] are **setup**, [4]–[5] are **ingestion**, [6] is **query/answer**. The plan is sequenced the same way.

---

## 4. Implementation Overview

Distilled from the codebase-exploration summary. Each step maps to the stage that owns it.

| # | Step | Detail | Owning Stage |
|---|---|---|---|
| 1 | Upload documents | 7 file-type categories (PDF, Word, Excel, PowerPoint, HTML, Text, Images) | Stage 4 |
| 2 | Extract text | Library-based, with **LLM-first + library fallback** for PDF/images | Stage 5a |
| 3 | Split into chunks | Fixed-size **or** structure-aware, depending on source | Stage 5b |
| 4 | Generate embeddings | OpenAI or Gemini, provider-selectable (Ollama also supported — see Stage 2) | Stage 5c + Stage 2 |
| 5 | Store | PostgreSQL + pgvector | Stage 5d |
| 6 | Search | Hybrid retrieval: BM25 keyword + ANN vector similarity, merged by RRF, with reranking | Stage 6a |
| 7 | Answer | RAG — chat model augmented by the retrieved chunks (streaming SSE, multi-language) | Stage 6b |
| 8 | Async job tracking | State transitions `PENDING → INDEXING → READY / FAILED` | Stage 5 |
| 9 | Cache | Embeddings in Redis (7-day TTL), OCR results in Redis (24-hour TTL) | Stage 5a / 5c |
| 10 | Multi-tenant isolation | API keys + namespace separation on every read/write | Stage 0 + cross-cutting |
| 11 | Ingestion metrics | Jobs by state, failure counts | Stage 5 |
| 12 | Reconciliation | Lucene ↔ Postgres drift detection cron | Stage 5d |
| 13 | Evaluation | Basic relevance-evaluation REST endpoint | Stage 6c |
| 14 | *(SPI-only)* External ingest | GitHub repos, Notion / Confluence ZIP exports | SPI interfaces only |

### Supported Document Sources

**Direct upload — in scope (the 7 categories):**

| File Type | Extensions |
|---|---|
| PDF | `.pdf` |
| Images | `.jpg`, `.jpeg`, `.png`, `.gif`, `.bmp`, `.tiff` |
| Word | `.doc`, `.docx` |
| Excel | `.xls`, `.xlsx` |
| PowerPoint | `.ppt`, `.pptx` |
| HTML | `.html`, `.htm` |
| Plain Text | `.txt`, `.md` |

**External sources — SPI-only:** GitHub repositories and Notion / Confluence ZIP exports appear in the exploration summary as document sources. In this RAG-app plan they are **SPI-only**: the `DocumentSource` / `SourceDocument` interfaces exist so these plug in later **without touching the pipeline core**, but no concrete connector is implemented here (matches `PLAN.md` §1.3 + §6). The extractors and chunking in Stage 5 are what any future source feeds into.

> **✅ Decision (Grooming #13):** External connectors remain SPI-only during this milestone. Concrete implementations (e.g., GitHub repo source) are deferred until the basic document upload RAG flow is fully complete and working properly. The interfaces are built now; the implementations come after.

---

# Stage 0 — Foundations (Prerequisite)

Before any flow step works, the shared scaffolding must exist. This is `PLAN.md` §1.1–§1.2 and is treated as a prerequisite, not re-planned here:

- Spring Boot 4 / Java 21 project, Maven wrapper, profiles (`local`/`dev`/`prod`).
- Hibernate-`ddl-auto`-owned schema, generated from JPA `@Entity` classes: `tenants`, `api_keys`, `model_configs`, `knowledge_bases`, `documents`, `chunks` (pgvector column), `ingestion_jobs` — full column-level definitions in **Stage 0.5** below.
- Multi-tenancy: **shared schema + `tenant_id` column + enforced filtering**; `tenant_id` on every tenant-scoped table with a composite index.
- API-key auth filter (SHA-256 at rest) → request-scoped `TenantContext`.
- **`TenantContext` + correlation-ID propagation into `@Async` executors** via `TaskDecorator` — the single easiest-to-miss, highest-blast-radius piece; keep its guardrail tests.

### Tenant Isolation Testing Strategy

> **✅ Decision (Grooming #1):** Repository-level tenant-isolation guardrail tests are **folded into each stage** — each stage adds its own isolation tests as it's built, rather than building them all upfront as a Stage-0 prerequisite. Every stage's "Done when" criteria include isolation verification specific to that stage's data.

### No Docker — Native Services Only

> **No Docker anywhere in the RAG app.** Local dev **and** the test suite run on **natively installed PostgreSQL 17 + pgvector + Redis** — never Docker, Docker Compose, Podman, or Testcontainers (matches `PLAN.md` §3 / §9 decisions). No container runtime is a dependency for building, running, or testing this app. Services are started natively (`brew services start …` or `scripts/setup-environment.sh` / `.ps1`); integration tests hit an isolated native `synapsemcp_test` database, not an ephemeral container. Redis is shared with the app (same database, index 0) and isolated by key prefix instead (`synapsemcp:*` vs `synapsemcp_test:*` — `plan.md` §9, 2026-07-16, `com.synapsemcp.common.RedisKeyPrefix`). Production containerization is a separate, out-of-scope Phase 3 concern and must not leak into this plan.

### Startup Behavior — Bootstrap, Then Standard Fail-Fast

> **✅ Decision (`plan.md` §9, 2026-07-16 — supersedes the earlier zero-DB-startup design):** The app does **not** attempt to stay up with no DB/schema present. It runs a bootstrap step (below) to provision what it can automatically, then follows standard Spring Boot fail-fast behavior for everything else: if Postgres is unreachable, or bootstrap/`ddl-auto`/ANN-index setup fails, the application **does not finish starting**. This deliberately drops the previous design (HTTP listener up even with zero DB present, custom non-blocking datasource config, a `SchemaReadinessHealthIndicator` distinct from liveness, self-healing retry loop) — that design existed specifically because Flyway ran decoupled from Spring context startup, in a separately-scheduled retrying runner. Hibernate's `ddl-auto` has no equivalent decoupled/retryable execution model without significant extra engineering (e.g. lazy JPA initialization); standard fail-fast was chosen deliberately over rebuilding that resilience layer.
>
> - **Bootstrap runs early, ahead of the app's own `DataSource`/`EntityManagerFactory` beans.** `DatabaseBootstrapRunner` (`local`/`dev` only — see Stage 0.5) opens a plain JDBC connection to Postgres's `postgres` maintenance database and idempotently creates the `synapsemcp` role/database and enables the `vector` extension, before Spring's normal JPA autoconfiguration proceeds.
> - **Then standard JPA/Hibernate startup takes over.** HikariCP connects normally; Hibernate `ddl-auto` (`update`/`create-drop`/`validate` per profile, Stage 0.5) creates/validates tables from the `@Entity` classes as part of ordinary `EntityManagerFactory` bootstrap. If this fails, the app fails to start — no custom recovery path.
> - **ANN indexes/`CHECK` constraints are added right after, still during startup.** `AnnIndexBootstrapRunner` runs once Hibernate's schema step completes, executing the raw SQL `ddl-auto` can't express (HNSW indexes, `CHECK` constraints) via idempotent `IF NOT EXISTS`/catch-and-ignore statements — cheap on every run, not just the first.
> - **Health/readiness is standard Actuator.** No custom readiness/liveness split — `/actuator/health` reflects real DB connectivity via the standard Spring Boot `DataSource` health indicator, since by the time the app is accepting traffic, the DB is already known to be reachable and schema-ready.
> - **This does not weaken tenant isolation** — unchanged from before, enforced at the application layer regardless of how the schema was created.

### Runtime Timeouts — Redis & Postgres (found by testing real outages, not mocks)

> **✅ Decision (`plan.md` §9, 2026-07-16):** Both Spring Data Redis and HikariCP ship with default timeouts far too long for this app's own stated resilience goals, discovered by actually stopping each service against a running instance rather than trusting a mocked exception path:
>
> - **`spring.data.redis.connect-timeout` / `timeout`: `1s` each** (env-var-overridable via `REDIS_CONNECT_TIMEOUT`/`REDIS_TIMEOUT`, every profile). Lettuce's own unconfigured default command timeout is **60 seconds** — with it, `TenantCreationRateLimitFilter`'s "fails open if Redis is down" (Stage 1) was true in principle but took a full minute per request in practice, which *is* blocking the endpoint, the exact outcome fail-open exists to prevent. Verified live: request now returns `201` in ~0.1s with Redis stopped, not 60s.
> - **`spring.datasource.hikari.connection-timeout`: `3000` (ms)** (env-var-overridable via `DB_CONNECTION_TIMEOUT`, every profile). HikariCP's own unconfigured default is **30 seconds**. Unlike Redis, Postgres going unreachable *mid-request* (as opposed to at startup, which Stage 0's fail-fast already covers) has no fail-open story — it's a genuinely critical dependency — but it must still fail *fast* and with the right status code, not hang 30s and return a generic `500`. `ApiExceptionHandler` maps **two** distinct, non-overlapping exception hierarchies to `503 Service Unavailable` / `"Database temporarily unavailable"`: `org.springframework.transaction.CannotCreateTransactionException` (an outer `@Transactional` boundary failing to open its transaction at all — the shape thrown by, e.g., `TenantService.createTenant()`) and `org.springframework.dao.DataAccessResourceFailureException` (Hibernate's own `JDBCConnectionException`, translated — the shape thrown by a bare repository call's own connection acquisition, e.g. `ApiKeyRepository.findTenantIdByKeyHash(...)`). Both are safe to catch specifically: neither ever fires for a real data-level error like a constraint violation, which can only happen after a connection is already obtained. Verified live: request now returns `503` in ~3s with Postgres stopped mid-flight, not a 30s hang into `500`. This corrects the "Errors" row in *Cross-Cutting Concerns* below, which had removed any "DB not ready" `503` contract on the (incomplete) reasoning that Stage 0's fail-fast startup made it unnecessary — that reasoning only ever covered startup-time unavailability.
> - **Second finding, one stage later (Stage 2 completeness audit):** the fix above was first verified only against `POST /api/v1/tenants` — the *one* endpoint that bypasses `ApiKeyAuthenticationFilter` (open/unauthenticated by design, Stage 1). Every other endpoint hits that filter's own `ApiKeyRepository.findTenantIdByKeyHash(...)` call *before* any controller code runs at all — and a raw Servlet `Filter` runs outside Spring MVC's dispatcher entirely, so `@RestControllerAdvice`/`ApiExceptionHandler` can never see an exception it throws, regardless of exception type. Verified live with Postgres stopped mid-flight: `GET /api/v1/tenants/{tenantId}/model-config` (Stage 2, authenticated) leaked a raw, non-RFC-7807 `500` from Tomcat's default error page instead of the intended `503`. Fixed by giving `ApiKeyAuthenticationFilter` its own try/catch around that one repository call, writing the same `503` RFC 7807 body directly (same pattern already used there for `401`s) rather than relying on `ApiExceptionHandler`. Re-verified live: now returns `503` correctly. This is a **structural** gap, not specific to Stage 2 — every future authenticated endpoint (all of Stage 3 onward) shares this exact filter, so this fix was a genuine prerequisite for continuing past Stage 2, not an isolated Stage 2 bug.
> - Also verified: `/actuator/health` correctly stays `UP` when only Redis is down (`management.health.redis.enabled: false`, base `application.yaml` — Redis is never critical) but correctly goes `DOWN` when Postgres is down (untouched, still the default `DataSource` health indicator — Postgres is genuinely critical). Both services' **recovery** was also verified live: stopping then restarting either (or both at once) while the app keeps running self-heals within about a second, no app restart needed.

### Concurrency & Constraint-Violation Handling (found by firing real concurrent requests, not sequential tests)

> **✅ Decision (`plan.md` §9, 2026-07-17):** An end-to-end audit of Stages 0–2, done specifically by firing concurrent requests and malformed input at a live app rather than re-checking sequential test coverage, found three real gaps — all client-caused conditions that were misclassified as server errors:
>
> - **`DataIntegrityViolationException` had no handler at all.** Firing 10 concurrent first-time `PUT .../model-config` requests for the same brand-new tenant — no lock, no atomic upsert in `ModelConfigService.configureModel()`'s find-or-create — produced **9 raw `500`s out of 10** on a live app; root cause confirmed in the logs as a `model_configs.tenant_id` unique-constraint violation on the losing inserts. A 500-character `CreateTenantRequest.name` (past Hibernate's implicit `varchar(255)`) hit the same unhandled exception type via a different constraint. Fix: `ApiExceptionHandler` now handles `DataIntegrityViolationException`, inspecting the underlying `SQLException`'s SQLSTATE — `23505` (unique violation, a real conflict with existing state) → `409 Conflict`; anything else (length/check/not-null violations, malformed input) → `400 Bad Request`. Re-verified live: the same 10-concurrent-request reproduction now returns only `200`/`409`, never `500`; the oversized name now returns `400`.
> - **`MethodArgumentTypeMismatchException` had no handler.** A malformed `{tenantId}` path segment (e.g. `GET /api/v1/tenants/not-a-uuid/model-config` with a valid API key) threw this during Spring MVC's argument resolution — before the controller method ever ran — and leaked a raw `500`. Fix: `ApiExceptionHandler` now maps it to `400 Bad Request`. Same "client mistake misclassified as a server bug" pattern already fixed once for `HttpMessageNotReadableException` (Stage 0) and once for `CannotCreateTransactionException`/`DataAccessResourceFailureException` (above) — the pattern is: find the real exception type from a live reproduction, map it explicitly, never let a client-input problem fall through to the generic `500` handler.
> - **`ChatModelFactory`/`EmbeddingModelFactory` cache eviction could race ahead of the DB commit.** `ModelConfigService.configureModel()` published `ModelConfigUpdatedEvent` synchronously from inside its own `@Transactional` method body — meaning eviction happened *before* the transaction committed (Spring's `@Transactional` proxy commits only after the method returns). A concurrent `getChatModel()`/`getEmbeddingModel()` call landing in that window would re-read `model_configs` under READ_COMMITTED, see the still-uncommitted **old** row, and repopulate the cache with stale credentials/model — permanently, since nothing would evict it again. Found by code inspection (the mechanism is certain from Spring's documented transaction-proxy timing), not forced via a live race window; confirmed instead with a deterministic test that opens a transaction, publishes the event, and asserts the cache is untouched until the transaction actually commits. Fix: both factories' `@EventListener` switched to `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`.
> - **`TenantCreationRateLimitFilter`'s `INCR` + conditional `EXPIRE` was two separate Redis round trips, not atomic.** If Redis failed only the second call (plausible given the 1s command timeout above under a real Redis blip), the key would be left with a bumped counter and no TTL — it would never expire again, permanently rate-limiting that IP. Not independently forced live (needs precise fault injection between two sequential commands), but the mechanism is real and version-checked: Redis 7's native `EXPIRE ... NX` isn't available (this environment runs Redis 6.2.18). Fix: replaced with a single atomic Lua script (`INCR` + conditional `EXPIRE`, one Redis round trip, no window for a partial failure) via `RedisScript`/`StringRedisTemplate.execute(...)`.
>
> This does **not** replace the Stage 3 locking design still to be decided — `create_knowledge_base`'s 10-KB-per-tenant limit and any future concurrent-write paths need their own review when built; this section only covers what already existed in Stages 0–2.

### Request Body Size Limit & Authorization Ordering (second audit pass, found by targeting angles the first pass hadn't touched)

> **✅ Decision (`plan.md` §9, 2026-07-17):** A second thorough pass over Stages 0–2 — deliberately not re-checking the concurrency/constraint-violation findings above, but going after credential/secret leakage, actuator exposure, and request-handling edge cases instead — confirmed the logging and actuator surface are clean (no secret leakage in any log statement; actuator defaults to health-only exposure), and found two more real gaps:
>
> - **No request body size limit existed anywhere.** Verified live: a 5MB JSON body was fully accepted and parsed on both the open, unauthenticated `POST /api/v1/tenants` (gated only by the weak 5/hour/IP limiter) and the authenticated `PUT .../model-config` — and on the latter, when app-layer provider validation rejected an oversized field, the **entire value got echoed back** in the error response, doubling the cost. `RequestBodySizeLimitFilter` (`com.synapsemcp.common`, `Order = HIGHEST_PRECEDENCE + 1`, runs before the rate limiter and API-key auth so an oversized request never wastes a Redis/DB round trip) now enforces a **1MB** limit (`synapsemcp.request.max-body-bytes`), rejecting with `413 Payload Too Large`. A `Content-Length`-only check was tried first and rejected once proven insufficient: `TestRestTemplate`'s default Apache HttpClient5 factory sends **every** request `Transfer-Encoding: chunked` with no `Content-Length` header at all — not a contrived edge case, Spring's own reference test client does this — so a header-only check would have silently passed every chunked-encoded body regardless of size. Fixed instead with a bounded read (up to `maxBytes + 1`, never draining a deliberately huge upload just to reject it) that buffers the body and wraps the request so downstream readers (Jackson, etc.) see the already-validated bytes — catches an oversized body regardless of how the client declares or encodes it. Re-verified live via both a `Content-Length`-bearing curl request and a `Transfer-Encoding: chunked` one — both correctly return `413`.
> - **`ModelConfigController` validated the request body before checking tenant ownership.** `@Valid` runs during Spring MVC argument resolution, which happens for every method parameter (in declaration order) before the controller method body ever executes — so the old per-method `requireOwnTenant()` check could never win against body validation regardless of parameter order. Found live: a caller with a valid API key for a *different* tenant, sending an invalid body to a cross-tenant request, got `400` (revealing the required-field shape) instead of `403`. Practical severity was low (the validation messages are static, revealing nothing tenant-specific), but the ordering was backwards in principle. Fixed with `TenantOwnershipInterceptor` (`com.synapsemcp.tenant`, a `HandlerInterceptor`), registered via `WebMvcConfig` against `/api/v1/tenants/{tenantId}/**` — a wildcard pattern so any future tenant-scoped endpoint is covered automatically, not just today's `model-config` routes. `preHandle` runs strictly before argument resolution, so authorization now always wins regardless of body validity. A malformed (non-UUID) `tenantId` is deliberately left unhandled here and falls through to normal argument resolution, which already produces the correct `400` via the `MethodArgumentTypeMismatchException` handler above — no need to duplicate that check. The old `requireOwnTenant()` calls stay in `ModelConfigController` as harmless defense-in-depth (the interceptor guarantees it by the time the method body runs, but the check is cheap and costs nothing to keep).

### Package Structure

> **✅ Decision (`plan.md` §9, 2026-07-16, first documented in `memory.md` Session 4 — supersedes the original coarse layout below):** The application uses **one package per domain**, not the original `core`/`mcp`/`rag`-only split. This reconciles with `plan.md` §4's more specific "each domain package" convention, which post-dates and supersedes the text originally here. `rag`-owned code (extraction, chunking, embedding, vector processing, retrieval) still lives under `com.synapsemcp.rag` and its sub-packages (e.g. `rag.chunk`) — that part of the original rule is unchanged, it's the flat `core`/`mcp` split that was replaced.

Current packages under `com.synapsemcp` (as of Stage 0–2): `tenant`, `knowledgebase`, `document`, `ingestion`, `rag.chunk`, `chat`, `embedding`, `config`, `common`. Each new domain gets its own package as it's built; `mcp_plan.md`'s Phase 2 work will add a `com.synapsemcp.mcp` package as literally originally planned, once that phase starts.

---

# Stage 0.5 — Data Model / Schema (Finalized)

The authoritative, finalized table definitions for the RAG app. **Hibernate-`ddl-auto`-owned schema**, generated from JPA `@Entity` classes — no Flyway, no migration files. PostgreSQL 17+, `vector` extension enabled by the startup bootstrap runner (below) before JPA initializes. Every table uses a `UUID` primary key generated in Java via `@GeneratedValue(strategy = GenerationType.UUID)` (Hibernate assigns the UUID client-side; no Postgres-side `gen_random_uuid()` default is needed, since `ddl-auto` doesn't emit custom column defaults from plain JPA annotations).

## Startup Bootstrap (Role, Database, Extension, ANN Indexes)

Hibernate's `ddl-auto` only manages tables/columns/basic constraints **inside an already-existing, already-connected database** — it cannot create the Postgres role, the database itself, or run `CREATE EXTENSION`, and it cannot express pgvector's HNSW index syntax or `CHECK` constraints from plain JPA annotations. Two small dedicated runners fill those gaps, both run automatically at app startup with no manual step:

**Why database/role creation needs a separate connection.** The app's own JPA datasource connects *to* the `synapsemcp` database — but that database might not exist yet, so that connection can't be what creates it. A `CREATE DATABASE` statement also can't run inside the same session as normal schema DDL once connected to the target DB.

1. **`DatabaseBootstrapRunner`** (`com.synapsemcp.config`) — runs first, before the app's own `DataSource`/`EntityManagerFactory` beans are created. Opens a **plain JDBC connection** (not the app's pooled datasource) to Postgres's `postgres` maintenance database using bootstrap credentials, and idempotently:
   - `CREATE ROLE synapsemcp WITH LOGIN PASSWORD '...'` — catches/ignores the "role already exists" error (Postgres has no `CREATE ROLE IF NOT EXISTS`)
   - `CREATE DATABASE synapsemcp OWNER synapsemcp` — same idempotent catch on "database already exists"
   - Reconnects to the now-guaranteed-to-exist `synapsemcp` database and runs `CREATE EXTENSION IF NOT EXISTS vector`
2. **Standard Spring JPA autoconfiguration proceeds** once step 1 succeeds: HikariCP connects to the app's normal datasource, and Hibernate `ddl-auto` (per-profile value below) creates/validates all tables from the `@Entity` classes as part of ordinary `EntityManagerFactory` bootstrap.
3. **`AnnIndexBootstrapRunner`** (`com.synapsemcp.config`, an `ApplicationRunner` that runs after the `EntityManagerFactory` bean is ready) executes the raw SQL `ddl-auto` can't express: six `CREATE INDEX IF NOT EXISTS idx_chunks_emb_* ... USING hnsw (embedding_* op_class)` statements (`vector_cosine_ops` for `384`–`1536`, `halfvec_cosine_ops` for `3072` — see *Schema Realization* below), plus the `CHECK` constraints on `model_configs.chat_provider`/`model_configs.embedding_provider` (plain `String` columns — see *Schema Realization* below for why `documents.status`/`ingestion_jobs.status` don't need one added here). Runs unconditionally on **every** startup — each statement is idempotent (`IF NOT EXISTS` / catch-and-ignore), so after the first real run it's a fast no-op; this also means the app self-heals if an index or constraint is ever manually dropped.

If Postgres is unreachable, or any of these three steps fails, the app fails to start — standard Spring Boot fail-fast, no retry loop (Stage 0).

### `ddl-auto` by Profile

| Profile | `spring.jpa.hibernate.ddl-auto` | Rationale |
|---|---|---|
| `local` | `update` | Fast dev iteration; schema evolves automatically as `@Entity` classes change |
| `dev` | `update` | Same as `local` — shared dev convenience |
| `test` | `create-drop` | Every `*IntegrationTest` run gets a genuinely fresh schema — consistent with this project's existing destructive-reset testing philosophy (`plan.md` §9, 2026-07-14 isolated-test-database entry) |
| `prod` | `validate` | Hibernate never auto-mutates a production schema; it must already match the entity mapping via a controlled, manual step |

> **✅ Decision (Grooming #18 — Bootstrap credentials):** `DatabaseBootstrapRunner` reuses the **local-dev SUPERUSER role** for `CREATE ROLE`/`CREATE DATABASE`-capable credentials, and is **`local`/`dev`-profile-only** (gated on `synapsemcp.bootstrap.enabled`) — it needs those elevated maintenance-database credentials, which `test`/`prod` don't have and shouldn't need. In `prod`, role/database/extension provisioning reverts to the manual one-time step in `plan.md` §3, `ddl-auto` is `validate` (schema must already exist and match), and ANN indexes/constraints must already be in place before deploy.
>
> **✅ Decision (`plan.md` §9, 2026-07-16 — corrects this entry's original "both bootstrap runners are local/dev-only" wording):** `AnnIndexBootstrapRunner` is gated on its own, separate `synapsemcp.ann-bootstrap.enabled` flag and also runs in **`test`** (only `false` in `prod`) - unlike `DatabaseBootstrapRunner`, it never needs elevated bootstrap credentials, only the app's normal datasource, which `test` already has. Found by directly inspecting the `test`-profile schema: bundling both runners under the same flag had left `synapsemcp_test` with zero HNSW indexes and no `model_configs` `CHECK` constraints at all, meaning any future test exercising ANN search or DB-level provider validation would have silently passed against a schema that didn't match `local`/`dev`/`prod`.

## Schema Realization — `ddl-auto` vs. the ANN Bootstrap Supplement

There is no standalone migration file. The tables below (full detail in *Table Definitions (Reference)* further down) are realized as JPA `@Entity` classes in each domain package (`plan.md` §4 Code conventions), and Hibernate `ddl-auto` generates the actual DDL from those classes at startup. Most of the schema maps directly onto standard JPA/Hibernate annotations:

| Schema feature | JPA/Hibernate mechanism |
|---|---|
| `UUID PRIMARY KEY` | `@Id @GeneratedValue(strategy = GenerationType.UUID)` |
| `NOT NULL` | `@Column(nullable = false)` (the default for primitive/non-null-annotated fields) |
| `UNIQUE` (single or composite) | `@Column(unique = true)` or `@Table(uniqueConstraints = @UniqueConstraint(columnNames = {...}))` |
| `FOREIGN KEY ... REFERENCES` | `@ManyToOne`/`@JoinColumn` |
| `ON DELETE CASCADE` | `@OnDelete(action = OnDeleteAction.CASCADE)` (Hibernate-specific, alongside `@ManyToOne`) |
| Plain composite index (e.g. `idx_documents_tenant_kb`) | `@Table(indexes = @Index(columnList = "tenant_id, knowledge_base_id"))` |
| `TIMESTAMPTZ ... DEFAULT now()` | `@CreationTimestamp` (Hibernate sets the value client-side on insert; behaviorally equivalent) |
| `JSONB` (`chunks.metadata`) | `@JdbcTypeCode(SqlTypes.JSON)` on a `Map`/`JsonNode`-typed field |
| `TEXT` (unbounded, e.g. `chunks.content`, `ingestion_jobs.error_detail`) | `@Column(columnDefinition = "TEXT")` — Hibernate's implicit default for an unannotated `String` field is `varchar(255)`, which silently truncates; every long-text column needs this explicit annotation |
| `chunks.embedding_*` (`vector(N)`) | `org.hibernate.orm:hibernate-vector`'s `SqlTypes.VECTOR` type on a `float[]` field, **with** `@Array(length=N)` matching each column's own fixed name (384/512/.../1536) — unlike a single flexible column, each of these six is individually fixed-dimension by design, so Postgres rejects a wrong-length vector at the DB level as defense-in-depth alongside the app-layer routing check (Stage 5c). The `embedding_3072` column is the one exception — see below. |
| `chunks.embedding_3072` (`halfvec(3072)`) | Same `@Array(length=3072)`, but `SqlTypes.VECTOR_FLOAT16` instead of `SqlTypes.VECTOR` — verified empirically that pgvector's HNSW **and** IVFFlat indexes both hard-cap at 2000 dimensions on the plain `vector` type ("column cannot have more than 2000 dimensions for hnsw index"). `halfvec` (half-precision, 16-bit-per-component) raises that cap to 4000 and is pgvector's own documented answer to indexing high-dimensional embeddings, not a workaround — confirmed `hibernate-vector` maps `SqlTypes.VECTOR_FLOAT16` directly to Postgres `halfvec($l)` DDL. Its HNSW index uses `halfvec_cosine_ops`, not `vector_cosine_ops` (`AnnIndexBootstrapRunner` picks the operator class per column). User-confirmed choice over dropping 3072 from supported dimensions or shipping it with no ANN index. |

**What `ddl-auto` cannot express — handled by `AnnIndexBootstrapRunner` instead (Stage 0.5 above):**

- The six HNSW ANN indexes on `chunks`' sparse embedding columns: `CREATE INDEX IF NOT EXISTS idx_chunks_emb_384 ON chunks USING hnsw (embedding_384 vector_cosine_ops)` (and the same for `512`/`768`/`1024`/`1536`/`3072`) — plain `@Index` only supports ordinary B-tree-style column lists, not pgvector's `USING hnsw ... vector_cosine_ops` syntax.
- `CHECK` constraints on the two plain-`String` provider columns: `model_configs.chat_provider IN ('openai','anthropic','ollama','google-genai')`, `model_configs.embedding_provider IN ('openai','ollama','google-genai')` — standard JPA has no `CHECK` constraint annotation, so these are added as raw `ALTER TABLE ... ADD CONSTRAINT ... CHECK (...)` statements, each guarded by a catch-and-ignore on "constraint already exists" for idempotency. `documents.status`/`ingestion_jobs.status` do **not** get one added here: both are mapped `@Enumerated(EnumType.STRING)` on the `IngestionStatus` enum, and Hibernate's own `ddl-auto` generates an equivalent `CHECK` constraint automatically from the enum's declared constants (verified empirically, Hibernate 7.4.1) — adding a second one here would just be a redundant duplicate, not something `ddl-auto` genuinely can't do.
- `vector` extension enablement itself — handled by `DatabaseBootstrapRunner`, not `ddl-auto` (Stage 0.5 above).

## Table Definitions (Reference)

> **Note on `TEXT` in the tables below:** this column is a generic "this holds string data" notation, not a literal claim that every such column is Postgres's unbounded `TEXT` type. Per the *Schema Realization* rule above, an unannotated `String` field defaults to Hibernate's implicit `varchar(255)` — only fields explicitly given `@Column(columnDefinition = "TEXT")` (`chunks.content`, `ingestion_jobs.error_detail`, `model_configs`/`knowledge_base_model_configs`' `provider_credentials`) are genuinely unbounded in the live schema; verified directly against the running database. Every other "TEXT" field below (`tenants.name`, `api_keys.key_hash`, `model_configs`/`knowledge_base_model_configs`' `chat_provider`/`chat_model`/`embedding_provider`/`embedding_model`, `documents.filename`/`file_type`, etc.) is `varchar(255)` in practice — a deliberate non-issue, since none of these ever hold arbitrarily long values, but worth stating precisely rather than leaving the table's "Type" column ambiguous.

### `tenants`

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK |
| `name` | TEXT | NOT NULL |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `now()` |

### `api_keys`

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK |
| `tenant_id` | UUID | NOT NULL, FK → `tenants(id)` |
| `key_hash` | TEXT | NOT NULL |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `now()` |

Indexes: unique on `key_hash`; index on `tenant_id`.

### `model_configs`

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK |
| `tenant_id` | UUID | NOT NULL, **UNIQUE**, FK → `tenants(id)` |
| `chat_provider` | TEXT | NOT NULL, CHECK IN (`openai`,`anthropic`,`ollama`,`google-genai`) |
| `chat_model` | TEXT | NOT NULL |
| `embedding_provider` | TEXT | NOT NULL, CHECK IN (`openai`,`ollama`,`google-genai`) — note: **no `anthropic`**, Anthropic has no embeddings API |
| `embedding_model` | TEXT | NOT NULL |
| `provider_credentials` | TEXT | JSON: `{"chatApiKey": "", "embeddingApiKey": ""}`, Base64-encoded (see Stage 2) |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `now()` |

One config row per tenant (`tenant_id UNIQUE`) — a tenant has exactly one active provider/model selection at a time.

> **✅ Decision (Grooming #17):** One config per tenant is sufficient for the RAG-app milestone. No multi-profile-per-tenant need (e.g., "fast" vs. "accurate" profiles).

> **✅ Decision (Grooming #19):** One model config per tenant is enough for this milestone. The `tenant_id UNIQUE` constraint stays as-is.

### `knowledge_bases`

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK |
| `tenant_id` | UUID | NOT NULL, FK → `tenants(id)` |
| `name` | TEXT | NOT NULL |
| `embedding_dim` | INT | NOT NULL |

Constraint: `UNIQUE (tenant_id, name)`. `embedding_dim` is **auto-derived** from the tenant's configured embedding model at creation time and never changes (Stage 3's locked-dimension rule).

### `knowledge_base_model_configs`

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK |
| `knowledge_base_id` | UUID | NOT NULL, **UNIQUE**, FK → `knowledge_bases(id)` **ON DELETE CASCADE** |
| `chat_provider` | TEXT | NOT NULL |
| `chat_model` | TEXT | NOT NULL |
| `embedding_provider` | TEXT | NOT NULL |
| `embedding_model` | TEXT | NOT NULL |
| `provider_credentials` | TEXT | JSON, Base64-encoded |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `now()` |

This table acts as a permanent snapshot of the tenant's model configuration taken at the time the knowledge_base was created. This locks the knowledge_base to a specific model to ensure vector dimensions and semantic spaces never drift.

### `documents`

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK |
| `tenant_id` | UUID | NOT NULL (**no FK** — intentional, see decision below) |
| `knowledge_base_id` | UUID | NOT NULL, FK → `knowledge_bases(id)` |
| `filename` | TEXT | NOT NULL |
| `file_type` | TEXT | NOT NULL |
| `status` | TEXT | NOT NULL, CHECK IN (`PENDING`,`INDEXING`,`READY`,`FAILED`) |
| `content_hash` | TEXT | NOT NULL |
| `extractor_name` | TEXT | nullable — populated by Stage 5a (e.g. `PdfExtractor(pdfbox-fallback)`) |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `now()` |

Indexes: `idx_documents_tenant_kb` on `(tenant_id, knowledge_base_id)`; **unique** `idx_documents_tenant_kb_content_hash` on `(tenant_id, knowledge_base_id, content_hash)` — this unique index is what makes Stage 4's idempotency short-circuit atomic and race-safe, not just an application-level check.

### `chunks`

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK |
| `tenant_id` | UUID | NOT NULL (**no FK** — intentional) |
| `document_id` | UUID | NOT NULL, FK → `documents(id)` **ON DELETE CASCADE** |
| `chunk_index` | INT | NOT NULL |
| `content` | TEXT | NOT NULL |
| `embedding_*` | `vector(*)` for `384`–`1536`, `halfvec(3072)` for `3072` | **Sparse Dimension Columns** (`384`, `512`, `768`, `1024`, `1536`, `3072`). Only the column matching the knowledge_base's `embedding_dim` is populated. `embedding_3072` is `halfvec`, not `vector` — pgvector's HNSW/IVFFlat indexes hard-cap at 2000 dimensions, and `halfvec` is pgvector's native way to index above that (see *Schema Realization* above). |
| `metadata` | JSONB | NOT NULL, default `'{}'` |

Indexes: `idx_chunks_tenant_doc` on `(tenant_id, document_id)`. Plus HNSW ANN indexes on every sparse dimension column (e.g., `idx_chunks_emb_1536`) created idempotently by `AnnIndexBootstrapRunner` on every startup (`CREATE INDEX IF NOT EXISTS`) — a fixed, static set of six indexes, no per-tenant or otherwise dynamic runtime DDL.

### `ingestion_jobs`

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK |
| `tenant_id` | UUID | NOT NULL (**no FK** — intentional) |
| `document_id` | UUID | NOT NULL, **UNIQUE**, FK → `documents(id)` **ON DELETE CASCADE** |
| `status` | TEXT | NOT NULL, CHECK IN (`PENDING`,`INDEXING`,`READY`,`FAILED`) |
| `stage` | TEXT | nullable |
| `error_detail` | TEXT | nullable |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `now()` |
| `updated_at` | TIMESTAMPTZ | NOT NULL, default `now()` |

Index: `idx_ingestion_jobs_tenant_doc` on `(tenant_id, document_id)`. `document_id UNIQUE` means **one job per document** — each file upload creates a new job (no rerun of existing jobs).

> **✅ Decision (Grooming #16 — Missing FKs on `tenant_id`):** The absence of FK constraints on `tenant_id` in `documents`, `chunks`, and `ingestion_jobs` is **intentional** — denormalized for query-filter performance, integrity enforced at the application layer. Unlike `api_keys.tenant_id` and `knowledge_bases.tenant_id` which do reference `tenants(id)`, these tables rely on app-layer enforcement.

### Schema Source (Pointer)

The authoritative schema is the JPA `@Entity` classes under each domain package, generated/validated via Hibernate `ddl-auto` per the profile table above, with the ANN indexes/`CHECK` constraints added by `AnnIndexBootstrapRunner` (*Schema Realization* above). The column-level tables in this section are the design spec those entities must match — there's no separate migration file to keep in sync.

---

# Stage 1 — Create Tenant

**Flow step [1].** Provision a tenant and hand back its initial API key.

| Aspect | Detail |
|---|---|
| Endpoint | `POST /api/v1/tenants` |
| Auth | **Open / unauthenticated** (no admin tier exists — `PLAN.md` §9 decision) |
| Abuse guard | Per-IP fixed-window limiter (`TenantCreationRateLimitFilter`), **5 requests/hour/IP**, fails **open** if Redis is down — genuinely fast (~100ms), not Lettuce's 60s default command timeout; see *Runtime Timeouts — Redis & Postgres* in Stage 0 above. `INCR`+`EXPIRE` runs as one atomic Lua script, not two separate round trips — see *Concurrency & Constraint-Violation Handling* in Stage 0 above |
| DB unavailable mid-request | `503 Service Unavailable` (not the Redis case above — Postgres has no fail-open story, but must still fail fast; see *Runtime Timeouts* in Stage 0 above) |
| Returns | Tenant id + the one-time API key (shown once) |
| Components | `TenantService`, `TenantController`, `api_keys` row (hashed) |

### Decisions

> **✅ Decision (Grooming #2 — API-key model):** Single API key per tenant is sufficient for the RAG-app milestone. Key rotation and multiple keys per tenant are deferred to a later phase.

> **✅ Decision (Grooming #22 — Rate-limit defaults):** 5 requests/hour/IP is confirmed as the right default for tenant creation rate limiting.

### Done When

- A fresh caller with no credentials can create a tenant, receives a usable API key, and that key resolves to the correct `TenantContext` on the next request.
- Rate limiting works correctly (5/hour/IP).
- Tenant-isolation test: creating tenant B doesn't affect tenant A's data.

---

# Stage 2 — Configure Model Config

**Flow step [2].** Tell the tenant which chat model and embedding model to use, and store the provider credentials.

| Aspect | Detail |
|---|---|
| Endpoints | `PUT /api/v1/tenants/{tenantId}/model-config`, `GET .../model-config` |
| Auth | Tenant-key-only, own-tenant-only |
| Stores | `model_configs`: chat model + embedding model + provider credentials |
| Credential storage | **Base64-encoded** JSON (`plan.md` §9, 2026-07-16) — obfuscation, not encryption; no key material, no mandatory boot secret |
| Never | Credentials are never echoed back in any response |
| Providers | OpenAI, Google GenAI, Ollama (chat also: Anthropic — no embeddings) |

### Hard Rule — Chat/Embedding Provider Comes from DB Only

`model_configs.chat_provider`/`chat_model`/`embedding_provider`/`embedding_model` (Stage 0.5 schema) are the **single source of truth**. There is no static/env-var/`application.yaml` fallback for *which* provider or model a request uses — `ChatModelFactory`/`EmbeddingModelFactory` resolve **exclusively** from the tenant's `model_configs` row at request time, resolved fresh (or served from the tenant/provider-keyed cache, evicted on update) on every call. This deliberately **excludes** the master `PLAN.md` §1.5 note about `spring.ai.model.chat`/`spring.ai.model.embedding` selecting one static provider for the whole running application — that mechanism is **not used by the RAG app's request path** at all; it may still exist as Spring AI auto-config boilerplate, but nothing in this plan's flow reads from it.

### Behavior When No Config Exists

Stage 4/5 ingestion and Stage 6 `ask` rely on the **knowledge_base-specific snapshot** (Stage 3). However, Stage 3 knowledge_base creation itself requires a global `model_configs` row to take a snapshot from. No row → knowledge_base creation fails.

> **✅ Decision (Grooming #21 — Error contract for missing `model_configs`):** Returns **`422 Unprocessable Entity`** with RFC 7807 problem-detail: `"model config not set for tenant"`. This status code and wording is **consistent across all endpoints** — upload (Stage 4/5) and ask (Stage 6) both return the same `422`.
>
> One documented exception: `PLAN.md` §1.3's LLM-first PDF/image extraction already treats a missing `model_configs` row as an expected, silent fallback to the library-only extraction path — that specific fallback stays as-is; it is not a "which provider" decision, it's a "skip the LLM step" decision.

### Decisions

> **✅ Decision (Grooming #3 — Credential storage, superseded `plan.md` §9 2026-07-16):** Credentials are stored **Base64-encoded**, not plaintext and not AES-GCM-encrypted. This is an app-layer encode/decode transform with no key material — explicitly **obfuscation, not confidentiality** (trivially reversible by anyone with DB access), chosen over reintroducing AES-GCM/key management while still avoiding a byte-for-byte human-readable API key in a raw text column. No encryption flag, no mandatory boot secret.

> **✅ Decision (Grooming #22 — API Key Auto-Sync):** If a tenant updates their global provider credentials in `model_configs` (e.g. key rotation), the system automatically syncs the new credentials to all existing `knowledge_base_model_configs` rows that share the same `chat_provider` or `embedding_provider`. The model names themselves remain permanently locked to the knowledge_base.

> **✅ Decision (Grooming #4 — Model config validation):** No eager validation of model names at config write time. Instead:
> - **During file upload:** if ingestion fails for any valid reason (including bad model name), **do not add/persist the entry in the DB** — return a proper response message with an appropriate status code.
> - **During ask time:** if the call fails (including due to an invalid model), return a proper response explaining why it failed, along with the appropriate HTTP status code.

> **✅ Decision (Grooming #15 — `ChatOptions`):** **Generic `ChatOptions.builder()` is correct** and works per-call. The sub-plan's original note about needing concrete per-provider `*ChatOptions` to avoid `ClassCastException` is stale — fix the sub-plan to match the master `PLAN.md` §9 decision. `ChatModelFactory.optionsFor(...)` uses the generic builder.

### Done When

- A tenant can set and retrieve a config.
- A request-time factory resolves the correct provider/model from it without an app restart.
- Credentials never leak in responses or logs.
- Credentials round-trip correctly through Base64 encode/decode (stored encoded, decoded and usable by `ChatModelFactory`/`EmbeddingModelFactory` at resolution time).
- Tenant-isolation test: tenant B cannot read or modify tenant A's config.

---

# Stage 3 — Create Knowledge Base

**Flow step [3].** Create a knowledge_base the tenant will upload documents into. Full CRUD (create, list, update, delete).

| Aspect | Detail |
|---|---|
| Endpoints | `POST /api/v1/knowledgebase`, `GET /api/v1/knowledgebase` (list), `PUT /api/v1/knowledgebase/{id}`, `DELETE /api/v1/knowledgebase/{id}` |
| Auth | Tenant-key-only |
| Params | `name` (required); `embedding_dim` is **auto-derived** from the tenant's configured embedding model |
| Snapshot | **Takes a permanent snapshot** of the tenant's `model_configs` into `knowledge_base_model_configs` during creation. |
| Limits | **Maximum 10 Knowledge Bases per tenant.** Creating an 11th returns `422 Unprocessable Entity`. |

### Decisions

> **✅ Decision (Grooming #23 — knowledge_base Model Lock / Snapshot):** To protect existing knowledge_bases from the tenant dynamically changing their global default model, the system takes a snapshot of the tenant's current model config at knowledge_base creation time. **Once an LLM and embedding model are configured for a Knowledge Base, they cannot be changed.** However, the latest token (API key/credentials) for those configured models will be automatically synced from the global config (see Grooming #22). Stage 4/5 (ingestion) and Stage 6 (search/ask) read exclusively from the knowledge_base's `knowledge_base_model_configs` snapshot, not the tenant's global default.

> **✅ Decision (Grooming #5a — knowledge_base update/delete):** knowledge_base **update and delete** are **in scope** for the RAG-app milestone. Full CRUD: create, list, update (name only — `embedding_dim` is immutable), and delete (cascading to documents and chunks).

> **✅ Decision (Grooming #5b — `embedding_dim` derivation):** `embedding_dim` is **auto-derived** from the tenant's configured embedding model in `model_configs`. The caller does **not** pass `embedding_dim` explicitly. At knowledge_base creation time, the system resolves the tenant's current embedding model and determines its dimension automatically. This requires a valid `model_configs` row to exist before creating a knowledge_base (returns `422` if missing). **Crucially, the derived dimension must exactly match one of the allowed sparse column sizes (384, 512, 768, 1024, 1536, 3072).** If the model outputs an unsupported dimension, knowledge_base creation is rejected with a `422 Unprocessable Entity` stating the dimension is not supported.
>
> **✅ Decision (`plan.md` §9, 2026-07-16 — derivation mechanism):** Derivation is a **live probe call**, not a static model-name→dimension lookup table. `KnowledgeBaseService` resolves the tenant's `EmbeddingModel` via `EmbeddingModelFactory` (Stage 2) and calls `embed()` once against a fixed constant probe string, measuring the returned vector's length. A static table was rejected because Ollama permits arbitrary user-pulled embedding models with no fixed registry to look up. **If the probe call itself throws** (bad credentials, unreachable provider, unknown model name), knowledge_base creation returns `422 Unprocessable Entity` with a problem-detail explaining the embedding model failed validation — the same status code as the missing-config and unsupported-dimension cases above, not a new one.

### Done When

- A tenant can create, list, update, and delete its own knowledge_bases.
- Another tenant's knowledge_bases never appear in the list.
- `embedding_dim` is auto-derived and fixed at creation; the caller doesn't specify it.
- Attempting to create a knowledge_base without a `model_configs` row returns `422`.
- The embedding dimension is derived via a live probe call to the resolved embedding model, not a lookup table; a failed probe call (bad credentials, unreachable provider) returns `422`.
- Attempting to create a knowledge_base with an unsupported model dimension returns `422`.
- Attempting to create more than 10 knowledge_bases returns `422`.
- Delete cascades correctly to associated documents and chunks.
- Tenant-isolation test: tenant B cannot access/modify tenant A's knowledge_bases.

---

# Stage 4 — Upload Document

**Flow step [4].** Upload a document into a knowledge_base by `knowledge_base_id`. This is the *entry* to ingestion; the heavy work is Stage 5 (async).

| Aspect | Detail |
|---|---|
| Endpoint | `POST /api/v1/knowledgebase/{knowledgebaseId}/documents` (multipart) |
| Response | **`202 Accepted` + job id immediately** — never blocks on processing |
| Sync checks | Content-type validated **synchronously** (415 on unsupported type before any async work); size cap (20 MB → 413). **Must be exempted from `RequestBodySizeLimitFilter`'s global 1MB limit** (`com.synapsemcp.common`, added Stage 0, `plan.md` §9 2026-07-17) via `shouldNotFilter` — that filter has no path exemptions yet and would otherwise silently reject every upload over 1MB, long before this endpoint's own 20MB check is ever reached. Flagged during a later audit pass, not yet fixed since this endpoint doesn't exist yet |
| Page limit | Up to **100 pages per file** for LLM-first extraction (`MAX_LLM_PAGES` cost guardrail) |
| Type detection | Apache Tika content-sniffing — never trust the extension alone |
| Idempotency | Content-hash short-circuit: re-uploading identical content to the same knowledge_base returns the existing job |

**Supported formats (Phase 1):** the 7 direct-upload categories — PDF · images · Word · Excel · PowerPoint · HTML · text/markdown. Full extension list in *Supported Document Sources* above. External sources (GitHub / Notion / Confluence) are SPI-only here.

### Implementation

Synchronous validation, then async processing — the HTTP call returns `202` immediately and never blocks on extraction.

- **MIME detection (`FileTypeDetector`).** `org.apache.tika.Tika.detect(bytes, filename)` sniffs the actual bytes, not the extension — HTML bytes named `.txt` still resolve to `text/html`. Unsupported types are rejected synchronously (`415`) before any async work starts.
- **Entry-point-agnostic ingestion (Grooming #28).** The synchronous validation + async dispatch described in this section is shared by **both** entry points: the REST multipart upload (this endpoint) and the MCP `ingest` tool (`mcp_plan.md` Stage 2), which calls the same underlying service directly rather than looping back through HTTP (per `mcp_plan.md` Grooming #2). The MCP tool normalizes its two accepted input shapes down to the same `(byte[] content, String mimeType, String filename)` triple this service expects before handing off: raw file bytes go through the same Tika-sniffing path above; a raw `text` field skips sniffing entirely and is passed with `mimeType` hard-set to `text/markdown` (since its nature is already known, not guessed). From this point on, both entry points are indistinguishable to the pipeline.
- **Idempotency (`ContentHasher.sha256Hex(bytes)`).** If a `Document` already exists for `(tenantId, knowledgeBaseId, contentHash)`, its status decides what happens (Grooming #26):
  - `PENDING` / `INDEXING` / `READY` → the existing job is returned unchanged, re-uploading identical content is a no-op.
  - `FAILED` → the same document/job rows are reset (`status → PENDING`, `error_detail`/`stage` cleared) and re-dispatched onto the pipeline, giving the caller a working retry path through the same upload action, without violating `ingestion_jobs.document_id UNIQUE` (Stage 0.5) by minting a second job for the same document.
- **Async dispatch.** `Document(PENDING)` and `IngestionJob(PENDING)` are committed in **independent transactions** (not one spanning transaction, so the worker thread can never read absent rows), then `IngestionPipelineService.run(...)` fires on the `@Async("ingestionExecutor")` pool. `ContextPropagatingTaskDecorator` copies the `TenantContext` / correlation-ID ThreadLocals across the pool boundary.
- **Extraction dispatch (`DocumentExtractionService`).** Picks the **first** `DocumentExtractor` whose `supports(mimeType)` is true (see Stage 5a for the extractor set).
- **Job state machine.** `PENDING → INDEXING → READY | FAILED`, recording the failing `IngestionStage` + exception message on failure.
- **Error handling:** If upload/ingestion fails for any valid reason during the synchronous phase, **do not persist** the document or job entry in the DB — return a proper error response with an appropriate status code.

### Decisions

> **✅ Decision (Grooming #6a — Upload caps):** Single file size cap is **20 MB** (413 on exceed). LLM-first extraction supports up to **100 pages per file** (increased from the draft's 30-page cap). Beyond 100 pages, falls back to library-only extraction.

> **✅ Decision (Grooming #28 — MCP `ingest` Input Shape):** `mcp_plan.md`'s `ingest` tool was originally described as accepting "content or a reference" without defining either term concretely, and "reference" implied URL/external-source ingestion that contradicts this plan's own SPI-only scope decision for external connectors (§2, Milestone 7). Resolved: the MCP `ingest` tool accepts exactly two input shapes — file bytes (`filename` + `content_base64`, routed through the same Tika-sniffing path as the REST upload above) or raw text (a `text` field, wrapped as a synthetic `.md` document with `mimeType` hard-set to `text/markdown`, bypassing sniffing since the content type is already known). Both normalize to the same `(byte[], mimeType, filename)` triple before entering the shared pipeline described in this section. URL/reference-based ingestion is explicitly **not** part of this tool's scope — it remains an external-connector concern, deferred exactly where §2/Milestone 7 already deferred it.

> **✅ Decision (Grooming #6b — Idempotency):** Idempotency is **short-circuit** (not versioning). Re-uploading identical content returns the existing job. Each new (non-duplicate) file upload creates a **new job** — there is no "rerun job" mechanism. A new upload with different content creates a new document + new job.

> **✅ Decision (Grooming #26 — Retry via Re-upload on FAILED):** Grooming #6b's short-circuit, as originally written, returns the existing job **regardless of its status** — meaning a legitimately-`FAILED` job (e.g. a transient provider outage during extraction) becomes permanently stuck: re-uploading the identical file forever returns the same `FAILED` job, and no retry endpoint exists anywhere in the plan. Fixed by making the short-circuit status-aware: `PENDING`/`INDEXING`/`READY` still short-circuit as a pure no-op (Grooming #6b's original behavior, unchanged); a `FAILED` existing document/job is instead **reset in place** (`documents.status` and `ingestion_jobs.status → PENDING`, `ingestion_jobs.error_detail`/`stage` cleared) and immediately re-dispatched onto the ingestion pipeline, returning the same `documentId`/`jobId` as before. This requires no new endpoint and no schema change — it preserves the `ingestion_jobs.document_id UNIQUE` invariant (still exactly one job per document, Grooming #6b's "no rerun job mechanism" is unaffected since this reuses the *same* job rather than creating a new one) while giving users a working recovery path through the same upload action they'd naturally retry with.

### Done When

- A valid upload returns `202` + job id in well under a few seconds even for large files.
- Unsupported types are rejected up front (`415`).
- Files exceeding 20 MB are rejected (`413`).
- A duplicate upload against a `PENDING`/`INDEXING`/`READY` job returns the prior job unchanged (no-op).
- A duplicate upload against a `FAILED` job resets and re-dispatches the same document/job (same `documentId`/`jobId`), giving the caller a working retry path (Grooming #26).
- Failed synchronous validation does not leave orphaned rows in the DB.
- Tenant-isolation test: tenant B cannot upload to tenant A's knowledge_base.

---

# Stage 5 — Async Ingestion Pipeline

**Flow step [5] — the notebook's "async transaction during document upload, create proper chunk generation and store them in DB."**

This is the ingestion pipeline. It runs on a dedicated bounded executor **after** the `202` returns, with `TenantContext` + correlation ID propagated in.

### Pipeline Stages

```
extract → chunk → embed (batched) → persist → index (Lucene) → mark READY
```

Job state machine: `PENDING → INDEXING → READY | FAILED` (`ingestion_jobs`, with per-stage `stage` for observability). `ingestion_jobs` is the authoritative record of this state; `documents.status` is written to the identical value in the **same transaction** at every transition (Grooming #30) — a read-optimized mirror only, never updated independently.

### Executor Configuration

> **✅ Decision (Grooming #9):** Executor defaults are confirmed: **core 4 / max 8 / queue 100 / `CallerRunsPolicy`**.

---

## 5a. Extract (Text Out of the File)

`DocumentExtractionService` dispatches to the first `DocumentExtractor` whose `supports(mimeType)` is true. Two are LLM-first, three are library-only:

- **`PdfExtractor` (LLM-first).** Renders each page with PDFBox `PDFRenderer` at 150 DPI, batches **5 pages per vision call** to the tenant's chat model, concatenates. Falls back to `PDFTextStripper` on error/blank, or **skips the LLM path entirely above 100 pages** (`MAX_LLM_PAGES` cost guardrail). Redis-cached 24 h by content hash.
- **`ImageExtractor` (LLM-first).** LLM vision transcription first (converting BMP/TIFF → PNG), falling back to **RapidOCR** (pure-JVM ONNX PP-OCRv4) on error/blank. Redis-cached 24 h.
- **`OfficeExtractor` (POI), `HtmlExtractor` (jsoup), `PlainTextExtractor`** — library-only, no LLM path.
- Records which extractor produced the final text (`documents.extractor_name`, e.g. `PdfExtractor(pdfbox-fallback)`).

---

## 5b. Chunk (Proper Chunk Generation)

Strategy-based (`ChunkingStrategy` SPI) with an `@Order` priority chain and a size-adaptive shortcut. `DocumentChunkingService` picks the first strategy that `supports(...)`.

- **Token estimation (`TokenEstimator`).** `ceil(text.length / 4.0)` — the chars-per-token ≈ 4 rule of thumb; deliberately approximate since no provider is fixed at chunk-design time.
- **Single-chunk shortcut.** Returns one chunk if `estimateTokens(text) < windowTokensFor(totalTokens) × 1.2`, regardless of format (threshold derived from the selected window, never a flat constant).
- **`TableAwareChunkingStrategy` (`@Order(1)`, spreadsheet MIME only).** Groups adjacent rows into ~512-char windows on **row boundaries**, repeating the `# SheetName` heading in each chunk. Runs first because spreadsheet text also contains `#` lines that would otherwise trip the structure strategy.
- **`StructureAwareChunkingStrategy` (`@Order(2)`, content-based).** Triggered by any `^#{1,6}\s+.*$` heading line (works for Markdown/HTML/Word uniformly). Splits at each heading, maintains a heading-level stack for `headingPath` metadata, and skips flushing heading-only chunks.
- **`FixedSizeChunkingStrategy` (`LOWEST_PRECEDENCE`, universal fallback).** Sliding window with overlap, 3-tier dynamic scaling by total estimated tokens: `<1,000` → 256 / 10% · `1,000–50,000` → 512 / 15% (default) · `>50,000` → 1,024 / 20%.
- Chunk metadata (strategy, position, `headingPath`) persisted for later citation.

---

## 5c. Embed

- Embedding model resolved from the knowledge_base's `knowledge_base_model_configs` snapshot, **not** the global tenant config. Batch every cache-miss into one call.
- Redis embedding cache: key `sha256(provider:model:normalized_text)`, TTL 7 days.
- **Sparse Column Mapping:** Based on the knowledge_base's `embedding_dim` (e.g., 1536), the ingestion pipeline saves the vector into the corresponding sparse column (e.g., `embedding_1536`), leaving the other dimension columns `NULL`. Mismatched dimensions fail the job.

---

## 5d. Persist + Index

- Chunks committed atomically to Postgres (`chunks.embedding` via Hibernate `SqlTypes.VECTOR`); a mid-batch failure leaves no partial chunks.
- Lucene BM25 index written **only after** the Postgres commit (consistency rule); one index directory per knowledge_base.
- On success → job `READY`. On any exception → caught, stage + error recorded to `ingestion_jobs.error_detail`, job `FAILED`.

### Lucene ↔ Postgres Reconciliation

> **✅ Decision (Grooming #7):** A **basic reconciliation cron** is included in this milestone. The cron detects drift between the Lucene index and Postgres `chunks` table (e.g., orphaned Lucene entries, missing index entries for committed chunks) and repairs them. Runs at a configurable interval (e.g., every hour). Does not need to be real-time — eventual consistency is acceptable. The cron compares Postgres `chunks` against the Lucene index **directly by knowledge_base**, independent of `ingestion_jobs.status` — this also covers the specific case where Postgres commit succeeded but the subsequent Lucene write then threw (job recorded `FAILED` despite the chunks being fully durable): the cron indexes the missing Lucene entries and flips that job back to `READY`, rather than leaving a permanently `FAILED` job for data that is actually complete and searchable.

---

## Ingestion Metrics

> **✅ Decision (Grooming #8):** Basic ingestion metrics are included in this milestone:
> - **Jobs by state** — count of jobs in each state (`PENDING`, `INDEXING`, `READY`, `FAILED`).
> - **Failure counts** — count and breakdown of failures by stage and error type.
> - Exposed via a metrics endpoint or actuator. Full stage-duration histograms and advanced observability are deferred to Phase 3.

---

## Polling

- `GET /api/v1/jobs/{jobId}` — tenant-scoped status polling (also feeds the future MCP `ingest` tool).

### Done When

- Every supported format ingests end-to-end on fixture files.
- A 100-page PDF completes without blocking API responsiveness.
- LLM-first extraction supports up to 100 pages, falls back to library-only beyond that.
- A dimension mismatch or extractor failure marks the job `FAILED` with a useful `error_detail`.
- Duplicate content short-circuits.
- Reconciliation cron detects and fixes Lucene ↔ Postgres drift.
- Basic metrics (jobs by state, failure counts) are exposed.
- Tenant-isolation test: tenant B's ingestion never touches tenant A's chunks or indexes.

---

# Stage 6 — Search & Answer

**Flow step [6] — "get the chunk details for the question asked by the consumer → from the chunks generate understandable user-language answer."**

Three sub-capabilities: **retrieve** the right chunks (with ANN-optimized search), **rerank** them, then **answer** grounded in them (with streaming and multi-language support).

---

## 6a. Retrieve (Get the Relevant Chunks — ANN-Optimized)

| Aspect | Detail |
|---|---|
| Endpoint | `POST /api/v1/knowledgebase/{knowledgebaseId}/search` |
| Method | Hybrid: **BM25 (Lucene) + ANN vector (pgvector cosine with HNSW/IVFFlat) + RRF merge** |
| RRF | `score(d) = Σ 1 / (k + rank_i(d))`, default `k = 60`, dedupe by chunk id |
| Vector query | Tenant + knowledge_base filter **inside the SQL**; query vector bound as a parameter (no injection) |
| ANN indexing | Per-knowledge_base HNSW or IVFFlat index (see ANN strategy below) |
| Per-request config | `top_k`, `rrf_k`, `mode` (hybrid / vector / keyword) |
| Result shape | chunk id, document id, filename, content, score, chunking metadata |

Note: pure-`keyword` mode never embeds the query, so keyword search works even if the tenant has no embedding provider configured.

### Implementation

- **Query embedding.** Resolved from the knowledge_base's `knowledge_base_model_configs` snapshot. Same batched + Redis-cached path as ingestion; computed **only** when `mode` is `vector`/`hybrid`.
- **Vector search — pgvector cosine with ANN index (`VectorSearchService`).** Native query targets the specific sparse column based on the knowledge_base's dimension (e.g., `c.embedding_1536 <=> CAST(:queryVector AS vector)`) filtered by `tenant_id` + `knowledge_base_id`, `ORDER BY distance LIMIT topK`, `score = 1.0 - distance`. Because of the sparse columns and static schema indexes, this guarantees an ANN lookup, never a sequential scan.
- **Keyword search — Lucene BM25 (`LuceneIndexManager`).** `QueryParser.escape(queryText)` (free text is always literal terms, never Lucene syntax) → `StandardAnalyzer` + `QueryParser` on the `content` field, default BM25 similarity. **One index directory per knowledge base.**
- **Reciprocal Rank Fusion (`ReciprocalRankFusion.merge`).** `score(d) = Σ 1/(k + rank_i(d))` across every ranked list the chunk appears in, accumulated in a `LinkedHashMap<UUID,Double>` (natural dedup), sorted descending. `DEFAULT_K = 60`.
- **Mode dispatch.** `vector` → vector-only · `keyword` → keyword-only · `hybrid` (default) → RRF merge; truncate to `topK`, then hydrate each chunk id into a full result with content, metadata, and source filename.

### ANN Index Strategy

> **✅ Decision (Grooming #14 — pgvector ANN index - Sparse Columns):** The **best and most optimized search flow** is in scope. To support HNSW ANN indexing without requiring dangerous runtime DDL (like dynamic table partitions or thousands of partial indexes), the schema uses **Sparse Dimension Columns**.
>
> - The `chunks` table pre-defines explicit columns for supported dimensions (e.g., `embedding_384`, `embedding_512`, `embedding_768`, `embedding_1024`, `embedding_1536`, `embedding_3072`).
> - HNSW indexes are created on these columns idempotently by `AnnIndexBootstrapRunner` at every app startup (Stage 0.5), not by a migration file.
> - At ingestion and query time, the app routes the vector to the specific column that matches the Knowledge Base's `embedding_dim`. This guarantees fast ANN search with zero operational runtime DDL risk.
>
> **✅ Decision (implementation session, 2026-07-16 — `embedding_3072` uses `halfvec`, not `vector`):** Verified empirically while building `AnnIndexBootstrapRunner` that pgvector's HNSW **and** IVFFlat indexes both hard-cap at 2000 dimensions on the plain `vector` type — `embedding_3072` (needed for e.g. OpenAI `text-embedding-3-large`'s default output, Google `gemini-embedding-001`'s default) cannot be indexed as `vector(3072)` at all. User-confirmed choice, after being shown three options (drop 3072 from supported dimensions; keep it with no ANN index and accept a sequential-scan fallback; or use `halfvec`): use Postgres's `halfvec` type for this one column. `halfvec` is pgvector's own native, documented mechanism for indexing high-dimensional embeddings (half-precision, 16-bit-per-component, raising the HNSW cap to 4000) — not a workaround outside the ecosystem. Confirmed `org.hibernate.orm:hibernate-vector` maps `SqlTypes.VECTOR_FLOAT16` (not `SqlTypes.VECTOR`) directly to Postgres `halfvec($l)` DDL, same `float[]` Java type, verified by reading the library's own source. The HNSW index on this column uses `halfvec_cosine_ops`; the other five columns keep `vector`/`vector_cosine_ops` unchanged.

---

## 6a+ Rerank (Post-RRF Reranking)

> **✅ Decision (Grooming #11 — Reranking):** Reranking is **in scope** for this milestone.

> **✅ Decision (Grooming #27 — Reranker Model Correction):** The originally-named example reranker, `cross-encoder/ms-marco-MiniLM-L-6-v2`, is trained solely on the English MS MARCO dataset and has no real multilingual capability — directly contradicting this same section's "must support multiple languages" requirement below. Corrected to `BAAI/bge-reranker-v2-m3`, a genuinely multilingual (100+ languages) open-source cross-encoder-style reranker with a permissive license, consistent with the plan's existing ONNX/JVM approach used elsewhere (e.g. RapidOCR, `plan.md` §9).

| Aspect | Detail |
|---|---|
| Position in pipeline | After RRF merge, before answer generation |
| Model preference | **Open-source multilingual reranking model first** (e.g., `BAAI/bge-reranker-v2-m3` via ONNX runtime or similar JVM-compatible approach — genuinely multilingual, unlike an MS-MARCO-trained English-only cross-encoder, which would silently fail the multi-language requirement below despite superficially looking like a valid "cross-encoder reranker" choice). If open-source is not feasible at all due to cost or complexity, fall back to using the tenant's configured LLM for reranking. |
| Multi-language support | Reranker must support multiple languages — choose a multilingual model |
| Purpose | Re-score the top-K RRF results using a more sophisticated relevance model to improve precision |

### Implementation

- After RRF merges and dedupes results, the top-N candidates are passed through the reranker.
- The reranker produces a refined relevance score for each candidate against the original query.
- Results are re-sorted by the reranker score and truncated to the final `topK`.
- Reranking is optional and can be disabled via a request parameter (default: enabled in `hybrid` mode).

---

## 6b. Answer (Generate Understandable-Language Response — Streaming SSE)

| Aspect | Detail |
|---|---|
| Endpoint | `POST /api/v1/knowledgebase/{knowledgebaseId}/ask` |
| Pipeline | hybrid retrieve → rerank → assemble prompt (system + numbered source chunks + question) → tenant's chat model → answer + citations |
| Streaming | **Server-Sent Events (SSE)** — answer tokens stream to the client as they are generated |
| Multi-language | System prompt instructs the model to respond **in the user's language** (detected from the question or explicitly specified) |
| Context budgeting | Greedily include chunks up to the resolved model's token budget (per-model limits in config), always keeping at least one |
| "I don't know" | System-prompt instruction to decline rather than hallucinate **+** hard short-circuit (no LLM call) when retrieval returns zero results |
| History | Optional caller-supplied conversation history (stateless API) |
| Citations | Sources numbered `[Source N — filename]`; response echoes the same chunks |

> **✅ Decision (Grooming #11 — Streaming):** SSE streaming answers are **in scope** for this milestone. The `/ask` endpoint supports both streaming (SSE) and non-streaming modes, controlled by a request parameter or `Accept` header.

### Implementation (`RagAnsweringService.ask`)

- **Retrieve.** Calls `HybridRetrievalService.search` with a fixed internal request (`topK=20`, `mode=hybrid`). That single call **also serves as the tenant/knowledge_base authorization check** — no duplicate check.
- **Rerank.** Post-retrieval reranking refines the chunk order before context budgeting.
- **Empty short-circuit.** If retrieval returns nothing, returns a fixed "I don't have enough information…" answer and makes **no chat-model call at all** (saves cost/latency on empty knowledge_bases).
- **Context-window budgeting (`budgetToContextWindow`).** Greedily includes chunks in reranked order, summing `TokenEstimator` counts, stopping once the next chunk would exceed the model's window (`RagProperties` per-model token map, default `8000`) — but **always keeps at least the first chunk** even if it alone overflows.
- **Prompt assembly.** System prompt numbers each included chunk as `[Source N — filename]`, with explicit instructions to:
  - Cite sources using the provided `[Source N]` format
  - Say "I don't know" rather than guess when sources are insufficient
  - **Respond in the user's language** (auto-detected from the question text, or explicitly specified via a `language` parameter)
  - Appends conversation history (user/assistant → `UserMessage`/`AssistantMessage`), then the question as a final `UserMessage`.
- **Provider call.** `resolved.model().call(new Prompt(messages, ChatOptions.builder().model(name).build()))`. Uses **generic `ChatOptions.builder()`** (confirmed working per-call — see Grooming #15).
- **Streaming.** For SSE mode, uses `resolved.model().stream(new Prompt(...))` and writes tokens to the SSE connection as they arrive. Non-streaming mode uses `.call(...)` and returns the complete response.
- **Return.** `AskResponse(answer, citations)`, where citations mirror the **exact budgeted chunk list** — every citation corresponds to something actually shown to the model. In streaming mode, citations are sent as a final SSE event after the answer completes.

---

## 6c. Evaluate (Basic Relevance Evaluation)

> **✅ Decision (Grooming #10):** A basic **REST `evaluate` endpoint** is included in this milestone.

| Aspect | Detail |
|---|---|
| Endpoint | `POST /api/v1/knowledgebase/{knowledgebaseId}/evaluate` |
| Purpose | Evaluate retrieval quality against a set of golden queries with expected relevant document/chunk IDs |
| Input | A set of `(query, expected_chunk_ids or expected_document_ids)` pairs |
| Output | Relevance metrics: precision@K, recall@K, MRR (Mean Reciprocal Rank) |

### Implementation

- Accepts a batch of golden queries with expected results.
- Runs each query through the retrieval pipeline (same as `/search`).
- Computes and returns relevance metrics comparing retrieved results against expected results.
- This is a **testing/development tool** — not part of the runtime answer flow.

### Done When (Stage 6 — All Sub-Stages)

- `search` returns ranked, tenant-scoped chunks using ANN-optimized vector search.
- Reranking improves precision over raw RRF results (measurable via the evaluate endpoint).
- `ask` produces a grounded answer with citations, supports streaming (SSE) and multi-language responses.
- `ask` declines when sources are insufficient, never calls the LLM on an empty retrieval.
- `ask` never reaches another tenant's data.
- `evaluate` endpoint returns relevance metrics for golden queries.
- Hybrid retrieval beats pure vector and pure BM25 on the golden-query harness.
- Tenant-isolation test: tenant B cannot search/ask/evaluate against tenant A's knowledge_base.

---


## REST API Summary

The following is a complete list of REST endpoints that will be available upon completion of Phase 1 (Stages 1-6):

### 1. Tenant Management
* **`POST /api/v1/tenants`**
  * **Auth:** Open / Unauthenticated
  * **Purpose:** Create a new tenant. Returns the tenant ID and a one-time initial API key.
  * **Rate Limit:** 5 requests/hour/IP.

### 2. Model Configuration
* **`PUT /api/v1/tenants/{tenantId}/model-config`**
  * **Auth:** Tenant API Key
  * **Purpose:** Set the chat provider, chat model, embedding provider, embedding model, and credentials.
* **`GET /api/v1/tenants/{tenantId}/model-config`**
  * **Auth:** Tenant API Key
  * **Purpose:** Retrieve the current model configuration (credentials are never returned).

### 3. Knowledge Base Management
* **`POST /api/v1/knowledgebase`**
  * **Auth:** Tenant API Key
  * **Purpose:** Create a new knowledge base. The `embedding_dim` is auto-derived from the current tenant model config.
* **`GET /api/v1/knowledgebase`**
  * **Auth:** Tenant API Key
  * **Purpose:** List all knowledge bases for the authenticated tenant.
* **`PUT /api/v1/knowledgebase/{id}`**
  * **Auth:** Tenant API Key
  * **Purpose:** Update the name of a knowledge base (dimension cannot be changed).
* **`DELETE /api/v1/knowledgebase/{id}`**
  * **Auth:** Tenant API Key
  * **Purpose:** Delete a knowledge base, cascading to all documents and chunks.

### 4. Document Ingestion
* **`POST /api/v1/knowledgebase/{knowledgebaseId}/documents`**
  * **Auth:** Tenant API Key
  * **Purpose:** Upload a document (multipart). Returns `202 Accepted` and a job ID for async processing.
* **`GET /api/v1/jobs/{jobId}`**
  * **Auth:** Tenant API Key
  * **Purpose:** Poll the status of an async ingestion job (`PENDING`, `INDEXING`, `READY`, `FAILED`).
* **`GET /api/v1/documents/{documentId}/status`**
  * **Auth:** Tenant API Key
  * **Purpose:** Get the status of a specific document (maps to job status).

### 5. Retrieval and Answering
* **`POST /api/v1/knowledgebase/{knowledgebaseId}/search`**
  * **Auth:** Tenant API Key
  * **Purpose:** Perform hybrid retrieval (BM25 + Vector + RRF). Accepts `mode` (hybrid, vector, keyword) and `top_k`.
* **`POST /api/v1/knowledgebase/{knowledgebaseId}/ask`**
  * **Auth:** Tenant API Key
  * **Purpose:** Ask a question. Returns an LLM-generated answer with citations. Supports SSE streaming.
* **`POST /api/v1/knowledgebase/{knowledgebaseId}/evaluate`**
  * **Auth:** Tenant API Key
  * **Purpose:** Development endpoint. Evaluate retrieval quality against golden queries.

---

## Cross-Cutting Concerns

These apply to **every stage** without exception:

| Concern | Detail |
|---|---|
| **Tenant isolation** | Non-negotiable. Every read/write is `tenant_id`-scoped; never weaken, skip, or delete an isolation test — a failing one means the code is wrong. Each stage adds its own isolation tests. |
| **Errors** | RFC 7807 problem-details on every endpoint; all under `/api/v1`. Missing model config → `422`. Unsupported type → `415`. File too large → `413` (Stage 4 document uploads; the JSON control-plane endpoints get their own `413` too — see *Request Body Size Limit* in Stage 0 above). DB unreachable **mid-request** (not at startup) → `503` (`plan.md` §9, 2026-07-16 — corrects the note previously here, which claimed no "DB not ready" `503` contract was needed since fail-fast startup covers it; that reasoning only covered startup-time unavailability, not Postgres disappearing while the app is already serving traffic, a distinct and equally real scenario found by testing it directly). Unique-constraint violation (e.g. a losing concurrent-write race) → `409`; any other constraint violation (oversized input, etc.) → `400`; malformed path/query parameter type → `400` (`plan.md` §9, 2026-07-17 — see *Concurrency & Constraint-Violation Handling* in Stage 0 above; found live, 9 of 10 concurrent first-time `model-config` writes for one tenant crashed with an unhandled `500` before this). Request body over the global size limit → `413` regardless of endpoint, checked before Spring MVC parses anything (`plan.md` §9, 2026-07-17). |
| **Observability** | Correlation ID generated/propagated on every request and into async threads via `TaskDecorator`. |
| **Config & secrets** | Per-profile YAML; secrets only via env vars; never logged. Provider credentials Base64-encoded at rest (Stage 2). |
| **Done-gate** | A task is only done when `./mvnw clean verify` is green, the behavior is tested at the right level, and `implementation/memory.md` is updated. |

---

## Build Order (Milestones)

> **✅ Decision (Grooming #12):** **Breadth-first** build order (as originally drafted).

The stages are built in this order:

### Milestone 1 — Setup Path Works
**Stages 1 → 2 → 3** callable end-to-end (tenant, config, knowledge_base) with isolation.
- Tenant creation with API key
- Model config (Base64-encoded credential storage)
- knowledge_base CRUD (create, list, update, delete) with auto-derived `embedding_dim`
- Tenant-isolation tests for each stage

### Milestone 2 — Ingestion Happy Path
**Stages 4 → 5** for one simple format (e.g. `.txt`): upload → `202` → async chunk/embed/store → `READY`, polled via `/jobs/{id}`.
- Document upload with validation
- Basic ingestion pipeline (extract → chunk → embed → persist → index)
- Job state machine and polling
- Idempotency (content-hash short-circuit)
- Basic ingestion metrics

### Milestone 3 — All Formats Ingest
Extend **Stage 5a extractors** across every supported type including LLM-first PDF/image (up to 100 pages) + fallback.
- All 7 file-type categories fully working
- Redis caching for embeddings (7-day TTL) and OCR (24-hour TTL)
- Lucene ↔ Postgres reconciliation cron

### Milestone 4 — Optimized Retrieval
**Stage 6a** `search` with hybrid + RRF + ANN-optimized vector search.
- HNSW/IVFFlat ANN index strategy implemented
- Hybrid, vector-only, and keyword-only modes
- Proven better than either method alone

### Milestone 5 — Reranking + Answering
**Stage 6a+ and 6b** — reranking, `ask` with citations, budgeting, streaming (SSE), multi-language, and "I don't know".
- Open-source reranker integration (multilingual)
- SSE streaming answers
- Multi-language response support
- Context budgeting and citation generation

### Milestone 6 — Evaluation + Hardening
**Stage 6c** + cross-cutting hardening.
- Basic `evaluate` endpoint with relevance metrics
- Dimension validation end-to-end
- Failure recording and error contracts finalized
- Coverage ≥ 70% on ingestion, retrieval, and tenant modules

### Milestone 7 — SPI External Connectors (Interfaces Only)
After core RAG is fully working, build the **SPI interfaces** for external document sources.
- `DocumentSource` / `SourceDocument` interfaces finalized
- No concrete connector implementations (deferred to later)
- Ensures future connectors (GitHub, Notion, Confluence) can plug in without touching the pipeline core

---

## Acceptance Criteria

**"RAG App Done"** (subset of `PLAN.md` §1.9):

- [ ] All 7 supported file-type categories ingest end-to-end on fixtures.
- [ ] Tenant-isolation holds across search, ask, jobs, knowledge_base listing, and evaluate (no cross-tenant leakage).
- [ ] Hybrid retrieval beats pure vector and pure BM25 on the golden-query harness.
- [ ] A 100-page PDF ingests without blocking API responsiveness (LLM-first up to 100 pages).
- [ ] Provider switch (e.g. OpenAI ↔ Ollama) works by config change only, no code change.
- [ ] ≥ 70% coverage on ingestion, retrieval, and tenant modules.
- [ ] ANN-optimized vector search is functional (no full sequential scans).
- [ ] Reranking improves retrieval precision (measurable via evaluate endpoint).
- [ ] Streaming (SSE) answers work end-to-end.
- [ ] Multi-language responses work correctly.
- [ ] Credentials round-trip correctly through Base64 encode/decode and never leak in responses or logs.
- [ ] Lucene ↔ Postgres reconciliation cron detects and fixes drift.
- [ ] Basic ingestion metrics (jobs by state, failure counts) are exposed.
- [ ] Evaluate endpoint returns relevance metrics for golden queries.
- [ ] `./mvnw clean verify` is green.

---

## Resolved Grooming Decisions Summary

All grooming items from the original draft (plus later-added items #22–28) are now resolved:

| # | Item | Decision |
|---|---|---|
| 1 | Tenant-isolation tests | Folded into each stage (not a Stage-0 prerequisite) |
| 2 | API-key model | Single key per tenant; rotation/multiple keys deferred |
| 3 | Credential storage | Base64-encoded (obfuscation, not encryption) — superseded by `plan.md` §9 2026-07-16 |
| 4 | Model-config validation | Fail at ingest/ask time with proper status codes; don't persist failed entries |
| 5a | knowledge_base update/delete | In scope — full CRUD |
| 5b | `embedding_dim` | Auto-derived from embedding model, not passed by caller |
| 5c | Rate-limit defaults | 5/hour/IP confirmed |
| 6a | Upload caps | 20 MB file size, 100 pages LLM cap (increased from 30) |
| 6b | Idempotency | Short-circuit; new job per upload, no rerun mechanism |
| 7 | Lucene ↔ Postgres reconciliation | Basic cron included in this milestone |
| 8 | Ingestion metrics | Basic metrics (jobs by state, failure counts) included |
| 9 | Executor sizing | Core 4 / max 8 / queue 100 / CallerRunsPolicy confirmed |
| 10 | REST evaluate endpoint | Basic endpoint included in this milestone |
| 11 | Reranking & streaming | **Both in scope** — open-source multilingual reranker + SSE streaming |
| 12 | Build order | Breadth-first (as drafted) |
| 13 | External connectors | SPI-only; concrete implementations after core RAG is complete |
| 14 | pgvector ANN index | In scope — best/optimized search flow with HNSW/IVFFlat |
| 15 | `ChatOptions` contradiction | Generic `ChatOptions.builder()` is correct; sub-plan fixed |
| 16 | Missing FKs on `tenant_id` | Intentional — denormalized for performance, app-layer integrity |
| 17 | One config per tenant | Sufficient for this milestone |
| 18 | Bootstrap credentials | Reuse local-dev SUPERUSER; `DatabaseBootstrapRunner` is local/dev-profile only (needs elevated credentials). `AnnIndexBootstrapRunner` has its own separate flag and also runs in `test` — no such credential requirement, and `*IntegrationTest`s need the same HNSW indexes/`CHECK` constraints local/dev/prod have |
| 19 | Error contract (no model config) | `422 Unprocessable Entity` — "model config not set for tenant" |
| 22 | API Key Auto-Sync | Sync updated `model_configs` credentials to existing knowledge_base snapshots |
| 23 | knowledge_base Model Lock / Snapshot | Snapshot the global model config into a knowledge_base-specific table on creation to prevent semantic drift |
| 24 | ANN Index Strategy | Use "Sparse Dimension Columns" (384, 512, 768, 1024, 1536, 3072) with HNSW indexes created idempotently by the startup `AnnIndexBootstrapRunner` (no Flyway, no per-tenant/dynamic runtime DDL) |
| 25 | knowledge_base Limit | Maximum of 10 Knowledge Bases per tenant |
| 26 | Retry via re-upload on FAILED | Duplicate upload against a `FAILED` job resets and re-dispatches the same document/job in place, rather than staying permanently stuck |
| 27 | Reranker model correction | Replaced English-only `ms-marco-MiniLM-L-6-v2` example with genuinely multilingual `BAAI/bge-reranker-v2-m3` |
| 28 | MCP `ingest` input shape | Accepts file bytes or raw text only, normalized into the same pipeline as REST upload; URL/"reference" ingestion explicitly out of scope, deferred to external connectors (§2/Milestone 7) |
| 29 | `embedding_dim` derivation mechanism | Live probe call (one `embed()` call against a fixed test string at KB creation), not a static model→dimension table — required to support arbitrary Ollama model names; probe failure returns `422` |
| 30 | `documents.status` / `ingestion_jobs.status` duplication | Intentional read-optimized denormalization: `ingestion_jobs` is authoritative, `documents.status` is a same-transaction mirror for join-free listing/status reads |
| 31 | MCP `create_knowledge_base` params corrected | Removed the erroneous `embedding_dim` caller param from `mcp_plan.md` — it must match this plan's Grooming #5b (server-derived only) |
| 32 | Reconciliation cron vs. job status | Cron compares Postgres `chunks` to the Lucene index directly (not via `ingestion_jobs.status`); a job `FAILED` only because the post-commit Lucene write threw gets its missing entries indexed and flipped back to `READY` |
| 33 | Flyway removed; Hibernate `ddl-auto` owns schema (`plan.md` §9, 2026-07-16) | No migration files. `ddl-auto` is `update` (`local`/`dev`), `create-drop` (`test`), `validate` (`prod`). `DatabaseBootstrapRunner` still creates the Postgres role/database/`vector` extension at startup (`ddl-auto` can't); `AnnIndexBootstrapRunner` idempotently adds HNSW indexes/`CHECK` constraints on every startup. The old "app starts with zero DB present" hard rule is dropped in favor of standard Spring Boot fail-fast startup |
| 34 | `embedding_3072` is `halfvec(3072)`, not `vector(3072)` | Empirically verified: pgvector's HNSW/IVFFlat indexes both hard-cap at 2000 dimensions on `vector`. `halfvec` is pgvector's own native answer (half-precision, cap raised to 4000), chosen over dropping 3072 support or shipping it with no ANN index. Uses `SqlTypes.VECTOR_FLOAT16` (`hibernate-vector`) and a `halfvec_cosine_ops` HNSW index, not `vector_cosine_ops` |
| 35 | Constraint-violation error mapping | `ApiExceptionHandler` maps `DataIntegrityViolationException` by SQLSTATE — `23505` (unique violation) → `409 Conflict`, everything else (length/check/not-null) → `400 Bad Request`. Found live: 9 of 10 concurrent first-time `model-config` writes for one tenant crashed with an unhandled `500` before this existed (see *Concurrency & Constraint-Violation Handling*, Stage 0) |
| 36 | Malformed path/query parameter type | `ApiExceptionHandler` maps `MethodArgumentTypeMismatchException` → `400 Bad Request` — a malformed `{tenantId}` (e.g. non-UUID) previously leaked a raw `500` from Spring MVC's own argument-resolution failure, before any controller code ran |
| 37 | Cache-eviction transaction timing | `ChatModelFactory`/`EmbeddingModelFactory`'s `ModelConfigUpdatedEvent` listeners use `@TransactionalEventListener(phase = AFTER_COMMIT)`, not a plain `@EventListener` — the publishing transaction (`ModelConfigService.configureModel()`) hadn't committed yet when eviction fired, letting a concurrent cache read repopulate stale, pre-update data that would then never be evicted again |
| 38 | Rate-limiter atomicity | `TenantCreationRateLimitFilter`'s `INCR` + conditional `EXPIRE` runs as a single atomic Lua script (`RedisScript`), not two separate round trips — a transient failure landing only on the `EXPIRE` call would otherwise leave a key with no TTL, permanently rate-limiting that IP. Redis 7's native `EXPIRE ... NX` isn't available (this environment runs Redis 6.2.18) |
| 39 | Global request body size limit | `RequestBodySizeLimitFilter` (`com.synapsemcp.common`) enforces a **1MB** limit on every request (`synapsemcp.request.max-body-bytes`), rejecting with `413` before Spring MVC parses anything. Found live: a 5MB body was fully accepted/parsed on both the open tenant-creation and authenticated model-config endpoints. Bounded-reads the body rather than trusting `Content-Length` alone — a real client (`TestRestTemplate`'s Apache HttpClient5 factory) sends every request chunked with no `Content-Length` header at all, which a header-only check would have silently missed. **Must be exempted for Stage 4's 20MB document-upload endpoint when built** (see Stage 4 above) — not yet a blocker since that endpoint doesn't exist |
| 40 | Authorization-before-validation ordering | `TenantOwnershipInterceptor` (a `HandlerInterceptor`, registered against `/api/v1/tenants/{tenantId}/**`) enforces own-tenant-only access in `preHandle`, before Spring MVC's argument resolution runs `@Valid` body validation. A per-controller-method check (the original `ModelConfigController.requireOwnTenant()`) can never win this race regardless of parameter order, since `@Valid` validates during argument resolution, before the method body ever executes — found live as a cross-tenant caller getting `400` (revealing the required-field shape) instead of `403` |

---

*Finalized from the draft `RAG_APP_SUBPLAN.md` with all grooming decisions resolved. This is the authoritative RAG-app plan.*
