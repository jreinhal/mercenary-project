param(
    [string]$BaseUrl = "http://127.0.0.1:18081",
    [string]$Profile = "enterprise",
    [string]$AuthMode = "STANDARD",
    [string]$MongoUri = $env:MONGODB_URI,
    [string]$OllamaUrl = $env:OLLAMA_URL,
    [string]$AdminUser = "admin",
    [string]$AdminPass = $env:SENTINEL_ADMIN_PASSWORD,
    [int]$TimeoutSec = 600
)

$ErrorActionPreference = "Stop"

function Write-Log($message) {
    $ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    Write-Host "[$ts] $message"
}

function Get-PortFromUrl([string]$url) {
    try {
        return ([System.Uri]::new($url)).Port
    } catch {
        throw "Invalid BaseUrl: $url"
    }
}

function Wait-ForHealth([string]$baseUrl, [int]$timeoutSec = 180) {
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
    $AdminPass = "Test123!"
}

$port = Get-PortFromUrl $BaseUrl
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\\..")).Path
$artifactDir = Join-Path $PSScriptRoot "artifacts\\ops-signoff"
New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null

$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$bootOut = Join-Path $artifactDir "boot_${Profile}_${port}_${stamp}.log"
$bootErr = Join-Path $artifactDir "boot_${Profile}_${port}_${stamp}.log.err"
$resultPath = Join-Path $artifactDir "ops_signoff_${Profile}_${stamp}.json"

$checks = New-Object System.Collections.Generic.List[Object]
function Add-Check([string]$name, [bool]$pass, [object]$detail) {
    $checks.Add([PSCustomObject]@{
        name = $name
        pass = $pass
        detail = $detail
    }) | Out-Null
}

function Invoke-GetWithSession([string]$url, [Microsoft.PowerShell.Commands.WebRequestSession]$session) {
    try {
        $resp = Invoke-WebRequest -Uri $url -WebSession $session -UseBasicParsing -TimeoutSec 15
        return @{ status = [int]$resp.StatusCode; body = ($resp.Content ?? "") }
    } catch {
        if ($_.Exception.Response) {
            try {
                $status = [int]$_.Exception.Response.StatusCode
            } catch {
                $status = 0
            }
            return @{ status = $status; body = "" }
        }
        return @{ status = 0; body = $_.Exception.Message }
    }
}

