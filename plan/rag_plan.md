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
> - **Third finding, a stage later still (Stage 3 audit):** Postgres row locks have the same "hidden unbounded default" shape as the two timeouts above, but no fix had ever been needed until Stage 3 introduced this codebase's first pessimistic lock (`KnowledgeBaseService`'s `SELECT ... FOR UPDATE` on the tenant row, for the 10-KB-per-tenant race guard). Postgres's own `lock_timeout` default is `0` — disabled, wait forever. Verified live: manually held a lock on a tenant row for 25s via a separate `psql` session and fired a concurrent `POST /api/v1/knowledgebase` for that same tenant — it waited the **entire 25-second hold duration** with no timeout at all, tying up a Tomcat worker thread and a pooled HikariCP connection (pool size 10, Session 15's finding) for the whole wait. Fixed with `spring.datasource.hikari.connection-init-sql: SET lock_timeout = '3s'` (env-var-overridable via `DB_LOCK_TIMEOUT`, every profile) — applied to every pooled connection via HikariCP's `connectionInitSql`, not just this one call site, so it's a defensive backstop for any current or future row lock in the app. When it fires, Postgres's SQLSTATE `55P03` translates to `org.springframework.dao.CannotAcquireLockException`, mapped by `ApiExceptionHandler` to `503 Service Unavailable` with a `Retry-After: 3` header — distinct from the `CannotCreateTransactionException`/`DataAccessResourceFailureException` mapping above (the database is up and reachable here, just contended by another transaction, a transient/retry-able condition rather than an outage). Re-verified live after the fix: the identical reproduction now fails in ~4.7s instead of hanging the full lock-hold duration.
> - **Fourth finding, one stage of scrutiny later still (a sixth "final validation" pass):** the same "hidden unbounded default" shape, this time in the external provider HTTP clients themselves - `ChatModelFactory`/`EmbeddingModelFactory` (Stage 2) never set an explicit timeout on any cloud provider client. Confirmed via `javap` decompilation, not guessed: OpenAI's and Anthropic's Java SDKs both default to a **10-minute** request timeout (`com.{openai,anthropic}.core.Timeout`'s getter fallback logic - `connect`: 1 min, `read`/`write`/overall `request`: 10 min if unset); Google GenAI's underlying `OkHttpClient` is worse - `ApiClient` sets `connectTimeout`/`readTimeout`/`writeTimeout` to `Duration.ofMillis(0)` (OkHttp's convention for **no timeout at all**) unless `HttpOptions.timeout()` is explicitly present, which it never was. Since a construction/probe call (Stage 3's `EmbeddingModelFactory` dimension probe, and every future Stage 5/6 embed/chat call) holds a Tomcat worker thread for the entire call, a slow/unresponsive provider could hang a request for up to 10 minutes (OpenAI/Anthropic) or genuinely forever (Google GenAI). Fixed with a new `synapsemcp.provider.timeout-seconds` property (default **30**, both factories) applied via each SDK's own hook - `httpClientBuilderCustomizer(builder -> builder.timeout(duration))` for OpenAI/Anthropic (`SpringAiOpenAiHttpClient$Builder`/`SpringAiAnthropicHttpClient$Builder`, both confirmed via `javap`), and `Client.builder().httpOptions(HttpOptions.builder().timeout(millis).build())` for Google GenAI - the embedding factory's `google-genai` case switched from `GoogleGenAiEmbeddingConnectionDetails.builder().apiKey(...)` to `.genAiClient(...)` to reach the same hook the chat factory already used. Ollama has no equivalent builder-exposed timeout setter (checked via `javap`) and was deliberately left as-is - a self-hosted, typically low-latency dependency, not a cloud call across the open internet. Re-verified live with a real OpenAI call (this environment has a real `OPENAI_API_KEY`, per the Stage 2/3 credential-fallback finding above) that the new code path still succeeds normally in ~2s; forcing the timeout to actually fire live wasn't practical without a controllable slow endpoint (unlike stopping a local service), so the enforcement itself rests on trusting OkHttp's own well-established timeout semantics rather than an independent live reproduction - a lower confidence tier than this section's other three findings, flagged explicitly as such rather than presented with equal certainty.

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
| `knowledge_base_id` | UUID | NOT NULL (**no FK** — intentional, same rationale as `tenant_id`) — added `plan.md` §9 2026-07-18, see below |
| `document_id` | UUID | NOT NULL, FK → `documents(id)` **ON DELETE CASCADE** |
| `chunk_index` | INT | NOT NULL |
| `content` | TEXT | NOT NULL |
| `embedding_*` | `vector(*)` for `384`–`1536`, `halfvec(3072)` for `3072` | **Sparse Dimension Columns** (`384`, `512`, `768`, `1024`, `1536`, `3072`). Only the column matching the knowledge_base's `embedding_dim` is populated. `embedding_3072` is `halfvec`, not `vector` — pgvector's HNSW/IVFFlat indexes hard-cap at 2000 dimensions, and `halfvec` is pgvector's native way to index above that (see *Schema Realization* above). |
| `metadata` | JSONB | NOT NULL, default `'{}'` |

Indexes: `idx_chunks_tenant_doc` on `(tenant_id, document_id)`; `idx_chunks_tenant_kb` on `(tenant_id, knowledge_base_id)`. Plus HNSW ANN indexes on every sparse dimension column (e.g., `idx_chunks_emb_1536`) created idempotently by `AnnIndexBootstrapRunner` on every startup (`CREATE INDEX IF NOT EXISTS`) — a fixed, static set of six indexes, no per-tenant or otherwise dynamic runtime DDL.

> **✅ Decision (Grooming #73 — `knowledge_base_id` denormalized onto `chunks`, `plan.md` §9 2026-07-18):** Found live via `EXPLAIN ANALYZE` against a realistic multi-tenant dataset (35,000 chunks across 16 tenants) that `VectorSearchService`'s original query — which joined `documents` to reach `knowledge_base_id`, since `chunks` didn't have it directly — made Postgres's planner never once consider the HNSW ANN index, at any selectivity tested: always a full sequential scan + in-memory sort, directly violating this plan's own "ANN-optimized vector search... no full sequential scans" acceptance criterion. Fixed by denormalizing `knowledge_base_id` directly onto `chunks` (same rationale as `tenant_id`, Grooming #16) and rewriting the query to filter on `chunks` alone, no join. Confirmed live this removes the structural blocker: the planner now uses the new `idx_chunks_tenant_kb` B-tree index to fetch only the matching knowledge_base's rows (instead of scanning the whole table), then sorts that smaller candidate set by vector distance. **Honestly not a complete fix, and not claimed as one:** at every selectivity tested locally (14% and 100% of the table), the cost-based planner still preferred the B-tree-then-sort path over the HNSW index itself, since brute-force-sorting a few thousand rows is genuinely cheap. Removing the join is a *necessary* precondition for the planner to ever pick the HNSW index (confirmed - it structurally couldn't before), not a *sufficient* one - whether it actually does so in production depends on real selectivity (a knowledge_base's share of the whole `chunks` table), which will be much lower than this local test's worst case once there are many tenants. `hnsw.iterative_scan = relaxed_order` is also now set on every pooled connection (`spring.datasource.hikari.connection-init-sql`, every profile) so that whenever the planner does choose the HNSW path for a filtered query like this one, it searches until it genuinely finds `topK` matches rather than silently returning fewer/zero (confirmed live: a plain, non-iterative filtered HNSW scan can return 0 rows despite real matches existing elsewhere in the vector space). Revisit with real production-scale data if/when vector search latency becomes a measured problem.

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

> **✅ Decision (implementation, `plan.md` §9 2026-07-17 — credential presence required for key-requiring providers, found during a Stage 3 audit pass):** `ModelConfigService.configureModel()` now rejects a blank `chatApiKey`/`embeddingApiKey` with `422` whenever the corresponding provider is `openai`, `anthropic`, or `google-genai` (`ollama` needs no credential and stays exempt). Found live, not theorized: this environment happens to have a real `OPENAI_API_KEY` set in the server process's own environment, and the OpenAI Java SDK silently falls back to reading it whenever the tenant's own `embeddingApiKey` is blank (`com.openai.core.ClientOptions` - confirmed by configuring exactly this, watching a knowledge_base creation succeed using the server's own key, then fail once that env var was unset). On any real deployment where an operator's own provider API key happens to be present in the process environment, this would silently let every tenant who leaves a credential blank use - and get billed against - the operator's own key, with zero visibility or consent. Rejecting the blank credential at write time closes the gap at the source, before a tenant can ever reach a state where an SDK's fallback could trigger. Checked Google GenAI's SDK via decompilation and found no equivalent fallback (matches the `Assert.notNull` finding from Stage 2's original implementation, above); Anthropic's fallback behavior is inconclusive from static inspection but not currently reachable anyway (embedding-only call sites in this codebase, and Anthropic has no embeddings API).

### Done When

- A tenant can set and retrieve a config.
- A request-time factory resolves the correct provider/model from it without an app restart.
- Credentials never leak in responses or logs.
- Credentials round-trip correctly through Base64 encode/decode (stored encoded, decoded and usable by `ChatModelFactory`/`EmbeddingModelFactory` at resolution time).
- Tenant-isolation test: tenant B cannot read or modify tenant A's config.
- A blank credential for a provider that requires one (`openai`/`anthropic`/`google-genai`) is rejected with `422` at write time; `ollama` needs no credential and is unaffected.

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
>
> **✅ Decision (implementation, `plan.md` §9 2026-07-17 — probe implementation detail):** `EmbeddingModel.dimensions()` (a Spring AI default interface method, verified via `javap -c` decompilation before use) already does exactly this - calls `embed("Test String")` and returns the array length. `KnowledgeBaseService` calls this directly rather than hand-rolling the same probe-and-measure logic, wrapping only the call itself in a `try/catch` to map any thrown exception to the `422` above.

> **✅ Decision (implementation, `plan.md` §9 2026-07-17 — delete cascade, user-confirmed):** `documents.knowledge_base_id`'s FK gained `@OnDelete(action = OnDeleteAction.CASCADE)` (previously had none - Stage 0.5's original schema only cascaded `chunks.document_id`/`ingestion_jobs.document_id` FROM `documents`). Since those two already cascade, this one addition makes a knowledge_base delete cascade the entire tree (KB → documents → chunks + ingestion_jobs) at the DB level in a single statement, satisfying this stage's "delete cascades to documents and chunks" requirement with no application-layer explicit deletion. **`ddl-auto: update` does not retrofit this onto an already-existing `local`/`dev` database** - confirmed live (`confdeltype` stayed `a`/NO ACTION after restart, not `c`/CASCADE) - a fresh schema (`DROP DATABASE` + restart, or `test`'s `create-drop`) is required to pick it up. `documents`/`chunks`/`ingestion_jobs` retain their existing lack of a `tenant_id` FK (Grooming #16, unaffected by this change) - only the `knowledge_base_id` FK gained the cascade.

> **✅ Decision (implementation, `plan.md` §9 2026-07-17 — 10-KB-limit concurrency, user-confirmed):** The count-then-insert check for the 10-knowledge-base-per-tenant limit has no unique constraint to catch a losing race the way `model_configs.tenant_id` does, so `KnowledgeBaseService.createKnowledgeBase` takes a **pessimistic row lock** on the tenant (`TenantRepository.lockById`, `SELECT ... FOR UPDATE`) for the duration of the count-check + insert, serializing concurrent creates per tenant. The embedding-dimension probe (a live network call) deliberately runs **before** this lock is acquired and outside any transaction, so a slow/hanging provider call never holds a pooled DB connection or the tenant row lock idle. Verified live: 15 concurrent create requests against one tenant resulted in exactly 10 successes, never more.

> **✅ Decision (implementation, `plan.md` §9 2026-07-17 — cross-tenant access response code, user-confirmed):** Unlike `ModelConfigController` (which returns `403 Forbidden` for a `{tenantId}`-in-path mismatch), knowledge_base endpoints identify the resource by its own opaque `{id}` with no tenant id in the URL at all - so `TenantOwnershipInterceptor`'s wildcard registration doesn't apply here. A knowledge_base belonging to a different tenant returns **`404 Not Found`**, identical to a genuinely nonexistent id - the caller can never distinguish "not yours" from "doesn't exist," preventing UUID enumeration against other tenants' resources. Enforced via `KnowledgeBaseRepository.findByIdAndTenant_Id(id, tenantId)` inside the service, not a shared interceptor.

> **✅ Decision (implementation, `plan.md` §9 2026-07-17 — probe try/catch scope, found during an audit pass):** `KnowledgeBaseService.probeEmbeddingDimension` wraps both `embeddingModelFactory.getEmbeddingModel(tenantId)` (client construction) and `.dimensions()` (the network call) in the same `try/catch` - an earlier version only wrapped the latter. Found live: a missing-credential failure for a provider requiring one throws at *construction* time (e.g. OpenAI's SDK: `IllegalStateException: At least one credential source must be specified`), not inside `dimensions()`, so the narrower try/catch let it leak as an unhandled `500` instead of this method's intended `422`. An already-thrown `ApiException` (e.g. the unrelated "model config not set" case, not expected to be reachable here since it's pre-checked earlier in `createKnowledgeBase`, but handled defensively) passes through unwrapped rather than being double-wrapped with a less precise message.

> **✅ Decision (implementation, `plan.md` §9 2026-07-17 — cascade verified transitively, found during an audit pass):** The original cascade-delete test (Session 17) only verified a `Document` row disappeared after a knowledge_base delete, never the `Chunk`/`IngestionJob` rows two levels down - which was the actual point of the `Document.knowledgeBase` `@OnDelete(CASCADE)` addition above (those two already cascade FROM `documents`, so the fix's whole value is in the transitive chain, not just the first level). Extended the test to persist one row at each level and confirm all three, plus the `KnowledgeBaseModelConfig` snapshot itself, are gone after delete - passed cleanly, confirming the cascade genuinely works end to end, not just at the level originally checked.

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

# Stage 4 — Upload Document ✅ Implemented (`plan.md` §9, 2026-07-17)

**Flow step [4].** Upload a document into a knowledge_base by `knowledge_base_id`. This is the *entry* to ingestion; the heavy work is Stage 5 (async, implemented — `IngestionPipelineService.run(jobId, content)` now runs the full pipeline; Grooming #56 records the deliberate stub this superseded).

| Aspect | Detail |
|---|---|
| Endpoint | `POST /api/v1/knowledgebase/{knowledgebaseId}/documents` (multipart) |
| Response | **`202 Accepted` + job id** when new pipeline work is actually triggered (fresh upload, or a `FAILED` job reset-and-redispatched) — **`200 OK`** when the upload is a pure idempotency no-op against an existing `PENDING`/`INDEXING`/`READY` job, nothing new happens (user-confirmed distinction, Grooming #58) — never blocks on processing either way |
| Sync checks | Content-type validated **synchronously** via real `Tika.detect(bytes, filename)` calls against a 15-MIME-type allowlist (415 on unsupported type before any async work); size cap (20 MB → 413) enforced by Spring's own multipart resolver (`spring.servlet.multipart.max-file-size`), not a hand-rolled check — Boot 4.1's own default (1MB/10MB, verified via `javap`) had to be raised explicitly or it would have silently shadowed this cap first (Grooming #57). `RequestBodySizeLimitFilter`'s global 1MB limit is exempted via `shouldNotFilter` (same Grooming #57 entry, closes the TODO first flagged Stage 0) |
| Page limit | Up to **100 pages per file** for LLM-first extraction (`MAX_LLM_PAGES` cost guardrail) — a Stage 5 concept (extraction-time), not a Stage 4 sync check |
| Type detection | Apache Tika content-sniffing — never trust the extension alone. Verified empirically for all 7 categories, including two magic-byte ambiguities Tika resolves via the filename hint: legacy `.doc`/`.xls`/`.ppt` (shared OLE2 header) and `.docx`/`.xlsx`/`.pptx` (shared bare-ZIP header) |
| Idempotency | Content-hash short-circuit: re-uploading identical content to the same knowledge_base returns the existing job (`200`) unless it's `FAILED`, in which case it's reset and re-dispatched (`202`, Grooming #26) |

**Supported formats (Phase 1):** the 7 direct-upload categories — PDF · images · Word · Excel · PowerPoint · HTML · text/markdown. Full extension list in *Supported Document Sources* above. External sources (GitHub / Notion / Confluence) are SPI-only here.

### Implementation

Synchronous validation, then async processing — the HTTP call returns `202` immediately and never blocks on extraction.

- **MIME detection (`FileTypeDetector`).** `org.apache.tika.Tika.detect(bytes, filename)` sniffs the actual bytes, not the extension — HTML bytes named `.txt` still resolve to `text/html`. Unsupported types are rejected synchronously (`415`) before any async work starts.
- **Entry-point-agnostic ingestion (Grooming #28).** The synchronous validation + async dispatch described in this section is shared by **both** entry points: the REST multipart upload (this endpoint) and the MCP `ingest` tool (`mcp_plan.md` Stage 2), which calls the same underlying service directly rather than looping back through HTTP (per `mcp_plan.md` Grooming #2). The MCP tool normalizes its two accepted input shapes down to the same `(byte[] content, String mimeType, String filename)` triple this service expects before handing off: raw file bytes go through the same Tika-sniffing path above; a raw `text` field skips sniffing entirely and is passed with `mimeType` hard-set to `text/markdown` (since its nature is already known, not guessed). From this point on, both entry points are indistinguishable to the pipeline.
- **Idempotency (`ContentHasher.sha256Hex(bytes)`).** If a `Document` already exists for `(tenantId, knowledgeBaseId, contentHash)`, its status decides what happens (Grooming #26):
  - `PENDING` / `INDEXING` / `READY` → the existing job is returned unchanged, re-uploading identical content is a no-op.
  - `FAILED` → the same document/job rows are reset (`status → PENDING`, `error_detail`/`stage` cleared) and re-dispatched onto the pipeline, giving the caller a working retry path through the same upload action, without violating `ingestion_jobs.document_id UNIQUE` (Stage 0.5) by minting a second job for the same document.
  - **Concurrency (Grooming #59, supersedes this section's original "independent transactions" wording below):** the idempotency check-then-write is wrapped in a pessimistic lock on the `knowledge_base` row (`KnowledgeBaseRepository.lockById`, the same pattern already used for the 10-KB-per-tenant limit), acquired only after file reading/hashing/MIME validation complete - found live that without it, concurrent uploads of identical new content raced past the check with no synchronization, and concurrent retries of the same `FAILED` job all dispatched simultaneously.
- **Async dispatch.** `Document(PENDING)` and `IngestionJob(PENDING)` are now committed together inside that same locked transaction (not the independent, unwrapped transactions originally planned here - superseded by Grooming #59's live-found race, above). `IngestionPipelineService.run(...)` still only fires on the `@Async("ingestionExecutor")` pool strictly *after* the locked transaction commits - the "worker thread can never read absent/uncommitted rows" guarantee this section originally described is preserved, just enforced by ordering the dispatch call after commit rather than by splitting the writes into separate transactions. `ContextPropagatingTaskDecorator` copies the `TenantContext` / correlation-ID ThreadLocals across the pool boundary.
- **Extraction dispatch (`DocumentExtractionService`).** Picks the **first** `DocumentExtractor` whose `supports(mimeType)` is true (see Stage 5a for the extractor set).
- **Job state machine.** `PENDING → INDEXING → READY | FAILED`, recording the failing `IngestionStage` + exception message on failure.
- **Error handling:** If upload/ingestion fails for any valid reason during the synchronous phase, **do not persist** the document or job entry in the DB — return a proper error response with an appropriate status code.

### Decisions

> **✅ Decision (Grooming #6a — Upload caps):** Single file size cap is **20 MB** (413 on exceed). LLM-first extraction supports up to **100 pages per file** (increased from the draft's 30-page cap). Beyond 100 pages, falls back to library-only extraction.

> **✅ Decision (Grooming #28 — MCP `ingest` Input Shape):** `mcp_plan.md`'s `ingest` tool was originally described as accepting "content or a reference" without defining either term concretely, and "reference" implied URL/external-source ingestion that contradicts this plan's own SPI-only scope decision for external connectors (§2, Milestone 7). Resolved: the MCP `ingest` tool accepts exactly two input shapes — file bytes (`filename` + `content_base64`, routed through the same Tika-sniffing path as the REST upload above) or raw text (a `text` field, wrapped as a synthetic `.md` document with `mimeType` hard-set to `text/markdown`, bypassing sniffing since the content type is already known). Both normalize to the same `(byte[], mimeType, filename)` triple before entering the shared pipeline described in this section. URL/reference-based ingestion is explicitly **not** part of this tool's scope — it remains an external-connector concern, deferred exactly where §2/Milestone 7 already deferred it.

> **✅ Decision (Grooming #6b — Idempotency):** Idempotency is **short-circuit** (not versioning). Re-uploading identical content returns the existing job. Each new (non-duplicate) file upload creates a **new job** — there is no "rerun job" mechanism. A new upload with different content creates a new document + new job.

> **✅ Decision (Grooming #26 — Retry via Re-upload on FAILED):** Grooming #6b's short-circuit, as originally written, returns the existing job **regardless of its status** — meaning a legitimately-`FAILED` job (e.g. a transient provider outage during extraction) becomes permanently stuck: re-uploading the identical file forever returns the same `FAILED` job, and no retry endpoint exists anywhere in the plan. Fixed by making the short-circuit status-aware: `PENDING`/`INDEXING`/`READY` still short-circuit as a pure no-op (Grooming #6b's original behavior, unchanged); a `FAILED` existing document/job is instead **reset in place** (`documents.status` and `ingestion_jobs.status → PENDING`, `ingestion_jobs.error_detail`/`stage` cleared) and immediately re-dispatched onto the ingestion pipeline, returning the same `documentId`/`jobId` as before. This requires no new endpoint and no schema change — it preserves the `ingestion_jobs.document_id UNIQUE` invariant (still exactly one job per document, Grooming #6b's "no rerun job mechanism" is unaffected since this reuses the *same* job rather than creating a new one) while giving users a working recovery path through the same upload action they'd naturally retry with.

### Done When

All verified live against a real running app, not just the automated test suite (`plan.md` §9, 2026-07-17):

- ✅ A valid upload returns `202` + job id in well under a few seconds even for large files.
- ✅ Unsupported types are rejected up front (`415`).
- ✅ Files exceeding 20 MB are rejected (`413`) - confirmed with a real 21MB file (RFC 7807 body, zero unhandled-exception log lines) and a real 15MB legitimate file (`202`).
- ✅ A duplicate upload against a `PENDING`/`INDEXING`/`READY` job returns the prior job unchanged (no-op, `200`).
- ✅ A duplicate upload against a `FAILED` job resets and re-dispatches the same document/job (same `documentId`/`jobId`), giving the caller a working retry path (Grooming #26).
- ✅ Failed synchronous validation does not leave orphaned rows in the DB.
- ✅ Tenant-isolation test: tenant B cannot upload to tenant A's knowledge_base (`404`) - also verified under genuine concurrent multi-tenant load, both via API responses and direct DB inspection (Grooming #59's fix session).
- ✅ Concurrency test: N simultaneous uploads of identical new content never produce a raw `409` - exactly one `202` winner, the rest gracefully `200` with the same job info (Grooming #59).
- ✅ Concurrency test: N simultaneous retries of the same `FAILED` document dispatch the pipeline exactly once, not once per racing request (Grooming #59).

---

# Stage 5 — Async Ingestion Pipeline

**Flow step [5] — the notebook's "async transaction during document upload, create proper chunk generation and store them in DB."**

This is the ingestion pipeline. It runs on a dedicated bounded executor **after** the `202` returns, with `TenantContext` + correlation ID propagated in.

### Pipeline Stages

```
extract → chunk → embed (batched) → persist → index (Lucene) → mark READY
```

Job state machine: `PENDING → INDEXING → READY | FAILED` (`ingestion_jobs`, with per-stage `stage` for observability). `ingestion_jobs` is the authoritative record of this state; `documents.status` is written to the identical value in the **same transaction** at every transition (Grooming #30) — a read-optimized mirror only, never updated independently.

> **✅ Decision (Grooming #65 — Content delivery, no blob storage):** Stage 4 never persists the uploaded file's raw bytes anywhere, only metadata — a gap invisible until this stage was actually wired together. `IngestionPipelineService.run(jobId, content)` takes the bytes directly from the still-in-memory upload request rather than reading them back from any durable store; no new blob-storage layer was added. Consistent with this plan's existing hash-based idempotency (Grooming #26 already retries a `FAILED` job via re-upload, not server-side replay) at the cost that a job's content is lost if the JVM restarts between the upload committing and the async task running — that job then stays `PENDING` until the client re-uploads.

### Executor Configuration

> **✅ Decision (Grooming #9):** Executor defaults are confirmed: **core 4 / max 8 / queue 100 / `CallerRunsPolicy`**.

---

## 5a. Extract (Text Out of the File)

`DocumentExtractionService` dispatches to the first `DocumentExtractor` whose `supports(mimeType)` is true. Two are LLM-first, three are library-only:

- **`PdfExtractor` (LLM-first).** Renders each page with PDFBox `PDFRenderer` at 150 DPI, batches **5 pages per vision call** to the tenant's chat model, concatenates. Falls back to `PDFTextStripper` on error/blank, or **skips the LLM path entirely above 100 pages** (`MAX_LLM_PAGES` cost guardrail). Redis-cached 24 h by content hash.
- **`ImageExtractor` (LLM-first).** LLM vision transcription first (converting BMP/TIFF → PNG), falling back to **RapidOCR** (pure-JVM ONNX PP-OCRv4) on error/blank. Redis-cached 24 h.
- **`OfficeExtractor` (POI), `HtmlExtractor` (jsoup), `PlainTextExtractor`** — library-only, no LLM path, **implemented**. `OfficeExtractor`'s decompression-bomb TODO (found during a Stage 4 audit pass) needed no extra code: confirmed via `javap` that Apache POI's `ZipSecureFile` (used internally by every OOXML reader path) already defaults `MIN_INFLATE_RATIO = 0.01` (rejects >100x expansion), `MAX_ENTRY_SIZE` ≈ 4GB, and `MAX_FILE_COUNT = 1000`, active automatically for any file opened through POI's standard APIs. Structure preservation (heading styles → `#` lines, sheet names → `# SheetName`) is implemented for `.docx`/`.xlsx`/`.pptx`; legacy `.doc`/`.ppt` fall back to POI's own plain-text extraction utilities, a deliberate scope boundary given their comparative rarity.
- Records which extractor produced the final text (`documents.extractor_name`, e.g. `PdfExtractor(pdfbox-fallback)`).

> **✅ Decision (Grooming #61 — Scope narrowed, LLM/OCR extractors deferred):** User confirmed (recommended option): the core pipeline above is built now using only the three library-only extractors. `PdfExtractor` and `ImageExtractor` (both LLM-first, the latter also needing a pure-JVM ONNX OCR runtime) are deferred to a dedicated follow-up session — genuinely higher-risk, new dependencies rather than bundled into an already-large stage. A document whose MIME type only they would support currently fails at the `extract` stage via `DocumentExtractionService`'s `UnsupportedOperationException`, caught like any other stage failure and recorded to `error_detail` — the pipeline's ordinary error contract, not a special case.
>
> **✅ Superseded (Grooming #79):** `PdfExtractor`/`ImageExtractor` are now implemented, full plan spec, both LLM-vision-first with a library fallback. See Grooming #79 below.

---

## 5b. Chunk (Proper Chunk Generation)

Strategy-based (`ChunkingStrategy` SPI) with an `@Order` priority chain and a size-adaptive shortcut. `DocumentChunkingService` picks the first strategy that `supports(...)`.

- **Token estimation (`TokenEstimator`).** `ceil(text.length / 4.0)` — the chars-per-token ≈ 4 rule of thumb; deliberately approximate since no provider is fixed at chunk-design time.
- **Single-chunk shortcut.** Returns one chunk if `estimateTokens(text) < windowTokensFor(totalTokens) × 1.2`, regardless of format (threshold derived from the selected window, never a flat constant).
- **`TableAwareChunkingStrategy` (`@Order(1)`, spreadsheet MIME only).** Groups adjacent rows into ~512-char windows on **row boundaries**, repeating the `# SheetName` heading in each chunk. Runs first because spreadsheet text also contains `#` lines that would otherwise trip the structure strategy.
- **`StructureAwareChunkingStrategy` (`@Order(2)`, content-based).** Triggered by any `^#{1,6}\s+.*$` heading line (works for Markdown/HTML/Word uniformly). Splits at each heading, maintains a heading-level stack for `headingPath` metadata, and skips flushing heading-only chunks.
- **`FixedSizeChunkingStrategy` (`LOWEST_PRECEDENCE`, universal fallback).** Sliding window with overlap, 3-tier dynamic scaling by total estimated tokens: `<1,000` → 256 / 10% · `1,000–50,000` → 512 / 15% (default) · `>50,000` → 1,024 / 20%.
- Chunk metadata (strategy, position, `headingPath`) persisted for later citation.

> **✅ Decision (Grooming #62 — `windowTokensFor` formula):** Undefined elsewhere in this section. User confirmed the recommended interpretation: identical to `FixedSizeChunkingStrategy`'s own 3-tier table above — keeps the single-chunk shortcut consistent with the fallback strategy's actual window size rather than an independently-chosen constant.

---

## 5c. Embed

- Embedding model resolved from the knowledge_base's `knowledge_base_model_configs` snapshot, **not** the global tenant config. Batch every cache-miss into one call.
- Redis embedding cache: key `sha256(provider:model:normalized_text)`, TTL 7 days. **Fail-open** (Grooming #68, found live via a real simulated Redis outage, `plan.md` §9 2026-07-17): a Redis read/write failure degrades to "treat as a cache miss" / "skip caching this result" rather than failing the job — this cache is a pure performance optimization and must never gate ingestion, matching this project's established Redis-fail-open convention everywhere else it appears.
- **Sparse Column Mapping:** Based on the knowledge_base's `embedding_dim` (e.g., 1536), the ingestion pipeline saves the vector into the corresponding sparse column (e.g., `embedding_1536`), leaving the other dimension columns `NULL`. Mismatched dimensions fail the job.

---

## 5d. Persist + Index

- Chunks committed atomically to Postgres (`chunks.embedding` via Hibernate `SqlTypes.VECTOR`); a mid-batch failure leaves no partial chunks. **Idempotent replace, not append** (Grooming #67, found live, `plan.md` §9 2026-07-17): any chunks already committed for the document from a previous attempt are deleted in the same transaction before the new set is inserted — a document can reach `FAILED` with its chunks already durable (persist succeeded, the Lucene write then threw) and get retried via Grooming #26's re-upload path, which re-runs this step a second time; without this, a retry silently doubled the chunk count instead of replacing it.
- Lucene BM25 index written **only after** the Postgres commit (consistency rule); one index directory per knowledge_base.
- On success → job `READY`. On any exception → caught, stage + error recorded to `ingestion_jobs.error_detail`, job `FAILED`. Includes a document with no real extractable text (Grooming #69) — extraction succeeding but producing only blank/whitespace content is treated as a genuine failure, not a silently-indexed empty chunk.

> **✅ Decision (Grooming #63 — Lucene index base directory):** Unspecified in this plan. User rejected a working-directory-relative default (`./lucene-indexes`) and confirmed instead: configurable via `synapsemcp.lucene.base-dir`, defaulting to `${java.io.tmpdir}/synapsemcp/lucene-indexes` — one subdirectory per knowledge_base, guarded by a per-KB in-process `ReentrantLock` since Lucene disallows two `IndexWriter`s open on the same directory concurrently and the `ingestionExecutor` pool can run up to 8 documents in parallel.

### Lucene ↔ Postgres Reconciliation

> **✅ Decision (Grooming #7):** A **basic reconciliation cron** is included in this milestone. The cron detects drift between the Lucene index and Postgres `chunks` table (e.g., orphaned Lucene entries, missing index entries for committed chunks) and repairs them. Runs at a configurable interval (e.g., every hour). Does not need to be real-time — eventual consistency is acceptable. The cron compares Postgres `chunks` against the Lucene index **directly by knowledge_base**, independent of `ingestion_jobs.status` — this also covers the specific case where Postgres commit succeeded but the subsequent Lucene write then threw (job recorded `FAILED` despite the chunks being fully durable): the cron indexes the missing Lucene entries and flips that job back to `READY`, rather than leaving a permanently `FAILED` job for data that is actually complete and searchable.
>
> **Concurrent-retry safety (Grooming #70, found live, `plan.md` §9 2026-07-17):** the FAILED→READY repair re-fetches the job fresh and re-verifies it is still `FAILED` immediately before flipping it, inside the same transaction — the sweep's own initial job list is a stale snapshot, and without this re-check a concurrent Grooming #26 retry of that exact job landing in the narrow window between the snapshot and the repair transaction could have its own in-flight state overwritten by the cron.

---

## Ingestion Metrics

> **✅ Decision (Grooming #8):** Basic ingestion metrics are included in this milestone:
> - **Jobs by state** — count of jobs in each state (`PENDING`, `INDEXING`, `READY`, `FAILED`).
> - **Failure counts** — count and breakdown of failures by stage and error type.
> - Exposed via a metrics endpoint or actuator. Full stage-duration histograms and advanced observability are deferred to Phase 3.

> **✅ Decision (Grooming #64 — Metrics exposure mechanism):** This app has no admin tier to gate a richer dashboard behind. User confirmed (recommended option): Micrometer, via `/actuator/metrics` (`management.endpoints.web.exposure.include` extended to `health,metrics`), system-wide counts only, not per-tenant. "Jobs by state" is a live-query `Gauge` per `IngestionStatus` value; "failure counts by stage/error type" is a cumulative `Counter` incremented once at the moment a job fails, since a snapshot gauge can't answer "how many failures of this kind have there ever been" once a job is retried or reconciled back to `READY`.

---

## Polling

- `GET /api/v1/jobs/{jobId}` — tenant-scoped status polling (also feeds the future MCP `ingest` tool). **Implemented.**

### Done When

- ✅ Every *core-pipeline-supported* format (`.txt`/`.md`, `.html`, `.doc`/`.docx`/`.xls`/`.xlsx`/`.ppt`/`.pptx`) ingests end-to-end on fixture files — verified via extraction/chunking scratch probes and live integration-test uploads reaching `READY`.
- ✅ A 100-page PDF completes without blocking API responsiveness — `PdfExtractor` now implemented (Grooming #79); the upload endpoint remains fully async regardless of page count, and `maxLlmPages` (default 100) caps the vision-call path specifically, not the pipeline's responsiveness.
- ✅ LLM-first extraction supports up to 100 pages, falls back to library-only beyond that — `PdfExtractor`/`ImageExtractor` implemented (Grooming #79): verified live against real Google GenAI credentials (real PDF/JPEG fixtures, correctly transcribed and correctly answerable via `/ask`), plus automated tests for the page-limit skip and the library-fallback paths (`PDFTextStripper`, `RapidOCR`).
- ✅ A dimension mismatch or extractor failure marks the job `FAILED` with a useful `error_detail` — `ChunkPersistenceService` unit-tested for the mismatch case; an unsupported MIME type's `UnsupportedOperationException` follows the same catch-all path.
- ✅ Duplicate content short-circuits — unchanged from Stage 4, now genuinely exercised against a pipeline that reaches `READY` instead of staying `PENDING` forever.
- ✅ Reconciliation cron detects and fixes Lucene ↔ Postgres drift — `IngestionReconciliationJobIntegrationTest` covers all three cases live: a chunk missing from Lucene, an orphaned Lucene entry, and a `FAILED` job whose chunks were actually durable.
- ✅ Basic metrics (jobs by state, failure counts) are exposed — verified live against a running app (`/actuator/metrics/synapsemcp.ingestion.jobs`, tag-filtered per state, matched a direct `SELECT status, count(*) FROM ingestion_jobs GROUP BY status`).
- ✅ Tenant-isolation test: tenant B's ingestion never touches tenant A's chunks or indexes — `IngestionJobControllerIntegrationTest` covers cross-tenant polling (`404`); per-knowledge_base Lucene directories and `chunks.tenant_id` scoping give this the same structural isolation guarantee as every earlier stage; a post-build "final thorough validation" pass (`plan.md` §9 2026-07-17) confirmed this live under genuine concurrent multi-tenant ingestion too, not just sequentially (`IngestionPipelineEdgeCaseIntegrationTest`).
- ✅ A "final thorough validation" pass found and fixed three real, previously-unknown bugs (`plan.md` §9 2026-07-17, none anticipated during the original build): retrying a `FAILED` job with already-durable chunks silently duplicated them (Grooming #67); the embedding Redis cache was fail-closed, contradicting this project's own established Redis-fail-open convention (Grooming #68); and the reconciliation cron could stomp on a concurrent retry's in-flight state via a stale job reference (Grooming #70). Also confirmed live and safe with no code change needed: a knowledge_base deleted mid-pipeline, corrupt/malformed file content through `OfficeExtractor`, and identical content ingested into two different knowledge bases. One user-confirmed design decision closed a real gap: empty/whitespace-only extracted text now fails the job with a clear `error_detail` instead of indexing a meaningless empty chunk (Grooming #69).
- ✅ A second "final thorough validation" pass (`plan.md` §9 2026-07-18) found and fixed a fourth real bug: deleting a knowledge_base left its Lucene index directory orphaned on disk forever, an unbounded leak invisible to the reconciliation cron (Grooming #71). Also added the dedicated unit test coverage `LuceneIndexManager` never had before (only scratch probes and indirect integration coverage), confirmed a genuinely multi-chunk document (every prior test used single-chunk content) flows correctly through embed/persist/index with correct positions and heading paths, and directly confirmed the `synapsemcp.ingestion.failures` Counter actually increments with the correct stage/error-type tags (previously only the jobs-by-state gauge had been checked live).
- ✅ A third "final thorough validation" pass (`plan.md` §9 2026-07-18) found a real, significant testing gap rather than a code defect: no genuinely valid `.docx`/`.xlsx`/`.pptx`/HTML file had ever been uploaded through the *full* live pipeline before this pass - `OfficeExtractor`/`HtmlExtractor`/`TableAwareChunkingStrategy` were only ever verified in isolation (Stage 5's own original build-time scratch probes, plus unit tests calling the chunking strategies directly with hand-shaped text). The only prior binary-format integration test was a deliberately-corrupt `.docx`, which never exercised the real happy path either. Closed with `RealFormatIngestionIntegrationTest` (4 tests, real fixtures generated via POI's own writer APIs): a real `.docx` with Word heading styles correctly drives `StructureAwareChunkingStrategy`'s `headingPath` metadata end-to-end; a real `.xlsx` with enough rows to clear the single-chunk shortcut correctly drives multi-window `TableAwareChunkingStrategy`; a real `.pptx` ingests correctly; real HTML headings correctly drive structure-aware chunking through the same `#`-line convention `HtmlExtractor` converts them to. All four reach `READY` with chunks correctly embedded and indexed. No product defect found - this pass's value was closing the coverage gap itself (the first two content-length assumptions in the test's own drafts were themselves too short and legitimately hit the single-chunk shortcut, caught by the tests failing honestly rather than passing on thin content).
- ✅ A fourth "final thorough validation" pass (`plan.md` §9 2026-07-18) verified something no prior pass had: that the reconciliation cron's `@Scheduled` annotation genuinely fires repeatedly on a live timer, not just that its business logic is correct when called directly. Confirmed live via `/actuator/scheduledtasks` against a real running app (13 pre-existing knowledge bases, a short overridden interval) that it registers correctly and its `lastExecution` timestamp advances on schedule. Found one real observability gap along the way (Grooming #72): the cron produces zero log output when a sweep finds nothing to repair - the common case - so a silently-broken cron would be indistinguishable from a healthy one without exposing the endpoint that already tracks this for free. Also added the first automated test coverage for actuator endpoint exposure itself (`/actuator/metrics` and `/actuator/scheduledtasks`), previously only ever checked manually against a running app.

---

# Stage 6 — Search & Answer

**Flow step [6] — "get the chunk details for the question asked by the consumer → from the chunks generate understandable user-language answer."**

Three sub-capabilities: **retrieve** the right chunks (with ANN-optimized search), **rerank** them, then **answer** grounded in them (with streaming and multi-language support).

---

## 6a. Retrieve (Get the Relevant Chunks — ANN-Optimized) ✅ Implemented (`plan.md` §9, 2026-07-18)

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

> **⚠️ Known limitation, honestly documented rather than papered over (Grooming #73, `plan.md` §9 2026-07-18 — see the `chunks` table definition in Stage 0.5 above for the full write-up):** the "guarantees fast ANN search" language above is aspirational, not yet fully verified true under production-scale, production-selectivity data. Removing the `documents` join (Grooming #73's fix) was a necessary but empirically **not sufficient** condition for Postgres's planner to actually choose the HNSW index over an exact B-tree-fetch-then-sort — at every selectivity tested locally (14%–100% of a 35,000-row table), the planner preferred the exact path, correctly, since sorting a few thousand rows in memory is genuinely cheap. This is expected cost-based-planner behavior, not a bug, but it means "no full sequential scans" is not yet demonstrated true at the scale this milestone's acceptance criteria describe - only demonstrated *no longer structurally impossible*. Needs revisiting against real production data volumes.

---

## 6a+ Rerank (Post-RRF Reranking) ✅ Implemented (`plan.md` §9, 2026-07-18 — LLM-reranker fallback, see Grooming #74)

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

## 6b. Answer (Generate Understandable-Language Response — Streaming SSE) ✅ Implemented (`plan.md` §9, 2026-07-18)

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

## 6c. Evaluate (Basic Relevance Evaluation) ✅ Implemented (`plan.md` §9, 2026-07-18)

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
  * **Purpose:** Delete a knowledge base, cascading to all documents and chunks (Postgres) and the Lucene index directory (filesystem, Grooming #71).

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
| **Errors** | RFC 7807 problem-details on every endpoint; all under `/api/v1`. Missing model config → `422`. Unsupported type → `415`. File too large → `413` (Stage 4 document uploads; the JSON control-plane endpoints get their own `413` too — see *Request Body Size Limit* in Stage 0 above). DB unreachable **mid-request** (not at startup) → `503` (`plan.md` §9, 2026-07-16 — corrects the note previously here, which claimed no "DB not ready" `503` contract was needed since fail-fast startup covers it; that reasoning only covered startup-time unavailability, not Postgres disappearing while the app is already serving traffic, a distinct and equally real scenario found by testing it directly). Unique-constraint violation (e.g. a losing concurrent-write race) → `409`; any other constraint violation (oversized input, etc.) → `400`; malformed path/query parameter type → `400` (`plan.md` §9, 2026-07-17 — see *Concurrency & Constraint-Violation Handling* in Stage 0 above; found live, 9 of 10 concurrent first-time `model-config` writes for one tenant crashed with an unhandled `500` before this). Request body over the global size limit → `413` regardless of endpoint, checked before Spring MVC parses anything (`plan.md` §9, 2026-07-17). A row lock not acquired within `lock_timeout` (contended, not unreachable) → `503` + `Retry-After: 3`, distinct from the DB-unavailable `503` above (`plan.md` §9, 2026-07-17 — see *Runtime Timeouts* in Stage 0 above). A write racing a concurrent delete of the same resource (Hibernate's row-count check on UPDATE/DELETE by id) → `404`, matching the same "doesn't exist" contract as a genuinely nonexistent id, since by the time the response is written the two are indistinguishable (Grooming #54, `plan.md` §9 2026-07-17). Any Spring MVC framework exception implementing `ErrorResponse` (wrong HTTP method → `405`, wrong `Content-Type` → `415`, missing multipart part → `400`, etc.) maps to that exception's own self-described status, app-wide, not just `500` (Grooming #60, `plan.md` §9 2026-07-17). |
| **Observability** | Correlation ID generated/propagated on every request and into async threads via `TaskDecorator`, and actually rendered into every log line via `logging.pattern.level` (Grooming #51, `plan.md` §9 2026-07-17 — found live that this was silently non-functional from Stage 0 until this fix: MDC was populated and the response header echoed correctly, but nothing configured the console pattern to show it). |
| **Config & secrets** | Per-profile YAML; secrets only via env vars; never logged. Provider credentials Base64-encoded at rest (Stage 2). `ProviderCredentials`' `toString()` is overridden to redact both API keys (Grooming #50, `plan.md` §9 2026-07-17), so the "never logged" claim holds even if the object is accidentally passed to a logger in future code, not just by current code discipline. Every response also carries `Cache-Control: no-store` (Grooming #53, `plan.md` §9 2026-07-17) — no endpoint in this API returns cacheable content, and a freshly-issued API key or tenant-scoped data must never be stored by any intermediary. |
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

- [x] All 7 supported file-type categories ingest end-to-end on fixtures (Grooming #79 - PDF and images were the last two, now implemented and verified live against real Google GenAI credentials).
- [x] Tenant-isolation holds across search, ask, jobs, knowledge_base listing, and evaluate (no cross-tenant leakage) - re-confirmed live this session (a fresh 10-endpoint sweep: model-config `403`, KB list/rename/delete, job status, document status, upload, search, ask, evaluate all correctly `404`/empty for a second tenant), on top of Sessions 40/43/47's earlier passes covering the same ground with no regressions found across any of them.
- [ ] Hybrid retrieval beats pure vector and pure BM25 on the golden-query harness - **partially closed, third attempt (Grooming #86):** a genuinely different adversarial construction - 18 near-duplicate support-ticket documents, identical template text, differing *only* by an arbitrary numeric ticket id - finally reproduced a real vector-only failure (MRR 0.833, 4/18 queries wrong) where Sessions 41/49's thematically-distinct golden sets never had (embeddings distinguish topics well; they distinguish arbitrary digit strings embedded in near-identical surrounding text poorly). Hybrid (MRR 0.944-0.926 across two runs) **now demonstrably beats vector-only for the first time** - the first half of this criterion is closed. It does **not** yet beat pure BM25 in this same construction (keyword MRR 1.0, exact-matches the digit string trivially) - RRF fusion averaging a perfect keyword ranking against a partially-confused vector ranking can land short of keyword alone when the two signals disagree, a genuine property of rank fusion, not a bug. Left honestly partial rather than fully checked.
- [x] A 100-page PDF ingests without blocking API responsiveness (LLM-first up to 100 pages) - closed this session (Grooming #83): a genuine, real 100-page PDF (generated via PDFBox's own writer API, a distinct planted fact on page 42) was uploaded live - the `202` response returned in ~50ms, concurrent unrelated API calls stayed fast (~40-60ms) throughout the ~115s of real background vision processing (20 batched `gpt-5-nano` vision calls, 5 pages/batch), the job reached `READY` via the genuine `PdfExtractor(llm)` path (not the fallback), and `/ask` correctly retrieved and answered the page-42 planted fact. A companion 101-page PDF, uploaded the same way, correctly and immediately skipped straight to `PdfExtractor(pdfbox-fallback, page-limit)` with no vision calls at all - confirms the exact page-count boundary (100 = LLM path, 101 = skip) behaves as designed, not just that the counter exists.
- [ ] Provider switch (e.g. OpenAI ↔ Ollama) works by config change only, no code change - **genuinely untested, honest known blocker unchanged across every session since Session 40:** no Ollama installation or Anthropic key exists anywhere in this environment. The `ChatModelFactory`/`EmbeddingModelFactory` provider-switch code path itself has been exercised live with two independent real providers (OpenAI, Google GenAI - Sessions 40-41), which is real evidence the *mechanism* works, but Ollama specifically has never had a genuine round trip.
- [x] ≥ 70% coverage on ingestion, retrieval, and tenant modules (Grooming #82 - genuinely measured for the first time, JaCoCo added this session: line coverage 94.6% / 94.3% / 95.1%, branch coverage 81.7% / 84.3% / 87.5% respectively, all comfortably clearing the bar).
- [x] ANN-optimized vector search is functional (no full sequential scans) - **closed (Grooming #86):** a much larger synthetic dataset (313,000 total chunks, one knowledge_base with 200,000) confirmed the planner *does* choose the HNSW index at production-relevant scale, executing in ~2ms regardless of table size, versus every prior test's largest single-KB case (~4,900-8,000 rows) which stayed below the switchover point. Bracketed the actual threshold: a 15,000-row KB still uses exact B-tree-then-sort (521ms, correctly - sorting 15k rows is still cheap enough that the cost-based planner reasonably prefers it), a 50,000-row KB already uses HNSW (2.5ms). Below that threshold the exact path is the *correct* choice, not a defect; above it, the index is genuinely used. No longer an open concern - a knowledge_base large enough to actually need ANN search gets it automatically.
- [x] Reranking improves retrieval precision (measurable via evaluate endpoint) - **closed (Grooming #86):** reusing the same near-duplicate-ticket adversarial scenario above (where hybrid retrieval is no longer perfect, unlike Grooming #85's prior attempts which left no room to improve), comparing `rerank=false` (MRR 0.944, 2/18 queries wrong) vs `rerank=true` (MRR 1.0, all 18 correct) on identical hybrid retrieval - the LLM reranker fixed both queries hybrid alone got wrong. First genuine demonstration of a measurable `/evaluate`-metric improvement from reranking, not just the previously-observed real reordering with no metric effect.
- [x] Streaming (SSE) answers work end-to-end - proven live with two independent real providers (Sessions 40-41), re-confirmed this session (`/ask` SSE genuine token-by-token delivery with a correctly-arriving final `citations` event).
- [x] Multi-language responses work correctly - proven live (Session 41: a Spanish question with no `language` param got a correct Spanish answer; an English question with an explicit `language: "French"` override got a correct French answer).
- [x] Credentials round-trip correctly through Base64 encode/decode and never leak in responses or logs - proven throughout every session using real provider credentials (chat/embed calls only work if the round-trip is correct) and re-confirmed this session that `GET .../model-config` never returns any credential field.
- [x] Lucene ↔ Postgres reconciliation cron detects and fixes drift - proven live in dedicated drift-injection tests (Session 35) and re-confirmed registered/firing via `/actuator/scheduledtasks` in Sessions 43-44 and this session.
- [x] Basic ingestion metrics (jobs by state, failure counts) are exposed - proven live via `/actuator/metrics/synapsemcp.ingestion.jobs` in multiple sessions including this one.
- [x] Evaluate endpoint returns relevance metrics for golden queries - proven live this session (precision@K/recall@K/MRR correctly computed against real golden queries, including the vacuous-recall convention for an empty expected-set).
- [x] `./mvnw clean verify` is green - confirmed fresh this session: 230 unit + 101 integration tests, 0 failures/errors.

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
| 15 | `ChatOptions` contradiction | ~~Generic `ChatOptions.builder()` is correct; sub-plan fixed~~ **Superseded by Grooming #76** (`plan.md` §9 2026-07-18) - found live against a real provider that this was wrong: generic `ChatOptions` throws `ClassCastException` on every real chat call, for all four providers. Concrete, provider-specific `*ChatOptions` is correct instead |
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
| 41 | `embed()`/`dimensions()` probe implementation | `EmbeddingModel.dimensions()` (Spring AI default method, decompiled via `javap -c` before use) already calls `embed("Test String")` and returns the array length — `KnowledgeBaseService` calls it directly rather than reimplementing the same probe-and-measure logic by hand |
| 42 | knowledge_base delete cascade | `documents.knowledge_base_id`'s FK gained `@OnDelete(CASCADE)` (had none in the original Stage 0.5 schema) — since `chunks`/`ingestion_jobs` already cascade FROM `documents`, this one addition cascades the whole tree at the DB level. **`ddl-auto: update` does not retrofit this on an already-existing database** — confirmed live; a fresh schema rebuild is required |
| 43 | 10-KB-limit race safety | `KnowledgeBaseService.createKnowledgeBase` takes a pessimistic row lock on the tenant (`SELECT ... FOR UPDATE`) around the count-check + insert, since no unique constraint can express "max N rows per tenant" the way `model_configs.tenant_id` catches its own race. The live embedding-dimension probe runs before the lock is acquired, outside any transaction, so a slow provider call never holds a DB connection or the lock idle. Verified live: 15 concurrent creates against one tenant → exactly 10 successes |
| 44 | Cross-tenant knowledge_base access → `404`, not `403` | Unlike `ModelConfigController` (tenant id literally in the URL, `403` on mismatch), knowledge_base endpoints identify the resource by opaque `{id}` alone — a KB belonging to another tenant returns `404`, identical to a nonexistent id, to prevent UUID enumeration against other tenants' resources |
| 45 | Blank credential rejected for key-requiring providers | `ModelConfigService` rejects a blank `chatApiKey`/`embeddingApiKey` with `422` when the provider is `openai`/`anthropic`/`google-genai` (`ollama` exempt). Found live: a real `OPENAI_API_KEY` in this environment's process env caused the OpenAI SDK to silently fall back to it when a tenant's own key was blank — a real cross-tenant billing/security leak on any deployment where an operator's key happens to be set |
| 46 | Embedding-probe try/catch scope | `KnowledgeBaseService.probeEmbeddingDimension` wraps both client construction (`getEmbeddingModel`) and the network call (`.dimensions()`) in one try/catch — a construction-time credential failure (verified live via OpenAI's SDK) previously escaped the narrower try/catch and leaked as `500` instead of `422` |
| 47 | Postgres `lock_timeout` | `spring.datasource.hikari.connection-init-sql: SET lock_timeout = '3s'` (every profile) — Postgres's own default is `0`/disabled; verified live that a 25s-held lock on a tenant row made a concurrent knowledge_base creation wait the entire duration with no timeout. `CannotAcquireLockException` → `503` + `Retry-After: 3`, distinct from the DB-unavailable `503` mapping (contended, not unreachable) |
| 48 | Provider HTTP client timeouts | `ChatModelFactory`/`EmbeddingModelFactory` set an explicit `synapsemcp.provider.timeout-seconds` (default 30) on every cloud provider client. Found via `javap`: OpenAI/Anthropic SDKs default to a 10-minute request timeout; Google GenAI's underlying `OkHttpClient` defaults to `0` — no timeout at all. Ollama has no equivalent builder-exposed setter, left as-is (self-hosted, lower risk) |
| 49 | Provider timeout config rejects `0`/negative | Both factories throw `IllegalArgumentException` at construction if `synapsemcp.provider.timeout-seconds` is `≤ 0` — confirmed via `javap` that OkHttp treats a `0` timeout as "no timeout at all," not "fail instantly," so a natural-but-wrong operator setting would otherwise silently disable Grooming #48's entire fix |
| 50 | `ProviderCredentials.toString()` redacts both keys | A plain Java record's default `toString()` dumps every component's raw value — without this override, `ProviderCredentials` (wrapping both plaintext API keys) would leak them in full the moment any future code passed it to a logger or an exception message. Nothing currently does; this is a defense-in-depth fix, not a live-exploited bug. Confirmed the override doesn't affect Jackson (de)serialization (reflection-based) or record `equals`/`hashCode` |
| 51 | Correlation ID actually rendered in log output | `logging.pattern.level: "%5p [correlationId=%X{correlationId:-}]"` added to base `application.yaml`. Found live: `CorrelationIdFilter` populated MDC and echoed the response header correctly, but Spring Boot's default console pattern never interpolates MDC keys — the correlation ID never appeared in a single rendered log line since the filter was first built, silently defeating Grooming #9's entire purpose. Zero prior test coverage on this filter; `ContextPropagatingTaskDecoratorTest` only checks `MDC.get(...)` programmatically, which cannot detect "populated but never rendered" |
| 52 | Knowledge base name trim + case-insensitive uniqueness | User confirmed (recommended option): names are trimmed server-side before storage (`request.name().trim()`, both create and update), and per-tenant uniqueness is case-insensitive. `KnowledgeBase`'s plain JPA `@UniqueConstraint(tenant_id, name)` was removed (can only express exact-value uniqueness) and replaced with a hand-written functional unique index — `uq_knowledge_bases_tenant_name_ci ON knowledge_bases (tenant_id, lower(name))` — added to `AnnIndexBootstrapRunner`, alongside the HNSW indexes/`CHECK` constraints it already manages for the same reason (plain `ddl-auto` can't express this shape). On an already-existing local/dev database the old exact-match constraint remains as a harmless leftover (`ddl-auto: update` doesn't retrofit removed annotations, same caveat as the Stage 3 cascade-delete fix) — verified live via `\d knowledge_bases` that both coexist without conflict, and that Postgres raises the same `23505` SQLSTATE either way, so `ApiExceptionHandler`'s `409` mapping needed no changes |
| 53 | `Cache-Control: no-store` on every response | New `CacheControlFilter` (`Order = HIGHEST_PRECEDENCE`) sets it unconditionally, ordered before every filter capable of short-circuiting with its own committed response (body-size/rate-limit/auth). Found live: no response anywhere carried any cache-control header, including a freshly-issued API key and tenant-scoped data. Not an active browser-cache risk today (RFC 7234 §3.2 already restricts shared caching of `Authorization`-header requests by default), but defense-in-depth against a future reverse proxy/CDN caching by URL alone and serving one tenant's response to another - this app has no infra layer yet, so nothing else currently guards against that |
| 54 | Concurrent-delete race on knowledge_base UPDATE/DELETE → `404` | `ApiExceptionHandler` now handles `ObjectOptimisticLockingFailureException` → `404` (user confirmed over `409`, matching `requireOwnedKnowledgeBase`'s existing "doesn't exist" contract). Found live: a `DELETE` racing a concurrent `PUT`/`DELETE` on the same knowledge base reliably threw an unhandled `500` — Hibernate checks the affected row count on every UPDATE/DELETE by primary key regardless of `@Version` presence, and throws when a concurrent request already removed the row. Reproduced both as an uncontrolled 40-request batch and a controlled 5-for-5 deterministic one-delete-vs-one-update race; re-verified post-fix that the exception fires and maps to `404` with zero `500`s in either case |
| 55 | SpotBugs static analysis added, 0 findings after triage | `./mvnw spotbugs:check` (`effort=Max`, `threshold=Low`) — a structurally different bug-finding method than this project's usual live-testing audits: systematic bytecode-pattern scanning, not a human picking one angle. First run: 35 findings, 28 of which (`EI_EXPOSE_REP`/`EI_EXPOSE_REP2`) were the standard JPA-entity/Spring-constructor-injection false-positive pattern (excluded via `spotbugs-exclude.xml`, documented — defensively copying an entity relationship or an injected singleton bean would break how JPA/Spring are meant to work, not harden anything). 4 were real: `ChatModelFactory`/`EmbeddingModelFactory` made `final` (throwing constructors + `CT_CONSTRUCTOR_THROW`'s finalizer-attack precondition, SEI CERT OBJ-11-J — empirically confirmed `@MockitoBean` still works on a `final` class first); `DatabaseBootstrapRunner` gained an explicit alphanumeric/underscore identifier-validation guard before its unavoidable string-built `CREATE DATABASE` DDL, plus a scoped `@SuppressFBWarnings` (Postgres has no parameterized-placeholder syntax for a DDL identifier, so the pattern match can't be eliminated, only mitigated); `ModelConfigUpdatedEvent` gained an explicit `serialVersionUID` |
| 56 | Stage 4/5 boundary — `IngestionPipelineService` is a deliberate stub | Stage 4's own Implementation section describes firing `IngestionPipelineService.run(...)` on `@Async("ingestionExecutor")` after committing `Document(PENDING)`/`IngestionJob(PENDING)` - that dispatch call is genuinely Stage 4's scope, but its body (extract → chunk → embed → persist → index) is Stage 5, not yet built. `run(jobId)` logs a warning and returns, leaving the job honestly `PENDING` rather than fabricating a `READY`/`FAILED` outcome. New `ingestionExecutor` bean (`AsyncConfig`, `@EnableAsync`) created now since Stage 4's dispatch call needs it to exist and compile, sized per this section's own already-decided Grooming #9 values (core 4/max 8/queue 100/`CallerRunsPolicy`), wired with the previously-unused `ContextPropagatingTaskDecorator` |
| 57 | Multipart size limit configuration + `RequestBodySizeLimitFilter` exemption | Decompiled Spring Boot 4.1's `MultipartProperties` via `javap` and confirmed its defaults (1MB/file, 10MB/request) are both smaller than this stage's required 20MB cap - without explicit `spring.servlet.multipart.max-file-size: 20MB`/`max-request-size: 21MB`, every upload over 1MB would have been silently rejected by Spring's own multipart resolver before `DocumentUploadService` ever ran. The resulting `MaxUploadSizeExceededException` (confirmed via `javap` it already self-describes as `413` via `ErrorResponse`) needed its own explicit `ApiExceptionHandler` entry anyway, since this app's catch-all `@ExceptionHandler(Exception.class)` would otherwise intercept it first and leak a `500`. Also closes the `RequestBodySizeLimitFilter` TODO first flagged at Stage 0's own construction: added a `shouldNotFilter` exemption (`AntPathMatcher` against `/api/v1/knowledgebase/*/documents`) so the 1MB JSON-control-plane filter never shadows this endpoint's own cap - verified live that the exemption is correctly scoped (an oversized JSON body to an unrelated endpoint still `413`s via the original filter) |
| 58 | Upload short-circuit response: `200` vs `202` | User confirmed (recommended option): a pure idempotency no-op (existing `PENDING`/`INDEXING`/`READY` document, nothing new dispatched) returns `200`; a fresh upload or a `FAILED`-reset-and-redispatch (genuine new pipeline work triggered either way) returns `202`. `status` alone can't drive this in the controller, since a no-op and a fresh dispatch can both currently read `PENDING` (Stage 5 doesn't exist yet to ever advance a job past it) - `UploadDocumentResponse` gained an explicit `dispatched: boolean` field for the controller to branch on |
| 59 | Upload idempotency race → pessimistic lock on knowledge_base | User confirmed (recommended option) over a lock-free catch-and-recover/conditional-update alternative. Found live: concurrent uploads of identical new content raced past the un-synchronized idempotency check-then-insert (10 concurrent duplicates → 1×`202` + 9× a raw `409` with no job info, violating Grooming #6b/#26's idempotent-short-circuit promise); concurrent retries of the same `FAILED` document all independently reset-and-dispatched (harmless today since Stage 5 is a stub, but would mean duplicate paid processing once it exists). Fixed with `KnowledgeBaseRepository.lockById` (same `SELECT ... FOR UPDATE` pattern as the 10-KB-per-tenant limit), acquired only around the check-and-write step; `IngestionPipelineService.run(...)` still only fires after that locked transaction commits. Re-verified live: the identical 10-concurrent-upload reproduction now returns `1×202 + 9×200`, zero `409`s; concurrent `FAILED` retries dispatch exactly once. Proven with genuine concurrent-thread integration tests against a real database, not mocks |
| 60 | App-wide `ErrorResponse` exception family generalized | Generalizes Grooming #57's narrow `MaxUploadSizeExceededException`-only fix, found insufficient by a later audit pass: several Spring MVC framework exceptions self-describe their correct HTTP response via the `ErrorResponse` interface, but this app's own catch-all `Exception` handler intercepted every one of them first, leaking `500`. Confirmed live and **not Stage-4-specific** - affects the whole API: a missing multipart `"file"` part (`400`), a wrong `Content-Type` on the upload endpoint (`415`), and a wrong HTTP method on both the upload endpoint and an unrelated, pre-existing Stage 3 endpoint (`405`). Fixed with one consolidated `@ExceptionHandler` covering every `ErrorResponse`-implementing exception reachable from this app's request-handling flow (found by scanning `spring-web`/`spring-webmvc` for implementors, not guessed), deliberately excluding `MethodArgumentNotValidException` (already has its own more specific handler) |
| 61 | Stage 5 scope narrowed — core pipeline first, LLM/OCR extractors deferred | User confirmed (recommended option): the full `extract → chunk → embed → persist → index → READY` pipeline built now using only the three library-only extractors (`PlainTextExtractor`, `HtmlExtractor`, `OfficeExtractor`); `PdfExtractor` (LLM vision + PDFBox fallback) and `ImageExtractor` (LLM vision + RapidOCR) deferred to a dedicated follow-up session as genuinely higher-risk, new dependencies (real vision-model calls, a pure-JVM ONNX OCR runtime) rather than bundled into an already-large stage |
| 62 | `windowTokensFor(totalTokens)` formula | Undefined elsewhere in this plan's chunking spec (5b). User confirmed the recommended interpretation: identical to `FixedSizeChunkingStrategy`'s own 3-tier chunk-size table (`<1,000` tok → 256, `1,000–50,000` → 512, `>50,000` → 1,024) — keeps `DocumentChunkingService`'s single-chunk shortcut consistent with the fallback strategy's actual window size rather than an independently-chosen constant |
| 63 | Lucene index base directory | Unspecified in this plan. User rejected a working-directory-relative default (`./lucene-indexes`) and confirmed instead: configurable via `synapsemcp.lucene.base-dir`, defaulting to a system temp/data directory (`${java.io.tmpdir}/synapsemcp/lucene-indexes`) — one subdirectory per knowledge_base, guarded by a per-KB in-process `ReentrantLock` since Lucene disallows two `IndexWriter`s open on the same directory concurrently and the `ingestionExecutor` pool can run up to 8 documents in parallel |
| 64 | Ingestion metrics exposure mechanism | This app has no admin tier to gate a richer dashboard behind, and the plan only specifies "exposed via a metrics endpoint or actuator" without naming one. User confirmed (recommended option): Micrometer, via `/actuator/metrics` (`management.endpoints.web.exposure.include` extended from `health` alone to `health,metrics`), system-wide counts only, not per-tenant. "Jobs by state" is a live-query `Gauge` per `IngestionStatus` value (always reflects current `ingestion_jobs`, including cron-repaired rows); "failure counts by stage/error type" is a cumulative `Counter` incremented once by `IngestionPipelineService` at the moment a job fails, since a snapshot gauge can't answer "how many failures of this kind have there ever been" once a job is retried or reconciled back to `READY` |
| 65 | Ingestion pipeline content delivery — no blob storage | A genuine gap surfaced only while wiring Stage 5, not visible when Stage 4 alone was built and audited: Stage 4 never persists the uploaded file's raw bytes anywhere, only metadata (filename, MIME type, content hash) — Stage 5's `@Async` pipeline runs on a separate thread, potentially long after the original HTTP request (and its in-memory `MultipartFile`) has returned, with no way to retrieve the content to extract from. User confirmed (recommended option) over adding a new blob-storage layer (`documents.content_blob`, or a filesystem store): pass the raw bytes straight into the async dispatch call (`IngestionPipelineService.run(jobId, content)`), threaded through from the still-in-memory upload request. Consistent with this plan's existing hash-based idempotency design — Grooming #26 already retries a `FAILED` job via re-upload of the identical file, not server-side replay — at the cost that a job's content is lost if the JVM restarts between the upload committing and the async task actually running; that job then stays `PENDING` until the client re-uploads |
| 66 | Lazy-association `LazyInitializationException` across per-stage transactions | Found live against a real Postgres instance on the very first genuine end-to-end pipeline run — not caught by compilation or by any of the isolated component-level scratch probes used while building each Stage 5 piece individually. `IngestionJob.document` and `Document.knowledgeBase` are lazy JPA associations (`@OneToOne`/`@ManyToOne`); each Spring Data repository call runs in its own short-lived transaction, so by the time a later pipeline stage (in a brand-new `TransactionTemplate` transaction) called a setter on a lazily-loaded reference obtained earlier, the originating Hibernate session had already closed. Fixed by re-fetching `Document`/`KnowledgeBase` directly via their own repositories immediately after loading the job, rather than navigating the lazy association chain — `.getId()` alone on an uninitialized proxy stays safe (Hibernate's identifier-only shortcut needs no session), but any other accessor (a setter, a non-id getter) does not |
| 67 | Retry of a `FAILED` job with already-durable chunks silently duplicated them | Found live in a post-Stage-5 "final thorough validation" pass (`plan.md` §9 2026-07-17), not anticipated during the original build: a document can reach `FAILED` with its chunks already committed (persist succeeded, the later Lucene write then threw — exactly the case the reconciliation cron exists to repair). Retrying it (Grooming #26 - re-upload resets the job to `PENDING` and redispatches) re-ran the whole pipeline including `ChunkPersistenceService.persist`, which never checked for chunks already committed from the prior attempt — confirmed live, a single retry doubled the chunk count, and repeated retries would keep multiplying it, corrupting the RAG corpus with duplicate content. Fixed by having `persist` delete any pre-existing chunks for the document in the same transaction before inserting the new set, and returning their ids so the caller (`IngestionPipelineService`) also removes the corresponding stale Lucene entries (different ids than the new chunks, so re-indexing the new set alone wouldn't overwrite them) |
| 68 | Embedding cache Redis calls were fail-closed, contradicting this project's own established convention | This project has an explicit, repeatedly-stated principle that Redis must never gate otherwise-working functionality (`TenantCreationRateLimitFilter`'s own Javadoc; `management.health.redis.enabled: false`). Found live via a real `CLIENT PAUSE`-simulated outage (not assumed): `ChunkEmbeddingService`'s cache read/write calls were unwrapped, and a Redis timeout during the embed stage failed the entire ingestion job — a purely-optimizational cache outage taking down otherwise-good ingestion. Fixed by catching and logging both the read (`multiGet`) and write (`set`) calls, degrading to "treat as a cache miss" / "skip caching this result" respectively rather than propagating |
| 69 | Empty/whitespace-only extracted text now fails the job instead of indexing a meaningless chunk | Found live: a file with no real content (blank `.txt`, a scanned-blank page) reached `READY` with one chunk containing empty-string content — genuinely embedded (a real provider may reject an empty-string embed call outright) and indexed, but useless for search and silently misrepresenting the document as successfully ingested. User confirmed (recommended option): `IngestionPipelineService` now throws immediately after extraction if the text is blank, following the pipeline's existing error contract (job `FAILED`, stage `extract`, a clear `error_detail`) rather than silently producing a degenerate chunk |
| 70 | Reconciliation cron could stomp on a concurrent retry's in-flight state | Found by tracing Grooming #67's fix for a related race: the cron's `repairFailedJobsWithDurableChunks` reused a stale `IngestionJob` reference from its initial snapshot query, without re-verifying the job was still `FAILED` immediately before flipping it to `READY` inside its own transaction — a concurrent Grooming #26 retry of that exact job (reset to `PENDING`, redispatched) landing in that narrow window could have its own in-flight state overwritten by the cron. Fixed by re-fetching the job fresh inside the repair transaction and skipping the repair (no-op) if its status is no longer `FAILED` |
| 71 | Deleting a knowledge_base orphaned its Lucene index directory forever | Found live in a second post-Stage-5 "final thorough validation" pass (`plan.md` §9 2026-07-18): `KnowledgeBaseService.deleteKnowledgeBase` only called `knowledgeBaseRepository.delete(...)` — a Postgres-level cascade removing every `documents`/`chunks`/`ingestion_jobs` row, but with no way to reach the filesystem. Confirmed live: the on-disk Lucene directory for a deleted knowledge_base survived the delete untouched. This is a genuine, unbounded disk-space leak, and one the reconciliation cron could never discover or repair, since it only iterates `knowledgeBaseRepository.findAll()` — knowledge bases that still exist, by definition excluding every orphan this bug produced. Fixed with a new `LuceneIndexManager.deleteIndex(knowledgeBaseId)` (recursively removes the whole directory tree, also drops the JVM's per-KB `ReentrantLock` map entry), called from `deleteKnowledgeBase` after the Postgres delete commits. A filesystem failure during this cleanup is logged, not propagated — the knowledge_base is already gone from Postgres by that point, so failing the API response back to the caller would be misleading |
| 72 | Reconciliation cron had no observable evidence it was actually running | Found live in a fourth post-Stage-5 "final thorough validation" pass (`plan.md` §9 2026-07-18): started the app with a short reconciliation interval against 13 real pre-existing knowledge bases and confirmed via `/actuator/scheduledtasks` that the cron genuinely fires repeatedly on schedule (`lastExecution.time` advanced across successive checks, `status: SUCCESS`) — the `@Scheduled`/`@EnableScheduling` wiring itself was never verified this way before (every prior test called `IngestionReconciliationJob.reconcile()` directly). But the sweep produced **zero log output** across all those successful runs, since `IngestionReconciliationJob` only logs when it finds actual drift to repair — the common case in a healthy system. A silently-broken cron (an unhandled exception, or `@Scheduled` never registering at all) would look identical to a healthy one from the logs alone. Fixed by adding `scheduledtasks` to `management.endpoints.web.exposure.include` — Spring Boot already tracks exactly the needed data (`lastExecution` status/timestamp, `nextExecution`) per `@Scheduled` method for free; it just wasn't exposed. No custom logging/metrics code needed. Confirmed `/actuator/*`'s existing prefix-match authentication exemption already covers the newly-exposed endpoint with no further change |
| 73 | `VectorSearchService` never used the HNSW ANN index — `knowledge_base_id` denormalized onto `chunks` | Found live via `EXPLAIN ANALYZE` against a realistic multi-tenant dataset (`plan.md` §9 2026-07-18): the original query joined `documents` to reach `knowledge_base_id` (since `chunks` never had it directly, only `tenant_id` was denormalized per Grooming #16) — Postgres's planner never once considered the HNSW index for that shape, always a full sequential scan + sort, violating this plan's "no full sequential scans" acceptance criterion. Fixed by adding `knowledge_base_id` directly onto `chunks` (same no-FK rationale as `tenant_id`) and rewriting the query with no join. Honestly incomplete, not oversold: removing the join is *necessary* (confirmed the join structurally blocked the index) but not proven *sufficient* — at every selectivity tested locally the cost-based planner still preferred an exact B-tree-fetch-then-sort over the ANN index, which may differ at real production scale. `hnsw.iterative_scan = relaxed_order` also set on every pooled connection (`connection-init-sql`, every profile) for correctness whenever the index path *is* chosen — confirmed live that a plain filtered HNSW scan can otherwise silently return fewer/zero rows |
| 74 | Reranker (Stage 6a+) implemented as an LLM-reranker, not the plan's first-preference ONNX cross-encoder | User confirmed (`plan.md` §9 2026-07-18), asked rather than guessed: a real ONNX export of `BAAI/bge-reranker-v2-m3` was confirmed downloadable (`onnx-community/bge-reranker-v2-m3-ONNX`, int8, ~587MB incl. tokenizer), but wiring it up would have been this codebase's first ML-runtime dependency (ONNX Runtime + a HuggingFace-tokenizer library, real inference-correctness risk this environment can't verify against a reference implementation) — the same risk category already deferred once before (Grooming #61). The plan's own documented fallback ("if open-source is not feasible at all due to cost or complexity, fall back to using the tenant's configured LLM for reranking") was used instead: `LlmRerankerService` prompts the tenant/KB's own configured chat model to score numbered candidate passages 0-100, parses "N: score" lines (lenient regex, tolerates minor format drift), and re-sorts. Deliberately fail-open (provider/parsing failure → log + return unreranked order) - reranking is an optional precision enhancement, not a correctness requirement. `SearchRequest`/`EvaluateRequest`/`AskRequest` all expose a `rerank` boolean (default `true`) |
| 75 | Spring Boot 4/Framework 7 defaults to Jackson 3, not Jackson 2 - no injectable `com.fasterxml.jackson.databind.ObjectMapper` bean exists | Found live (`plan.md` §9 2026-07-18): `RagAnsweringService` originally constructor-injected `com.fasterxml.jackson.databind.ObjectMapper` (needed to serialize the SSE `citations` event's data) - context startup failed with `NoSuchBeanDefinitionException`. `mvn dependency:tree` confirmed this app's Jackson autoconfiguration resolves to `tools.jackson.core:jackson-databind:3.1.4` (the new Jackson 3 package namespace); the classic `com.fasterxml.jackson.databind:jackson-databind:2.21.4` is present only transitively (via another dependency's own JSON-schema tooling) and Spring never registers a bean of that type. Fixed by constructing a plain, unconfigured `new ObjectMapper()` (Jackson 2) directly in `RagAnsweringService` instead of injecting one - sufficient for serializing the small, plain `Citation` list, and sidesteps the whole Jackson-major-version ambiguity |
| 76 | **Grooming #15 was wrong - generic `ChatOptions.builder()` throws `ClassCastException` on every real chat provider call, superseded** | Found live (`plan.md` §9 2026-07-18) via a genuine end-to-end smoke test against real OpenAI credentials - the first time any Stage 6a+/6b code path had ever been exercised against a real chat provider rather than a mocked `ChatModel` (every one of Stage 6's ~30 automated tests mocks `ChatModel` entirely). `/ask` and the LLM reranker both threw `ClassCastException: class org.springframework.ai.chat.prompt.DefaultChatOptions cannot be cast to class org.springframework.ai.openai.OpenAiChatOptions` from inside Spring AI's own `OpenAiChatModel.createRequest`. Decompiled all four providers' `buildRequestPrompt`/`createRequest` (`javap`): each passes a non-null `Prompt.getOptions()` straight through unchanged, then unconditionally casts it to its own concrete `*ChatOptions` type - identical `checkcast` pattern confirmed in OpenAI, Anthropic, Ollama, and Google GenAI's bytecode, so this was never OpenAI-specific. Grooming #15's rationale for the generic approach (avoiding each provider's own enum-typed `model()` setter) was based on an incomplete API reading - empirically confirmed (compiled and ran a standalone probe against the real classpath) that all four builders *also* inherit a `model(String)` overload alongside their enum-typed one. Fixed: `ChatModelFactory.optionsFor`/`optionsForKnowledgeBase` now build a concrete, provider-specific `*ChatOptions` instance via a provider switch (mirroring `EmbeddingModelFactory`'s existing per-provider construction pattern). Re-verified live after the fix against **two independent real providers** (OpenAI `gpt-5-nano` and Google GenAI `gemini-3.1-flash-lite`): `/ask` (JSON and SSE streaming), the reranker, `/search`, and `/evaluate` all produced genuine, correctly-grounded, correctly-cited answers end to end |
| 77 | Real OpenAI streaming emits a metadata-only final `ChatResponse` chunk with a `null` result - `RagAnsweringService.askStream` didn't guard against it | Found live in the same end-to-end smoke test, immediately after fixing Grooming #76: the real SSE `/ask` endpoint threw `NullPointerException: Cannot invoke "Generation.getOutput()" because the return value of "ChatResponse.getResult()" is null` mid-stream, then a **second**, more confusing exception (`HttpMessageNotWritableException: No converter for [...] with preset Content-Type 'text/event-stream'`) when `ApiExceptionHandler` tried to write a JSON `ProblemDetail` into a response already committed as `text/event-stream` - the citations event never arrived, and curl reported a mid-transfer connection drop. No prior test caught this because every mocked streaming `Flux` in this codebase's tests only ever contained well-formed `ChatResponse`s with a real `Generation`; a real provider's own end-of-stream metadata chunk was never modeled. Fixed with two changes: (1) `askStream` now filters out any `ChatResponse` whose `getResult()` is null before reading its output; (2) the whole SSE `Flux` gained an `onErrorResume` converting *any* mid-stream failure into a `text/event-stream`-native `error` SSE event, since a normal exception handler structurally cannot write a JSON body into an already-committed streaming response - confirmed exactly this cascading failure live before the fix. Re-verified live after both fixes: the identical two-part real-OpenAI-streaming request now completes cleanly (curl exit 0), with the answer correctly citing both sources and the `citations` event arriving as the final frame |
| 78 | `GET /api/v1/documents/{documentId}/status` was missing entirely | Found during a third completeness-audit pass (`plan.md` §9 2026-07-18) by diffing this file's own "REST API Summary" against the actual `@*Mapping` annotations in the codebase - every other endpoint in the "complete list of REST endpoints... upon completion of Phase 1" was implemented except this one; only job-based polling (`GET /api/v1/jobs/{jobId}`) existed, leaving no way to poll status from a bare `documentId` (e.g. the id the upload endpoint itself returns) without separately tracking its job id. Fixed with a new `DocumentStatusController` (`com.synapsemcp.document`) backed by a new `IngestionJobService.getStatusByDocumentId` method reusing `ingestion_jobs.document_id`'s existing `UNIQUE` constraint (Grooming #6b - exactly one job per document) - returns the identical `JobStatusResponse` shape as the job-id endpoint, same tenant-ownership-or-404 convention. User confirmed (asked, not guessed) that a related, larger gap found in the same pass - Milestone 7's `DocumentSource`/`SourceDocument` SPI interfaces, never built - should be deferred to a later session rather than done now |
| 79 | **`PdfExtractor`/`ImageExtractor` built - Grooming #61's deferral superseded, full plan spec, user-confirmed over the simpler lower-risk alternative** | User manually tested all 6 documented-but-unimplemented MIME types (`application/pdf`, `image/jpeg`, `image/png`, `image/gif`, `image/bmp`, `image/tiff`) live and asked for them to be built rather than accepting the existing deferral. Asked rather than guessed on two real architecture forks before writing any code: (1) PDF - full plan spec (LLM-vision-first + PDFBox fallback) over a PDFBox-only alternative that would have handled the large majority of real-world PDFs (any with a text layer) at zero new risk/cost; user chose the full spec. (2) Images - full plan spec (LLM-vision-first + RapidOCR fallback) over an LLM-vision-only alternative that would have avoided a brand-new ML-runtime dependency (the same risk category already deferred once for the reranker, Grooming #74); user chose the full spec. Verified every dependency for real before adding it, not guessed: `org.apache.pdfbox:pdfbox:3.0.8` (`Loader.loadPDF`/`PDFRenderer`/`PDFTextStripper`, confirmed via `javap`); `io.github.mymonstercat:rapidocr`/`rapidocr-onnx-platform:0.0.7` (a genuine, Apache-2.0-licensed, cross-platform pure-JVM ONNX PP-OCRv4 port - confirmed `Model.ONNX_PPOCR_V4` exists via `javap`, then proved it actually works with a real OCR smoke test - a hand-rendered "Golden Retriever" PNG correctly recognized end to end - *before* committing to the dependency); Spring AI's `UserMessage.builder().media(...)`/`Media.builder()` multimodal API (confirmed via `javap` against the project's existing `spring-ai-commons`/`spring-ai-model` 2.0.0 jars); confirmed this JDK's bundled `ImageIO` already reads/writes all five image formats including TIFF, no extra image-codec dependency needed. `DocumentExtractor.extract(...)` gained a `KnowledgeBaseModelConfig` parameter (both new extractors need it to resolve the knowledge_base's locked chat model via `ChatModelFactory#getChatModelForKnowledgeBase`) - a mechanical signature change across all four extractors and `DocumentExtractionService`/`IngestionPipelineService`'s one call site each; the three pre-existing extractors' actual behavior is unchanged (confirmed: the full 208+97-test baseline stayed green, unchanged, both before and after). New shared components: `VisionTranscriptionService` (the vision-model call itself, shared by both extractors), `ExtractionCacheService` (the 24h-by-content-hash Redis cache both extractors use, fail-open on Redis errors - same established convention as the embedding cache, Grooming #68), `RapidOcrService` (wraps every OCR call in a single lock - `InferenceEngine.getInstance(Model)`'s concurrent-call safety is undocumented, and this app's `ingestionExecutor` pool runs up to 8 documents in parallel - the same defensive pattern already used for `LuceneIndexManager`'s per-knowledge_base `IndexWriter` lock; also wraps every call in a temp file, since the published jar's `runOcr` only accepts a file path, not raw bytes). Both extractors treat "page/image count over the cost guardrail", "vision call throws", and "vision call returns blank" identically - fall back to the cheaper library path, matching this project's established fail-open philosophy (`LlmRerankerService`, the embedding cache). Verified live against real Google GenAI credentials (not just mocks) before considering this done: real PDF and JPEG fixtures containing genuine text ("The capital of France is Paris.") were correctly transcribed via the real vision path, correctly indexed, and correctly answerable via a live `/ask` call with proper citations; a second upload of identical bytes into a different knowledge_base confirmed the Redis cache genuinely engages (`ImageExtractor(cached)`, no second vision call). 21 new unit tests (including a deliberately real, non-mocked `RapidOcrServiceTest` - the OCR engine needs no external credentials, so there's no reason to mock it the way every chat/embedding provider call in this codebase must be) + 4 new integration tests (mocked `ChatModelFactory`, since no live credentials are guaranteed in CI) covering the vision-success and library-fallback paths for both extractors end-to-end through the real pipeline |
| 80 | **All 61 SpotBugs `EI_EXPOSE_REP`/`EI_EXPOSE_REP2`/`REC_CATCH_EXCEPTION` findings properly fixed, `spotbugs-exclude.xml` now genuinely empty - user explicitly rejected suppressing them** | User asked "why suppress it" when told 16 of the findings (constructor-injected Spring/infra beans) had no real fix, and asked for the id-only refactor on the other 14 (JPA `@ManyToOne`/`@OneToOne` relationships) despite the real risk that doing it the SpotBugs-suggested way (removing the entity reference from the constructor entirely) would have meant dropping real FK constraints and `@OnDelete(CASCADE)` behavior (Grooming #42) - a genuine regression risk surfaced and flagged before proceeding, not discovered partway through. Found a materially safer alternative instead of either suppressing or doing the risky version, and verified it empirically rather than trusting SpotBugs' own documentation (which doesn't clearly state this): making the constructor (and, for entities, the entity-returning getter) **package-private instead of public** eliminates the finding entirely, confirmed on a real class (`ApiKey`) before applying it anywhere else - neither Spring's constructor injection nor Hibernate's entity instantiation/field access care about Java visibility at all (both use reflection), so this keeps every `@JoinColumn`/`@OnDelete(CASCADE)` mapping and every FK constraint completely unchanged. For the handful of cases needing cross-package construction (`IngestionJob`, `Chunk`, `ModelConfig`, `KnowledgeBase`, `KnowledgeBaseModelConfig`, `Document` - checked every real call site via `grep`, not assumed), a public static factory method (e.g. `IngestionJob.create(...)`) was added instead, also verified empirically to not be flagged (the actual field-assignment bytecode lives inside the package-private constructor it delegates to). Every external call site that only ever needed the related entity's id (checked all 8 real ones, not assumed) got a new `getXxxId()`-style accessor instead of the full entity reference - one exception found needing more than an id, `HybridRetrievalService`'s use of `chunk.getDocument().getFilename()`, got a second accessor (`Chunk.getDocumentFilename()`) rather than exposing the whole entity. `Chunk`'s embedding `float[]` fields/setters and every plain-record DTO carrying a `List`/`Map` (`AskRequest.history`, `AskResponse.citations`, `EvaluateRequest.queries`, `PersistResult`, `SearchResultChunk.metadata`, etc.) got genuine defensive copying - one real behavioral bug found and fixed along the way, not just cosmetically: `ChunkPersistenceService` used to mutate the `Map` returned by `chunk.getMetadata()` in place, relying on it being the same reference as the entity's internal field; the defensive copy would have silently dropped that write, so it now builds its own map and calls `chunk.setMetadata(...)` explicitly instead. `IngestionPipelineService.run()`'s `REC_CATCH_EXCEPTION` was narrowed from `catch (Exception e)` to `catch (IOException \| RuntimeException e)` - exactly what the try block can actually throw, confirmed rather than assumed. A real regression was found live during this fix (not by inspection) and fixed before considering the work done: `ModelConfigRepository.findByTenantId` - a Spring Data JPA derived-query method - broke the moment `ModelConfig` gained a real `getTenantId()` convenience method, because Spring Data's property-path resolver started matching "tenantId" as a literal (non-existent) property instead of falling back to its own `tenant`+`id` nested-traversal disambiguation, producing invalid JPQL (`m.tenantId`) that failed with `UnknownPathException` at query-validation time on every single integration test that created a knowledge_base (81 of 101 integration tests failed this way, caught by running the full suite, not assumed clean from compilation alone). Fixed with an explicit `@Query("SELECT m FROM ModelConfig m WHERE m.tenant.id = :tenantId")`, matching the explicit-JPQL pattern every other relationship-navigating repository method in this codebase already used - this was the one exception that had relied on Spring Data's ambiguous-name fallback. Re-verified full suite green after the fix: 229 unit + 101 integration, 0 failures. `./mvnw spotbugs:check` now passes with a completely empty `spotbugs-exclude.xml` - confirmed, not assumed. |
| 81 | **`ImageExtractor`'s blank-content safety net doesn't catch a vision model's own non-committal hedge output - user chose a minimum-image-dimension floor** | Found during a sixth consecutive Stage 0-6 validation round (Session 47, `plan.md` §9 2026-07-19), while live-testing a small/thin real image (612x60px, legible text, confirmed by direct visual inspection) through the real pipeline with `gpt-5-nano`. Across repeated identical-shape uploads, the real vision call returned `""` (correctly fell back to RapidOCR, which also correctly found no text and failed the job), `"-"`, `"—"`, and once the literal sentence `"No readable text."` - the latter three are all non-blank, so `ImageExtractor`'s `!result.text().isBlank()` check accepted them as valid extracted content and let the document reach `READY` with a chunk that is never the real transcription. Isolated the true root cause via a controlled A/B, not guessed: the identical text rendered at 3x the linear resolution (1836x180px) transcribed correctly via the real vision call every time, in both RGB and RGBA - this is a small/cheap-tier vision model's practical resolution floor for dense/small text, not a code defect in how bytes are sent (ruled out an RGBA-alpha-channel theory specifically, since both color modes failed at the small size and both succeeded at the larger one). **User chose the minimum-image-dimension-floor fix** over a hedge-phrase deny-list or a second LLM self-verification call. Implemented: `ImageExtractor` gained a `synapsemcp.extraction.image.min-dimension-px` property (default **200**, same inline-`@Value`-default convention as `PdfExtractor`'s own tunables) - if either the normalized image's width or height is below this floor, vision is skipped entirely (never attempted, never billed) and the image goes straight to `RapidOcrService`, recorded as `ImageExtractor(ocr-fallback, min-size)` - mirrors `PdfExtractor`'s own page-limit skip (`PdfExtractor(pdfbox-fallback, page-limit)`), the same "known to be unreliable up front" pattern applied to a different dimension of unreliability. Existing test fixtures that happened to be below the new default floor (`ImageExtractorTest`'s 50x50 fixture, `PdfImageIngestionIntegrationTest`'s 300x80 fixture) were resized above it so they continue to exercise the vision/OCR-fallback paths they were originally written to test, rather than being silently redirected into the new skip path; a new dedicated test (`imageBelowMinDimensionSkipsVisionAndGoesStraightToOcr`) covers the skip path itself. This is a heuristic, not a complete fix - a genuinely low-resolution but still-legible image (e.g. a deliberately small icon-like screenshot) will now always go to OCR even though vision might have succeeded; the user weighed this against the alternative approaches' own trade-offs (a hedge-phrase list needs maintaining and can't cover phrasings not yet seen; self-verification doubles the billed vision calls) and chose this one. `./mvnw clean verify` green after the fix: 230 unit (+1) + 101 integration, unchanged. **Separately, during this fix: `spotbugs-exclude.xml` (Grooming #80's genuinely-empty filter, never committed to git since Session 3 - Session 46's own note that "all of Stage 3 onward remains uncommitted") was found missing from disk entirely at the start of this fix session, cause unknown** (not attributable to any command run in the validation session that preceded it, which had itself run `spotbugs:check` successfully partway through) - recreated with equivalent content (empty filter + the same explanatory comment) since it was silently breaking `spotbugs:check` outright (a missing-resource build error, not a findings failure) rather than left broken or worked around. |
| 82 | **Code-coverage acceptance criterion had never been measurable - no tool existed in 48 prior sessions** | Found during a seventh consecutive Stage 0-6 validation round (`plan.md` §9 2026-07-19), re-reading this file's own Acceptance Criteria fresh rather than from memory: "≥70% coverage on ingestion, retrieval, and tenant modules" has no corresponding tool in `pom.xml` anywhere in this project's history - every prior session's "verified" claims were about test counts and live behavior, never an actual coverage percentage. Asked the user rather than silently adding new build tooling; **user confirmed: add JaCoCo now**. Added `org.jacoco:jacoco-maven-plugin:0.8.12` with `prepare-agent` bound early (before `test`) and `report` bound to `verify` (after both surefire and failsafe have run), so the report reflects combined unit+integration coverage in one `./mvnw clean verify` run, not unit tests alone. Measured, not assumed: line coverage 94.6% (`com.synapsemcp.ingestion*`), 94.3% (`com.synapsemcp.rag.retrieve`), 95.1% (`com.synapsemcp.tenant`); branch coverage 81.7%/84.3%/87.5% respectively - all three modules comfortably clear the 70% bar on both metrics. |
| 83 | **A genuine ~100-page PDF had never been run through the pipeline - closed** | Honestly flagged as open since Grooming #79 (Session 45): only the page-limit-skip logic and small synthetic fixtures had ever been tested, not a real ~100-page document. Generated two real PDFs via PDFBox's own writer API (not guessed/assumed adequate without checking) - exactly 100 pages (the documented `maxLlmPages` boundary) and 101 pages, each with a distinct planted fact on page 42/none respectively. Verified live: both uploads returned `202` in under 150ms regardless of page count; concurrent unrelated API calls stayed fast (~40-60ms) throughout the ~115s the 100-page document spent processing 20 real batched `gpt-5-nano` vision calls in the background; the 100-page document reached `READY` via the genuine `PdfExtractor(llm)` path (not the fallback) with the page-42 planted fact (`BANANA-QUASAR-77`) correctly present in the chunks and correctly retrieved/answered via a live `/ask` call; the 101-page document correctly and immediately skipped straight to `PdfExtractor(pdfbox-fallback, page-limit)` with zero vision calls - confirms the exact page-count boundary behaves as designed on both sides, not just that a counter exists. |
| 84 | **Legacy `.doc`/`.xls`/`.ppt` binary Office formats had never been verified with real fixtures through the live pipeline** | `RealFormatIngestionIntegrationTest` (Session 44) covered real `.docx`/`.xlsx`/`.pptx`/HTML fixtures, but the plan's own documented scope boundary for legacy OLE2 formats ("legacy `.doc`/`.ppt` fall back to POI's own plain-text extraction utilities") had never actually been exercised with genuine legacy-format files, only unit-level assumptions about POI's fallback behavior. Generated real fixtures - `.doc` via macOS `textutil -convert doc`, `.xls`/`.ppt` via Apache POI's own legacy writer APIs (`HSSFWorkbook`, `HSLFSlideShow` - the same "real fixtures via the library's own writer API" convention established in Session 45) - and ran all three through the real live pipeline. All three reached `READY` with correctly-differentiated extractor names (`OfficeExtractor(doc-legacy)`, `OfficeExtractor(spreadsheet)`, `OfficeExtractor(ppt-legacy)`), byte-for-byte-correct extracted content confirmed via direct DB inspection, and a live `/ask` call correctly synthesized an answer citing the `.doc` source specifically. |
| 85 | **`spotbugs-exclude.xml` disappeared from disk a second time (root cause finally identified) - and two acceptance criteria got a genuine second empirical attempt with no change in outcome** | An eighth consecutive Stage 0-6 validation round (`plan.md` §9 2026-07-19) found `spotbugs-exclude.xml` missing again, exactly as in Grooming #81's first occurrence. This time the cause was identifiable: `git log`/`git status` showed a new commit ("implementation of phase 1 - rag app") had landed, covering everything since Session 3's "remains uncommitted" state, and the working tree was fully clean with zero untracked files - meaning the file, still untracked at the time, was never staged into that commit and was then swept away by a subsequent cleanup (e.g. `git clean -fd`) that the commit didn't protect it from. Not an environment bug; recreated with the same content and flagged to the user to consider committing it so this doesn't recur a third time. Separately, this round made real, deliberate second attempts at two acceptance criteria that Session 49 left honestly open, rather than re-stating the same caveat unchanged: (1) a larger, more adversarial 10-document/6-query golden set for hybrid-vs-vector-only (see the Acceptance Criteria section above) reproduced Session 41's exact tie result on different content; (2) a purpose-built near-duplicate-document scenario for reranking-measured-via-`/evaluate` found real embeddings plus RRF already ranked correctly even under a deliberately keyword-stuffed adversarial distractor, leaving no measurable room for reranking to improve the metric in either attempt. Both are now better-evidenced honest gaps rather than under-tested ones - the likely explanation in both cases is that `text-embedding-3-small` combined with RRF is simply strong enough on small, cleanly-authored test content that these two specific improvements only become measurable at a scale/noise level a hand-built golden set can't practically replicate. |
| 86 | **A ninth consecutive validation round, user-directed to re-attempt three previously-open acceptance criteria rather than leave them unchanged, closed all three with genuinely new evidence** | User's explicit instruction after a fresh full-system validation pass: don't just re-state Grooming #85's honest gaps, make one more real attempt at each with a different angle, and report the true result whichever way it goes. **(1) ANN sequential-scan-at-scale**, closed: the prior test's largest single-KB case (Grooming #73/#85, ~4,900-8,000 rows out of a 35,000-row table) never crossed the point where Postgres's cost-based planner would prefer HNSW over an exact B-tree-fetch-then-sort. Built a genuinely large synthetic dataset directly via SQL (313,000 total chunks, one knowledge_base with 200,000 rows, random vectors - fine for testing planner behavior, which is driven by row-count statistics, not similarity values) and confirmed live via `EXPLAIN ANALYZE` that the planner *does* choose the HNSW index at this scale (~2ms, vs. 521ms for a 15,000-row KB that still used the exact path). Bracketed the actual switchover threshold between 15,000 and 50,000 rows per knowledge_base - below it, exact search is the *correct*, cost-optimal choice; above it, the index is genuinely used. One practical finding along the way: bulk-inserting with the HNSW index already in place is extremely slow (incremental per-row graph maintenance - a 200,000-row insert never finished in 5+ minutes); dropping the index, bulk-loading, then rebuilding it in one pass (`maintenance_work_mem` raised to 2GB to avoid an on-disk graph build) took ~1 minute load + a few minutes to index - a real operational note for anyone bulk-seeding this schema, not just a test-methodology detail. **(2) Hybrid-vs-vector-only**, partially closed: Sessions 41/49's thematically-distinct golden sets (refund policies, etc.) never gave vector-only a real chance to fail, since embeddings distinguish different topics well. Switched to a genuinely different adversarial construction - 18 near-duplicate support-ticket documents, identical template text, differing *only* by an arbitrary numeric ticket id, queried by that same id - and finally reproduced a real vector-only failure (MRR 0.833, wrong on 4/18 queries; embeddings are known to be weak at distinguishing arbitrary digit strings embedded in otherwise-identical surrounding text). Hybrid (MRR 0.944, then 0.926 on a second run) beat vector-only both times - genuinely new evidence the acceptance criterion's first half is achievable, not just theoretically possible. Hybrid still didn't beat pure BM25 (keyword MRR 1.0) in this same construction - an honest, interesting nuance: RRF fusion, when a perfect keyword ranking and a partially-wrong vector ranking disagree, can land short of the better single signal, a property of rank fusion under close ties, not a code defect. **(3) Reranking's measurable benefit**, closed: reused the same near-duplicate-ticket dataset (deliberately, since hybrid retrieval was no longer perfect there, unlike Grooming #85's prior scenarios which left reranking no room to help) and compared `rerank=false` (MRR 0.944, 2 queries wrong) against `rerank=true` (MRR 1.0, all correct) on identical hybrid retrieval - the LLM reranker fixed both queries RRF fusion alone got wrong, the first genuine demonstration of a measurable `/evaluate`-metric improvement from reranking, not just previously-observed real reordering with no measured effect. All three findings obtained live against real OpenAI credentials already present in this environment, not simulated. |
| 87 | **A concurrent knowledge_base delete landing between chunk-persist committing and the Lucene write left genuinely orphaned index data, permanently unreachable by reconciliation - found and fixed during an architect-level concurrency/consistency audit** | User asked for a fresh, thorough audit of concurrency correctness under load on the single running instance (explicitly scoped away from multi-instance/horizontal-scaling concerns via a clarifying question first). Traced `TenantContext`/`RateLimitContext` ThreadLocal propagation across the one real `@Async` boundary (`ContextPropagatingTaskDecorator`, confirmed correctly wired), `IngestionReconciliationJob`'s own concurrency safety (confirmed already correct - avoids `TenantContext` entirely by design, and its `repairFailedJobsWithDurableChunks` already re-fetches and re-checks job status inside its own transaction to avoid stomping a concurrent retry), and `LuceneIndexManager`'s per-knowledge_base `ReentrantLock` (confirmed it correctly serializes concurrent writers, but - the actual finding - does **not** prevent a write from happening at all once a knowledge_base is deleted, since the lock is a mutual-exclusion mechanism, not an existence check: `IngestionPipelineService.run()` only verifies the job/document/knowledge_base still exist once, at the very start, with no re-check immediately before the Lucene write several stages later). Confirmed live, not guessed: a `delete_knowledge_base` call landing after `ChunkPersistenceService.persist` commits (chunks durably in Postgres) but before `LuceneIndexManager.indexChunks` runs leaves a genuinely orphaned Lucene index directory for a knowledge_base that no longer exists in Postgres at all - `IngestionReconciliationJob` can never find or clean it up, since that sweep only ever iterates `knowledgeBaseRepository.findAll()` (knowledge bases that still exist). Practical severity was low (orphaned but inert disk data, no data corruption or wrong query results reachable through any normal app path) - presented to the user as a genuine decision rather than silently fixed or silently left alone; **user chose to fix it now**. Fixed with a `knowledgeBaseRepository.existsById(knowledgeBaseId)` re-check immediately before the Lucene write - skips the write and logs a warning if the knowledge_base is already gone, closing the window down to a few-millisecond residual between that check and the write itself (accepted elsewhere in this codebase for structurally similar races). A genuine test-technique dead end along the way, worth recording: the first reproduction attempt used a `MockitoSpyBean` to intercept `ChunkPersistenceService.persist` mid-flight and trigger the concurrent delete from inside the spy's answer - this turned out to be unreliable, since `Mockito.doAnswer(...).callRealMethod()` on a `@Transactional` method doesn't go through Spring's transactional AOP proxy, so the method's own commit never actually happened before the spy's answer continued, and the injected concurrent delete then genuinely deadlocked against the still-open transaction's own row lock (a real Postgres `lock_timeout` cancellation, SQLState `55P03`) - a test-harness artifact, not the race being characterized. Sidestepped by testing the underlying primitive's behavior directly instead (real committed chunks, real delete, then a direct `indexChunks` call) for the integration-level regression test, and added a new, first-ever unit test class for `IngestionPipelineService` (`IngestionPipelineServiceTest`, fully mocked, no Spring context) to verify the actual orchestration-level fix in both directions - skips cleanly when the knowledge_base is gone, proceeds normally when it still exists. `./mvnw clean verify` green: 304 unit (+2) + 124 integration (unchanged shape, one test rewritten), zero regressions; `spotless:check`/`spotbugs:check` both clean after one `spotless:apply` pass. |
| 88 | **A delete landing during the embed stage (the one real network-call window before persist) is already caught by Hibernate's own stale-row detection - but the failure path itself then threw an identical exception uncaught, escaping the `@Async` method with only a generic Spring log line instead of this pipeline's own intentional failure message** | A "final" architect-level validation round, continuing Grooming #87's exact same concurrency-audit scope (user repeated the same request; treated as continuing under the already-clarified single-instance scope rather than re-asking). Targeted the one remaining gap in the pipeline's existence-checking: `run()` checks knowledge_base existence once right after `transitionToIndexing` (before `extract`/`chunk`/`embed`), then never again until Grooming #87's guard - `embed` is the one stage in that gap backed by a real network call to an external provider, the single most realistic point for a concurrent delete to land in practice, and never verified before this round. Reproduced without touching any `@Transactional` bean at all (avoiding Grooming #87's own Mockito dead end entirely): the already-mocked, plain `EmbeddingModel` bean was re-stubbed for one test to trigger a real concurrent delete from inside its own answer, before returning normal vectors. **Found live, not guessed - two things at once:** (1) good news first - no data corruption resulted, because the very next `markStage` call (`STAGE_PERSIST`, i.e. `persist()` itself is never even reached) throws `ObjectOptimisticLockingFailureException` on its own - Hibernate's own stale-row detection via a detached-entity `merge()` affecting zero rows, translated to this exception even with no explicit `@Version` column anywhere on `IngestionJob`/`Document`. This is a genuine, previously-unverified safety net covering *every* stage-transition write in the pipeline, not just the one window Grooming #87 explicitly guards - in practice it fires before Grooming #87's `existsById` check ever gets a chance to run for most realistic race windows, since real concurrent deletes take real wall-clock time (an HTTP round trip) that's overwhelmingly more likely to land during a slow stage like `embed` than in the sub-millisecond gap between `persist()` returning and the next line. (2) the actual gap - `markFailed`, called from the general `catch (IOException | RuntimeException e)` clause, then tries to update the identical already-gone row and throws the same exception itself, which escapes `run()` uncaught entirely (confirmed via the log: `SimpleAsyncUncaughtExceptionHandler`'s generic "Unexpected exception occurred invoking async method" line, not the pipeline's own `"ingestion job {} failed at stage {}"` message) - meaning `ingestionMetrics.recordFailure(...)` is never reached either, undercounting this failure category in the `synapsemcp.ingestion.failures` metric. Not data corruption, but a genuine monitoring blind spot - presented to the user as a decision (same pattern as Grooming #87); **user chose to fix it now**. Fixed with a dedicated `catch (ObjectOptimisticLockingFailureException e)` clause, ordered before the general `RuntimeException` catch, that logs a clean, intentional warning and returns without ever attempting `markFailed` for this specific exception type. New unit test added to `IngestionPipelineServiceTest` reproducing the exception from the earliest possible save call (`transitionToIndexing`'s own `documentRepository.save`), confirming `run()` never propagates it and never attempts a second (`markFailed`) save. `./mvnw clean verify` green: 305 unit (+1) + 125 integration (+1), zero regressions; `spotless:check`/`spotbugs:check` both clean after one `spotless:apply` pass. |
| 89 | **User-requested (2026-07-22): duplicate knowledge base names within a tenant needed an explicit code-level check, made concurrency-proof, not left solely to the database's own unique constraint** | `KnowledgeBaseService.createWithinLock` had zero application-level duplicate-name checking - only the pre-existing `uq_knowledge_bases_tenant_name_ci` constraint caught it, meaning REST got a generic, non-specific 409 (`ApiExceptionHandler`'s existing catch-all `handleDataIntegrityViolation`) and MCP's `create_knowledge_base`/`update_knowledge_base` tools would leak the raw `DataIntegrityViolationException` (constraint name, table, column, offending value) straight to the client - the exact same information-disclosure shape as Grooming #29's `switch_knowledge_base` finding, just never checked for this call path. Fixed with a new `requireNameNotTaken` helper (using the existing `findByNameIgnoreCaseAndTenant_Id`) called from both `createWithinLock` and `updateKnowledgeBase`, throwing a clean, name-specific `ApiException(CONFLICT, ...)` - shared by REST and MCP alike, since both transports call the identical domain service. Concurrency-proofed with two different guarantees depending on which write path is racing: create-vs-create is already fully serialized by the existing per-tenant `tenantRepository.lockById` pessimistic lock (the same lock the 10-KB-per-tenant count check already relies on), so the pre-check alone is airtight there; `updateKnowledgeBase` (rename) holds no such lock and can race a concurrent create or another concurrent rename, so both `createWithinLock` and `updateKnowledgeBase` also call `knowledgeBaseRepository.saveAndFlush(...)` (forcing the flush to happen synchronously inside the method, not deferred to an enclosing transaction's own commit) wrapped in a `catch (DataIntegrityViolationException)` that translates the exact same clean conflict message - the database's own unique index is the only thing that can never be raced past, mirroring the exact catch-and-translate pattern already established for `McpActiveKnowledgeBaseSwitchingService` (Grooming #29). A rename to a knowledge base's own current name (e.g. a no-op or case-only change) is correctly excluded from the conflict check via an `excludingKnowledgeBaseId` parameter (`null` for create, so every existing row counts as a conflict there). New unit tests cover the pre-check on both create and rename, the self-name exclusion on rename, and the constraint-violation backstop translating cleanly on both paths (using `Objects.equals`-based null-safe id comparison, since a not-yet-persisted `KnowledgeBase.create(...)` test fixture's `@GeneratedValue` id is null until Hibernate assigns it - a real test-construction gap, not a production behavior, worth remembering for any future test needing to compare a fetched entity's id). New live-concurrency integration test (`concurrentKnowledgeBaseCreationWithTheSameNameOnlyOneSucceeds`, mirroring the existing `concurrentKnowledgeBaseCreationNeverExceedsTheLimit` pattern): 10 concurrent creates with the identical name for one tenant - exactly 1 succeeds (`201`), every other gets a clean `409`, and exactly one row for that name exists afterward. `./mvnw clean verify` green (299 unit + 131 integration by method count), zero regressions; `spotless:check`/`spotbugs:check` both clean after one `spotless:apply` pass. |

---

*Finalized from the draft `RAG_APP_SUBPLAN.md` with all grooming decisions resolved. This is the authoritative RAG-app plan.*
