#!/usr/bin/env bash
#
# SynapseMCP — first-run initialization wizard (macOS + Linux).
#
# Distinct from scripts/setup-environment.sh (referenced in plan.md/rag_plan.md as a narrower
# "install services + reset synapsemcp/synapsemcp_test to an empty clean slate" tool) - this script
# is a superset: it also starts the app, verifies real connectivity (Postgres/Redis/Lucene/pgvector
# *and* the tenant's configured chat/embedding models), and creates a first tenant + knowledge base
# through the running API. Safe to re-run; every destructive step asks for confirmation first.
#
# Usage:
#   ./scripts/init-environment.sh
#
# Requires: bash 4+, psql, redis-cli, java 21+, curl, jq, the committed ./mvnw wrapper.

set -uo pipefail

# ── Paths ────────────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
APP_LOG="$PROJECT_ROOT/init-environment-app.log"
TMP_DIR="$(mktemp -d)"

# ── Output helpers ───────────────────────────────────────────────────────────
if [ -t 1 ]; then
  C_RESET=$'\033[0m'; C_RED=$'\033[31m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'; C_BLUE=$'\033[34m'
else
  C_RESET=""; C_RED=""; C_GREEN=""; C_YELLOW=""; C_BLUE=""
fi
info()    { printf '%s[INFO]%s  %s\n'  "$C_BLUE"   "$C_RESET" "$1"; }
success() { printf '%s[ OK ]%s  %s\n'  "$C_GREEN"  "$C_RESET" "$1"; }
warn()    { printf '%s[WARN]%s  %s\n'  "$C_YELLOW" "$C_RESET" "$1"; }
error()   { printf '%s[FAIL]%s  %s\n'  "$C_RED"    "$C_RESET" "$1" >&2; }
step()    { printf '\n%s=== %s ===%s\n' "$C_BLUE" "$1" "$C_RESET"; }

ask() { # ask "prompt" "default" -> echoes the answer
  local prompt="$1" default="${2:-}" answer
  if [ -n "$default" ]; then
    read -r -p "$prompt [$default]: " answer
    echo "${answer:-$default}"
  else
    read -r -p "$prompt: " answer
    echo "$answer"
  fi
}
ask_secret() { # ask_secret "prompt" -> echoes the answer, input hidden
  local prompt="$1" answer
  read -r -s -p "$prompt: " answer
  echo >&2
  echo "$answer"
}
confirm() { # confirm "prompt" -> returns 0 for yes
  local prompt="$1" answer
  read -r -p "$prompt [y/N]: " answer
  [[ "$answer" =~ ^[Yy]$ ]]
}

# ── State tracked for rollback (Step 7) ───────────────────────────────────────
STEP_NAME="startup"
APP_PID=""
APP_STARTED_BY_SCRIPT=false
MAIN_DB_EXISTED=false
TEST_DB_EXISTED=false
CLEANUP_DONE=false
TENANT_ID=""
FAILED=false

on_exit() {
  local exit_code=$?
  if [ "$FAILED" = true ] || { [ "$exit_code" -ne 0 ] && [ "$STEP_NAME" != "finalization" ]; }; then
    error "Initialization failed during: $STEP_NAME"
    rollback
    error "Rollback finished. Your environment has been restored as closely as possible to its"
    error "prior state. Nothing from this failed run is left running or half-configured."
  fi
  rm -rf "$TMP_DIR" 2>/dev/null || true
  exit "$exit_code"
}
trap on_exit EXIT

fail() { # fail "message" -> print, mark failed, exit (triggers rollback via the EXIT trap)
  error "$1"
  FAILED=true
  exit 1
}

collect_descendants() {
  # Recursively walks the process tree rooted at $1, printing it and every descendant pid.
  local parent="$1" child
  echo "$parent"
  for child in $(pgrep -P "$parent" 2>/dev/null); do
    collect_descendants "$child"
  done
}

