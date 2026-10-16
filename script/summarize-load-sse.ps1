<#
.SYNOPSIS
  Recheck S3 idle SSE evidence, sampler artifacts and final local engine state.
.NOTES
  Only two local GET requests are made. No load, database or Redis command runs.
  Ten connections are script smoke evidence, never 1000-connection acceptance.
#>
param(
    [Parameter(Mandatory=$true)][string]$EvidenceFile,
    [Parameter(Mandatory=$true)][string]$MetricsDir,
    [Parameter(Mandatory=$true)][ValidateSet(10,1000)][int]$ExpectedConnections,
    [Parameter(Mandatory=$true)][ValidateSet('same-run','different-runs')][string]$Topology,
    [Parameter(Mandatory=$true)][string]$OutFile,
    [string]$BaseUrl = 'http://localhost:8082'
)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$reportRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'target/m4-3c'))
$rootPrefix = $reportRoot.TrimEnd([char[]]@('\','/')) + [IO.Path]::DirectorySeparatorChar

function Report-Path([string]$Path) {
    $absolute = if ([IO.Path]::IsPathRooted($Path)) { [IO.Path]::GetFullPath($Path) } else { [IO.Path]::GetFullPath((Join-Path $repoRoot $Path)) }
    if (-not $absolute.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Paths must be under target/m4-3c' }
    $parent = $absolute
    while ($parent -and $parent.StartsWith($repoRoot, [StringComparison]::OrdinalIgnoreCase)) {
        if (Test-Path -LiteralPath $parent) {
            if (((Get-Item -LiteralPath $parent -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Report paths must not traverse junctions or symbolic links' }
        }
        if ($parent -eq $repoRoot) { break }
        $parent = Split-Path -Parent $parent
    }
    return $absolute
}
function Read-Json([string]$Path) { return ConvertFrom-Json -InputObject ([IO.File]::ReadAllText($Path, [Text.Encoding]::UTF8)) }
function Read-Ndjson([string]$Path) {
    $rows = New-Object System.Collections.Generic.List[object]
    foreach ($line in [IO.File]::ReadAllLines($Path, [Text.Encoding]::UTF8)) {
        if (-not [string]::IsNullOrWhiteSpace($line)) { $rows.Add((ConvertFrom-Json -InputObject $line)) }
    }
    return ,$rows.ToArray()
}
function Is-True($Value) { return $Value -is [bool] -and $Value }
function Is-False($Value) { return $Value -is [bool] -and -not $Value }
function Is-Finite($Value) {
    if ($null -eq $Value -or $Value -is [bool] -or $Value -is [string]) { return $false }
    try { $number = [double]$Value; return -not ([double]::IsNaN($number) -or [double]::IsInfinity($number)) } catch { return $false }
}
function Is-Uuid($Value) {
    $id = [Guid]::Empty
    return [string]$Value -cmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' -and [Guid]::TryParseExact([string]$Value, 'D', [ref]$id)
}
function Check([string]$Name, [bool]$Passed, $Detail = $null) {
    $checks.Add([pscustomobject]@{ name = $Name; passed = $Passed; evidence = $Detail })
    if (-not $Passed) { $script:failed = $true }
}
function Peak($Samples, [string]$Name) {
    $values = New-Object System.Collections.Generic.List[double]
    foreach ($sample in $Samples) {
        $metrics = @($sample.metrics | Where-Object { $_.name -ceq $Name })
        if ($metrics.Count -gt 0) { $values.Add([double](($metrics | Measure-Object -Property value -Sum).Sum)) }
    }
    if ($values.Count -eq 0) { return $null }
    return ($values | Measure-Object -Maximum).Maximum
}
function Api([string]$Path) {
    $response = Invoke-WebRequest -UseBasicParsing -Method Get -Uri ($BaseUrl+$Path) -TimeoutSec 5 -MaximumRedirection 0
    if ([int]$response.StatusCode -ne 200) { throw 'Final state GET returned an unexpected status' }
    $body = ConvertFrom-Json -InputObject ([Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray()))
    if ($null -eq $body.data -or -not (Is-Finite $body.code) -or $body.code -ne 0) { throw 'Final state API contract invalid' }
    return $body.data
}

# Complete path guards before entering the finally block that writes the report.
$evidencePath = Report-Path $EvidenceFile
$metricsPath = Report-Path $MetricsDir
$outPath = Report-Path $OutFile
$metricsPrefix = $metricsPath.TrimEnd([char[]]@('\','/')) + [IO.Path]::DirectorySeparatorChar
if ([IO.Path]::GetExtension($outPath) -ne '.json' -or [IO.Path]::GetExtension($evidencePath) -ne '.ndjson' -or
    $outPath -eq $evidencePath -or $outPath.StartsWith($metricsPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Output must be a separate JSON report outside the metrics directory'
}
if (Test-Path -LiteralPath $outPath) { throw 'OutFile must be new; existing evidence and reports are retained' }
$checks = New-Object System.Collections.Generic.List[object]
$failed = $false
$report = [ordered]@{
    startedAt = [DateTime]::UtcNow.ToString('o'); stage = $(if ($Topology -ceq 'same-run') { 'S3a' } else { 'S3b' })
    topology = $Topology; expectedConnections = $ExpectedConnections; passed = $false; phase = 'parameters'
    scope = $(if ($ExpectedConnections -eq 10) { '10-connection script smoke only; does not establish 1000-connection capacity' } else { '1000-connection acceptance for the specified topology and validation environment' })
    sources = [ordered]@{ evidence = $evidencePath; metricsDirectory = $metricsPath }
}
try {
    $origin = $null
    if (-not [Uri]::TryCreate($BaseUrl, [UriKind]::Absolute, [ref]$origin) -or $origin.Scheme -ne 'http' -or
        $origin.Host -notin @('localhost','127.0.0.1') -or $origin.UserInfo -or $origin.Query -or $origin.Fragment -or $origin.AbsolutePath -ne '/') {
        throw 'BaseUrl must be a local HTTP origin without credentials or a path'
    }
    $BaseUrl = $origin.GetLeftPart([UriPartial]::Authority)
    $report.baseUrl = $BaseUrl
    $report.phase = 'client-evidence'
    $rows = Read-Ndjson $evidencePath
    Check 'client-record-count' ($rows.Count -eq $ExpectedConnections) @{ actual = $rows.Count; expected = $ExpectedConnections }
    Check 'client-identifiers-valid' (@($rows | Where-Object { -not (Is-Uuid $_.runId) -or -not (Is-Finite $_.vu) -or $_.vu -lt 1 -or $_.vu -ne [Math]::Floor($_.vu) }).Count -eq 0)
    Check 'client-vus-unique' (@($rows.vu | Sort-Object -Unique).Count -eq $rows.Count)
    Check 'client-all-successful-and-topology-matches' (@($rows | Where-Object { -not (Is-False $_.failed) -or $_.topology -cne $Topology }).Count -eq 0)
    Check 'client-confirmed-heartbeats-and-duration' (@($rows | Where-Object { -not (Is-Finite $_.pingCount) -or $_.pingCount -lt 4 -or $_.pingCount -ne [Math]::Floor($_.pingCount) -or -not (Is-Finite $_.observedMs) -or $_.observedMs -lt 60000 }).Count -eq 0)
    $runCount = @($rows.runId | Sort-Object -Unique).Count
    $expectedRuns = if ($Topology -ceq 'same-run') { 1 } else { $ExpectedConnections }
    Check 'client-run-topology-count' ($runCount -eq $expectedRuns) @{ actual = $runCount; expected = $expectedRuns }
    $rampRows = @($rows | Where-Object { $null -ne $_.PSObject.Properties['rampSeconds'] })
    $rampValues = @($rampRows.rampSeconds | Sort-Object -Unique)
    if ($rampRows.Count -gt 0) {
        Check 'client-recorded-injection-profile-consistent' ($rampRows.Count -eq $rows.Count -and $rampValues.Count -eq 1 -and
            (Is-Finite $rampValues[0]) -and $rampValues[0] -ge 0 -and $rampValues[0] -le 300 -and
            $rampValues[0] -eq [Math]::Floor($rampValues[0]))
    }
    $report.client = [ordered]@{
        records = $rows.Count; distinctRuns = $runCount
        rampSeconds = $(if ($rampValues.Count -eq 1) { $rampValues[0] } else { $null })
        injectionProfileRecorded = $rampRows.Count -eq $rows.Count
        minPingCount = ($rows | Measure-Object -Property pingCount -Minimum).Minimum
        minObservedMs = ($rows | Measure-Object -Property observedMs -Minimum).Minimum
        maxObservedMs = ($rows | Measure-Object -Property observedMs -Maximum).Maximum
        durationDefinition = 'last confirmed PING nanoTime minus first confirmed PING nanoTime; no time after the last confirmed PING is counted'
    }

    $report.phase = 'sampler-artifacts'
    $samples = Read-Ndjson (Join-Path $metricsPath 'samples.ndjson')
    $runIds = @($samples.runId | Sort-Object -Unique)
    Check 'one-complete-sampler-run' ($samples.Count -gt 1 -and $runIds.Count -eq 1)
    if ($samples.Count -lt 2 -or $runIds.Count -ne 1) { throw 'Metrics directory must contain exactly one sampler run' }
    $samplerId = [string]$runIds[0]
    if ($samplerId -cnotmatch '^[0-9]{8}T[0-9]{9}Z-[0-9a-f]{8}$') { throw 'Sampler run id invalid' }
    Check 'sampler-all-http-successful' (@($samples | Where-Object { $_.failed -eq $true -or $_.httpStatus -ne 200 }).Count -eq 0)
    $artifacts = [ordered]@{}
    foreach ($suffix in @('environment','start-infrastructure','end-infrastructure','summary')) {
        $path = Report-Path (Join-Path $metricsPath ($samplerId+'-'+$suffix+'.json'))
        $artifacts[$suffix] = $path
        Check ('sampler-artifact-present-'+$suffix) ([IO.File]::Exists($path))
        if (-not [IO.File]::Exists($path)) { continue }
        $artifact = Read-Json $path
        Check ('sampler-artifact-id-'+$suffix) ($artifact.runId -ceq $samplerId)
        if ($suffix -eq 'summary') {
            Check 'sampler-summary-no-failures' ((Is-True $artifact.passed) -and $artifact.httpFailures -eq 0 -and $artifact.snapshotFailures -eq 0 -and $artifact.successfulSamples -eq $samples.Count)
        } elseif ($suffix -eq 'environment') {
            Check 'sampler-origin-matches-final-state-origin' ($artifact.baseUrl -ceq $BaseUrl)
            $report.environment = $artifact.environment
        } else {
            $bad = @($artifact.redis.PSObject.Properties.Value | Where-Object { $_.status -cne 'available' }).Count + @($artifact.postgres.PSObject.Properties.Value | Where-Object { $_.status -cne 'available' }).Count
            Check ('sampler-infrastructure-available-'+$suffix) ($bad -eq 0 -and @($artifact.redis.PSObject.Properties).Count -eq 4 -and @($artifact.postgres.PSObject.Properties).Count -eq 2)
        }
    }
    $rawMissing = 0
    $requiredMetrics = @('aiflow_sse_connections','aiflow_events_subscriptions','aiflow_events_readers','aiflow_sse_overflow_total','aiflow_events_publish_failures_total','aiflow_events_read_failures_total')
    $numericMetricNames = @($requiredMetrics + 'aiflow_dispatch_lease_lost_total')
    $badMetricSamples = 0
    foreach ($sample in $samples) {
        if ([string]$sample.rawFile -cnotmatch ('^'+[regex]::Escape($samplerId)+'-sample-[0-9]{6}\.prom$')) { throw 'Raw sample filename invalid' }
        $rawPath = Report-Path (Join-Path $metricsPath $sample.rawFile)
        if (-not [IO.File]::Exists($rawPath)) { $rawMissing++ }
        if (@($requiredMetrics | Where-Object { $_ -cnotin $sample.metrics.name }).Count -gt 0 -or
            @($sample.metrics | Where-Object { $_.name -cin $numericMetricNames -and (-not (Is-Finite $_.value) -or $_.value -lt 0) }).Count -gt 0) { $badMetricSamples++ }
    }
    Check 'sampler-original-prometheus-files-present' ($rawMissing -eq 0) @{ missing = $rawMissing }
    Check 'sampler-required-metrics-finite-and-present' ($badMetricSamples -eq 0) @{ invalidSamples = $badMetricSamples }
    $connectionsPeak = Peak $samples 'aiflow_sse_connections'
    $subscriptionsPeak = Peak $samples 'aiflow_events_subscriptions'
    $readersPeak = Peak $samples 'aiflow_events_readers'
    Check 'sampled-connections-reach-scale' ($null -ne $connectionsPeak -and $connectionsPeak -ge $ExpectedConnections) $connectionsPeak
    Check 'sampled-subscriptions-reach-scale' ($null -ne $subscriptionsPeak -and $subscriptionsPeak -ge $ExpectedConnections) $subscriptionsPeak
    Check 'sampled-readers-match-topology' ($null -ne $readersPeak -and $(if ($Topology -ceq 'same-run') { $readersPeak -eq 1 } else { $readersPeak -ge $ExpectedConnections })) $readersPeak
    $samplesAtScale = 0
    $firstScaleSampleAt = $null
    foreach ($sample in $samples) {
        $currentConnections = Peak $sample 'aiflow_sse_connections'
        $currentSubscriptions = Peak $sample 'aiflow_events_subscriptions'
        $currentReaders = Peak $sample 'aiflow_events_readers'
        $readersAtScale = if ($Topology -ceq 'same-run') { $currentReaders -eq 1 } else { $currentReaders -ge $ExpectedConnections }
        if ($currentConnections -ge $ExpectedConnections -and $currentSubscriptions -ge $ExpectedConnections -and $readersAtScale) {
            $samplesAtScale++
            if ($null -eq $firstScaleSampleAt) { $firstScaleSampleAt = $sample.capturedAt }
        }
    }
    Check 'one-sample-simultaneously-reaches-topology-scale' ($samplesAtScale -gt 0) @{ matchingSamples = $samplesAtScale; firstAt = $firstScaleSampleAt }
    foreach ($name in @('aiflow_sse_overflow_total','aiflow_events_publish_failures_total','aiflow_events_read_failures_total')) {
        Check ('sampled-zero-'+$name) ((Peak $samples $name) -eq 0) (Peak $samples $name)
    }
    $leaseLostPeak = Peak $samples 'aiflow_dispatch_lease_lost_total'
    Check 'sampled-lease-lost-zero-if-exported' ($null -eq $leaseLostPeak -or $leaseLostPeak -eq 0) @{ exported = $null -ne $leaseLostPeak; peak = $leaseLostPeak }
    $report.sampler = [ordered]@{
        runId = $samplerId; samples = $samples.Count; artifacts = $artifacts
        connectionsPeak = $connectionsPeak; subscriptionsPeak = $subscriptionsPeak; readersPeak = $readersPeak
        samplesSimultaneouslyAtScale = $samplesAtScale; firstScaleSampleAt = $firstScaleSampleAt
        leaseLostPeak = $leaseLostPeak
        scope = 'one explicitly supplied complete sampler run; peaks are sampled and short-lived peaks may be missed'
    }

    $report.phase = 'final-engine-state'
    $events = Api '/api/v1/runs/events/status'
    $dispatch = Api '/api/v1/runs/dispatch/status'
    $stateNumbers = @($events.connectionCount,$events.sseOverflow,$events.bus.publishFailures,$events.bus.readFailures,$events.bus.readerCount,$events.bus.subscriptionCount,$dispatch.activeWorkers,$dispatch.leaseLost)
    Check 'final-state-contract-fields-present' (@($stateNumbers | Where-Object { -not (Is-Finite $_) -or $_ -lt 0 }).Count -eq 0 -and $null -ne $events.bus -and $events.bus.mode -ceq 'redis' -and (Is-True $dispatch.enabled) -and (Is-True $dispatch.workerEnabled))
    Check 'final-connections-subscriptions-readers-zero' ($events.connectionCount -eq 0 -and $events.bus.subscriptionCount -eq 0 -and $events.bus.readerCount -eq 0)
    Check 'final-events-failures-overflow-zero' ($events.sseOverflow -eq 0 -and $events.bus.publishFailures -eq 0 -and $events.bus.readFailures -eq 0)
    Check 'final-workers-idle-and-lease-lost-zero' ($dispatch.activeWorkers -eq 0 -and $dispatch.leaseLost -eq 0)
    $report.finalState = [ordered]@{
        capturedAt = [DateTime]::UtcNow.ToString('o')
        events = $events | Select-Object connectionCount,sseOverflow,@{Name='bus';Expression={$_.bus | Select-Object mode,publishFailures,readFailures,readerCount,subscriptionCount}}
        dispatch = $dispatch | Select-Object enabled,workerEnabled,activeWorkers,claimed,recovered,leaseLost
        scope = 'fresh local status after this run; failure and lease counters are cumulative for the engine process'
    }
    $report.phase = 'complete'
} catch {
    $failed = $true
    $report.failureKind = $_.Exception.GetType().Name
    Check ('summary-exception-'+$report.phase) $false @{ kind = $report.failureKind }
} finally {
    $report.finishedAt = [DateTime]::UtcNow.ToString('o')
    $report.passed = -not $failed
    $report.thousandConnectionAcceptance = [ordered]@{ attempted = $ExpectedConnections -eq 1000; passed = $(if ($ExpectedConnections -eq 1000) { -not $failed } else { $null }) }
    $report.checks = $checks.ToArray()
    [IO.Directory]::CreateDirectory((Split-Path -Parent $outPath)) | Out-Null
    [IO.File]::WriteAllText($outPath, ($report | ConvertTo-Json -Depth 16), (New-Object Text.UTF8Encoding($false)))
}
Write-Output ('SSE summary saved: '+$outPath)
if ($failed) { throw 'SSE summary checks failed; inspect the saved JSON report.' }
