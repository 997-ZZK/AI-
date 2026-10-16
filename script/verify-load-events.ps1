<#
.SYNOPSIS
  Read-only Redis stream checks against the M4-3C PostgreSQL evidence report.
.NOTES
  Reads evidence.database.runs from verify-load-evidence.ps1 output.
  Only XRANGE is sent to the existing Redis container. No key is changed or deleted.
  Stream payloads are inspected in memory; reports never retain event/message text.
  Sequence is taken from Redis stream IDs; the stored event payload seq=0 is expected.
  Save with one UTF-8 BOM for Windows PowerShell 5.1.
#>
param(
    [Parameter(Mandatory=$true)][string]$PgEvidenceFile,
    [string]$OutFile = 'target/m4-3c/events-verify.json',
    [string]$RedisContainer = 'aiflow-redis'
)
$ErrorActionPreference = 'Stop'
$checks = New-Object System.Collections.Generic.List[object]
$runEvidence = New-Object System.Collections.Generic.List[object]
$phase = 'parameters'
$failed = $false
$failureKind = $null
$startedAt = [DateTime]::UtcNow
$terminalTypes = @('RUN_SUCCEEDED','RUN_DEGRADED','RUN_FAILED','RUN_CANCELED')
$knownTypes = @(
    'RUN_STARTED','RUN_SUCCEEDED','RUN_DEGRADED','RUN_FAILED','RUN_CANCELED','RUN_WAITING',
    'NODE_STARTED','NODE_SUCCEEDED','NODE_DEGRADED','NODE_FAILED','NODE_RETRY','NODE_SUSPENDED',
    'DELTA','MEMORY_SUMMARIZED','MEMORY_EXTRACTED',
    'APPROVAL_REQUESTED','APPROVAL_DECIDED','APPROVAL_ESCALATED','APPROVAL_EXPIRED'
)

function Check([string]$Name, [bool]$Passed, $Detail) {
    $checks.Add([pscustomobject]@{name=$Name;passed=$Passed;evidence=$Detail})
    if ($Passed) { Write-Output "PASS: $Name" } else { Write-Output "FAIL: $Name" }
}

function StreamEntries([string]$RunId) {
    # Run IDs are validated as canonical UUIDs before interpolation. The command
    # is fixed and read-only, so report content cannot select another Redis command.
    $streamKey = 'aiflow:events:{'+$RunId+'}:stream'
    $lines = @(& docker exec $RedisContainer redis-cli --json XRANGE $streamKey '-' '+' 2>$null)
    if ($LASTEXITCODE -ne 0 -or $lines.Count -eq 0) { throw 'REDIS_XRANGE_FAILED' }
    try {
        # Wrapping the top-level array preserves its shape for a single record in PS 5.1.
        $parsed = ConvertFrom-Json -InputObject ('{"entries":'+($lines -join "`n")+'}')
        if ($null -eq $parsed.entries -or $parsed.entries -isnot [Array]) { throw 'REDIS_ARRAY_EXPECTED' }
        return ,$parsed.entries
    } catch { throw 'REDIS_STREAM_JSON_INVALID' }
}

