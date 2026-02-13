param(
    [string]$BaseUrl = "http://127.0.0.1:18080",
    [string]$Profile = "dev",
    [string]$AuthMode = "DEV",
    [string]$MongoUri = $env:MONGODB_URI,
    [string]$OllamaUrl = $env:OLLAMA_URL,
    [string]$AdminUser = "admin",
    [string]$AdminPass = $env:SENTINEL_ADMIN_PASSWORD,
    [string]$RunLabel = $env:RUN_LABEL,
    [string]$SkipSeedDocs = $env:SKIP_SEED_DOCS,
    [int]$UiTimeoutSec = 900
)

$ErrorActionPreference = "Stop"

function Write-Log($message) {
    $ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    Write-Host "[$ts] $message"
}

function Get-PortFromUrl([string]$url) {
    try {
        $uri = [System.Uri]::new($url)
        return $uri.Port
    } catch {
        throw "Invalid BaseUrl: $url"
    }
}

function Wait-ForHealth([string]$baseUrl, [int]$timeoutSec = 120) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $resp = Invoke-WebRequest -Uri "$baseUrl/api/health" -UseBasicParsing -TimeoutSec 5
            if ($resp.StatusCode -eq 200) {
                return $true
            }
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    return $false
}

if ([string]::IsNullOrWhiteSpace($MongoUri)) {
    throw "MONGODB_URI is required (pass -MongoUri or set env MONGODB_URI)."
}

if ([string]::IsNullOrWhiteSpace($OllamaUrl)) {
    $OllamaUrl = "http://localhost:11434"
}

if ([string]::IsNullOrWhiteSpace($AdminPass)) {
    # Dev auth does not require login, but the UI runner can attempt it depending on profile.
    $AdminPass = "Test123!"
}

if ([string]::IsNullOrWhiteSpace($RunLabel)) {
    $RunLabel = "MASK"
}

$port = Get-PortFromUrl $BaseUrl
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\\..")).Path
$runnerDir = $PSScriptRoot
$logDir = Join-Path $runnerDir "artifacts\\ui-run"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$outLog = Join-Path $logDir "boot_$Profile`_$port.log"
$errLog = Join-Path $logDir "boot_$Profile`_$port.log.err"
$uiOutLog = Join-Path $logDir "ui_$Profile`_$port.log"
$uiErrLog = Join-Path $logDir "ui_$Profile`_$port.log.err"

Write-Log "Starting backend: profile=$Profile auth=$AuthMode port=$port"

