#Requires -Version 7.1
<#
.SYNOPSIS
    SynapseMCP - first-run initialization wizard (Windows).

.DESCRIPTION
    Distinct from scripts/setup-environment.ps1 (referenced in plan.md/rag_plan.md as a narrower
    "install services + reset synapsemcp/synapsemcp_test to an empty clean slate" tool) - this
    script is a superset: it also starts the app and verifies real connectivity (PostgreSQL/Redis/
    Lucene/pgvector). Safe to re-run; every destructive step asks for confirmation first.

    Requires PowerShell 7.1+, psql/dropdb/createdb on PATH, redis-cli on PATH, Java 21+, and the
    committed mvnw.cmd.

.EXAMPLE
    ./scripts/init-environment.ps1
#>

$ErrorActionPreference = "Stop"

$ScriptDir    = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot  = Split-Path -Parent $ScriptDir
$AppLog       = Join-Path $ProjectRoot "init-environment-app.log"
$AppErrLog    = "${AppLog}.err"

# ── Output helpers ────────────────────────────────────────────────────────────
function Write-Info    { param($Message) Write-Host "[INFO]  $Message" -ForegroundColor Cyan }
function Write-Success { param($Message) Write-Host "[ OK ]  $Message" -ForegroundColor Green }
function Write-Warn    { param($Message) Write-Host "[WARN]  $Message" -ForegroundColor Yellow }
function Write-ErrorMsg{ param($Message) Write-Host "[FAIL]  $Message" -ForegroundColor Red }
function Write-Step    { param($Message) Write-Host "`n=== $Message ===" -ForegroundColor Cyan }

function Ask {
    param([string]$Prompt, [string]$Default = "")
    if ($Default -ne "") {
        $answer = Read-Host "$Prompt [$Default]"
        if ([string]::IsNullOrEmpty($answer)) { return $Default }
        return $answer
    }
    return Read-Host "$Prompt"
}
function Ask-Secret {
    param([string]$Prompt)
    # Verified empirically: Read-Host -AsSecureString aborts the entire pwsh process (not a normal,
    # catchable terminating error - try/catch does not stop it) whenever stdin isn't a real attached
    # console (piped/redirected input: automation, CI, some non-standard terminal hosts). Check for
    # that up front and fall back to plain, visible input instead of risking a silent process death.
    if ([Console]::IsInputRedirected) {
        return Read-Host "$Prompt (WARNING: input will be visible - no console available for masked input)"
    }
    $secure = Read-Host "$Prompt" -AsSecureString
    return [System.Net.NetworkCredential]::new("", $secure).Password
}
function Confirm-Action {
    param([string]$Prompt)
    $answer = Read-Host "$Prompt [y/N]"
    return $answer -match '^[Yy]$'
}

# ── State tracked for rollback (Step 5) ────────────────────────────────────────
$script:StepName       = "startup"
$script:AppProcess     = $null
$script:MainDbExisted  = $false
$script:TestDbExisted  = $false
$script:CleanupDone    = $false
$script:Failed         = $false

function Stop-AppIfRunning {
    if ($script:AppProcess -and -not $script:AppProcess.HasExited) {
        Write-Info "Stopping the application (pid $($script:AppProcess.Id))..."
        try {
            $script:AppProcess.Kill($true)
            $script:AppProcess.WaitForExit(10000) | Out-Null
        } catch {}
    }
}

function Invoke-Rollback {
    Write-Step "Rolling back"
    Stop-AppIfRunning

    if ($script:CleanupDone) {
        Restore-Database "synapsemcp" $script:MainDbExisted
        Restore-Database "synapsemcp_test" $script:TestDbExisted
    }
}

function Restore-Database {
    param([string]$DbName, [bool]$Existed)
    $env:PGPASSWORD = $DbPassword
    if (-not $Existed -and $script:CleanupDone) {
        Write-Info "$DbName did not exist before this run - dropping it again to match the prior (absent) state."
        dropdb -h $DbHost -p $DbPort -U $DbUsername --if-exists $DbName 2>$null
    }
}

function Fail {
    param([string]$Message)
    Write-ErrorMsg $Message
    $script:Failed = $true
    throw $Message
}

function Test-RequiredCommand {
    param([string]$Name, [string]$Remediation)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        Write-ErrorMsg "Required tool not found: $Name"
        Write-Host $Remediation
        return $false
    }
    return $true
}

