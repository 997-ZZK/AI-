<#
.SYNOPSIS
  M4-3A real PostgreSQL two-instance dispatch contract validation.
.NOTES
  Start two mock engines against the same PostgreSQL/Redis database. No restart/delete is performed.
  PostgreSQL reads contain UUIDs, statuses, numeric sequences and timestamps only.
  Optional -PreApprovalToolId skill.list requires enabled tools/skills; otherwise a delay node proves
  no replay of an already executed node (and the report explicitly records that narrower scope).
  Both API origins are exercised. To strictly prove cancellation by a different worker process,
  start B with its worker disabled and pass -RequireReceiverOnlyB; the report records that mode.
#>
param(
    [string]$BaseUrlA = 'http://localhost:8082',
    [string]$BaseUrlB = 'http://localhost:8083',
    [string]$PostgresContainer = 'aiflow-postgres',
    [string]$Database = 'aiflow',
    [string]$DatabaseUser = 'aiflow',
    [string]$Assignee = 'reviewer-distributed',
    [string]$PreApprovalToolId,
    [int]$SequentialMessages = 10,
    [int]$DelayMillis = 300,
    [switch]$RequireReceiverOnlyB,
    [string]$OutFile = 'target/m4-3/distributed-flow-verify.json'
)
$ErrorActionPreference = 'Stop'
$checks = New-Object System.Collections.Generic.List[object]
$evidence = [ordered]@{}
$stamp = [Guid]::NewGuid().ToString('N')
if ($SequentialMessages -lt 2 -or $DelayMillis -lt 100) { throw 'Use at least 2 messages and a 100 ms delay' }

