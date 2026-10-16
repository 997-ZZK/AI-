<#
.SYNOPSIS
  Read-only local load metrics sampler for M4-3C.
.NOTES
  Writes raw Prometheus samples and numeric observations under target/m4-3c.
  Redis INFO and PostgreSQL statistics snapshots contain no credentials or message rows.
  Run concurrently with an explicitly configured mock validation engine.
#>
param(
    [string]$BaseUrl = 'http://localhost:8082',
    [int]$Seconds = 30,
    [int]$IntervalMs = 1000,
    [string]$OutDir = 'target/m4-3c/s1-metrics'
)

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$reportRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'target/m4-3c'))
$outputRoot = if ([IO.Path]::IsPathRooted($OutDir)) {
    [IO.Path]::GetFullPath($OutDir)
} else {
    [IO.Path]::GetFullPath((Join-Path $repoRoot $OutDir))
}
$rootPrefix = $reportRoot.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if ($outputRoot -ne $reportRoot -and -not $outputRoot.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'OutDir must be under the repository target/m4-3c directory'
}
if ($Seconds -lt 1 -or $Seconds -gt 3600 -or $IntervalMs -lt 100 -or $IntervalMs -gt 60000) {
    throw 'Use Seconds 1-3600 and IntervalMs 100-60000'
}
$origin = $null
if (-not [Uri]::TryCreate($BaseUrl, [UriKind]::Absolute, [ref]$origin) -or
    $origin.Scheme -ne 'http' -or $origin.Host -notin @('localhost', '127.0.0.1') -or
    $origin.UserInfo -or $origin.Query -or $origin.Fragment -or $origin.AbsolutePath -ne '/') {
    throw 'BaseUrl must be an HTTP localhost/127.0.0.1 origin without credentials or a path'
}
$BaseUrl = $origin.GetLeftPart([UriPartial]::Authority)

# Reject existing junctions/symlinks before creating output directories.
$pathToCheck = $outputRoot
while ($pathToCheck -and $pathToCheck.StartsWith($repoRoot, [StringComparison]::OrdinalIgnoreCase)) {
    if (Test-Path -LiteralPath $pathToCheck) {
        $item = Get-Item -LiteralPath $pathToCheck -Force
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'OutDir must not traverse a junction or symbolic link'
        }
    }
    if ($pathToCheck -eq $repoRoot) { break }
    $pathToCheck = Split-Path -Parent $pathToCheck
}
[IO.Directory]::CreateDirectory($outputRoot) | Out-Null
$utf8 = New-Object Text.UTF8Encoding($false)
$runId = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$samplesFile = Join-Path $outputRoot 'samples.ndjson'
$startedAt = [DateTime]::UtcNow
$httpFailures = 0
$snapshotFailures = 0
$successfulSamples = 0
$failureCategory = $null

function Write-JsonFile([string]$Name, [object]$Value) {
    $path = Join-Path $outputRoot $Name
    [IO.File]::WriteAllText($path, ($Value | ConvertTo-Json -Depth 12), $utf8)
}

function Append-Sample([object]$Value) {
    $json = ($Value | ConvertTo-Json -Depth 10 -Compress) + [Environment]::NewLine
    [IO.File]::AppendAllText($samplesFile, $json, $utf8)
}

function Get-EnvironmentSnapshot {
    $result = [ordered]@{ capturedAt = [DateTime]::UtcNow.ToString('o'); hardwareStatus = 'available' }
    try {
        $os = Get-CimInstance Win32_OperatingSystem
        $cpu = @(Get-CimInstance Win32_Processor | Select-Object Name, NumberOfCores, NumberOfLogicalProcessors)
        $result.operatingSystem = [ordered]@{
            caption = $os.Caption
            version = $os.Version
            totalVisibleMemoryBytes = [long]$os.TotalVisibleMemorySize * 1024
            freePhysicalMemoryBytes = [long]$os.FreePhysicalMemory * 1024
        }
        $result.processors = $cpu
    } catch {
        $result.hardwareStatus = 'unavailable'
    }
    return $result
}

