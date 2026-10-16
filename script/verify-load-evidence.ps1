<#
.SYNOPSIS
  Read-only PostgreSQL and engine-state evidence for mock M4-3C S0/S1 runs.
.NOTES
  Only SELECT is sent to the existing aiflow-postgres container. No cleanup occurs.
  Reports contain synthetic identifiers, counts and timings, never message text,
  node input/output text, errors, credentials or ownership-token values.
  Terminal dispatch rows retain historical lease fields. No active lease is the
  contract; terminal owner_token/lease_until are not required to be NULL.
  Save with one UTF-8 BOM for Windows PowerShell 5.1.
#>
param(
    [Parameter(Mandatory=$true)][string]$Prefix,
    [Parameter(Mandatory=$true)][int]$ExpectedUsers,
    [Parameter(Mandatory=$true)][int]$ExpectedSessions,
    [Parameter(Mandatory=$true)][int]$ExpectedRuns,
    [int]$ExpectedCanceledRuns = 0,
    [string]$BaseUrl = 'http://localhost:8082',
    [string]$OutFile = 'target/m4-3c/load-evidence-verify.json'
)
$ErrorActionPreference = 'Stop'
$checks = New-Object System.Collections.Generic.List[object]
$evidence = [ordered]@{}
$phase = 'parameters'
$failed = $false
$failureKind = $null
$startedAt = [DateTime]::UtcNow

function Check([string]$Name, [bool]$Passed, $Detail) {
    $checks.Add([pscustomobject]@{name=$Name; passed=$Passed; evidence=$Detail})
    if ($Passed) { Write-Output "PASS: $Name" } else { Write-Output "FAIL: $Name" }
}

function Api([string]$Path) {
    try {
        $result = Invoke-RestMethod -Method Get -Uri ($BaseUrl.TrimEnd('/')+$Path) -TimeoutSec 2
    } catch {
        throw 'API_STATE_READ_FAILED'
    }
    if ($null -eq $result -or $null -eq $result.data -or
        $null -eq $result.PSObject.Properties['code'] -or $result.code -ne 0) {
        throw 'API_STATE_CONTRACT_INVALID'
    }
    return $result.data
}

