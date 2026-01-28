param(
    [string[]]$Profiles = @("dev", "standard", "enterprise", "govcloud"),
    [string]$MongoUri = $env:MONGODB_URI,
    [string]$AdminPassword = $env:SENTINEL_ADMIN_PASSWORD,
    [string]$OllamaUrl = $env:OLLAMA_URL,
    [string]$OutputDir = "build\\e2e-results",
    [int]$RequestTimeoutSec = 120
)

$ErrorActionPreference = "Stop"

$tls12 = [System.Net.SecurityProtocolType]::Tls12
if ([enum]::GetNames([System.Net.SecurityProtocolType]) -contains "Tls13") {
    $tls12 = $tls12 -bor [System.Net.SecurityProtocolType]::Tls13
}
[System.Net.ServicePointManager]::SecurityProtocol = $tls12

$script:SupportsSkipCert = (Get-Command Invoke-WebRequest).Parameters.ContainsKey("SkipCertificateCheck")
$script:SupportsSslProtocol = (Get-Command Invoke-WebRequest).Parameters.ContainsKey("SslProtocol")
$script:SupportsForm = (Get-Command Invoke-WebRequest).Parameters.ContainsKey("Form")
$script:SupportsSkipHttpErrorCheck = (Get-Command Invoke-WebRequest).Parameters.ContainsKey("SkipHttpErrorCheck")
if (-not $script:SupportsSkipCert) {
    # PowerShell 5.1: allow self-signed HTTPS for govcloud test runs
    [System.Net.ServicePointManager]::ServerCertificateValidationCallback = { $true }
}

if ([string]::IsNullOrWhiteSpace($OllamaUrl)) {
    $OllamaUrl = "http://localhost:11434"
}

if ([string]::IsNullOrWhiteSpace($MongoUri)) {
    throw "MONGODB_URI is required. Set env MONGODB_URI or pass -MongoUri."
}

if ([string]::IsNullOrWhiteSpace($AdminPassword)) {
    throw "SENTINEL_ADMIN_PASSWORD is required for STANDARD auth profiles. Set env SENTINEL_ADMIN_PASSWORD or pass -AdminPassword."
}

function Ensure-Ollama($ollamaUrl) {
    $ollamaCmd = Get-Command ollama -ErrorAction SilentlyContinue
    if ($null -eq $ollamaCmd) {
        throw "ollama CLI not found. Install Ollama and ensure it is in PATH."
    }
    $tagsUrl = "$ollamaUrl/api/tags"
    try {
        Invoke-WebRequest -Uri $tagsUrl -UseBasicParsing -TimeoutSec 5 | Out-Null
        return $true
    } catch {
        Write-Log "Ollama not reachable. Starting 'ollama serve'..."
        Start-Process -FilePath "ollama" -ArgumentList "serve" | Out-Null
        $deadline = (Get-Date).AddSeconds(30)
        while ((Get-Date) -lt $deadline) {
            try {
                Invoke-WebRequest -Uri $tagsUrl -UseBasicParsing -TimeoutSec 5 | Out-Null
                return $true
            } catch {
                Start-Sleep -Seconds 2
            }
        }
    }
    return $false
}