stop_app_if_running() {
  # ./mvnw does not exec-replace itself - it forks a Maven JVM as a child, which in turn forks a
  # *second* JVM (the actual Spring Boot app, `spring-boot:run`'s default fork=true lifecycle)
  # that's the one actually bound to the port. Killing only $APP_PID (the ./mvnw wrapper shell's
  # own pid, from $!) leaves both descendant JVMs running as orphans - the app looks "stopped" but
  # is still live and still holds the port.
  #
  # An earlier version matched on the main class name process-wide (`pgrep -f
  # com.synapsemcp.SynapsemcpApplication`) to reach that descendant JVM - found live to be a real,
  # dangerous bug: on a machine already running an unrelated SynapsemcpApplication instance (e.g.
  # started from an IDE), that broad match killed it too, even though this script never started
  # it and this run wasn't even past the confirmation prompt that would have started one. Fixed by
  # only ever walking $APP_PID's own process tree (this script's own ./mvnw child and whatever it
  # forked) - it can never see, let alone kill, a process this run didn't itself start.
  [ -z "$APP_PID" ] && return
  kill -0 "$APP_PID" 2>/dev/null || return
  local pids
  pids=$(collect_descendants "$APP_PID" | sort -u)
  info "Stopping the application (pid(s): $(echo "$pids" | tr '\n' ' '))..."
  for pid in $pids; do kill "$pid" 2>/dev/null || true; done
  for _ in $(seq 1 20); do
    local any_alive=false
    for pid in $pids; do kill -0 "$pid" 2>/dev/null && any_alive=true; done
    [ "$any_alive" = true ] || break
    sleep 0.5
  done
  for pid in $pids; do kill -9 "$pid" 2>/dev/null || true; done
}

rollback() {
  step "Rolling back"
  stop_app_if_running

  if [ -n "$TENANT_ID" ]; then
    info "Removing the tenant this run partially created (id: $TENANT_ID) - no DELETE endpoint"
    info "exists for tenants, so this goes directly through the database, same as its own FK cascade order."
    PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USERNAME" -d synapsemcp -v ON_ERROR_STOP=0 <<SQL >/dev/null 2>&1 || true
DELETE FROM api_keys WHERE tenant_id = '$TENANT_ID';
DELETE FROM model_configs WHERE tenant_id = '$TENANT_ID';
DELETE FROM knowledge_bases WHERE tenant_id = '$TENANT_ID';
DELETE FROM tenants WHERE id = '$TENANT_ID';
SQL
  fi

  if [ "$CLEANUP_DONE" = true ]; then
    restore_database "synapsemcp" "$MAIN_DB_EXISTED"
    restore_database "synapsemcp_test" "$TEST_DB_EXISTED"
  fi
}

restore_database() {
  local dbname="$1" existed="$2"
  if [ "$existed" = false ] && [ "$CLEANUP_DONE" = true ]; then
    info "$dbname did not exist before this run - dropping it again to match the prior (absent) state."
    PGPASSWORD="$DB_PASSWORD" dropdb -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USERNAME" --if-exists "$dbname" 2>/dev/null || true
  fi
}

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    error "Required tool not found: $1"
    echo "$2"
    return 1
  fi
  return 0
}

# ══════════════════════════════════════════════════════════════════════════
step "SynapseMCP Initialization Wizard"
# ══════════════════════════════════════════════════════════════════════════
echo "This will (1) collect environment configuration, (2) validate dependencies,"
echo "(3) destructively reset the local + test databases/caches, (4) start the app and verify"
echo "connectivity (including your chat/embedding provider), and (5) create a test tenant,"
echo "knowledge base, and model config. Every destructive step asks for confirmation first."

# ── Step 1: Environment Setup ────────────────────────────────────────────────
STEP_NAME="environment setup"
step "Step 1/7: Environment Setup"
echo "Which profile is this for?"
echo "  1) local (default)"
echo "  2) dev"
PROFILE_CHOICE=$(ask "Enter 1 or 2" "1")
case "$PROFILE_CHOICE" in
  2) PROFILE="dev" ;;
  *) PROFILE="local" ;;
esac
info "Profile: $PROFILE"
if [ "$PROFILE" != "local" ] && [ "$PROFILE" != "dev" ]; then
  fail "This wizard only supports local/dev (prod's bootstrap runners are disabled by design and its schema/role must already be provisioned manually - see rag_plan.md Stage 0.5)."
fi

