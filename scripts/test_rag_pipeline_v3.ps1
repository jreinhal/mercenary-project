$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$reportsDir = Join-Path $repoRoot "reports\\tests"
New-Item -ItemType Directory -Force -Path $reportsDir | Out-Null

$tests = @(
    @{ Sector = "ENTERPRISE"; Query = "What is the organizational hierarchy?" },
    @{ Sector = "GOVERNMENT"; Query = "What security classifications are mentioned?" },
    @{ Sector = "MEDICAL"; Query = "What patient care protocols are documented?" },
    @{ Sector = "FINANCE"; Query = "What are the key financial metrics?" },
    @{ Sector = "ACADEMIC"; Query = "What research projects are documented?" }
)

$outputFile = Join-Path $reportsDir "test_results_ascii.txt"
"RAG Test Results" | Out-File $outputFile -Encoding Ascii

foreach ($test in $tests) {
    $sector = $test.Sector
    $query = $test.Query.Replace(" ", "%20")
    "Testing $sector..." | Out-File $outputFile -Append -Encoding Ascii
    
    try {
        $uri = "http://localhost:8081/api/ask?q=$query&dept=$sector"
        $response = Invoke-WebRequest -Uri $uri -Method Post -UseBasicParsing
        "Status: $($response.StatusCode)" | Out-File $outputFile -Append -Encoding Ascii
        $len = $response.Content.Length
        "Length: $len" | Out-File $outputFile -Append -Encoding Ascii
        if ($len -gt 0) {
            # Remove newlines for cleaner log
            $cleanContent = $response.Content.Replace("`n", " ").Replace("`r", "")
            "Content Preview: $($cleanContent.Substring(0, [Math]::Min(100, $cleanContent.Length)))" | Out-File $outputFile -Append -Encoding Ascii
        }
    }
    catch {
        "Error: $($_.Exception.Message)" | Out-File $outputFile -Append -Encoding Ascii
    }
    "----------------" | Out-File $outputFile -Append -Encoding Ascii
}