$reportPath = [IO.Path]::GetFullPath($OutFile)
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$reportRoot = [IO.Path]::GetFullPath((Join-Path $repositoryRoot 'target/m4-3c'))
$rootPrefix = $reportRoot.TrimEnd([char[]]@('\','/'))+[IO.Path]::DirectorySeparatorChar
if (-not $reportPath.StartsWith($rootPrefix,[StringComparison]::OrdinalIgnoreCase) -or
    [IO.Path]::GetExtension($reportPath) -ne '.json') {
    throw 'OutFile must be a JSON report under target/m4-3c.'
}

try {
    if ($RedisContainer -cnotmatch '^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$') { throw 'REDIS_CONTAINER_INVALID' }
    $phase = 'postgres-report-read'
    try {
        $pgReport = ConvertFrom-Json -InputObject ([IO.File]::ReadAllText([IO.Path]::GetFullPath($PgEvidenceFile),[Text.Encoding]::UTF8))
    } catch { throw 'POSTGRES_REPORT_INVALID' }
    if ($null -eq $pgReport.evidence -or $null -eq $pgReport.evidence.database -or
        $null -eq $pgReport.evidence.database.PSObject.Properties['runs']) { throw 'POSTGRES_RUNS_MISSING' }
    $runs = @($pgReport.evidence.database.runs)
    Check 'postgres-evidence-passed' ($pgReport.passed -eq $true) $null
    Check 'postgres-runs-present' ($runs.Count -gt 0) $runs.Count
    Check 'postgres-run-count-matches' ($runs.Count -eq $pgReport.evidence.database.counts.runs) @{
        actual=$runs.Count;expected=$pgReport.evidence.database.counts.runs
    }
    if ($runs.Count -eq 0) { throw 'POSTGRES_RUNS_EMPTY' }
    $seenRuns = @{}

    foreach ($run in $runs) {
        $phase = 'run-validation'
        $runId = [string]$run.runId
        $guid = [Guid]::Empty
        if ($runId -cnotmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' -or
            -not [Guid]::TryParseExact($runId,'D',[ref]$guid) -or $seenRuns.ContainsKey($runId)) {
            throw 'POSTGRES_RUN_UUID_INVALID_OR_DUPLICATE'
        }
        $seenRuns[$runId] = $true
        $expectedStatus = [string]$run.status
        if ($expectedStatus -cnotin @('SUCCEEDED','DEGRADED','FAILED','CANCELED')) { throw 'POSTGRES_RUN_NOT_TERMINAL' }
        if ($expectedStatus -ceq 'SUCCEEDED' -and [string]::IsNullOrWhiteSpace([string]$run.workflowName)) {
            throw 'POSTGRES_SUCCESSFUL_WORKFLOW_MISSING'
        }
        $expectedType = 'RUN_'+$expectedStatus
        $phase = 'redis-stream-read'
        $entries = StreamEntries $runId
        $eventCount = $entries.Count
        $firstSeq = $null
        $lastSeq = $null
        $contiguous = $true
        $matchingRun = $true
        $validTypes = $true
        $validPayloads = $true
        $terminalCount = 0
        $terminalType = $null
        $terminalSeq = $null
        $terminalStatus = $null
        $nonemptyDeltaCount = 0
        $phase = 'redis-stream-inspect'

        foreach ($entry in $entries) {
            if ($entry -isnot [Array] -or $entry.Count -ne 2 -or $entry[1] -isnot [Array] -or
                $entry[1].Count -ne 2 -or [string]$entry[1][0] -cne 'event') { throw 'REDIS_ENTRY_SHAPE_INVALID' }
            $streamId = [string]$entry[0]
            if ($streamId -cnotmatch '^([1-9][0-9]*)-0$') { throw 'REDIS_STREAM_ID_INVALID' }
            $sequenceText = $Matches[1]
            $seq = [long]0
            if (-not [long]::TryParse($sequenceText,[ref]$seq)) { throw 'REDIS_STREAM_SEQUENCE_INVALID' }
            if ($null -eq $firstSeq) { $firstSeq = $seq }
            if ($null -ne $lastSeq -and $seq -ne ($lastSeq+1)) { $contiguous = $false }
            $lastSeq = $seq
            try { $event = ConvertFrom-Json -InputObject ([string]$entry[1][1]) } catch { throw 'REDIS_EVENT_JSON_INVALID' }
            if ($null -eq $event -or $null -eq $event.PSObject.Properties['type'] -or
                $null -eq $event.PSObject.Properties['runId'] -or $null -eq $event.PSObject.Properties['data']) {
                $validPayloads = $false
                continue
            }
            if ([string]$event.runId -cne $runId) { $matchingRun = $false }
            $type = [string]$event.type
            if ($type -cnotin $knownTypes) { $validTypes = $false }
            if ($type -ceq 'DELTA' -and $event.data.text -is [string] -and $event.data.text.Length -gt 0) {
                $nonemptyDeltaCount++
            }
            if ($type -cin $terminalTypes) {
                $terminalCount++
                $terminalType = $type
                $terminalSeq = $seq
                $terminalStatus = [string]$event.data.status
            }
        }

        # The stages create fresh streams below the retention cap. A trimmed prefix
        # is a failed completeness check, even if the surviving entries are contiguous.
        $completeSequence = $eventCount -gt 0 -and $firstSeq -eq 1 -and $contiguous -and $lastSeq -eq $eventCount
        $terminalMatches = $terminalCount -eq 1 -and $terminalType -ceq $expectedType -and
            $terminalStatus -ceq $expectedStatus
        $terminalLast = $terminalCount -eq 1 -and $terminalSeq -eq $lastSeq
        $requiresDelta = $expectedStatus -ceq 'SUCCEEDED' -and [string]$run.workflowName -ceq 'basic-chat'
        $deltaValid = -not $requiresDelta -or $nonemptyDeltaCount -gt 0
        $runPassed = $completeSequence -and $matchingRun -and $validTypes -and $validPayloads -and
            $terminalMatches -and $terminalLast -and $deltaValid
        $runEvidence.Add([pscustomobject]@{
            runId=$runId;expectedStatus=$expectedStatus;eventCount=$eventCount;firstSeq=$firstSeq;lastSeq=$lastSeq
            contiguous=$contiguous;completeSequence=$completeSequence;matchingRun=$matchingRun
            validPayloads=$validPayloads;validTypes=$validTypes;terminalCount=$terminalCount
            terminalType=$terminalType;terminalSeq=$terminalSeq;terminalStatus=$terminalStatus
            terminalMatches=$terminalMatches;terminalLast=$terminalLast
            requiresNonemptyDelta=$requiresDelta;nonemptyDelta=($nonemptyDeltaCount -gt 0)
            nonemptyDeltaCount=$nonemptyDeltaCount;passed=$runPassed
        })
        Check ('stream-complete-'+$runId) $runPassed $null
    }
    Check 'all-postgres-runs-inspected' ($runEvidence.Count -eq $runs.Count) @{actual=$runEvidence.Count;expected=$runs.Count}
    $failed = @($checks | Where-Object {-not $_.passed}).Count -gt 0
    $phase = 'complete'
} catch {
    $failed = $true
    # Error messages can contain Redis payload fragments. Persist only the safe
    # phase and exception class, never the exception text or command output.
    $failureKind = $_.Exception.GetType().Name
    Check ('verification-exception-'+$phase) $false @{kind=$failureKind}
} finally {
    $report = [pscustomobject]@{
        startedAt=$startedAt.ToString('o');finishedAt=[DateTime]::UtcNow.ToString('o');phase=$phase
        passed=(-not $failed);failureKind=$failureKind
        source='verify-load-evidence.ps1:evidence.database.runs';sequenceSource='Redis stream ID; payload.seq ignored'
        readOnlyCommand='XRANGE';runs=$runEvidence.ToArray();checks=$checks.ToArray()
    }
    [IO.Directory]::CreateDirectory((Split-Path -Parent $reportPath)) | Out-Null
    [IO.File]::WriteAllText($reportPath,($report | ConvertTo-Json -Depth 15),[Text.UTF8Encoding]::new($false))
}
Write-Output "Report: $OutFile"
if ($failed) { throw 'Load event verification failed; inspect the saved report.' }