DB_URL=$(ask "DB_URL (JDBC base URL, no database name)" "jdbc:postgresql://localhost:5432")
# Parse host:port out of the JDBC URL for psql/pg_isready/etc, which don't take JDBC URLs directly.
DB_HOSTPORT="${DB_URL#jdbc:postgresql://}"
DB_HOST="${DB_HOSTPORT%%:*}"
DB_PORT="${DB_HOSTPORT##*:}"
DB_PORT="${DB_PORT%%/*}"
if [ -z "$DB_HOST" ] || [ -z "$DB_PORT" ]; then
  fail "Could not parse host/port out of DB_URL='$DB_URL' - expected jdbc:postgresql://<host>:<port>"
fi

DB_USERNAME=$(ask "DB_USERNAME (a role that can already authenticate to Postgres)" "$(whoami)")
DB_PASSWORD=$(ask_secret "DB_PASSWORD (leave empty for trust/peer auth)")

if confirm "Use defaults for the remaining settings (timeouts, rate limits, Lucene directory)?"; then
  DB_CONNECTION_TIMEOUT="3000"
  DB_LOCK_TIMEOUT="3s"
  REDIS_HOST="localhost"
  REDIS_PORT="6379"
  REDIS_CONNECT_TIMEOUT="1s"
  REDIS_TIMEOUT="1s"
  SYNAPSEMCP_PROVIDER_TIMEOUT_SECONDS="30"
  SYNAPSEMCP_LUCENE_BASE_DIR="${TMPDIR:-/tmp}/synapsemcp/lucene-indexes"
  SYNAPSEMCP_RATE_LIMIT_TENANT_CREATION_LIMIT="5"
  SYNAPSEMCP_RATE_LIMIT_TENANT_CREATION_WINDOW_SECONDS="3600"
else
  DB_CONNECTION_TIMEOUT=$(ask "DB_CONNECTION_TIMEOUT (ms, HikariCP)" "3000")
  DB_LOCK_TIMEOUT=$(ask "DB_LOCK_TIMEOUT (Postgres lock_timeout)" "3s")
  REDIS_HOST=$(ask "REDIS_HOST" "localhost")
  REDIS_PORT=$(ask "REDIS_PORT" "6379")
  REDIS_CONNECT_TIMEOUT=$(ask "REDIS_CONNECT_TIMEOUT" "1s")
  REDIS_TIMEOUT=$(ask "REDIS_TIMEOUT" "1s")
  SYNAPSEMCP_PROVIDER_TIMEOUT_SECONDS=$(ask "SYNAPSEMCP_PROVIDER_TIMEOUT_SECONDS (chat/embedding HTTP client timeout)" "30")
  SYNAPSEMCP_LUCENE_BASE_DIR=$(ask "SYNAPSEMCP_LUCENE_BASE_DIR" "${TMPDIR:-/tmp}/synapsemcp/lucene-indexes")
  SYNAPSEMCP_RATE_LIMIT_TENANT_CREATION_LIMIT=$(ask "SYNAPSEMCP_RATE_LIMIT_TENANT_CREATION_LIMIT" "5")
  SYNAPSEMCP_RATE_LIMIT_TENANT_CREATION_WINDOW_SECONDS=$(ask "SYNAPSEMCP_RATE_LIMIT_TENANT_CREATION_WINDOW_SECONDS" "3600")
fi

export DB_URL DB_USERNAME DB_PASSWORD DB_CONNECTION_TIMEOUT DB_LOCK_TIMEOUT
export REDIS_HOST REDIS_PORT REDIS_CONNECT_TIMEOUT REDIS_TIMEOUT
export SYNAPSEMCP_PROVIDER_TIMEOUT_SECONDS SYNAPSEMCP_LUCENE_BASE_DIR
export SYNAPSEMCP_RATE_LIMIT_TENANT_CREATION_LIMIT SYNAPSEMCP_RATE_LIMIT_TENANT_CREATION_WINDOW_SECONDS
success "Environment collected."

# ── Step 2: Dependency Validation ────────────────────────────────────────────
STEP_NAME="dependency validation"
step "Step 2/7: Dependency Validation"

