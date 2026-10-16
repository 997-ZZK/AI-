<#
.SYNOPSIS
  S0 raw SSE: successful mock chat and running delay cancellation, including EOF and unique terminal.
.NOTES
  Synthetic records and evidence are retained. Does not delete data or run migrations.
#>
param(
    [string]$BaseUrl = 'http://localhost:8082',
    [string]$Prefix = ('m43c-s0-raw-' + [Guid]::NewGuid().ToString('N').Substring(0,24)),
    [string]$OutDir = 'target/m4-3c/s0-raw'
)
$ErrorActionPreference = 'Stop'
[IO.Directory]::CreateDirectory([IO.Path]::GetFullPath($OutDir)) | Out-Null
$checks = New-Object 'System.Collections.Generic.List[object]'
$startedAt=[DateTime]::UtcNow
$phase='parameters'
$failed=$false
$failureKind=$null
$evidence = [ordered]@{
    prefix=$(if ($Prefix -cmatch '^[A-Za-z0-9-]{1,40}$') {$Prefix} else {'(invalid)'})
    users=0; sessions=0; runs=0; canceledRuns=0; workflowsCreated=0
    sessionIds=@(); runIds=@(); rawStreams=@()
}
function Check([string]$Name, [bool]$Passed, $Detail) {
    $checks.Add([pscustomobject]@{name=$Name;passed=$Passed;evidence=$Detail})
    if (-not $Passed) { throw "FAILED: $Name" }
    Write-Host "PASS: $Name"
}
function Api([string]$Method, [string]$Path, $Body=$null) {
    $requestArgs = @{Uri=($BaseUrl+$Path);Method=$Method;UseBasicParsing=$true;TimeoutSec=10}
    if ($null -ne $Body) { $requestArgs.ContentType='application/json; charset=utf-8'; $requestArgs.Body=[Text.Encoding]::UTF8.GetBytes(($Body|ConvertTo-Json -Depth 12 -Compress)) }
    $r=Invoke-WebRequest @requestArgs
    if ([int]$r.StatusCode -ne 200) { throw "Expected HTTP 200: $Path" }
    $text=$r.Content
    if ($text -is [byte[]]) { $text=[Text.Encoding]::UTF8.GetString($text) }
    $response=$text | ConvertFrom-Json
    if ($null -eq $response.data -or $null -eq $response.PSObject.Properties['code'] -or $response.code -ne 0) {
        throw 'API_RESPONSE_CONTRACT_INVALID'
    }
    return $response.data
}
function WaitRun([string]$Id, [string]$Status) {
    $deadline=[DateTime]::UtcNow.AddSeconds(15)
    do {
        $r=Api 'GET' "/api/v1/runs/$Id"
        if ($r.status -eq $Status) { return $r }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Run $Id did not reach $Status (last=$($r.status))"
}
function ReadSse([string]$RunId, [string]$Name, [string]$Terminal, [bool]$ExpectDelta) {
    $headers=Join-Path $OutDir "$Name-headers.txt"
    $frames=Join-Path $OutDir "$Name-events.sse"
    $watch=[Diagnostics.Stopwatch]::StartNew()
    & curl.exe --silent --show-error --no-buffer --max-time 15 --dump-header $headers --output $frames -H 'Accept: text/event-stream' "$BaseUrl/api/v1/runs/$RunId/events"
    $code=$LASTEXITCODE
    $watch.Stop()
    Check "$Name.serverClosedStream" ($code -eq 0) @{exitCode=$code;elapsedMs=$watch.ElapsedMilliseconds}
    $head=[IO.File]::ReadAllText([IO.Path]::GetFullPath($headers))
    Check "$Name.httpAndContentType" ($head -match 'HTTP/1\.1 200' -and $head -match '(?i)Content-Type:\s*text/event-stream') $head.Trim()
    $content=[IO.File]::ReadAllText([IO.Path]::GetFullPath($frames),[Text.Encoding]::UTF8)
    $events=@($content -split '\r?\n\r?\n' | Where-Object {$_ -match '(?m)^data:'} | ForEach-Object {
        $id=[regex]::Match($_,'(?m)^id:\s*(\d+)').Groups[1].Value
        $type=[regex]::Match($_,'(?m)^event:\s*([^\r\n]+)').Groups[1].Value
        $data=[regex]::Match($_,'(?m)^data:\s*(.+)').Groups[1].Value | ConvertFrom-Json
        [pscustomobject]@{id=$id;type=$type;payload=$data}
    })
    $terminalEvents=@($events | Where-Object {$_.type -match '^RUN_(SUCCEEDED|DEGRADED|FAILED|CANCELED)$'})
    Check "$Name.uniqueTerminal" ($terminalEvents.Count -eq 1 -and $terminalEvents[0].type -eq $Terminal -and $events[-1].type -eq $Terminal) @($terminalEvents|ForEach-Object{$_.type})
    $sequenceOk=$events.Count -gt 0
    for($i=0;$i -lt $events.Count;$i++) {
        if ([long]$events[$i].id -ne $i+1 -or [long]$events[$i].payload.seq -ne $i+1 -or $events[$i].payload.runId -ne $RunId -or $events[$i].payload.type -ne $events[$i].type) { $sequenceOk=$false }
    }
    Check "$Name.contiguousSequenceAndMatchingRun" $sequenceOk @{count=$events.Count;lastSeq=$events[-1].id}
    if($ExpectDelta) { Check "$Name.nonemptyDelta" (@($events|Where-Object{$_.type -eq 'DELTA' -and $_.payload.data.text.Length -gt 0}).Count -gt 0) $RunId }
    return [pscustomobject]@{runId=$RunId;eventCount=$events.Count;lastSeq=$events[-1].id;terminal=$Terminal;serverClosed=$true;frames=$frames}
}
try {
    Check 'syntheticPrefixValid' ($Prefix -cmatch '^[A-Za-z0-9-]{1,40}$') @{allowed='letters,digits,hyphens;1..40';length=$Prefix.Length}
    Check 'localOriginValid' ($BaseUrl -match '^http://(localhost|127\.0\.0\.1):[0-9]+$') $null
    $phase='capabilities'
    $caps=Api 'GET' '/api/v1/capabilities'
    Check 'mockOnlyCapabilities' ((@($caps.channels|Where-Object{$_.channel -eq 'chat'})[0].activeProvider -eq 'mock') -and (@($caps.channels|Where-Object{$_.channel -eq 'embedding'})[0].activeProvider -eq 'mock-embedding')) $caps.channels
    $phase='create-user'
    $user=Api 'POST' '/api/v1/users' @{externalId=$Prefix;displayName='Synthetic load smoke'}
    $evidence.users++; $evidence.userId=$user.id
    $phase='create-success-session'
    $session=Api 'POST' '/api/v1/sessions' @{userId=$user.id;title=$Prefix;workflowName='basic-chat'}
    $evidence.sessions++; $evidence.sessionIds+=@($session.id)
    $phase='submit-success-run'
    $submitted=Api 'POST' "/api/v1/sessions/$($session.id)/messages" @{clientMsgId="$Prefix-success";content='synthetic raw SSE smoke'}
    $evidence.runs++; $evidence.runIds+=@($submitted.runId)
    Check 'submissionQueued' ($submitted.status -eq 'QUEUED') $submitted
    $phase='read-success-stream'
    $evidence.rawStreams += ReadSse $submitted.runId 'success' 'RUN_SUCCEEDED' $true
    $phase='success-snapshot'
    $snapshot=WaitRun $submitted.runId 'SUCCEEDED'
    Check 'successSnapshotAgrees' ($null -ne $snapshot.finishedAt) $snapshot.status
    $phase='register-delay-workflow'
    $flow="$Prefix-delay"
    Api 'POST' '/api/v1/workflows' @{id=$flow;name=$flow;version=1;nodes=@(
        @{id='start';type='start'},@{id='delay';type='delay';params=@{millis=30000};timeoutSeconds=60},
        @{id='end';type='end';params=@{outputs=@{reply='input'}}}
    );edges=@(@{from='start';to='delay'},@{from='delay';to='end'})} | Out-Null
    $evidence.workflowsCreated++
    $phase='create-cancel-session'
    $cancelSession=Api 'POST' '/api/v1/sessions' @{userId=$user.id;title="$Prefix-cancel";workflowName=$flow}
    $evidence.sessions++; $evidence.sessionIds+=@($cancelSession.id)
    $phase='submit-cancel-run'
    $cancelRun=Api 'POST' "/api/v1/sessions/$($cancelSession.id)/messages" @{clientMsgId="$Prefix-cancel";content='synthetic cancellation smoke'}
    $evidence.runs++; $evidence.runIds+=@($cancelRun.runId)
    Check 'cancelSubmissionQueued' ($cancelRun.status -eq 'QUEUED') $cancelRun
    $phase='wait-delay-executing'
    WaitRun $cancelRun.runId 'RUNNING' | Out-Null
    $deadline=[DateTime]::UtcNow.AddSeconds(10)
    do {
        $rows=@(& docker exec -e 'PGOPTIONS=-c default_transaction_read_only=on -c statement_timeout=2000' aiflow-postgres psql -X -U aiflow -d aiflow -At -c "select count(*) from node_runs where run_id='$($cancelRun.runId)' and node_id='delay' and status='RUNNING'")
        if($LASTEXITCODE -ne 0){throw 'Read-only node assertion failed'}
        if([int]$rows[0] -eq 1){break}
        Start-Sleep -Milliseconds 100
    } while([DateTime]::UtcNow -lt $deadline)
    Check 'delayWasExecutingBeforeCancel' ([int]$rows[0] -eq 1) $cancelRun.runId
    $phase='cancel-delay-run'
    $watch=[Diagnostics.Stopwatch]::StartNew()
    $cancel=Api 'POST' "/api/v1/runs/$($cancelRun.runId)/cancel?actor=load-smoke"
    WaitRun $cancelRun.runId 'CANCELED' | Out-Null
    $evidence.canceledRuns++
    $watch.Stop()
    $evidence.cancelLatencyMs=$watch.ElapsedMilliseconds
    $phase='read-cancel-stream'
    $evidence.rawStreams += ReadSse $cancelRun.runId 'cancel' 'RUN_CANCELED' $false
    $phase='complete'
} catch {
    $failed=$true
    $failureKind=$_.Exception.GetType().Name
    # Record the failed stage without retaining upstream response/error text.
    $checks.Add([pscustomobject]@{name=('verificationException-'+$phase);passed=$false;evidence=@{kind=$failureKind}})
    Write-Host "FAIL: verificationException-$phase"
} finally {
    $report=[ordered]@{
        startedAt=$startedAt.ToString('o');finishedAt=[DateTime]::UtcNow.ToString('o')
        baseUrl=$(if ($BaseUrl -match '^http://(localhost|127\.0\.0\.1):[0-9]+$') {$BaseUrl} else {'(invalid)'})
        passed=(-not $failed);phase=$phase;failureKind=$failureKind
        expected=@{users=1;sessions=2;runs=2;canceledRuns=1}
        checks=$checks.ToArray();evidence=$evidence
    }
    [IO.File]::WriteAllText([IO.Path]::GetFullPath((Join-Path $OutDir 's0-raw-report.json')),($report|ConvertTo-Json -Depth 15),[Text.UTF8Encoding]::new($false))
}
Write-Output "Report: $OutDir/s0-raw-report.json"
if ($failed) { throw 'Raw SSE smoke failed; inspect the saved report.' }
