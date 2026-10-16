<#
.SYNOPSIS
  S1-order: ten consecutive submissions on one fresh session, then verify PG order.
.NOTES
  Use a separately started mock engine with queue capacity 1000 and 16 workers.
  The submit loop has no sleep or run polling. All synthetic records are retained.
  PostgreSQL commands are SELECT only, with read-only mode and a 2 second timeout.
  The existing verify-load-evidence.ps1 supplies the complete PG/state assertions.
  Reports retain identifiers and timings, never message/node text or credentials.
  evidence.database.runs can be read by verify-load-events.ps1. UTF-8 BOM/PS 5.1.
#>
param(
    [string]$BaseUrl='http://localhost:8082',
    [string]$Prefix=('m43c-order-'+[Guid]::NewGuid().ToString('N').Substring(0,24)),
    [string]$OutDir='target/m4-3c/session-order'
)
$ErrorActionPreference='Stop'
$repoRoot=[IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$reportRoot=[IO.Path]::GetFullPath((Join-Path $repoRoot 'target/m4-3c'))
$outputRoot=if ([IO.Path]::IsPathRooted($OutDir)) {[IO.Path]::GetFullPath($OutDir)} else {[IO.Path]::GetFullPath((Join-Path $repoRoot $OutDir))}
$rootPrefix=$reportRoot.TrimEnd([char[]]@('\','/'))+[IO.Path]::DirectorySeparatorChar
if ($outputRoot -ne $reportRoot -and -not $outputRoot.StartsWith($rootPrefix,[StringComparison]::OrdinalIgnoreCase)) {
    throw 'OutDir must be under target/m4-3c.'
}
$pathToCheck=$outputRoot
while ($pathToCheck -and $pathToCheck.StartsWith($repoRoot,[StringComparison]::OrdinalIgnoreCase)) {
    if (Test-Path -LiteralPath $pathToCheck) {
        if (((Get-Item -LiteralPath $pathToCheck -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'OutDir must not traverse a junction or symbolic link.'
        }
    }
    if ($pathToCheck -eq $repoRoot) {break}
    $pathToCheck=Split-Path -Parent $pathToCheck
}
[IO.Directory]::CreateDirectory($outputRoot)|Out-Null
$reportStem=if ($Prefix -cmatch '^[A-Za-z0-9-]{1,40}$') {$Prefix} else {'invalid-'+[Guid]::NewGuid().ToString('N')}
$reportPath=Join-Path $outputRoot ($reportStem+'-order-report.json')
$pgReportPath=Join-Path $outputRoot ($reportStem+'-pg-evidence.json')
$checks=New-Object System.Collections.Generic.List[object]
$submissions=New-Object System.Collections.Generic.List[object]
$evidence=[ordered]@{prefix=$reportStem;users=0;sessions=0;runs=0;submissions=@();database=$null}
$startedAt=[DateTime]::UtcNow
$phase='parameters'
$failed=$false
$failureKind=$null
$safeOrigin=$false
$childInvoked=$false
$pgReport=$null
$runCount=10

function Check([string]$Name,[bool]$Passed,$Detail) {
    $checks.Add([pscustomobject]@{name=$Name;passed=$Passed;evidence=$Detail})
    if (-not $Passed) {throw "FAILED: $Name"}
    Write-Host "PASS: $Name"
}
function GuidText([string]$Value) {
    $guid=[Guid]::Empty
    if (-not [Guid]::TryParseExact($Value,'D',[ref]$guid)) {throw 'UUID_INVALID'}
    return $guid.ToString('D')
}
function Api([string]$Method,[string]$Path,$Body=$null) {
    $requestArgs=@{Uri=($BaseUrl+$Path);Method=$Method;UseBasicParsing=$true;TimeoutSec=8;MaximumRedirection=0}
    if ($null -ne $Body) {
        $requestArgs.ContentType='application/json; charset=utf-8'
        $requestArgs.Body=[Text.Encoding]::UTF8.GetBytes(($Body|ConvertTo-Json -Depth 8 -Compress))
    }
    try {$response=Invoke-WebRequest @requestArgs} catch {throw 'API_REQUEST_FAILED'}
    if ([int]$response.StatusCode -ne 200) {throw 'API_HTTP_STATUS_UNEXPECTED'}
    $text=$response.Content
    if ($text -is [byte[]]) {$text=[Text.Encoding]::UTF8.GetString($text)}
    try {$parsed=$text|ConvertFrom-Json} catch {throw 'API_JSON_INVALID'}
    if ($null -eq $parsed.data -or $null -eq $parsed.PSObject.Properties['code'] -or $parsed.code -ne 0) {throw 'API_RESPONSE_CONTRACT_INVALID'}
    return [pscustomobject]@{httpStatus=[int]$response.StatusCode;data=$parsed.data}
}
function Sql([string]$Query) {
    if (-not $Query.TrimStart().StartsWith('select ',[StringComparison]::OrdinalIgnoreCase) -or $Query.Contains(';')) {throw 'SELECT_ONLY_GUARD_FAILED'}
    $rows=@(& docker exec -e 'PGOPTIONS=-c default_transaction_read_only=on -c statement_timeout=2000' `
        aiflow-postgres psql -X -U aiflow -d aiflow -At -v 'ON_ERROR_STOP=1' -c $Query 2>$null)
    if ($LASTEXITCODE -ne 0 -or $rows.Count -ne 1 -or [string]::IsNullOrWhiteSpace($rows[0])) {throw 'POSTGRES_SELECT_FAILED'}
    try {return ($rows[0]|ConvertFrom-Json)} catch {throw 'POSTGRES_JSON_INVALID'}
}
function ClosureCounts([string]$SessionId) {
    $sid=GuidText $SessionId
    return (Sql @"
select snapshot from (
with ss as (
 select s.id from sessions s join users u on u.id=s.user_id where s.id='$sid' and u.external_id='$Prefix'
), rr as (select * from workflow_runs where session_id in (select id from ss)),
tt as (select * from session_dispatch_tasks where run_id in (select id from rr)),
nn as (select * from node_runs where run_id in (select id from rr))
select json_build_object(
 'runs',(select count(*) from rr),
 'successfulRuns',(select count(*) from rr where status='SUCCEEDED'),
 'unexpectedTerminals',(select count(*) from rr where status in ('FAILED','DEGRADED','CANCELED')),
 'activeRuns',(select count(*) from rr where status in ('PENDING','RUNNING','WAITING_APPROVAL') or finished_at is null),
 'tasks',(select count(*) from tt),
 'activeTasks',(select count(*) from tt where status in ('QUEUED','RUNNING') or finished_at is null),
 'activeNodes',(select count(*) from nn where status in ('RUNNING','WAITING') or ended_at is null)
) snapshot
) closure_counts
"@)
}
function ImportPgReport {
    if (-not (Test-Path -LiteralPath $pgReportPath)) {return}
    try {$report=ConvertFrom-Json -InputObject ([IO.File]::ReadAllText($pgReportPath,[Text.Encoding]::UTF8))} catch {throw 'PG_EVIDENCE_REPORT_INVALID'}
    if ($null -eq $report.evidence -or $null -eq $report.evidence.database) {throw 'PG_EVIDENCE_DATABASE_MISSING'}
    $evidence.database=$report.evidence.database
    $evidence.pgVerification=[pscustomobject]@{file=$pgReportPath;passed=$report.passed;phase=$report.phase;checks=$report.checks}
    $evidence.events=$report.evidence.events
    $evidence.dispatch=$report.evidence.dispatch
    $evidence.users=$report.evidence.database.counts.users
    $evidence.sessions=$report.evidence.database.counts.sessions
    $evidence.runs=$report.evidence.database.counts.runs
    return $report
}

try {
    Check 'synthetic-prefix-valid' ($Prefix -cmatch '^[A-Za-z0-9-]{1,40}$') @{length=$Prefix.Length;allowed='letters,digits,hyphens;1..40'}
    $origin=$null
    $safeOrigin=[Uri]::TryCreate($BaseUrl,[UriKind]::Absolute,[ref]$origin) -and $origin.Scheme -eq 'http' -and
        $origin.Host -in @('localhost','127.0.0.1') -and -not $origin.UserInfo -and -not $origin.Query -and -not $origin.Fragment -and $origin.AbsolutePath -eq '/'
    Check 'local-origin-valid' $safeOrigin $null
    $BaseUrl=$origin.GetLeftPart([UriPartial]::Authority)
    $verifier=Join-Path $PSScriptRoot 'verify-load-evidence.ps1'
    Check 'existing-pg-verifier-present' (Test-Path -LiteralPath $verifier) $null
    $phase='preflight'
    $existing=Sql "select count(*) from users where starts_with(external_id,'$Prefix')"
    Check 'synthetic-prefix-unused' ($existing -eq 0) $existing
    $caps=(Api 'GET' '/api/v1/capabilities').data
    $chat=@($caps.channels|Where-Object {$_.channel -eq 'chat'})
    $embedding=@($caps.channels|Where-Object {$_.channel -eq 'embedding'})
    Check 'mock-only-capabilities' ($chat.Count -eq 1 -and $chat[0].activeProvider -eq 'mock' -and
        $embedding.Count -eq 1 -and $embedding[0].activeProvider -eq 'mock-embedding' -and
        @($chat.candidates|Where-Object {$_.available -and $_.id -ne 'mock'}).Count -eq 0) $null
    $phase='create-user'
    $user=(Api 'POST' '/api/v1/users' @{externalId=$Prefix;displayName='Synthetic session order'}).data
    $userId=GuidText $user.id
    $evidence.users++;$evidence.userId=$userId
    $phase='create-session'
    $session=(Api 'POST' '/api/v1/sessions' @{userId=$userId;title=$Prefix;workflowName='basic-chat'}).data
    $sessionId=GuidText $session.id
    $evidence.sessions++;$evidence.sessionId=$sessionId
    $phase='submit-ten-without-terminal-wait'
    $burstWatch=[Diagnostics.Stopwatch]::StartNew()
    for ($index=1;$index -le $runCount;$index++) {
        $clientMsgId="$Prefix-$index"
        $at=[DateTime]::UtcNow
        $requestWatch=[Diagnostics.Stopwatch]::StartNew()
        $response=Api 'POST' "/api/v1/sessions/$sessionId/messages" @{clientMsgId=$clientMsgId;content='synthetic ordered session round'}
        $requestWatch.Stop()
        $runId=GuidText $response.data.runId
        $submissions.Add([pscustomobject]@{index=$index;clientMsgId=$clientMsgId;runId=$runId;
            submittedAt=$at.ToString('o');acceptedAt=[DateTime]::UtcNow.ToString('o');elapsedMs=$requestWatch.ElapsedMilliseconds;
            httpStatus=$response.httpStatus;status=$response.data.status})
        $evidence.runs++;$evidence.submissions=$submissions.ToArray()
        Check ('submission-'+$index+'-queued') ($response.httpStatus -eq 200 -and $response.data.status -eq 'QUEUED') @{
            index=$index;runId=$runId;httpStatus=$response.httpStatus;status=$response.data.status
        }
    }
    $burstWatch.Stop()
    $evidence.submissionSpanMs=$burstWatch.ElapsedMilliseconds
    Check 'ten-distinct-run-and-client-ids' ((@($submissions.runId|Select-Object -Unique)).Count -eq $runCount -and
        (@($submissions.clientMsgId|Select-Object -Unique)).Count -eq $runCount) $runCount
    $phase='wait-ten-run-node-task-closed'
    $deadline=[DateTime]::UtcNow.AddSeconds(60)
    $polls=0
    do {
        $closure=ClosureCounts $sessionId
        $polls++
        $evidence.closure=$closure;$evidence.closurePolls=$polls
        if ($closure.unexpectedTerminals -gt 0) {throw 'UNEXPECTED_RUN_TERMINAL'}
        $closed=$closure.runs -eq $runCount -and $closure.successfulRuns -eq $runCount -and $closure.tasks -eq $runCount -and
            $closure.activeRuns -eq 0 -and $closure.activeTasks -eq 0 -and $closure.activeNodes -eq 0
        if ($closed) {break}
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)
    Check 'ten-run-node-task-closed' $closed $closure
    $phase='existing-pg-verification'
    $childInvoked=$true
    & $verifier -Prefix $Prefix -ExpectedUsers 1 -ExpectedSessions 1 -ExpectedRuns $runCount -ExpectedCanceledRuns 0 -BaseUrl $BaseUrl -OutFile $pgReportPath
    $pgReport=ImportPgReport
    Check 'existing-pg-verification-passed' ($null -ne $pgReport -and $pgReport.passed -eq $true) $null
    $phase='match-submission-index-to-pg-seq'
    $tasks=@($pgReport.evidence.database.tasks|Where-Object {$_.sessionId -eq $sessionId}|Sort-Object {[long]$_.seq})
    Check 'ten-process-tasks-for-created-session' ($tasks.Count -eq $runCount -and @($tasks|Where-Object {$_.kind -ne 'PROCESS'}).Count -eq 0) $tasks.Count
    $matches=New-Object System.Collections.Generic.List[object]
    for ($offset=0;$offset -lt $runCount;$offset++) {
        $submitted=$submissions[$offset]
        $task=$tasks[$offset]
        $matched=$submitted.runId -eq $task.runId -and $submitted.clientMsgId -eq $task.clientMsgId -and
            $task.status -eq 'DONE' -and $task.overlap -eq $false -and
            ($offset -eq 0 -or [long]$task.seq -gt [long]$tasks[$offset-1].seq)
        $matches.Add([pscustomobject]@{index=$submitted.index;clientMsgId=$submitted.clientMsgId;runId=$submitted.runId;
            taskId=$task.taskId;seq=$task.seq;startedAt=$task.startedAt;finishedAt=$task.finishedAt;matched=$matched;overlap=$task.overlap})
        $evidence.orderMatches=$matches.ToArray()
        Check ('submit-index-'+$submitted.index+'-matches-task-seq') $matched $matches[$offset]
    }
    Check 'zero-overlapping-session-segments' ($pgReport.evidence.database.counts.overlappingTasks -eq 0) $pgReport.evidence.database.counts.overlappingTasks
    $phase='complete'
} catch {
    $failed=$true;$failureKind=$_.Exception.GetType().Name
    $checks.Add([pscustomobject]@{name=('verification-exception-'+$phase);passed=$false;evidence=@{kind=$failureKind}})
    Write-Host "FAIL: verification-exception-$phase"
} finally {
    if ($childInvoked) {
        try {$pgReport=ImportPgReport} catch {
            $failed=$true
            $checks.Add([pscustomobject]@{name='pg-evidence-import';passed=$false;evidence=@{kind=$_.Exception.GetType().Name}})
        }
    }
    $report=[ordered]@{
        startedAt=$startedAt.ToString('o');finishedAt=[DateTime]::UtcNow.ToString('o');phase=$phase;passed=(-not $failed);failureKind=$failureKind
        baseUrl=$(if ($safeOrigin) {$BaseUrl} else {'(invalid)'})
        expected=@{users=1;sessions=1;runs=$runCount;queueCapacity=1000;workers=16}
        checks=$checks.ToArray();evidence=$evidence
        orderContract='Ten consecutive POSTs with no sleep/run wait; increasing PG seq must match every submission index/runId/clientMsgId.'
    }
    [IO.File]::WriteAllText($reportPath,($report|ConvertTo-Json -Depth 25),[Text.UTF8Encoding]::new($false))
}
Write-Output "Report: $reportPath"
if ($failed) {throw 'Session order verification failed; inspect the saved report.'}
