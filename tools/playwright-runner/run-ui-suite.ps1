param(
    [string]$BaseUrl = "http://127.0.0.1:18080",
    [string]$Profile = "dev",
    [string]$AuthMode = "DEV",
    [string]$MongoUri = $env:MONGODB_URI,
    [string]$OllamaUrl = $env:OLLAMA_URL,
    [string]$AdminUser = "admin",
    [string]$AdminPass = $env:SENTINEL_ADMIN_PASSWORD,
    [string]$RunLabel = $env:RUN_LABEL,
    [string]$SkipSeedDocs = $env:SKIP_SEED_DOCS
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

Write-Log "Starting backend: profile=$Profile auth=$AuthMode port=$port"

$backend = $null
try {
    $env:APP_PROFILE = $Profile
    $env:APP_AUTH_MODE = $AuthMode
    $env:AUTH_MODE = $AuthMode
    $env:SERVER_PORT = "$port"
    $env:MONGODB_URI = $MongoUri
    $env:OLLAMA_URL = $OllamaUrl

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

        node .\\run-ui-tests.js
        if ($LASTEXITCODE -ne 0) {
            throw "UI suite failed (exit=$LASTEXITCODE)."
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
}

