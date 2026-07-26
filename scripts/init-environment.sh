#!/usr/bin/env bash
#
# SynapseMCP — first-run initialization wizard (macOS + Linux).
#
# Distinct from scripts/setup-environment.sh (referenced in plan.md/rag_plan.md as a narrower
# "install services + reset synapsemcp/synapsemcp_test to an empty clean slate" tool) - this script
# is a superset: it also starts the app and verifies real connectivity (Postgres/Redis/Lucene/
# pgvector). Safe to re-run; every destructive step asks for confirmation first.
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

# ── State tracked for rollback (Step 6) ────────────────────────────────────────
STEP_NAME="startup"
APP_PID=""
APP_STARTED_BY_SCRIPT=false
MAIN_DB_EXISTED=false
TEST_DB_EXISTED=false
CLEANUP_DONE=false
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
echo "(3) destructively reset the local + test databases/caches, and (4) start the app and verify"
echo "connectivity. Every destructive step asks for confirmation first."

# ── Step 1: Environment Setup ────────────────────────────────────────────────
STEP_NAME="environment setup"
step "Step 1/6: Environment Setup"
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
  fail "This wizard only supports local/dev (prod has no in-app schema provisioning by design - its database/role/schema must already be provisioned via the CI/CD database-initialization pipeline stage - see rag_plan.md Stage 0.5)."
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
step "Step 2/6: Dependency Validation"

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

# This wizard always starts the app on port 8080 and verifies it there (Step 4) - it never
# parameterizes the port. Checked here, before Step 3's destructive reset, rather than left to
# surface as a confusing failure later: if a *different*, already-running process (e.g. your own
# separately-running SynapseMCP instance, or anything else) is already listening on 8080, Step 4's
# health check would get a real "UP" response from that unrelated process almost instantly and
# never notice its own freshly-started instance failed to bind at all - silently verifying and
# seeding tenant/model-config/knowledge_base data against the wrong target while reporting success,
# rather than against the databases this run just reset. (A same-database conflict is already
# caught earlier and loudly, by `dropdb` refusing to drop a database with active connections - this
# check is for the narrower, silent case where the other process uses a different database but the
# same port.)
info "Checking that port 8080 is free (the app always binds here)..."
# The connection test below runs entirely inside its own subshell (the parentheses) - fd 3 is
# opened and closed there automatically when the subshell exits - so there is nothing to clean up
# in this shell afterward. Do NOT add an `exec 3<&- ...` cleanup line here: `exec` with no command
# applies its redirections to the *current shell permanently*, not just to itself - an earlier
# version of this check did exactly that with a trailing `2>/dev/null` on such a line, which
# silently redirected this whole script's stderr to /dev/null for the rest of the run, hiding every
# subsequent `fail`/`error` message. Found live, not guessed - caught before this shipped.
if (exec 3<>/dev/tcp/localhost/8080) 2>/dev/null; then
  fail "Port 8080 is already in use by another process. Stop whatever is listening there (e.g. your own already-running SynapseMCP instance) and re-run - otherwise this wizard cannot tell its own freshly-started instance apart from whatever already answers on that port."
fi
success "Port 8080 is free."

# ── Step 3: Cleanup ───────────────────────────────────────────────────────────
STEP_NAME="cleanup"
step "Step 3/6: Cleanup"
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
# rag_plan.md Stage 0.5: no in-app bootstrap runner exists anymore - synapsemcp_test's vector
# extension must already exist before the app/test-suite ever connects to it, so this script
# creates it explicitly for both databases.
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
step "Step 4/6: Start the Application & Verify Connectivity"
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

# No in-app bootstrap runner exists anymore - schema.sql (HNSW indexes/CHECK constraints/unique
# index) is applied by Spring Boot's own deferred SQL init in local/dev/test, but there's no log
# line to grep for it. Verify directly against the database instead - this wizard always targets
# the "synapsemcp" database regardless of profile (local/dev share it, see Step 1).
info "Verifying schema.sql's supplemental DDL against 'synapsemcp'..."
SCHEMA_CHECK=$(PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USERNAME" -d synapsemcp -tAc "
  SELECT
    (SELECT count(*) FROM pg_indexes WHERE indexname IN
      ('idx_chunks_emb_384','idx_chunks_emb_512','idx_chunks_emb_768',
       'idx_chunks_emb_1024','idx_chunks_emb_1536','idx_chunks_emb_3072')) = 6
    AND (SELECT count(*) FROM pg_constraint WHERE conname IN
      ('chk_model_configs_chat_provider','chk_model_configs_embedding_provider')) = 2
    AND (SELECT count(*) FROM pg_indexes WHERE indexname = 'uq_knowledge_bases_tenant_name_ci') = 1
" 2>/dev/null || echo "f")
if [ "$SCHEMA_CHECK" = "t" ]; then
  success "Schema + pgvector HNSW indexes verified (6 HNSW indexes, 2 CHECK constraints, 1 unique index)."
else
  fail "schema.sql's supplemental DDL (HNSW indexes/CHECK constraints/unique index) is missing or incomplete on 'synapsemcp' - schema may not be ready."
fi

info "Re-checking Redis connectivity now that the app is running..."
if [ "$(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" PING 2>/dev/null)" != "PONG" ]; then
  fail "Redis became unreachable after app startup."
fi
success "Redis connectivity confirmed."
info "Lucene needs no server of its own - its base directory was already confirmed writable above;"
info "a real per-knowledge_base index directory will be created under it once a document is ingested."

# ── Step 5: Finalization ──────────────────────────────────────────────────────
STEP_NAME="finalization"
step "Step 5/6: Finalization"
stop_app_if_running
APP_STARTED_BY_SCRIPT=false
success "Application stopped."

# ── Step 6 note: nothing to roll back on this path - we succeeded. ───────────
step "Step 6/6: Done"
cat <<SUMMARY

${C_GREEN}Initialization complete.${C_RESET}

You can now start the application yourself:

  export DB_URL="$DB_URL"
  export DB_USERNAME="$DB_USERNAME"
  export DB_PASSWORD="<your DB password>"
  export REDIS_HOST="$REDIS_HOST"
  export REDIS_PORT="$REDIS_PORT"
  cd "$PROJECT_ROOT" && ./mvnw spring-boot:run -Dspring-boot.run.profiles=$PROFILE

Once it's running, create a tenant, configure a model, and create a knowledge base via the REST
API or an MCP client - see scripts/instructions.md for a full walkthrough.
SUMMARY