$backend = $null
try {
    $env:APP_PROFILE = $Profile
    $env:APP_AUTH_MODE = $AuthMode
    $env:AUTH_MODE = $AuthMode
    $env:SERVER_PORT = "$port"
    $env:MONGODB_URI = $MongoUri
    $env:OLLAMA_URL = $OllamaUrl
    if (($AuthMode ?? "").Trim().ToUpperInvariant() -ne "OIDC") {
        # Enterprise profile wires OIDC properties at startup even in non-OIDC auth modes.
        # Use local placeholders when omitted so STANDARD/DEV UI suites can boot deterministically.
        if ([string]::IsNullOrWhiteSpace($env:OIDC_ISSUER)) {
            $env:OIDC_ISSUER = "http://127.0.0.1/oidc-placeholder"
        }
        if ([string]::IsNullOrWhiteSpace($env:OIDC_CLIENT_ID)) {
            $env:OIDC_CLIENT_ID = "sentinel-ui-suite"
        }
    }
    if (-not [string]::IsNullOrWhiteSpace($AdminPass)) {
        # Keep backend bootstrap/login credentials in sync with the UI runner login.
        $env:SENTINEL_ADMIN_PASSWORD = $AdminPass
        if ([string]::IsNullOrWhiteSpace($env:SENTINEL_BOOTSTRAP_ADMIN_PASSWORD)) {
            $env:SENTINEL_BOOTSTRAP_ADMIN_PASSWORD = $AdminPass
        }
    }
    $bootstrapEnabled = (($env:SENTINEL_BOOTSTRAP_ENABLED ?? "").Trim().ToLowerInvariant() -eq "true")
    if ($bootstrapEnabled -and [string]::IsNullOrWhiteSpace($env:SENTINEL_BOOTSTRAP_ADMIN_PASSWORD) -and [string]::IsNullOrWhiteSpace($env:SENTINEL_ADMIN_PASSWORD)) {
        throw "SENTINEL_BOOTSTRAP_ENABLED=true but no admin password was provided. Set SENTINEL_BOOTSTRAP_ADMIN_PASSWORD or SENTINEL_ADMIN_PASSWORD."
    }

    # Air-gap enforcement: ensure Spring AI doesn't attempt to wire external providers.
    $env:SPRING_AUTOCONFIGURE_EXCLUDE = "org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration,org.springframework.ai.autoconfigure.azure.openai.AzureOpenAiAutoConfiguration,org.springframework.ai.autoconfigure.vectorstore.mongo.MongoDBAtlasVectorStoreAutoConfiguration,org.springframework.ai.autoconfigure.vertexai.gemini.VertexAiGeminiAutoConfiguration,org.springframework.ai.autoconfigure.vertexai.palm2.VertexAiPalm2AutoConfiguration"
    $env:SPRING_AI_MODEL_CHAT = "ollama"
    $env:SPRING_AI_MODEL_EMBEDDING = "ollama"
    $env:SPRING_AI_OLLAMA_CHAT_ENABLED = "true"
    $env:SPRING_AI_OLLAMA_EMBEDDING_ENABLED = "true"
    $env:SPRING_AI_OPENAI_CHAT_ENABLED = "false"
    $env:SPRING_AI_OPENAI_EMBEDDING_ENABLED = "false"
    $env:SPRING_AI_OPENAI_IMAGE_ENABLED = "false"
    $env:SPRING_AI_OPENAI_AUDIO_TRANSCRIPTION_ENABLED = "false"
    $env:SPRING_AI_OPENAI_AUDIO_SPEECH_ENABLED = "false"
    $env:SPRING_AI_OPENAI_MODERATION_ENABLED = "false"
    $env:SPRING_AI_ANTHROPIC_CHAT_ENABLED = "false"
    $env:SPRING_AI_VERTEX_AI_GEMINI_CHAT_ENABLED = "false"
    $env:SPRING_AI_VERTEX_AI_EMBEDDING_TEXT_ENABLED = "false"
    $env:SPRING_AI_VERTEX_AI_EMBEDDING_MULTIMODAL_ENABLED = "false"

    # UI/UAT determinism: disable ingestion resilience fail-threshold gating.
    # Persisted failed-doc checkpoints can block all uploads before file-type validation executes.
    $env:SENTINEL_INGEST_RESILIENCE_ENABLED = "false"
    # UI/UAT determinism: avoid fail-closed LLM guardrail dependence in local/offline runs.
    # Pattern + semantic layers remain active.
    if ([string]::IsNullOrWhiteSpace($env:GUARDRAILS_LLM_ENABLED)) {
        $env:GUARDRAILS_LLM_ENABLED = "false"
    }
    if ([string]::IsNullOrWhiteSpace($env:GUARDRAILS_LLM_SCHEMA_ENABLED)) {
        $env:GUARDRAILS_LLM_SCHEMA_ENABLED = "false"
    }
    if ([string]::IsNullOrWhiteSpace($env:DEEP_ANALYSIS_MODE)) {
        # Full deep-graph checks are covered by dedicated graph test scripts.
        # Keep default UI UAT runs stable and bounded.
        $env:DEEP_ANALYSIS_MODE = "off"
    }

    $backend = Start-Process -FilePath "pwsh" -ArgumentList "-NoProfile", "-Command", "cd '$repoRoot'; ./gradlew bootRun" -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru

    if (-not (Wait-ForHealth $BaseUrl 180)) {
        throw "Backend failed health check at $BaseUrl/api/health (see $outLog)"
    }

    Write-Log "Running UI suite in Edge via Playwright against $BaseUrl"
    Push-Location $runnerDir
    try {
        $env:BASE_URL = $BaseUrl
        $env:ADMIN_USER = $AdminUser
        $env:ADMIN_PASS = $AdminPass
        $env:RUN_LABEL = $RunLabel
        if (-not [string]::IsNullOrWhiteSpace($SkipSeedDocs)) {
            $env:SKIP_SEED_DOCS = $SkipSeedDocs
        }

        $uiProc = Start-Process -FilePath "node" -ArgumentList ".\\run-ui-tests.js" -WorkingDirectory $runnerDir -RedirectStandardOutput $uiOutLog -RedirectStandardError $uiErrLog -PassThru
        $startedAt = Get-Date
        while (-not $uiProc.HasExited) {
            $elapsed = ((Get-Date) - $startedAt).TotalSeconds
            if ($elapsed -ge $UiTimeoutSec) {
                Write-Log "UI suite timeout reached (${UiTimeoutSec}s), terminating PID=$($uiProc.Id)"
                cmd /c "taskkill /PID $($uiProc.Id) /T /F" | Out-Null
                throw "UI suite timed out after ${UiTimeoutSec}s (see $uiOutLog and $uiErrLog)."
            }
            Start-Sleep -Seconds 1
            $uiProc.Refresh()
        }
        if ($uiProc.ExitCode -ne 0) {
            throw "UI suite failed (exit=$($uiProc.ExitCode)). See $uiOutLog and $uiErrLog."
        }
    } finally {
        Pop-Location
    }
} finally {
    if ($backend -ne $null -and -not $backend.HasExited) {
        Write-Log "Stopping backend PID=$($backend.Id)"
        cmd /c "taskkill /PID $($backend.Id) /T /F" | Out-Null
    }
    Write-Log "Backend logs: $outLog"
    if (Test-Path $errLog) {
        $errLen = (Get-Item $errLog).Length
        if ($errLen -gt 0) {
            Write-Log "Backend stderr log not empty ($errLen bytes): $errLog"
        }
    }
    if (Test-Path $uiErrLog) {
        $uiErrLen = (Get-Item $uiErrLog).Length
        if ($uiErrLen -gt 0) {
            Write-Log "UI stderr log not empty ($uiErrLen bytes): $uiErrLog"
        }
    }
}

