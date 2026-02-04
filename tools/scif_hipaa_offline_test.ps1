param(
  [string]$MongoUri = $env:MONGODB_URI,
  [string]$AdminPassword = $env:SENTINEL_ADMIN_PASSWORD,
  [string]$OllamaUrl = $env:OLLAMA_URL,
  [switch]$SkipPlaywrightInstall
)

$ErrorActionPreference = "Stop"

function Write-Log([string]$Message) {
  $ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
  Write-Host "[$ts] $Message"
}

function Kill-ListeningPort([int]$Port) {
  Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | ForEach-Object {
    $p = $_.OwningProcess
    if ($p -and $p -ne 0) {
      Write-Log "Killing listener on port $Port (PID $p)"
      cmd /c "taskkill /PID $p /T /F" | Out-Null
    }
  }
}

function Start-EgressMonitor([string]$RepoRoot, [string]$OutFile, [string[]]$WatchProcessNames) {
  New-Item -ItemType File -Force -Path $OutFile | Out-Null
  # Collect pids for processes that clearly originate from this repo/tests to avoid noise.
  $matchers = @(
    [regex]::Escape($RepoRoot),
    "run_e2e_profiles\.ps1",
    "gradlew(\.bat)?",
    "playwright",
    "run-ui-tests\.js",
    "run-ui-govcloud\.js"
  )
  $pidRegex = ($matchers -join "|")
  return Start-Job -ScriptBlock {
    param($repoRoot, $outFile, $pidRegex, $watchNames)
    while ($true) {
      $now = Get-Date -Format o
      try {
        $pids = @(
          Get-CimInstance Win32_Process |
            Where-Object {
              $_.CommandLine -and
              $_.CommandLine -match $pidRegex -and
              $watchNames -contains $_.Name
            } |
            ForEach-Object { [int]$_.ProcessId } |
            Sort-Object -Unique
        )
        if ($pids.Count -gt 0) {
          $pidSet = @{}
          foreach ($procId in $pids) { $pidSet[[int]$procId] = $true }

          $conns = Get-NetTCPConnection | Where-Object {
            $_.State -in @("Established","SynSent","SynReceived") -and
            $_.RemoteAddress -and
            $_.RemoteAddress -notin @("127.0.0.1","::1","0.0.0.0","::") -and
            $_.RemoteAddress -notlike "127.*" -and
            $_.RemoteAddress -notlike "::ffff:127.*" -and
            $pidSet.ContainsKey([int]$_.OwningProcess)
          } | Select-Object LocalAddress,LocalPort,RemoteAddress,RemotePort,State,OwningProcess

          foreach ($c in $conns) {
            $proc = $null
            $procName = ""
            $procCmd = ""
            try {
              $proc = Get-CimInstance Win32_Process -Filter ("ProcessId = " + [int]$c.OwningProcess)
              if ($null -ne $proc) {
                $procName = $proc.Name
                $procCmd = $proc.CommandLine
              }
            } catch {}

            # Truncate command line to keep logs readable.
            if ($procCmd -and $procCmd.Length -gt 220) {
              $procCmd = $procCmd.Substring(0, 220) + "..."
            }

            "$now`t$procName`tPID=$($c.OwningProcess)`t$($c.LocalAddress):$($c.LocalPort)`t->`t$($c.RemoteAddress):$($c.RemotePort)`t$($c.State)`t$procCmd" |
              Add-Content -Path $outFile -Encoding UTF8
          }
        }
      } catch {
        "$now`tERROR`t$($_.Exception.Message)" | Add-Content -Path $outFile -Encoding UTF8
      }
      Start-Sleep -Milliseconds 500
    }
  } -ArgumentList $RepoRoot, $OutFile, $pidRegex, $WatchProcessNames
}

function Assert-NoEgress([string]$Path) {
  if (-not (Test-Path $Path)) { return }
  if ((Get-Item $Path).Length -gt 0) {
    Write-Log "FAIL: Non-loopback connections detected from repo-bound processes (first 50 lines):"
    Get-Content $Path -TotalCount 50
    throw "Egress check failed: $Path"
  }
  Write-Log "OK: No non-loopback connections detected from repo-bound processes."
}

