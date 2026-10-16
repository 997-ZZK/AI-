<#
.SYNOPSIS
  M4-3A real PostgreSQL approval/cancellation and resume/cancellation races.
.NOTES
  Start two mock engines sharing PostgreSQL/Redis, with at least one durable worker enabled.
  Each case creates a fresh synthetic workflow/session. No schema change, DELETE or restart occurs.
  Only start/delay/approval/end nodes run; no model or external tool is called.
  API requests use explicit UTF-8. Save this script with exactly one UTF-8 BOM for PowerShell 5.1.
#>
param(
    [string]$BaseUrlA = 'http://localhost:8082',
    [string]$BaseUrlB = 'http://localhost:8083',
    [string]$PostgresContainer = 'aiflow-postgres',
    [string]$Database = 'aiflow',
    [string]$DatabaseUser = 'aiflow',
    [string]$Assignee = 'reviewer-dispatch-races',
    [int]$Iterations = 5,
    [int]$ResumeDelayMillis = 2000,
    [switch]$RequireReceiverOnlyB,
    [string]$OutFile = 'target/m4-3/dispatch-races-verify.json'
)
$ErrorActionPreference = 'Stop'
if ($Iterations -lt 1 -or $Iterations -gt 50 -or $ResumeDelayMillis -lt 1000 -or $ResumeDelayMillis -gt 5000) {
    throw 'Use 1..50 iterations and a 1000..5000 ms post-approval delay'
}
Add-Type -AssemblyName System.Net.Http
$checks = New-Object System.Collections.Generic.List[object]
$cases = New-Object System.Collections.Generic.List[object]
$stamp = [Guid]::NewGuid().ToString('N')
$http = [Net.Http.HttpClient]::new()
$http.Timeout = [TimeSpan]::FromSeconds(8)

