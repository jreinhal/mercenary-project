param(
  [string]$AdminPwd = "Hulk1077",
  [string]$AppPwd = "Hulk1077",
  [string]$MongoCfg = "D:\\Program Files\\bin\\mongod.cfg",
  [string]$MongoShell = "C:\\Tools\\mongosh-2.5.6-win32-x64\\bin\\mongosh.exe"
)

function Assert-Admin {
  $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
  $principal = New-Object Security.Principal.WindowsPrincipal($identity)
  if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Error "Run this script in an elevated (Administrator) PowerShell window."
    exit 1
  }
}

Assert-Admin

if (-not (Test-Path -Path $MongoCfg)) {
  Write-Error "mongod.cfg not found at: $MongoCfg"
  exit 1
}
if (-not (Test-Path -Path $MongoShell)) {
  Write-Error "mongosh not found at: $MongoShell"
  exit 1
}

$backupDir = "D:\\MongoDB\\backups"
New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
$ts = Get-Date -Format "yyyyMMdd_HHmmss"
$backupCfg = Join-Path $backupDir "mongod.cfg.bak_$ts"

Copy-Item -Force $MongoCfg $backupCfg
Write-Host "Backed up config to: $backupCfg"

Write-Host "Stopping MongoDB service..."
Stop-Service MongoDB -Force

Write-Host "Temporarily disabling auth..."
$content = Get-Content -Path $MongoCfg -Raw
$content = $content -replace '(?m)^\\s*authorization:\\s*enabled\\s*$', '  authorization: disabled'
Set-Content -Path $MongoCfg -Value $content -Encoding UTF8

Start-Service MongoDB
Start-Sleep -Seconds 2

Write-Host "Creating/updating admin and app users..."
$js = @"
const adminPwd = '$AdminPwd';
const appPwd = '$AppPwd';
const adminDb = db.getSiblingDB('admin');
const adminRoles = [
  { role: 'userAdminAnyDatabase', db: 'admin' },
  { role: 'readWriteAnyDatabase', db: 'admin' }
];
const adminUser = adminDb.getUser('admin');
if (adminUser) {
  adminDb.updateUser('admin', { pwd: adminPwd, roles: adminRoles });
} else {
  adminDb.createUser({ user: 'admin', pwd: adminPwd, roles: adminRoles });
}

const appDb = db.getSiblingDB('mercenary');
const appRoles = [ { role: 'readWrite', db: 'mercenary' } ];
const appUser = appDb.getUser('mercenary_app');
if (appUser) {
  appDb.updateUser('mercenary_app', { pwd: appPwd, roles: appRoles });
} else {
  appDb.createUser({ user: 'mercenary_app', pwd: appPwd, roles: appRoles });
}
"@

& $MongoShell --quiet --eval $js

Write-Host "Re-enabling auth..."
Stop-Service MongoDB -Force
$content = Get-Content -Path $MongoCfg -Raw
$content = $content -replace '(?m)^\\s*authorization:\\s*disabled\\s*$', '  authorization: enabled'
Set-Content -Path $MongoCfg -Value $content -Encoding UTF8
Start-Service MongoDB
Start-Sleep -Seconds 2

Write-Host "Testing admin login..."
& $MongoShell --username admin --password $AdminPwd --authenticationDatabase admin --host localhost --port 27017 --eval "db.runCommand({connectionStatus:1})"

Write-Host "Testing app login..."
& $MongoShell --username mercenary_app --password $AppPwd --authenticationDatabase mercenary --host localhost --port 27017 --eval "db.runCommand({connectionStatus:1})"

Write-Host "Done. App URI:"
$enc = [uri]::EscapeDataString($AppPwd)
Write-Host ("  mongodb://mercenary_app:{0}@localhost:27017/mercenary" -f $enc)
