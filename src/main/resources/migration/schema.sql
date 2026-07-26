-- Supplemental DDL that plain JPA/ddl-auto can't express (rag_plan.md Stage 0.5): the pgvector HNSW
-- ANN indexes on `chunks`, the two provider CHECK constraints on `model_configs`, and the
-- case-insensitive per-tenant knowledge-base-name uniqueness index. Every statement is idempotent
-- (safe to run repeatedly) - this used to be raw SQL executed by a custom Java ApplicationRunner
-- (AnnIndexBootstrapRunner, removed) at every startup; it's now this plain SQL file instead.
--
-- Applied two different ways depending on environment, both against the identical content here:
--   - local/dev/test: Spring Boot's own deferred SQL initialization runs this automatically, right
--     after Hibernate's ddl-auto creates/validates the base schema, on every application context
--     startup (spring.jpa.defer-datasource-initialization: true, spring.sql.init.mode: always,
--     spring.sql.init.schema-locations: classpath:migration/schema.sql - lives under this
--     migration/ subdirectory, not the classpath root, so the default schema-locations wouldn't
--     find it - see application-local.yaml/application-dev.yaml/application-test.yaml). For `test`
--     (ddl-auto: create-drop), this re-applies on every fresh schema, which is exactly why this
--     couldn't just be a one-off external script for that profile.
--   - prod: spring.sql.init.mode is `never` - this file is instead applied directly via `psql` as
--     part of the CI/CD database-initialization pipeline stage (Phase 3), before the app is ever
--     started, matching prod's existing `ddl-auto: validate` contract (the app never mutates
--     schema there).
--
-- `uq_knowledge_bases_tenant_name_ci` is the one genuinely load-bearing statement here, not just
-- defense-in-depth: KnowledgeBaseService.updateKnowledgeBase() (a rename) has no application-level
-- lock, and relies on catching this exact constraint's violation as its only protection against a
-- concurrent duplicate-name race (see KnowledgeBaseService's own Javadoc, Grooming #89). The two
-- CHECK constraints below are pure defense-in-depth - ModelConfigService.validateProvider() already
-- enforces the same allow-list at the application layer on every write path.

CREATE INDEX IF NOT EXISTS idx_chunks_emb_384 ON chunks USING hnsw (embedding_384 vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_chunks_emb_512 ON chunks USING hnsw (embedding_512 vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_chunks_emb_768 ON chunks USING hnsw (embedding_768 vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_chunks_emb_1024 ON chunks USING hnsw (embedding_1024 vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_chunks_emb_1536 ON chunks USING hnsw (embedding_1536 vector_cosine_ops);
-- embedding_3072 uses halfvec, not vector - pgvector's HNSW index caps at 2000 dimensions for the
-- plain vector type, so this column (and only this one) needs the matching halfvec_cosine_ops
-- operator class instead of vector_cosine_ops (see Chunk.getEmbedding3072()'s own Javadoc).
CREATE INDEX IF NOT EXISTS idx_chunks_emb_3072 ON chunks USING hnsw (embedding_3072 halfvec_cosine_ops);

-- Postgres has no `ADD CONSTRAINT IF NOT EXISTS` - drop-then-add is the idempotent equivalent.
-- Deliberately NOT a `DO $$ ... EXCEPTION WHEN duplicate_object $$` block (AnnIndexBootstrapRunner's
-- old approach): Spring Boot's built-in SQL initializer (used in local/dev/test, see
-- application-local.yaml) splits this file into statements by a bare semicolon and has no concept
-- of Postgres's `$$`-quoting, so it would split a DO block's own internal semicolons into broken
-- statement fragments (confirmed live - "Unterminated dollar quote" from the Postgres JDBC driver).
-- Plain DROP-then-ADD needs no dollar-quoting at all and works identically here and via psql.
ALTER TABLE model_configs DROP CONSTRAINT IF EXISTS chk_model_configs_chat_provider;
ALTER TABLE model_configs ADD CONSTRAINT chk_model_configs_chat_provider
    CHECK (chat_provider IN ('openai','anthropic','ollama','google-genai'));

ALTER TABLE model_configs DROP CONSTRAINT IF EXISTS chk_model_configs_embedding_provider;
ALTER TABLE model_configs ADD CONSTRAINT chk_model_configs_embedding_provider
    CHECK (embedding_provider IN ('openai','ollama','google-genai'));

CREATE UNIQUE INDEX IF NOT EXISTS uq_knowledge_bases_tenant_name_ci
    ON knowledge_bases (tenant_id, lower(name));