function Check([string]$Name, [bool]$Passed, [object]$Detail) {
    $checks.Add([pscustomobject]@{ name=$Name; passed=$Passed; evidence=$Detail })
    if (-not $Passed) { throw "FAILED: $Name" }
    Write-Output "PASS: $Name"
}
function Api([string]$Base, [string]$Method, [string]$Path, $Body=$null) {
    if ($null -eq $Body) { return (Invoke-RestMethod -Method $Method -Uri ($Base+$Path) -TimeoutSec 8).data }
    $bytes = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 20 -Compress))
    return (Invoke-RestMethod -Method $Method -Uri ($Base+$Path) -TimeoutSec 8 -ContentType 'application/json; charset=utf-8' -Body $bytes).data
}
function Sql([string]$Query) {
    if (-not $Query.TrimStart().StartsWith('select ',[StringComparison]::OrdinalIgnoreCase)) { throw 'Only SELECT is allowed' }
    $rows = @(& docker exec -e 'PGOPTIONS=-c statement_timeout=2000' $PostgresContainer psql -X -U $DatabaseUser -d $Database -At -v 'ON_ERROR_STOP=1' -c $Query)
    if ($LASTEXITCODE -ne 0) { throw 'Read-only PostgreSQL assertion failed' }
    return ,$rows
}
function GuidText([string]$Value) { return ([Guid]::Parse($Value)).ToString() }
function Snapshot([string]$RunId) {
    $id = GuidText $RunId
    # One SQL statement sees one MVCC snapshot; separate queries could combine two legitimate instants.
    $rows = Sql @"
select json_build_object(
  'runId',r.id,'runStatus',r.status,'runFinishedEpoch',extract(epoch from r.finished_at),
  'tasks',(select coalesce(json_agg(json_build_object('id',t.id,'seq',t.seq,'kind',t.kind,'status',t.status,'checkpointId',t.checkpoint_id,'cancelRequested',t.cancel_requested_at is not null,'cancelRequestedEpoch',extract(epoch from t.cancel_requested_at)) order by t.seq),'[]'::json) from session_dispatch_tasks t where t.run_id=r.id),
  'approvals',(select coalesce(json_agg(json_build_object('id',a.id,'status',a.status) order by a.created_at),'[]'::json) from approvals a where a.run_id=r.id),
  'checkpointCount',(select count(*) from run_checkpoints c where c.run_id=r.id),
  'pendingCheckpoints',(select count(*) from run_checkpoints c where c.run_id=r.id and c.consumed_at is null),
  'beforeRows',(select count(*) from node_runs n where n.run_id=r.id and n.node_id='before'),
  'afterRows',(select count(*) from node_runs n where n.run_id=r.id and n.node_id='after'),
  'activeNodes',(select count(*) from node_runs n where n.run_id=r.id and n.status in ('RUNNING','WAITING')),
  'duplicateNodes',(select count(*) from (select n.node_id from node_runs n where n.run_id=r.id group by n.node_id having count(*) > 1) d),
  'userMessages',(select count(*) from messages m where m.run_id=r.id and m.role='USER'),
  'assistantMessages',(select count(*) from messages m where m.run_id=r.id and m.role='ASSISTANT')
) from workflow_runs r where r.id='$id'
"@
    if ($rows.Count -ne 1 -or -not $rows[0]) { throw "Run $id has no unique PostgreSQL snapshot" }
    return ($rows[0] | ConvertFrom-Json)
}
function WaitSnapshot([string]$RunId, [DateTime]$Deadline, [bool]$WantRunningResume=$false) {
    $last=$null
    while ([DateTime]::UtcNow -lt $Deadline) {
        $last=Snapshot $RunId
        $unfinished=@($last.tasks | Where-Object { $_.status -in @('QUEUED','RUNNING') })
        if ($WantRunningResume) {
            if ($last.runStatus -eq 'RUNNING' -and @($last.tasks | Where-Object { $_.kind -eq 'RESUME' -and $_.status -eq 'RUNNING' }).Count -eq 1) { return $last }
            if ($last.runStatus -in @('SUCCEEDED','CANCELED','FAILED','DEGRADED')) { throw 'Resume ended before its worker/cancel race could be observed' }
        } elseif ($last.runStatus -in @('SUCCEEDED','CANCELED','FAILED','DEGRADED') -and $unfinished.Count -eq 0) {
            return $last
        }
        Start-Sleep -Milliseconds 40
    }
    throw "Run $RunId did not settle within the shared 10-second deadline (last=$($last.runStatus))"
}
function NewCase([string]$Stage,[int]$Index) {
    $name="dispatch-race-$Stage-$Index-$stamp"
    $definition=@{ id=$name;name=$name;version=1;nodes=@(
        @{id='start';type='start'},
        @{id='before';type='delay';params=@{millis=100}},
        @{id='approve';type='approval';params=@{title='Synthetic dispatch race';assignees=$Assignee;waitSeconds=600;onTimeout='reject';maxLevel=1;onExhausted='reject';onReject='fallback';fallback='Synthetic rejected request'}},
        @{id='after';type='delay';params=@{millis=$ResumeDelayMillis};timeoutSeconds=10},
        @{id='end';type='end';params=@{outputs=@{reply='input';approvalStatus='approvalStatus'}}}
    );edges=@(@{from='start';to='before'},@{from='before';to='approve'},@{from='approve';to='after'},@{from='after';to='end'}) }
    Api $BaseUrlA 'POST' '/api/v1/workflows' $definition | Out-Null
    $session=Api $BaseUrlA 'POST' '/api/v1/sessions' @{userId=$script:userId;title=$name;workflowName=$name}
    $accepted=Api $BaseUrlA 'POST' "/api/v1/sessions/$($session.id)/messages" @{clientMsgId="race-$Stage-$Index-$stamp";content='Synthetic approval and cancellation race'}
    $deadline=[DateTime]::UtcNow.AddSeconds(10)
    do {
        $initial=Snapshot $accepted.runId
        if ($initial.runStatus -eq 'WAITING_APPROVAL' -and @($initial.tasks | Where-Object { $_.status -in @('QUEUED','RUNNING') }).Count -eq 0) { break }
        if ($initial.runStatus -in @('FAILED','CANCELED','SUCCEEDED','DEGRADED')) { throw 'Synthetic run ended before suspension' }
        Start-Sleep -Milliseconds 40
    } while ([DateTime]::UtcNow -lt $deadline)
    $pending=@($initial.approvals | Where-Object { $_.status -eq 'PENDING' })
    if ($initial.runStatus -ne 'WAITING_APPROVAL' -or $pending.Count -ne 1 -or $initial.pendingCheckpoints -ne 1) {
        throw 'Fresh race run did not reach a complete, committed suspension in 10 seconds'
    }
    return [pscustomobject]@{workflowName=$name;sessionId=$session.id;runId=$accepted.runId;approvalId=$pending[0].id;initial=$initial}
}
function NewHttpRequest([string]$Base,[string]$Path,$Body=$null) {
    $request=[Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Post,($Base+$Path))
    if ($null -ne $Body) {
        $request.Content=[Net.Http.StringContent]::new(($Body | ConvertTo-Json -Depth 10 -Compress),[Text.Encoding]::UTF8,'application/json')
    }
    return $request
}
function HttpOutcome($Task,[string]$Operation) {
    try { $response=$Task.GetAwaiter().GetResult() }
    catch { throw "Race HTTP request timed out or failed: $Operation" }
    try {
        $body=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
        return [pscustomobject]@{operation=$Operation;httpStatus=[int]$response.StatusCode;code=$body.code;status=$body.data.status}
    } finally { $response.Dispose() }
}
function Validate([string]$Name,$State,$Requests,[double]$ElapsedMs,[bool]$WorkerObserved) {
    $process=@($State.tasks | Where-Object { $_.kind -eq 'PROCESS' })
    $resume=@($State.tasks | Where-Object { $_.kind -eq 'RESUME' })
    Check "${Name}:http200Or409" (@($Requests | Where-Object { $_.httpStatus -notin @(200,409) }).Count -eq 0) $Requests
    Check "${Name}:bounded10Seconds" ($ElapsedMs -lt 10000) $ElapsedMs
    Check "${Name}:oneValidTerminalOutcome" ($State.runStatus -in @('SUCCEEDED','CANCELED')) $State.runStatus
    Check "${Name}:noTaskCheckpointOrNodeOrphans" ($process.Count -eq 1 -and $process[0].status -eq 'DONE' -and $resume.Count -le 1 -and @($State.tasks | Where-Object { $_.status -notin @('DONE','CANCELED') }).Count -eq 0 -and $State.checkpointCount -eq 1 -and $State.pendingCheckpoints -eq 0 -and $State.activeNodes -eq 0 -and @($State.approvals | Where-Object { $_.status -eq 'PENDING' }).Count -eq 0) $State
    Check "${Name}:executedNodesNotReplayed" ($State.beforeRows -eq 1 -and $State.afterRows -le 1 -and $State.duplicateNodes -eq 0 -and $State.userMessages -eq 1) @{beforeRows=$State.beforeRows;afterRows=$State.afterRows;duplicateNodes=$State.duplicateNodes;userMessages=$State.userMessages}
    if ($State.runStatus -eq 'SUCCEEDED') {
        Check "${Name}:successFactsAgree" ($resume.Count -eq 1 -and $resume[0].status -eq 'DONE' -and @($State.approvals | Where-Object { $_.status -eq 'APPROVED' }).Count -eq 1 -and $State.assistantMessages -eq 1) $State
    } else {
        Check "${Name}:cancellationFactsAgree" ($State.assistantMessages -eq 0 -and @($State.approvals | Where-Object { $_.status -in @('APPROVED','CANCELED') }).Count -eq 1) $State
    }
    $decision=@($Requests | Where-Object { $_.operation -eq 'decision' })
    if ($decision.Count -eq 1 -and $decision[0].httpStatus -eq 409) {
        Check "${Name}:losingDecisionHasNoResume" ($State.runStatus -eq 'CANCELED' -and $resume.Count -eq 0) $State
    }
    $cancel=@($Requests | Where-Object { $_.operation -eq 'cancel' })
    if ($cancel.Count -eq 1 -and $cancel[0].httpStatus -eq 200) {
        # CANCEL_REQUESTED can arrive after success was committed but before the task closes.
        # It cannot undo that earlier terminal fact. Require timestamp evidence for this race.
        $lateFlag=@($State.tasks | Where-Object { $_.cancelRequested -and $null -ne $_.cancelRequestedEpoch -and $State.runFinishedEpoch -le $_.cancelRequestedEpoch })
        Check "${Name}:acceptedCancelHasConsistentTerminalTiming" ($State.runStatus -eq 'CANCELED' -or ($State.runStatus -eq 'SUCCEEDED' -and $lateFlag.Count -gt 0)) @{runStatus=$State.runStatus;runFinishedEpoch=$State.runFinishedEpoch;lateFlags=$lateFlag}
    }
    if ($WorkerObserved) {
        Check "${Name}:claimedResumeWasCanceledOrFinished" ($resume.Count -eq 1 -and $resume[0].status -in @('DONE','CANCELED')) $resume
    }
}

