<#
.SYNOPSIS
  M4-3A synthetic-session lease, ownership loss, crash recovery and optional 429 assertions.
.NOTES
  Small lock/task TTLs must be configured by the caller. Models must be mock.
  Redis mutations affect only UUID sessions created by this invocation and never credentials.
  Crash requires A worker on/B worker off, a caller-provided PID and matching JVM validation marker.
  After crash, start a survivor worker and rerun with -RecoveryReport; the script starts no process.
  Nothing deletes files, volumes, database rows or Redis keys. A test lock is released via expiry.
#>
param(
    [string]$BaseUrlA = 'http://localhost:8082',
    [string]$BaseUrlB = 'http://localhost:8083',
    [string]$PostgresContainer = 'aiflow-postgres',
    [string]$RedisContainer = 'aiflow-redis',
    [string]$Database = 'aiflow',
    [string]$DatabaseUser = 'aiflow',
    [int]$LockTtlMillis = 1000,
    [int]$TaskLeaseMillis = 1000,
    [switch]$SkipLeaseChecks,
    [int]$CrashEnginePid = 0,
    [string]$CrashProcessMarker,
    [string]$PreCrashToolId,
    [string]$RecoveryReport,
    [string]$CapacityBaseUrl,
    [int]$QueueCapacity = 2,
    [string]$OutFile = 'target/m4-3/dispatch-failures-verify.json'
)
$ErrorActionPreference = 'Stop'
$checks = New-Object System.Collections.Generic.List[object]
$evidence = [ordered]@{}
$stamp = [Guid]::NewGuid().ToString('N')
$phase = 'lease-checks'

