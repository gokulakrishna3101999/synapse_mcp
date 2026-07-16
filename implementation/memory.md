# Implementation Memory

> Living execution log. See `plan/rag_plan.md` for the roadmap this tracks against, `plan/plan.md` §9 for architectural Decision Log.

---

## Session 1 — 2026-07-16

**Phase:** `rag_plan.md` Stage 0 (Foundations) + Stage 0.5 (Data Model / Schema) — **complete**.

**Completed:**
- `pom.xml`: `groupId` fixed to `com.synapsemcp`; added `spring-boot-starter-data-jpa`, `-validation`, `-actuator`, `postgresql` driver, `org.hibernate.orm:hibernate-vector:7.4.1.Final`; wired `maven-surefire-plugin` (excludes `*IntegrationTest`) / `maven-failsafe-plugin` (includes `*IntegrationTest`, bound to `integration-test`+`verify`).
- `application.yaml` + per-profile `application-{local,dev,prod}.yaml` (main) / `application-test.yaml` (test resources): `spring.datasource.*` bound to `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` env vars (`DB_URL` is host:port only, no db name — each profile appends its own: `/synapsemcp`, `/synapsemcp_test`). `ddl-auto`: `update` (local/dev), `create-drop` (test), `validate` (prod).
- All 8 JPA `@Entity` classes for the Stage 0.5 schema (`Tenant`, `ApiKey`, `ModelConfig`, `KnowledgeBase`, `KnowledgeBaseModelConfig`, `Document`, `Chunk`, `IngestionJob`), one domain package each (`tenant`, `knowledgebase`, `document`, `rag.chunk`, `ingestion`), UUIDs via `@GeneratedValue(strategy = GenerationType.UUID)` (Java-side, no DB default).
- `DatabaseBootstrapRunner` (`com.synapsemcp.config`, `EnvironmentPostProcessor`, `local`/`dev`-only): creates the Postgres database + `vector` extension at startup, before any `DataSource`/`EntityManagerFactory` bean exists. Reuses `spring.datasource.username`/`password` as bootstrap admin credentials (no separate `CREATE ROLE` step).
- `AnnIndexBootstrapRunner` (`com.synapsemcp.config`, `ApplicationRunner`, `local`/`dev`-only): idempotently adds the 6 HNSW indexes on `chunks`' sparse embedding columns + 4 `CHECK` constraints `ddl-auto` can't express.
- `TenantContext` (ThreadLocal), `CorrelationIdFilter` (MDC), `ContextPropagatingTaskDecorator` (propagates both across `@Async` boundaries) — with guardrail tests.
- `ApiKeyAuthenticationFilter` (SHA-256 via `ApiKeyHasher`, `ApiKeyRepository`) — populates `TenantContext` from `Authorization: Bearer <key>`, bypasses `/actuator/*` and `POST /api/v1/tenants`.
- `ApiException` + `ApiExceptionHandler` (`@RestControllerAdvice`) — RFC 7807 `ProblemDetail` responses.
- `AbstractIntegrationTest` (`@SpringBootTest` + `@ActiveProfiles("test")`) + `SynapsemcpApplicationIntegrationTest` (replaces the Initializr placeholder `SynapsemcpApplicationTests`, which did a full context load with no profile — wrong fit for the `*Test` = no-external-dependencies convention).
- `./mvnw clean verify` green: 9 unit tests + 1 integration test, against the real local `synapsemcp`/`synapsemcp_test` databases.

