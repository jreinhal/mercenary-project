$ErrorActionPreference = "Stop"

function Get-MongoServiceConfigPath {
    $pathName = (Get-CimInstance Win32_Service -Filter "Name='MongoDB'" | Select-Object -ExpandProperty PathName)
    if (-not $pathName) {
        throw "MongoDB service not found. Ensure MongoDB is installed as a Windows service."
    }
    if ($pathName -match '--config\s+"([^"]+)"') {
        return $Matches[1]
    }
    throw "Could not parse --config path from MongoDB service."
}

function Get-MongoShell {
    $mongosh = (where.exe mongosh 2>$null | Select-Object -First 1)
    if ($mongosh) { return $mongosh }
    throw "mongosh not found in PATH. Install MongoDB Shell (mongosh) and retry."
}

$mongosh = Get-MongoShell
$cfg = Get-MongoServiceConfigPath
$backup = "$cfg.bak"

if (-not (Test-Path $backup)) {
    Copy-Item -Path $cfg -Destination $backup -Force
}

Write-Host "Using mongosh: $mongosh"
Write-Host "Mongo config: $cfg (backup: $backup)"

$adminUser = "admin"
$appUser = "mercenary_app"

$adminPwdSec = Read-Host "Enter password for admin user '$adminUser'" -AsSecureString
$appPwdSec = Read-Host "Enter password for app user '$appUser'" -AsSecureString
$adminPwd = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($adminPwdSec))
$appPwd = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($appPwdSec))
$adminPwdUri = [System.Uri]::EscapeDataString($adminPwd)
$appPwdUri = [System.Uri]::EscapeDataString($appPwd)
# Escape for JS string literals
$adminPwdEsc = $adminPwd -replace '\\', '\\\\' -replace '"', '\"'
$appPwdEsc = $appPwd -replace '\\', '\\\\' -replace '"', '\"'

$tmp = Join-Path $env:TEMP "mongo_user_init.js"
@"
use admin
if (!db.getUser("$adminUser")) {
  db.createUser({user: "$adminUser", pwd: "$adminPwdEsc", roles: [{role: "userAdminAnyDatabase", db: "admin"}, {role: "readWriteAnyDatabase", db: "admin"}]});
  print('created admin');
} else { print('admin exists'); }

use mercenary
if (!db.getUser("$appUser")) {
  db.createUser({user: "$appUser", pwd: "$appPwdEsc", roles: [{role: "readWrite", db: "mercenary"}]});
  print('created app user');
} else { print('app user exists'); }
"@ | Set-Content -Path $tmp -Encoding UTF8

& $mongosh --quiet --file $tmp
Remove-Item -Path $tmp -Force

$content = Get-Content -Path $cfg -Raw

if ($content -notmatch '(?m)^\s*security:\s*$') {
    if ($content -match '(?m)^\s*#\s*security:\s*$') {
        $content = $content -replace '(?m)^\s*#\s*security:\s*$', "security:`n  authorization: enabled"
    } else {
        $content = $content.TrimEnd() + "`n`nsecurity:`n  authorization: enabled`n"
    }
} elseif ($content -notmatch '(?m)^\s*authorization:\s*enabled\s*$') {
    $content = $content -replace '(?m)^\s*security:\s*$', '$0' + "`n  authorization: enabled"
}

# Ensure bindIp is set to localhost only (SCIF-friendly)
if ($content -notmatch '(?m)^\s*bindIp:\s*127\.0\.0\.1\s*$') {
    $content = $content -replace '(?m)^\s*bindIp:.*$', '  bindIp: 127.0.0.1'
}

Set-Content -Path $cfg -Value $content -Encoding UTF8

Restart-Service MongoDB
Start-Sleep -Seconds 2

& $mongosh "mongodb://${appUser}:${appPwdUri}@localhost:27017/mercenary" --quiet --eval "db.runCommand({connectionStatus:1})" | Out-Host

Write-Host "Done. Set MONGODB_URI for the app:"
Write-Host "  mongodb://${appUser}:***@localhost:27017/mercenary"
