<#
.SYNOPSIS
  M4-3A real Redis contract plus two-instance SSE replay. Use only mock engines.
.NOTES
  UTF-8 BOM required for Windows PowerShell 5.1. Reports stay in target/m4-3/.
  Restart Redis/application outside this script, then pass -ReplayRunId to prove persistence.
  This script never restarts containers or deletes files/keys.
#>
param(
    [string]$BaseUrlA = 'http://localhost:8082',
    [string]$BaseUrlB = 'http://localhost:8083',
    [string]$RedisHost = 'localhost',
    [int]$RedisPort = 16379,
    [string]$MavenCommand,
    [switch]$SkipRedisContractTests,
    [string]$ReplayRunId,
    [string]$OutFile = 'target/m4-3/redis-events-verify.json'
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(45)
$checks = New-Object System.Collections.Generic.List[object]

function Check([string]$Name, [bool]$Passed, [object]$Evidence) {
    $checks.Add([pscustomobject]@{ name = $Name; passed = $Passed; evidence = $Evidence })
    if (-not $Passed) { throw "FAILED: $Name" }
    Write-Output "PASS: $Name"
}

function Api([string]$Base, [string]$Method, [string]$Path, $Body = $null) {
    $request = New-Object System.Net.Http.HttpRequestMessage ([System.Net.Http.HttpMethod]::new($Method)), ($Base + $Path)
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 20 -Compress
        $request.Content = New-Object System.Net.Http.StringContent $json, ([Text.Encoding]::UTF8), 'application/json'
    }
    $response = $client.SendAsync($request).GetAwaiter().GetResult()
    try {
        if (-not $response.IsSuccessStatusCode) { throw "API $Method $Path returned $([int]$response.StatusCode)" }
        return ($response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json).data
    } finally { $response.Dispose(); $request.Dispose() }
}