function Assert-NoVendorStrings([string]$Path) {
  if (-not (Test-Path $Path)) { return }
  $hits = Select-String -Path $Path -Pattern "api\.openai\.com|openai|anthropic|cohere|huggingface|azure|vertex|gemini|bedrock|amazonaws|cdn\." -ErrorAction SilentlyContinue
  if ($hits) {
    Write-Log "WARNING: Vendor strings found in log output (first 20 matches):"
    $hits | Select-Object -First 20 | ForEach-Object { Write-Host ("  " + $_.LineNumber + ": " + $_.Line) }
  } else {
    Write-Log "OK: No obvious vendor strings in log output."
  }
}

function Wait-ForPort([int]$Port, [int]$TimeoutSec) {
  $deadline = (Get-Date).AddSeconds($TimeoutSec)
  while ((Get-Date) -lt $deadline) {
    if (Test-NetConnection -ComputerName "127.0.0.1" -Port $Port -InformationLevel Quiet -WarningAction SilentlyContinue) {
      return $true
    }
    Start-Sleep -Seconds 2
  }
  return $false
}

$repoRoot = (Resolve-Path ".").Path
$ts = Get-Date -Format "yyyyMMdd-HHmmss"
$auditDir = Join-Path $repoRoot "build\network-audit"
New-Item -ItemType Directory -Force -Path $auditDir | Out-Null

if ([string]::IsNullOrWhiteSpace($MongoUri)) { throw "MONGODB_URI is required (set env var or pass -MongoUri)" }
if ([string]::IsNullOrWhiteSpace($AdminPassword)) { $AdminPassword = "Test123!" }
if ([string]::IsNullOrWhiteSpace($OllamaUrl)) { $OllamaUrl = "http://localhost:11434" }

Write-Log "Preflight: ensuring ports 8080 and 8443 are free."
Kill-ListeningPort 8080
Kill-ListeningPort 8443

# ---------------------------------
# Step 1: Baseline integrity
# ---------------------------------
$stage1Log = Join-Path $auditDir "stage1-$ts.log"
$stage1Egress = Join-Path $auditDir "stage1-egress-$ts.log"
$job = Start-EgressMonitor $repoRoot $stage1Egress @("java.exe","javaw.exe")
try {
  Write-Log "Step 1: ./gradlew test"
  ./gradlew --no-daemon test *>&1 | Tee-Object -FilePath $stage1Log | Out-Host
  Write-Log "Step 1: ./gradlew ciE2eTest"
  ./gradlew --no-daemon ciE2eTest *>&1 | Tee-Object -FilePath $stage1Log -Append | Out-Host
} finally {
  Stop-Job $job | Out-Null
  Remove-Job $job | Out-Null
}
Assert-NoVendorStrings $stage1Log
Assert-NoEgress $stage1Egress

# ---------------------------------
# Step 2: Profile validation (full E2E)
# ---------------------------------
$stage2Log = Join-Path $auditDir "stage2-$ts.log"
$stage2Egress = Join-Path $auditDir "stage2-egress-$ts.log"
$job = Start-EgressMonitor $repoRoot $stage2Egress @("java.exe","javaw.exe")
try {
  $env:MONGODB_URI = $MongoUri
  $env:SENTINEL_ADMIN_PASSWORD = $AdminPassword
  $env:OLLAMA_URL = $OllamaUrl

  Write-Log "Step 2: pwsh -File tools/run_e2e_profiles.ps1"
  pwsh -NoProfile -File tools/run_e2e_profiles.ps1 *>&1 | Tee-Object -FilePath $stage2Log | Out-Host
} finally {
  Stop-Job $job | Out-Null
  Remove-Job $job | Out-Null
}
Assert-NoVendorStrings $stage2Log
Assert-NoEgress $stage2Egress

$latestMd = Get-ChildItem build\e2e-results -Filter "e2e_results_*.md" -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($latestMd) {
  Write-Log "Latest E2E summary: $($latestMd.FullName)"
  Get-Content $latestMd.FullName -TotalCount 200 | Out-Host
}