try {
    $dispatchA=Api $BaseUrlA 'GET' '/api/v1/runs/dispatch/status'
    $dispatchB=Api $BaseUrlB 'GET' '/api/v1/runs/dispatch/status'
    Check 'bothOriginsUseDurableDispatchWithAnActiveWorker' ($dispatchA.enabled -and $dispatchB.enabled -and ($dispatchA.workerEnabled -or $dispatchB.workerEnabled)) @{A=$dispatchA;B=$dispatchB}
    if ($RequireReceiverOnlyB) {
        Check 'instanceARunsWhileInstanceBOnlyAcceptsControlRequests' ($dispatchA.workerEnabled -and -not $dispatchB.workerEnabled) @{A=$dispatchA;B=$dispatchB}
    }
    foreach ($base in @($BaseUrlA,$BaseUrlB)) {
        $capabilities=Api $base 'GET' '/api/v1/capabilities'
        $chat=@($capabilities.channels | Where-Object { $_.channel -eq 'chat' })[0]
        Check "mockProvider:$base" ($chat.activeProvider -eq 'mock') $chat.activeProvider
    }
    $script:userId=(Api $BaseUrlA 'POST' '/api/v1/users' @{externalId="dispatch-races-$stamp";displayName='Dispatch race validation'}).id
    for ($index=0;$index -lt $Iterations;$index++) {
        $case=NewCase 'decision-cancel' $index
        $decisionRequest=NewHttpRequest $BaseUrlA "/api/v1/approvals/$($case.approvalId)/decision" @{decision='APPROVE';decidedBy=$Assignee;comment='Synthetic concurrent decision'}
        $cancelRequest=NewHttpRequest $BaseUrlB "/api/v1/runs/$($case.runId)/cancel?actor=race-check"
        $clock=[Diagnostics.Stopwatch]::StartNew()
        $deadline=[DateTime]::UtcNow.AddSeconds(10)
        try {
            # Both asynchronous sends start before either result is awaited.
            $decisionTask=$http.SendAsync($decisionRequest)
            $cancelTask=$http.SendAsync($cancelRequest)
            $requests=@((HttpOutcome $decisionTask 'decision'),(HttpOutcome $cancelTask 'cancel'))
        } finally { $decisionRequest.Dispose();$cancelRequest.Dispose() }
        $state=WaitSnapshot $case.runId $deadline
        $clock.Stop()
        $cases.Add([pscustomobject]@{stage='decision-cancel';iteration=$index;workflowName=$case.workflowName;sessionId=$case.sessionId;runId=$case.runId;requests=$requests;elapsedMs=$clock.Elapsed.TotalMilliseconds;workerObserved=$false;state=$state})
        Validate "decisionCancel:$index" $state $requests $clock.Elapsed.TotalMilliseconds $false

        $case=NewCase 'worker-cancel' $index
        Api $BaseUrlA 'POST' "/api/v1/approvals/$($case.approvalId)/decision" @{decision='APPROVE';decidedBy=$Assignee;comment='Synthetic worker race'} | Out-Null
        $observed=WaitSnapshot $case.runId ([DateTime]::UtcNow.AddSeconds(10)) $true
        $cancelRequest=NewHttpRequest $BaseUrlB "/api/v1/runs/$($case.runId)/cancel?actor=race-check"
        $clock=[Diagnostics.Stopwatch]::StartNew()
        $deadline=[DateTime]::UtcNow.AddSeconds(10)
        try { $requests=@(HttpOutcome ($http.SendAsync($cancelRequest)) 'cancel') }
        finally { $cancelRequest.Dispose() }
        $state=WaitSnapshot $case.runId $deadline
        $clock.Stop()
        $cases.Add([pscustomobject]@{stage='worker-cancel';iteration=$index;workflowName=$case.workflowName;sessionId=$case.sessionId;runId=$case.runId;requests=$requests;elapsedMs=$clock.Elapsed.TotalMilliseconds;workerObserved=$true;observed=$observed;state=$state})
        Validate "workerCancel:$index" $state $requests $clock.Elapsed.TotalMilliseconds $true
    }
} finally {
    $http.Dispose()
    $parent=Split-Path -Parent $OutFile
    if ($parent) { [IO.Directory]::CreateDirectory([IO.Path]::GetFullPath($parent)) | Out-Null }
    $report=[pscustomobject]@{at=[DateTime]::UtcNow.ToString('o');baseUrlA=$BaseUrlA;baseUrlB=$BaseUrlB;iterations=$Iterations;modelOrToolCalls=$false;differentCancellationWorkerProven=[bool]$RequireReceiverOnlyB;checks=$checks.ToArray();cases=$cases.ToArray()}
    [IO.File]::WriteAllText([IO.Path]::GetFullPath($OutFile),($report | ConvertTo-Json -Depth 18),[Text.UTF8Encoding]::new($false))
}
Write-Output "Report: $OutFile"