if (-not (Ensure-Ollama $OllamaUrl)) {
    throw "Ollama is not available at $OllamaUrl"
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$sectors = @("GOVERNMENT", "MEDICAL", "FINANCE", "ACADEMIC", "ENTERPRISE")
$sectorFiles = @{
    GOVERNMENT = "src/test/resources/test_docs/defense_cybersecurity.txt"
    MEDICAL    = "src/test/resources/test_docs/medical_clinical_trial.txt"
    FINANCE    = "src/test/resources/test_docs/finance_earnings_q4.txt"
    ACADEMIC   = "src/test/resources/test_docs/legal_ip_brief.txt"
    ENTERPRISE = "src/test/resources/test_docs/enterprise_transformation.txt"
}

$cacSubjectDn = "CN=E2E_TEST, OU=E2E, O=Mercenary, L=Local, S=NA, C=US"

function Write-Log($message) {
    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    Write-Host "[$timestamp] $message"
}

$netHttpLoaded = $false
try {
    Add-Type -AssemblyName System.Net.Http -ErrorAction Stop
    $netHttpLoaded = $true
} catch {
    Write-Log "WARNING: Failed to load System.Net.Http; multipart ingest may fail."
}

function Invoke-Request($params, $skipCert) {
    if ($skipCert -and $script:SupportsSkipCert) {
        $params["SkipCertificateCheck"] = $true
    }
    if ($skipCert -and $script:SupportsSslProtocol) {
        $params["SslProtocol"] = "Tls12"
    }
    return Invoke-WebRequest @params
}

function Invoke-RequestAllowError($params, $skipCert) {
    if ($skipCert -and $script:SupportsSkipCert) {
        $params["SkipCertificateCheck"] = $true
    }
    if ($skipCert -and $script:SupportsSslProtocol) {
        $params["SslProtocol"] = "Tls12"
    }
    if ($script:SupportsSkipHttpErrorCheck) {
        $params["SkipHttpErrorCheck"] = $true
    }
    try {
        return Invoke-WebRequest @params
    } catch {
        $response = $_.Exception.Response
        if ($null -ne $response) {
            try {
                $reader = New-Object System.IO.StreamReader($response.GetResponseStream())
                $body = $reader.ReadToEnd()
            } finally {
                if ($null -ne $reader) { $reader.Dispose() }
            }
            return [pscustomobject]@{
                StatusCode = [int]$response.StatusCode
                Content = $body
            }
        }
        throw
    }
}

function Ensure-CacTestUser($subjectDn) {
    $mongoshCmd = (Get-Command mongosh -ErrorAction SilentlyContinue)
    if ($null -eq $mongoshCmd) {
        Write-Log "mongosh not found; skipping CAC test user seed."
        return $false
    }
    $js = @"
db.users.updateOne(
  { externalId: '$subjectDn' },
  {
    `$set: {
      username: 'cac_e2e',
      displayName: 'CAC E2E',
      authProvider: 'CAC',
      roles: ['ADMIN'],
      clearance: 'TOP_SECRET',
      allowedSectors: ['GOVERNMENT','MEDICAL','FINANCE','ACADEMIC','ENTERPRISE'],
      active: true,
      pendingApproval: false
    },
    `$setOnInsert: { createdAt: new Date() }
  },
  { upsert: true }
)
"@
    & $mongoshCmd.Source "$MongoUri" --quiet --eval $js | Out-Null
    return $true
}

function Start-App($profile, $port, $extraEnv, $logPath) {
    $envAssignments = @(
        "`$env:APP_PROFILE='$profile'",
        "`$env:APP_AUTH_MODE='$($extraEnv.APP_AUTH_MODE)'",
        "`$env:AUTH_MODE='$($extraEnv.AUTH_MODE)'",
        "`$env:MONGODB_URI='$MongoUri'",
        "`$env:OLLAMA_URL='$OllamaUrl'",
        "`$env:SPRING_AUTOCONFIGURE_EXCLUDE='org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration,org.springframework.ai.autoconfigure.azure.openai.AzureOpenAiAutoConfiguration,org.springframework.ai.autoconfigure.vectorstore.mongo.MongoDBAtlasVectorStoreAutoConfiguration,org.springframework.ai.autoconfigure.vertexai.gemini.VertexAiGeminiAutoConfiguration,org.springframework.ai.autoconfigure.vertexai.palm2.VertexAiPalm2AutoConfiguration'",
        "`$env:SPRING_AI_MODEL_CHAT='ollama'",
        "`$env:SPRING_AI_MODEL_EMBEDDING='ollama'",
        "`$env:SPRING_AI_OLLAMA_CHAT_ENABLED='true'",
        "`$env:SPRING_AI_OLLAMA_EMBEDDING_ENABLED='true'",
        "`$env:SPRING_AI_OPENAI_CHAT_ENABLED='false'",
        "`$env:SPRING_AI_OPENAI_EMBEDDING_ENABLED='false'",
        "`$env:SPRING_AI_OPENAI_IMAGE_ENABLED='false'",
        "`$env:SPRING_AI_OPENAI_AUDIO_TRANSCRIPTION_ENABLED='false'",
        "`$env:SPRING_AI_OPENAI_AUDIO_SPEECH_ENABLED='false'",
        "`$env:SPRING_AI_OPENAI_MODERATION_ENABLED='false'",
        "`$env:SPRING_AI_ANTHROPIC_CHAT_ENABLED='false'",
        "`$env:SPRING_AI_VERTEX_AI_GEMINI_CHAT_ENABLED='false'",
        "`$env:SPRING_AI_VERTEX_AI_EMBEDDING_TEXT_ENABLED='false'",
        "`$env:SPRING_AI_VERTEX_AI_EMBEDDING_MULTIMODAL_ENABLED='false'"
    )
    foreach ($kvp in $extraEnv.GetEnumerator()) {
        if ($kvp.Key -in @("AUTH_MODE")) { continue }
        $envAssignments += "`$env:$($kvp.Key)='$($kvp.Value)'"
    }
    $envPrefix = ($envAssignments -join "; ")
    $cmd = "$envPrefix; cd '$PWD'; ./gradlew bootRun"
    Write-Log "Starting profile '$profile' on port $port"
    $stderrPath = "$logPath.err"
    return Start-Process -FilePath "powershell" -ArgumentList "-NoProfile -Command $cmd" -RedirectStandardOutput $logPath -RedirectStandardError $stderrPath -PassThru
}

function Stop-App($process) {
    if ($null -ne $process -and -not $process.HasExited) {
        Write-Log "Stopping app (PID $($process.Id))"
        cmd /c "taskkill /PID $($process.Id) /T /F" | Out-Null
    }
}

function Wait-ForHealth($baseUrl, $timeoutSec, $skipCert, $port) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    $lastError = $null
    while ((Get-Date) -lt $deadline) {
        try {
            $resp = Invoke-Request @{
                Uri = "$baseUrl/api/health"
                UseBasicParsing = $true
                TimeoutSec = 5
            } $skipCert
            if ($resp.StatusCode -eq 200) {
                return $true
            }
        } catch {
            $lastError = $_
            if (-not $skipCert) {
                if (Test-NetConnection -ComputerName "127.0.0.1" -Port $port -InformationLevel Quiet -WarningAction SilentlyContinue) {
                    return $true
                }
            }
            Start-Sleep -Seconds 2
        }
    }
    if ($lastError -ne $null) {
        Write-Log ("Health check error: " + $lastError.Exception.Message)
    }
    return $false
}