DEPS_OK=true
require_cmd psql       "Install the PostgreSQL client (macOS: brew install postgresql@17 · Debian/Ubuntu: sudo apt install postgresql-client)" || DEPS_OK=false
require_cmd redis-cli  "Install Redis (macOS: brew install redis · Debian/Ubuntu: sudo apt install redis-tools)" || DEPS_OK=false
require_cmd java       "Install Java 21+ (e.g. via sdkman, or your OS package manager)" || DEPS_OK=false
require_cmd curl       "Install curl" || DEPS_OK=false
require_cmd jq         "Install jq (macOS: brew install jq · Debian/Ubuntu: sudo apt install jq)" || DEPS_OK=false
if [ ! -x "$PROJECT_ROOT/mvnw" ]; then
  error "Maven wrapper not found/executable at $PROJECT_ROOT/mvnw"
  DEPS_OK=false
fi
[ "$DEPS_OK" = true ] || fail "One or more required tools are missing - see messages above."
success "All required command-line tools are present."

JAVA_VERSION=$(java -version 2>&1 | head -1 | grep -oE '"[0-9]+' | tr -d '"')
if [ -z "$JAVA_VERSION" ] || [ "$JAVA_VERSION" -lt 21 ]; then
  fail "Java 21+ is required; found version '$JAVA_VERSION'."
fi
success "Java $JAVA_VERSION detected."

info "Checking Postgres connectivity at $DB_HOST:$DB_PORT as '$DB_USERNAME'..."
if ! PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USERNAME" -d postgres -tAc "SELECT 1" >/dev/null 2>&1; then
  fail "Could not connect to Postgres at $DB_HOST:$DB_PORT as '$DB_USERNAME'. Is the server running and are the credentials correct?"
fi
success "Postgres is reachable."

