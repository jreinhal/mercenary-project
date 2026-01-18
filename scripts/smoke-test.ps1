param(
    [string]$Port = "8080",
    [string]$Profile = "standard",
    [string]$AuthMode = "STANDARD",
    [string]$MongoUri = "mongodb://localhost:27017/mercenary_smoketest",
    [string]$AdminUser = "admin",
    [string]$AdminPassword = $env:SENTINEL_ADMIN_PASSWORD,
    [string]$Gradle,
    [switch]$KeepRunning
)

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
if (-not $Gradle) {
    $Gradle = Join-Path $repoRoot "gradlew.bat"
}

if (-not $AdminPassword) {
    Write-Error "Set SENTINEL_ADMIN_PASSWORD or pass -AdminPassword."
    exit 1
}

$reportsDir = Join-Path $repoRoot "build\\reports\\smoke"
New-Item -ItemType Directory -Force -Path $reportsDir | Out-Null

$env:APP_PROFILE = $Profile
$env:AUTH_MODE = $AuthMode
$env:APP_STANDARD_ALLOW_BASIC = "false"
$env:SENTINEL_BOOTSTRAP_ENABLED = "true"
$env:SENTINEL_ADMIN_PASSWORD = $AdminPassword
$env:MONGODB_URI = $MongoUri
$env:SERVER_PORT = $Port

$bootProc = Start-Process -FilePath $Gradle -ArgumentList "bootRun" `
    -WorkingDirectory $repoRoot `
    -RedirectStandardOutput "$reportsDir/bootrun.out.log" `
    -RedirectStandardError "$reportsDir/bootrun.err.log" `
    -PassThru -WindowStyle Hidden
$bootProc.Id | Out-File -FilePath "$reportsDir/bootrun.pid" -Encoding ascii

try {
    $ready = $false
    $healthUrl = "http://127.0.0.1:$Port/api/health"
    for ($i = 0; $i -lt 60; $i++) {
        try {
            $resp = Invoke-WebRequest -Uri $healthUrl -Method Get -TimeoutSec 2
            if ($resp.StatusCode -ge 200 -and $resp.StatusCode -lt 500) {
                $ready = $true
                break
            }
        } catch {
            Start-Sleep -Seconds 1
        }
    }

    if (-not $ready) {
        throw "App did not become ready on port $Port."
    }

    $results = [ordered]@{}
    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

    $token = ""
    try {
        $csrfResp = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/api/auth/csrf" `
            -WebSession $session -Method Get -TimeoutSec 10
        $csrfJson = $csrfResp.Content | ConvertFrom-Json
        $token = $csrfJson.token
        if ($token) { $results["csrf"] = "ok" } else { $results["csrf"] = "missing token" }
    } catch {
        $results["csrf"] = "error: $($_.Exception.Message)"
    }

    $headers = @{}
    if ($token) { $headers["X-XSRF-TOKEN"] = $token }

    try {
        $body = @{ username = $AdminUser; password = $AdminPassword } | ConvertTo-Json
        Invoke-WebRequest -Uri "http://127.0.0.1:$Port/api/auth/login" `
            -WebSession $session -Method Post -Headers $headers `
            -ContentType "application/json" -Body $body -TimeoutSec 10 | Out-Null
        $results["login"] = "ok"
    } catch {
        $results["login"] = "error: $($_.Exception.Message)"
    }

    try {
        $ctxResp = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/api/user/context" `
            -WebSession $session -Method Get -TimeoutSec 10
        $ctx = $ctxResp.Content | ConvertFrom-Json
        $results["user_context"] = "ok: $($ctx.displayName)"
    } catch {
        $results["user_context"] = "error: $($_.Exception.Message)"
    }

    try {
        $sectorsResp = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/api/config/sectors" `
            -WebSession $session -Method Get -TimeoutSec 10
        $sectors = $sectorsResp.Content | ConvertFrom-Json
        $results["sectors"] = "ok: $($sectors.Count)"
    } catch {
        $results["sectors"] = "error: $($_.Exception.Message)"
    }

    try {
        $telemetryResp = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/api/telemetry" `
            -WebSession $session -Method Get -TimeoutSec 10
        $telemetry = $telemetryResp.Content | ConvertFrom-Json
        $results["telemetry"] = "ok: dbOnline=$($telemetry.dbOnline) llmOnline=$($telemetry.llmOnline)"
    } catch {
        $results["telemetry"] = "error: $($_.Exception.Message)"
    }

    try {
        $query = [uri]::EscapeDataString("smoke test")
        $dept = [uri]::EscapeDataString("ENTERPRISE")
        $askResp = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/api/ask?q=$query&dept=$dept" `
            -WebSession $session -Method Get -TimeoutSec 20
        $results["ask"] = "ok: status $($askResp.StatusCode)"
    } catch {
        $results["ask"] = "error: $($_.Exception.Message)"
    }

    try {
        $uploadPath = "$reportsDir/smoke_upload.txt"
        "smoke upload" | Out-File -FilePath $uploadPath -Encoding ascii
        $form = @{ file = Get-Item $uploadPath; dept = "ENTERPRISE" }
        $uploadResp = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/api/ingest/file" `
            -WebSession $session -Method Post -Headers $headers -Form $form -TimeoutSec 60
        $results["upload"] = "ok: status $($uploadResp.StatusCode)"
    } catch {
        $results["upload"] = "error: $($_.Exception.Message)"
    }

    $resultsText = $results.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }
    $resultsText | Out-File -FilePath "$reportsDir/smoke-results.txt" -Encoding ascii
    $resultsText
} finally {
    if (-not $KeepRunning) {
        Stop-Process -Id $bootProc.Id -Force -ErrorAction SilentlyContinue
    }
}
