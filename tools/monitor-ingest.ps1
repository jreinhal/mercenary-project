param(
    [string]$BasePath = 'D:\\Corpus',
    [string]$LogPath = 'D:\\Corpus\\ingest_log.txt',
    [int]$IntervalMinutes = 15
)

$extensions = @('.txt','.md','.csv','.docx','.pptx','.xlsx','.xls','.html','.htm','.json','.ndjson','.log')
$sectors = @('Government','Medical','Finance','Academic','Enterprise')

function Get-ExpectedKeySet {
    $set = New-Object 'System.Collections.Generic.HashSet[string]'
    foreach ($sector in $sectors) {
        $path = Join-Path $BasePath $sector
        if (-not (Test-Path $path)) { continue }
        Get-ChildItem -Path $path -File | Where-Object { $extensions -contains $_.Extension.ToLower() } | ForEach-Object {
            $null = $set.Add("$sector|$($_.Name)")
        }
    }
    return $set
}

function Get-LogKeySet {
    param(
        [string]$StatusToken,
        [System.Collections.Generic.HashSet[string]]$Expected
    )

    $set = New-Object 'System.Collections.Generic.HashSet[string]'
    if (-not (Test-Path $LogPath)) { return $set }
    Get-Content $LogPath | ForEach-Object {
        if ($_ -match "$StatusToken\\|([^|]+)\\|([^|]+)\\|") {
            $key = "$($matches[1])|$($matches[2])"
            if ($Expected.Contains($key)) {
                $null = $set.Add($key)
            }
        }
    }
    return $set
}

function Write-Status {
    $expected = Get-ExpectedKeySet
    $total = $expected.Count
    $okSet = Get-LogKeySet -StatusToken 'OK' -Expected $expected
    $errSet = Get-LogKeySet -StatusToken 'ERR' -Expected $expected
    $errOnly = New-Object 'System.Collections.Generic.HashSet[string]'
    foreach ($key in $errSet) {
        if (-not $okSet.Contains($key)) {
            $null = $errOnly.Add($key)
        }
    }

    $ok = $okSet.Count
    $err = $errOnly.Count
    $pct = if ($total -gt 0) { [math]::Round(($ok / $total) * 100, 2) } else { 0 }
    $status = "{0} | OK {1}/{2} ({3}%) | ERR {4}" -f (Get-Date).ToString("s"), $ok, $total, $pct, $err
    $status | Set-Content -Path (Join-Path $BasePath 'ingest_status.txt')
    Write-Host $status
}

while ($true) {
    Write-Status
    Start-Sleep -Seconds ($IntervalMinutes * 60)
}