# ---------------------------------
# Step 3: HIPAA & UI compliance (Playwright)
# ---------------------------------
Write-Log "Step 3: UI smoke tests (Playwright)."
$runnerDir = Join-Path $repoRoot "tools\playwright-runner"
$nodeModules = Join-Path $runnerDir "node_modules"
if (-not (Test-Path $nodeModules)) {
  if ($SkipPlaywrightInstall) {
    throw "Playwright runner deps missing at $nodeModules and -SkipPlaywrightInstall was set."
  }
  # In a real air-gap, deps should be vendored/cached; installing requires network.
  Write-Log "Installing Playwright runner deps via npm ci (NOTE: this requires network if cache is not present)."
  Push-Location $runnerDir
  try {
    npm ci
  } finally {
    Pop-Location
  }
}

Kill-ListeningPort 8080
Start-Sleep -Seconds 2

$bootOut = Join-Path $auditDir "ui-boot-$ts.log"
$bootErr = Join-Path $auditDir "ui-boot-$ts.err.log"

Write-Log "Starting app for UI tests (APP_PROFILE=dev,test-users)"
$env:APP_PROFILE = "dev,test-users"
$env:AUTH_MODE = "DEV"
$env:APP_AUTH_MODE = "DEV"
$env:OLLAMA_URL = $OllamaUrl
$env:MONGODB_URI = $MongoUri
$env:SENTINEL_ADMIN_PASSWORD = $AdminPassword
$env:SPRING_AI_MODEL_CHAT = "ollama"
$env:SPRING_AI_MODEL_EMBEDDING = "ollama"
$env:SPRING_AI_OPENAI_CHAT_ENABLED = "false"
$env:SPRING_AI_OPENAI_EMBEDDING_ENABLED = "false"
$env:SPRING_AI_ANTHROPIC_CHAT_ENABLED = "false"
$env:SPRING_AI_VERTEX_AI_GEMINI_CHAT_ENABLED = "false"
$env:SPRING_AI_VERTEX_AI_EMBEDDING_TEXT_ENABLED = "false"
$env:SPRING_AI_VERTEX_AI_EMBEDDING_MULTIMODAL_ENABLED = "false"

$appProc = Start-Process -FilePath "powershell" -ArgumentList "-NoProfile -Command cd `"$repoRoot`"; ./gradlew bootRun" -RedirectStandardOutput $bootOut -RedirectStandardError $bootErr -PassThru

try {
  if (-not (Wait-ForPort 8080 120)) {
    Write-Log "UI app did not open port 8080 in time; tailing logs."
    Get-Content $bootOut -Tail 120 | Out-Host
    Get-Content $bootErr -Tail 120 | Out-Host
    throw "UI app not ready"
  }

  $stage3Log = Join-Path $auditDir "stage3-$ts.log"
  $stage3Egress = Join-Path $auditDir "stage3-egress-$ts.log"
  # Browser processes (msedge/chrome) may phone-home for updates/telemetry on a connected dev box;
  # instead, Playwright scripts enforce air-gap by blocking any non-localhost HTTP(S) requests.
  $job = Start-EgressMonitor $repoRoot $stage3Egress @("java.exe","javaw.exe","node.exe")
  try {
    Push-Location $runnerDir
    try {
      # Prefer using the existing DB contents for speed/determinism unless explicitly overridden.
      if ([string]::IsNullOrWhiteSpace($env:SKIP_SEED_DOCS)) {
        $env:SKIP_SEED_DOCS = "true"
      }

      $global:LASTEXITCODE = 0
      node run-ui-tests.js 2>&1 | Tee-Object -FilePath $stage3Log | Out-Host
      if ($LASTEXITCODE -ne 0) {
        throw "UI suite failed (exit code $LASTEXITCODE). See $stage3Log"
      }
    } finally {
      Pop-Location
    }
  } finally {
    Stop-Job $job | Out-Null
    Remove-Job $job | Out-Null
  }

  Assert-NoVendorStrings $stage3Log
  Assert-NoEgress $stage3Egress
} finally {
  if ($null -ne $appProc -and -not $appProc.HasExited) {
    Write-Log "Stopping UI app (PID $($appProc.Id))"
    cmd /c "taskkill /PID $($appProc.Id) /T /F" | Out-Null
  }
}

Write-Log "All steps completed successfully."