function DbSnapshot {
    # Prefix is validated before reaching this interpolation. One SELECT gives
    # every counter and row the same MVCC snapshot. No body/error text is selected.
    $query = @"
select snapshot from (
with us as (
  select id from users where starts_with(external_id,'$Prefix')
), ss as (
  select id from sessions where user_id in (select id from us)
), rr as (
  select * from workflow_runs where session_id in (select id from ss)
), tt as (
  select * from session_dispatch_tasks where run_id in (select id from rr)
), nn as (
  select * from node_runs where run_id in (select id from rr)
), mm as (
  select * from messages where session_id in (select id from ss)
), ordered_tasks as (
  select t.*, max(finished_at) over (
    partition by session_id order by seq
    rows between unbounded preceding and 1 preceding
  ) as preceding_finished_at from tt t
)
select json_build_object(
  'at',now(),
  'counts',json_build_object(
    'users',(select count(*) from us),
    'sessions',(select count(*) from ss),
    'runs',(select count(*) from rr),
    'tasks',(select count(*) from tt),
    'nodes',(select count(*) from nn),
    'userMessages',(select count(*) from mm where role='USER'),
    'assistantMessages',(select count(*) from mm where role='ASSISTANT'),
    'successfulRuns',(select count(*) from rr where status='SUCCEEDED'),
    'canceledRuns',(select count(*) from rr where status='CANCELED'),
    'unexpectedRuns',(select count(*) from rr where status not in ('SUCCEEDED','CANCELED')),
    'activeRuns',(select count(*) from rr where status in ('PENDING','RUNNING','WAITING_APPROVAL') or finished_at is null),
    'activeTasks',(select count(*) from tt where status in ('QUEUED','RUNNING') or finished_at is null),
    'activeLeases',(select count(*) from tt where status='RUNNING' and lease_until>now()),
    'activeNodes',(select count(*) from nn where status in ('RUNNING','WAITING') or ended_at is null),
    'overlappingTasks',(select count(*) from ordered_tasks where started_at<preceding_finished_at),
    'nonMockProviders',(select count(*) from nn where output->>'provider' is not null and output->>'provider'<>'mock'),
    'missingMockProviders',(select count(*) from nn where node_type in ('llm.chat','llm.vision') and status='SUCCEEDED' and coalesce(output->>'provider','')<>'mock'),
    'nonMockAttempts',(select count(*) from nn n where exists (
      select 1 from jsonb_array_elements_text(coalesce(n.output->'degradedFrom','[]'::jsonb)) p(id) where p.id<>'mock')),
    'canceledAssistantMessages',(select count(*) from mm where role='ASSISTANT' and run_id in (select id from rr where status='CANCELED'))
  ),
  'runs',(select coalesce(json_agg(json_build_object(
    'runId',r.id,'sessionId',r.session_id,'status',r.status,
    'workflowName',(select name from workflows where id=r.workflow_id),
    'startedAt',r.started_at,'finishedAt',r.finished_at,'steps',r.steps,
    'hasError',r.error is not null,
    'processTasks',(select count(*) from tt where run_id=r.id and kind='PROCESS'),
    'otherTasks',(select count(*) from tt where run_id=r.id and kind<>'PROCESS'),
    'userMessages',(select count(*) from mm where run_id=r.id and role='USER'),
    'assistantMessages',(select count(*) from mm where run_id=r.id and role='ASSISTANT'),
    'nodeCount',(select count(*) from nn where run_id=r.id),
    'startNodes',(select count(*) from nn where run_id=r.id and node_id='start' and node_type='start'),
    'llmNodes',(select count(*) from nn where run_id=r.id and node_id='llm' and node_type='llm.chat'),
    'endNodes',(select count(*) from nn where run_id=r.id and node_id='end' and node_type='end'),
    'duplicateNodes',(select count(*) from (select node_id from nn where run_id=r.id group by node_id having count(*)>1) d),
    'retries',(select count(*) from nn where run_id=r.id and attempt<>1),
    'unsuccessfulNodes',(select count(*) from nn where run_id=r.id and status<>'SUCCEEDED'),
    'successfulModelNodes',(select count(*) from nn where run_id=r.id and node_type in ('llm.chat','llm.vision') and status='SUCCEEDED'),
    'cancelRequests',(select count(*) from tt where run_id=r.id and cancel_requested_at is not null)
  ) order by r.session_id,r.created_at,r.id),'[]'::json) from rr r),
  'tasks',(select coalesce(json_agg(json_build_object(
    'taskId',t.id,'runId',t.run_id,'sessionId',t.session_id,'seq',t.seq,'kind',t.kind,'status',t.status,
    'runStatus',(select status from rr where id=t.run_id),
    'clientMsgId',(select client_msg_id from mm where run_id=t.run_id and role='USER' order by created_at,id limit 1),
    'createdAt',t.created_at,'startedAt',t.started_at,'finishedAt',t.finished_at,
    'precedingFinishedAt',t.preceding_finished_at,'overlap',coalesce(t.started_at<t.preceding_finished_at,false),
    'cancelRequestedAt',t.cancel_requested_at,'hasOwner',t.owner_token is not null,
    'leaseUntil',t.lease_until,'activeLease',t.status='RUNNING' and t.lease_until>now()
  ) order by t.session_id,t.seq),'[]'::json) from ordered_tasks t),
  'nodes',(select coalesce(json_agg(json_build_object(
    'runId',n.run_id,'nodeId',n.node_id,'nodeType',n.node_type,'attempt',n.attempt,'status',n.status,
    'startedAt',n.started_at,'endedAt',n.ended_at,
    'durationMs',extract(epoch from (n.ended_at-n.started_at))*1000,
    'provider',n.output->>'provider','model',n.output->>'model','hasError',n.error is not null
  ) order by n.run_id,n.started_at,n.node_id,n.attempt),'[]'::json) from nn n),
  'nodeSummary',(select coalesce(json_agg(json_build_object(
    'nodeType',x.node_type,'status',x.status,'provider',x.provider,'count',x.total,
    'minDurationMs',x.min_ms,'meanDurationMs',x.mean_ms,'maxDurationMs',x.max_ms
  ) order by x.node_type,x.status,x.provider),'[]'::json) from (
    select node_type,status,output->>'provider' provider,count(*) total,
      min(extract(epoch from (ended_at-started_at))*1000) min_ms,
      avg(extract(epoch from (ended_at-started_at))*1000) mean_ms,
      max(extract(epoch from (ended_at-started_at))*1000) max_ms
    from nn group by node_type,status,output->>'provider'
  ) x)
) as snapshot
) load_evidence
"@
    if (-not $query.TrimStart().StartsWith('select ',[StringComparison]::OrdinalIgnoreCase) -or $query.Contains(';')) {
        throw 'SELECT_ONLY_GUARD_FAILED'
    }
    $rows = @(& docker exec -e 'PGOPTIONS=-c default_transaction_read_only=on -c statement_timeout=2000' `
        aiflow-postgres psql -X -U aiflow -d aiflow -At -v 'ON_ERROR_STOP=1' -c $query 2>$null)
    if ($LASTEXITCODE -ne 0 -or $rows.Count -ne 1 -or [string]::IsNullOrWhiteSpace($rows[0])) {
        throw 'POSTGRES_SELECT_FAILED'
    }
    try { return ($rows[0] | ConvertFrom-Json) } catch { throw 'POSTGRES_JSON_INVALID' }
}

try {
    $prefixValid = $Prefix -cmatch '^[A-Za-z0-9-]{1,40}$'
    Check 'synthetic-prefix-valid' $prefixValid @{length=$Prefix.Length; allowed='letters,digits,hyphens;1..40'}
    if (-not $prefixValid) { throw 'PREFIX_INVALID' }
    $expectedValid = $ExpectedUsers -gt 0 -and $ExpectedSessions -gt 0 -and $ExpectedRuns -gt 0 -and
        $ExpectedCanceledRuns -ge 0 -and $ExpectedCanceledRuns -le $ExpectedRuns
    Check 'expected-counts-valid' $expectedValid @{
        users=$ExpectedUsers;sessions=$ExpectedSessions;runs=$ExpectedRuns;canceledRuns=$ExpectedCanceledRuns
    }
    if (-not $expectedValid) { throw 'EXPECTED_COUNTS_INVALID' }
    $baseUri = $null
    $baseValid = [Uri]::TryCreate($BaseUrl,[UriKind]::Absolute,[ref]$baseUri) -and
        $baseUri.Scheme -in @('http','https') -and [string]::IsNullOrEmpty($baseUri.UserInfo) -and
        [string]::IsNullOrEmpty($baseUri.Query) -and [string]::IsNullOrEmpty($baseUri.Fragment) -and
        $baseUri.AbsolutePath -eq '/'
    Check 'base-url-valid' $baseValid $null
    if (-not $baseValid) { throw 'BASE_URL_INVALID' }

    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    $polls = 0
    $phase = 'database-poll'
    do {
        $snapshot = DbSnapshot
        $polls++
        $counts = $snapshot.counts
        $closed = $counts.activeRuns -eq 0 -and $counts.activeTasks -eq 0 -and $counts.activeNodes -eq 0 -and
            $counts.runs -eq $ExpectedRuns -and $counts.assistantMessages -eq ($ExpectedRuns-$ExpectedCanceledRuns)
        if ($closed -or [DateTime]::UtcNow -ge $deadline) { break }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    $evidence.database = $snapshot
    $evidence.databasePolls = $polls
    $evidence.leaseContract = 'Terminal rows retain historical owner/lease fields; active RUNNING leases must be zero.'

    $phase = 'state-poll'
    do {
        $events = Api '/api/v1/runs/events/status'
        $dispatch = Api '/api/v1/runs/dispatch/status'
        $stateClosed = $events.connectionCount -eq 0 -and $events.bus.subscriptionCount -eq 0 -and
            $events.bus.readerCount -eq 0 -and $dispatch.activeWorkers -eq 0
        if ($stateClosed -or [DateTime]::UtcNow -ge $deadline) { break }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    $evidence.events = $events
    $evidence.dispatch = $dispatch
    $capabilities = Api '/api/v1/capabilities'
    $evidence.capabilities = @($capabilities.channels | ForEach-Object {
        [pscustomobject]@{
            channel=$_.channel;status=$_.status;activeProvider=$_.activeProvider;activeTier=$_.activeTier
            candidates=@($_.candidates | ForEach-Object {
                [pscustomobject]@{id=$_.id;tier=$_.tier;available=$_.available;expected=$_.expected}
            })
        }
    })

    $phase = 'checks'
    Check 'users-count' ($counts.users -eq $ExpectedUsers) @{actual=$counts.users;expected=$ExpectedUsers}
    Check 'sessions-count' ($counts.sessions -eq $ExpectedSessions) @{actual=$counts.sessions;expected=$ExpectedSessions}
    Check 'runs-count' ($counts.runs -eq $ExpectedRuns) @{actual=$counts.runs;expected=$ExpectedRuns}
    Check 'tasks-count' ($counts.tasks -eq $ExpectedRuns) @{actual=$counts.tasks;expected=$ExpectedRuns}
    Check 'user-messages-count' ($counts.userMessages -eq $ExpectedRuns) @{actual=$counts.userMessages;expected=$ExpectedRuns}
    Check 'assistant-messages-count' ($counts.assistantMessages -eq ($ExpectedRuns-$ExpectedCanceledRuns)) @{
        actual=$counts.assistantMessages;expected=($ExpectedRuns-$ExpectedCanceledRuns)
    }
    Check 'canceled-runs-count' ($counts.canceledRuns -eq $ExpectedCanceledRuns) @{actual=$counts.canceledRuns;expected=$ExpectedCanceledRuns}
    Check 'successful-runs-count' ($counts.successfulRuns -eq ($ExpectedRuns-$ExpectedCanceledRuns)) @{
        actual=$counts.successfulRuns;expected=($ExpectedRuns-$ExpectedCanceledRuns)
    }
    Check 'no-unexpected-run-status' ($counts.unexpectedRuns -eq 0) $counts.unexpectedRuns
    Check 'run-node-task-closed' ($counts.activeRuns -eq 0 -and $counts.activeTasks -eq 0 -and $counts.activeNodes -eq 0) @{
        activeRuns=$counts.activeRuns;activeTasks=$counts.activeTasks;activeNodes=$counts.activeNodes
    }
    $invalidTasks = @($snapshot.tasks | Where-Object {
        $_.status -notin @('DONE','FAILED','CANCELED') -or $null -eq $_.finishedAt -or
        ($_.status -in @('DONE','FAILED') -and $null -eq $_.startedAt)
    })
    Check 'tasks-terminal' ($invalidTasks.Count -eq 0) @($invalidTasks.taskId)
    $inconsistentTasks = @($snapshot.tasks | Where-Object {
        ($_.runStatus -eq 'SUCCEEDED' -and $_.status -ne 'DONE') -or
        ($_.runStatus -eq 'CANCELED' -and $_.status -notin @('DONE','CANCELED'))
    })
    Check 'task-run-terminal-consistent' ($inconsistentTasks.Count -eq 0) @($inconsistentTasks.taskId)
    Check 'no-active-lease' ($counts.activeLeases -eq 0 -and $counts.activeTasks -eq 0) $counts.activeLeases
    Check 'session-segments-no-overlap' ($counts.overlappingTasks -eq 0) $counts.overlappingTasks
    $badRunShape = @($snapshot.runs | Where-Object {$_.processTasks -ne 1 -or $_.otherTasks -ne 0 -or $_.userMessages -ne 1})
    Check 'each-run-one-process-and-user' ($badRunShape.Count -eq 0) @($badRunShape.runId)
    $badSuccessful = @($snapshot.runs | Where-Object {
        $_.status -eq 'SUCCEEDED' -and ($_.workflowName -ne 'basic-chat' -or $_.nodeCount -ne 3 -or
            $_.startNodes -ne 1 -or $_.llmNodes -ne 1 -or $_.endNodes -ne 1 -or $_.duplicateNodes -ne 0 -or $_.retries -ne 0 -or
            $_.unsuccessfulNodes -ne 0 -or $_.successfulModelNodes -ne 1 -or $_.assistantMessages -ne 1)
    })
    Check 'successful-basic-chat-three-nodes-once-attempt-one' ($badSuccessful.Count -eq 0) @($badSuccessful.runId)
    $badCanceled = @($snapshot.runs | Where-Object {
        $_.status -eq 'CANCELED' -and ($_.assistantMessages -ne 0 -or $_.cancelRequests -ne 1)
    })
    Check 'cancel-flag-and-no-assistant' ($badCanceled.Count -eq 0 -and $counts.canceledAssistantMessages -eq 0) @($badCanceled.runId)
    Check 'node-providers-mock-only' ($counts.nonMockProviders -eq 0 -and $counts.missingMockProviders -eq 0 -and $counts.nonMockAttempts -eq 0) @{
        nonMockProviders=$counts.nonMockProviders;missingMockProviders=$counts.missingMockProviders;nonMockAttempts=$counts.nonMockAttempts
    }
    $chat = @($evidence.capabilities | Where-Object {$_.channel -eq 'chat'})
    $embedding = @($evidence.capabilities | Where-Object {$_.channel -eq 'embedding'})
    $nonMockAvailable = @($chat.candidates | Where-Object {$_.available -and $_.id -ne 'mock'})
    Check 'active-chat-and-embedding-mock' ($chat.Count -eq 1 -and $chat[0].activeProvider -eq 'mock' -and
        $embedding.Count -eq 1 -and $embedding[0].activeProvider -eq 'mock-embedding' -and
        $embedding[0].activeTier -eq 'mock' -and $nonMockAvailable.Count -eq 0) @{
        chat=@($chat.activeProvider);embedding=@($embedding.activeProvider);nonMockAvailable=@($nonMockAvailable.id)
    }
    $eventsFields = @('mode','publishFailures','readFailures','readerCount','subscriptionCount')
    $missingEventsFields = @($eventsFields | Where-Object {$null -eq $events.bus.PSObject.Properties[$_]})
    $missingStreamFields = @(@('connectionCount','sseOverflow') | Where-Object {$null -eq $events.PSObject.Properties[$_]})
    $missingDispatchFields = @(@('enabled','workerEnabled','activeWorkers','claimed','recovered','leaseLost') | Where-Object {
        $null -eq $dispatch.PSObject.Properties[$_]
    })
    Check 'state-contract-fields-present' ($null -ne $events.bus -and $missingEventsFields.Count -eq 0 -and
        $missingStreamFields.Count -eq 0 -and $missingDispatchFields.Count -eq 0) @{
        missingBus=$missingEventsFields;missingStream=$missingStreamFields;missingDispatch=$missingDispatchFields
    }
    Check 'durable-dispatch-enabled' ($dispatch.enabled -eq $true -and $dispatch.workerEnabled -eq $true) @{
        enabled=$dispatch.enabled;workerEnabled=$dispatch.workerEnabled
    }
    Check 'events-no-failures-overflow' ($events.bus.publishFailures -eq 0 -and $events.bus.readFailures -eq 0 -and $events.sseOverflow -eq 0) @{
        publishFailures=$events.bus.publishFailures;readFailures=$events.bus.readFailures;sseOverflow=$events.sseOverflow
    }
    Check 'connections-subscriptions-readers-zero' ($events.connectionCount -eq 0 -and $events.bus.subscriptionCount -eq 0 -and
        $events.bus.readerCount -eq 0) @{connections=$events.connectionCount;subscriptions=$events.bus.subscriptionCount;readers=$events.bus.readerCount}
    Check 'workers-idle-no-lease-lost' ($dispatch.activeWorkers -eq 0 -and $dispatch.leaseLost -eq 0) @{
        activeWorkers=$dispatch.activeWorkers;leaseLost=$dispatch.leaseLost
    }
    $failed = @($checks | Where-Object {-not $_.passed}).Count -gt 0
    $phase = 'complete'
} catch {
    $failed = $true
    # Keep upstream error/body/connection data out of reports and console output.
    $failureKind = $_.Exception.GetType().Name
    Check ('verification-exception-'+$phase) $false @{kind=$failureKind}
} finally {
    $report = [pscustomobject]@{
        startedAt=$startedAt.ToString('o');finishedAt=[DateTime]::UtcNow.ToString('o');phase=$phase;passed=(-not $failed)
        prefix=$(if ($Prefix -cmatch '^[A-Za-z0-9-]{1,40}$') {$Prefix} else {'(invalid)'})
        baseUrl=$(if ($baseValid) {$BaseUrl} else {'(invalid)'})
        expected=[pscustomobject]@{users=$ExpectedUsers;sessions=$ExpectedSessions;runs=$ExpectedRuns;canceledRuns=$ExpectedCanceledRuns}
        failureKind=$failureKind;evidence=$evidence;checks=$checks.ToArray()
    }
    $reportPath = [IO.Path]::GetFullPath($OutFile)
    $parent = Split-Path -Parent $reportPath
    if ($parent) {[IO.Directory]::CreateDirectory($parent) | Out-Null}
    [IO.File]::WriteAllText($reportPath,($report | ConvertTo-Json -Depth 25),[Text.UTF8Encoding]::new($false))
}
Write-Output "Report: $OutFile"
if ($failed) { throw 'Load evidence verification failed; inspect the saved report.' }