function Get-InfrastructureSnapshot([string]$Phase) {
    $snapshot = [ordered]@{
        runId = $runId
        phase = $Phase
        capturedAt = [DateTime]::UtcNow.ToString('o')
        redis = [ordered]@{}
        postgres = [ordered]@{}
    }
    foreach ($section in @('stats', 'memory', 'clients', 'server')) {
        try {
            $lines = @(& docker exec aiflow-redis redis-cli INFO $section 2>$null)
            if ($LASTEXITCODE -ne 0) { throw 'Redis INFO command failed' }
            $values = [ordered]@{}
            foreach ($line in $lines) {
                if ($line -match '^([a-zA-Z0-9_]+):(.*)$') { $values[$matches[1]] = $matches[2].TrimEnd("`r") }
            }
            if ($values.Count -eq 0) { throw 'Redis INFO returned no fields' }
            $snapshot.redis[$section] = [ordered]@{ status = 'available'; fields = $values }
        } catch {
            $script:snapshotFailures++
            $snapshot.redis[$section] = [ordered]@{ status = 'unavailable' }
        }
    }
    $queries = [ordered]@{
        activity = "SELECT json_build_object('connections', count(*), 'active', count(*) FILTER (WHERE state='active'), 'idle', count(*) FILTER (WHERE state='idle'), 'idleInTransaction', count(*) FILTER (WHERE state='idle in transaction'), 'waiting', count(*) FILTER (WHERE wait_event_type IS NOT NULL))::text FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid()"
        database = "SELECT json_build_object('numbackends', numbackends, 'xactCommit', xact_commit, 'xactRollback', xact_rollback, 'blksRead', blks_read, 'blksHit', blks_hit, 'tupReturned', tup_returned, 'tupFetched', tup_fetched, 'tupInserted', tup_inserted, 'tupUpdated', tup_updated, 'tupDeleted', tup_deleted, 'conflicts', conflicts, 'tempFiles', temp_files, 'tempBytes', temp_bytes, 'deadlocks', deadlocks, 'blkReadTimeMs', blk_read_time, 'blkWriteTimeMs', blk_write_time, 'statsReset', stats_reset)::text FROM pg_stat_database WHERE datname=current_database()"
    }
    foreach ($entry in $queries.GetEnumerator()) {
        try {
            # Transaction read-only mode and a 2 second statement timeout apply only to this psql process.
            $lines = @(& docker exec -e 'PGOPTIONS=-c default_transaction_read_only=on -c statement_timeout=2000' aiflow-postgres psql -X -U aiflow -d aiflow -At -c $entry.Value 2>$null)
            if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL statistics command failed' }
            $text = ($lines -join '').Trim()
            if (-not $text) { throw 'PostgreSQL statistics returned no row' }
            $snapshot.postgres[$entry.Key] = [ordered]@{ status = 'available'; statistics = ($text | ConvertFrom-Json) }
        } catch {
            $script:snapshotFailures++
            $snapshot.postgres[$entry.Key] = [ordered]@{ status = 'unavailable' }
        }
    }
    Write-JsonFile ("{0}-{1}-infrastructure.json" -f $runId, $Phase) $snapshot
}

function Read-NumericMetrics([string]$PrometheusText) {
    $result = New-Object System.Collections.Generic.List[object]
    $numberPattern = '[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?'
    $samplePattern = '^(?<name>[a-zA-Z_:][a-zA-Z0-9_:]*)(?<labels>\{.*\})?\s+(?<value>' + $numberPattern + ')(?:\s+[0-9]+)?\s*$'
    foreach ($line in ($PrometheusText -split '\r?\n')) {
        if ($line -notmatch $samplePattern) { continue }
        $name = $matches['name']
        $sampleValue = $matches['value']
        $labels = $matches['labels']
        $selected = $name -match '^aiflow_(?:dispatch|events|sse)(?:_|$)' -or
            $name -match '^jvm_memory_(?:used|max)_bytes$' -or
            $name -match '^jvm_gc_pause_seconds(?:_|$)' -or
            $name -eq 'process_cpu_usage' -or
            $name -match '^hikaricp_connections_(?:active|pending|timeout(?:s)?)(?:_|$)'
        if (-not $selected) { continue }
        $value = [double]::Parse($sampleValue, [Globalization.CultureInfo]::InvariantCulture)
        if ([double]::IsNaN($value) -or [double]::IsInfinity($value)) { continue }
        # These metric families carry infrastructure labels only, never request/message bodies.
        $result.Add([pscustomobject]@{ name = $name; labels = $labels; value = $value })
    }
    return ,$result.ToArray()
}