info "Checking the pgvector extension is installed on this Postgres server..."
PGVECTOR_AVAILABLE=$(PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USERNAME" -d postgres -tAc \
  "SELECT 1 FROM pg_available_extensions WHERE name = 'vector'" 2>/dev/null || echo "")
if [ "$PGVECTOR_AVAILABLE" != "1" ]; then
  fail "pgvector is not installed on this Postgres server (no 'vector' entry in pg_available_extensions). Install it (macOS: brew install pgvector · Linux: see https://github.com/pgvector/pgvector#installation) and retry."
fi
success "pgvector is installed on the server."

info "Checking Redis connectivity at $REDIS_HOST:$REDIS_PORT..."
if [ "$(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" PING 2>/dev/null)" != "PONG" ]; then
  fail "Could not reach Redis at $REDIS_HOST:$REDIS_PORT. Is the server running?"
fi
success "Redis is reachable."

# ── Step 3: Cleanup ───────────────────────────────────────────────────────────
STEP_NAME="cleanup"
step "Step 3/7: Cleanup"
echo "This will DESTRUCTIVELY reset (no backup is taken - this cannot be undone):"
echo "  - Postgres databases 'synapsemcp' and 'synapsemcp_test' (dropped and recreated empty)"
echo "  - pgvector data (it lives inside those databases, so this is the same action)"
echo "  - Redis database index 0 (flushed)"
echo "  - The Lucene index directory: $SYNAPSEMCP_LUCENE_BASE_DIR (deleted)"
if ! confirm "Proceed with this destructive reset?"; then
  fail "Cancelled by user before any destructive action was taken."
fi

for dbname in synapsemcp synapsemcp_test; do
  exists=$(PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USERNAME" -d postgres -tAc \
    "SELECT 1 FROM pg_database WHERE datname = '$dbname'" 2>/dev/null || echo "")
  if [ "$exists" = "1" ]; then
    if [ "$dbname" = "synapsemcp" ]; then MAIN_DB_EXISTED=true; else TEST_DB_EXISTED=true; fi
  fi
done

info "Dropping and recreating both databases..."
PGPASSWORD="$DB_PASSWORD" dropdb -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USERNAME" --if-exists synapsemcp \
  || fail "Failed to drop 'synapsemcp'."
PGPASSWORD="$DB_PASSWORD" dropdb -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USERNAME" --if-exists synapsemcp_test \
  || fail "Failed to drop 'synapsemcp_test'."
PGPASSWORD="$DB_PASSWORD" createdb -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USERNAME" -O "$DB_USERNAME" synapsemcp \
  || fail "Failed to create 'synapsemcp'."
PGPASSWORD="$DB_PASSWORD" createdb -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USERNAME" -O "$DB_USERNAME" synapsemcp_test \
  || fail "Failed to create 'synapsemcp_test'."
CLEANUP_DONE=true
# rag_plan.md Stage 0.5: DatabaseBootstrapRunner (which would normally do this) is local/dev-only
# and never runs for the `test` profile - synapsemcp_test's vector extension must already exist
# before the app/test-suite ever connects to it, so this script creates it explicitly for both.
PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USERNAME" -d synapsemcp -c "CREATE EXTENSION IF NOT EXISTS vector;" >/dev/null \
  || fail "Failed to enable pgvector on 'synapsemcp'."
PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USERNAME" -d synapsemcp_test -c "CREATE EXTENSION IF NOT EXISTS vector;" >/dev/null \
  || fail "Failed to enable pgvector on 'synapsemcp_test'."
success "Databases reset and pgvector enabled on both."

info "Flushing Redis (database index 0)..."
redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" -n 0 FLUSHDB >/dev/null || fail "Failed to flush Redis."
success "Redis flushed."

info "Removing the Lucene index directory..."
rm -rf "$SYNAPSEMCP_LUCENE_BASE_DIR"
mkdir -p "$SYNAPSEMCP_LUCENE_BASE_DIR"
touch "$SYNAPSEMCP_LUCENE_BASE_DIR/.write-test" && rm -f "$SYNAPSEMCP_LUCENE_BASE_DIR/.write-test" \
  || fail "Lucene base directory '$SYNAPSEMCP_LUCENE_BASE_DIR' is not writable."
success "Lucene index directory cleared and confirmed writable."

# ── Step 4: Verification and Connectivity ────────────────────────────────────
STEP_NAME="starting the application"
step "Step 4/7: Start the Application & Verify Connectivity"
info "Starting the application on profile '$PROFILE' (this may take a little while on first boot)..."
(cd "$PROJECT_ROOT" && ./mvnw -q spring-boot:run "-Dspring-boot.run.profiles=$PROFILE" > "$APP_LOG" 2>&1 &
 echo $! > "$TMP_DIR/app.pid")
sleep 1
APP_PID=$(cat "$TMP_DIR/app.pid")
APP_STARTED_BY_SCRIPT=true

info "Waiting for /actuator/health to report UP (up to 90s)..."
HEALTHY=false
HEALTH_TIMEOUT=90
for i in $(seq 1 "$HEALTH_TIMEOUT"); do
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    break
  fi
  if [ -t 1 ]; then
    printf '\r\033[K  Starting application... %d%%' "$(( i * 100 / HEALTH_TIMEOUT ))"
  fi
  STATUS=$(curl -s --max-time 2 http://localhost:8080/actuator/health 2>/dev/null | jq -r '.status' 2>/dev/null || echo "")
  if [ "$STATUS" = "UP" ]; then
    HEALTHY=true
    break
  fi
  sleep 1
done
[ -t 1 ] && printf '\r\033[K'
if [ "$HEALTHY" != true ]; then
  error "Application did not become healthy in time. Last 60 lines of $APP_LOG:"
  tail -60 "$APP_LOG" >&2 || true
  fail "Application startup/health check failed."
fi
success "Application is UP (confirms Postgres connectivity - the DataSource health indicator)."

if grep -q "ANN indexes and CHECK constraints verified" "$APP_LOG"; then
  success "Schema + pgvector HNSW indexes verified by the app's own startup bootstrap."
else
  fail "Did not find the expected ANN/pgvector bootstrap confirmation in the app log - schema may not be ready."
fi

info "Re-checking Redis connectivity now that the app is running..."
if [ "$(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" PING 2>/dev/null)" != "PONG" ]; then
  fail "Redis became unreachable after app startup."
fi
success "Redis connectivity confirmed."
info "Lucene needs no server of its own - its base directory was already confirmed writable above;"
info "a real per-knowledge_base index directory will be created under it once a document is ingested below."

# ── Step 5: Initial Configuration (also verifies chat/embedding connectivity) ─
STEP_NAME="initial configuration"
step "Step 5/7: Initial Configuration"
echo "This step also completes Step 4's requirement to verify the chat/embedding models are"
echo "connected and functioning - there is no separate 'test connection' endpoint, so the proof is"
echo "a real knowledge_base creation (embeds a live probe string) and a real ask (a live chat call)."

TENANT_NAME=$(ask "Test tenant name" "Test Tenant")
TENANT_RESP=$(curl -s -w '\n%{http_code}' -X POST http://localhost:8080/api/v1/tenants \
  -H "Content-Type: application/json" -d "$(jq -n --arg name "$TENANT_NAME" '{name:$name}')")
TENANT_HTTP=$(echo "$TENANT_RESP" | tail -1)
TENANT_BODY=$(echo "$TENANT_RESP" | sed '$d')
[ "$TENANT_HTTP" = "201" ] || fail "Tenant creation failed (HTTP $TENANT_HTTP): $TENANT_BODY"
TENANT_ID=$(echo "$TENANT_BODY" | jq -r '.tenantId')
TENANT_API_KEY=$(echo "$TENANT_BODY" | jq -r '.apiKey')
success "Tenant created: $TENANT_ID"

echo
echo "Chat provider options: openai, anthropic, ollama, google-genai"
CHAT_PROVIDER=$(ask "Chat provider" "openai")
CHAT_MODEL=$(ask "Chat model name" "gpt-4o")
echo "Embedding provider options: openai, ollama, google-genai (no anthropic - it has no embeddings API)"
EMBEDDING_PROVIDER=$(ask "Embedding provider" "openai")
EMBEDDING_MODEL=$(ask "Embedding model name" "text-embedding-3-small")

CHAT_API_KEY=""
if [ "$CHAT_PROVIDER" != "ollama" ]; then
  CHAT_API_KEY=$(ask_secret "Chat API key (required for $CHAT_PROVIDER)")
fi
EMBEDDING_API_KEY=""
if [ "$EMBEDDING_PROVIDER" != "ollama" ]; then
  EMBEDDING_API_KEY=$(ask_secret "Embedding API key (required for $EMBEDDING_PROVIDER)")
fi

MODEL_CFG_BODY=$(jq -n \
  --arg cp "$CHAT_PROVIDER" --arg cm "$CHAT_MODEL" --arg ep "$EMBEDDING_PROVIDER" --arg em "$EMBEDDING_MODEL" \
  --arg cak "$CHAT_API_KEY" --arg eak "$EMBEDDING_API_KEY" \
  '{chatProvider:$cp, chatModel:$cm, embeddingProvider:$ep, embeddingModel:$em, chatApiKey:$cak, embeddingApiKey:$eak}')
MODEL_CFG_RESP=$(curl -s -w '\n%{http_code}' -X PUT "http://localhost:8080/api/v1/tenants/$TENANT_ID/model-config" \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TENANT_API_KEY" -d "$MODEL_CFG_BODY")
MODEL_CFG_HTTP=$(echo "$MODEL_CFG_RESP" | tail -1)
[ "$MODEL_CFG_HTTP" = "200" ] || fail "Model configuration failed (HTTP $MODEL_CFG_HTTP): $(echo "$MODEL_CFG_RESP" | sed '$d')"
success "Model configuration saved."

KB_NAME=$(ask "Knowledge base name" "test-kb")
KB_RESP=$(curl -s -w '\n%{http_code}' -X POST http://localhost:8080/api/v1/knowledgebase \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TENANT_API_KEY" \
  -d "$(jq -n --arg name "$KB_NAME" '{name:$name}')")
KB_HTTP=$(echo "$KB_RESP" | tail -1)
KB_BODY=$(echo "$KB_RESP" | sed '$d')
if [ "$KB_HTTP" != "201" ]; then
  fail "Knowledge base creation failed (HTTP $KB_HTTP): $KB_BODY - this is the live embedding-model probe, so this most likely means the embedding provider/model/credentials aren't working."
fi
KB_ID=$(echo "$KB_BODY" | jq -r '.id')
KB_DIM=$(echo "$KB_BODY" | jq -r '.embeddingDim')
success "Knowledge base created (id: $KB_ID, embedding_dim: $KB_DIM) - embedding model confirmed connected and functioning."

TEST_DOC_CONTENT=$(ask "Content for a small test document" "SynapseMCP is a multi-tenant retrieval-augmented generation platform.")
TEST_DOC_FILE="$TMP_DIR/test-document.txt"
printf '%s\n' "$TEST_DOC_CONTENT" > "$TEST_DOC_FILE"
UPLOAD_RESP=$(curl -s -w '\n%{http_code}' -X POST "http://localhost:8080/api/v1/knowledgebase/$KB_ID/documents" \
  -H "Authorization: Bearer $TENANT_API_KEY" -F "file=@$TEST_DOC_FILE;type=text/plain")
UPLOAD_HTTP=$(echo "$UPLOAD_RESP" | tail -1)
UPLOAD_BODY=$(echo "$UPLOAD_RESP" | sed '$d')
[[ "$UPLOAD_HTTP" =~ ^(200|202)$ ]] || fail "Test document upload failed (HTTP $UPLOAD_HTTP): $UPLOAD_BODY"
JOB_ID=$(echo "$UPLOAD_BODY" | jq -r '.jobId')

info "Waiting for ingestion to complete (up to 60s - this re-exercises the embedding model)..."
JOB_STATUS="PENDING"
for _ in $(seq 1 60); do
  JOB_STATUS=$(curl -s "http://localhost:8080/api/v1/jobs/$JOB_ID" -H "Authorization: Bearer $TENANT_API_KEY" | jq -r '.status')
  [[ "$JOB_STATUS" == "READY" || "$JOB_STATUS" == "FAILED" ]] && break
  sleep 1
done
if [ "$JOB_STATUS" != "READY" ]; then
  ERROR_DETAIL=$(curl -s "http://localhost:8080/api/v1/jobs/$JOB_ID" -H "Authorization: Bearer $TENANT_API_KEY" | jq -r '.errorDetail')
  fail "Test document ingestion did not reach READY (status: $JOB_STATUS, detail: $ERROR_DETAIL)."
fi
success "Test document ingested successfully - embedding model re-confirmed, and a real Lucene index now exists under $SYNAPSEMCP_LUCENE_BASE_DIR/$KB_ID."

TEST_QUESTION=$(ask "A test question to ask against the document" "What is SynapseMCP?")
ASK_RESP=$(curl -s -w '\n%{http_code}' -X POST "http://localhost:8080/api/v1/knowledgebase/$KB_ID/ask" \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TENANT_API_KEY" \
  -d "$(jq -n --arg q "$TEST_QUESTION" '{question:$q}')")
ASK_HTTP=$(echo "$ASK_RESP" | tail -1)
ASK_BODY=$(echo "$ASK_RESP" | sed '$d')
[ "$ASK_HTTP" = "200" ] || fail "Test question failed (HTTP $ASK_HTTP): $ASK_BODY - this is the live chat-model call, so this most likely means the chat provider/model/credentials aren't working."
ANSWER=$(echo "$ASK_BODY" | jq -r '.answer')
[ -n "$ANSWER" ] && [ "$ANSWER" != "null" ] || fail "The chat model returned an empty answer."
success "Chat model confirmed connected and functioning. Answer: \"$ANSWER\""

# ── Step 6: Finalization ──────────────────────────────────────────────────────
STEP_NAME="finalization"
step "Step 6/7: Finalization"
stop_app_if_running
APP_STARTED_BY_SCRIPT=false
success "Application stopped."

# ── Step 7 note: nothing to roll back on this path - we succeeded. ───────────
step "Step 7/7: Done"
cat <<SUMMARY

${C_GREEN}Initialization complete.${C_RESET}

Tenant id:        $TENANT_ID
Tenant API key:   $TENANT_API_KEY   ${C_YELLOW}(shown once - save it now)${C_RESET}
Knowledge base:   $KB_ID  ($KB_NAME, embedding_dim=$KB_DIM)

You can now start the application yourself:

  export DB_URL="$DB_URL"
  export DB_USERNAME="$DB_USERNAME"
  export DB_PASSWORD="<your DB password>"
  export REDIS_HOST="$REDIS_HOST"
  export REDIS_PORT="$REDIS_PORT"
  cd "$PROJECT_ROOT" && ./mvnw spring-boot:run -Dspring-boot.run.profiles=$PROFILE

The tenant, model configuration, and knowledge base created above are already there waiting for you.
SUMMARY
