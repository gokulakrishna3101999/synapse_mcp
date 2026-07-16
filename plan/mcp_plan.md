# SynapseMCP — MCP App Plan (Phase 2)

> The authoritative, dedicated plan for building **Phase 2: MCP Integration & API Management**.
> This plan focuses on exposing the core RAG capabilities (built in Phase 1) via the Model Context Protocol (MCP) and adding production caching/rate-limiting.

---

## Table of Contents

1. [Scope](#1-scope)
2. [Stage 1: MCP Server Implementation](#stage-1-mcp-server-implementation)
3. [Stage 2: Tool Definitions](#stage-2-tool-definitions)
4. [Stage 3: Caching & Rate Limiting](#stage-3-caching--rate-limiting)
5. [Acceptance Criteria](#acceptance-criteria)

---

## 1. Scope

### In Scope
- Official MCP Java SDK integration
- Transport mechanism: HTTP (Streamable HTTP / SSE) only — `stdio` explicitly excluded (Grooming #1)
- Username/Password (Basic Auth) for MCP via new `mcp_users` table
- 13 core MCP tools mapped to Phase 1 REST endpoints
- Redis-based rate limiting (Bucket4j)
- Cache hit-rate metrics for the embedding/OCR caches already built in `rag_plan.md` (not new caches)

### Out of Scope
- Frontend / Web UI
- Core RAG pipelines (already covered in `rag_plan.md`)
- Containerization and Terraform (deferred to Phase 3)

---

## Phase 2 — MCP Integration & API Management

## Stage 1: MCP Auth & Server Implementation

> **✅ Decision (Grooming #1 — Transport Mechanism):** The MCP server will exclusively use the **HTTP (SSE)** transport. Since the app is designed to run as a multi-tenant web service (both locally and in the cloud), exposing it as a long-lived HTTP server is the most robust and idiomatic approach. `stdio` will not be implemented to avoid treating the web application as a background CLI script.

> **✅ Decision (Grooming #11 & #12 — MCP Authentication Flow):** The MCP server will use a dedicated **Username and Password** authentication model (via HTTP Basic Auth) instead of requiring AI clients to manage raw Tenant API Keys. AI clients (like Claude Desktop) will connect to the SSE endpoint using `Authorization: Basic <base64>`. A new open REST endpoint (`POST /api/v1/mcp-users/register`) will allow humans to register these MCP user accounts.

> **✅ Decision (Grooming #13 — Tool Access Control & Tenant Linkage):** When an MCP user authenticates, if their account is not yet linked to a tenant, an MCP interceptor will block all tools EXCEPT `create_tenant`. When they call `create_tenant`, the system will provision the tenant, generate the API key, and permanently save the `tenant_id` on the user's `mcp_users` record. For all subsequent tool calls, the MCP layer will automatically inject this `tenant_id` into the `TenantContext`, allowing seamless consumption of all RAG tools without the AI ever needing to handle the raw API key.

> **✅ Decision (Grooming #14 — Link at Registration):** To allow humans who already created a tenant via the REST API to use MCP without creating a second duplicate tenant, the registration endpoint will accept an optional `api_key`. If provided, the system validates the key, extracts the existing `tenant_id`, and immediately saves it to the new `mcp_users` row. This strictly prevents raw API keys from ever being passed through AI chat prompts.

> **✅ Decision (Grooming #15 — Rate Limiting on MCP User Registration):** `POST /api/v1/mcp-users/register` is open/unauthenticated and is the actual repeatable step an abuser needs (each registered user can only ever link to one tenant, per Grooming #13/#16 below — so registration volume, not tenant-linking, is the real abuse surface for MCP-side tenant creation). It reuses the same per-IP fixed-window limiter pattern as `TenantCreationRateLimitFilter` (`plan.md` §9): **5 requests/hour/IP**, fails **open** if Redis is unreachable, keyed on `request.getRemoteAddr()` (not `X-Forwarded-For`, for the same spoofing reason as the REST limiter). This also protects the endpoint's `api_key`-validation branch from being used to brute-force/enumerate real API keys. No separate rate limit is placed on the `create_tenant` MCP tool itself — it is already self-limiting to one successful call per linked user (Grooming #13), so limiting it further would only add friction to legitimate retry-after-failure without closing any real gap.

> **✅ Decision (Grooming #16 — Atomic Tenant Linkage, Race-Condition Guard):** The interceptor's "is `mcp_users.tenant_id` null?" pre-check (Grooming #13) is a read, not a lock — two concurrent `create_tenant` calls from the same not-yet-linked user could both pass it before either commits, each provisioning a real tenant + API key, with only one able to win the link. To close this: tenant creation, API-key issuance, and the conditional linking update are all executed inside **one `@Transactional` boundary** — `UPDATE mcp_users SET tenant_id = :newTenantId WHERE id = :mcpUserId AND tenant_id IS NULL`. If that update affects 0 rows (another concurrent call already linked this user), the MCP tool handler throws, and Spring's transaction manager **rolls back the entire transaction** — the tenant and API-key inserts are undone as part of the same rollback, never committed in the first place. This is a plain transactional rollback, **not** a separate compensating delete step, so no tenant-deletion capability needs to exist anywhere else in the system for this path. The call returns the same explicit "already linked to tenant X" error as a normal duplicate `create_tenant` call — the user is left with exactly one tenant either way, and nothing is ever persisted for the race loser.

- [ ] Create `mcp_users` table: `id`, `username` (UNIQUE), `password_hash`, `tenant_id` (Nullable FK), `created_at`.
- [ ] Implement open REST endpoint: `POST /api/v1/mcp-users/register`.
  - JSON body: `{ "username": "...", "password": "...", "api_key": "..." (optional) }`
  - Hashes password with **`BCryptPasswordEncoder`** (Spring Security's adaptive/salted default) — deliberately **not** the fast `SHA-256` scheme `rag_plan.md` Stage 0 uses for `api_keys.key_hash`. API keys are high-entropy random secrets where a fast hash is fine; human-chosen passwords need a slow, salted algorithm to resist offline brute-forcing. If `api_key` is present, validates it against `api_keys` table and saves the associated `tenant_id` to the new user.
  - Per-IP rate limited: 5 requests/hour/IP, fail-open on Redis outage (Grooming #15).
- [ ] Add MCP Java SDK and configure Streamable HTTP (SSE) transport (`WebMvcSseServerTransport`).
- [ ] Configure Spring Security to secure `/mcp/sse` and `/mcp/message` using HTTP Basic Auth against the `mcp_users` table.
- [ ] Implement MCP Interceptor:
  - If `mcp_users.tenant_id` is null, block all tools except `create_tenant`.
  - If `mcp_users.tenant_id` is NOT null, inject it into `TenantContext` for the duration of the tool execution.

### Usage Flow: Registration & Tenant Linkage
1. **Scenario A (Brand New User):** Human calls `POST /register` with just `username/password`. They configure their AI client with these credentials. The AI connects, sees the tenant is missing, calls the `create_tenant` MCP tool, and the system securely links the new tenant to the user behind the scenes.
2. **Scenario B (Existing REST User):** Human already has an API key from Phase 1. They call `POST /register` with `username/password` AND their `api_key`. The system links their existing tenant. They configure their AI client, and the AI immediately has access to all tools (`ask`, `search`, etc.) without ever calling `create_tenant`.

## Stage 2: Tool Definitions (Official MCP Java SDK)

> **✅ Decision (Grooming #2 — Tools vs REST Layering):** MCP tools will **directly invoke the underlying domain services** (e.g., `TenantService`, `RagAnsweringService`). This completely bypasses the REST Controllers, avoiding HTTP loopback overhead and aligning with idiomatic Spring Boot architecture. The REST layer and the MCP layer will both act as equal-peer consumers of the core services.

> **✅ Decision (Grooming #4 — Long-Running Ingestion over MCP):** AI clients will be explicitly guided on how to handle async tasks. The MCP `ingest` tool description will include an explicit system prompt instruction: *"This tool returns immediately with a Job ID. You MUST use the job_status tool every 5 seconds until the job completes."* This prevents AI hallucinations regarding job completion.

> **✅ Decision (Grooming #7 — Tool Schema Generation):** All MCP tool inputs will be defined as standard Java `record` classes. We will rely on the official MCP SDK and Spring's Jackson integration to automatically generate the strict JSON schemas from these records, preventing manual schema drift.

- [ ] Implement tools:
  - [ ] `create_tenant` — provisions a new tenant and issues its initial API key; **Updates the caller's `mcp_users` row** to link this new `tenant_id` permanently to their session via an atomic `WHERE tenant_id IS NULL` conditional update, all inside one transaction (Grooming #16) — a 0-row update throws and the whole transaction (tenant + API key + link attempt) rolls back, returning the standard "already linked" error (MUST fail with an explicit error if the user is already linked to a tenant, preventing accidental overwrites or orphaned tenants under concurrent calls).
  - [ ] `get_tenant` — return the authenticated user's tenant details: name, created date, KB count, and a document-count/status-summary rollup across those KBs. ("Usage summary" here means these aggregate counts only — there is no historical request-volume or cost-tracking feature anywhere in this plan; per-tenant/provider spend is bounded by Stage 3's rate limiter, not measured/reported here.)
  - [ ] `configure_model` — set the tenant's `chat_provider`, `chat_model`, `embedding_provider`, `embedding_model`, and provider credentials (mandatory prerequisite before creating a KB); same fields as `PUT /api/v1/tenants/{tenantId}/model-config` (`rag_plan.md` Stage 2).
  - [ ] `create_knowledge_base` — create a knowledge base for the authenticated tenant; params: `name` **only** — `embedding_dim` is never a caller input. It is auto-derived server-side via a live probe of the tenant's configured embedding model and locked at creation (`rag_plan.md` Stage 3, Grooming #5b / `plan.md` §9 2026-07-16).
  - [ ] `update_knowledge_base` — rename an existing knowledge base.
  - [ ] `delete_knowledge_base` — permanently delete a knowledge base and all its associated documents/chunks.
  - [ ] `list_knowledge_bases` — list knowledge bases for the authenticated tenant (name, doc count, status summary).
  - [ ] `search` — hybrid retrieval; params: `knowledgebase`, `query`, `top_k`, `mode` (hybrid/vector/keyword).
  - [ ] `ask` — RAG answer with citations; params: `knowledgebase`, `question`, optional history. Calls `RagAnsweringService.ask` in its **non-streaming** mode (`.call(...)`, not `.stream(...)`) and returns the complete answer + citations in a single tool result — `rag_plan.md` Stage 6b's SSE streaming is a REST-only capability, since an MCP tool call has no equivalent token-streaming response shape.
  - [ ] `ingest` — accepts **either** `filename` + `content_base64` (file bytes, routed through the same `DocumentExtractionService`/Tika-sniffing entry point Stage 4's REST upload uses — see Grooming #28 in `rag_plan.md`) **or** a raw `text` field (wrapped as a synthetic `.md` document with `mimeType` set directly to `text/markdown`, skipping MIME-sniffing since the content's nature is already known). Starts an async job, **returns a job ID immediately**. Does **not** accept a URL/external-source "reference" — external connectors (GitHub, Notion, Confluence) remain SPI-only per `rag_plan.md` §2 / Milestone 7 and `plan.md` §6, deferred until a concrete connector is implemented; promising URL-based ingestion here would silently contradict that existing scope decision.
  - [ ] `job_status` — poll job progress by job ID (companion to `ingest`).
  - [ ] `get_document_status` — document upload/indexing status by document ID (state `PENDING | INDEXING | READY | FAILED`, error detail if failed).
  - [ ] `evaluate` — run the golden-query harness against a KB; returns per-mode relevance metrics.
- [ ] Tool input schemas defined as `record` classes with strict validation and helpful error messages.
- [ ] Clear, descriptive tool descriptions — they are effectively the "UI" for AI clients.
- [ ] Integration test: scripted MCP client logging in via Basic Auth, calling `create_tenant`, and exercising every tool against a seeded KB.

## Stage 3: Caching & Rate Limiting (Redis)

> **✅ Decision (Grooming #3 — Rate Limiting Library):** We will use **Bucket4j** (backed by Redis) for per-tenant/per-provider rate limits. The advanced token-bucket features provided by Bucket4j are necessary for robust rate limit enforcement, justifying the extra dependency.

> **✅ Decision (Grooming #5 — Rate Limiting Enforcement Point):** Since MCP tools bypass REST controllers to call domain services directly, Bucket4j rate limiting MUST be implemented as a Spring AOP Interceptor (`@Aspect`) directly on the **Domain Service layer**. This guarantees that both REST traffic and MCP traffic consume from the same token buckets and are limited equally.

- [ ] Embedding cache (from 1.5) reviewed for hit-rate; add metrics
- [ ] OCR cache (already built in `rag_plan.md` Stage 5a, 24-hour TTL keyed by content hash) reviewed for hit-rate; add metrics — not new work, same pattern as the embedding cache above
- [ ] Per-tenant rate limiting: token-bucket in Redis via Bucket4j, keyed by `tenant_id + provider` — separate buckets per provider since cost/latency profiles differ
- [ ] Rate-limit headers on REST responses (`X-RateLimit-Remaining`, `Retry-After`); structured MCP errors on limit breach
- [ ] Graceful Redis-down behavior: fail open for cache, fail closed (or conservative local limit) for rate limiting — document the choice

## Cross-Cutting Concerns (Mapped from Master Plan)

> **✅ Decision (Grooming #8 — Error Translation):** Domain services throw standard Java exceptions. The MCP layer MUST implement an error-handling interceptor/adapter that catches these domain exceptions and translates them into proper MCP/JSON-RPC error codes (just as the REST layer translates them into RFC 7807 Problem Details). The SSE connection must not crash on a domain error.

> **✅ Decision (Grooming #9 — Observability & MDC):** The MCP HTTP filter chain must generate a Correlation ID for every incoming request and inject it into the SLF4J MDC, exactly like the REST layer. This ensures all logs generated by MCP tool invocations are fully traceable.

> **✅ Decision (Grooming #10 — Metrics):** The MCP layer must emit Micrometer metrics for tool invocation counts, tool execution latency, and Bucket4j rate-limit rejections.

- [ ] Implement MCP error translation adapter (Domain Exception → JSON-RPC Error).
- [ ] Ensure Correlation ID generation/propagation in the MCP SSE filter chain.
- [ ] Add Micrometer tracking for MCP tool endpoints.
- [ ] All tasks are gated by `./mvnw clean verify` (must pass checkstyle, tests, and compilation).

## Acceptance Criteria

- [ ] All thirteen MCP tools usable from a real MCP client (e.g., Claude Desktop / MCP Inspector)
- [ ] Authorization verified: MCP users are restricted to their linked tenant's data, and blocked from all tools except `create_tenant` if unlinked.
- [ ] Long ingestion via MCP: `ingest` → `job_status` polling flow works end-to-end
- [ ] Rate limits enforced and observable per tenant/provider
- [ ] No REST behavior regressions (shared service layer, thin MCP adapter)

---