try {
    Write-JsonFile ("{0}-environment.json" -f $runId) ([ordered]@{
        runId = $runId
        baseUrl = $BaseUrl
        seconds = $Seconds
        intervalMs = $IntervalMs
        startedAt = $startedAt.ToString('o')
        environment = Get-EnvironmentSnapshot
    })
    Get-InfrastructureSnapshot 'start'
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $index = 0
    while ($timer.Elapsed.TotalSeconds -lt $Seconds) {
        $sampleStarted = $timer.ElapsedMilliseconds
        $capturedAt = [DateTime]::UtcNow.ToString('o')
        try {
            # Disable redirects so a local origin cannot redirect the sampler off-host.
            $response = Invoke-WebRequest -UseBasicParsing -Method Get -Uri ($BaseUrl + '/actuator/prometheus') -TimeoutSec 5 -MaximumRedirection 0
            if ([int]$response.StatusCode -ne 200) { throw 'Prometheus HTTP status was not 200' }
            $raw = [Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())
            $rawFile = '{0}-sample-{1:D6}.prom' -f $runId, $index
            [IO.File]::WriteAllText((Join-Path $outputRoot $rawFile), $raw, $utf8)
            $metrics = Read-NumericMetrics $raw
            foreach ($required in @('aiflow_dispatch_queued','aiflow_dispatch_running','aiflow_events_readers',
                    'aiflow_sse_connections','jvm_memory_used_bytes','hikaricp_connections_active','process_cpu_usage')) {
                if (@($metrics | Where-Object {$_.name -eq $required}).Count -eq 0) { throw 'Required numeric metrics are missing' }
            }
            Append-Sample ([ordered]@{
                runId = $runId
                index = $index
                capturedAt = $capturedAt
                elapsedMs = $sampleStarted
                httpStatus = [int]$response.StatusCode
                durationMs = $timer.ElapsedMilliseconds - $sampleStarted
                rawFile = $rawFile
                metrics = $metrics
            })
            $successfulSamples++
        } catch {
            $httpFailures++
            $failureCategory = 'prometheusSampleFailed'
            $status = $null
            if ($null -ne $_.Exception.Response) {
                try { $status = [int]$_.Exception.Response.StatusCode } catch { }
            }
            Append-Sample ([ordered]@{
                runId = $runId
                index = $index
                capturedAt = $capturedAt
                elapsedMs = $sampleStarted
                httpStatus = $status
                failed = $true
                failureCategory = $failureCategory
            })
            break
        }
        $index++
        $remainingMs = [long]($Seconds * 1000) - $timer.ElapsedMilliseconds
        $sleepMs = [Math]::Min($IntervalMs - ($timer.ElapsedMilliseconds - $sampleStarted), $remainingMs)
        if ($sleepMs -gt 0) { Start-Sleep -Milliseconds ([int]$sleepMs) }
    }
} catch {
    $failureCategory = 'samplerFailed'
} finally {
    Get-InfrastructureSnapshot 'end'
    $finishedAt = [DateTime]::UtcNow
    $passed = $httpFailures -eq 0 -and $snapshotFailures -eq 0 -and $successfulSamples -gt 0 -and $null -eq $failureCategory
    Write-JsonFile ("{0}-summary.json" -f $runId) ([ordered]@{
        runId = $runId
        startedAt = $startedAt.ToString('o')
        finishedAt = $finishedAt.ToString('o')
        successfulSamples = $successfulSamples
        httpFailures = $httpFailures
        snapshotFailures = $snapshotFailures
        failureCategory = $failureCategory
        passed = $passed
        samplesFile = 'samples.ndjson'
    })
}
Write-Output ("Metrics sampler: {0} samples, {1} HTTP failures, {2} snapshot failures; reports: {3}" -f $successfulSamples, $httpFailures, $snapshotFailures, $outputRoot)
if (-not $passed) { exit 1 }
exit 0