function Get-CsrfToken($baseUrl, $skipCert) {
    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $csrfResp = Invoke-Request @{
        Uri = "$baseUrl/api/auth/csrf"
        WebSession = $session
        UseBasicParsing = $true
        TimeoutSec = $RequestTimeoutSec
    } $skipCert
    $json = $csrfResp.Content | ConvertFrom-Json
    return @{ Session = $session; Token = $json.token }
}

function Login-Standard($baseUrl, $adminPassword, $skipCert) {
    $csrf = Get-CsrfToken $baseUrl $skipCert
    $body = @{ username = "admin"; password = $adminPassword } | ConvertTo-Json
    Invoke-Request @{
        Uri = "$baseUrl/api/auth/login"
        Method = "Post"
        WebSession = $csrf.Session
        Headers = @{ "X-XSRF-TOKEN" = $csrf.Token }
        ContentType = "application/json"
        Body = $body
        UseBasicParsing = $true
        TimeoutSec = $RequestTimeoutSec
    } $skipCert | Out-Null
    return $csrf
}

function Get-CsrfTokenGovcloud($baseUrl, $skipCert, $authHeaders) {
    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    try {
        Invoke-Request @{
            Uri = "$baseUrl/api/user/context"
            WebSession = $session
            Headers = $authHeaders
            UseBasicParsing = $true
            TimeoutSec = $RequestTimeoutSec
        } $skipCert | Out-Null
    } catch {
        # Ignore; we'll fallback to a manual CSRF token
    }
    $cookies = $session.Cookies.GetCookies([Uri]$baseUrl)
    $tokenCookie = $cookies["XSRF-TOKEN"]
    if ($null -eq $tokenCookie -or [string]::IsNullOrWhiteSpace($tokenCookie.Value)) {
        $tokenValue = [guid]::NewGuid().ToString("N")
        $cookie = New-Object System.Net.Cookie("XSRF-TOKEN", $tokenValue, "/", ([Uri]$baseUrl).Host)
        $session.Cookies.Add($cookie)
        return @{ Session = $session; Token = $tokenValue }
    }
    return @{ Session = $session; Token = $tokenCookie.Value }
}

