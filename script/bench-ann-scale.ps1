param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$KbId = "e2e-ann-bench",
    [string]$Query = "zq",
    [int]$TopK = 8,
    [double]$MinScore = 0.9,
    [int]$Repeat = 5
)

$ErrorActionPreference = "Stop"
$body = @{ query = $Query; topK = $TopK; minScore = $MinScore; sources = @("local"); includeContext = $false } |
    ConvertTo-Json -Compress

$times = @()
$last = $null
for ($i = 1; $i -le $Repeat; $i++) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $last = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/kbs/$KbId/retrieve" -ContentType "application/json" -Body $body
    $sw.Stop()
    $times += $sw.Elapsed.TotalMilliseconds
}

$sorted = $times | Sort-Object
$p50 = [math]::Round($sorted[[int][math]::Floor($sorted.Count * 0.5)], 1)
$p95 = [math]::Round($sorted[[math]::Min($sorted.Count - 1, [int][math]::Ceiling($sorted.Count * 0.95) - 1)], 1)
$chunkIndexes = @($last.data.chunks | ForEach-Object { $_.chunkIndex })
$expected = @(1..$TopK | ForEach-Object { $_ * 7 })
$recall = (@($chunkIndexes | Where-Object { $_ -le 200 }).Count)

"query=$Query kb=$KbId repeat=$Repeat"
"latency: p50=${p50}ms p95=${p95}ms min=$([math]::Round($sorted[0],1))ms max=$([math]::Round($sorted[-1],1))ms"
"stats:  $($last.data.stats | ConvertTo-Json -Compress)"
"top-$TopK chunkIndex: $($chunkIndexes -join ',')"
"expected(1..$TopK): $($expected -join ',')"
"exactMatch=$(($chunkIndexes -join ',') -eq ($expected -join ','))  needleHits=$recall/$TopK"
