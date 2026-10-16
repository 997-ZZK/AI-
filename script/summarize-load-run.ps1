<#
.SYNOPSIS
  Read-only S1/S2 load evidence and server metrics summary.
.NOTES
  Percentiles use nearest rank. Run throughput uses the first submit through the
  last terminal event, never Gatling's global HTTP requests per second.
  Counter deltas use one complete sampler run which brackets the load window;
  this wider scope is recorded explicitly, including warmup/cooldown samples.
  No API, database, Redis, Maven, or load process is invoked by this script.
#>
param(
    [Parameter(Mandatory=$true)][string]$EvidenceFile,
    [Parameter(Mandatory=$true)][string]$PgEvidenceFile,
    [Parameter(Mandatory=$true)][string]$EventsEvidenceFile,
    [Parameter(Mandatory=$true)][string]$MetricsDir,
    [Parameter(Mandatory=$true)][int]$ExpectedUsers,
    [Parameter(Mandatory=$true)][int]$ExpectedRounds,
    [string]$OutFile = 'target/m4-3c/s1-report.json'
)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$reportRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'target/m4-3c'))
$rootPrefix = $reportRoot.TrimEnd([char[]]@('\','/')) + [IO.Path]::DirectorySeparatorChar
$outPath = if ([IO.Path]::IsPathRooted($OutFile)) { [IO.Path]::GetFullPath($OutFile) } else { [IO.Path]::GetFullPath((Join-Path $repoRoot $OutFile)) }
if (-not $outPath.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetExtension($outPath) -ne '.json') {
    throw 'OutFile must be a JSON report under target/m4-3c'
}
$checks = New-Object System.Collections.Generic.List[object]
$summary = [ordered]@{ startedAt = [DateTime]::UtcNow.ToString('o'); stage = 'unknown'; passed = $false; phase = 'parameters' }
$failed = $false

function Check([string]$Name, [bool]$Passed, $Detail = $null) {
    $checks.Add([pscustomobject]@{ name = $Name; passed = $Passed; evidence = $Detail })
    if (-not $Passed) { $script:failed = $true }
}
function Resolve-ReportPath([string]$Path) {
    $absolute = if ([IO.Path]::IsPathRooted($Path)) { [IO.Path]::GetFullPath($Path) } else { [IO.Path]::GetFullPath((Join-Path $repoRoot $Path)) }
    if (-not $absolute.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Input must be under target/m4-3c' }
    return $absolute
}
function Assert-NoReparsePoint([string]$Path) {
    $pathToCheck = $Path
    while ($pathToCheck -and $pathToCheck.StartsWith($repoRoot, [StringComparison]::OrdinalIgnoreCase)) {
        if (Test-Path -LiteralPath $pathToCheck) {
            $item = Get-Item -LiteralPath $pathToCheck -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Report paths must not traverse a junction or symbolic link' }
        }
        if ($pathToCheck -eq $repoRoot) { break }
        $pathToCheck = Split-Path -Parent $pathToCheck
    }
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
function Unique-Count($Values) { return @($Values | Sort-Object -Unique).Count }
function Same-Ids($Left, $Right) {
    $leftIds = @($Left | Sort-Object)
    $rightIds = @($Right | Sort-Object)
    if ($leftIds.Count -ne $rightIds.Count) { return $false }
    for ($i = 0; $i -lt $leftIds.Count; $i++) { if ($leftIds[$i] -cne $rightIds[$i]) { return $false } }
    return $true
}
function Epoch-Ms($Timestamp) { return [DateTimeOffset]::Parse([string]$Timestamp, [Globalization.CultureInfo]::InvariantCulture).ToUnixTimeMilliseconds() }
function Percentiles($Values) {
    $sorted = @($Values | Sort-Object)
    if ($sorted.Count -eq 0) { return $null }
    $result = [ordered]@{ count = $sorted.Count; method = 'nearest-rank'; min = [double]$sorted[0]; max = [double]$sorted[-1] }
    foreach ($p in @(50,95,99)) { $result['p'+$p] = [double]$sorted[[int][Math]::Ceiling(($p/100.0)*$sorted.Count)-1] }
    return $result
}
function Sum-Peak($Samples, [string]$MetricName, [string]$LabelFilter) {
    $peaks = New-Object System.Collections.Generic.List[double]
    foreach ($sample in $Samples) {
        $metrics = @($sample.metrics | Where-Object { $_.name -ceq $MetricName -and [string]$_.labels -match $LabelFilter })
        if ($metrics.Count -gt 0) { $peaks.Add([double](($metrics | Measure-Object -Property value -Sum).Sum)) }
    }
    if ($peaks.Count -eq 0) { return $null }
    return ($peaks | Measure-Object -Maximum).Maximum
}
function Metric-Series($Samples, [long]$ClientWindowStart, [long]$ClientWindowEnd) {
    $series = @{}
    foreach ($sample in $Samples) {
        foreach ($metric in @($sample.metrics)) {
            if (-not (Is-Finite $metric.value)) { throw 'Metric contains a non-finite numeric value' }
            $name = [string]$metric.name
            if ($name -cnotmatch '^(aiflow_(dispatch|events|sse)_[a-z0-9_]+|jvm_memory_(used|max)_bytes|jvm_gc_pause_seconds(_[a-z]+)?|process_cpu_usage|hikaricp_connections_[a-z_]+)$') { continue }
            $labels = [string]$metric.labels
            $key = $name + '|' + $labels
            if (-not $series.ContainsKey($key)) { $series[$key] = New-Object System.Collections.Generic.List[object] }
            $series[$key].Add([pscustomobject]@{ name = $name; labels = $labels; value = [double]$metric.value; at = $sample.capturedAt })
        }
    }
    $result = New-Object System.Collections.Generic.List[object]
    foreach ($key in @($series.Keys | Sort-Object)) {
        $values = $series[$key]
        $name = $values[0].name
        $isCounter = $name -match '(_total|_count|_sum)$'
        $reset = $false
        if ($isCounter) {
            for ($i = 1; $i -lt $values.Count; $i++) { if ($values[$i].value -lt $values[$i-1].value) { $reset = $true } }
        }
        $result.Add([pscustomobject]@{
            name = $name; labels = $values[0].labels; samples = $values.Count
            firstAt = $values[0].at; lastAt = $values[-1].at
            coversClientWindowStart = (Epoch-Ms $values[0].at) -le $ClientWindowStart
            coversClientWindowEnd = (Epoch-Ms $values[-1].at) -ge $ClientWindowEnd
            first = $values[0].value; last = $values[-1].value
            peak = ($values | Measure-Object -Property value -Maximum).Maximum
            kind = $(if ($isCounter) { 'cumulative-counter' } else { 'gauge-or-timer-max' })
            delta = $(if ($isCounter -and -not $reset) { $values[-1].value - $values[0].value } else { $null })
            resetDetected = $reset
        })
    }
    return ,$result.ToArray()
}
function Sum-Deltas($Series, [string]$Name) {
    $items = @($Series | Where-Object { $_.name -ceq $Name })
    if ($items.Count -eq 0 -or @($items | Where-Object { $null -eq $_.delta }).Count -gt 0) { return $null }
    return ($items | Measure-Object -Property delta -Sum).Sum
}
function Peak($Series, [string]$Name) {
    $items = @($Series | Where-Object { $_.name -ceq $Name })
    if ($items.Count -eq 0) { return $null }
    return ($items | Measure-Object -Property peak -Maximum).Maximum
}

# Path safety must complete before the try/finally report writer is entered.
$evidencePath = Resolve-ReportPath $EvidenceFile
$pgPath = Resolve-ReportPath $PgEvidenceFile
$eventsPath = Resolve-ReportPath $EventsEvidenceFile
$metricsPath = Resolve-ReportPath $MetricsDir
$metricsPrefix = $metricsPath.TrimEnd([char[]]@('\','/')) + [IO.Path]::DirectorySeparatorChar
if (@($evidencePath,$pgPath,$eventsPath) -contains $outPath -or $outPath.StartsWith($metricsPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Output must not overwrite source evidence or metrics artifacts'
}
foreach ($path in @($evidencePath,$pgPath,$eventsPath,$metricsPath,$outPath)) { Assert-NoReparsePoint $path }

try {
    if ($ExpectedUsers -lt 1 -or $ExpectedRounds -lt 1 -or ([long]$ExpectedUsers*$ExpectedRounds) -gt 1000000) { throw 'Expected users/rounds are invalid' }
    $expectedRuns = [long]$ExpectedUsers*$ExpectedRounds
    $summary.expected = [ordered]@{ users = $ExpectedUsers; roundsPerSession = $ExpectedRounds; runs = $expectedRuns }
    $summary.sources = [ordered]@{ evidence = $evidencePath; postgres = $pgPath; events = $eventsPath; metricsDirectory = $metricsPath }
    $summary.phase = 'client-evidence'
    $rows = Read-Ndjson $evidencePath
    $stages = @($rows.stage | Sort-Object -Unique)
    Check 'client-one-supported-stage' ($stages.Count -eq 1 -and $stages[0] -cin @('s1','s2')) $stages
    if ($stages.Count -ne 1 -or $stages[0] -cnotin @('s1','s2')) { throw 'Client evidence must contain exactly one supported S1/S2 stage' }
    $summary.stage = ([string]$stages[0]).ToUpperInvariant()
    $maxRunMs = if ($summary.stage -ceq 'S2') { 300000 } else { 120000 }
    Check 'client-record-count' ($rows.Count -eq $expectedRuns) @{ actual = $rows.Count; expected = $expectedRuns }
    Check 'client-all-successful' (@($rows | Where-Object { -not (Is-False $_.failed) -or $_.terminalType -cne 'RUN_SUCCEEDED' -or $_.terminalStatus -cne 'SUCCEEDED' -or $_.snapshotStatus -cne 'SUCCEEDED' }).Count -eq 0)
    Check 'client-identifiers-valid' (@($rows | Where-Object { -not (Is-Uuid $_.runId) -or -not (Is-Uuid $_.sessionId) -or -not (Is-Uuid $_.userId) -or [string]$_.clientMsgId -cnotmatch '^[A-Za-z0-9-]{1,100}$' }).Count -eq 0)
    Check 'client-run-ids-unique' ((Unique-Count $rows.runId) -eq $rows.Count)
    Check 'client-message-ids-unique' ((Unique-Count $rows.clientMsgId) -eq $rows.Count)
    Check 'client-users-and-sessions-count' ((Unique-Count $rows.userId) -eq $ExpectedUsers -and (Unique-Count $rows.sessionId) -eq $ExpectedUsers)
    $timingBad = 0
    $maxEpoch = [DateTimeOffset]::UtcNow.AddMinutes(5).ToUnixTimeMilliseconds()
    foreach ($row in $rows) {
        $valid = (Is-Finite $row.ttftMs) -and (Is-Finite $row.runMs) -and (Is-Finite $row.submittedEpochMs) -and (Is-Finite $row.terminalEpochMs)
        if ($valid) {
            # S1 allows two 30 second checks; S2 allows two 120 second checks, plus setup allowance.
            $valid = $row.ttftMs -ge 0 -and $row.runMs -ge $row.ttftMs -and $row.runMs -le $maxRunMs -and
                $row.submittedEpochMs -ge 946684800000 -and $row.terminalEpochMs -le $maxEpoch -and $row.terminalEpochMs -ge $row.submittedEpochMs -and
                [Math]::Abs(($row.terminalEpochMs-$row.submittedEpochMs)-$row.runMs) -le 1000
        }
        if (-not $valid) { $timingBad++ }
    }
    Check 'client-timings-finite-and-consistent' ($timingBad -eq 0) @{ invalid = $timingBad; monotonicVsWallClockToleranceMs = 1000; maxRunMs = $maxRunMs }
    $sessions = @($rows | Group-Object sessionId)
    $sessionBad = 0
    foreach ($session in $sessions) {
        $ordered = @($session.Group | Sort-Object round)
        $valid = $ordered.Count -eq $ExpectedRounds -and (Unique-Count $ordered.userId) -eq 1 -and (Unique-Count $ordered.vu) -eq 1
        for ($i = 0; $i -lt $ordered.Count; $i++) {
            if (-not (Is-Finite $ordered[$i].round) -or $ordered[$i].round -ne $i+1) { $valid = $false }
            if ($i -gt 0 -and $ordered[$i].submittedEpochMs -lt $ordered[$i-1].terminalEpochMs) { $valid = $false }
        }
        if (-not $valid) { $sessionBad++ }
    }
    Check 'client-each-session-rounds-one-through-N' ($sessionBad -eq 0) @{ invalidSessions = $sessionBad }
    Check 'client-one-synthetic-prefix' ((Unique-Count $rows.prefix) -eq 1)
    if ($rows.Count -eq 0 -or $timingBad -gt 0) { throw 'Client timing window unavailable' }
    $windowStart = [long](($rows | Measure-Object -Property submittedEpochMs -Minimum).Minimum)
    $windowEnd = [long](($rows | Measure-Object -Property terminalEpochMs -Maximum).Maximum)
    $durationSeconds = ($windowEnd-$windowStart)/1000.0
    Check 'run-throughput-window-positive' ($durationSeconds -gt 0)
    $summary.client = [ordered]@{
        records = $rows.Count
        successfulRuns = @($rows | Where-Object { Is-False $_.failed }).Count
        ttftMs = Percentiles $rows.ttftMs
        runMs = Percentiles $rows.runMs
        window = [ordered]@{ firstSubmitEpochMs = $windowStart; lastTerminalEpochMs = $windowEnd; seconds = $durationSeconds }
        completedRunsPerSecond = $(if ($durationSeconds -gt 0) { $rows.Count/$durationSeconds } else { $null })
        throughputDefinition = 'completed streamed runs / (max terminalEpochMs - min submittedEpochMs); excludes global HTTP request rate'
    }

    $summary.phase = 'postgres-and-event-evidence'
    $pg = Read-Json $pgPath
    $events = Read-Json $eventsPath
    $pgRuns = @($pg.evidence.database.runs)
    $tasks = @($pg.evidence.database.tasks)
    $eventRuns = @($events.runs)
    Check 'postgres-report-passed' (Is-True $pg.passed)
    Check 'events-report-passed' (Is-True $events.passed)
    Check 'postgres-prefix-matches-client' ($pg.prefix -ceq $rows[0].prefix)
    Check 'postgres-run-uuids-align' ((Same-Ids $rows.runId $pgRuns.runId) -and (Unique-Count $pgRuns.runId) -eq $expectedRuns)
    Check 'postgres-task-run-uuids-align' ((Same-Ids $rows.runId $tasks.runId) -and (Unique-Count $tasks.taskId) -eq $expectedRuns)
    Check 'event-run-uuids-align' ((Same-Ids $rows.runId $eventRuns.runId) -and (Unique-Count $eventRuns.runId) -eq $expectedRuns)
    Check 'postgres-and-event-uuids-valid' (@($pgRuns | Where-Object { -not (Is-Uuid $_.runId) -or -not (Is-Uuid $_.sessionId) }).Count -eq 0 -and
        @($tasks | Where-Object { -not (Is-Uuid $_.taskId) -or -not (Is-Uuid $_.runId) -or -not (Is-Uuid $_.sessionId) }).Count -eq 0 -and
        @($eventRuns | Where-Object { -not (Is-Uuid $_.runId) }).Count -eq 0)
    Check 'events-each-run-passed' (@($eventRuns | Where-Object { -not (Is-True $_.passed) -or $_.terminalType -cne 'RUN_SUCCEEDED' -or $_.terminalStatus -cne 'SUCCEEDED' -or $_.terminalCount -ne 1 -or -not (Is-True $_.nonemptyDelta) }).Count -eq 0)
    $taskBad = 0
    $orderBad = 0
    $overlaps = 0
    foreach ($session in $sessions) {
        $ordered = @($session.Group | Sort-Object round)
        $sessionTasks = @($tasks | Where-Object { $_.sessionId -ceq $session.Name } | Sort-Object seq)
        if ($ordered.Count -ne $sessionTasks.Count) { $orderBad++; continue }
        $previousEnd = $null
        $previousSeq = $null
        for ($i = 0; $i -lt $ordered.Count; $i++) {
            $row = $ordered[$i]
            $task = $sessionTasks[$i]
            $matchingPgRun = @($pgRuns | Where-Object { $_.runId -ceq $row.runId -and $_.sessionId -ceq $row.sessionId -and $_.status -ceq 'SUCCEEDED' })
            if ($task.runId -cne $row.runId -or $task.clientMsgId -cne $row.clientMsgId -or $matchingPgRun.Count -ne 1) { $orderBad++ }
            $validTask = (Is-Finite $task.seq) -and $task.seq -gt 0 -and $task.seq -eq [Math]::Floor($task.seq) -and
                $task.kind -ceq 'PROCESS' -and $task.status -ceq 'DONE' -and $task.runStatus -ceq 'SUCCEEDED' -and (Is-False $task.overlap)
            try {
                $start = Epoch-Ms $task.startedAt
                $end = Epoch-Ms $task.finishedAt
                if ($end -lt $start -or ($null -ne $previousSeq -and $task.seq -le $previousSeq)) { $validTask = $false }
                if ($null -ne $previousEnd -and $start -lt $previousEnd) { $overlaps++ }
                $previousEnd = $end
                $previousSeq = $task.seq
            } catch { $validTask = $false }
            if (-not $validTask) { $taskBad++ }
        }
    }
    Check 'postgres-task-round-client-message-alignment' ($orderBad -eq 0) @{ mismatches = $orderBad }
    Check 'postgres-tasks-valid-and-sequences-increasing' ($taskBad -eq 0 -and (Unique-Count $tasks.seq) -eq $expectedRuns) @{ invalidTasks = $taskBad }
    Check 'postgres-session-tasks-no-overlap' ($overlaps -eq 0 -and $pg.evidence.database.counts.overlappingTasks -eq 0) @{ computedOverlaps = $overlaps }
    $summary.correctness = [ordered]@{ postgresPassed = $pg.passed; eventsPassed = $events.passed; taskCount = $tasks.Count; eventRunCount = $eventRuns.Count; overlappingTasks = $overlaps }

    $summary.phase = 'metrics'
    $allSamples = Read-Ndjson (Join-Path $metricsPath 'samples.ndjson')
    $candidateRuns = New-Object System.Collections.Generic.List[object]
    foreach ($group in @($allSamples | Group-Object runId)) {
        $groupSamples = @($group.Group | Sort-Object { Epoch-Ms $_.capturedAt })
        if ($groupSamples.Count -gt 1 -and (Epoch-Ms $groupSamples[0].capturedAt) -le $windowStart -and (Epoch-Ms $groupSamples[-1].capturedAt) -ge $windowEnd) {
            $candidateRuns.Add([pscustomobject]@{ runId = $group.Name; samples = $groupSamples })
        }
    }
    Check 'one-sampler-run-brackets-client-window' ($candidateRuns.Count -eq 1) @{ candidates = $candidateRuns.Count }
    if ($candidateRuns.Count -ne 1) { throw 'Sampler window is missing or ambiguous' }
    $samplerRunId = [string]$candidateRuns[0].runId
    if ($samplerRunId -cnotmatch '^[0-9]{8}T[0-9]{9}Z-[0-9a-f]{8}$') { throw 'Sampler run id invalid' }
    $samples = @($candidateRuns[0].samples)
    $sampleFailures = @($samples | Where-Object { $_.failed -eq $true -or $_.httpStatus -ne 200 })
    Check 'metrics-all-samples-successful' ($sampleFailures.Count -eq 0) @{ failures = $sampleFailures.Count }
    foreach ($sample in $samples) {
        if ([string]$sample.rawFile -cnotmatch ('^'+[regex]::Escape($samplerRunId)+'-sample-[0-9]{6}\.prom$')) { throw 'Raw sample filename invalid' }
        Check ('raw-sample-present-'+[string]$sample.index) ([IO.File]::Exists((Join-Path $metricsPath $sample.rawFile)))
    }
    $series = Metric-Series $samples $windowStart $windowEnd
    Check 'metrics-no-cumulative-counter-reset' (@($series | Where-Object { $_.resetDetected }).Count -eq 0)
    $required = @('aiflow_dispatch_queued','aiflow_dispatch_running','aiflow_dispatch_queue_wait_seconds_count','aiflow_dispatch_queue_wait_seconds_sum',
        'aiflow_dispatch_queue_wait_seconds_max','aiflow_dispatch_lock_wait_seconds_count','aiflow_dispatch_lock_wait_seconds_sum','aiflow_dispatch_lock_wait_seconds_max',
        'aiflow_events_publish_failures_total','aiflow_events_read_failures_total','aiflow_sse_overflow_total','aiflow_sse_connections','hikaricp_connections_active',
        'aiflow_dispatch_pending_age_seconds','aiflow_dispatch_claimed_total','aiflow_dispatch_metrics_sample_failures_total','aiflow_events_readers','aiflow_events_subscriptions',
        'hikaricp_connections_pending','hikaricp_connections_timeout_total','jvm_memory_used_bytes','jvm_memory_max_bytes','jvm_gc_pause_seconds_count','jvm_gc_pause_seconds_sum','jvm_gc_pause_seconds_max','process_cpu_usage')
    $missing = @($required | Where-Object { $_ -cnotin $series.name })
    Check 'required-server-metrics-present' ($missing.Count -eq 0) $missing
    $zeroMetrics = @('aiflow_events_publish_failures_total','aiflow_events_read_failures_total','aiflow_sse_overflow_total')
    Check 'events-failures-and-overflow-zero' (@($series | Where-Object { $_.name -cin $zeroMetrics -and $_.peak -ne 0 }).Count -eq 0 -and @($zeroMetrics | Where-Object { $_ -cnotin $series.name }).Count -eq 0)
    $leaseLostSeries = @($series | Where-Object { $_.name -ceq 'aiflow_dispatch_lease_lost_total' })
    Check 'dispatch-lease-lost-zero-if-exported' (@($leaseLostSeries | Where-Object { $_.peak -ne 0 }).Count -eq 0) @{ exported = $leaseLostSeries.Count -gt 0; peak = Peak $series 'aiflow_dispatch_lease_lost_total' }
    Check 'dispatch-metric-sample-failures-zero' ((Peak $series 'aiflow_dispatch_metrics_sample_failures_total') -eq 0)
    Check 'database-connection-timeouts-zero' ((Peak $series 'hikaricp_connections_timeout_total') -eq 0)
    $timers = New-Object System.Collections.Generic.List[object]
    foreach ($timerName in @('aiflow_dispatch_queue_wait_seconds','aiflow_dispatch_lock_wait_seconds','jvm_gc_pause_seconds')) {
        $count = Sum-Deltas $series ($timerName+'_count')
        $sum = Sum-Deltas $series ($timerName+'_sum')
        $counterSeries = @($series | Where-Object { $_.name -cin @(($timerName+'_count'),($timerName+'_sum')) })
        $startCoverageGap = $counterSeries.Count -eq 0 -or @($counterSeries | Where-Object { -not $_.coversClientWindowStart }).Count -gt 0
        $timers.Add([pscustomobject]@{
            name = $timerName; countDelta = $count; sumSecondsDelta = $sum
            sampledMaxSeconds = Peak $series ($timerName+'_max')
            meanSeconds = $(if ($null -ne $count -and $count -gt 0) { $sum/$count } else { $null })
            clientStartBaselineGap = $startCoverageGap
            deltaScope = 'sum of last minus first exported value for each labeled counter series; only the firstAt..lastAt intervals are observed; missing earlier values are unknown, never assumed zero'
            registrationCoverage = $(if ($startCoverageGap) { 'first export after client start or series absent; registration/export baseline gap prevents interpreting delta as the full client run total' } else { 'counter samples begin at or before client start; delta still includes the wider sampler scope' })
            counterSeriesCoverage = @($counterSeries | Select-Object name,labels,firstAt,lastAt,first,last,coversClientWindowStart,coversClientWindowEnd)
        })
    }
    $snapshotFiles = [ordered]@{}
    foreach ($suffix in @('environment','start-infrastructure','end-infrastructure','summary')) {
        $path = Join-Path $metricsPath ($samplerRunId+'-'+$suffix+'.json')
        $snapshotFiles[$suffix] = $path
        Check ('sampler-artifact-present-'+$suffix) ([IO.File]::Exists($path))
        if (-not [IO.File]::Exists($path)) { continue }
        $artifact = Read-Json $path
        Check ('sampler-artifact-run-id-'+$suffix) ($artifact.runId -ceq $samplerRunId)
        if ($suffix -eq 'summary') { Check 'sampler-summary-passed' ((Is-True $artifact.passed) -and $artifact.httpFailures -eq 0 -and $artifact.snapshotFailures -eq 0 -and $artifact.successfulSamples -eq $samples.Count) }
        if ($suffix -eq 'environment') { Check 'sampler-base-url-aligns' ($artifact.baseUrl -ceq $pg.baseUrl); $summary.environment = $artifact.environment }
        if ($suffix -match 'infrastructure$') {
            $badInfra = @($artifact.redis.PSObject.Properties.Value | Where-Object { $_.status -cne 'available' }).Count + @($artifact.postgres.PSObject.Properties.Value | Where-Object { $_.status -cne 'available' }).Count
            Check ('sampler-infrastructure-available-'+$suffix) ($badInfra -eq 0 -and @($artifact.redis.PSObject.Properties).Count -eq 4 -and @($artifact.postgres.PSObject.Properties).Count -eq 2)
        }
    }
    $summary.serverMetrics = [ordered]@{
        scope = 'complete sampler run bracketing client load window; includes warmup/cooldown; counter deltas cannot be attributed only to the exact client window'
        samplerRunId = $samplerRunId; sampleCount = $samples.Count; firstAt = $samples[0].capturedAt; lastAt = $samples[-1].capturedAt
        insideClientWindowSamples = @($samples | Where-Object { (Epoch-Ms $_.capturedAt) -ge $windowStart -and (Epoch-Ms $_.capturedAt) -le $windowEnd }).Count
        heapUsedBytesSumPeak = Sum-Peak $samples 'jvm_memory_used_bytes' 'area="heap"'
        heapMaxBytesSumPeak = Sum-Peak $samples 'jvm_memory_max_bytes' 'area="heap"'
        processCpuUsagePeak = Peak $series 'process_cpu_usage'
        leaseLost = [ordered]@{ exported = $leaseLostSeries.Count -gt 0; peak = Peak $series 'aiflow_dispatch_lease_lost_total'; registrationLimit = 'registered lazily on the first lost lease; absent series is recorded as not exported' }
        database = [ordered]@{ activePeak = Sum-Peak $samples 'hikaricp_connections_active' '.*'; pendingPeak = Sum-Peak $samples 'hikaricp_connections_pending' '.*'; timeoutDelta = Sum-Deltas $series 'hikaricp_connections_timeout_total' }
        timers = $timers.ToArray(); series = $series; artifacts = $snapshotFiles
        gaugeLimit = 'peaks are sampled; short-lived peaks between scrapes may be missed; dispatch gauges refresh every 5 seconds'
        timerMaxLimit = 'Micrometer timer max is a rolling maximum, not a percentile or exact peak limited to the client window'
    }
    $summary.phase = 'complete'
} catch {
    $failed = $true
    $summary.failureKind = $_.Exception.GetType().Name
    Check ('summary-exception-'+$summary.phase) $false @{ kind = $summary.failureKind }
} finally {
    $summary.finishedAt = [DateTime]::UtcNow.ToString('o')
    $summary.passed = -not $failed
    $summary.checks = $checks.ToArray()
    [IO.Directory]::CreateDirectory((Split-Path -Parent $outPath)) | Out-Null
    [IO.File]::WriteAllText($outPath, ($summary | ConvertTo-Json -Depth 18), (New-Object Text.UTF8Encoding($false)))
}
Write-Output ('Load summary saved: '+$outPath)
if ($failed) { throw 'Load summary checks failed; inspect the saved JSON report.' }