function Ingest-File($baseUrl, $sector, $filePath, $csrf, $authHeaders, $skipCert) {
    $headers = @{}
    $session = $null
    if ($csrf -ne $null) {
        $session = $csrf.Session
        $headers["X-XSRF-TOKEN"] = $csrf.Token
        $headers["X-CSRF-TOKEN"] = $csrf.Token
        if ($skipCert) {
            $headers["Cookie"] = "XSRF-TOKEN=$($csrf.Token)"
        }
    }
    if ($authHeaders -ne $null) {
        foreach ($kvp in $authHeaders.GetEnumerator()) {
            $headers[$kvp.Key] = $kvp.Value
        }
    }

    if ($script:SupportsForm) {
        $form = @{
            file = Get-Item $filePath
            dept = $sector
        }
        return Invoke-RequestAllowError @{
            Uri = "$baseUrl/api/ingest/file"
            Method = "Post"
            Form = $form
            Headers = $headers
            WebSession = $session
            UseBasicParsing = $true
            TimeoutSec = $RequestTimeoutSec
        } $skipCert
    }

    $handler = New-Object System.Net.Http.HttpClientHandler
    if ($skipCert) {
        $handler.ServerCertificateCustomValidationCallback = { $true }
        $handler.SslProtocols = [System.Security.Authentication.SslProtocols]::Tls12
    }
    if ($csrf -ne $null -and $csrf.Session -ne $null -and $csrf.Session.Cookies -ne $null) {
        $handler.CookieContainer = $csrf.Session.Cookies
    } else {
        $handler.CookieContainer = New-Object System.Net.CookieContainer
    }
    $handler.UseCookies = $true
    $client = New-Object System.Net.Http.HttpClient($handler)
    $client.Timeout = [TimeSpan]::FromSeconds($RequestTimeoutSec)
    $fileStream = [System.IO.File]::OpenRead($filePath)
    $multipart = New-Object System.Net.Http.MultipartFormDataContent
    $fileContent = New-Object System.Net.Http.StreamContent($fileStream)
    $multipart.Add($fileContent, "file", [System.IO.Path]::GetFileName($filePath))
    $multipart.Add([System.Net.Http.StringContent]::new($sector), "dept")
    $request = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, "$baseUrl/api/ingest/file")
    $request.Content = $multipart
    foreach ($kvp in $headers.GetEnumerator()) {
        $request.Headers.Add($kvp.Key, $kvp.Value)
    }
    if ($csrf -ne $null) {
        $request.Headers.TryAddWithoutValidation("Cookie", "XSRF-TOKEN=$($csrf.Token)") | Out-Null
    }
    try {
        $response = $client.SendAsync($request).Result
        $statusCode = if ($null -ne $response) { [int]$response.StatusCode } else { -1 }
        $content = ""
        if ($null -ne $response -and $null -ne $response.Content) {
            $content = $response.Content.ReadAsStringAsync().Result
        }
        return [pscustomobject]@{
            StatusCode = $statusCode
            Content = $content
        }
    } finally {
        if ($null -ne $fileStream) { $fileStream.Dispose() }
        if ($null -ne $client) { $client.Dispose() }
    }
}

