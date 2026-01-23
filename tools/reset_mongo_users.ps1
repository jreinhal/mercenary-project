param(
  [string]$AdminPwd = "Hulk1077",
  [string]$AppPwd = "Hulk1077",
  [string]$MongoShell = "C:\\Tools\\mongosh-2.5.6-win32-x64\\bin\\mongosh.exe",
  [string]$DataDir = "D:\\MongoDB\\data",
  [string]$LogDir = "D:\\MongoDB\\log"
)

$ErrorActionPreference = "Stop"

function Assert-Admin {
  $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
  $principal = New-Object Security.Principal.WindowsPrincipal($identity)
  if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Error "Run this script in an elevated (Administrator) PowerShell window."
    exit 1
  }
}

function Escape-JsSingle([string]$s) {
  return ($s -replace '\\', '\\\\' -replace "'", "\\'")
}

Assert-Admin

if (-not (Test-Path -Path $MongoShell)) {
  Write-Error "mongosh not found at: $MongoShell"
  exit 1
}

$ts = Get-Date -Format "yyyyMMdd_HHmmss"
$backupDir = "D:\\MongoDB\\backups"
New-Item -ItemType Directory -Force -Path $backupDir | Out-Null

Write-Host "Stopping MongoDB service..."
Stop-Service MongoDB -Force

if (Test-Path -Path $DataDir) {
  $dataBackup = Join-Path $backupDir ("data_old_{0}" -f $ts)
  Write-Host "Backing up data dir to: $dataBackup"
  Move-Item -Path $DataDir -Destination $dataBackup
}

New-Item -ItemType Directory -Force -Path $DataDir | Out-Null
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

icacls $DataDir /grant "NT AUTHORITY\\NetworkService:(OI)(CI)F" | Out-Null
icacls $LogDir /grant "NT AUTHORITY\\NetworkService:(OI)(CI)F" | Out-Null

Write-Host "Starting MongoDB service..."
Start-Service MongoDB
Start-Sleep -Seconds 2

$adminPwdJs = Escape-JsSingle $AdminPwd
$appPwdJs = Escape-JsSingle $AppPwd

$js = @"
const adminPwd = '$adminPwdJs';
const appPwd = '$appPwdJs';
const adminDb = db.getSiblingDB('admin');
adminDb.createUser({
  user: 'admin',
  pwd: adminPwd,
  roles: [
    { role: 'userAdminAnyDatabase', db: 'admin' },
    { role: 'readWriteAnyDatabase', db: 'admin' }
  ]
});

adminDb.auth('admin', adminPwd);
const appDb = db.getSiblingDB('mercenary');
appDb.createUser({
  user: 'mercenary_app',
  pwd: appPwd,
  roles: [ { role: 'readWrite', db: 'mercenary' } ]
});
"@

Write-Host "Creating admin and app users (localhost exception)..."
& $MongoShell --quiet --eval $js

Write-Host "Testing admin login..."
& $MongoShell --username admin --password $AdminPwd --authenticationDatabase admin --host localhost --port 27017 --eval "db.runCommand({connectionStatus:1})"

Write-Host "Testing app login..."
& $MongoShell --username mercenary_app --password $AppPwd --authenticationDatabase mercenary --host localhost --port 27017 --eval "db.runCommand({connectionStatus:1})"

$enc = [uri]::EscapeDataString($AppPwd)
Write-Host "Done. App URI:"
Write-Host ("  mongodb://mercenary_app:{0}@localhost:27017/mercenary" -f $enc)