**Decisions/fixes made this session** (see `plan/plan.md` §9 and `plan/rag_plan.md` for the durable record):
- `EnvironmentPostProcessor` registration: Spring Boot 4.1 still uses the classic `META-INF/spring.factories` (key `org.springframework.boot.EnvironmentPostProcessor`), **not** the newer `.imports`-file mechanism (that's a different, structurally-identical-but-distinct interface in the `.env` package not wired to classpath scanning the same way) — first attempt silently no-op'd with zero errors until traced via a temporary debug log line.
- **`embedding_3072` uses `halfvec(3072)`, not `vector(3072)`** — pgvector's HNSW *and* IVFFlat indexes both hard-cap at 2000 dimensions; verified empirically. `halfvec` (half-precision) raises the cap to 4000 and is pgvector's own native mechanism for this, not a workaround. User-confirmed choice over dropping 3072 support or shipping with no ANN index. Uses `SqlTypes.VECTOR_FLOAT16` (confirmed via `hibernate-vector` source) and a `halfvec_cosine_ops` HNSW index.
- Each of the six sparse embedding columns keeps `@Array(length=N)` matching its own name (reversing the pre-implementation plan text, which said no `@Array` at all) — since each column is individually fixed-dimension by design (unlike the single flexible column the original no-`@Array` reasoning was written for), this gives real DB-level rejection of wrong-length vectors as defense-in-depth.
- Bootstrap admin credentials: reuses `spring.datasource.username`/`password` directly (env-var-driven, `DB_USERNAME`/`DB_PASSWORD`) rather than a separate `CREATE ROLE` step — a role that couldn't already authenticate couldn't have made the bootstrap connection in the first place, so the original plan text's separate `CREATE ROLE` step is dead code under this credential strategy and was dropped.
- `DatabaseBootstrapRunner`'s own log lines are invisible at runtime (Spring's logging system isn't initialized yet at `EnvironmentPostProcessor` time) — functionally proven correct via DB inspection instead; not a defect, just a known early-lifecycle logging limitation, left as-is rather than adding `DeferredLogFactory` complexity for a cosmetic-only gap.

**Next task:** `rag_plan.md` Stage 1 — Create Tenant (`POST /api/v1/tenants`, `TenantService`, `TenantController`, `TenantCreationRateLimitFilter`).

**Blockers:** None currently. Noted for later stages: no Ollama and no OpenAI/Anthropic/Google API keys available in this environment — provider-dependent integration tests (Stage 2 `configure_model`, Stage 3's live embedding-dimension probe, Stage 5c embed, Stage 6 ask) will need to mock `EmbeddingModel`/`ChatModel` at the Spring bean level rather than hit real providers (user-confirmed direction).

---

## Session 2 — 2026-07-16

**Phase:** `rag_plan.md` Stage 1 (Create Tenant) — **complete**.

**Completed:**
- `spring-boot-starter-data-redis` added; `spring.data.redis.*` configured per profile (host/port via `REDIS_HOST`/`REDIS_PORT` env vars, default `localhost:6379`; database `0` for local/dev/prod, `1` for test - same isolation pattern as the Postgres split).
- `TenantRepository`, `ApiKeyGenerator` (32 random bytes, base64url), `CreateTenantRequest`/`CreateTenantResponse` (records), `TenantService.createTenant(name)` (creates `Tenant` + `ApiKey` in one `@Transactional` call, returns the raw key once), `TenantController` (`POST /api/v1/tenants`, `201 Created`).
- `TenantCreationRateLimitFilter`: per-IP fixed-window counter on `StringRedisTemplate` (`INCR` + `EXPIRE` on first hit), 5/hour default (`synapsemcp.rate-limit.tenant-creation.limit`/`...window-seconds`), fails open on Redis errors, scoped to `POST /api/v1/tenants` only. Filter order: `CorrelationIdFilter` (0) → `TenantCreationRateLimitFilter` (+1) → `ApiKeyAuthenticationFilter` (+2, bumped from +1).
- Tests: `TenantServiceTest`, `TenantCreationRateLimitFilterTest` (unit, mocked Redis) + `TenantCreationIntegrationTest` (real flow: create → API key resolves to correct tenant via `ApiKeyRepository`, tenant isolation between two created tenants, unauthenticated-vs-open-endpoint check, 6th rapid request from the same IP gets `429`).
- `./mvnw clean verify` green: 15 unit tests + 5 integration tests.

**Decisions/fixes made this session:**
- `TestRestTemplate` moved to a brand-new module in Boot 4.1 (`org.springframework.boot.resttestclient.TestRestTemplate`, artifact `spring-boot-resttestclient`), not `spring-boot-test` where it lived in Boot 3.x - and its auto-configuration is now opt-in via `@AutoConfigureTestRestTemplate` on the test class rather than automatic under `@SpringBootTest`. Also needed `spring-boot-restclient` (provides `RestTemplateBuilder`) as a real, non-test dependency, since `TestRestTemplateTestAutoConfiguration` depends on it and Boot 4 no longer bundles it transitively via the test starter. Both added to `AbstractIntegrationTest`/`pom.xml`.
- `AbstractIntegrationTest` now flushes the isolated test Redis database (index 1 only, via `RedisCallback`/`connection.flushDb()`) before every test method: `TestRestTemplate` calls all originate from the same loopback address, so anything keyed by remote address (the rate limiter) would otherwise leak counts between test methods and across repeated dev-iteration runs (Redis, unlike Postgres's `create-drop`, has no automatic per-context reset).

**Next task:** `rag_plan.md` Stage 2 — Configure Model Config (`PUT`/`GET /api/v1/tenants/{tenantId}/model-config`, Base64-encoded credential storage, `ChatModelFactory`/`EmbeddingModelFactory` groundwork). Needs Spring AI dependencies added for the first time.

**Blockers:** None. Same provider-mocking note as Session 1 applies once Stage 2 needs to exercise real `ChatModel`/`EmbeddingModel` beans in tests.

---

## Session 3 — 2026-07-16

**Phase:** Correction to Stage 1's Redis setup, user-directed. No new `rag_plan.md` stage started.

**Completed:**
- **Reverses Session 2's Redis-index-1 isolation** (`plan.md` §9, 2026-07-16 entry): app and test suite now share one Redis database (index 0); isolation is by key prefix instead - `synapsemcp:*` (app, `local`/`dev`/`prod`) vs `synapsemcp_test:*` (`*IntegrationTest`s), via `synapsemcp.redis.key-prefix` and a new `com.synapsemcp.common.RedisKeyPrefix` component. `TenantCreationRateLimitFilter` now builds its key via `redisKeyPrefix.key("rate_limit:tenant-create:" + remoteAddr)` instead of a raw literal prefix.
- `spring.data.redis.database` removed from every profile YAML (all default to index 0 now); `synapsemcp.redis.key-prefix` added instead (`synapsemcp` in local/dev/prod, `synapsemcp_test` in test).
- **`AbstractIntegrationTest`'s Redis reset changed from `FLUSHDB` to a prefix-scoped delete** (`redisTemplate.keys(redisKeyPrefix.key("*"))` + `delete(...)`) - this is the critical companion fix: since app and test now share index 0, `FLUSHDB` would have wiped real `synapsemcp:*` app data on every test run.
- Verified for real: ran `TenantCreationIntegrationTest` alone, confirmed via `redis-cli -n 0 keys "synapsemcp*"` that exactly `synapsemcp_test:rate_limit:tenant-create:127.0.0.1` landed in index 0, and `redis-cli -n 1 keys "*"` came back empty (index 1 no longer used). `./mvnw clean verify` green afterward: 15 unit + 5 integration tests, unchanged counts from Session 2.

**Next task:** `rag_plan.md` Stage 2 — Configure Model Config (unchanged from Session 2's note).

**Blockers:** None.

---

## Session 4 — 2026-07-16

**Phase:** Completeness audit of Stage 0/0.5/1 against `rag_plan.md`, user-requested. No new stage started.

**Method:** Dropped and rebuilt `synapsemcp` from scratch (`DROP DATABASE` + boot on `local` profile) so the inspected schema was freshly `ddl-auto`-generated, not leftover state; dumped every table with `psql \d` and compared column-by-column, index-by-index, constraint-by-constraint against Stage 0.5's Table Definitions; re-ran `./mvnw clean verify` end to end.

**Found and fixed:**
- **Duplicate `CHECK` constraints on `documents.status` and `ingestion_jobs.status`.** `AnnIndexBootstrapRunner` was adding `chk_documents_status`/`chk_ingestion_jobs_status`, but Hibernate 7.4.1 *also* auto-generates an equivalent constraint (e.g. `documents_status_check`) for any `@Enumerated(EnumType.STRING)` column - confirmed by the fact `model_configs.chat_provider`/`embedding_provider` (plain `String`, not enum-mapped) show only the one manually-added constraint each, no auto-generated duplicate. Functionally harmless (both enforce the identical rule) but genuinely redundant. Fixed: removed the two now-unnecessary `addCheckConstraintIfMissing` calls from `AnnIndexBootstrapRunner`, keeping only the two `model_configs` ones (which are real gaps - plain `String` columns get no automatic constraint). `rag_plan.md` Stage 0.5 corrected in three places to stop claiming `AnnIndexBootstrapRunner` needs to add these two. Verified via fresh rebuild: exactly one `CHECK` constraint per column now. `./mvnw clean verify` re-run clean afterward (20 tests, unchanged).

**Confirmed correct (no other gaps found):** every column, type, nullability, FK (including the intentional missing ones on `documents`/`chunks`/`ingestion_jobs.tenant_id`, Grooming #16), `ON DELETE CASCADE` placement, and index (including all 6 HNSW indexes with the right operator class per column, and every composite `tenant_id`-leading index) on all 8 tables matches Stage 0.5 exactly. `ddl-auto` is `update`/`update`/`create-drop`/`validate` for `local`/`dev`/`test`/`prod` respectively; `synapsemcp.bootstrap.enabled` is `true`/`true`/`false`/`false`. Filter order is `CorrelationIdFilter` → `TenantCreationRateLimitFilter` → `ApiKeyAuthenticationFilter` (`HIGHEST_PRECEDENCE` + 0/1/2). Stage 1's three "Done When" criteria (API key resolves to correct tenant, 5/hour/IP rate limit, tenant isolation) all have a passing integration test.

**Known, deliberate deviations from the literal plan text** (both previously documented, re-confirmed still intentional, not regressions):
1. Package structure uses one package per domain (`tenant`, `knowledgebase`, `document`, `ingestion`, `rag.chunk`, `config`, `common`) rather than Stage 0's literal `core`/`mcp`/`rag`-only structure - reconciled with `plan.md` §4's more specific "each domain package" convention and Decision Log precedent (`com.synapsemcp.tenant`, `com.synapsemcp.embedding`), which post-dates and supersedes the coarser Stage 0 text.
2. `ContextPropagatingTaskDecorator` is built and guardrail-tested, but no concrete `@Async` `ThreadPoolTaskExecutor` bean uses it yet - that's Stage 5's `ingestionExecutor` (core 4/max 8/queue 100/`CallerRunsPolicy`), not needed until real async ingestion work exists.

**Minor cosmetic note, not a gap:** a few `@OneToOne`/`@ManyToOne` single-column unique constraints (`model_configs.tenant_id`, `knowledge_base_model_configs.knowledge_base_id`, `ingestion_jobs.document_id`) have Hibernate-auto-generated names (e.g. `ukruxxcndi3dvx9u3kdte4d8yu7`) instead of readable ones, since `@JoinColumn(unique = true)` doesn't take a name attribute the way `@Table(indexes = ...)` does. The plan's Table Definitions never assign these specific names (unlike the composite indexes, which are named and match exactly), so this isn't a spec violation - flagged only in case a future session wants them prettier.

**Next task:** `rag_plan.md` Stage 2 — Configure Model Config (unchanged).

**Blockers:** None.

---

## Session 5 — 2026-07-16

**Phase:** Second, deeper completeness pass on Stage 0/0.5/1, user-requested ("check thoroughly again"). No new stage started.

**Method:** Session 4's audit only inspected the `local`-profile (`synapsemcp`) database. This session specifically inspected the **`test`-profile database** (`synapsemcp_test`) too, since that's what every future `*IntegrationTest` actually runs against and Session 4 hadn't verified it directly. Used a temporary `TempSchemaInspectionIntegrationTest` (extends `AbstractIntegrationTest`, sleeps 25s) to hold the context open long enough to inspect the live schema via `psql \d` mid-run, then deleted it.

**Found and fixed a real gap:** `synapsemcp_test` had **zero HNSW indexes on `chunks` and no `model_configs` `CHECK` constraints at all**. Root cause: `AnnIndexBootstrapRunner` was gated on the same `synapsemcp.bootstrap.enabled` flag as `DatabaseBootstrapRunner`, which is `false` in the `test` profile - correctly, for `DatabaseBootstrapRunner` (needs elevated maintenance-database credentials test doesn't have), but `AnnIndexBootstrapRunner` has no such requirement (only touches an already-existing DB via the normal app datasource) and got swept into the same restriction by accident. This meant any future test exercising ANN vector search or DB-level provider-value rejection would have silently passed against a schema that didn't match `local`/`dev`/`prod` - the kind of gap that's invisible until someone writes that test and it inexplicably passes when it shouldn't, or inexplicably fails only in prod.

**Fix:** Split into two independent flags - `synapsemcp.bootstrap.enabled` (`DatabaseBootstrapRunner`, unchanged: `true` local/dev, `false` test/prod) and `synapsemcp.ann-bootstrap.enabled` (`AnnIndexBootstrapRunner`, new: `true` local/dev/**test**, `false` only prod). Verified via the same temp-test technique: `synapsemcp_test` now has both `chk_model_configs_chat_provider`/`chk_model_configs_embedding_provider` and all six HNSW indexes (`vector_cosine_ops` ×5, `halfvec_cosine_ops` for `embedding_3072`). `rag_plan.md` Grooming #18 and `plan.md` §9 updated with a new dated entry. `./mvnw clean verify` re-run clean afterward: still 20 tests (15 unit + 5 integration), unchanged from Session 4.

**Confirmed no further gaps** on a second, more adversarial pass covering: RFC 7807 response shapes from both the exception handler and the two hand-written filter-level error bodies; filter bypass logic for `/actuator/*` and open endpoints; `TenantContext` clearing on every code path (including exceptions, via `finally`); Redis fail-open behavior (unit-tested with a thrown `RedisConnectionFailureException`); rate-limit fixed-window semantics (`INCR` + `EXPIRE`-only-on-first-hit); API key generation entropy (32 `SecureRandom` bytes) and one-way hashing (raw key never persisted); `pom.xml` dependency list for anything unused or missing; every profile YAML's `ddl-auto`/bootstrap-flag/Redis config. Nothing further found.

**Next task:** `rag_plan.md` Stage 2 — Configure Model Config (unchanged).

**Blockers:** None.

---

## Session 6 — 2026-07-16

**Phase:** Third completeness pass on Stage 0/0.5/1, user-requested ("check thoroughly again, be honest"). No new stage started.

**Honesty note on Session 5:** Session 5's "Confirmed no further gaps" list included "Redis fail-open behavior (unit-tested with a thrown `RedisConnectionFailureException`)" as evidence of correctness. That confidence was misplaced - the mocked unit test proved the *code path* was reachable, but never proved it was reachable *quickly*. This session found it wasn't.

**Method:** Actually stopped the real local Redis and Postgres services (`brew services stop`) and hit the running app directly with `curl` and `time`, rather than re-checking mocked exception paths or schema state. This is qualitatively different from Sessions 4/5's methods (schema diffing, unit tests) and is what surfaced all three findings below - none of which any existing automated test could have caught.

**Found and fixed three real gaps:**

1. **Redis fail-open took 60 seconds, not milliseconds.** Stopped Redis, `POST /api/v1/tenants` via curl with `--max-time 90`: request hung, then eventually returned `201 Created` at **exactly 60.04s**. Spring Data Redis's default command timeout (Lettuce's own default) is 60s when `spring.data.redis.timeout` is unset - a detail neither `rag_plan.md` nor Sessions 1-5 had ever considered. A real Redis outage was making every tenant-creation request hang for a full minute before eventually succeeding, which functionally *is* blocking the endpoint - the exact failure mode "fail open" was supposed to prevent. Fix: `spring.data.redis.connect-timeout`/`timeout` set to `1s` (env-var-overridable via `REDIS_CONNECT_TIMEOUT`/`REDIS_TIMEOUT`) in all four profiles. Re-verified with Redis still stopped: same request now returns `201` in **0.125s**.

2. **`/actuator/health` went `DOWN` when Redis stopped.** Spring Boot auto-includes any detected health indicator into the aggregate status once its starter is on the classpath - found by checking `/actuator/health` immediately after stopping Redis for gap #1's test, before applying any fix. This directly contradicts the app's own "Redis is fail-open, never critical" design: a real orchestrator's liveness/readiness probe would kill or de-register an otherwise-perfectly-healthy pod over a non-critical dependency being briefly down. Fix: `management.health.redis.enabled: false` added to the base `application.yaml` (applies to every profile). Re-verified: health stays `UP` with Redis stopped.

3. **Malformed request JSON returned `500`, not `400`.** `curl -d 'not json'` against the real running `POST /api/v1/tenants` returned `{"title":"Internal Server Error","status":500,...}` - `HttpMessageNotReadableException` was falling through to the generic `@ExceptionHandler(Exception.class)` catch-all, misclassifying a client mistake as a server bug and triggering its `log.error(...)` call (which would pollute error-level logs with client-caused noise in production, potentially masking a real server error arriving in the same window). Fix: added a dedicated `@ExceptionHandler(HttpMessageNotReadableException.class)` returning `400 Bad Request` / `"Malformed request body"`, plus a new `ApiExceptionHandlerTest` case. Re-verified live: now returns `400` correctly.

**Also verified for real (previously only inferred from documentation, not tested):** Postgres fail-fast startup - stopped Postgres, booted the app, failed in **3.7 seconds** with a clean `Connection refused` root cause (no hang, no confusing stack trace) - this one was already correct as designed, no fix needed.

**Confirmed still correct on this pass:** real end-to-end validation (`{"name":""}` and missing-field both correctly return `400` with `"must not be blank"`), correlation ID header present on real HTTP responses (`X-Correlation-Id`), tenant creation and rate-limit-trip both still work correctly with real (not mocked) Redis up.

`plan.md` §9 and `rag_plan.md` Stage 1 updated. Cleaned up test-tenant rows created in the local `synapsemcp` database and stray Redis keys accumulated during manual `curl` verification (dropped/recreated `synapsemcp` via the normal bootstrap flow, `redis-cli flushdb` on index 0). `./mvnw clean verify` green: 21 tests (16 unit + 5 integration).

**Next task:** `rag_plan.md` Stage 2 — Configure Model Config (unchanged).

**Blockers:** None. **Process note for future sessions:** when a "Done When" criterion describes behavior under a failure condition (fails open, fails fast, degrades gracefully), a mocked unit test proving the code path exists is necessary but not sufficient - verify against the real dependency actually being down before calling it done.

---

## Session 7 — 2026-07-16

**Phase:** Fourth completeness pass on Stage 0/0.5/1, user-requested ("check thoroughly again, be honest") - same request as Session 6. No new stage started.

**Method:** Rather than re-running Session 6's checks, extended its core insight ("dependency down while the app is already live, not just at startup" is where the real bugs were hiding) to the one other component with the same shape of risk that hadn't been tested that way yet: **Postgres going down mid-flight**, as opposed to Postgres being down before the app even starts (which Session 6 tested and found correct).

**Found and fixed a fourth real gap:** Booted the app with everything up, confirmed a baseline tenant creation succeeded, then stopped Postgres and hit `POST /api/v1/tenants` again. Request hung **30 seconds** (HikariCP's own default `connection-timeout`), then returned a generic `{"title":"Internal Server Error","status":500}`. Same shape of bug as Session 6's Redis finding: a real outage was making requests hang far longer than reasonable, and the resulting error was misclassified (a transient infrastructure problem reported as if it were a server bug).

**Root cause identified precisely before fixing:** checked the app log for the actual exception chain rather than guessing - `org.springframework.transaction.CannotCreateTransactionException` ← `org.hibernate.exception.JDBCConnectionException` ← `java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 30003ms`. `CannotCreateTransactionException` is precise and safe to catch specifically: it only fires when a transaction genuinely can't be *opened* (pool exhausted / DB unreachable), never for a real data-level error like a constraint violation, which can only happen *after* a connection is already successfully obtained - so mapping it to `503` carries no risk of misclassifying a real bug as a transient outage.

**Fix:** `spring.datasource.hikari.connection-timeout: 3000` (ms, overridable via `DB_CONNECTION_TIMEOUT`) added to all four profiles. `ApiExceptionHandler` gained `@ExceptionHandler(CannotCreateTransactionException.class)` → `503 Service Unavailable` / `"Database temporarily unavailable"`, plus a new `ApiExceptionHandlerTest` case. Re-verified live with Postgres still stopped: same request now returns `503` in **3.07s** instead of hanging 30s and returning `500`.

**This also corrected an earlier planning mistake, found only because of this test:** back when the three plan docs were originally revised for the Flyway→`ddl-auto` migration (before any code existed), `rag_plan.md`'s Cross-Cutting Concerns table had its "DB not ready → `503`" contract removed, reasoning that Stage 0's fail-fast startup meant the app would never accept traffic before the DB was ready. That reasoning was correct as far as it went, but only covered *startup-time* unavailability - it silently assumed the DB could never become unavailable *after* a successful startup, which is a distinct, equally real scenario this session proved actually happens and was previously completely unhandled. `rag_plan.md` corrected to restore the `503` contract, scoped correctly to "mid-request," not "startup."

**Confirmed still correct, not broken by any of these changes:** `/actuator/health` correctly still reports `DOWN` when Postgres is unreachable (as it should - Postgres is genuinely critical, unlike Redis) - the earlier Session 6 fix (`management.health.redis.enabled: false`) was Redis-specific and didn't touch the DB health indicator. Full `./mvnw clean verify` green throughout: 22 tests (17 unit + 5 integration).

**Housekeeping:** cleaned up test-tenant rows accumulated in the local `synapsemcp` database and flushed Redis index 0 from manual `curl`/`brew services stop` verification; left the local dev environment booted once more at the end to confirm a clean final state (`/actuator/health` → `UP`), then stopped it.

**Next task:** `rag_plan.md` Stage 2 — Configure Model Config (unchanged).

**Blockers:** None. Same process note as Session 6 applies, now demonstrated twice in a row (Redis, then Postgres) - the fix pattern for "component X is fail-open/should-degrade-gracefully" bugs is consistently: (1) find the real exception type from an actual log, not a guess, (2) set an explicit short timeout instead of trusting the client library's default, (3) map the specific, safe exception type to the right status code rather than letting it fall through to a generic handler. Worth checking whether any *other* infra client this project will add later (a future embedding/chat provider HTTP client, Stage 2 onward) has the same "safe-looking default timeout that's actually 30-60s" trap before it ships.

---

## Session 8 — 2026-07-16

**Phase:** Fifth completeness pass on Stage 0/0.5/1, user-requested ("check thoroughly again... start the services and check them"). No new stage started.

**Method:** Sessions 6/7 only ever tested the *failure* half of each fail-open/fail-fast behavior. This session tested the other half neither had covered: **recovery** - stop a service, confirm the already-proven failure behavior, then restart the service *while the app keeps running* and confirm it self-heals without an app restart. Also tested a combination neither prior session tried: both Postgres and Redis down at the same time.

**All three recovery scenarios confirmed correct, no new bugs found:**

1. **Redis: stop → fail open (already known) → restart → self-heals.** After restarting Redis, the very next request completed in 25ms (fully back to normal, no lingering reconnect penalty) and the rate limiter correctly resumed enforcing the 5/hour limit - traced the count carefully across the outage (Redis retains its own state across a restart; only the *app's* ability to reach it was interrupted) and confirmed the 6th real request was correctly rejected, not the 5th or 7th.
2. **Postgres: stop → `503` (already known) → restart → self-heals.** After restarting Postgres, the next request completed in 25ms - HikariCP re-established a working connection with no app restart needed.
3. **Both down simultaneously (new combination, not tested before):** request took ~4.0s total and correctly returned `503` - the Redis check fails open in ~1s, then the request reaches the DB layer and fails there too within its own ~3s budget. Additive, not multiplicative or hung; correctly surfaces `503` (the more severe of the two problems) as the final answer. After restarting both together, full recovery in ~1.0s (both pools re-establishing at once, still well within reason).

**No code changes this session** - this is the first of five audit passes that found nothing wrong. `./mvnw clean verify` unchanged at 22 tests. Cleaned up test-tenant rows and Redis keys from manual verification afterward; left the local environment booted once more to confirm a clean final state, then stopped it.

**Next task:** `rag_plan.md` Stage 2 — Configure Model Config (unchanged).

**Blockers:** None.

---

## Session 9 — 2026-07-16

**Phase:** Sixth completeness pass, user-requested ("check thoroughly again... confirm accordingly" against `plan.md`/`rag_plan.md`/`memory.md` specifically). No new stage started.

**Method:** A genuinely different angle from Sessions 6-8 (which all tested infrastructure behavior): this time audited **the documentation itself** for accuracy - does `rag_plan.md` actually describe what's built, and do `plan.md`'s own documented developer commands actually work. Re-read Stage 0/0.5/1 of `rag_plan.md` fresh end-to-end, re-read all of `memory.md` end-to-end for internal consistency, and literally ran every command `plan.md` §3 claims is canonical.

**Found and fixed three real gaps, none of them runtime bugs this time - all documentation/tooling gaps:**

1. **Dangling reference in `rag_plan.md`.** Stage 1's Redis fail-open row said "see `spring.data.redis.timeout`/`connect-timeout` below" - no such section existed anywhere in the file. Worse, tracing it further: **the entire Postgres/HikariCP `503` fix from Session 7 had never been added to `rag_plan.md` at all** - only to this Decision Log (`plan.md` §9). The authoritative spec document was missing a real, already-shipped behavior. Fixed: added a proper "Runtime Timeouts — Redis & Postgres" subsection to Stage 0 documenting both fixes together (why each default was too long, what they're set to now, how they were verified), and repointed both Stage 1's Redis row and a new "DB unavailable mid-request" row at it.

2. **`./mvnw spotless:apply` (`plan.md` §3's own "Format code" canonical command) genuinely failed.** Ran it for real rather than assuming a documented command works: `[ERROR] No plugin found for prefix 'spotless'` - the plugin was never added to `pom.xml` in the first place, presumably a carry-over from the original plan draft that pre-dated any real `pom.xml` work. Fixed properly, not just documented as broken: added `spotless-maven-plugin` to `pom.xml`. First attempt used plain Google Java Format (2-space indent) and would have reformatted all 36 files' indentation, wholesale, away from the 4-space style already used throughout - checked this with `spotless:check` (dry-run) before applying anything, saw the scale of the diff, and switched to Google Java Format's **AOSP style** (4-space, matches what's already there) instead. Re-checked: AOSP limited the real diff to line-wrapping only. Applied, then ran the full test suite to confirm the reformat didn't break anything (didn't - formatting-only). `spotless:check` now passes cleanly (all 36 files compliant).

3. **Confirmed `org.owasp:dependency-check-maven:check` (the other canonical command) genuinely works** - ran it for real, watched it resolve the plugin and start downloading the NVD vulnerability database (366,616 records), stopped it before completion since a full first-run download takes a very long time without an API key (as the tool's own output warns) - no fix needed, this one was already accurate as documented.

**Also confirmed:** full `./mvnw clean verify` still green (22 tests) both before and after the Spotless fix, `memory.md` (this file) re-read fully end-to-end and found internally consistent across all 8 prior sessions - no contradictions, no stale claims.

**Housekeeping:** cleaned up test data from Spotless/OWASP testing, left the local `synapsemcp` database rebuilt fresh and the app confirmed booting cleanly (`/actuator/health` → `UP`) before stopping it. Killed only the specific Maven processes this session started - correctly left an unrelated IntelliJ IDEA background Maven server process alone rather than indiscriminately killing everything matching "maven".

**Next task:** `rag_plan.md` Stage 2 — Configure Model Config (unchanged).

**Blockers:** None. **Process note:** this is the first pass in the whole sequence to look at the *documents* as an artifact worth verifying in their own right, rather than treating them as a fixed reference to check code against. Worth remembering going forward: every time a fix lands in code, it needs to land in `rag_plan.md` itself (the authoritative spec), not just in this log or the Decision Log - Session 7's `503` fix shipped correctly in code but silently didn't make it into `rag_plan.md`, and that gap sat undetected for two more sessions until this one went looking for it specifically.

---

## Session 10 — 2026-07-16

**Phase:** `rag_plan.md` Stage 2 (Configure Model Config) — **complete**.

**Completed:**
- `PUT`/`GET /api/v1/tenants/{tenantId}/model-config`: `ProviderCredentials`/`ProviderCredentialsCodec` (Base64 obfuscation), `ModelConfigRepository`, `KnowledgeBaseModelConfigRepository`, `ConfigureModelRequest`/`ModelConfigResponse` (records), `ModelConfigUpdatedEvent`, `ModelConfigService` (upsert, provider allow-list validation → `422`, Grooming #22 credential auto-sync to existing `knowledge_base_model_configs` rows, event publish), `ModelConfigController` (own-tenant-only `403`).
- `ChatModelFactory` (`com.synapsemcp.chat`) / `EmbeddingModelFactory` (`com.synapsemcp.embedding`) — new packages, per `plan.md` §9's 2026-07-13 decision-log entries written ahead of this session. Both build per-tenant provider clients directly from `model_configs` via each provider's real Spring AI builder (`OpenAiChatModel`/`AnthropicChatModel`/`OllamaChatModel`/`GoogleGenAiChatModel`, `OpenAiEmbeddingModel`/`OllamaEmbeddingModel`/`GoogleGenAiTextEmbeddingModel`), cached per tenant in a `ConcurrentHashMap`, evicted via `@EventListener` on `ModelConfigUpdatedEvent`, `422` if no `model_configs` row exists.
- `com.google.genai:google-genai` added as an explicit `pom.xml` dependency (pinned to `1.58.0`, the version already resolved transitively via `spring-ai-google-genai-embedding`) since `ChatModelFactory` imports `com.google.genai.Client` directly.
- Tests: `ProviderCredentialsCodecTest`, `ModelConfigServiceTest` (incl. a dedicated Grooming #22 partial-sync test), `ChatModelFactoryTest`/`EmbeddingModelFactoryTest` (parameterized over every supported provider, cache-hit/eviction/cross-tenant-isolation, `422` on missing config) + `ModelConfigIntegrationTest` (real DB: PUT/GET round-trip, credentials never echoed, cross-tenant `403`, invalid-provider `422`, missing-config `404`, and an end-to-end Grooming #22 verification against a directly-`persist()`-ed `KnowledgeBaseModelConfig` row).
- `./mvnw clean verify` green: 46 unit tests + 11 integration tests.

**Method (per standing instruction to never guess):** every Spring AI builder signature used in the two factories was verified via `javap -c` against the real `spring-ai-{openai,anthropic,ollama,google-genai,google-genai-embedding}` 2.0.0 jars on the classpath before being called - including decompiling the private lazy-client-construction lambdas (`OpenAiSetup.setupSyncClient`, `AnthropicSetup.setupSyncClient`) to confirm each provider's `*ChatOptions`/`*EmbeddingOptions` builder's `apiKey(String)`/`baseUrl(String)` setters are sufficient at construction time with no manual `OkHttpClient`/`OpenAIClient` wiring required. This is also what confirmed Grooming #15's design is correct: every provider's *own* `model()` builder setter is typed to an SDK-specific enum (Anthropic's `com.anthropic.models.messages.Model`, Ollama's `OllamaModel`, Google's `GoogleGenAiChatModel$ChatModel`) - only the *generic* `ChatOptions.builder().model(String)` accepts a plain string, which is why `ChatModelFactory` never bakes a model name into provider-specific options at construction and instead exposes `optionsFor(tenantId)` for per-call attachment. Confirmed via bytecode inspection that `com.google.genai.Client` **must** be explicitly supplied to `GoogleGenAiChatModel.builder()` (`Assert.notNull`, no lazy fallback) - this is the exact gap the earlier rejected `spring.autoconfigure.exclude`/`spring.ai.model.*` fix attempts were trying to paper over; building it directly per-tenant from the `model_configs` row is what actually fixes it.

**Found and fixed one real bug via live testing against the real app** (not assumed from reading the code): `ModelConfigService.configureModel()`'s `PUT` response showed `createdAt: null` for a brand-new row, while an immediately-following `GET` showed the real timestamp. Root cause: `@CreationTimestamp` is only populated by Hibernate at flush time, and `modelConfigRepository.save(modelConfig)` doesn't flush before the method returns (client-generated UUID means Hibernate has no reason to flush early). Fixed by switching to `saveAndFlush`. Caught by booting the real app against real Postgres/Redis and exercising the endpoint with `curl` before writing the automated test suite - the same "verify against real infrastructure, don't assume" pattern as every prior session's fixes.

**Also found:** `HttpStatus.UNPROCESSABLE_ENTITY` (422) deserializes back from a real HTTP response as the enum constant `UNPROCESSABLE_CONTENT` in this Spring Framework version (`TestRestTemplate`'s `HttpStatus.valueOf(422)` resolves to the newer canonical name) - not a bug, just an enum-identity trap. Fixed the one integration-test assertion that compared the exact enum constant to compare `.getStatusCode().value() == 422` instead, matching the `.value()`-based pattern `TenantCreationIntegrationTest` already used for its `429` assertion.

**Housekeeping:** cleaned up manually-`curl`-created test tenants from the local `synapsemcp` database; `synapsemcp_test` self-resets every run (`create-drop`). App confirmed booting cleanly with zero `spring.ai.*` YAML configuration before being stopped.

**Next task:** `rag_plan.md` Stage 3 — Create Knowledge Base (`embedding_dim` live-probe derivation via `EmbeddingModelFactory`, `knowledge_base_model_configs` snapshot creation, locked-dimension rule).

**Blockers:** Same as every prior session - no live Ollama server and no real OpenAI/Anthropic/Google API keys in this environment, so `ChatModelFactory`/`EmbeddingModelFactory`'s tests can only verify construction-time wiring (no exception building the provider client), never an actual `call()`/`embed()` round-trip. Stage 3's live dimension-probe (`plan.md` §9, 2026-07-16) will hit this same wall and will need to be tested with a mocked `EmbeddingModel` bean rather than a real provider call.

---

## Session 11 — 2026-07-16

**Phase:** Seventh completeness pass, user-requested ("check thoroughly again... confirm me if we are good to go to next stage from `rag_plan.md`/`plan.md`... be honest, if you have any doubts ask me do not guess"). No new stage started - this audit is what unblocks Stage 3.

**Method:** Re-read `rag_plan.md` Stage 0 through Stage 2 and `plan.md` fully end-to-end fresh, cross-checked every documented claim against the live schema/app/tests, then extended Session 7/8's "test real outages, don't trust mocks" method specifically to the parts of the request path Stage 2 newly added (an authenticated endpoint's filter-level DB dependency, not just an unauthenticated controller's).

**Found and fixed one genuine regression - a structural gap affecting every authenticated endpoint, not just Stage 2:**

Re-ran Session 8's exact "stop Postgres mid-flight" test, but against `GET /api/v1/tenants/{tenantId}/model-config` (an *authenticated* endpoint) instead of re-testing only `POST /api/v1/tenants` (the one endpoint Session 8 actually verified, which is unauthenticated and bypasses `ApiKeyAuthenticationFilter` entirely). Result: a raw, non-RFC-7807 `500` from Tomcat's default error page, not the intended `503`. Root cause, confirmed via the actual stack trace rather than assumed: `ApiKeyAuthenticationFilter` is a raw Servlet `Filter` running *before* Spring MVC's dispatcher - `@RestControllerAdvice`/`ApiExceptionHandler` structurally cannot see anything it throws, and its own `ApiKeyRepository.findTenantIdByKeyHash(...)` call had no try/catch of its own for a DB-connectivity failure (only for the "key not found" 401 case). Made worse by a second finding: the exception thrown from this bare repository call was `org.springframework.dao.DataAccessResourceFailureException` (Hibernate's `JDBCConnectionException`, translated), not `org.springframework.transaction.CannotCreateTransactionException` (the type Session 8's fix actually catches) - confirmed via `javap` that these are two genuinely non-overlapping exception hierarchies (`DataAccessException` vs. `TransactionException`, sibling branches under `NestedRuntimeException`), both meaning "Postgres unreachable" depending on exactly where in the connection-acquisition lifecycle the failure hits.

Fixed with two changes: (1) `ApiKeyAuthenticationFilter` now catches `DataAccessResourceFailureException`/`CannotCreateTransactionException` around its repository call and writes a `503` RFC 7807 body directly - the same pattern it already used for `401`s; (2) `ApiExceptionHandler` additionally now maps `DataAccessResourceFailureException` to the same `503` response (alongside the existing `CannotCreateTransactionException` mapping), for controller-level call sites that might hit this exception shape instead. Added regression tests at both layers (`ApiKeyAuthenticationFilterTest`, `ApiExceptionHandlerTest`) using a mocked repository/thrown exception, since reproducing a real Postgres outage isn't something an automated `*Test`/`*IntegrationTest` can do. Re-verified live: `GET /api/v1/tenants/{tenantId}/model-config` now correctly returns `503`/`"Database temporarily unavailable"` with Postgres stopped mid-flight, and recovers correctly once Postgres is back. This was a genuine blocker for Stage 3+: every future authenticated endpoint shares this exact filter.

**Also verified, no fix needed:**
- Redis-down fail-open (Stage 1) still works correctly post-Stage-2 (~1s, not 60s; `/actuator/health` stays `UP`) - a regression check, not a new finding.
- Live schema inspection (`\d` on all 8 tables) matches `rag_plan.md`'s Table Definitions exactly on structure/constraints/indexes - `model_configs`' two `CHECK` constraints, all 6 HNSW indexes with correct operator classes (`vector_cosine_ops` for 384-1536, `halfvec_cosine_ops` for 3072), all FKs, all composite indexes.
- `./mvnw clean verify` green throughout: 48 unit + 11 integration tests (net +2 from this session's regression tests) after the fix.

**Found and fixed two documentation-accuracy gaps (`rag_plan.md`/`plan.md`, not code):**
1. `plan.md`'s own "Testing setup" section still said integration tests use the `*IT` suffix - stale since the project-wide rename to `*IntegrationTest` (already correctly reflected everywhere else, including the Decision Log entry documenting that exact rename). Fixed.
2. `rag_plan.md`'s Table Definitions section labels several columns "TEXT" (`tenants.name`, `model_configs.chat_provider`/`chat_model`/etc.) that are actually Hibernate's implicit `varchar(255)` default in the live schema - only the fields explicitly given `@Column(columnDefinition = "TEXT")` (`chunks.content`, `ingestion_jobs.error_detail`, both tables' `provider_credentials`) are genuinely unbounded. Not a functional bug (none of these fields ever hold long values), but imprecise. Added a clarifying note rather than rewriting the whole reference table's "Type" column, since the divergence is systemic/pre-existing across the entire schema, not a Stage 2 regression.

**Deliberately not touched:** `plan.md`'s Decision Log contains many entries (dated 2026-07-12 through 2026-07-14, before this project's actual from-scratch implementation began on 2026-07-16 per Session 1) describing a since-scrapped prior design - Flyway migrations, Testcontainers, AES-GCM credential encryption, an `ADMIN_BOOTSTRAP_KEY` admin tier, `*IT` test suffix, internal `§1.1`-`§1.9` cross-references to sections that no longer exist now that Phase 1 detail lives in `rag_plan.md` instead. These are already correctly superseded by later entries where it matters functionally (e.g. the plaintext-then-Base64 credential storage reversals), and the log is explicitly append-only ("future sessions inherit the reasoning instead of re-litigating it") - rewriting decades of historical entries to fix dangling internal section references wasn't in scope for this pass and weighed against being asked, since it's a large-scale editorial question (rewrite each one vs. one disclaimer) rather than a functional gap blocking Stage 3.

**Housekeeping:** cleaned up regression-test tenants from the local `synapsemcp` database (back to 0 rows); killed the manually-started `spring-boot:run` process; Postgres/Redis left running (found already running, left as found). `./mvnw clean verify` confirmed green one final time after cleanup.

**Verdict: good to go to Stage 3.** Everything in Stages 0-2 checked against real infrastructure and found correct except the one filter-level gap above, now fixed and regression-tested. No open questions blocking Stage 3 start.

**Next task:** `rag_plan.md` Stage 3 — Create Knowledge Base (`POST`/`GET`/`PUT`/`DELETE /api/v1/knowledgebase`, live-probe `embedding_dim` derivation via `EmbeddingModelFactory`, `knowledge_base_model_configs` snapshot creation at creation time, 10-KB-per-tenant limit, locked-dimension rule).

**Blockers:** Unchanged - no live Ollama server, no real provider API keys in this environment.

---

## Session 12 — 2026-07-17

**Phase:** Pickup/orientation session, user-requested ("check the current code and configuration files, plan.md/rag_plan.md/mcp_plan.md, and update memory.md so I can pick up where we left off"). No new stage started - this was purely a state-verification pass to confirm Session 11's stopping point still holds before starting Stage 3.

**Method:** Fresh session, no prior context carried over automatically - re-read all three plan docs end-to-end, inventoried every `.java` file under `src/main/java`/`src/test/java` against what Session 11 claimed was built, diffed the four tracked-but-modified files (`pom.xml`, `application.yaml`, `SynapsemcpApplication.java`, plus both plan docs) against HEAD, and ran a real `./mvnw clean verify` against live Postgres/Redis rather than trusting the log.

**Confirmed: code state matches Session 11's stopping point exactly, no drift:**
- All 8 Stage 0.5 entities present (`Tenant`, `ApiKey`, `ModelConfig`, `KnowledgeBase`, `KnowledgeBaseModelConfig`, `Document`, `Chunk`, `IngestionJob`) - these are Stage 0.5 scaffolding, not new Stage 3 work; confirmed no `KnowledgeBaseRepository`/`KnowledgeBaseService`/`KnowledgeBaseController`, no `DocumentRepository`/`ChunkRepository`/`IngestionJobRepository` exist anywhere - i.e. Stage 3 (Create Knowledge Base) genuinely has not been started yet, only its prerequisite entities exist.
- No `mcp` package or any MCP SDK dependency anywhere in `pom.xml` - `mcp_plan.md` (Phase 2) is entirely unstarted, consistent with this project still being in `rag_plan.md` (Phase 1) work.
- `pom.xml`/`application.yaml`/`SynapsemcpApplication.java` diffs against HEAD contain nothing not already accounted for in Sessions 1-11 (Spotless plugin, spring-ai BOM/artifacts, surefire/failsafe config, `management.health.redis.enabled: false`, 4-space reformat).
- `plan/rag_plan.md` and `plan/plan.md` diffs against HEAD match Session 11's (and earlier sessions') documented changes line-for-line - no undocumented plan edits, no plan claims outrunning the code.

**Found one real environment gap, not a code bug:** a brand-new shell has no `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` set - `./mvnw clean verify` fails fast with `'url' must start with "jdbc"` before even reaching the DB connection stage. Checked `~/.zshrc`/`~/.zprofile`/`~/.bashrc` - this project's DB env vars are not exported anywhere persistent (only unrelated projects' DB vars live there). There is no `.env` file and no `scripts/setup-environment.sh` in the repo despite `plan.md` referencing one by name - it was never actually created. Every session so far has presumably exported these by hand in a long-lived shell and never hit this cold-start case. **For next session:** local Postgres (`gokulakrishna` superuser role, no password, trust auth) needs `export DB_URL="jdbc:postgresql://localhost:5432" DB_USERNAME="gokulakrishna" DB_PASSWORD=""` before any `./mvnw` command in a fresh shell - Redis needs no equivalent export (defaults to `localhost:6379`). Worth creating the referenced `scripts/setup-environment.sh` in a future session so this stops being a manual step, though that wasn't done here since it's tooling, not plan-mandated code.

**Verified real, not just re-read:** with both env vars exported and Postgres/Redis confirmed running (`brew services list`), `./mvnw clean verify` is **green**: 48 unit tests + 13 integration tests (61 total), `BUILD SUCCESS`. Note: this integration-test count (13) differs from Session 11's stated "48 unit + 11 integration" - recounted directly from `[INFO] Tests run: N -- in <class>` lines (`ModelConfigIntegrationTest`=8, `TenantCreationIntegrationTest`=4, `SynapsemcpApplicationIntegrationTest`=1 = 13) and cross-checked against each test class's actual method list, both matching 13. No test files have changed since Session 11 (git status shows no diff in any `*IntegrationTest.java`), so this is most likely a stale/undercounted number in a prior session's log entry, not a regression introduced since - not chased further since it doesn't block Stage 3 and no code changed.

**No code or plan changes this session** - purely a verification/orientation pass to safely resume work.

**Next task:** `rag_plan.md` Stage 3 — Create Knowledge Base (`POST`/`GET`/`PUT`/`DELETE /api/v1/knowledgebase`, live-probe `embedding_dim` derivation via `EmbeddingModelFactory`, `knowledge_base_model_configs` snapshot creation at creation time, 10-KB-per-tenant limit, locked-dimension rule) - confirmed genuinely ready to start, unchanged from Session 11's assessment.

**Blockers:** Unchanged - no live Ollama server, no real provider API keys in this environment. New process note: remember to export `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` at the start of any fresh shell in future sessions before running Maven - see the environment gap above.

---

## Session 13 — 2026-07-17

**Phase:** Final thorough validation of Stages 0-2 before Stage 3, user-requested ("final thorough validation and analysis... works end to end and also works individually properly in all scenario without missing edge case and make it architecturally strong. be honest, if you need any confirmation ask me instead of guessing"). Sessions 4-11 already covered schema correctness, fail-open/fail-fast timing, recovery, and doc accuracy exhaustively - this session deliberately targeted angles none of those had touched: concurrency/races, resource lifecycle, transaction-boundary timing, and validation completeness. **Found and fixed four real bugs.**

**Method:** Read every service/controller/filter/factory/entity in `tenant`, `common`, `config`, `chat`, `embedding` end-to-end (not just the ones touched by the immediately-preceding session). For each suspected gap, reproduced it live against a running app with real Postgres/Redis (`curl`, concurrent background requests) before trusting the analysis - consistent with this project's established "verify against real infrastructure, don't guess" pattern (Sessions 6-8). Presented all findings honestly to the user via `AskUserQuestion` before fixing anything, including one (the cache-eviction race) that could only be confirmed by code-reading + a deterministic test, not forced via a live timing race, and one (the rate-limiter atomicity gap) that was plausible but not independently forced live either - flagged the confidence difference explicitly rather than presenting all findings as equally certain.

**Found and fixed, all four confirmed by the user before fixing:**

1. **`DataIntegrityViolationException` had no handler at all → `409`/`400`, not `500`.** Fired 10 concurrent first-time `PUT .../model-config` requests for one brand-new tenant against a live app: **9 of 10 crashed with a raw `500`** (`model_configs.tenant_id` unique-constraint violation on the losing inserts - `ModelConfigService.configureModel()`'s find-or-create has no locking). A 500-character `CreateTenantRequest.name` (no `@Size` bound; Hibernate's implicit `varchar(255)`) hit the same unhandled exception type via a different constraint. Fix: new `ApiExceptionHandler` handler inspects the underlying `SQLException`'s SQLSTATE - `23505` (unique violation) → `409 Conflict`; anything else → `400 Bad Request`. Test assumption caught and corrected mid-session: my first regression test assumed exactly one `200` winner among 10 concurrent requests - wrong, since `configureModel()` is find-or-create, a request whose SELECT runs after an earlier request's already-committed INSERT legitimately sees the row and UPDATEs it (also a `200`); only requests that overlap in the no-row-yet window collide. Corrected the test to assert "never `500`, at least one `200`" instead of "exactly one `200`".
2. **`MethodArgumentTypeMismatchException` had no handler → `400`, not `500`.** `GET /api/v1/tenants/not-a-uuid/model-config` with a valid API key threw this during Spring MVC's path-variable binding, before the controller ever ran, and leaked a raw `500`. Same "client mistake misclassified as server bug" pattern already fixed twice before in this project (`HttpMessageNotReadableException` - Session 6; `CannotCreateTransactionException`/`DataAccessResourceFailureException` - Session 7/11) - a sibling exception type was simply missed both prior times. Fix: new handler maps it to `400`.
3. **`ChatModelFactory`/`EmbeddingModelFactory` cache eviction could race ahead of the DB commit.** Found by code inspection, not a forced live race: `ModelConfigService.configureModel()` publishes `ModelConfigUpdatedEvent` synchronously from inside its own `@Transactional` method body - Spring's transaction proxy commits only *after* the method returns, so a plain `@EventListener` evicts the cache *before* the row is durable. A concurrent `getChatModel()`/`getEmbeddingModel()` landing in that window would re-read `model_configs` under READ_COMMITTED, see the still-uncommitted old row, and repopulate the cache with stale data - permanently, since nothing evicts it again. No existing test could have caught this (all were strictly sequential: PUT then read). Fix: both factories switched to `@TransactionalEventListener(phase = AFTER_COMMIT)`. Verified with a new deterministic test (not a timing race): opens a transaction via `TransactionTemplate`, updates the row, publishes the event, asserts the cache is *still* the old instance while the transaction is open, then asserts it's evicted/rebuilt immediately after the transaction commits.
4. **Rate limiter's `INCR`+`EXPIRE` wasn't atomic.** `TenantCreationRateLimitFilter` called `INCR` then, only if the count was exactly `1`, a separate `EXPIRE` call - both wrapped in the same try/catch, so a transient failure hitting only the `EXPIRE` call (plausible given the 1s command timeout from Session 6) would leave a key with a bumped counter and no TTL, silently rate-limiting that IP forever. Checked the actual Redis version before choosing a fix (`redis-cli INFO server` → `6.2.18`) - Redis 7's native `EXPIRE ... NX` isn't available here, so used a single atomic Lua script (`RedisScript`/`DefaultRedisScript`, one round trip) instead, which works on any Redis version. Not independently forced live (needs precise fault injection between two sequential commands) - flagged this confidence distinction explicitly to the user rather than claiming the same certainty as findings 1-3.

**All four fixes re-verified live** against a running app (the exact `curl`/concurrent-`curl` reproductions that found each bug, re-run after the fix): malformed UUID now returns clean `400`; 10 concurrent model-config writes now return only `200`/`409`, zero `500`s.

**Also found and fixed, unrelated to the four bugs above:** `./mvnw spotless:check` failed across ~20 files with pre-existing formatting drift (files added since Session 9's spotless setup were apparently never run through `spotless:apply`) - ran `spotless:apply` to bring everything back into compliance; confirmed formatting-only via `./mvnw clean verify` staying green before and after.

**New tests added:** `ApiExceptionHandlerTest` (+3: type-mismatch → 400, unique-violation → 409, other-constraint-violation → 400), `ModelConfigIntegrationTest` (+3: the 10-concurrent-writes-never-500 reproduction, the AFTER_COMMIT eviction-timing proof, malformed-UUID-in-path → 400), `TenantCreationIntegrationTest` (+1: oversized name → 400), `TenantCreationRateLimitFilterTest` (rewritten, not added to - now mocks `redisTemplate.execute(RedisScript, ...)` instead of `opsForValue().increment(...)`/`.expire(...)`, since the filter no longer calls those). `./mvnw clean verify` green: **51 unit + 17 integration tests** (up from Session 12's 48 unit + 13 integration).

**Confirmed solid, no changes needed:** `DatabaseBootstrapRunner`/`AnnIndexBootstrapRunner` (re-read in full, no new issues beyond what Sessions 4/5 already found and fixed), `TenantContext`/`CorrelationIdFilter`/`ContextPropagatingTaskDecorator` (thread-local hygiene correct, cleared in `finally` on every path), `ApiKeyGenerator`/`ApiKeyHasher` (32-byte `SecureRandom`, one-way SHA-256, unique index on `key_hash` gives real DB-level collision protection even though practically unreachable), rate limiter's atomicity *within* the `INCR` step itself (control test: 10 concurrent tenant-creation requests correctly rate-limited at exactly the 5th, not the 4th or 6th - Redis `INCR` is atomic on its own, only the *conditional* `EXPIRE` was the gap).

**Lower-priority item flagged but explicitly deferred, not fixed:** evicted `ChatModel`/`EmbeddingModel` cache entries aren't explicitly closed on eviction (possible connection-pool resource leak under heavy model-config churn) - couldn't verify with real provider credentials in this environment (same blocker as every prior session), likely low risk since these are pooled HTTP clients with idle eviction. Not asked about via `AskUserQuestion` since it's speculative rather than confirmed - noted here for a future session if it becomes relevant once Stage 3+ makes the factories load-bearing in a real request path.

**Housekeeping:** cleaned up all test tenants/keys/configs created during live verification from the local `synapsemcp` database and the stray Redis rate-limit key from earlier `curl` runs; app confirmed booting cleanly and stopped at the end.

**Verdict: genuinely ready for Stage 3 now**, more so than Session 11's "good to go" - that session verified infrastructure resilience end-to-end but never tested concurrent application-level writes or cache-eviction timing, which is exactly where these four bugs were hiding. Stage 3's own concurrent-write paths (e.g. `create_knowledge_base`'s 10-KB-per-tenant limit) will need their own dedicated concurrency review when built - this session only closed gaps in what already existed.

**Next task:** `rag_plan.md` Stage 3 — Create Knowledge Base (`POST`/`GET`/`PUT`/`DELETE /api/v1/knowledgebase`, live-probe `embedding_dim` derivation via `EmbeddingModelFactory`, `knowledge_base_model_configs` snapshot creation at creation time, 10-KB-per-tenant limit, locked-dimension rule).

**Blockers:** Unchanged - no live Ollama server, no real provider API keys in this environment. Same `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` export note from Session 12 applies to every fresh shell.

---

## Session 14 — 2026-07-17

**Phase:** Second "final thorough validation" pass on Stages 0-2, same-day repeat of Session 13's request ("again lets do a final thorough validation... be honest, if you need any confirmation ask me instead of guessing"). Deliberately did **not** re-check Session 13's ground (concurrency races, constraint violations, transaction timing) - targeted genuinely untouched angles instead: credential/secret leakage, actuator exposure, and request-handling edge cases. **Found and fixed two more real bugs**, both confirmed with the user before fixing, one of which required a follow-up confirmation mid-implementation after the first chosen approach was proven insufficient.

**Method:** Grepped every `log.*` call in `src/main/java` for anything that might echo request bodies/credentials (none found - Hibernate's own SQL-error logging uses parameterized `?` placeholders, never literal bound values); checked actuator exposure config (no `management.endpoints.web.exposure.include`/`show-details` override anywhere, so Spring Boot's safe health-only default applies); then live-tested edge cases suggested by re-reading every service/controller/filter end-to-end: oversized fields on a *different* table/column than Session 13 tested (to prove that fix was genuinely general, not narrowly proven), and a cross-tenant request with a simultaneously-invalid body (an ordering question neither Session 13 nor any prior session had asked).

**Confirmed clean, no action needed:** no credential/secret leakage in any log statement; actuator endpoints correctly default to health-only exposure; Session 13's `DataIntegrityViolationException` fix confirmed genuinely general - live-tested against `model_configs.chat_model` (different table/column than originally tested), correctly returns `400`.

**Found and fixed two real gaps:**

1. **No request body size limit existed anywhere.** Sent a 5MB JSON body to both `POST /api/v1/tenants` (open, unauthenticated, gated only by the weak 5/hour/IP limiter) and `PUT .../model-config` (authenticated) - both fully accepted and parsed it; the latter, when app-layer provider validation rejected an oversized field, echoed the **entire 5MB value back** in the error response, doubling the cost. User confirmed fixing this but wanted to specify the approach rather than accept my first recommendation - chose "1MB via a Spring filter checking Content-Length" over "64KB via Tomcat connector property." Implemented `RequestBodySizeLimitFilter` accordingly - but the Content-Length-only check I'd previewed and the user picked turned out to be **insufficient**, discovered by actually running the new integration test rather than assuming it would pass: the test failed (`400` instead of `413`), and temporary debug logging revealed why - `TestRestTemplate`'s default Apache HttpClient5 factory sends **every single request** in this test suite `Transfer-Encoding: chunked` with **no `Content-Length` header at all**, not just the large-payload one. This wasn't a contrived edge case found by theorizing; it's Spring's own reference test client's actual default behavior, meaning the chosen approach would have silently protected against nothing for any client that streams/chunks its body - a materially different risk profile than what was presented in the original question. Went back to the user with this concrete evidence rather than silently upgrading the implementation myself; user chose to upgrade to stream-wrapping. Rebuilt `RequestBodySizeLimitFilter` to bounded-read the body (up to `maxBytes + 1`, deliberately not draining a full deliberately-huge upload just to reject it) regardless of declared `Content-Length`, then wrap the request so downstream readers (Jackson) see the already-validated buffered bytes. Re-verified live with both a real `Content-Length`-bearing curl request and an explicit `curl -H "Transfer-Encoding: chunked"` request - both correctly return `413` now.
2. **`ModelConfigController` validated the request body before checking tenant ownership.** Found live: a caller with a valid API key for a *different* tenant, sending an invalid body to a cross-tenant request, got `400` (revealing the required-field shape, though nothing tenant-specific) instead of `403`. Root cause, confirmed by reading Spring MVC's argument-resolution order: `@Valid` runs during argument resolution for every method parameter, in declaration order, before the controller method body ever executes - so `ModelConfigController`'s old per-method `requireOwnTenant()` call could never win this race regardless of where `tenantId` sits in the method signature; reordering parameters would not have fixed it. Correct fix has to run earlier in the MVC lifecycle: `TenantOwnershipInterceptor` (`HandlerInterceptor`, `preHandle` runs in `DispatcherServlet`'s interceptor chain strictly before argument resolution), registered via new `WebMvcConfig` against the wildcard path `/api/v1/tenants/{tenantId}/**` rather than one path per controller, so any future tenant-scoped endpoint is covered automatically. A malformed (non-UUID) `tenantId` is deliberately left unhandled in the interceptor - falls through to normal argument resolution, which already produces the correct `400` via Session 13's `MethodArgumentTypeMismatchException` handler, avoiding a duplicate check. Kept the old `requireOwnTenant()` calls in `ModelConfigController` as harmless, zero-cost defense-in-depth rather than removing them.

**Both fixes re-verified live** against a running app: oversized body via both `Content-Length` and chunked encoding → `413`; cross-tenant + invalid body → `403` (not `400`); a full legitimate PUT/GET round-trip re-confirmed unaffected by either new filter/interceptor.

**New tests added:** `RequestBodySizeLimitFilterTest` (3: content-length fast-path rejection, chunked/unknown-length rejection via bounded-read fallback, legitimate body correctly wrapped and readable downstream), `TenantOwnershipInterceptorTest` (4: match allowed, mismatch throws 403, malformed UUID falls through untouched, no path variable at all falls through), `ModelConfigIntegrationTest` (+1: cross-tenant + invalid body → 403 not 400), `TenantCreationIntegrationTest` (+1: oversized body → 413). `./mvnw clean verify` green: **58 unit + 19 integration tests** (up from Session 13's 51 unit + 17 integration). `./mvnw spotless:apply` re-run after these edits, clean.

**Process note worth remembering:** when the user picks a specific approach in response to an `AskUserQuestion`, that choice is still provisional on it actually working - Session 13/14's standing instruction is "ask instead of guessing," and that includes coming back to ask again if live verification proves the chosen approach doesn't hold, rather than either (a) silently shipping something that doesn't actually work, or (b) silently overriding the user's stated choice without telling them why. The mid-session follow-up `AskUserQuestion` here (after finding TestRestTemplate defaults to chunked encoding) is the pattern to repeat whenever a confirmed approach turns out to have a materially different risk profile than what was presented when it was chosen.

**Verdict: Stage 0-2 is now solid across three genuinely different audit dimensions** - infrastructure resilience (Sessions 6-8), concurrency/transaction-timing/constraint-handling (Session 13), and request-handling/secret-hygiene edge cases (this session). Each pass deliberately targeted ground the previous ones hadn't covered rather than re-verifying the same things with more confidence. Stage 3's own concurrent-write paths and any file-upload-specific size limits (Stage 4) will still need their own dedicated review when built.

**Next task:** `rag_plan.md` Stage 3 — Create Knowledge Base (unchanged from Session 13's note).

**Blockers:** Unchanged - no live Ollama server, no real provider API keys in this environment. Same `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` export note applies to every fresh shell.

---

## Session 15 — 2026-07-17

**Phase:** Third consecutive "final thorough validation" pass on Stages 0-2, same-day repeat of Sessions 13/14's identical request. No new stage started. This time deliberately avoided re-covering either prior session's ground (concurrency/constraint-violations/transaction-timing in 13; secrets/body-size/auth-ordering in 14) and instead reasoned about what "non-negotiable" requirement stated in `rag_plan.md`'s own Cross-Cutting Concerns table - tenant isolation - had never actually been tested against real concurrency, only sequential correctness.

**Method:** Created 15 distinct tenants via the real running app, each configured with a uniquely identifiable `chatModel` value (`tenant-{i}-unique-model`) so any cross-contamination would be immediately detectable in a response body, not just inferable from a status code. Fired 150 concurrent `GET model-config` requests, randomly interleaved across all 15 tenants (not sequential per-tenant batches), and asserted every single response's `tenantId`/`chatModel` matched the API key used to make that specific request. Also captured raw HTTP status codes for the same 150-request burst to separately confirm no request failed due to connection-pool contention (HikariCP's default pool is only size 10, untouched by any prior session - 150 concurrent DB reads is 15x the pool size). Additionally verified `X-Correlation-Id` presence/correctness on the two new error paths Session 14 added (`403` from `TenantOwnershipInterceptor`, `413` from `RequestBodySizeLimitFilter`) - previously asserted in Session 14's Javadoc/plan-doc prose but never actually curl-tested.

**All confirmed clean - no bugs found this pass:**
- **Zero cross-tenant leakage across 150 concurrent requests, 15 tenants.** Confirms `TenantContext` (`ThreadLocal`, populated by `ApiKeyAuthenticationFilter`, cleared in `finally` on every code path) is genuinely airtight under Tomcat's thread-pool-reuse model at meaningful concurrency - not just in theory (the reasoning was always sound: no `@Async` work exists yet in Stage 0-2, so the `finally` block always completes before a thread returns to the pool) but now actually demonstrated under load rather than assumed.
- **HikariCP's default pool (size 10) handled 150 concurrent requests gracefully** - all 150 returned clean `200`s, none hit the `503` mapping from Session 6/7's timeout fixes, meaning the pool queued efficiently for these fast, short-lived reads rather than anyone hitting the 3s connection-timeout ceiling.
- **Correlation ID correctness confirmed on both new error paths** from Session 14: present on `403` and `413`, and a client-supplied `X-Correlation-Id` header is correctly echoed back rather than overwritten with a server-generated one.

**One real gap found - documented, not fixed (the affected code doesn't exist yet):** `RequestBodySizeLimitFilter` (Session 14, `com.synapsemcp.common`) is global with no `shouldNotFilter` path exemption. Cross-referencing `rag_plan.md` Stage 4's own table (which I hadn't re-read carefully when adding that filter last session) shows Stage 4's planned document-upload endpoint already has a documented **20MB** size cap - meaning this filter's 1MB default will silently shadow it and reject every legitimate upload over 1MB long before Stage 4's own 20MB check is ever reached, once that endpoint exists. Not fixed now since there's no Stage 4 controller to test an exemption against yet, and the fix (a `shouldNotFilter` path exemption) is trivial when that time comes - but documented explicitly in both the filter's own Javadoc and `rag_plan.md` Stage 4's table row, specifically so a future session building Stage 4 doesn't discover this as a mysterious "why are my uploads getting rejected" bug instead of a known, already-flagged constraint.

**No code changes this session beyond the two documentation/Javadoc notes about the Stage 4 collision** - everything else was verification that came back clean. `./mvnw clean verify` and `./mvnw spotless:check` both still green (no test/production code touched, only comments) - re-ran both anyway to confirm the Javadoc edit didn't break compilation.

**Process reflection, three passes in on the same request:** each of the three "final thorough validation" sessions found real, different bugs by deliberately choosing a method the previous pass hadn't used (Session 13: fire concurrent requests + malformed input; Session 14: grep logs for secrets, test request-handling edge cases; Session 15: stress-test the one specific "non-negotiable" requirement under real concurrency). The common thread across all three, and every infrastructure-audit session going back to Session 6, remains: reasoning about why something *should* be correct is not the same as demonstrating it live, and the fastest way to find what's still broken is to pick an angle no prior pass actually exercised rather than re-running higher-confidence versions of the same checks.

**Next task:** `rag_plan.md` Stage 3 — Create Knowledge Base (unchanged). When Stage 4 is eventually reached, remember the `RequestBodySizeLimitFilter`/20MB-cap collision flagged this session before wiring up the multipart upload endpoint.

**Blockers:** Unchanged - no live Ollama server, no real provider API keys in this environment. Same `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` export note applies to every fresh shell.

---

## Session 16 — 2026-07-17

**Phase:** Documentation consolidation pass, user-requested ("update everything in plan.md, rag_plan.md, and memory.md accordingly upto the current status"). Not a new audit - re-read all three docs end-to-end (not just the sections touched by recent edits) specifically to check for drift, dangling references, and consistency between what's documented and what Sessions 13-15's rapid-fire edits actually left behind.

**Found and fixed two real documentation gaps:**

1. **`rag_plan.md`'s "Resolved Grooming Decisions Summary" table stopped at #34** - Sessions 13-15's six real findings (`DataIntegrityViolationException`/`409`-`400` mapping, `MethodArgumentTypeMismatchException`/`400`, `@TransactionalEventListener(AFTER_COMMIT)` cache-eviction fix, the atomic Lua rate-limiter script, `RequestBodySizeLimitFilter`, `TenantOwnershipInterceptor`) were all fully documented in prose (the "Concurrency & Constraint-Violation Handling" and "Request Body Size Limit & Authorization Ordering" subsections under Stage 0), but never captured as scannable table rows the way every other decision in the document is - meaning a future session skimming just the summary table (a reasonable thing to do) would miss six real architectural decisions entirely. Added rows #35-40 summarizing each.
2. **Stage 0's "Package Structure" section was stale since Session 4** - it still literally listed the original `com.synapsemcp.core`/`com.synapsemcp.mcp`/`com.synapsemcp.rag`-only layout, even though Session 4 (2026-07-16) had already established the actual, deliberate convention (one package per domain) as correct and every session since has followed it without exception. `memory.md` had this documented as a "known, deliberate deviation" back in Session 4, but the authoritative spec text itself (`rag_plan.md`) was never actually corrected to match - the exact "fix lands in code/memory.md but not in the spec document itself" gap Session 9 warned about explicitly, recurring here for a different fix. Corrected the section to describe the real current structure (`tenant`, `knowledgebase`, `document`, `ingestion`, `rag.chunk`, `chat`, `embedding`, `config`, `common`, verified directly via `find` against the live source tree) and added a decision note explaining the supersession.

**Confirmed accurate, no changes needed:** `plan.md` in full (Decision Log correctly has all three 2026-07-17 session entries, §3's canonical commands, tech stack, architecture diagram all still match reality); `rag_plan.md`'s Stage 0 through Stage 6 content, REST API summary, and Cross-Cutting Concerns table (all already correctly updated incrementally during Sessions 13-15 - re-read fully to confirm, not just spot-checked); `memory.md`'s own Session 1-15 log (internally consistent, no contradictions, already complete through Session 15 since it was being actively written during those same sessions in this conversation).

**No code changes this session** - `./mvnw clean verify` and `./mvnw spotless:check` re-run anyway to confirm the doc-only edits didn't somehow break compilation (they didn't, as expected): 58 unit + 19 integration tests still green.

**Next task:** `rag_plan.md` Stage 3 — Create Knowledge Base (unchanged from Session 15).

**Blockers:** Unchanged - no live Ollama server, no real provider API keys in this environment. Same `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` export note applies to every fresh shell.