function Ask-Query($baseUrl, $sector, $fileName, $csrf, $authHeaders, $skipCert) {
    $query = [Uri]::EscapeDataString("Summarize the key points from $fileName")
    $fileParam = [Uri]::EscapeDataString($fileName)
    $headers = @{}
    $session = $null
    if ($csrf -ne $null) {
        $session = $csrf.Session
        $headers["X-XSRF-TOKEN"] = $csrf.Token
        $headers["X-CSRF-TOKEN"] = $csrf.Token
    }
    if ($authHeaders -ne $null) {
        foreach ($kvp in $authHeaders.GetEnumerator()) {
            $headers[$kvp.Key] = $kvp.Value
        }
    }
    return Invoke-Request @{
        Uri = "$baseUrl/api/ask?q=$query&dept=$sector&files=$fileParam"
        WebSession = $session
        Headers = $headers
        UseBasicParsing = $true
        TimeoutSec = $RequestTimeoutSec
    } $skipCert
}

function Ask-Enhanced($baseUrl, $sector, $fileName, $csrf, $authHeaders, $skipCert) {
    $query = [Uri]::EscapeDataString("List the primary entities and metrics mentioned in $fileName")
    $fileParam = [Uri]::EscapeDataString($fileName)
    $headers = @{}
    $session = $null
    if ($csrf -ne $null) {
        $session = $csrf.Session
        $headers["X-XSRF-TOKEN"] = $csrf.Token
        $headers["X-CSRF-TOKEN"] = $csrf.Token
    }
    if ($authHeaders -ne $null) {
        foreach ($kvp in $authHeaders.GetEnumerator()) {
            $headers[$kvp.Key] = $kvp.Value
        }
    }
    return Invoke-Request @{
        Uri = "$baseUrl/api/ask/enhanced?q=$query&dept=$sector&files=$fileParam"
        WebSession = $session
        Headers = $headers
        UseBasicParsing = $true
        TimeoutSec = $RequestTimeoutSec
    } $skipCert
}

function Inspect-Doc($baseUrl, $sector, $fileName, $csrf, $authHeaders, $skipCert) {
    $fileParam = [Uri]::EscapeDataString($fileName)
    $headers = @{}
    $session = $null
    if ($csrf -ne $null) {
        $session = $csrf.Session
        $headers["X-XSRF-TOKEN"] = $csrf.Token
    }
    if ($authHeaders -ne $null) {
        foreach ($kvp in $authHeaders.GetEnumerator()) {
            $headers[$kvp.Key] = $kvp.Value
        }
    }
    return Invoke-Request @{
        Uri = "$baseUrl/api/inspect?fileName=$fileParam&dept=$sector"
        WebSession = $session
        Headers = $headers
        UseBasicParsing = $true
        TimeoutSec = $RequestTimeoutSec
    } $skipCert
}

$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$results = @()

