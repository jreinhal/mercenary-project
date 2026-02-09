param(
    [string]$BasePath = 'D:\\Corpus',
    [string]$ApiBase = 'http://localhost:8080/api',
    [string]$LogPath = 'D:\\Corpus\\ingest_log.txt',
    [int]$MaxRetries = 5,
    [int]$DelayMs = 750
)

$sectorMap = @{
    Government = 'GOVERNMENT'
    Medical    = 'MEDICAL'
    Enterprise = 'ENTERPRISE'
}

$done = @{}
if (Test-Path $LogPath) {
    Get-Content $LogPath | ForEach-Object {
        if ($_ -match '^(?:\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\|)?OK\\|(.*?)\\|(.*?)\\|') {
            $key = "$($matches[1])|$($matches[2])"
            $done[$key] = $true
        }
    }
}

$files = @()
foreach ($folder in $sectorMap.Keys) {
    $path = Join-Path $BasePath $folder
    if (Test-Path $path) {
        $files += Get-ChildItem -Path $path -File | Where-Object {
            $_.Extension -in '.txt','.md','.csv','.docx','.pptx','.xlsx','.xls','.html','.htm','.json','.ndjson','.log'
        }
    }
}

$total = $files.Count
$index = 0
foreach ($file in $files) {
    $index++
    $sector = Split-Path $file.DirectoryName -Leaf
    $dept = $sectorMap[$sector]
    if (-not $dept) { continue }
    $key = "$sector|$($file.Name)"
    if ($done.ContainsKey($key)) { continue }

    $attempt = 0
    $success = $false
    while (-not $success -and $attempt -lt $MaxRetries) {
        $attempt++
        try {
            $resp = Invoke-WebRequest -UseBasicParsing -Uri "$ApiBase/ingest/file" -Method Post -Form @{ file = $file; dept = $dept } -TimeoutSec 300 -SkipHttpErrorCheck
            if ($resp.StatusCode -eq 429) {
                Start-Sleep -Seconds (5 * $attempt)
                continue
            }
            $ts = (Get-Date).ToString("s")
            if ($resp.StatusCode -ge 200 -and $resp.StatusCode -lt 300) {
                Add-Content -Path $LogPath -Value "$ts|OK|$sector|$($file.Name)|$($resp.StatusCode)|$($resp.Content)"
                $success = $true
            } else {
                Add-Content -Path $LogPath -Value "$ts|ERR|$sector|$($file.Name)|HTTP $($resp.StatusCode)"
                $success = $true
            }
        } catch {
            $ts = (Get-Date).ToString("s")
            $msg = $_.Exception.Message -replace '\r?\n',' '
            Add-Content -Path $LogPath -Value "$ts|ERR|$sector|$($file.Name)|$msg"
            $success = $true
        }
    }

    Start-Sleep -Milliseconds $DelayMs
    if ($index % 50 -eq 0) {
        Write-Host ("Progress: {0}/{1}" -f $index, $total)
    }
}

Write-Host 'Ingestion complete.'
