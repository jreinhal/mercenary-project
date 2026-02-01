param(
    [string]$ApiBase = 'http://localhost:8080/api',
    [string]$Dept = 'ENTERPRISE'
)

$sampleDir = Join-Path $PSScriptRoot '.'
$files = Get-ChildItem -Path $sampleDir -File | Where-Object { $_.Extension -ne '.ps1' -and $_.Extension -ne '.py' }

if ($files.Count -eq 0) {
    Write-Host "No sample files found. Run generate_samples.py first."
    exit 1
}

foreach ($file in $files) {
    if ($file.Name -eq '.gitignore') { continue }
    try {
        $resp = Invoke-WebRequest -UseBasicParsing -Uri "$ApiBase/ingest/file" -Method Post -InFile $file.FullName -ContentType 'multipart/form-data' -TimeoutSec 300 -SkipHttpErrorCheck
        if ($resp.StatusCode -ge 200 -and $resp.StatusCode -lt 300) {
            Write-Host "$($file.Name): OK"
        } else {
            Write-Host "$($file.Name): HTTP $($resp.StatusCode)"
        }
    } catch {
        Write-Host "$($file.Name): ERROR $($_.Exception.Message)"
    }
}