function Check([string]$Name, [bool]$Passed, [object]$Detail) {
    $checks.Add([pscustomobject]@{ name = $Name; passed = $Passed; evidence = $Detail })
    if (-not $Passed) { throw "FAILED: $Name" }
    Write-Output "PASS: $Name"
}
function Api([string]$Base, [string]$Method, [string]$Path, $Body = $null) {
    if ($null -eq $Body) { return (Invoke-RestMethod -Method $Method -Uri ($Base + $Path)).data }
    $bytes = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 20 -Compress))
    return (Invoke-RestMethod -Method $Method -Uri ($Base + $Path) -ContentType 'application/json; charset=utf-8' -Body $bytes).data
}
function Sql([string]$Query) {
    if (-not $Query.TrimStart().StartsWith('select ', [StringComparison]::OrdinalIgnoreCase)) { throw 'Only SELECT is allowed' }
    $lines = @(& docker exec $PostgresContainer psql -X -U $DatabaseUser -d $Database -At -F '|' -c $Query)
    if ($LASTEXITCODE -ne 0) { throw 'Read-only PostgreSQL assertion failed' }
    return ,$lines
}
function GuidText([string]$Value) { return ([Guid]::Parse($Value)).ToString() }
function WaitRun([string]$Run, [string]$Status = 'SUCCEEDED', [int]$Seconds = 45) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    $last = $null
    while ([DateTime]::UtcNow -lt $deadline) {
        $last = Api $BaseUrlB 'GET' "/api/v1/runs/$Run"
        if ($last.status -eq $Status) { return $last }
        if ($last.status -in @('FAILED','DEGRADED','CANCELED') -and $last.status -ne $Status) { throw "Run $Run ended $($last.status), expected $Status" }
        Start-Sleep -Milliseconds 100
    }
    throw "Run $Run did not reach $Status (last=$($last.status))"
}
function Tasks([string]$Session) {
    $id = GuidText $Session
    $rows = Sql "select run_id,seq,kind,status,coalesce(extract(epoch from started_at),0),coalesce(extract(epoch from finished_at),0) from session_dispatch_tasks where session_id='$id' order by seq"
    return @($rows | Where-Object { $_ } | ForEach-Object {
        $parts = $_ -split '\|'
        [pscustomobject]@{ runId=$parts[0]; seq=[long]$parts[1]; kind=$parts[2]; status=$parts[3]; start=[double]::Parse($parts[4],[Globalization.CultureInfo]::InvariantCulture); finish=[double]::Parse($parts[5],[Globalization.CultureInfo]::InvariantCulture) }
    })
}
function WaitTasks([string]$Session, [int]$Count) {
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    do {
        $tasks = @(Tasks $Session)
        if ($tasks.Count -eq $Count -and @($tasks | Where-Object { $_.status -in @('QUEUED','RUNNING') }).Count -eq 0) { return $tasks }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Session $Session did not finish $Count task segments"
}
function NewSession([string]$Workflow, [string]$Title) {
    return Api $BaseUrlA 'POST' '/api/v1/sessions' @{ userId=$script:userId; title=$Title; workflowName=$Workflow }
}
function Send([string]$Base, [string]$Session, [string]$Key) {
    return Api $Base 'POST' "/api/v1/sessions/$Session/messages" @{ clientMsgId=$Key; content='synthetic distributed flow check' }
}
function DelayWorkflow([string]$Name, [int]$Millis) {
    return @{ id=$Name; name=$Name; version=1; nodes=@(
        @{ id='start'; type='start' }, @{ id='delay'; type='delay'; params=@{ millis=$Millis }; timeoutSeconds=60 },
        @{ id='end'; type='end'; params=@{ outputs=@{ reply='input' } } }
    ); edges=@(@{from='start';to='delay'},@{from='delay';to='end'}) }
}

try {
    $statusA = Api $BaseUrlA 'GET' '/api/v1/runs/dispatch/status'
    $statusB = Api $BaseUrlB 'GET' '/api/v1/runs/dispatch/status'
    Check 'persistentDispatchEnabledOnBothOrigins' ($statusA.enabled -and $statusB.enabled -and $statusA.workerEnabled) @{ A=$statusA; B=$statusB }
    if ($RequireReceiverOnlyB) { Check 'receiverOnlyBProvesDifferentCancellationOwner' (-not $statusB.workerEnabled) $statusB }
    foreach ($base in @($BaseUrlA,$BaseUrlB)) {
        $capabilities = Api $base 'GET' '/api/v1/capabilities'
        $chat = @($capabilities.channels | Where-Object { $_.channel -eq 'chat' })[0]
        Check "mockProvider:$base" ($chat.activeProvider -eq 'mock') $chat.activeProvider
    }
    $user = Api $BaseUrlA 'POST' '/api/v1/users' @{ externalId="dispatch-$stamp"; displayName='Distributed validation' }
    $script:userId = $user.id
    $flow = "dispatch-delay-$stamp"
    Api $BaseUrlA 'POST' '/api/v1/workflows' (DelayWorkflow $flow $DelayMillis) | Out-Null
    $session = NewSession $flow 'Sequential cross-origin submissions'
    $runs = New-Object System.Collections.Generic.List[string]
    for ($index=0; $index -lt $SequentialMessages; $index++) {
        $base = $BaseUrlA
        if (($index % 2) -eq 1) { $base=$BaseUrlB }
        $accepted = Send $base $session.id "seq-$stamp-$index"
        $runs.Add([string]$accepted.runId)
    }
    foreach ($run in $runs) { WaitRun $run | Out-Null }
    $tasks = @(WaitTasks $session.id $SequentialMessages)
    $ordered = ($tasks.Count -eq $runs.Count)
    $nonoverlap = $true
    for ($index=0; $index -lt $tasks.Count; $index++) {
        if ($tasks[$index].runId -ne $runs[$index] -or $tasks[$index].kind -ne 'PROCESS' -or $tasks[$index].status -ne 'DONE') { $ordered=$false }
        if ($index -gt 0 -and $tasks[$index].start -lt $tasks[$index-1].finish) { $nonoverlap=$false }
    }
    Check 'sameSessionTaskSeqMatchesTenCrossOriginAdmissions' $ordered $tasks
    Check 'sameSessionExecutionSegmentsDoNotOverlap' $nonoverlap $tasks
    $evidence.sequentialSession = $session.id

    $parallelFlow = "dispatch-parallel-$stamp"
    Api $BaseUrlA 'POST' '/api/v1/workflows' (DelayWorkflow $parallelFlow 2000) | Out-Null
    $left = NewSession $parallelFlow 'Parallel left'
    $right = NewSession $parallelFlow 'Parallel right'
    $leftRun = Send $BaseUrlA $left.id "parallel-left-$stamp"
    $rightRun = Send $BaseUrlB $right.id "parallel-right-$stamp"
    WaitRun $leftRun.runId | Out-Null
    WaitRun $rightRun.runId | Out-Null
    $leftTask = @(WaitTasks $left.id 1)[0]
    $rightTask = @(WaitTasks $right.id 1)[0]
    Check 'differentSessionsExecuteInParallel' ([Math]::Max($leftTask.start,$rightTask.start) -lt [Math]::Min($leftTask.finish,$rightTask.finish)) @{ left=$leftTask; right=$rightTask }

    $duplicateA = Send $BaseUrlA $session.id "idempotent-$stamp"
    $duplicateB = Send $BaseUrlB $session.id "idempotent-$stamp"
    Check 'crossOriginClientMsgIdReturnsSameRun' ($duplicateA.runId -eq $duplicateB.runId -and $duplicateA.messageId -eq $duplicateB.messageId) @{ A=$duplicateA.runId; B=$duplicateB.runId }
    WaitRun $duplicateA.runId | Out-Null
    $duplicateRun = GuidText $duplicateA.runId
    $duplicateCount = Sql "select count(*) from session_dispatch_tasks where run_id='$duplicateRun' and kind='PROCESS'"
    Check 'idempotentMessageCreatesOnlyOneProcessTask' ([int]$duplicateCount[0] -eq 1) $duplicateCount[0]

    $approvalFlow = "dispatch-approval-$stamp"
    $pre = @{ id='before'; type='delay'; params=@{ millis=100 } }
    $preKind = 'delay'
    if ($PreApprovalToolId) { $pre=@{ id='before'; type='tool.call'; params=@{ tool=$PreApprovalToolId; args=@{} } }; $preKind='tool.call' }
    $definition = @{ id=$approvalFlow; name=$approvalFlow; version=1; nodes=@(
        @{id='start';type='start'}, $pre,
        @{id='approve';type='approval';params=@{title='Distributed approval';assignees=$Assignee;waitSeconds=600;onTimeout='reject';maxLevel=1;onExhausted='reject';onReject='fallback';fallback='Rejected synthetic request'}},
        @{id='end';type='end';params=@{outputs=@{approvalStatus='approvalStatus';reply='input'}}}
    ); edges=@(@{from='start';to='before'},@{from='before';to='approve'},@{from='approve';to='end'}) }
    Api $BaseUrlA 'POST' '/api/v1/workflows' $definition | Out-Null
    $approvalSession = NewSession $approvalFlow 'Cross-origin approval'
    $first = Send $BaseUrlA $approvalSession.id "approve-first-$stamp"
    WaitRun $first.runId 'WAITING_APPROVAL' | Out-Null
    $second = Send $BaseUrlB $approvalSession.id "approve-second-$stamp"
    WaitRun $second.runId 'WAITING_APPROVAL' | Out-Null
    Check 'waitingApprovalReleasesSessionSoNextMessageCanSuspend' $true @{ first=$first.runId; second=$second.runId }
    $chain = @(Api $BaseUrlB 'GET' "/api/v1/runs/$($first.runId)/approvals")
    $approval = @($chain | Where-Object { $_.status -eq 'PENDING' })[0]
    Api $BaseUrlB 'POST' "/api/v1/approvals/$($approval.approvalId)/decision" @{decision='APPROVE';decidedBy=$Assignee;comment='Synthetic cross-origin decision'} | Out-Null
    $finished = WaitRun $first.runId
    Check 'instanceBDecisionResumesSubmittedRun' ($finished.output.approvalStatus -eq 'APPROVED') $finished.status
    $approvalRun = GuidText $first.runId
    $preCount = Sql "select count(*) from node_runs where run_id='$approvalRun' and node_id='before'"
    Check 'alreadyExecutedNodeIsNotReplayedOnResume' ([int]$preCount[0] -eq 1) @{nodeType=$preKind;toolId=$PreApprovalToolId;attemptRows=$preCount[0]}
    $resumeCount = Sql "select count(*) from session_dispatch_tasks where run_id='$approvalRun' and kind='RESUME'"
    Check 'approvalDecisionCreatesOneDurableResumeTask' ([int]$resumeCount[0] -eq 1) $resumeCount[0]
    $decisionStatus = 200
    try { Api $BaseUrlA 'POST' "/api/v1/approvals/$($approval.approvalId)/decision" @{decision='APPROVE';decidedBy=$Assignee} | Out-Null }
    catch { if ($_.Exception.Response) { $decisionStatus=[int]$_.Exception.Response.StatusCode } else { throw } }
    $resumeAfterDuplicate = Sql "select count(*) from session_dispatch_tasks where run_id='$approvalRun' and kind='RESUME'"
    Check 'duplicateDecision409DoesNotCreateSecondResume' ($decisionStatus -eq 409 -and [int]$resumeAfterDuplicate[0] -eq 1) @{status=$decisionStatus;resumeTasks=$resumeAfterDuplicate[0]}
    Api $BaseUrlB 'POST' "/api/v1/runs/$($second.runId)/cancel?actor=distributed-check" | Out-Null
    WaitRun $second.runId 'CANCELED' | Out-Null
    $secondChain = @(Api $BaseUrlA 'GET' "/api/v1/runs/$($second.runId)/approvals")
    Check 'cancelWaitingFromInstanceBClosesApproval' (@($secondChain | Where-Object {$_.status -eq 'PENDING'}).Count -eq 0) $second.runId

    $cancelFlow = "dispatch-cancel-$stamp"
    Api $BaseUrlA 'POST' '/api/v1/workflows' (DelayWorkflow $cancelFlow 10000) | Out-Null
    $cancelSession = NewSession $cancelFlow 'Durable cancellation'
    $running = Send $BaseUrlA $cancelSession.id "cancel-running-$stamp"
    WaitRun $running.runId 'RUNNING' | Out-Null
    Api $BaseUrlB 'POST' "/api/v1/runs/$($running.runId)/cancel?actor=distributed-check" | Out-Null
    WaitRun $running.runId 'CANCELED' | Out-Null
    $runningId = GuidText $running.runId
    $cancelFlag = Sql "select count(*) from session_dispatch_tasks where run_id='$runningId' and cancel_requested_at is not null"
    Check 'instanceBCancellationReachesDurableRunningTask' ([int]$cancelFlag[0] -eq 1) @{ runId=$running.runId;differentWorkerProven=[bool]$RequireReceiverOnlyB }
    $evidence.approvedRun=$first.runId
    $evidence.canceledRun=$running.runId
    $evidence.preApprovalNodeType=$preKind
    $evidence.toolInvocationReplayCovered=($preKind -eq 'tool.call')
    $evidence.differentCancellationWorkerProven=[bool]$RequireReceiverOnlyB
    $evidence.uncovered=@('queued/running crash recovery','forced lease-token replacement','capacity 429 rollback')
} finally {
    $parent = Split-Path -Parent $OutFile
    if ($parent) { [IO.Directory]::CreateDirectory([IO.Path]::GetFullPath($parent)) | Out-Null }
    $report=[pscustomobject]@{ at=[DateTime]::UtcNow.ToString('o');baseUrlA=$BaseUrlA;baseUrlB=$BaseUrlB;evidence=$evidence;checks=$checks.ToArray() }
    [IO.File]::WriteAllText([IO.Path]::GetFullPath($OutFile),($report | ConvertTo-Json -Depth 15),[Text.UTF8Encoding]::new($false))
}
Write-Output "Report: $OutFile"
