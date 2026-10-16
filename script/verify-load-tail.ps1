<#
.SYNOPSIS
  M4-3C S4 bounded admission and S5 parallel cancellation on a dedicated mock engine.
.NOTES
  Start the engine separately with queue capacity 2 and 16 workers. S5 uses four
  running delays and four cancel requests released after a shared CountdownEvent
  readiness barrier and ManualResetEventSlim start gate. HTTP start/end intervals
  must overlap with an observed peak of at least two; four accepted responses
  alone do not establish concurrent cancellation. The start gate is released in
  finally as well so a failed readiness check cannot strand waiting runspaces.
  No process is
  started/stopped. Synthetic records are retained. PostgreSQL commands are SELECT
  only, read-only, with a 2 second statement timeout. The one synthetic Redis lock
  is SET NX PX 30000 and released by owner-conditional PEXPIRE 1, never by DEL.
  No credentials, message bodies, node bodies or ownership-token values are saved.
  Run stream terminals are checked separately with verify-load-events.ps1 using
  this report's evidence.database.runs. Save with one UTF-8 BOM for PS 5.1.
#>
param(
    [string]$BaseUrl = 'http://localhost:8082',
    [int]$QueueCapacity = 2,
    [string]$Prefix = ('m43c-tail-' + [Guid]::NewGuid().ToString('N').Substring(0,24)),
    [string]$OutDir = 'target/m4-3c/tail'
)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$reportRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'target/m4-3c'))
$outputRoot = if ([IO.Path]::IsPathRooted($OutDir)) {[IO.Path]::GetFullPath($OutDir)} else {[IO.Path]::GetFullPath((Join-Path $repoRoot $OutDir))}
$rootPrefix = $reportRoot.TrimEnd([char[]]@('\','/'))+[IO.Path]::DirectorySeparatorChar
if ($outputRoot -ne $reportRoot -and -not $outputRoot.StartsWith($rootPrefix,[StringComparison]::OrdinalIgnoreCase)) {
    throw 'OutDir must be under target/m4-3c.'
}
$pathToCheck = $outputRoot
while ($pathToCheck -and $pathToCheck.StartsWith($repoRoot,[StringComparison]::OrdinalIgnoreCase)) {
    if (Test-Path -LiteralPath $pathToCheck) {
        if (((Get-Item -LiteralPath $pathToCheck -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'OutDir must not traverse a junction or symbolic link.'
        }
    }
    if ($pathToCheck -eq $repoRoot) {break}
    $pathToCheck=Split-Path -Parent $pathToCheck
}
[IO.Directory]::CreateDirectory($outputRoot) | Out-Null
$reportName=if ($Prefix -cmatch '^[A-Za-z0-9-]{1,40}$') {$Prefix+'-tail-report.json'} else {'invalid-'+[Guid]::NewGuid().ToString('N')+'-tail-report.json'}
$reportPath=Join-Path $outputRoot $reportName
$checks=New-Object System.Collections.Generic.List[object]
$evidence=[ordered]@{
    prefix=$(if ($Prefix -cmatch '^[A-Za-z0-9-]{1,40}$') {$Prefix} else {'(invalid)'})
    users=0;sessions=0;runs=0;canceledRuns=0;workflowsCreated=0
    sessionIds=@();runIds=@();s4=[ordered]@{};s5=[ordered]@{}
    database=$null
}
$phase='parameters'
$failed=$false
$failureKind=$null
$startedAt=[DateTime]::UtcNow
$lockKey=$null
$lockOwner=$null
$lockHeld=$false
$pool=$null
$cancelReadyGate=$null
$cancelStartGate=$null
$cancelJobs=New-Object System.Collections.Generic.List[object]
$delayRuns=New-Object System.Collections.Generic.List[object]
$cancelObservation=[ordered]@{}
$safeOrigin=$false
$cancelCount=4
$expectedSessions=$cancelCount+2
$expectedRuns=$cancelCount+$QueueCapacity+2

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
function Api([string]$Method,[string]$Path,$Body=$null,[int]$ExpectedStatus=200) {
    $requestArgs=@{Uri=($BaseUrl+$Path);Method=$Method;UseBasicParsing=$true;TimeoutSec=8;MaximumRedirection=0}
    if ($null -ne $Body) {
        $requestArgs.ContentType='application/json; charset=utf-8'
        $requestArgs.Body=[Text.Encoding]::UTF8.GetBytes(($Body|ConvertTo-Json -Depth 12 -Compress))
    }
    try {
        $response=Invoke-WebRequest @requestArgs
        $status=[int]$response.StatusCode
    } catch {
        $status=$null
        if ($null -ne $_.Exception.Response) {
            try {$status=[int]$_.Exception.Response.StatusCode} catch {}
        }
        if ($ExpectedStatus -ne 200 -and $status -eq $ExpectedStatus) {
            return [pscustomobject]@{httpStatus=$status;data=$null}
        }
        throw 'API_REQUEST_FAILED'
    }
    if ($status -ne $ExpectedStatus) {throw 'API_HTTP_STATUS_UNEXPECTED'}
    if ($ExpectedStatus -ne 200) {return [pscustomobject]@{httpStatus=$status;data=$null}}
    $text=$response.Content
    if ($text -is [byte[]]) {$text=[Text.Encoding]::UTF8.GetString($text)}
    try {$parsed=$text|ConvertFrom-Json} catch {throw 'API_JSON_INVALID'}
    if ($null -eq $parsed.data -or $null -eq $parsed.PSObject.Properties['code'] -or $parsed.code -ne 0) {
        throw 'API_RESPONSE_CONTRACT_INVALID'
    }
    return [pscustomobject]@{httpStatus=$status;data=$parsed.data}
}
function Sql([string]$Query) {
    if (-not $Query.TrimStart().StartsWith('select ',[StringComparison]::OrdinalIgnoreCase) -or $Query.Contains(';')) {
        throw 'SELECT_ONLY_GUARD_FAILED'
    }
    $rows=@(& docker exec -e 'PGOPTIONS=-c default_transaction_read_only=on -c statement_timeout=2000' `
        aiflow-postgres psql -X -U aiflow -d aiflow -At -v 'ON_ERROR_STOP=1' -c $Query 2>$null)
    if ($LASTEXITCODE -ne 0 -or $rows.Count -ne 1 -or [string]::IsNullOrWhiteSpace($rows[0])) {throw 'POSTGRES_SELECT_FAILED'}
    try {return ($rows[0]|ConvertFrom-Json)} catch {throw 'POSTGRES_JSON_INVALID'}
}
function SessionCounts([string]$SessionId,[string]$ClientMsgId) {
    $sid=GuidText $SessionId
    if ($ClientMsgId -cnotmatch '^[A-Za-z0-9-]{1,64}$') {throw 'CLIENT_MESSAGE_ID_INVALID'}
    return (Sql @"
select snapshot from (
with owned_session as (
 select s.id from sessions s join users u on u.id=s.user_id
 where s.id='$sid' and u.external_id='$Prefix'
)
select json_build_object(
 'messages',(select count(*) from messages where session_id in (select id from owned_session)),
 'runs',(select count(*) from workflow_runs where session_id in (select id from owned_session)),
 'tasks',(select count(*) from session_dispatch_tasks where session_id in (select id from owned_session)),
 'unfinishedTasks',(select count(*) from session_dispatch_tasks where session_id in (select id from owned_session) and status in ('QUEUED','RUNNING')),
 'executingNodes',(select count(*) from node_runs where run_id in (select id from workflow_runs where session_id in (select id from owned_session))),
 'clientMessages',(select count(*) from messages where session_id in (select id from owned_session) and client_msg_id='$ClientMsgId'),
 'clientRuns',(select count(distinct run_id) from messages where session_id in (select id from owned_session) and client_msg_id='$ClientMsgId'),
 'clientTasks',(select count(*) from session_dispatch_tasks where run_id in (select run_id from messages where session_id in (select id from owned_session) and client_msg_id='$ClientMsgId'))
) snapshot
) session_counts
"@)
}
function RunSnapshot([string]$RunId) {
    $id=GuidText $RunId
    return (Sql @"
select json_build_object(
 'runId',r.id,'status',r.status,'finishedAt',r.finished_at,
 'delayRunning',(select count(*) from node_runs where run_id=r.id and node_id='delay' and status='RUNNING'),
 'activeNodes',(select count(*) from node_runs where run_id=r.id and (status in ('RUNNING','WAITING') or ended_at is null)),
 'activeTasks',(select count(*) from session_dispatch_tasks where run_id=r.id and (status in ('QUEUED','RUNNING') or finished_at is null)),
 'invalidTasks',(select count(*) from session_dispatch_tasks where run_id=r.id and status not in ('DONE','CANCELED')),
 'assistantMessages',(select count(*) from messages where run_id=r.id and role='ASSISTANT')
) from workflow_runs r join sessions s on s.id=r.session_id join users u on u.id=s.user_id
where r.id='$id' and u.external_id='$Prefix'
"@)
}
function WaitRun([string]$RunId,[string]$WantStatus,[int]$Seconds=20) {
    $deadline=[DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        $run=RunSnapshot $RunId
        if ($run.status -eq $WantStatus -and $null -ne $run.finishedAt -and $run.activeNodes -eq 0 -and $run.activeTasks -eq 0 -and
            ($WantStatus -ne 'SUCCEEDED' -or $run.assistantMessages -eq 1)) {return $run}
        if ($run.status -in @('FAILED','DEGRADED') -or ($run.status -eq 'CANCELED' -and $WantStatus -ne 'CANCELED')) {throw 'RUN_REACHED_UNEXPECTED_TERMINAL'}
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'RUN_CLOSE_TIMEOUT'
}
function CreateSession([string]$UserId,[string]$Title,[string]$Workflow) {
    $response=Api 'POST' '/api/v1/sessions' @{userId=$UserId;title=$Title;workflowName=$Workflow}
    $id=GuidText $response.data.id
    $evidence.sessions++;$evidence.sessionIds+=@($id)
    return $id
}
function Submit([string]$SessionId,[string]$ClientMsgId,[string]$Content) {
    $response=Api 'POST' "/api/v1/sessions/$SessionId/messages" @{clientMsgId=$ClientMsgId;content=$Content}
    $id=GuidText $response.data.runId
    $evidence.runs++;$evidence.runIds+=@($id)
    Check ('accepted-'+$ClientMsgId) ($response.data.status -eq 'QUEUED') @{httpStatus=$response.httpStatus;status=$response.data.status;runId=$id}
    return $id
}
function LockRemaining {
    if (-not $lockHeld) {return -1}
    # Never read or print the stored ownership-token value.
    $rows=@(& docker exec aiflow-redis redis-cli --raw PTTL $lockKey 2>$null)
    if ($LASTEXITCODE -ne 0 -or $rows.Count -ne 1) {throw 'REDIS_PTTL_FAILED'}
    return [long]$rows[0]
}
function ReleaseSyntheticLock {
    if (-not $lockHeld) {return}
    $release="if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('pexpire',KEYS[1],1) else return 0 end"
    $rows=@(& docker exec aiflow-redis redis-cli --raw EVAL $release 1 $lockKey $lockOwner 2>$null)
    if ($LASTEXITCODE -ne 0 -or $rows.Count -ne 1) {throw 'REDIS_PEXPIRE_FAILED'}
    $evidence.s4.lockRelease=[pscustomobject]@{method='owner-conditional PEXPIRE 1';ownerMatched=([long]$rows[0] -eq 1)}
    $script:lockHeld=$false
}
function DatabaseSnapshot {
    return (Sql @"
select snapshot from (
with us as (select id from users where external_id='$Prefix'),
ss as (select id from sessions where user_id in (select id from us)),
rr as (select * from workflow_runs where session_id in (select id from ss)),
tt as (select * from session_dispatch_tasks where run_id in (select id from rr)),
nn as (select * from node_runs where run_id in (select id from rr)),
mm as (select * from messages where session_id in (select id from ss)),
ordered_tasks as (
 select t.*,max(finished_at) over(partition by session_id order by seq rows between unbounded preceding and 1 preceding) preceding_finished_at from tt t
)
select json_build_object(
 'at',now(),'counts',json_build_object(
  'users',(select count(*) from us),'sessions',(select count(*) from ss),'runs',(select count(*) from rr),
  'tasks',(select count(*) from tt),'nodes',(select count(*) from nn),
  'userMessages',(select count(*) from mm where role='USER'),'assistantMessages',(select count(*) from mm where role='ASSISTANT'),
  'successfulRuns',(select count(*) from rr where status='SUCCEEDED'),'canceledRuns',(select count(*) from rr where status='CANCELED'),
  'activeRuns',(select count(*) from rr where status not in ('SUCCEEDED','CANCELED') or finished_at is null),
  'activeTasks',(select count(*) from tt where status in ('QUEUED','RUNNING') or finished_at is null),
  'activeLeases',(select count(*) from tt where status='RUNNING' and lease_until>now()),
  'activeNodes',(select count(*) from nn where status in ('RUNNING','WAITING') or ended_at is null),
  'overlappingTasks',(select count(*) from ordered_tasks where started_at<preceding_finished_at),
  'nonMockProviders',(select count(*) from nn where output->>'provider' is not null and output->>'provider'<>'mock'),
  'missingMockProviders',(select count(*) from nn where node_type='llm.chat' and status='SUCCEEDED' and coalesce(output->>'provider','')<>'mock'),
  'canceledAssistantMessages',(select count(*) from mm where role='ASSISTANT' and run_id in (select id from rr where status='CANCELED'))
 ),
 'runs',(select coalesce(json_agg(json_build_object(
  'runId',r.id,'sessionId',r.session_id,'workflowName',(select name from workflows where id=r.workflow_id),
  'status',r.status,'startedAt',r.started_at,'finishedAt',r.finished_at,
  'processTasks',(select count(*) from tt where run_id=r.id and kind='PROCESS'),
  'otherTasks',(select count(*) from tt where run_id=r.id and kind<>'PROCESS'),
  'userMessages',(select count(*) from mm where run_id=r.id and role='USER'),
  'assistantMessages',(select count(*) from mm where run_id=r.id and role='ASSISTANT'),
  'nodeCount',(select count(*) from nn where run_id=r.id),
  'duplicateNodes',(select count(*) from (select node_id from nn where run_id=r.id group by node_id having count(*)>1) d),
  'retries',(select count(*) from nn where run_id=r.id and attempt<>1),
  'unsuccessfulNodes',(select count(*) from nn where run_id=r.id and status<>'SUCCEEDED'),
  'startNodes',(select count(*) from nn where run_id=r.id and node_id='start' and node_type='start'),
  'llmNodes',(select count(*) from nn where run_id=r.id and node_id='llm' and node_type='llm.chat'),
  'endNodes',(select count(*) from nn where run_id=r.id and node_id='end' and node_type='end'),
  'cancelRequests',(select count(*) from tt where run_id=r.id and cancel_requested_at is not null)
 ) order by r.created_at,r.id),'[]'::json) from rr r),
 'tasks',(select coalesce(json_agg(json_build_object(
  'taskId',t.id,'sessionId',t.session_id,'runId',t.run_id,'seq',t.seq,'kind',t.kind,'status',t.status,
  'runStatus',(select status from rr where id=t.run_id),
  'clientMsgId',(select client_msg_id from mm where run_id=t.run_id and role='USER' order by created_at,id limit 1),
  'createdAt',t.created_at,'startedAt',t.started_at,'finishedAt',t.finished_at,
  'precedingFinishedAt',t.preceding_finished_at,'overlap',coalesce(t.started_at<t.preceding_finished_at,false),
  'cancelRequestedAt',t.cancel_requested_at,'leaseUntil',t.lease_until
 ) order by t.session_id,t.seq),'[]'::json) from ordered_tasks t),
 'nodes',(select coalesce(json_agg(json_build_object(
  'runId',n.run_id,'nodeId',n.node_id,'nodeType',n.node_type,'attempt',n.attempt,'status',n.status,
  'startedAt',n.started_at,'endedAt',n.ended_at,'durationMs',extract(epoch from(n.ended_at-n.started_at))*1000,
  'provider',n.output->>'provider','model',n.output->>'model'
 ) order by n.run_id,n.started_at,n.node_id),'[]'::json) from nn n)
) snapshot
) load_tail
"@)
}

try {
    Check 'synthetic-prefix-valid' ($Prefix -cmatch '^[A-Za-z0-9-]{1,40}$') @{allowed='letters,digits,hyphens;1..40';length=$Prefix.Length}
    Check 'queue-capacity-two' ($QueueCapacity -eq 2) $QueueCapacity
    $origin=$null
    $safeOrigin=[Uri]::TryCreate($BaseUrl,[UriKind]::Absolute,[ref]$origin) -and $origin.Scheme -eq 'http' -and
        $origin.Host -in @('localhost','127.0.0.1') -and -not $origin.UserInfo -and -not $origin.Query -and -not $origin.Fragment -and $origin.AbsolutePath -eq '/'
    Check 'local-origin-valid' $safeOrigin $null
    $BaseUrl=$origin.GetLeftPart([UriPartial]::Authority)
    $phase='preflight'
    $existing=DatabaseSnapshot
    Check 'synthetic-prefix-unused' ($existing.counts.users -eq 0 -and $existing.counts.sessions -eq 0 -and $existing.counts.runs -eq 0) $existing.counts
    $caps=(Api 'GET' '/api/v1/capabilities').data
    $chat=@($caps.channels|Where-Object {$_.channel -eq 'chat'})
    $embedding=@($caps.channels|Where-Object {$_.channel -eq 'embedding'})
    Check 'mock-only-capabilities' ($chat.Count -eq 1 -and $chat[0].activeProvider -eq 'mock' -and $embedding.Count -eq 1 -and
        $embedding[0].activeProvider -eq 'mock-embedding' -and @($chat.candidates|Where-Object {$_.available -and $_.id -ne 'mock'}).Count -eq 0) $null
    $dispatch=(Api 'GET' '/api/v1/runs/dispatch/status').data
    Check 'durable-worker-idle' ($dispatch.enabled -eq $true -and $dispatch.workerEnabled -eq $true -and $dispatch.activeWorkers -eq 0 -and $dispatch.leaseLost -eq 0) $dispatch
    $phase='create-user'
    $user=(Api 'POST' '/api/v1/users' @{externalId=$Prefix;displayName='Synthetic load tail'}).data
    $userId=GuidText $user.id
    $evidence.users++;$evidence.userId=$userId
    $phase='s4-create-session'
    $s4Session=CreateSession $userId "$Prefix-s4" 'basic-chat'
    $evidence.s4.sessionId=$s4Session
    $phase='s4-lock'
    $lockKey='aiflow:lock:session:'+$s4Session
    $lockOwner=[Guid]::NewGuid().ToString('D')
    $rows=@(& docker exec aiflow-redis redis-cli --raw SET $lockKey $lockOwner NX PX 30000 2>$null)
    if ($LASTEXITCODE -ne 0 -or $rows.Count -ne 1 -or $rows[0] -ne 'OK') {throw 'SYNTHETIC_LOCK_NOT_ACQUIRED'}
    $lockHeld=$true
    $evidence.s4.lock=[pscustomobject]@{key=$lockKey;method='SET NX PX';ttlMs=30000;ownerRecorded=$false}
    $phase='s4-admit-capacity'
    $acceptedRuns=@()
    for ($index=1;$index -le $QueueCapacity;$index++) {
        $acceptedRuns+=@(Submit $s4Session "$Prefix-s4-$index" "synthetic capacity round $index")
    }
    $evidence.s4.acceptedRunIds=$acceptedRuns
    $thirdClientId="$Prefix-s4-retry"
    $before=SessionCounts $s4Session $thirdClientId
    $evidence.s4.before429=$before
    Check 's4-held-lock-blocked-execution' ($before.unfinishedTasks -eq $QueueCapacity -and $before.executingNodes -eq 0 -and (LockRemaining) -gt 0) $before
    $phase='s4-overflow'
    $overflowWatch=[Diagnostics.Stopwatch]::StartNew()
    $overflow=Api 'POST' "/api/v1/sessions/$s4Session/messages" @{clientMsgId=$thirdClientId;content='synthetic capacity retry'} 429
    $overflowWatch.Stop()
    $after=SessionCounts $s4Session $thirdClientId
    $evidence.s4.after429=$after
    $evidence.s4.overflow=[pscustomobject]@{httpStatus=$overflow.httpStatus;elapsedMs=$overflowWatch.ElapsedMilliseconds;clientMsgId=$thirdClientId}
    Check 's4-429-without-db-residue' ($overflow.httpStatus -eq 429 -and $before.messages -eq $after.messages -and $before.runs -eq $after.runs -and
        $before.tasks -eq $after.tasks -and $after.clientMessages -eq 0 -and $after.clientRuns -eq 0 -and $after.clientTasks -eq 0) @{before=$before;after=$after}
    Check 's4-429-fast-response' ($overflowWatch.ElapsedMilliseconds -lt 2000) @{elapsedMs=$overflowWatch.ElapsedMilliseconds;limitMs=2000}
    Check 's4-lock-still-held' ((LockRemaining) -gt 0) $null
    $phase='s4-release-lock'
    ReleaseSyntheticLock
    Check 's4-owner-release-applied' $evidence.s4.lockRelease.ownerMatched $evidence.s4.lockRelease
    $phase='s4-drain'
    foreach ($runId in $acceptedRuns) {WaitRun $runId 'SUCCEEDED' | Out-Null}
    $phase='s4-retry-same-client-id'
    $retryRun=Submit $s4Session $thirdClientId 'synthetic capacity retry'
    $evidence.s4.retryRunId=$retryRun
    WaitRun $retryRun 'SUCCEEDED' | Out-Null
    $retried=SessionCounts $s4Session $thirdClientId
    $evidence.s4.afterRetry=$retried
    Check 's4-client-id-retry-exactly-once' ($retried.clientMessages -eq 1 -and $retried.clientRuns -eq 1 -and $retried.clientTasks -eq 1 -and
        $retried.runs -eq ($QueueCapacity+1) -and $retried.tasks -eq ($QueueCapacity+1)) $retried

    $phase='s5-register-delay'
    $delayWorkflow="$Prefix-delay"
    Api 'POST' '/api/v1/workflows' @{id=$delayWorkflow;name=$delayWorkflow;version=1;nodes=@(
        @{id='start';type='start'},@{id='delay';type='delay';params=@{millis=30000};timeoutSeconds=60},
        @{id='end';type='end';params=@{outputs=@{reply='input'}}}
    );edges=@(@{from='start';to='delay'},@{from='delay';to='end'})} | Out-Null
    $evidence.workflowsCreated++;$evidence.s5.workflowName=$delayWorkflow
    $phase='s5-create-running-delays'
    for ($index=1;$index -le $cancelCount;$index++) {
        $sessionId=CreateSession $userId "$Prefix-s5-$index" $delayWorkflow
        $runId=Submit $sessionId "$Prefix-s5-$index" 'synthetic parallel cancellation'
        $delayRuns.Add([pscustomobject]@{runId=$runId;sessionId=$sessionId})
        $evidence.s5.delayRuns=$delayRuns.ToArray()
        $deadline=[DateTime]::UtcNow.AddSeconds(8)
        do {
            $run=RunSnapshot $runId
            if ($run.status -eq 'RUNNING' -and $run.delayRunning -eq 1) {break}
            if ($run.status -in @('SUCCEEDED','FAILED','DEGRADED','CANCELED')) {throw 'DELAY_ENDED_BEFORE_CANCELLATION'}
            Start-Sleep -Milliseconds 100
        } while ([DateTime]::UtcNow -lt $deadline)
        Check ('s5-delay-running-'+$index) ($run.status -eq 'RUNNING' -and $run.delayRunning -eq 1) @{runId=$runId}
    }
    $phase='s5-all-delays-still-running'
    $runningSnapshot=DatabaseSnapshot
    $evidence.s5.beforeCancelDatabase=$runningSnapshot
    $delayRunIds=@($delayRuns|ForEach-Object {$_.runId})
    $runningDelayNodes=@($runningSnapshot.nodes|Where-Object {$_.runId -in $delayRunIds -and $_.nodeId -eq 'delay' -and $_.status -eq 'RUNNING'})
    Check 's5-four-delays-concurrently-running' ($runningDelayNodes.Count -eq $cancelCount) $runningDelayNodes.Count
    $phase='s5-submit-short-run'
    $shortSession=CreateSession $userId "$Prefix-short" 'basic-chat'
    $shortWatch=[Diagnostics.Stopwatch]::StartNew()
    $shortSubmittedAt=[DateTime]::UtcNow
    $shortRun=Submit $shortSession "$Prefix-short" 'synthetic independent short run'
    $evidence.s5.shortRun=[ordered]@{runId=$shortRun;sessionId=$shortSession;submittedAt=$shortSubmittedAt.ToString('o')}
    # Observe completion while every 30 second delay is still active, before any
    # cancellation request. This establishes that the short session progressed.
    $shortClosed=WaitRun $shortRun 'SUCCEEDED' 8
    $shortWatch.Stop()
    $evidence.s5.shortRun.finishedAt=$shortClosed.finishedAt
    $evidence.s5.shortRun.observedElapsedMs=$shortWatch.ElapsedMilliseconds
    Check 's5-short-independent-completes-under-delay-duration' ($shortWatch.ElapsedMilliseconds -lt 30000) $evidence.s5.shortRun
    $stillRunning=DatabaseSnapshot
    $stillRunningNodes=@($stillRunning.nodes|Where-Object {$_.runId -in $delayRunIds -and $_.nodeId -eq 'delay' -and $_.status -eq 'RUNNING'})
    Check 's5-short-completed-with-four-delays-active' ($stillRunningNodes.Count -eq $cancelCount) $stillRunningNodes.Count

    $phase='s5-parallel-cancel'
    $cancelCode={param([string]$Origin,[string]$RunId,
        [Threading.CountdownEvent]$ReadyBarrier,[Threading.ManualResetEventSlim]$StartBarrier)
        $watch=New-Object Diagnostics.Stopwatch
        $readyAt=[DateTime]::UtcNow
        $at=$null;$endedAt=$null
        $httpStatus=$null;$responseStatus=$null;$failure=$null
        try {
            [void]$ReadyBarrier.Signal()
            if (-not $StartBarrier.Wait([TimeSpan]::FromSeconds(12))) {throw 'CANCEL_START_GATE_TIMEOUT'}
            $watch.Start()
            $at=[DateTime]::UtcNow
            $response=Invoke-WebRequest -UseBasicParsing -Method Post -Uri ($Origin+'/api/v1/runs/'+$RunId+'/cancel?actor=load-tail') -TimeoutSec 8 -MaximumRedirection 0
            $endedAt=[DateTime]::UtcNow
            $watch.Stop()
            $httpStatus=[int]$response.StatusCode
            $text=$response.Content
            if ($text -is [byte[]]) {$text=[Text.Encoding]::UTF8.GetString($text)}
            $parsed=$text|ConvertFrom-Json
            if ($parsed.code -ne 0 -or $null -eq $parsed.data) {throw 'CANCEL_RESPONSE_CONTRACT_INVALID'}
            $responseStatus=$parsed.data.status
        } catch {
            if ($null -ne $at -and $null -eq $endedAt) {$endedAt=[DateTime]::UtcNow}
            if ($watch.IsRunning) {$watch.Stop()}
            $failure=$_.Exception.GetType().Name
            if ($null -ne $_.Exception.Response) {try {$httpStatus=[int]$_.Exception.Response.StatusCode} catch {}}
        }
        [pscustomobject]@{runId=$RunId;readyAt=$readyAt.ToString('o');
            requestedAt=$(if ($null -ne $at) {$at.ToString('o')} else {$null});
            responseAt=$(if ($null -ne $endedAt) {$endedAt.ToString('o')} else {$null});
            requestStartedAt=$(if ($null -ne $at) {$at.ToString('o')} else {$null});
            requestEndedAt=$(if ($null -ne $endedAt) {$endedAt.ToString('o')} else {$null});
            httpStatus=$httpStatus;responseStatus=$responseStatus;responseLatencyMs=$watch.ElapsedMilliseconds;failureKind=$failure}
    }
    $cancelReadyGate=[Threading.CountdownEvent]::new($cancelCount)
    $cancelStartGate=[Threading.ManualResetEventSlim]::new($false)
    $pool=[RunspaceFactory]::CreateRunspacePool($cancelCount,$cancelCount)
    $pool.Open()
    foreach ($delayRun in $delayRuns) {
        $shell=[PowerShell]::Create()
        $shell.RunspacePool=$pool
        [void]$shell.AddScript($cancelCode.ToString()).AddArgument($BaseUrl).AddArgument($delayRun.runId).AddArgument($cancelReadyGate).AddArgument($cancelStartGate)
        $handle=$shell.BeginInvoke()
        $cancelJobs.Add([pscustomobject]@{runId=$delayRun.runId;shell=$shell;handle=$handle;collected=$false})
    }
    $allReady=$cancelReadyGate.Wait([TimeSpan]::FromSeconds(8))
    $evidence.s5.cancelBarrier=[ordered]@{method='CountdownEvent+ManualResetEventSlim';participants=$cancelCount;
        allReady=$allReady;remaining=$cancelReadyGate.CurrentCount;releasedAt=$null}
    Check 's5-all-cancel-runspaces-ready' $allReady $evidence.s5.cancelBarrier
    $cancelWatch=[Diagnostics.Stopwatch]::StartNew()
    $evidence.s5.cancelBarrier.releasedAt=[DateTime]::UtcNow.ToString('o')
    $cancelStartGate.Set()
    $cancelResponses=New-Object System.Collections.Generic.List[object]
    foreach ($job in $cancelJobs) {
        $rows=@($job.shell.EndInvoke($job.handle))
        $job.collected=$true
        if ($rows.Count -ne 1) {throw 'PARALLEL_CANCEL_RESPONSE_MISSING'}
        $cancelResponses.Add($rows[0])
        $evidence.s5.cancelResponses=$cancelResponses.ToArray()
    }
    $evidence.s5.cancelHttpOverallMs=$cancelWatch.ElapsedMilliseconds
    $invalidCancels=@($cancelResponses|Where-Object {$_.httpStatus -ne 200 -or $_.responseStatus -notin @('CANCEL_REQUESTED','CANCELED') -or $null -ne $_.failureKind})
    Check 's5-four-cancel-requests-accepted' ($cancelResponses.Count -eq $cancelCount -and $invalidCancels.Count -eq 0) $cancelResponses.ToArray()
    $transitions=New-Object System.Collections.Generic.List[object]
    foreach ($response in $cancelResponses) {
        if ($null -eq $response.requestStartedAt -or $null -eq $response.requestEndedAt) {throw 'CANCEL_HTTP_INTERVAL_MISSING'}
        $startTicks=[DateTime]::Parse($response.requestStartedAt).ToUniversalTime().Ticks
        $endTicks=[DateTime]::Parse($response.requestEndedAt).ToUniversalTime().Ticks
        if ($endTicks -lt $startTicks) {throw 'CANCEL_HTTP_INTERVAL_INVALID'}
        if ($endTicks -eq $startTicks) {continue}
        $transitions.Add([pscustomobject]@{ticks=$startTicks;delta=1})
        $transitions.Add([pscustomobject]@{ticks=$endTicks;delta=-1})
    }
    $activeCancelRequests=0;$peakCancelRequests=0
    # End events precede starts at the same tick: adjacent requests do not overlap.
    foreach ($transition in ($transitions|Sort-Object -Property @{Expression={$_.ticks};Ascending=$true},@{Expression={$_.delta};Ascending=$true})) {
        $activeCancelRequests+=$transition.delta
        if ($activeCancelRequests -gt $peakCancelRequests) {$peakCancelRequests=$activeCancelRequests}
    }
    $evidence.s5.cancelHttpOverlap=[pscustomobject]@{intervalCount=$cancelResponses.Count;peak=$peakCancelRequests;
        requiredPeak=2;method='HTTP start inclusive/end exclusive; end sorted before start at equal timestamps'}
    Check 's5-cancel-http-intervals-overlap' ($peakCancelRequests -ge 2) $evidence.s5.cancelHttpOverlap
    $phase='s5-cancel-close'
    $deadline=[DateTime]::UtcNow.AddSeconds(15)
    do {
        $closedSnapshot=DatabaseSnapshot
        $evidence.database=$closedSnapshot
        $canceledRuns=@($closedSnapshot.runs|Where-Object {$_.runId -in $delayRunIds -and $_.status -eq 'CANCELED'})
        $evidence.canceledRuns=$canceledRuns.Count
        foreach ($run in $canceledRuns) {
            if (-not $cancelObservation.Contains($run.runId)) {
                $response=@($cancelResponses|Where-Object {$_.runId -eq $run.runId})[0]
                $at=[DateTime]::UtcNow
                $cancelObservation[$run.runId]=[pscustomobject]@{runId=$run.runId;requestedAt=$response.requestedAt;
                    canceledObservedAt=$at.ToString('o');cancelLatencyMs=($at-[DateTime]::Parse($response.requestedAt).ToUniversalTime()).TotalMilliseconds}
            }
        }
        $evidence.s5.cancelObservations=@($cancelObservation.Values)
        $active=@($closedSnapshot.tasks|Where-Object {$_.runId -in $delayRunIds -and ($_.status -notin @('DONE','CANCELED') -or $null -eq $_.finishedAt)})
        $activeNodes=@($closedSnapshot.nodes|Where-Object {$_.runId -in $delayRunIds -and ($_.status -in @('RUNNING','WAITING') -or $null -eq $_.endedAt)})
        if ($canceledRuns.Count -eq $cancelCount -and $active.Count -eq 0 -and $activeNodes.Count -eq 0) {break}
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)
    $cancelWatch.Stop()
    $evidence.s5.cancelCloseOverallMs=$cancelWatch.ElapsedMilliseconds
    Check 's5-all-canceled-and-nodes-tasks-closed' ($canceledRuns.Count -eq $cancelCount -and $active.Count -eq 0 -and $activeNodes.Count -eq 0) @{
        canceledRuns=$canceledRuns.Count;activeTasks=$active.Count;activeNodes=$activeNodes.Count;overallMs=$cancelWatch.ElapsedMilliseconds
    }

    $phase='final-pg-evidence'
    $database=DatabaseSnapshot
    $evidence.database=$database
    $counts=$database.counts
    Check 'synthetic-counts-match' ($counts.users -eq 1 -and $counts.sessions -eq $expectedSessions -and $counts.runs -eq $expectedRuns -and $counts.tasks -eq $expectedRuns -and
        $counts.userMessages -eq $expectedRuns -and $counts.assistantMessages -eq ($expectedRuns-$cancelCount) -and
        $counts.successfulRuns -eq ($expectedRuns-$cancelCount) -and $counts.canceledRuns -eq $cancelCount) $counts
    Check 'no-active-run-node-task-lease-or-overlap' ($counts.activeRuns -eq 0 -and $counts.activeTasks -eq 0 -and $counts.activeNodes -eq 0 -and
        $counts.activeLeases -eq 0 -and $counts.overlappingTasks -eq 0) $counts
    Check 'canceled-no-assistant-and-mock-models-only' ($counts.canceledAssistantMessages -eq 0 -and $counts.nonMockProviders -eq 0 -and $counts.missingMockProviders -eq 0) $null
    $badRuns=@($database.runs|Where-Object {$_.processTasks -ne 1 -or $_.otherTasks -ne 0 -or $_.userMessages -ne 1 -or $_.duplicateNodes -ne 0 -or $_.retries -ne 0 -or
        ($_.status -eq 'CANCELED' -and ($_.assistantMessages -ne 0 -or $_.cancelRequests -ne 1)) -or
        ($_.status -eq 'SUCCEEDED' -and ($_.workflowName -ne 'basic-chat' -or $_.nodeCount -ne 3 -or $_.startNodes -ne 1 -or $_.llmNodes -ne 1 -or $_.endNodes -ne 1 -or $_.assistantMessages -ne 1 -or $_.unsuccessfulNodes -ne 0))})
    Check 'one-process-user-and-node-attempt-per-run' ($badRuns.Count -eq 0) @($badRuns.runId)
    $badTasks=@($database.tasks|Where-Object {($_.runStatus -eq 'SUCCEEDED' -and $_.status -ne 'DONE') -or
        ($_.runStatus -eq 'CANCELED' -and $_.status -notin @('DONE','CANCELED'))})
    Check 'run-and-task-terminals-consistent' ($badTasks.Count -eq 0) @($badTasks.taskId)
    $phase='final-state-evidence'
    $deadline=[DateTime]::UtcNow.AddSeconds(10)
    do {
        $events=(Api 'GET' '/api/v1/runs/events/status').data
        $dispatch=(Api 'GET' '/api/v1/runs/dispatch/status').data
        if ($events.connectionCount -eq 0 -and $events.bus.subscriptionCount -eq 0 -and $events.bus.readerCount -eq 0 -and $dispatch.activeWorkers -eq 0) {break}
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)
    $evidence.events=$events;$evidence.dispatch=$dispatch
    Check 'events-no-failures-overflow-and-no-readers' ($events.bus.mode -eq 'redis' -and $events.bus.publishFailures -eq 0 -and $events.bus.readFailures -eq 0 -and
        $events.sseOverflow -eq 0 -and $events.connectionCount -eq 0 -and $events.bus.subscriptionCount -eq 0 -and $events.bus.readerCount -eq 0) $events
    Check 'dispatch-idle-without-lease-loss' ($dispatch.enabled -eq $true -and $dispatch.workerEnabled -eq $true -and $dispatch.activeWorkers -eq 0 -and $dispatch.leaseLost -eq 0) $dispatch
    $phase='complete'
} catch {
    $failed=$true;$failureKind=$_.Exception.GetType().Name
    $checks.Add([pscustomobject]@{name=('verification-exception-'+$phase);passed=$false;evidence=@{kind=$failureKind}})
    Write-Host "FAIL: verification-exception-$phase"
} finally {
    # Readiness/launch failures must release any runspace already waiting to start.
    if ($null -ne $cancelStartGate) {try {$cancelStartGate.Set()} catch {}}
    foreach ($job in $cancelJobs) {
        try {if (-not $job.collected) {$job.shell.Stop()};$job.shell.Dispose()} catch {}
    }
    if ($null -ne $pool) {try {$pool.Close();$pool.Dispose()} catch {}}
    if ($null -ne $cancelReadyGate) {try {$cancelReadyGate.Dispose()} catch {}}
    if ($null -ne $cancelStartGate) {try {$cancelStartGate.Dispose()} catch {}}
    try {ReleaseSyntheticLock} catch {
        $failed=$true
        $checks.Add([pscustomobject]@{name='synthetic-lock-release';passed=$false;evidence=@{kind=$_.Exception.GetType().Name;ttlMs=30000}})
    }
    if ($Prefix -cmatch '^[A-Za-z0-9-]{1,40}$' -and $safeOrigin) {
        try {
            $evidence.database=DatabaseSnapshot
            $evidence.users=$evidence.database.counts.users
            $evidence.sessions=$evidence.database.counts.sessions
            $evidence.runs=$evidence.database.counts.runs
            $evidence.canceledRuns=$evidence.database.counts.canceledRuns
        } catch {
            $failed=$true
            $checks.Add([pscustomobject]@{name='final-report-db-snapshot';passed=$false;evidence=@{kind=$_.Exception.GetType().Name}})
        }
    }
    $report=[ordered]@{
        startedAt=$startedAt.ToString('o');finishedAt=[DateTime]::UtcNow.ToString('o');phase=$phase;passed=(-not $failed);failureKind=$failureKind
        baseUrl=$(if ($safeOrigin) {$BaseUrl} else {'(invalid)'})
        expected=@{users=1;sessions=$expectedSessions;runs=$expectedRuns;canceledRuns=$cancelCount;assistantMessages=($expectedRuns-$cancelCount);queueCapacity=2;workers=16}
        checks=$checks.ToArray();evidence=$evidence
        leaseContract='Terminal rows retain historical lease fields; no RUNNING lease may remain.'
        cancelLatencyContract='Per-run latency ends at first observed CANCELED; overall ends when run/node/task all close.'
        eventVerification='Run verify-load-events.ps1 separately against this report; this script reads PostgreSQL snapshots.'
    }
    [IO.File]::WriteAllText($reportPath,($report|ConvertTo-Json -Depth 25),[Text.UTF8Encoding]::new($false))
}
Write-Output "Report: $reportPath"
if ($failed) {throw 'Load tail verification failed; inspect the saved report.'}
