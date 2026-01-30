$ErrorActionPreference = 'Stop'

function Stop-App {
  $conn = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($conn) {
    Stop-Process -Id $conn.OwningProcess -Force
    Start-Sleep -Seconds 2
  }
}

function Wait-Port {
  param([int]$TimeoutSeconds = 180)
  $timeout = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
  while ([DateTime]::UtcNow -lt $timeout) {
    $conn = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($conn) { return $true }
    Start-Sleep -Seconds 2
  }
  return $false
}

$baseEnv = @{
  APP_PROFILE = 'standard'
  COOKIE_SECURE = 'false'
  ADAPTIVERAG_ENABLED = 'true'
  HYDE_ENABLED = 'false'
  SELFRAG_ENABLED = 'false'
  AGENTIC_ENABLED = 'false'
  CRAG_ENABLED = 'false'
  QUCORAG_ENABLED = 'false'
  PII_MODE = 'MASK'
}

$scenarios = @(
  @{ Name='hyde'; Scenario='HYDE'; Flags=@{ HYDE_ENABLED='true' } },
  @{ Name='selfrag'; Scenario='SELFRAG'; Flags=@{ SELFRAG_ENABLED='true' } },
  @{ Name='agentic'; Scenario='AGENTIC'; Flags=@{ AGENTIC_ENABLED='true' } },
  @{ Name='qucorag'; Scenario='QUCORAG'; Flags=@{ QUCORAG_ENABLED='true' } },
  @{ Name='crag'; Scenario='CRAG'; Flags=@{ CRAG_ENABLED='true' } },
  @{ Name='hyde_qucorag'; Scenario='HYDE_QUCORAG'; Flags=@{ HYDE_ENABLED='true'; QUCORAG_ENABLED='true' } },
  @{ Name='crag_selfrag'; Scenario='CRAG_SELFRAG'; Flags=@{ CRAG_ENABLED='true'; SELFRAG_ENABLED='true' } },
  @{ Name='agentic_hyde'; Scenario='AGENTIC_HYDE'; Flags=@{ AGENTIC_ENABLED='true'; HYDE_ENABLED='true' } },
  @{ Name='crag_qucorag'; Scenario='CRAG_QUCORAG'; Flags=@{ CRAG_ENABLED='true'; QUCORAG_ENABLED='true' } }
)

$runnerDir = 'D:\Projects\mercenary\tools\playwright-runner'
$resultsDir = $runnerDir

foreach ($scenario in $scenarios) {
  Write-Host "=== Running scenario: $($scenario.Name) ==="
  Stop-App

  foreach ($key in $baseEnv.Keys) {
    Set-Item -Path ("Env:{0}" -f $key) -Value $baseEnv[$key]
  }
  foreach ($key in $scenario.Flags.Keys) {
    Set-Item -Path ("Env:{0}" -f $key) -Value $scenario.Flags[$key]
  }

  $logOut = "D:\Projects\mercenary\bootrun-$($scenario.Name).log"
  $logErr = "D:\Projects\mercenary\bootrun-$($scenario.Name).err.log"
  Start-Process -FilePath "D:\Projects\mercenary\gradlew.bat" -ArgumentList "bootRun" -WorkingDirectory "D:\Projects\mercenary" -WindowStyle Hidden -RedirectStandardOutput $logOut -RedirectStandardError $logErr

  if (-not (Wait-Port -TimeoutSeconds 240)) {
    throw "App did not start for scenario $($scenario.Name)."
  }

  $env:SCENARIO = $scenario.Scenario
  $env:OUTPUT_JSON = Join-Path $resultsDir ("results_flags_{0}.json" -f $scenario.Name)
  $env:RUN_LABEL = $scenario.Name

  Push-Location $runnerDir
  node run-ui-flags.js
  Pop-Location
}

Stop-App
Write-Host "Flag matrix complete."