function ReadEvents([string]$Base, [string]$Run, [long]$Cursor = 0) {
    $request = New-Object System.Net.Http.HttpRequestMessage ([System.Net.Http.HttpMethod]::Get), "$Base/api/v1/runs/$Run/events"
    $request.Headers.TryAddWithoutValidation('Last-Event-ID', [string]$Cursor) | Out-Null
    $response = $client.SendAsync($request, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
    try {
        if (-not $response.IsSuccessStatusCode) { throw "SSE returned $([int]$response.StatusCode)" }
        $stream = $response.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
        $reader = New-Object IO.StreamReader $stream, ([Text.Encoding]::UTF8)
        $events = New-Object System.Collections.Generic.List[object]
        $deadline = [DateTime]::UtcNow.AddSeconds(30)
        while ([DateTime]::UtcNow -lt $deadline) {
            $lineTask = $reader.ReadLineAsync()
            $remaining = [Math]::Max(1, [int]($deadline - [DateTime]::UtcNow).TotalMilliseconds)
            if (-not $lineTask.Wait($remaining)) { throw 'SSE did not reach a terminal event in 30 seconds' }
            $line = $lineTask.GetAwaiter().GetResult()
            if ($null -eq $line) { throw 'SSE ended before a terminal event' }
            if ($line.StartsWith('data:')) {
                $event = $line.Substring(5).Trim() | ConvertFrom-Json
                if ($null -ne $event.seq) {
                    $events.Add($event)
                    if ($event.type -in @('RUN_SUCCEEDED', 'RUN_DEGRADED', 'RUN_FAILED', 'RUN_CANCELED')) {
                        return $events.ToArray()
                    }
                } elseif ($null -ne $event.code) { throw "SSE control error: $($event.code)" }
            }
        }
        throw 'SSE timeout'
    } finally {
        if ($null -ne $reader) { $reader.Dispose() }
        $response.Dispose()
        $request.Dispose()
    }
}

function CursorStatus([string]$Run, [string]$Cursor) {
    $request = New-Object System.Net.Http.HttpRequestMessage ([System.Net.Http.HttpMethod]::Get), "$BaseUrlB/api/v1/runs/$Run/events"
    $request.Headers.TryAddWithoutValidation('Last-Event-ID', $Cursor) | Out-Null
    $response = $client.SendAsync($request, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
    try { return [int]$response.StatusCode } finally { $response.Dispose(); $request.Dispose() }
}

$runId = $ReplayRunId
try {
    if (-not $SkipRedisContractTests) {
        if (-not $MavenCommand) {
            $maven = Get-Command mvn -ErrorAction SilentlyContinue
            if ($maven) { $MavenCommand = $maven.Source }
            elseif (Test-Path 'D:\tools\maven\apache-maven-3.9.16\bin\mvn.cmd') { $MavenCommand = 'D:\tools\maven\apache-maven-3.9.16\bin\mvn.cmd' }
            else { throw 'Set -MavenCommand to an existing local Maven executable' }
        }
        & $MavenCommand -q -s script/maven-settings.xml '-Dtest=RedisRunEventBusIntegrationTest' '-Daiflow.redis.integration=true' "-Daiflow.redis.host=$RedisHost" "-Daiflow.redis.port=$RedisPort" test
        Check 'realRedisContractSuite' ($LASTEXITCODE -eq 0) 'RedisRunEventBusIntegrationTest (real Redis, 5 tests)'
    }
    foreach ($base in @($BaseUrlA, $BaseUrlB)) {
        $bus = Api $base 'GET' '/api/v1/runs/events/status'
        Check "redisMode:$base" ($bus.bus.mode -eq 'redis') $bus.bus.mode
        $capabilities = Api $base 'GET' '/api/v1/capabilities'
        $chat = @($capabilities.channels | Where-Object { $_.channel -eq 'chat' })[0]
        Check "mockProvider:$base" ($chat.activeProvider -eq 'mock') $chat.activeProvider
    }
    if (-not $runId) {
        $stamp = [Guid]::NewGuid().ToString('N')
        $user = Api $BaseUrlA 'POST' '/api/v1/users' @{ externalId = "redis-events-$stamp"; displayName = 'Redis event validation' }
        $session = Api $BaseUrlA 'POST' '/api/v1/sessions' @{ userId = $user.id; title = 'Redis events'; workflowName = 'basic-chat' }
        $accepted = Api $BaseUrlA 'POST' "/api/v1/sessions/$($session.id)/messages" @{ clientMsgId = "redis-events-$stamp"; content = 'synthetic redis event check' }
        $runId = $accepted.runId
    }
    $all = @(ReadEvents $BaseUrlB $runId 0)
    Check 'instanceBReceivesCompleteRun' ($all.Count -gt 0 -and $all[0].seq -eq 1) @{ runId = $runId; count = $all.Count }
    $expected = 1L
    $ordered = $true
    foreach ($event in $all) { if ($event.seq -ne $expected) { $ordered = $false }; $expected++ }
    Check 'fullReplayHasContiguousNumericIds' $ordered @{ first = $all[0].seq; last = $all[-1].seq }
    $cursor = [long][Math]::Floor($all.Count / 2)
    $remaining = @(ReadEvents $BaseUrlA $runId $cursor)
    Check 'reconnectToOtherInstanceHasNoDuplicateOrGap' ($remaining.Count -eq $all.Count - $cursor -and $remaining[0].seq -eq $cursor + 1) @{ cursor = $cursor; count = $remaining.Count }
    Check 'malformedCursor400' ((CursorStatus $runId 'invalid') -eq 400) 'invalid Last-Event-ID'
    Check 'negativeCursor400' ((CursorStatus $runId '-1') -eq 400) 'negative Last-Event-ID'
    Check 'futureCursor400' ((CursorStatus $runId ([string]($all[-1].seq + 1))) -eq 400) 'cursor > latest'
    $snapshot = Api $BaseUrlB 'GET' "/api/v1/runs/$runId"
    Check 'postgresSnapshotMatchesTerminalEvent' ($snapshot.status -in @('SUCCEEDED', 'DEGRADED') -and $all[-1].type -eq ('RUN_' + $snapshot.status)) $snapshot.status
    if ($ReplayRunId) { Check 'persistedRunReplayAfterExternalRestart' $true $ReplayRunId }
} finally {
    $client.Dispose()
    $parent = Split-Path -Parent $OutFile
    if ($parent) { [IO.Directory]::CreateDirectory([IO.Path]::GetFullPath($parent)) | Out-Null }
    $report = [pscustomobject]@{ at = [DateTime]::UtcNow.ToString('o'); runId = $runId; baseUrlA = $BaseUrlA; baseUrlB = $BaseUrlB; redisContractTestsSkipped = [bool]$SkipRedisContractTests; checks = $checks.ToArray() }
    [IO.File]::WriteAllText([IO.Path]::GetFullPath($OutFile), ($report | ConvertTo-Json -Depth 12), [Text.UTF8Encoding]::new($false))
}
Write-Output "Report: $OutFile"