function Check([string]$Name, [bool]$Passed, [object]$Detail) {
    $checks.Add([pscustomobject]@{name=$Name;passed=$Passed;evidence=$Detail})
    if (-not $Passed) { throw "FAILED: $Name" }
    Write-Output "PASS: $Name"
}
function Api([string]$Base, [string]$Method, [string]$Path, $Body = $null) {
    if ($null -eq $Body) { return (Invoke-RestMethod -Method $Method -Uri ($Base+$Path)).data }
    $bytes=[Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 20 -Compress))
    return (Invoke-RestMethod -Method $Method -Uri ($Base+$Path) -ContentType 'application/json; charset=utf-8' -Body $bytes).data
}
function Sql([string]$Query) {
    if (-not $Query.TrimStart().StartsWith('select ',[StringComparison]::OrdinalIgnoreCase)) { throw 'Only SELECT is allowed' }
    $rows=@(& docker exec $PostgresContainer psql -X -U $DatabaseUser -d $Database -At -F '|' -c $Query)
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL assertion failed' }
    return ,$rows
}
function Redis([string[]]$Arguments) {
    $rows=@(& docker exec $RedisContainer redis-cli --raw @Arguments)
    if ($LASTEXITCODE -ne 0) { throw 'Redis test operation failed' }
    return ,$rows
}
function GuidText([string]$Value) { return ([Guid]::Parse($Value)).ToString() }
function WaitRun([string]$Base,[string]$Run,[string]$Expected,[int]$Seconds=45) {
    $deadline=[DateTime]::UtcNow.AddSeconds($Seconds)
    $last=$null
    do {
        $last=Api $Base 'GET' "/api/v1/runs/$Run"
        if ($last.status -eq $Expected) { return $last }
        if ($last.status -in @('SUCCEEDED','DEGRADED','FAILED','CANCELED') -and $last.status -ne $Expected) { throw "Run $Run ended $($last.status), expected $Expected" }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Run $Run timeout expecting $Expected (last=$($last.status))"
}
function WaitNode([string]$Run,[string]$Node,[string]$Expected='RUNNING') {
    $id=GuidText $Run
    if ($Node -notmatch '^[a-z]+$' -or $Expected -notmatch '^[A-Z_]+$') { throw 'Invalid test node/status' }
    $deadline=[DateTime]::UtcNow.AddSeconds(15)
    do {
        $rows=Sql "select count(*) from node_runs where run_id='$id' and node_id='$Node' and status='$Expected'"
        if ([int]$rows[0] -eq 1) { return }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Node $Node did not reach $Expected"
}
function NewSession([string]$Flow) {
    return Api $BaseUrlA 'POST' '/api/v1/sessions' @{userId=$script:userId;title='Synthetic dispatch failure validation';workflowName=$Flow}
}
function Send([string]$Base,[string]$Session,[string]$Key) {
    return Api $Base 'POST' "/api/v1/sessions/$Session/messages" @{clientMsgId=$Key;content='synthetic dispatch failure validation'}
}
function Flow([string]$Name,[int]$Millis,[string]$Tool) {
    $nodes=@(@{id='start';type='start'})
    $edges=@()
    $previous='start'
    if ($Tool) {
        $nodes+=@{id='before';type='tool.call';params=@{tool=$Tool;args=@{}}}
        $edges+=@{from='start';to='before'}
        $previous='before'
    }
    $nodes+=@{id='delay';type='delay';params=@{millis=$Millis};timeoutSeconds=60}
    $nodes+=@{id='end';type='end';params=@{outputs=@{reply='input'}}}
    $edges+=@{from=$previous;to='delay'}
    $edges+=@{from='delay';to='end'}
    return @{id=$Name;name=$Name;version=1;nodes=$nodes;edges=$edges}
}
function CheckMock([string]$Base) {
    $capabilities=Api $Base 'GET' '/api/v1/capabilities'
    $chat=@($capabilities.channels | Where-Object {$_.channel -eq 'chat'})[0]
    Check "mockProvider:$Base" ($chat.activeProvider -eq 'mock') $chat.activeProvider
}

try {
    if ($RecoveryReport) {
        $prior=Get-Content -LiteralPath $RecoveryReport -Raw | ConvertFrom-Json
        if ($prior.phase -ne 'crashed-awaiting-survivor' -or -not $prior.evidence.crashedRun -or -not $prior.evidence.queuedRun) { throw 'RecoveryReport is not a completed crash preparation report' }
        CheckMock $BaseUrlB
        $status=Api $BaseUrlB 'GET' '/api/v1/runs/dispatch/status'
        Check 'survivorWorkerEnabled' ($status.enabled -and $status.workerEnabled) $status
        $crashed=WaitRun $BaseUrlB $prior.evidence.crashedRun 'FAILED'
        $queued=WaitRun $BaseUrlB $prior.evidence.queuedRun 'SUCCEEDED'
        Check 'crashedRunningFailsRatherThanReplays' ($crashed.status -eq 'FAILED') $crashed.runId
        Check 'queuedSegmentSurvivesOriginProcessCrash' ($queued.status -eq 'SUCCEEDED') $queued.runId
        $id=GuidText $prior.evidence.crashedRun
        $rows=Sql "select count(*) from session_dispatch_tasks where run_id='$id' and kind='PROCESS' and status='FAILED'"
        Check 'reaperClosesExactlyOneFailedProcessTask' ([int]$rows[0] -eq 1) $rows[0]
        $nodeCount=Sql "select count(*) from node_runs where run_id='$id' and node_id='delay'"
        Check 'crashedNodeHasOnlyOneAttempt' ([int]$nodeCount[0] -eq 1) $nodeCount[0]
        if ($prior.evidence.preCrashToolId) {
            $toolCount=Sql "select count(*) from node_runs where run_id='$id' and node_id='before'"
            Check 'preCrashToolIsNotReplayedBySurvivor' ([int]$toolCount[0] -eq [int]$prior.evidence.preCrashToolRows -and [int]$toolCount[0] -eq 1) $toolCount[0]
        }
        $evidence.priorReport=$RecoveryReport
        $evidence.crashedRun=$prior.evidence.crashedRun
        $evidence.queuedRun=$prior.evidence.queuedRun
        $phase='recovery-verified'
    } else {
        CheckMock $BaseUrlA
        CheckMock $BaseUrlB
        $user=Api $BaseUrlA 'POST' '/api/v1/users' @{externalId="dispatch-fault-$stamp";displayName='Synthetic fault validation'}
        $script:userId=$user.id

        if (-not $SkipLeaseChecks) {
            if ($LockTtlMillis -lt 500 -or $LockTtlMillis -gt 5000 -or $TaskLeaseMillis -lt 500 -or $TaskLeaseMillis -gt 5000) { throw 'Configure small TTLs between 500 and 5000 ms for this check' }
            $duration=4*[Math]::Max($LockTtlMillis,$TaskLeaseMillis)+500
            $ttlFlow="dispatch-ttl-$stamp"
            Api $BaseUrlA 'POST' '/api/v1/workflows' (Flow $ttlFlow $duration '') | Out-Null
            $ttlSession=NewSession $ttlFlow
            $ttlRun=Send $BaseUrlA $ttlSession.id "ttl-$stamp"
            WaitNode $ttlRun.runId 'delay'
            $sessionId=GuidText $ttlSession.id
            $ttl=Redis @('PTTL',"aiflow:lock:session:$sessionId")
            Check 'redisSessionLockHasConfiguredSmallTtl' ([long]$ttl[0] -gt 0 -and [long]$ttl[0] -le $LockTtlMillis) @{pttlMs=$ttl[0];expectedMaximum=$LockTtlMillis}
            $id=GuidText $ttlRun.runId
            $remaining=Sql "select greatest(0,extract(epoch from (lease_until-current_timestamp))*1000)::bigint from session_dispatch_tasks where run_id='$id' and status='RUNNING'"
            Check 'postgresTaskHasConfiguredSmallLease' ([long]$remaining[0] -gt 0 -and [long]$remaining[0] -le $TaskLeaseMillis) @{remainingMs=$remaining[0];expectedMaximum=$TaskLeaseMillis}
            WaitRun $BaseUrlB $ttlRun.runId 'SUCCEEDED' | Out-Null
            $elapsed=Sql "select extract(epoch from (finished_at-started_at))*1000 from session_dispatch_tasks where run_id='$id' and status='DONE'"
            $elapsedMs=[double]::Parse($elapsed[0],[Globalization.CultureInfo]::InvariantCulture)
            Check 'bothLeasesRenewPastThreeTtlDurations' ($elapsedMs -gt 3*$LockTtlMillis -and $elapsedMs -gt 3*$TaskLeaseMillis) @{elapsedMs=$elapsedMs;lockTtl=$LockTtlMillis;taskLease=$TaskLeaseMillis}
            $evidence.longLeaseRun=$ttlRun.runId

            $lostFlow="dispatch-token-$stamp"
            Api $BaseUrlA 'POST' '/api/v1/workflows' (Flow $lostFlow 10000 '') | Out-Null
            $lostSession=NewSession $lostFlow
            $lostRun=Send $BaseUrlA $lostSession.id "lost-$stamp"
            WaitNode $lostRun.runId 'delay'
            $sessionId=GuidText $lostSession.id
            $key="aiflow:lock:session:$sessionId"
            # Owner is an ephemeral session-lock token, never an API credential. It is not printed.
            $replacement=[Guid]::NewGuid().ToString()
            $set=Redis @('SET',$key,$replacement,'PX','30000','XX')
            Check 'replaceOnlyThisSyntheticSessionsExistingLock' ($set[0] -eq 'OK') $sessionId
            WaitRun $BaseUrlB $lostRun.runId 'FAILED' | Out-Null
            $id=GuidText $lostRun.runId
            $endCount=Sql "select count(*) from node_runs where run_id='$id' and node_id='end'"
            Check 'oldOwnerStopsBeforeNextNode' ([int]$endCount[0] -eq 0) $endCount[0]
            $lockValue=Redis @('GET',$key)
            Check 'oldOwnerCannotUnlockReplacementOwnership' ($lockValue[0] -eq $replacement) 'replacement still present; token value omitted'
            $evidence.lostOwnerRun=$lostRun.runId
            # Shorten only this validation lock's TTL; never issue DEL.
            Redis @('PEXPIRE',$key,'1') | Out-Null
        }

        if ($CapacityBaseUrl) {
            if ($QueueCapacity -lt 1 -or $QueueCapacity -gt 20) { throw 'QueueCapacity must be 1..20 and match the receiver configuration' }
            CheckMock $CapacityBaseUrl
            $capacityStatus=Api $CapacityBaseUrl 'GET' '/api/v1/runs/dispatch/status'
            Check 'capacityReceiverWorkerIsDisabled' ($capacityStatus.enabled -and -not $capacityStatus.workerEnabled) $capacityStatus
            $capacityFlow="dispatch-capacity-$stamp"
            Api $BaseUrlA 'POST' '/api/v1/workflows' (Flow $capacityFlow 100 '') | Out-Null
            $capacitySession=NewSession $capacityFlow
            $sid=GuidText $capacitySession.id
            $blockKey="aiflow:lock:session:$sid"
            Redis @('SET',$blockKey,[Guid]::NewGuid().ToString(),'PX','60000','NX') | Out-Null
            $acceptedRuns=New-Object System.Collections.Generic.List[string]
            for($index=0;$index -lt $QueueCapacity;$index++){ $accepted=Send $CapacityBaseUrl $sid "capacity-$stamp-$index"; $acceptedRuns.Add([string]$accepted.runId) }
            $before=Sql "select (select count(*) from messages where session_id='$sid' and role='USER'),(select count(*) from workflow_runs where session_id='$sid'),(select count(*) from session_dispatch_tasks where session_id='$sid')"
            $overflowStatus=200
            try { Send $CapacityBaseUrl $sid "overflow-$stamp" | Out-Null } catch { if($_.Exception.Response){$overflowStatus=[int]$_.Exception.Response.StatusCode}else{throw} }
            $after=Sql "select (select count(*) from messages where session_id='$sid' and role='USER'),(select count(*) from workflow_runs where session_id='$sid'),(select count(*) from session_dispatch_tasks where session_id='$sid')"
            Check 'capacityOverflowReturns429AndCommitsNoPartialRows' ($overflowStatus -eq 429 -and $before[0] -eq $after[0]) @{status=$overflowStatus;before=$before[0];after=$after[0]}
            $rejected=Sql "select count(*) from messages where session_id='$sid' and client_msg_id='overflow-$stamp'"
            Check 'rejectedAdmissionDoesNotConsumeIdempotencyKey' ([int]$rejected[0] -eq 0) $rejected[0]
            Redis @('PEXPIRE',$blockKey,'1') | Out-Null
            foreach($run in $acceptedRuns){WaitRun $BaseUrlB $run 'SUCCEEDED' | Out-Null}
            $evidence.capacitySession=$sid
        }

        if ($CrashEnginePid -gt 0) {
            $source=Api $BaseUrlA 'GET' '/api/v1/runs/dispatch/status'
            $receiver=Api $BaseUrlB 'GET' '/api/v1/runs/dispatch/status'
            Check 'crashSourceIsOnlyWorkerOfTheseTwoInstances' ($source.workerEnabled -and -not $receiver.workerEnabled) @{A=$source;B=$receiver}
            if ($CrashProcessMarker -notmatch '^[a-zA-Z0-9_-]{8,80}$') { throw 'Provide a unique 8..80 character JVM validation marker' }
            $process=Get-CimInstance Win32_Process -Filter "ProcessId=$CrashEnginePid"
            $marker="-Daiflow.validation.run-id=$CrashProcessMarker"
            if (-not $process -or $process.Name -notmatch '^java(w)?\.exe$' -or -not $process.CommandLine.Contains($marker) -or -not $process.CommandLine.Contains('aiflow-engine')) { throw 'PID is not the explicitly marked validation engine; refusing to terminate it' }
            $crashFlow="dispatch-crash-$stamp"
            Api $BaseUrlA 'POST' '/api/v1/workflows' (Flow $crashFlow 30000 $PreCrashToolId) | Out-Null
            $crashSession=NewSession $crashFlow
            $crashed=Send $BaseUrlA $crashSession.id "crash-running-$stamp"
            WaitNode $crashed.runId 'delay'
            $id=GuidText $crashed.runId
            if($PreCrashToolId){
                $preCount=Sql "select count(*) from node_runs where run_id='$id' and node_id='before' and status='SUCCEEDED'"
                Check 'preCrashToolExecutedExactlyOnce' ([int]$preCount[0] -eq 1) $preCount[0]
                $evidence.preCrashToolRows=[int]$preCount[0]
                $evidence.preCrashToolId=$PreCrashToolId
            }
            $queued=Send $BaseUrlA $crashSession.id "crash-queued-$stamp"
            $queuedId=GuidText $queued.runId
            $queueState=Sql "select status from session_dispatch_tasks where run_id='$queuedId' and kind='PROCESS'"
            Check 'nextSameSessionTaskIsDurablyQueuedBeforeCrash' ($queueState[0] -eq 'QUEUED') $queued.runId
            $evidence.crashedRun=$crashed.runId
            $evidence.queuedRun=$queued.runId
            $evidence.crashSession=$crashSession.id
            $evidence.crashEnginePid=$CrashEnginePid
            $evidence.crashMarker=$CrashProcessMarker
            Stop-Process -Id $CrashEnginePid -Force
            Check 'onlyMarkedValidationProcessWasTerminated' (-not (Get-Process -Id $CrashEnginePid -ErrorAction SilentlyContinue)) $CrashEnginePid
            $phase='crashed-awaiting-survivor'
        } else { $phase='lease-and-capacity-verified' }
    }
} finally {
    $parent=Split-Path -Parent $OutFile
    if($parent){[IO.Directory]::CreateDirectory([IO.Path]::GetFullPath($parent)) | Out-Null}
    $report=[pscustomobject]@{at=[DateTime]::UtcNow.ToString('o');phase=$phase;baseUrlA=$BaseUrlA;baseUrlB=$BaseUrlB;evidence=$evidence;checks=$checks.ToArray()}
    [IO.File]::WriteAllText([IO.Path]::GetFullPath($OutFile),($report | ConvertTo-Json -Depth 15),[Text.UTF8Encoding]::new($false))
}
Write-Output "Report: $OutFile (phase=$phase)"
