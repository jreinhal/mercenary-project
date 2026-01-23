param(
    [string]$Url = "http://localhost:8080/api/health",
    [int]$Requests = 200,
    [int]$Concurrency = 20,
    [int]$TimeoutSec = 5
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-Percentile([double[]]$values, [double]$percent) {
    if ($values.Length -eq 0) { return 0 }
    $sorted = $values | Sort-Object
    $index = [math]::Ceiling(($percent / 100.0) * $sorted.Length) - 1
    if ($index -lt 0) { $index = 0 }
    if ($index -ge $sorted.Length) { $index = $sorted.Length - 1 }
    return $sorted[$index]
}

try {
    $probe = Invoke-WebRequest -UseBasicParsing -TimeoutSec $TimeoutSec -Uri $Url
} catch {
    Write-Host "Load test aborted: endpoint is not reachable ($Url)."
    exit 1
}

$results = New-Object System.Collections.Generic.List[object]

if ($PSVersionTable.PSVersion.Major -ge 7) {
    $results = 1..$Requests | ForEach-Object -Parallel {
        param($url, $timeoutSec)
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        try {
            $resp = Invoke-WebRequest -UseBasicParsing -TimeoutSec $timeoutSec -Uri $url
            $sw.Stop()
            if ($resp.StatusCode -ge 200 -and $resp.StatusCode -lt 300) {
                [pscustomobject]@{ Ok = $true; Latency = $sw.Elapsed.TotalMilliseconds; Error = $null }
            } else {
                [pscustomobject]@{ Ok = $false; Latency = $sw.Elapsed.TotalMilliseconds; Error = "HTTP $($resp.StatusCode)" }
            }
        } catch {
            $sw.Stop()
            [pscustomobject]@{ Ok = $false; Latency = $sw.Elapsed.TotalMilliseconds; Error = $_.Exception.Message }
        }
    } -ThrottleLimit $Concurrency -ArgumentList $Url, $TimeoutSec
} else {
    $jobs = @()
    for ($i = 0; $i -lt $Requests; $i++) {
        while (@($jobs).Count -ge $Concurrency) {
            $done = Wait-Job -Job $jobs -Any
            if ($done) {
                $results.AddRange(@(Receive-Job $done))
                Remove-Job $done
                $jobs = $jobs | Where-Object { $_.Id -ne $done.Id }
            }
        }
        $jobs += Start-Job -ScriptBlock {
            param($url, $timeoutSec)
            $sw = [System.Diagnostics.Stopwatch]::StartNew()
            try {
                $resp = Invoke-WebRequest -UseBasicParsing -TimeoutSec $timeoutSec -Uri $url
                $sw.Stop()
                if ($resp.StatusCode -ge 200 -and $resp.StatusCode -lt 300) {
                    [pscustomobject]@{ Ok = $true; Latency = $sw.Elapsed.TotalMilliseconds; Error = $null }
                } else {
                    [pscustomobject]@{ Ok = $false; Latency = $sw.Elapsed.TotalMilliseconds; Error = "HTTP $($resp.StatusCode)" }
                }
            } catch {
                $sw.Stop()
                [pscustomobject]@{ Ok = $false; Latency = $sw.Elapsed.TotalMilliseconds; Error = $_.Exception.Message }
            }
        } -ArgumentList $Url, $TimeoutSec
    }
    if (@($jobs).Count -gt 0) {
        Wait-Job -Job $jobs | Out-Null
        foreach ($job in $jobs) {
            $results.AddRange(@(Receive-Job $job))
            Remove-Job $job
        }
    }
}

$latencies = New-Object System.Collections.Generic.List[double]
$errors = New-Object System.Collections.Generic.List[string]
foreach ($result in $results) {
    if ($result.Ok -and $null -ne $result.Latency) {
        $latencies.Add([double]$result.Latency)
    } else {
        $errors.Add([string]$result.Error)
    }
}

$latencyArray = $latencies.ToArray()
$successCount = $latencyArray.Length
$errorCount = $errors.Count
$totalCount = $successCount + $errorCount

$avg = if ($successCount -gt 0) { ($latencyArray | Measure-Object -Average).Average } else { 0 }
$min = if ($successCount -gt 0) { ($latencyArray | Measure-Object -Minimum).Minimum } else { 0 }
$max = if ($successCount -gt 0) { ($latencyArray | Measure-Object -Maximum).Maximum } else { 0 }

$p50 = Get-Percentile $latencyArray 50
$p95 = Get-Percentile $latencyArray 95
$p99 = Get-Percentile $latencyArray 99

Write-Host "Load test complete:"
Write-Host "  URL:          $Url"
Write-Host "  Requests:     $totalCount"
Write-Host "  Success:      $successCount"
Write-Host "  Errors:       $errorCount"
Write-Host ("  Avg (ms):     {0:N2}" -f $avg)
Write-Host ("  Min (ms):     {0:N2}" -f $min)
Write-Host ("  Max (ms):     {0:N2}" -f $max)
Write-Host ("  P50 (ms):     {0:N2}" -f $p50)
Write-Host ("  P95 (ms):     {0:N2}" -f $p95)
Write-Host ("  P99 (ms):     {0:N2}" -f $p99)

if ($errorCount -gt 0) {
    Write-Host "Sample errors:"
    $errors.ToArray() | Select-Object -First 5 | ForEach-Object { Write-Host "  - $_" }
}