try {
    # ══════════════════════════════════════════════════════════════════════
    Write-Step "SynapseMCP Initialization Wizard"
    # ══════════════════════════════════════════════════════════════════════
    Write-Host "This will (1) collect environment configuration, (2) validate dependencies,"
    Write-Host "(3) destructively reset the local + test databases/caches, and (4) start the app and"
    Write-Host "verify connectivity. Every destructive step asks for confirmation first."

    # ── Step 1: Environment Setup ────────────────────────────────────────────
    $script:StepName = "environment setup"
    Write-Step "Step 1/6: Environment Setup"
    Write-Host "Which profile is this for?"
    Write-Host "  1) local (default)"
    Write-Host "  2) dev"
    $profileChoice = Ask "Enter 1 or 2" "1"
    $AppProfile = if ($profileChoice -eq "2") { "dev" } else { "local" }
    Write-Info "Profile: $AppProfile"

    $DbUrl = Ask "DB_URL (JDBC base URL, no database name)" "jdbc:postgresql://localhost:5432"
    if ($DbUrl -notmatch '^jdbc:postgresql://([^:/]+):(\d+)') {
        Fail "Could not parse host/port out of DB_URL='$DbUrl' - expected jdbc:postgresql://<host>:<port>"
    }
    $DbHost = $Matches[1]
    $DbPort = $Matches[2]

    $DbUsername = Ask "DB_USERNAME (a role that can already authenticate to Postgres)" "$env:USERNAME"
    $DbPassword = Ask-Secret "DB_PASSWORD (leave empty for trust auth)"

    if (Confirm-Action "Use defaults for the remaining settings (timeouts, rate limits, Lucene directory)?") {
        $DbConnectionTimeout = "3000"
        $DbLockTimeout = "3s"
        $RedisHost = "localhost"
        $RedisPort = "6379"
        $RedisConnectTimeout = "1s"
        $RedisTimeout = "1s"
        $ProviderTimeoutSeconds = "30"
        $LuceneBaseDir = Join-Path $env:TEMP "synapsemcp\lucene-indexes"
        $RateLimitLimit = "5"
        $RateLimitWindowSeconds = "3600"
    } else {
        $DbConnectionTimeout = Ask "DB_CONNECTION_TIMEOUT (ms, HikariCP)" "3000"
        $DbLockTimeout = Ask "DB_LOCK_TIMEOUT (Postgres lock_timeout)" "3s"
        $RedisHost = Ask "REDIS_HOST" "localhost"
        $RedisPort = Ask "REDIS_PORT" "6379"
        $RedisConnectTimeout = Ask "REDIS_CONNECT_TIMEOUT" "1s"
        $RedisTimeout = Ask "REDIS_TIMEOUT" "1s"
        $ProviderTimeoutSeconds = Ask "SYNAPSEMCP_PROVIDER_TIMEOUT_SECONDS (chat/embedding HTTP client timeout)" "30"
        $LuceneBaseDir = Ask "SYNAPSEMCP_LUCENE_BASE_DIR" (Join-Path $env:TEMP "synapsemcp\lucene-indexes")
        $RateLimitLimit = Ask "SYNAPSEMCP_RATE_LIMIT_TENANT_CREATION_LIMIT" "5"
        $RateLimitWindowSeconds = Ask "SYNAPSEMCP_RATE_LIMIT_TENANT_CREATION_WINDOW_SECONDS" "3600"
    }

    $env:DB_URL = $DbUrl
    $env:DB_USERNAME = $DbUsername
    $env:DB_PASSWORD = $DbPassword
    $env:DB_CONNECTION_TIMEOUT = $DbConnectionTimeout
    $env:DB_LOCK_TIMEOUT = $DbLockTimeout
    $env:REDIS_HOST = $RedisHost
    $env:REDIS_PORT = $RedisPort
    $env:REDIS_CONNECT_TIMEOUT = $RedisConnectTimeout
    $env:REDIS_TIMEOUT = $RedisTimeout
    $env:SYNAPSEMCP_PROVIDER_TIMEOUT_SECONDS = $ProviderTimeoutSeconds
    $env:SYNAPSEMCP_LUCENE_BASE_DIR = $LuceneBaseDir
    $env:SYNAPSEMCP_RATE_LIMIT_TENANT_CREATION_LIMIT = $RateLimitLimit
    $env:SYNAPSEMCP_RATE_LIMIT_TENANT_CREATION_WINDOW_SECONDS = $RateLimitWindowSeconds
    Write-Success "Environment collected."

    # ── Step 2: Dependency Validation ────────────────────────────────────────
    $script:StepName = "dependency validation"
    Write-Step "Step 2/6: Dependency Validation"

    $depsOk = $true
    $depsOk = (Test-RequiredCommand "psql" "Install the PostgreSQL client tools and add them to PATH.") -and $depsOk
    $depsOk = (Test-RequiredCommand "redis-cli" "Install Redis (or the redis-cli client tools) and add it to PATH.") -and $depsOk
    $depsOk = (Test-RequiredCommand "java" "Install Java 21+ and add it to PATH.") -and $depsOk
    if (-not (Test-Path (Join-Path $ProjectRoot "mvnw.cmd"))) {
        Write-ErrorMsg "Maven wrapper not found at $ProjectRoot\mvnw.cmd"
        $depsOk = $false
    }
    if (-not $depsOk) { Fail "One or more required tools are missing - see messages above." }
    Write-Success "All required command-line tools are present."

    $javaVersionOutput = (& java -version 2>&1) -join "`n"
    if ($javaVersionOutput -match '"(\d+)') {
        $javaMajor = [int]$Matches[1]
        if ($javaMajor -lt 21) { Fail "Java 21+ is required; found version $javaMajor." }
        Write-Success "Java $javaMajor detected."
    } else {
        Fail "Could not determine the Java version from: $javaVersionOutput"
    }

    Write-Info "Checking Postgres connectivity at ${DbHost}:${DbPort} as '$DbUsername'..."
    $env:PGPASSWORD = $DbPassword
    $pgCheck = & psql -h $DbHost -p $DbPort -U $DbUsername -d postgres -tAc "SELECT 1" 2>&1
    if ($LASTEXITCODE -ne 0) {
        Fail "Could not connect to Postgres at ${DbHost}:${DbPort} as '$DbUsername'. Is the server running and are the credentials correct?"
    }
    Write-Success "Postgres is reachable."

    Write-Info "Checking the pgvector extension is installed on this Postgres server..."
    $pgvectorAvailable = & psql -h $DbHost -p $DbPort -U $DbUsername -d postgres -tAc "SELECT 1 FROM pg_available_extensions WHERE name = 'vector'" 2>$null
    if ($pgvectorAvailable.Trim() -ne "1") {
        Fail "pgvector is not installed on this Postgres server. Install it and retry - see https://github.com/pgvector/pgvector#installation"
    }
    Write-Success "pgvector is installed on the server."

    Write-Info "Checking Redis connectivity at ${RedisHost}:${RedisPort}..."
    $redisPing = & redis-cli -h $RedisHost -p $RedisPort PING 2>$null
    if ($redisPing -ne "PONG") { Fail "Could not reach Redis at ${RedisHost}:${RedisPort}. Is the server running?" }
    Write-Success "Redis is reachable."

    # This wizard always starts the app on port 8080 and verifies it there (Step 4) - it never
    # parameterizes the port. Checked here, before Step 3's destructive reset, rather than left to
    # surface as a confusing failure later: if a *different*, already-running process is already
    # listening on 8080, Step 4's health check would get a real "UP" response from that unrelated
    # process almost instantly and never notice its own freshly-started instance failed to bind at
    # all - silently verifying and seeding tenant/model-config/knowledge_base data against the wrong
    # target while reporting success, rather than against the databases this run just reset. (A
    # same-database conflict is already caught earlier and loudly, by dropdb refusing to drop a
    # database with active connections - this check is for the narrower, silent case where the
    # other process uses a different database but the same port.)
    Write-Info "Checking that port 8080 is free (the app always binds here)..."
    $portInUse = $false
    try {
        $tcpClient = New-Object System.Net.Sockets.TcpClient
        $connectTask = $tcpClient.ConnectAsync("localhost", 8080)
        if ($connectTask.Wait(500) -and $tcpClient.Connected) {
            $portInUse = $true
        }
    } catch {
        $portInUse = $false
    } finally {
        $tcpClient.Close()
    }
    if ($portInUse) {
        Fail "Port 8080 is already in use by another process. Stop whatever is listening there (e.g. your own already-running SynapseMCP instance) and re-run - otherwise this wizard cannot tell its own freshly-started instance apart from whatever already answers on that port."
    }
    Write-Success "Port 8080 is free."

    # ── Step 3: Cleanup ───────────────────────────────────────────────────────
    $script:StepName = "cleanup"
    Write-Step "Step 3/6: Cleanup"
    Write-Host "This will DESTRUCTIVELY reset (no backup is taken - this cannot be undone):"
    Write-Host "  - Postgres databases 'synapsemcp' and 'synapsemcp_test' (dropped and recreated empty)"
    Write-Host "  - pgvector data (it lives inside those databases, so this is the same action)"
    Write-Host "  - Redis database index 0 (flushed)"
    Write-Host "  - The Lucene index directory: $LuceneBaseDir (deleted)"
    if (-not (Confirm-Action "Proceed with this destructive reset?")) {
        Fail "Cancelled by user before any destructive action was taken."
    }

    foreach ($dbName in @("synapsemcp", "synapsemcp_test")) {
        $exists = & psql -h $DbHost -p $DbPort -U $DbUsername -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = '$dbName'" 2>$null
        if ($exists.Trim() -eq "1") {
            if ($dbName -eq "synapsemcp") { $script:MainDbExisted = $true } else { $script:TestDbExisted = $true }
        }
    }

    Write-Info "Dropping and recreating both databases..."
    & dropdb -h $DbHost -p $DbPort -U $DbUsername --if-exists synapsemcp
    if ($LASTEXITCODE -ne 0) { Fail "Failed to drop 'synapsemcp'." }
    & dropdb -h $DbHost -p $DbPort -U $DbUsername --if-exists synapsemcp_test
    if ($LASTEXITCODE -ne 0) { Fail "Failed to drop 'synapsemcp_test'." }
    & createdb -h $DbHost -p $DbPort -U $DbUsername -O $DbUsername synapsemcp
    if ($LASTEXITCODE -ne 0) { Fail "Failed to create 'synapsemcp'." }
    & createdb -h $DbHost -p $DbPort -U $DbUsername -O $DbUsername synapsemcp_test
    if ($LASTEXITCODE -ne 0) { Fail "Failed to create 'synapsemcp_test'." }
    $script:CleanupDone = $true

    # rag_plan.md Stage 0.5: no in-app bootstrap runner exists anymore - synapsemcp_test's vector
    # extension must already exist before the app/test-suite ever connects to it, so this script
    # creates it explicitly for both databases.
    & psql -h $DbHost -p $DbPort -U $DbUsername -d synapsemcp -c "CREATE EXTENSION IF NOT EXISTS vector;" | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail "Failed to enable pgvector on 'synapsemcp'." }
    & psql -h $DbHost -p $DbPort -U $DbUsername -d synapsemcp_test -c "CREATE EXTENSION IF NOT EXISTS vector;" | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail "Failed to enable pgvector on 'synapsemcp_test'." }
    Write-Success "Databases reset and pgvector enabled on both."

    Write-Info "Flushing Redis (database index 0)..."
    & redis-cli -h $RedisHost -p $RedisPort -n 0 FLUSHDB | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail "Failed to flush Redis." }
    Write-Success "Redis flushed."

    Write-Info "Removing the Lucene index directory..."
    if (Test-Path $LuceneBaseDir) { Remove-Item -Recurse -Force $LuceneBaseDir }
    New-Item -ItemType Directory -Path $LuceneBaseDir -Force | Out-Null
    try {
        $testFile = Join-Path $LuceneBaseDir ".write-test"
        [IO.File]::WriteAllText($testFile, "")
        # Verified empirically: PowerShell's filesystem provider treats a leading-dot filename as
        # "hidden" cross-platform (even on Unix, where the dot is just a naming convention, not a
        # real attribute) and refuses to remove it without -Force.
        Remove-Item -Force $testFile
    } catch {
        Fail "Lucene base directory '$LuceneBaseDir' is not writable."
    }
    Write-Success "Lucene index directory cleared and confirmed writable."

    # ── Step 4: Verification and Connectivity ────────────────────────────────
    $script:StepName = "starting the application"
    Write-Step "Step 4/6: Start the Application & Verify Connectivity"
    Write-Info "Starting the application on profile '$AppProfile' (this may take a little while on first boot)..."
    # Process.Start with UseShellExecute=false (what -NoNewWindow/-RedirectStandardOutput require)
    # cannot launch a .cmd directly - CreateProcess doesn't know how to run a batch file as an
    # executable image. Wrapping with cmd.exe /c is the standard workaround.
    $script:AppProcess = Start-Process -FilePath "cmd.exe" `
        -ArgumentList "/c", "mvnw.cmd -q spring-boot:run -Dspring-boot.run.profiles=$AppProfile" `
        -WorkingDirectory $ProjectRoot `
        -RedirectStandardOutput $AppLog -RedirectStandardError $AppErrLog `
        -PassThru -NoNewWindow

    Write-Info "Waiting for /actuator/health to report UP (up to 90s)..."
    $healthy = $false
    $healthTimeout = 90
    for ($i = 1; $i -le $healthTimeout; $i++) {
        if ($script:AppProcess.HasExited) { break }
        Write-Host -NoNewline "`r  Starting application... $([int]($i * 100 / $healthTimeout))%  "
        try {
            $health = Invoke-RestMethod -Uri "http://localhost:8080/actuator/health" -TimeoutSec 2
            if ($health.status -eq "UP") { $healthy = $true; break }
        } catch {}
        Start-Sleep -Seconds 1
    }
    Write-Host "`r$(' ' * 40)`r" -NoNewline
    if (-not $healthy) {
        Write-ErrorMsg "Application did not become healthy in time. Last 60 lines of $AppLog and ${AppErrLog}:"
        if (Test-Path $AppLog) { Get-Content $AppLog -Tail 60 }
        if (Test-Path $AppErrLog) { Get-Content $AppErrLog -Tail 60 }
        Fail "Application startup/health check failed."
    }
    Write-Success "Application is UP (confirms Postgres connectivity - the DataSource health indicator)."

    # No in-app bootstrap runner exists anymore - schema.sql (HNSW indexes/CHECK constraints/unique
    # index) is applied by Spring Boot's own deferred SQL init in local/dev/test, but there's no log
    # line to grep for it. Verify directly against the database instead - this wizard always targets
    # the "synapsemcp" database regardless of profile (local/dev share it, see Step 1).
    Write-Info "Verifying schema.sql's supplemental DDL against 'synapsemcp'..."
    $schemaCheckSql = @"
