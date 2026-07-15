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
| `memory.md` | Living execution log (current phase, completed tasks, decisions/fixes). Not a roadmap. |

This plan **does not restate** shared conventions (RFC 7807 errors, correlation IDs, `./mvnw clean verify` as the done-gate, Conventional Commits). Those live in `PLAN.md` §4 and `CLAUDE.md` and apply here unchanged. (Note: Package layout is defined in Stage 0).

---

## 2. Scope

### In Scope — The RAG App (the 6-step flow)

- Tenant provisioning
- Model config (with optional credential encryption)
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
- Flyway-owned schema: `tenants`, `api_keys`, `model_configs`, `knowledge_bases`, `documents`, `chunks` (pgvector column), `ingestion_jobs` — full column-level definitions and DDL in **Stage 0.5** below.
- Multi-tenancy: **shared schema + `tenant_id` column + enforced filtering**; `tenant_id` on every tenant-scoped table with a composite index.
- API-key auth filter (SHA-256 at rest) → request-scoped `TenantContext`.
- **`TenantContext` + correlation-ID propagation into `@Async` executors** via `TaskDecorator` — the single easiest-to-miss, highest-blast-radius piece; keep its guardrail tests.

### Tenant Isolation Testing Strategy

> **✅ Decision (Grooming #1):** Repository-level tenant-isolation guardrail tests are **folded into each stage** — each stage adds its own isolation tests as it's built, rather than building them all upfront as a Stage-0 prerequisite. Every stage's "Done when" criteria include isolation verification specific to that stage's data.

### No Docker — Native Services Only

> **No Docker anywhere in the RAG app.** Local dev **and** the test suite run on **natively installed PostgreSQL 17 + pgvector + Redis** — never Docker, Docker Compose, Podman, or Testcontainers (matches `PLAN.md` §3 / §9 decisions). No container runtime is a dependency for building, running, or testing this app. Services are started natively (`brew services start …` or `scripts/setup-environment.sh` / `.ps1`); integration tests hit an isolated native `synapsemcp_test` database + Redis index 1, not an ephemeral container. Production containerization is a separate, out-of-scope Phase 3 concern and must not leak into this plan.

### Hard Rule — The App Must Start with No DB/Schema Present

> The Spring Boot process comes up (HTTP listener live, `/actuator/health` responding) even if Postgres is unreachable, the `synapsemcp` database doesn't exist yet, or migrations haven't run. This is a deliberate departure from Spring Boot's default fail-fast datasource/Flyway behavior and needs explicit design:
>
> - **Non-blocking connection pool.** HikariCP configured with `initialization-fail-timeout: -1` (or `0`) so pool creation never throws at context-startup even if Postgres refuses the connection — the pool is created lazily and simply retries on first real use.
> - **Flyway does not run automatically on the default startup path.** `spring.flyway.enabled: false`; migrations are instead triggered by the self-bootstrapping runner (Stage 0.5) which retries independently of the main application context startup, so a slow/absent DB never blocks the app from listening.
> - **Readiness vs. liveness are distinct.** `/actuator/health/liveness` reflects only "the JVM/HTTP server is up" (always green once boot completes); a custom `SchemaReadinessHealthIndicator` feeds `/actuator/health/readiness` and is `DOWN` until bootstrap+migration have succeeded at least once. Load balancers/orchestrators should gate traffic on readiness, not liveness.
> - **DB-dependent endpoints fail gracefully, not the app.** Any request touching a repository before schema is ready returns a clear `503` (RFC 7807 problem-detail, "database not ready yet") rather than a stack trace or a hung connection-pool wait.
> - **This does not weaken tenant isolation or Flyway-as-source-of-truth** — `ddl-auto: validate` still applies once schema exists; the only change is *when* and *how* migrations are triggered, not what they do.

### Package Structure

The application must strictly follow this organization. **All** RAG-related code (extraction, chunking, embedding, vector processing, retrieval, etc.) must be added to the `rag` package.

- `com.synapsemcp` (Standard root package for the application)
- `com.synapsemcp.core` (Core business logic and service layers)
- `com.synapsemcp.mcp` (Model Context Protocol client/server handling components)
- `com.synapsemcp.rag` (Retrieval-Augmented Generation and vector processing modules)

---

# Stage 0.5 — Data Model / Schema (Finalized)

The authoritative, finalized table definitions for the RAG app. **Flyway-owned schema only** — `ddl-auto: validate` in every profile (Hibernate never mutates the schema; every change is a new versioned migration). PostgreSQL 17+, `vector` extension enabled in `V2`. Every table uses `UUID PRIMARY KEY DEFAULT gen_random_uuid()`.

## Self-Executing Bootstrap

Because the app must start with **no DB or schema present** (Stage 0's hard rule), schema creation can't be a one-time developer step or a blocking part of `main()` — it has to be a component the app runs and retries itself, entirely separate from the normal request-serving path.

**Why this needs two connections, not one.** The app's own JPA/Flyway datasource connects *to* the `synapsemcp` database — but that database might not exist yet, so that connection can't be what creates it. A `CREATE DATABASE` statement also can't run inside a transaction/migration once connected to the target DB. So bootstrap is split into two steps against two different targets:

1. **`DatabaseBootstrapRunner`** (`com.synapsemcp.config`, a `Runnable` on a `@Scheduled` retry — *not* an `ApplicationRunner` that blocks startup): opens a **plain JDBC connection** (not the app's pooled datasource) to Postgres's `postgres` maintenance database using bootstrap credentials, and idempotently:
   - `CREATE ROLE synapsemcp WITH LOGIN PASSWORD '...'` — catches/ignores the "role already exists" error (Postgres has no `CREATE ROLE IF NOT EXISTS`)
   - `CREATE DATABASE synapsemcp OWNER synapsemcp` — same idempotent catch on "database already exists"
   - Reconnects to the now-guaranteed-to-exist `synapsemcp` database and runs `CREATE EXTENSION IF NOT EXISTS vector` (this one *does* support `IF NOT EXISTS` directly)
2. **Flyway migration**, triggered immediately after step 1 succeeds, against the app's normal datasource — runs `V1__init_schema.sql` (below) to create all seven tables.
3. On success, an internal `schemaReady` flag flips to `true`, which is what `SchemaReadinessHealthIndicator` (Stage 0) reports.

**Retry behavior:** if Postgres itself is unreachable (not just "database missing"), step 1 logs a warning and reschedules (e.g. every 10s, capped backoff) rather than throwing — this is what lets the app boot and stay up indefinitely with zero DB present, then self-heal the moment Postgres becomes reachable, with no restart and no manual migration command.

> **✅ Decision (Grooming #18 — Bootstrap credentials):** `DatabaseBootstrapRunner` reuses the **local-dev SUPERUSER role** for `CREATE ROLE`/`CREATE DATABASE`-capable credentials. This bootstrap behavior is **`local`/`dev`-profile-only**. In `prod`, schema creation reverts to a manual one-time step, and the self-execution behavior is disabled. No separate bootstrap-only credential is needed.

## Migration File (`V1__init_schema.sql` — Authoritative Copy)

Lives at `src/main/resources/db/migration/V1__init_schema.sql` — a single consolidated migration (if merging into an existing codebase's `V1`–`V6` history from `PLAN.md`, renumber to the next free version):

```sql
-- V1__init_schema.sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE tenants (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE api_keys (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  key_hash TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_api_keys_key_hash ON api_keys (key_hash);
CREATE INDEX idx_api_keys_tenant_id ON api_keys (tenant_id);

CREATE TABLE model_configs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL UNIQUE REFERENCES tenants(id),
  chat_provider TEXT NOT NULL CHECK (chat_provider IN ('openai', 'anthropic', 'ollama', 'google-genai')),
  chat_model TEXT NOT NULL,
  embedding_provider TEXT NOT NULL CHECK (embedding_provider IN ('openai', 'ollama', 'google-genai')),
  embedding_model TEXT NOT NULL,
  provider_credentials TEXT,   -- JSON: {"chatApiKey": "", "embeddingApiKey": ""} (optionally encrypted — see Stage 2)
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE knowledge_bases (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  name TEXT NOT NULL,
  embedding_dim INT NOT NULL,
  UNIQUE (tenant_id, name)
);

CREATE TABLE knowledge_base_model_configs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  knowledge_base_id UUID NOT NULL UNIQUE REFERENCES knowledge_bases(id) ON DELETE CASCADE,
  chat_provider TEXT NOT NULL,
  chat_model TEXT NOT NULL,
  embedding_provider TEXT NOT NULL,
  embedding_model TEXT NOT NULL,
  provider_credentials TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE documents (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL,
  knowledge_base_id UUID NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
  filename TEXT NOT NULL,
  file_type TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('PENDING', 'INDEXING', 'READY', 'FAILED')),
  content_hash TEXT NOT NULL,
  extractor_name TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_documents_tenant_kb ON documents (tenant_id, knowledge_base_id);
CREATE UNIQUE INDEX idx_documents_tenant_kb_content_hash
  ON documents (tenant_id, knowledge_base_id, content_hash);

CREATE TABLE chunks (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL,
  document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
  chunk_index INT NOT NULL,
  content TEXT NOT NULL,
  embedding_384 vector(384),
  embedding_512 vector(512),
  embedding_768 vector(768),
  embedding_1024 vector(1024),
  embedding_1536 vector(1536),
  embedding_3072 vector(3072),
  metadata JSONB NOT NULL DEFAULT '{}'
);
CREATE INDEX idx_chunks_tenant_doc ON chunks (tenant_id, document_id);
CREATE INDEX idx_chunks_emb_384 ON chunks USING hnsw (embedding_384 vector_cosine_ops);
CREATE INDEX idx_chunks_emb_512 ON chunks USING hnsw (embedding_512 vector_cosine_ops);
CREATE INDEX idx_chunks_emb_768 ON chunks USING hnsw (embedding_768 vector_cosine_ops);
CREATE INDEX idx_chunks_emb_1024 ON chunks USING hnsw (embedding_1024 vector_cosine_ops);
CREATE INDEX idx_chunks_emb_1536 ON chunks USING hnsw (embedding_1536 vector_cosine_ops);
CREATE INDEX idx_chunks_emb_3072 ON chunks USING hnsw (embedding_3072 vector_cosine_ops);

CREATE TABLE ingestion_jobs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL,
  document_id UUID NOT NULL UNIQUE REFERENCES documents(id) ON DELETE CASCADE,
  status TEXT NOT NULL CHECK (status IN ('PENDING', 'INDEXING', 'READY', 'FAILED')),
  stage TEXT,
  error_detail TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ingestion_jobs_tenant_doc ON ingestion_jobs (tenant_id, document_id);
```

## Table Definitions (Reference)

### `tenants`

| Column | Type | Constraints |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
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
| `provider_credentials` | TEXT | JSON: `{"chatApiKey": "", "embeddingApiKey": ""}` — optionally encrypted (see Stage 2 encryption flag) |
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
| `provider_credentials` | TEXT | JSON (optionally encrypted) |
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
| `embedding_*` | `vector(*)` | **Sparse Dimension Columns** (`384`, `512`, `768`, `1024`, `1536`, `3072`). Only the column matching the knowledge_base's `embedding_dim` is populated. |
| `metadata` | JSONB | NOT NULL, default `'{}'` |

Indexes: `idx_chunks_tenant_doc` on `(tenant_id, document_id)`. Plus HNSW ANN indexes on every sparse dimension column (e.g., `idx_chunks_emb_1536`) created statically in `V1__init_schema.sql` to avoid runtime DDL.

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

### Migration File (Pointer)

The executable migration is `V1__init_schema.sql`, given in full above under *Self-Executing Bootstrap*. It is not repeated to avoid two drifting copies of the same DDL.

---

# Stage 1 — Create Tenant

**Flow step [1].** Provision a tenant and hand back its initial API key.

| Aspect | Detail |
|---|---|
| Endpoint | `POST /api/v1/tenants` |
| Auth | **Open / unauthenticated** (no admin tier exists — `PLAN.md` §9 decision) |
| Abuse guard | Per-IP fixed-window limiter (`TenantCreationRateLimitFilter`), **5 requests/hour/IP**, fails **open** if Redis is down |
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
| Credential storage | **Optional encryption** — plaintext by default (self-hosted tradeoff), with an **encryption flag** to enable encrypted-at-rest credentials |
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

> **✅ Decision (Grooming #3 — Credential storage):** Add an **optional encryption flag** for credentials in this milestone. By default, credentials are stored as plaintext JSON (self-hosted tradeoff). When the encryption flag is enabled, credentials are encrypted at rest. This balances the self-hosted simplicity with security-conscious deployments.

> **✅ Decision (Grooming #22 — API Key Auto-Sync):** If a tenant updates their global provider credentials in `model_configs` (e.g. key rotation), the system automatically syncs the new credentials to all existing `knowledge_base_model_configs` rows that share the same `chat_provider` or `embedding_provider`. The model names themselves remain permanently locked to the knowledge_base.

> **✅ Decision (Grooming #4 — Model config validation):** No eager validation of model names at config write time. Instead:
> - **During file upload:** if ingestion fails for any valid reason (including bad model name), **do not add/persist the entry in the DB** — return a proper response message with an appropriate status code.
> - **During ask time:** if the call fails (including due to an invalid model), return a proper response explaining why it failed, along with the appropriate HTTP status code.

> **✅ Decision (Grooming #15 — `ChatOptions`):** **Generic `ChatOptions.builder()` is correct** and works per-call. The sub-plan's original note about needing concrete per-provider `*ChatOptions` to avoid `ClassCastException` is stale — fix the sub-plan to match the master `PLAN.md` §9 decision. `ChatModelFactory.optionsFor(...)` uses the generic builder.

### Done When

- A tenant can set and retrieve a config.
- A request-time factory resolves the correct provider/model from it without an app restart.
- Credentials never leak in responses or logs.
- Optional encryption flag works correctly when enabled.
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

### Done When

- A tenant can create, list, update, and delete its own knowledge_bases.
- Another tenant's knowledge_bases never appear in the list.
- `embedding_dim` is auto-derived and fixed at creation; the caller doesn't specify it.
- Attempting to create a knowledge_base without a `model_configs` row returns `422`.
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
| Sync checks | Content-type validated **synchronously** (415 on unsupported type before any async work); size cap (20 MB → 413) |
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

Job state machine: `PENDING → INDEXING → READY | FAILED` (`ingestion_jobs`, with per-stage `stage` for observability).

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

> **✅ Decision (Grooming #7):** A **basic reconciliation cron** is included in this milestone. The cron detects drift between the Lucene index and Postgres `chunks` table (e.g., orphaned Lucene entries, missing index entries for committed chunks) and repairs them. Runs at a configurable interval (e.g., every hour). Does not need to be real-time — eventual consistency is acceptable.

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
> - HNSW indexes are created statically on these columns during the `V1__init_schema` migration.
> - At ingestion and query time, the app routes the vector to the specific column that matches the Knowledge Base's `embedding_dim`. This guarantees fast ANN search with zero operational runtime DDL risk.

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
| **Errors** | RFC 7807 problem-details on every endpoint; all under `/api/v1`. Missing model config → `422`. DB not ready → `503`. Unsupported type → `415`. File too large → `413`. |
| **Observability** | Correlation ID generated/propagated on every request and into async threads via `TaskDecorator`. |
| **Config & secrets** | Per-profile YAML; secrets only via env vars; never logged. Optional credential encryption (Stage 2). |
| **Done-gate** | A task is only done when `./mvnw clean verify` is green, the behavior is tested at the right level, and `MEMORY.md` is updated. |

---

## Build Order (Milestones)

> **✅ Decision (Grooming #12):** **Breadth-first** build order (as originally drafted).

The stages are built in this order:

### Milestone 1 — Setup Path Works
**Stages 1 → 2 → 3** callable end-to-end (tenant, config, knowledge_base) with isolation.
- Tenant creation with API key
- Model config (with optional encryption)
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
- [ ] Optional credential encryption works when enabled.
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
| 3 | Credential storage | Optional encryption flag added in this milestone |
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
| 18 | Bootstrap credentials | Reuse local-dev SUPERUSER; bootstrap is local/dev-profile only |
| 19 | Error contract (no model config) | `422 Unprocessable Entity` — "model config not set for tenant" |
| 22 | API Key Auto-Sync | Sync updated `model_configs` credentials to existing knowledge_base snapshots |
| 23 | knowledge_base Model Lock / Snapshot | Snapshot the global model config into a knowledge_base-specific table on creation to prevent semantic drift |
| 24 | ANN Index Strategy | Use "Sparse Dimension Columns" (384, 512, 768, 1024, 1536, 3072) with static schema HNSW indexes to avoid runtime DDL |
| 25 | knowledge_base Limit | Maximum of 10 Knowledge Bases per tenant |
| 26 | Retry via re-upload on FAILED | Duplicate upload against a `FAILED` job resets and re-dispatches the same document/job in place, rather than staying permanently stuck |
| 27 | Reranker model correction | Replaced English-only `ms-marco-MiniLM-L-6-v2` example with genuinely multilingual `BAAI/bge-reranker-v2-m3` |
| 28 | MCP `ingest` input shape | Accepts file bytes or raw text only, normalized into the same pipeline as REST upload; URL/"reference" ingestion explicitly out of scope, deferred to external connectors (§2/Milestone 7) |

---

*Finalized from the draft `RAG_APP_SUBPLAN.md` with all grooming decisions resolved. This is the authoritative RAG-app plan.*