foreach ($profile in $Profiles) {
    $port = 8080
    $baseUrl = "http://127.0.0.1:$port"
    $skipCert = $false
    $extraEnv = @{}

    if ($profile -eq "dev") {
        $extraEnv["AUTH_MODE"] = "DEV"
        $extraEnv["APP_AUTH_MODE"] = "DEV"
        $extraEnv["COOKIE_SECURE"] = "false"
    } elseif ($profile -eq "govcloud") {
        $extraEnv["AUTH_MODE"] = "CAC"
        $extraEnv["APP_AUTH_MODE"] = "CAC"
        $extraEnv["TRUSTED_PROXIES"] = "127.0.0.1,::1,0:0:0:0:0:0:0:1"
        $extraEnv["CAC_AUTO_PROVISION"] = "true"
        $extraEnv["CAC_REQUIRE_APPROVAL"] = "false"
        $extraEnv["APP_CSRF_BYPASS_INGEST"] = "true"
        $extraEnv["COOKIE_SECURE"] = "true"
    } elseif ($profile -in @("standard", "enterprise")) {
        $extraEnv["AUTH_MODE"] = "STANDARD"
        $extraEnv["APP_AUTH_MODE"] = "STANDARD"
        $extraEnv["SENTINEL_BOOTSTRAP_ENABLED"] = "true"
        $extraEnv["SENTINEL_ADMIN_PASSWORD"] = $AdminPassword
        $extraEnv["SENTINEL_BOOTSTRAP_ADMIN_PASSWORD"] = $AdminPassword
        $extraEnv["SENTINEL_BOOTSTRAP_RESET_ADMIN"] = "true"
        $extraEnv["COOKIE_SECURE"] = "false"
    }

    if ($profile -eq "govcloud") {
        $port = 8443
        $baseUrl = "https://localhost:$port"
        $skipCert = $true
        $keystoreDir = Join-Path $OutputDir "ssl"
        $keystorePath = Join-Path $env:USERPROFILE ".keystore"
        $keystorePass = "changeit"
        New-Item -ItemType Directory -Force -Path $keystoreDir | Out-Null
        if (-not (Test-Path $keystorePath)) {
            Write-Log "Generating temporary SSL keystore for govcloud profile"
            cmd /c "keytool -genkeypair -alias mercenary-e2e -keyalg RSA -keysize 2048 -storetype PKCS12 -keystore `"$keystorePath`" -storepass $keystorePass -keypass $keystorePass -dname `"CN=localhost, OU=Dev, O=Mercenary, L=Local, S=NA, C=US`" -validity 3650" | Out-Null
        }
        $extraEnv["SERVER_PORT"] = "$port"
        $extraEnv["SERVER_SSL_ENABLED"] = "true"
        $extraEnv["SERVER_SSL_KEY_STORE"] = $keystorePath
        $extraEnv["SERVER_SSL_KEY_STORE_PASSWORD"] = $keystorePass
        $extraEnv["SERVER_SSL_KEY_PASSWORD"] = $keystorePass
        $extraEnv["SERVER_SSL_KEY_STORE_TYPE"] = "PKCS12"
        $extraEnv["SERVER_SSL_KEY_ALIAS"] = "mercenary-e2e"
        $extraEnv["SERVER_SSL_PROTOCOL"] = "TLSv1.2"
        $extraEnv["SERVER_SSL_ENABLED_PROTOCOLS"] = "TLSv1.2"
    }

    if ($profile -eq "enterprise") {
        $extraEnv["OIDC_ISSUER"] = "https://example.invalid"
        $extraEnv["OIDC_CLIENT_ID"] = "e2e-test-client"
    }

    $logPath = Join-Path $OutputDir "boot_$profile`_$timestamp.log"
    $proc = $null
    try {
        $proc = Start-App $profile $port $extraEnv $logPath
        $healthy = Wait-ForHealth $baseUrl 120 $skipCert $port
        if (-not $healthy) {
            Write-Log "Health check failed for profile '$profile'"
            $results += [pscustomobject]@{
                profile = $profile
                status = "FAILED"
                reason = "Health check timeout"
            }
            continue
        }

        $csrf = $null
        $authHeaders = $null
        if ($extraEnv["AUTH_MODE"] -eq "CAC") {
            $authHeaders = @{ "X-Client-Cert" = [Uri]::EscapeDataString($cacSubjectDn) }
            Ensure-CacTestUser $cacSubjectDn | Out-Null
            try {
                $csrf = Get-CsrfTokenGovcloud $baseUrl $skipCert $authHeaders
            } catch {
                Write-Log "CSRF init failed for profile '$profile': $($_.Exception.Message)"
                foreach ($sector in $sectors) {
                    $results += [pscustomobject]@{
                        profile = $profile
                        sector = $sector
                        ingest = "ERROR"
                        ask = "ERROR"
                        askEnhanced = "ERROR"
                        inspect = "ERROR"
                        loginError = $_.Exception.Message
                    }
                }
                continue
            }
        } elseif ($extraEnv["AUTH_MODE"] -eq "STANDARD") {
            try {
                $csrf = Login-Standard $baseUrl $AdminPassword $skipCert
            } catch {
                Write-Log "Login failed for profile '$profile': $($_.Exception.Message)"
                foreach ($sector in $sectors) {
                    $results += [pscustomobject]@{
                        profile = $profile
                        sector = $sector
                        ingest = "ERROR"
                        ask = "ERROR"
                        askEnhanced = "ERROR"
                        inspect = "ERROR"
                        loginError = $_.Exception.Message
                    }
                }
                continue
            }
        }

        foreach ($sector in $sectors) {
            $filePath = $sectorFiles[$sector]
            $fileName = [System.IO.Path]::GetFileName($filePath)
            $result = [ordered]@{
                profile = $profile
                sector = $sector
                ingest = "SKIP"
                ask = "SKIP"
                askEnhanced = "SKIP"
                inspect = "SKIP"
            }
            try {
                $ingestResp = Ingest-File $baseUrl $sector $filePath $csrf $authHeaders $skipCert
                $result.ingest = $ingestResp.StatusCode
                if ($ingestResp.StatusCode -ne 200 -and $ingestResp.Content) {
                    $result.ingestError = $ingestResp.Content
                }
            } catch {
                $result.ingest = "ERROR"
                $line = $_.InvocationInfo.ScriptLineNumber
                $code = $_.InvocationInfo.Line
                $result.ingestError = "$($_.Exception.Message) (line ${line}: $code)"
            }
            try {
                $askResp = Ask-Query $baseUrl $sector $fileName $csrf $authHeaders $skipCert
                $result.ask = $askResp.StatusCode
            } catch {
                $result.ask = "ERROR"
                $result.askError = $_.Exception.Message
            }
            try {
                $enhancedResp = Ask-Enhanced $baseUrl $sector $fileName $csrf $authHeaders $skipCert
                $result.askEnhanced = $enhancedResp.StatusCode
            } catch {
                $result.askEnhanced = "ERROR"
                $result.askEnhancedError = $_.Exception.Message
            }
            try {
                $inspectResp = Inspect-Doc $baseUrl $sector $fileName $csrf $authHeaders $skipCert
                $result.inspect = $inspectResp.StatusCode
            } catch {
                $result.inspect = "ERROR"
                $result.inspectError = $_.Exception.Message
            }
            $results += [pscustomobject]$result
        }
    } finally {
        Stop-App $proc
    }
}

$jsonPath = Join-Path $OutputDir "e2e_results_$timestamp.json"
$mdPath = Join-Path $OutputDir "e2e_results_$timestamp.md"
$results | ConvertTo-Json -Depth 6 | Set-Content -Path $jsonPath -Encoding UTF8

@"
# E2E Results ($timestamp)

Profiles: $($Profiles -join ", ")
Mongo: [REDACTED]
Ollama: $OllamaUrl

| Profile | Sector | Ingest | Ask | Ask Enhanced | Inspect |
| --- | --- | --- | --- | --- | --- |
"@ | Set-Content -Path $mdPath -Encoding UTF8

foreach ($row in $results) {
    $line = "| $($row.profile) | $($row.sector) | $($row.ingest) | $($row.ask) | $($row.askEnhanced) | $($row.inspect) |"
    Add-Content -Path $mdPath -Value $line
}

Write-Log "E2E results written to $jsonPath"
Write-Log "E2E summary written to $mdPath"