SELECT
  (SELECT count(*) FROM pg_indexes WHERE indexname IN
    ('idx_chunks_emb_384','idx_chunks_emb_512','idx_chunks_emb_768',
     'idx_chunks_emb_1024','idx_chunks_emb_1536','idx_chunks_emb_3072')) = 6
  AND (SELECT count(*) FROM pg_constraint WHERE conname IN
    ('chk_model_configs_chat_provider','chk_model_configs_embedding_provider')) = 2
  AND (SELECT count(*) FROM pg_indexes WHERE indexname = 'uq_knowledge_bases_tenant_name_ci') = 1
"@
    $schemaCheck = (& psql -h $DbHost -p $DbPort -U $DbUsername -d synapsemcp -tAc $schemaCheckSql 2>$null | Out-String).Trim()
    if ($schemaCheck -eq "t") {
        Write-Success "Schema + pgvector HNSW indexes verified (6 HNSW indexes, 2 CHECK constraints, 1 unique index)."
    } else {
        Fail "schema.sql's supplemental DDL (HNSW indexes/CHECK constraints/unique index) is missing or incomplete on 'synapsemcp' - schema may not be ready."
    }

    Write-Info "Re-checking Redis connectivity now that the app is running..."
    $redisPing2 = & redis-cli -h $RedisHost -p $RedisPort PING 2>$null
    if ($redisPing2 -ne "PONG") { Fail "Redis became unreachable after app startup." }
    Write-Success "Redis connectivity confirmed."
    Write-Info "Lucene needs no server of its own - its base directory was already confirmed writable above;"
    Write-Info "a real per-knowledge_base index directory will be created under it once a document is ingested."

    # ── Step 5: Finalization ──────────────────────────────────────────────────
    $script:StepName = "finalization"
    Write-Step "Step 5/6: Finalization"
    Stop-AppIfRunning
    Write-Success "Application stopped."

    # ── Step 6 note: nothing to roll back on this path - we succeeded. ───────
    Write-Step "Step 6/6: Done"
    Write-Host ""
    Write-Host "Initialization complete." -ForegroundColor Green
    Write-Host ""
    Write-Host "You can now start the application yourself:"
    Write-Host ""
    Write-Host "  `$env:DB_URL = `"$DbUrl`""
    Write-Host "  `$env:DB_USERNAME = `"$DbUsername`""
    Write-Host "  `$env:DB_PASSWORD = `"<your DB password>`""
    Write-Host "  `$env:REDIS_HOST = `"$RedisHost`""
    Write-Host "  `$env:REDIS_PORT = `"$RedisPort`""
    Write-Host "  cd `"$ProjectRoot`"; .\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=$AppProfile"
    Write-Host ""
    Write-Host "Once it's running, create a tenant, configure a model, and create a knowledge base via"
    Write-Host "the REST API or an MCP client - see scripts/instructions.md for a full walkthrough."
}
catch {
    Write-ErrorMsg "Initialization failed during: $($script:StepName)"
    Write-ErrorMsg $_.Exception.Message
    Invoke-Rollback
    Write-ErrorMsg "Rollback finished. Your environment has been restored as closely as possible to its"
    Write-ErrorMsg "prior state. Nothing from this failed run is left running or half-configured."
    exit 1
}
