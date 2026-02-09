$ErrorActionPreference = 'Stop'

function Stop-App {
  $conn = Get-NetTCPConnection -LocalPort 8443 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($conn) { Stop-Process -Id $conn.OwningProcess -Force; Start-Sleep -Seconds 2 }
}

function Wait-Port {
  param(
    [int]$Port,
    [int]$TimeoutSeconds = 240
  )
  $timeout = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
  while ([DateTime]::UtcNow -lt $timeout) {
    $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($conn) { return $true }
    Start-Sleep -Seconds 2
  }
  return $false
}

function Ensure-Ollama {
  if (Get-NetTCPConnection -LocalPort 11434 -State Listen -ErrorAction SilentlyContinue) { return }
  Start-Process -FilePath "ollama" -ArgumentList "serve" -WindowStyle Hidden | Out-Null
  if (-not (Wait-Port -Port 11434 -TimeoutSeconds 30)) {
    throw "Ollama did not start on port 11434."
  }
}

Stop-App
Ensure-Ollama

$keystorePath = Join-Path $env:USERPROFILE ".keystore"
$keystorePass = "changeit"
if (-not (Test-Path $keystorePath)) {
  cmd /c "keytool -genkeypair -alias mercenary-e2e -keyalg RSA -keysize 2048 -storetype PKCS12 -keystore `"$keystorePath`" -storepass $keystorePass -keypass $keystorePass -dname `"CN=localhost, OU=Dev, O=Mercenary, L=Local, S=NA, C=US`" -validity 3650" | Out-Null
}

$env:APP_PROFILE='govcloud'
$env:AUTH_MODE='CAC'
$env:TRUSTED_PROXIES='127.0.0.1,::1,0:0:0:0:0:0:0:1,::ffff:127.0.0.1'
$env:CAC_AUTO_PROVISION='true'
$env:CAC_REQUIRE_APPROVAL='false'
$env:COOKIE_SECURE='true'
$env:SERVER_PORT='8443'
$env:SERVER_SSL_ENABLED='true'
$env:SERVER_SSL_KEY_STORE=$keystorePath
$env:SERVER_SSL_KEY_STORE_PASSWORD=$keystorePass
$env:SERVER_SSL_KEY_PASSWORD=$keystorePass
$env:SERVER_SSL_KEY_STORE_TYPE='PKCS12'
$env:SERVER_SSL_KEY_ALIAS='mercenary-e2e'
$env:SERVER_SSL_PROTOCOL='TLSv1.2'
$env:SERVER_SSL_ENABLED_PROTOCOLS='TLSv1.2'

# Seed CAC admin user for UI access
$subjectDn = 'CN=E2E_TEST, OU=E2E, O=Mercenary, L=Local, S=NA, C=US'
$mongoUri = $env:MONGODB_URI
if (-not [string]::IsNullOrWhiteSpace($mongoUri)) {
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
      allowedSectors: ['GOVERNMENT','MEDICAL','ENTERPRISE'],
      active: true,
      pendingApproval: false
    },
    `$setOnInsert: { createdAt: new Date() }
  },
  { upsert: true }
)
"@
  mongosh $mongoUri --quiet --eval $js | Out-Null
}

Start-Process -FilePath "D:\Projects\mercenary\gradlew.bat" -ArgumentList "bootRun" -WorkingDirectory "D:\Projects\mercenary" -WindowStyle Hidden -RedirectStandardOutput "D:\Projects\mercenary\bootrun-govcloud-ui.log" -RedirectStandardError "D:\Projects\mercenary\bootrun-govcloud-ui.err.log"

if (-not (Wait-Port -Port 8443 -TimeoutSeconds 240)) {
  throw "Govcloud app failed to start on 8443."
}

$env:BASE_URL='https://localhost:8443'
$env:OUTPUT_JSON='D:\Projects\mercenary\tools\playwright-runner\results_govcloud.json'
node D:\Projects\mercenary\tools\playwright-runner\run-ui-govcloud.js

Stop-App
