<#
.SYNOPSIS
  Prepare a fresh, read-only SSE feeder from synthetic terminal PostgreSQL runs.
.NOTES
  Redis metadata is inspected in batches of 64 with EVAL_RO. Payloads stay inside
  Redis; only cursor, TTL and terminal validation flags are returned or recorded.
  No run, message, key, TTL or schema is changed. Missing runs are only reported.
  Save with UTF-8 BOM and invoke with Windows PowerShell 5.1 or newer.
#>
param(
    [Parameter(Mandatory=$true)][ValidateSet(1,10,1000)][int]$Count,
    [Parameter(Mandatory=$true)][string]$OutFile,
    [string]$ReportFile,
    [ValidateRange(120,3600)][int]$MinimumTtlSeconds = 300,
    [string]$SyntheticPrefix = 'm43c-',
    [string]$PostgresContainer = 'aiflow-postgres',
    [string]$RedisContainer = 'aiflow-redis'
)
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$reportRoot = [IO.Path]::GetFullPath((Join-Path $repositoryRoot 'target/m4-3c'))
$rootPrefix = $reportRoot.TrimEnd([char[]]@('\','/'))+[IO.Path]::DirectorySeparatorChar
$startedAt = [DateTime]::UtcNow
$phase = 'parameters'
$failureKind = $null
$failed = $false
$feederCreated = $false
$pgSnapshot = $null
$examinedCount = 0
$redisBatches = 0
$availableRuns = New-Object 'System.Collections.Generic.List[object]'
$rejectedRuns = New-Object 'System.Collections.Generic.List[object]'
$terminalCounts = [ordered]@{SUCCEEDED=0;DEGRADED=0;FAILED=0;CANCELED=0}
$rejectionCounts = [ordered]@{}
$minimumTtlMs = [long]$MinimumTtlSeconds * 1000

function New-ArtifactPath([string]$Requested, [string]$Extension) {
    if ([string]::IsNullOrWhiteSpace($Requested)) { throw 'ARTIFACT_PATH_REQUIRED' }
    $candidate = if ([IO.Path]::IsPathRooted($Requested)) { $Requested } else { Join-Path $repositoryRoot $Requested }
    $fullPath = [IO.Path]::GetFullPath($candidate)
    if (-not $fullPath.StartsWith($rootPrefix,[StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetExtension($fullPath) -cne $Extension -or (Test-Path -LiteralPath $fullPath)) {
        throw 'ARTIFACT_MUST_BE_NEW_FILE_UNDER_TARGET_M43C'
    }
    # Reject junction/symlink ancestors so lexical containment cannot redirect writes.
    $ancestor = Split-Path -Parent $fullPath
    while (-not [string]::IsNullOrEmpty($ancestor)) {
        if (Test-Path -LiteralPath $ancestor) {
            $item = Get-Item -LiteralPath $ancestor -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'ARTIFACT_REPARSE_POINT_REJECTED' }
        }
        $ancestor = Split-Path -Parent $ancestor
    }
    return $fullPath
}

function Write-NewArtifact([string]$Path, [string]$Text) {
    [IO.Directory]::CreateDirectory((Split-Path -Parent $Path)) | Out-Null
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes($Text)
    $stream = [IO.File]::Open($Path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
    try { $stream.Write($bytes,0,$bytes.Length) } finally { $stream.Dispose() }
}

function Is-CanonicalUuid([string]$Value) {
    $guid = [Guid]::Empty
    return $Value -cmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' -and
        [Guid]::TryParseExact($Value,'D',[ref]$guid)
}

function PostgreSql-TerminalSnapshot {
    # The prefix is restricted before interpolation, and this one SELECT runs in a
    # read-only transaction. No message, node input/output or credential is selected.
    $query = @"
select json_build_object(
  'at',now(),
  'runs',coalesce(json_agg(json_build_object(
    'runId',r.id,'status',r.status,'finishedAt',r.finished_at
  ) order by r.finished_at desc nulls last,r.id),'[]'::json)
)
from (
  select r.id,r.status,r.finished_at
  from workflow_runs r join users u on u.id=r.user_id
  where starts_with(u.external_id,'$SyntheticPrefix')
    and r.status in ('SUCCEEDED','DEGRADED','FAILED','CANCELED')
  order by r.finished_at desc nulls last,r.id limit 5000
) r
"@
    if (-not $query.TrimStart().StartsWith('select ',[StringComparison]::OrdinalIgnoreCase) -or $query.Contains(';')) {
        throw 'POSTGRES_SELECT_ONLY_GUARD_FAILED'
    }
    $lines = @(& docker exec -e 'PGOPTIONS=-c default_transaction_read_only=on -c statement_timeout=10000' `
        $PostgresContainer psql -X -U aiflow -d aiflow -At -v 'ON_ERROR_STOP=1' -c $query 2>$null)
    if ($LASTEXITCODE -ne 0 -or $lines.Count -ne 1 -or [string]::IsNullOrWhiteSpace([string]$lines[0])) {
        throw 'POSTGRES_SELECT_FAILED'
    }
    try { $snapshot = ConvertFrom-Json -InputObject ([string]$lines[0]) } catch { throw 'POSTGRES_JSON_INVALID' }
    if ($null -eq $snapshot -or $null -eq $snapshot.PSObject.Properties['at'] -or
        $null -eq $snapshot.PSObject.Properties['runs'] -or $snapshot.runs -isnot [Array]) {
        throw 'POSTGRES_SNAPSHOT_SHAPE_INVALID'
    }
    return $snapshot
}

function Redis-TerminalMetadata([object[]]$Runs) {
    # EVAL_RO enforces Redis' read-only script contract. Validate the latest entry
    # and counter atomically, without returning the event body to PowerShell.
    $lua = @'
local rows = {}
for i = 1, #ARGV, 2 do
  local item = (i + 1) / 2
  local stream = KEYS[item * 2 - 1]
  local counter = KEYS[item * 2]
  local row = {runId = ARGV[i], reason = 'missing-stream', ttlMs = -2, terminalFieldsValid = false}
  local streamType = redis.call('TYPE', stream).ok
  if streamType ~= 'none' then
    row.reason = 'stream-type-invalid'
    if streamType == 'stream' then
      row.ttlMs = redis.call('PTTL', stream)
      row.eventCount = redis.call('XLEN', stream)
      row.reason = 'counter-type-invalid'
      if redis.call('TYPE', counter).ok == 'string' then
        local latest = redis.call('GET', counter)
        local last = redis.call('XREVRANGE', stream, '+', '-', 'COUNT', 1)
        row.reason = 'empty-stream'
        if #last == 1 then
          local id = last[1][1]
          row.reason = 'stream-id-invalid'
          if string.match(id, '^[1-9][0-9]*%-0$') then
            row.lastSeq = string.sub(id, 1, -3)
            row.counterMatches = latest == row.lastSeq
            row.reason = 'counter-mismatch'
            if row.counterMatches then
              local fields = last[1][2]
              row.reason = 'terminal-fields-invalid'
              if #fields == 2 and fields[1] == 'event' then
                local ok, event = pcall(cjson.decode, fields[2])
                if ok and type(event) == 'table' and event.runId == ARGV[i] and
                   event.type == 'RUN_' .. ARGV[i + 1] and type(event.data) == 'table' and
                   event.data.status == ARGV[i + 1] then
                  row.terminalFieldsValid = true
                  row.terminalType = event.type
                  row.terminalStatus = event.data.status
                  row.reason = 'candidate'
                end
              end
            end
          end
        end
      end
    end
  end
  rows[#rows + 1] = row
end
return cjson.encode({rows = rows})
'@
    $arguments = New-Object 'System.Collections.Generic.List[string]'
    foreach ($run in $Runs) {
        $arguments.Add('aiflow:events:{'+[string]$run.runId+'}:stream')
        $arguments.Add('aiflow:events:{'+[string]$run.runId+'}:seq')
    }
    foreach ($run in $Runs) {
        $arguments.Add([string]$run.runId)
        $arguments.Add([string]$run.status)
    }
    $argumentsArray = $arguments.ToArray()
    $lines = @(& docker exec $RedisContainer redis-cli --raw EVAL_RO $lua ($Runs.Count * 2) @argumentsArray 2>$null)
    if ($LASTEXITCODE -ne 0 -or $lines.Count -ne 1 -or [string]::IsNullOrWhiteSpace([string]$lines[0])) {
        throw 'REDIS_READ_ONLY_BATCH_FAILED'
    }
    try { $metadata = ConvertFrom-Json -InputObject ([string]$lines[0]) } catch { throw 'REDIS_METADATA_JSON_INVALID' }
    if ($null -eq $metadata -or $metadata.rows -isnot [Array] -or $metadata.rows.Count -ne $Runs.Count) {
        throw 'REDIS_METADATA_SHAPE_INVALID'
    }
    return ,$metadata.rows
}

function Ttl-Range([object[]]$Runs) {
    if ($Runs.Count -eq 0) { return $null }
    $ttl = $Runs | Measure-Object -Property ttlMs -Minimum -Maximum
    return [pscustomobject]@{minimumMs=[long]$ttl.Minimum;maximumMs=[long]$ttl.Maximum}
}

if ([string]::IsNullOrWhiteSpace($ReportFile)) { $ReportFile = [IO.Path]::ChangeExtension($OutFile,'.json') }
$csvPath = New-ArtifactPath $OutFile '.csv'
$reportPath = New-ArtifactPath $ReportFile '.json'
$selected = @()
$candidateCount = 0

try {
    if ($SyntheticPrefix -cnotmatch '^[A-Za-z0-9-]{1,40}$' -or
        $PostgresContainer -cnotmatch '^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$' -or
        $RedisContainer -cnotmatch '^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$') { throw 'SOURCE_PARAMETER_INVALID' }
    $phase = 'postgres-terminal-snapshot'
    $pgSnapshot = PostgreSql-TerminalSnapshot
    $runs = @($pgSnapshot.runs)
    $candidateCount = $runs.Count
    $seen = @{}
    foreach ($run in $runs) {
        if (-not (Is-CanonicalUuid ([string]$run.runId)) -or $seen.ContainsKey([string]$run.runId) -or
            [string]$run.status -cnotin @('SUCCEEDED','DEGRADED','FAILED','CANCELED') -or
            $null -eq $run.PSObject.Properties['finishedAt']) { throw 'POSTGRES_TERMINAL_ROW_INVALID' }
        $seen[[string]$run.runId] = $true
        $terminalCounts[[string]$run.status]++
    }
    $phase = 'redis-read-only-batches'
    for ($offset = 0; $offset -lt $runs.Count; $offset += 64) {
        $end = [Math]::Min($offset + 63,$runs.Count - 1)
        $batch = @($runs[$offset..$end])
        $metadata = Redis-TerminalMetadata $batch
        $redisBatches++
        for ($index = 0; $index -lt $batch.Count; $index++) {
            $pg = $batch[$index]
            $row = $metadata[$index]
            if ([string]$row.runId -cne [string]$pg.runId -or $null -eq $row.PSObject.Properties['reason'] -or
                $null -eq $row.PSObject.Properties['ttlMs'] -or $null -eq $row.PSObject.Properties['terminalFieldsValid']) {
                throw 'REDIS_METADATA_ROW_INVALID'
            }
            $examinedCount++
            $reason = [string]$row.reason
            if ($reason -ceq 'candidate') {
                $seq = [long]0
                if ([string]$row.lastSeq -cnotmatch '^[1-9][0-9]*$' -or
                    -not [long]::TryParse([string]$row.lastSeq,[ref]$seq) -or $seq -lt 1 -or
                    $row.counterMatches -ne $true -or $row.terminalFieldsValid -ne $true -or
                    [string]$row.terminalType -cne ('RUN_'+[string]$pg.status) -or
                    [string]$row.terminalStatus -cne [string]$pg.status) { throw 'REDIS_VALIDATION_FLAGS_INVALID' }
                if ($null -eq $pg.finishedAt) { $reason = 'postgres-finished-time-missing' }
                elseif ([long]$row.ttlMs -lt $minimumTtlMs) { $reason = 'ttl-too-short-or-not-set' }
                elseif ([long]$row.eventCount -lt 1 -or [long]$row.eventCount -gt 2000) { $reason = 'event-count-outside-retention-cap' }
            }
            if ($reason -ceq 'candidate') {
                $availableRuns.Add([pscustomobject]@{
                    runId=[string]$pg.runId;lastSeq=[string]$row.lastSeq;ttlMs=[long]$row.ttlMs
                    pgStatus=[string]$pg.status;pgFinishedAt=$pg.finishedAt;eventCount=[long]$row.eventCount
                    terminalType=[string]$row.terminalType;terminalFieldsValid=$true;counterMatches=$true
                })
            } else {
                if (-not $rejectionCounts.Contains($reason)) { $rejectionCounts[$reason] = 0 }
                $rejectionCounts[$reason]++
                $rejectedRuns.Add([pscustomobject]@{runId=[string]$pg.runId;pgStatus=[string]$pg.status;reason=$reason})
            }
        }
    }
    $phase = 'feeder-write'
    if ($availableRuns.Count -ge $Count) {
        $selected = @($availableRuns | Select-Object -First $Count)
        $csv = "runId,lastSeq`r`n" + ((@($selected | ForEach-Object {$_.runId+','+$_.lastSeq}) -join "`r`n") + "`r`n")
        Write-NewArtifact $csvPath $csv
        $feederCreated = $true
    }
    $phase = 'complete'
} catch {
    $failed = $true
    # Exception text and native command output may contain connection details.
    # Persist only the fixed phase and exception class, never the upstream text.
    $failureKind = $_.Exception.GetType().Name
} finally {
    $ready = -not $failed -and $feederCreated -and $selected.Count -eq $Count
    $report = [pscustomobject]@{
        schemaVersion=1;startedAt=$startedAt.ToString('o');finishedAt=[DateTime]::UtcNow.ToString('o')
        phase=$phase;passed=$ready;failureKind=$failureKind;readOnly=$true
        requestedCount=$Count;pgCandidateCount=$candidateCount;pgCandidateLimit=5000;examinedCount=$examinedCount
        availableCount=$availableRuns.Count;shortfallCount=[Math]::Max(0,$Count-$availableRuns.Count)
        selectedCount=$selected.Count;feederCreated=$feederCreated;csvPath=$csvPath
        syntheticPrefix=$SyntheticPrefix;minimumTtlSeconds=$MinimumTtlSeconds
        postgresSnapshotAt=$(if ($null -ne $pgSnapshot) {$pgSnapshot.at} else {$null})
        postgresTerminalCounts=$terminalCounts;redisBatches=$redisBatches;redisBatchSize=64
        readCommands=@('PostgreSQL SELECT in read-only transaction','Redis EVAL_RO','TYPE','PTTL','XLEN','XREVRANGE','GET')
        verificationScope='Up to 5000 latest synthetic PG terminal runs: status/finishedAt and atomic Redis latest-entry runId/type/data.status, positive cursor/counter equality and TTL; not a full event-history replay audit or global available-run census'
        ttlScope='PTTL at each Redis batch snapshot; regenerate before load if preparation or waiting consumes the TTL margin'
        availableTtlRange=(Ttl-Range $availableRuns.ToArray());selectedTtlRange=(Ttl-Range $selected)
        availableRuns=$availableRuns.ToArray();rejectionCounts=$rejectionCounts;rejectedRuns=$rejectedRuns.ToArray()
        dataChanged=$false;createdMockRuns=0;payloadsRecorded=$false
    }
    Write-NewArtifact $reportPath ($report | ConvertTo-Json -Depth 10)
}
Write-Output "Available: $($availableRuns.Count); requested: $Count; feeder created: $feederCreated"
Write-Output "Report: $reportPath"
if ($failed) { exit 1 }
if (-not $feederCreated) { exit 2 }