$backend = $null
try {
    Write-Log "Starting backend for ops sign-off: profile=$Profile auth=$AuthMode port=$port"
    $env:APP_PROFILE = $Profile
    $env:APP_AUTH_MODE = $AuthMode
    $env:AUTH_MODE = $AuthMode
    $env:SERVER_PORT = "$port"
    $env:MONGODB_URI = $MongoUri
    $env:OLLAMA_URL = $OllamaUrl

    if (($AuthMode ?? "").Trim().ToUpperInvariant() -ne "OIDC") {
        if ([string]::IsNullOrWhiteSpace($env:OIDC_ISSUER)) {
            $env:OIDC_ISSUER = "http://127.0.0.1/oidc-placeholder"
        }
        if ([string]::IsNullOrWhiteSpace($env:OIDC_CLIENT_ID)) {
            $env:OIDC_CLIENT_ID = "sentinel-ui-suite"
        }
    }

    $env:SENTINEL_ADMIN_PASSWORD = $AdminPass
    if ([string]::IsNullOrWhiteSpace($env:SENTINEL_BOOTSTRAP_ADMIN_PASSWORD)) {
        $env:SENTINEL_BOOTSTRAP_ADMIN_PASSWORD = $AdminPass
    }
    if ([string]::IsNullOrWhiteSpace($env:COOKIE_SECURE)) {
        # Local ops sign-off runs over HTTP on loopback.
        $env:COOKIE_SECURE = "false"
    }

    # Deterministic local checks.
    $env:SENTINEL_INGEST_RESILIENCE_ENABLED = "false"
    $env:GUARDRAILS_LLM_ENABLED = "false"
    $env:GUARDRAILS_LLM_SCHEMA_ENABLED = "false"

    $backend = Start-Process -FilePath "pwsh" -ArgumentList "-NoProfile", "-Command", "cd '$repoRoot'; ./gradlew bootRun" -RedirectStandardOutput $bootOut -RedirectStandardError $bootErr -PassThru

    if (-not (Wait-ForHealth $BaseUrl 180)) {
        throw "Backend failed health check at $BaseUrl/api/health"
    }
    Add-Check "health_public" $true @{ status = 200 }

    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $statusUnauth = Invoke-GetWithSession "$BaseUrl/api/status" $session
    Add-Check "status_requires_auth" ($statusUnauth.status -eq 401) $statusUnauth

    $telemetryUnauth = Invoke-GetWithSession "$BaseUrl/api/telemetry" $session
    Add-Check "telemetry_requires_auth" ($telemetryUnauth.status -eq 401) $telemetryUnauth

    $adminHealthUnauth = Invoke-GetWithSession "$BaseUrl/api/admin/health" $session
    Add-Check "admin_health_requires_auth" (($adminHealthUnauth.status -eq 401) -or ($adminHealthUnauth.status -eq 403)) $adminHealthUnauth

    $csrfResp = Invoke-WebRequest -Uri "$BaseUrl/api/auth/csrf" -WebSession $session -UseBasicParsing -TimeoutSec 15
    $csrfToken = ""
    try {
        $csrfData = $csrfResp.Content | ConvertFrom-Json
        $csrfToken = [string]$csrfData.token
    } catch {
        $csrfToken = ""
    }

    $loginHeaders = @{ "Content-Type" = "application/json" }
    if (-not [string]::IsNullOrWhiteSpace($csrfToken)) {
        $loginHeaders["X-XSRF-TOKEN"] = $csrfToken
    }
    $loginBody = @{ username = $AdminUser; password = $AdminPass } | ConvertTo-Json
    $loginResp = Invoke-WebRequest -Uri "$BaseUrl/api/auth/login" -Method Post -WebSession $session -Headers $loginHeaders -Body $loginBody -UseBasicParsing -TimeoutSec 15
    Add-Check "login_standard_admin" ([int]$loginResp.StatusCode -eq 200) @{ status = [int]$loginResp.StatusCode }

    $statusAuth = Invoke-GetWithSession "$BaseUrl/api/status" $session
    Add-Check "status_authenticated" ($statusAuth.status -eq 200) $statusAuth

    $telemetryAuth = Invoke-GetWithSession "$BaseUrl/api/telemetry" $session
    Add-Check "telemetry_authenticated" ($telemetryAuth.status -eq 200) $telemetryAuth

    $userCtx = Invoke-GetWithSession "$BaseUrl/api/user/context" $session
    $ctxParsed = $null
    try { $ctxParsed = $userCtx.body | ConvertFrom-Json } catch { $ctxParsed = $null }
    $isAdmin = $false
    if ($ctxParsed -ne $null -and $ctxParsed.PSObject.Properties.Match("isAdmin").Count -gt 0) {
        $isAdmin = [bool]$ctxParsed.isAdmin
    }
    Add-Check "user_context_admin" (($userCtx.status -eq 200) -and $isAdmin) @{ status = $userCtx.status; isAdmin = $isAdmin }

    $adminHealthAuth = Invoke-GetWithSession "$BaseUrl/api/admin/health" $session
    Add-Check "admin_health_authenticated" ($adminHealthAuth.status -eq 200) $adminHealthAuth

    $connectorStatus = Invoke-GetWithSession "$BaseUrl/api/admin/connectors/status" $session
    Add-Check "connectors_status_authenticated" ($connectorStatus.status -eq 200) $connectorStatus

    $dashboardStatus = Invoke-GetWithSession "$BaseUrl/api/admin/dashboard" $session
    Add-Check "admin_dashboard_authenticated" ($dashboardStatus.status -eq 200) $dashboardStatus

    $results = [PSCustomObject]@{
        runAt = (Get-Date).ToString("o")
        baseUrl = $BaseUrl
        profile = $Profile
        authMode = $AuthMode
        checks = $checks
        passed = (@($checks | Where-Object { -not $_.pass }).Count -eq 0)
    }
    $results | ConvertTo-Json -Depth 8 | Set-Content -Path $resultPath -Encoding UTF8
    Write-Log "Ops sign-off results written: $resultPath"

    if (-not $results.passed) {
        throw "Ops sign-off has failing checks. See $resultPath"
    }
}
finally {
    if ($backend -ne $null -and -not $backend.HasExited) {
        Write-Log "Stopping backend PID=$($backend.Id)"
        cmd /c "taskkill /PID $($backend.Id) /T /F" | Out-Null
    }
    Write-Log "Backend logs: $bootOut"
    if (Test-Path $bootErr) {
        $errLen = (Get-Item $bootErr).Length
        if ($errLen -gt 0) {
            Write-Log "Backend stderr log not empty ($errLen bytes): $bootErr"
        }
    }
}
