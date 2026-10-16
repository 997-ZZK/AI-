<#
.SYNOPSIS
  One raw TCP non-reader and one continuously reading SSE client on the same mock run.
.NOTES
  Requires a dedicated start-load-engine snapshot: port 8082, Redis events,
  SSE queue 16, mock chunk size 8 / delay 5 ms, and Flyway disabled.
  Creates only one synthetic user, session, workflow and run. Keeps all records.
  The raw socket sends one GET and never reads. Its 512-byte receive buffer is
  requested before connect; it stays undisposed until PG/Redis/resource checks.
  curl headers and event bodies are parsed in memory, never saved or printed.
  Exit 0 = passed; 1 = failed; 2 = INCONCLUSIVE (overflow was not induced).
  Observations stop at 150 seconds, leaving up to 15 seconds for owned-run cleanup.
  Save with one UTF-8 BOM; compatible with Windows PowerShell 5.1.
#>
param(
    [string]$BaseUrl = 'http://localhost:8082',
    [string]$Prefix = ('m43c-slow-' + [Guid]::NewGuid().ToString('N').Substring(0,24)),
    [Parameter(Mandatory=$true)][string]$EngineConfigFile
)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$reportRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'target/m4-3c'))
$validPrefix = $Prefix -cmatch '^[A-Za-z0-9-]{1,40}$'
$safePrefix = if ($validPrefix) {$Prefix} else {'invalid-' + [Guid]::NewGuid().ToString('N')}
$outputRoot = Join-Path $reportRoot $safePrefix
$outputAlreadyExists = Test-Path -LiteralPath $outputRoot
if ($outputAlreadyExists) {$outputRoot=Join-Path $reportRoot ('invalid-'+[Guid]::NewGuid().ToString('N'))}
$pathToCheck=$outputRoot
while ($pathToCheck -and $pathToCheck.StartsWith($repoRoot,[StringComparison]::OrdinalIgnoreCase)) {
    if ((Test-Path -LiteralPath $pathToCheck) -and
        (((Get-Item -LiteralPath $pathToCheck -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)) {
        throw 'Output must not traverse a junction or symbolic link.'
    }
    if ($pathToCheck -eq $repoRoot) {break}
    $pathToCheck=Split-Path -Parent $pathToCheck
}
[IO.Directory]::CreateDirectory($outputRoot)|Out-Null
$reportPath=Join-Path $outputRoot 'slow-sse-report.json'
$startedAt=[DateTime]::UtcNow
$watch=[Diagnostics.Stopwatch]::StartNew()
$checks=New-Object System.Collections.Generic.List[object]
$samples=New-Object System.Collections.Generic.List[object]
$owned=New-Object 'System.Collections.Concurrent.ConcurrentDictionary[int,System.Diagnostics.Process]'
$normalState=[Collections.Hashtable]::Synchronized(@{firstDeltaAt=$null;lastSeq=0;deltaCount=0;terminalSeen=$false;failed=$false})
$evidence=[ordered]@{
    prefix=$(if ($validPrefix) {$Prefix} else {'(invalid)'})
    users=0;sessions=0;workflows=0;runs=0;userId=$null;sessionId=$null;runId=$null
    engine=$null;baseline=$null;delayReady=$null;firstOverflow=$null
    raw=[ordered]@{receiveBufferRequested=512;receiveBufferBeforeConnect=$null;receiveBufferActual=$null;localPort=$null;requestCount=0;sendCalls=0;bytesSent=0;readCalls=0;openedAt=$null;closedAt=$null;undisposedAtVerification=$false}
    normal=$null;database=$null;redis=$null;final=$null;cancel=$null;cleanup=$null
}
$phase='parameters'
$outcome='FAILED'
$failureKind=$null
$failureCode=$null
$timedOut=$false
$socket=$null
$normalShell=$null
$normalAsync=$null
$normalCollected=$false
$safeOrigin=$false
$runId=$null
$runTerminal=$false
$verificationComplete=$false

function Check([string]$Name,[bool]$Passed,$Detail) {
    $checks.Add([pscustomobject]@{name=$Name;passed=$Passed;evidence=$Detail})
    if (-not $Passed) {throw ('CHECK_FAILED:'+ $Name)}
    Write-Host ('PASS: '+$Name)
}
function Budget([int]$Limit=150) {
    if ($watch.Elapsed.TotalSeconds -ge $Limit) {
        $script:timedOut=$true
        throw 'OBSERVATION_TIMEOUT'
    }
}
function GuidText([string]$Text) {
    $value=[Guid]::Empty
    if (-not [Guid]::TryParseExact($Text,'D',[ref]$value)) {throw 'UUID_INVALID'}
    return $value.ToString('D')
}
function Api([string]$Method,[string]$Path,$Body=$null,[int]$Limit=150) {
    Budget $Limit
    $seconds=[Math]::Max(1,[Math]::Min(3,[Math]::Floor($Limit-$watch.Elapsed.TotalSeconds)))
    $request=@{Uri=($BaseUrl+$Path);Method=$Method;UseBasicParsing=$true;TimeoutSec=[int]$seconds;MaximumRedirection=0}
    if ($null -ne $Body) {
        $request.ContentType='application/json; charset=utf-8'
        $request.Body=[Text.Encoding]::UTF8.GetBytes(($Body|ConvertTo-Json -Depth 12 -Compress))
    }
    try {$response=Invoke-WebRequest @request} catch {throw 'API_REQUEST_FAILED'}
    if ([int]$response.StatusCode -ne 200) {throw 'API_HTTP_STATUS_UNEXPECTED'}
    $text=$response.Content
    if ($text -is [byte[]]) {$text=[Text.Encoding]::UTF8.GetString($text)}
    try {$value=ConvertFrom-Json -InputObject $text} catch {throw 'API_JSON_INVALID'}
    if ($null -eq $value.data -or $null -eq $value.PSObject.Properties['code'] -or $value.code -ne 0) {throw 'API_CONTRACT_INVALID'}
    return $value.data
}
function State([int]$Limit=150) {
    $events=Api 'GET' '/api/v1/runs/events/status' $null $Limit
    $dispatch=Api 'GET' '/api/v1/runs/dispatch/status' $null $Limit
    foreach ($field in @('connectionCount','sseOverflow')) {
        if ($null -eq $events.PSObject.Properties[$field]) {throw 'EVENTS_STATUS_FIELD_MISSING'}
    }
    foreach ($field in @('subscriptionCount','readerCount','publishFailures','readFailures','mode')) {
        if ($null -eq $events.bus.PSObject.Properties[$field]) {throw 'BUS_STATUS_FIELD_MISSING'}
    }
    foreach ($field in @('activeWorkers','leaseLost','enabled','workerEnabled')) {
        if ($null -eq $dispatch.PSObject.Properties[$field]) {throw 'DISPATCH_STATUS_FIELD_MISSING'}
    }
    return [pscustomobject]@{
        at=[DateTime]::UtcNow.ToString('o');elapsedMs=$watch.ElapsedMilliseconds
        connections=[int]$events.connectionCount;subscriptions=[int]$events.bus.subscriptionCount;readers=[int]$events.bus.readerCount
        overflow=[long]$events.sseOverflow;publishFailures=[long]$events.bus.publishFailures;readFailures=[long]$events.bus.readFailures
        activeWorkers=[int]$dispatch.activeWorkers;leaseLost=[long]$dispatch.leaseLost;mode=[string]$events.bus.mode
        dispatchEnabled=[bool]$dispatch.enabled;workerEnabled=[bool]$dispatch.workerEnabled
    }
}
function Sql([string]$Query,[int]$Limit=150) {
    Budget $Limit
    if (-not $Query.TrimStart().StartsWith('select ',[StringComparison]::OrdinalIgnoreCase) -or $Query.Contains(';')) {throw 'SELECT_ONLY_GUARD_FAILED'}
    $lines=@(& docker exec -e 'PGOPTIONS=-c default_transaction_read_only=on -c statement_timeout=2000' aiflow-postgres psql -X -U aiflow -d aiflow -At -v 'ON_ERROR_STOP=1' -c $Query 2>$null)
    if ($LASTEXITCODE -ne 0 -or $lines.Count -ne 1 -or [string]::IsNullOrWhiteSpace($lines[0])) {throw 'POSTGRES_SELECT_FAILED'}
    try {return ConvertFrom-Json -InputObject $lines[0]} catch {throw 'POSTGRES_JSON_INVALID'}
}
function Database([int]$Limit=150) {
    return (Sql @"
select snapshot from (
with us as (select id from users where external_id='$Prefix'),
ss as (select id from sessions where user_id in (select id from us)),
rr as (select * from workflow_runs where session_id in (select id from ss)),
tt as (select * from session_dispatch_tasks where run_id in (select id from rr)),
nn as (select * from node_runs where run_id in (select id from rr)),
mm as (select * from messages where session_id in (select id from ss))
select json_build_object(
 'users',(select count(*) from us),'sessions',(select count(*) from ss),'runs',(select count(*) from rr),
 'workflows',(select count(*) from workflows where name='$Prefix'),
 'tasks',(select count(*) from tt),'processTasks',(select count(*) from tt where kind='PROCESS'),
 'doneTasks',(select count(*) from tt where status='DONE'),'nodes',(select count(*) from nn),
 'userMessages',(select count(*) from mm where role='USER'),'assistantMessages',(select count(*) from mm where role='ASSISTANT'),
 'successfulRuns',(select count(*) from rr where status='SUCCEEDED'),
 'canceledRuns',(select count(*) from rr where status='CANCELED'),
 'activeRuns',(select count(*) from rr where status in ('PENDING','RUNNING','WAITING_APPROVAL') or finished_at is null),
 'activeTasks',(select count(*) from tt where status in ('QUEUED','RUNNING') or finished_at is null),
 'activeLeases',(select count(*) from tt where status='RUNNING' and lease_until>now()),
 'activeNodes',(select count(*) from nn where status in ('RUNNING','WAITING') or ended_at is null),
 'delayRunning',(select count(*) from nn where node_id='delay' and status='RUNNING'),
 'unsuccessfulNodes',(select count(*) from nn where status<>'SUCCEEDED'),
 'llmNodes',(select count(*) from nn where node_type='llm.chat'),
 'badMockNodes',(select count(*) from nn where node_type='llm.chat' and (status<>'SUCCEEDED' or coalesce(output->>'provider','')<>'mock')),
 'retries',(select count(*) from nn where attempt<>1),
 'duplicateNodes',(select count(*) from (select run_id,node_id from nn group by run_id,node_id having count(*)>1) d),
 'cancelRequests',(select count(*) from tt where cancel_requested_at is not null)
) snapshot
) owned_run
"@ $Limit)
}
function RedisSnapshot([string]$Id) {
    Budget
    # Read-only Lua returns only identifiers, enum states and numeric counters.
    # Event text is decoded inside Redis and never returned to this process.
    $lua=@'
local rows=redis.call('XRANGE',KEYS[1],'-','+')
local latest=tonumber(redis.call('GET',KEYS[2]) or '0')
local result={entryCount=#rows,latestSeq=latest,firstSeq=0,lastSeq=0,contiguous=true,matchingRun=true,terminalCount=0,terminalSeq=0,terminalType='',terminalStatus='',deltaCount=0,invalidEvents=0,ttlMillis=redis.call('PTTL',KEYS[1])}
for _,row in ipairs(rows) do
  local seq=tonumber(string.match(row[1],'^(%d+)%-0$'))
  local fields=row[2]
  if not seq or #fields~=2 or fields[1]~='event' then result.invalidEvents=result.invalidEvents+1
  else
    if result.firstSeq==0 then result.firstSeq=seq end
    if result.lastSeq~=0 and seq~=result.lastSeq+1 then result.contiguous=false end
    result.lastSeq=seq
    local ok,event=pcall(cjson.decode,fields[2])
    if not ok or type(event)~='table' or type(event.data)~='table' or event.seq~=0 then result.invalidEvents=result.invalidEvents+1
    else
      if event.runId~=ARGV[1] then result.matchingRun=false end
      if event.type=='DELTA' and type(event.data.text)=='string' and #event.data.text>0 then result.deltaCount=result.deltaCount+1 end
      if event.type=='RUN_SUCCEEDED' or event.type=='RUN_DEGRADED' or event.type=='RUN_FAILED' or event.type=='RUN_CANCELED' then
        result.terminalCount=result.terminalCount+1;result.terminalSeq=seq;result.terminalType=event.type;result.terminalStatus=event.data.status or ''
      end
    end
  end
end
return cjson.encode(result)
'@
    $stream='aiflow:events:{'+$Id+'}:stream'
    $sequence='aiflow:events:{'+$Id+'}:seq'
    $lines=@(& docker exec aiflow-redis redis-cli --raw EVAL $lua 2 $stream $sequence $Id 2>$null)
    if ($LASTEXITCODE -ne 0 -or $lines.Count -ne 1) {throw 'REDIS_SNAPSHOT_FAILED'}
    try {return ConvertFrom-Json -InputObject $lines[0]} catch {throw 'REDIS_SNAPSHOT_JSON_INVALID'}
}

$normalCode={
    param([string]$Origin,[string]$RunId,[string]$CurlPath,[int]$MaxSeconds,
        [Collections.Hashtable]$Shared,
        [System.Collections.Concurrent.ConcurrentDictionary[int,System.Diagnostics.Process]]$Owned)
    $ErrorActionPreference='Stop'
    $result=[ordered]@{requestCount=0;curlPid=$null;localPort=$null;httpStatus=$null;contentType=$null;headerBlocks=0;
        connectCount=$null;redirectCount=$null;metadataCount=0;exitCode=$null;stderrPresent=$false;eof=$false;killed=$false;
        eventCount=0;firstSeq=$null;lastSeq=0;deltaCount=0;pingCount=0;terminalCount=0;terminalSeq=$null;terminalType=$null;
        sequenceValid=$true;matchingRun=$true;passed=$false;failureKind=$null;startedAt=$null;endedAt=$null}
    $process=$null;$stderrTask=$null;$inHeaders=$false;$headersComplete=$false
    $eventName=$null;$idText=$null;$dataText=$null;$hasFrame=$false
    try {
        $info=[Diagnostics.ProcessStartInfo]::new()
        $info.FileName=$CurlPath
        $info.Arguments='--disable --silent --show-error --no-buffer --http1.1 --noproxy "*" --max-time '+$MaxSeconds+' --dump-header - --header "Accept: text/event-stream" --header "Last-Event-ID: 0" --write-out "\nSLOW_CURL_META|%{num_connects}|%{num_redirects}|%{local_port}|%{http_code}\n" "'+$Origin+'/api/v1/runs/'+$RunId+'/events"'
        $info.UseShellExecute=$false;$info.CreateNoWindow=$true
        $info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
        $info.StandardOutputEncoding=[Text.Encoding]::UTF8;$info.StandardErrorEncoding=[Text.Encoding]::UTF8
        $process=[Diagnostics.Process]::new();$process.StartInfo=$info
        if (-not $process.Start()) {throw 'CURL_START_FAILED'}
        $result.requestCount=1;$result.curlPid=$process.Id;$result.startedAt=[DateTime]::UtcNow.ToString('o')
        if (-not $Owned.TryAdd(1,$process)) {throw 'CURL_OWNERSHIP_FAILED'}
        $stderrTask=$process.StandardError.ReadToEndAsync()
        while ($null -ne ($line=$process.StandardOutput.ReadLine())) {
            if ($line.Length -gt 131072) {throw 'SSE_LINE_TOO_LARGE'}
            if ($line -match '^SLOW_CURL_META\|([0-9]+)\|([0-9]+)\|([0-9]+)\|([0-9]{3})$') {
                $result.metadataCount++;$result.connectCount=[int]$Matches[1];$result.redirectCount=[int]$Matches[2];$result.localPort=[int]$Matches[3]
                if ($result.httpStatus -ne [int]$Matches[4]) {throw 'CURL_STATUS_MISMATCH'}
                continue
            }
            if ($line -match '^HTTP/1\.[01] ([0-9]{3})(?: |$)') {
                $result.headerBlocks++;$result.httpStatus=[int]$Matches[1];$inHeaders=$true;$headersComplete=$false
                continue
            }
            if ($inHeaders) {
                if ($line.Length -eq 0) {$inHeaders=$false;$headersComplete=$true}
                elseif ($line -match '(?i)^Content-Type\s*:\s*(.+)$') {
                    $media=($Matches[1] -split ';',2)[0].Trim().ToLowerInvariant()
                    $result.contentType=if ($media -eq 'text/event-stream') {$media} else {'(other)'}
                }
                continue
            }
            if (-not $headersComplete) {if ($line.Length -gt 0) {throw 'SSE_HEADERS_MISSING'};continue}
            if ($line.Length -eq 0) {
                if ($hasFrame) {
                    if ($eventName -ceq 'PING') {
                        if ($null -ne $idText -or $dataText -cne '{}') {throw 'PING_FRAME_INVALID'}
                        $result.pingCount++
                    } else {
                        if ($result.terminalCount -gt 0) {throw 'EVENT_AFTER_TERMINAL'}
                        if ($idText -cnotmatch '^[1-9][0-9]*$' -or [string]::IsNullOrEmpty($dataText)) {throw 'SSE_FRAME_INVALID'}
                        $seq=[long]0
                        if (-not [long]::TryParse($idText,[ref]$seq)) {throw 'SSE_SEQUENCE_INVALID'}
                        if ($seq -ne ($result.lastSeq+1)) {$result.sequenceValid=$false;throw 'SSE_SEQUENCE_GAP'}
                        try {$payload=ConvertFrom-Json -InputObject $dataText} catch {throw 'SSE_EVENT_JSON_INVALID'}
                        if ($null -eq $payload -or [string]$payload.runId -cne $RunId) {$result.matchingRun=$false;throw 'SSE_RUN_MISMATCH'}
                        if ($payload.seq -ne $seq -or [string]$payload.type -cne $eventName) {throw 'SSE_PAYLOAD_MISMATCH'}
                        if ($eventName -cnotin @('RUN_STARTED','NODE_STARTED','NODE_SUCCEEDED','DELTA','RUN_SUCCEEDED')) {throw 'SSE_EVENT_UNEXPECTED'}
                        if ($null -eq $result.firstSeq) {$result.firstSeq=$seq}
                        $result.eventCount++;$result.lastSeq=$seq;$Shared.lastSeq=$seq
                        if ($eventName -ceq 'DELTA') {
                            if ($payload.data.text -isnot [string] -or $payload.data.text.Length -eq 0) {throw 'DELTA_EMPTY'}
                            $result.deltaCount++;$Shared.deltaCount=$result.deltaCount
                            if ($null -eq $Shared.firstDeltaAt) {$Shared.firstDeltaAt=[DateTime]::UtcNow.ToString('o')}
                        }
                        if ($eventName -ceq 'RUN_SUCCEEDED') {
                            if ([string]$payload.data.status -cne 'SUCCEEDED') {throw 'TERMINAL_STATUS_INVALID'}
                            $result.terminalCount++;$result.terminalSeq=$seq;$result.terminalType=$eventName;$Shared.terminalSeen=$true
                        }
                    }
                }
                $eventName=$null;$idText=$null;$dataText=$null;$hasFrame=$false
            } elseif ($line.StartsWith(':')) {continue}
            elseif ($line.StartsWith('event:')) {if ($null -ne $eventName) {throw 'SSE_EVENT_DUPLICATE_FIELD'};$eventName=$line.Substring(6).Trim();$hasFrame=$true}
            elseif ($line.StartsWith('id:')) {if ($null -ne $idText) {throw 'SSE_ID_DUPLICATE_FIELD'};$idText=$line.Substring(3).Trim();$hasFrame=$true}
            elseif ($line.StartsWith('data:')) {if ($null -ne $dataText) {throw 'SSE_DATA_DUPLICATE_FIELD'};$dataText=$line.Substring(5).TrimStart();$hasFrame=$true}
            else {throw 'SSE_FIELD_UNEXPECTED'}
        }
        $result.eof=$true
        if ($hasFrame) {throw 'SSE_EOF_INSIDE_FRAME'}
        if (-not $process.WaitForExit(2000)) {throw 'CURL_EXIT_TIMEOUT'}
        $result.exitCode=$process.ExitCode
        if ($null -ne $stderrTask) {$result.stderrPresent=-not [string]::IsNullOrWhiteSpace($stderrTask.GetAwaiter().GetResult())}
        $result.passed=$result.exitCode -eq 0 -and $result.httpStatus -eq 200 -and $result.contentType -ceq 'text/event-stream' -and
            $result.headerBlocks -eq 1 -and $result.metadataCount -eq 1 -and $result.connectCount -eq 1 -and $result.redirectCount -eq 0 -and
            $result.eventCount -gt 0 -and $result.firstSeq -eq 1 -and $result.deltaCount -gt 0 -and $result.terminalCount -eq 1 -and
            $result.terminalSeq -eq $result.lastSeq -and $result.terminalType -ceq 'RUN_SUCCEEDED' -and -not $result.stderrPresent
        if (-not $result.passed) {$result.failureKind='NORMAL_STREAM_CONTRACT_FAILED';$Shared.failed=$true}
    } catch {
        $Shared.failed=$true
        $code=[string]$_.Exception.Message
        $result.failureKind=if ($code -cmatch '^[A-Z][A-Z0-9_]{1,80}$') {$code} else {'NORMAL_CLIENT_EXCEPTION'}
    } finally {
        if ($null -ne $process) {
            try {if (-not $process.HasExited) {$process.Kill();$result.killed=$true;[void]$process.WaitForExit(2000)}} catch {}
            try {$result.exitCode=$process.ExitCode} catch {}
            if ($null -ne $stderrTask -and $stderrTask.IsCompleted) {
                try {$result.stderrPresent=-not [string]::IsNullOrWhiteSpace($stderrTask.GetAwaiter().GetResult())} catch {$result.stderrPresent=$true}
            }
        }
        $result.endedAt=[DateTime]::UtcNow.ToString('o')
    }
    return [pscustomobject]$result
}
function CollectNormal {
    if ($normalCollected -or $null -eq $normalAsync -or -not $normalAsync.IsCompleted) {return}
    $result=@($normalShell.EndInvoke($normalAsync))
    if ($result.Count -ne 1) {throw 'NORMAL_RESULT_INVALID'}
    $script:evidence.normal=$result[0]
    $script:normalCollected=$true
}

try {
    Check 'prefix-valid-and-output-new' ($validPrefix -and -not $outputAlreadyExists) @{length=$Prefix.Length;outputAlreadyExists=$outputAlreadyExists}
    $origin=$null
    $safeOrigin=[Uri]::TryCreate($BaseUrl,[UriKind]::Absolute,[ref]$origin) -and $origin.Scheme -eq 'http' -and
        $origin.Host -in @('localhost','127.0.0.1') -and $origin.Port -eq 8082 -and -not $origin.UserInfo -and
        -not $origin.Query -and -not $origin.Fragment -and $origin.AbsolutePath -eq '/'
    Check 'local-8082-origin' $safeOrigin $null
    $BaseUrl=$origin.GetLeftPart([UriPartial]::Authority)
    $phase='engine-config'
    $configPath=if ([IO.Path]::IsPathRooted($EngineConfigFile)) {[IO.Path]::GetFullPath($EngineConfigFile)} else {[IO.Path]::GetFullPath((Join-Path $repoRoot $EngineConfigFile))}
    $rootPrefix=$reportRoot.TrimEnd([char[]]@('\','/'))+[IO.Path]::DirectorySeparatorChar
    Check 'config-path-in-target' ($configPath.StartsWith($rootPrefix,[StringComparison]::OrdinalIgnoreCase) -and [IO.Path]::GetExtension($configPath) -eq '.json' -and (Test-Path -LiteralPath $configPath -PathType Leaf)) $null
    $pathToCheck=$configPath
    while ($pathToCheck -and $pathToCheck.StartsWith($repoRoot,[StringComparison]::OrdinalIgnoreCase)) {
        if (((Get-Item -LiteralPath $pathToCheck -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {throw 'CONFIG_REPARSE_POINT'}
        if ($pathToCheck -eq $repoRoot) {break}
        $pathToCheck=Split-Path -Parent $pathToCheck
    }
    $config=ConvertFrom-Json -InputObject ([IO.File]::ReadAllText($configPath,[Text.Encoding]::UTF8))
    $argsList=@($config.args)
    $required=@('--server.port=8082','--spring.flyway.enabled=false','--aiflow.events.mode=redis','--aiflow.events.max-entries=2000',
        '--aiflow.events.retention=24h','--aiflow.events.sse-queue-capacity=16','--aiflow.provider.mode=mock',
        '--aiflow.provider.mock.chunk-size=8','--aiflow.provider.mock.chunk-delay-ms=5','--aiflow.memory.summary-enabled=false',
        '--aiflow.long-memory.extract-enabled=false')
    Check 'diagnostic-config-contract' ($config.port -eq 8082 -and $config.marker -cmatch '^[A-Za-z0-9-]{1,80}$' -and
        @($required|Where-Object {$_ -cnotin $argsList}).Count -eq 0 -and @($argsList|Where-Object {$_ -like '--aiflow.safety.*'}).Count -eq 0) $null
    $processId=[int]$config.pid
    $engineProcess=Get-CimInstance Win32_Process -Filter ('ProcessId='+$processId)
    Check 'engine-marker-and-settings-match-running-process' ($null -ne $engineProcess -and
        $engineProcess.CommandLine.IndexOf(('-Daiflow.validation.run-id='+$config.marker),[StringComparison]::Ordinal) -ge 0 -and
        @($required|Where-Object {$engineProcess.CommandLine.IndexOf($_,[StringComparison]::Ordinal) -lt 0}).Count -eq 0) @{pid=$processId;marker=$config.marker}
    $evidence.engine=[ordered]@{pid=$processId;marker=$config.marker;configSha256=(Get-FileHash -LiteralPath $configPath -Algorithm SHA256).Hash;sseQueueCapacity=16;chunkSize=8;chunkDelayMs=5;safetyThresholdsUnchanged=$true;maxEntries=2000;retentionHours=24}
    $curlPath=(Get-Command curl.exe -CommandType Application -ErrorAction Stop|Select-Object -First 1).Source
    $phase='preflight'
    $preexisting=Database
    Check 'synthetic-prefix-unused' ($preexisting.users -eq 0 -and $preexisting.sessions -eq 0 -and $preexisting.runs -eq 0 -and $preexisting.workflows -eq 0) $preexisting
    $caps=Api 'GET' '/api/v1/capabilities'
    $chat=@($caps.channels|Where-Object {$_.channel -eq 'chat'})
    $embedding=@($caps.channels|Where-Object {$_.channel -eq 'embedding'})
    Check 'mock-only-capabilities' ($chat.Count -eq 1 -and $chat[0].activeProvider -eq 'mock' -and $embedding.Count -eq 1 -and
        $embedding[0].activeProvider -eq 'mock-embedding' -and @($chat[0].candidates|Where-Object {$_.available -and $_.id -ne 'mock'}).Count -eq 0) $null
    $baseline=State
    $evidence.baseline=$baseline
    Check 'isolated-idle-engine' ($baseline.mode -eq 'redis' -and $baseline.dispatchEnabled -and $baseline.workerEnabled -and
        $baseline.connections -eq 0 -and $baseline.subscriptions -eq 0 -and $baseline.readers -eq 0 -and $baseline.activeWorkers -eq 0 -and
        $baseline.publishFailures -eq 0 -and $baseline.readFailures -eq 0 -and $baseline.leaseLost -eq 0 -and $baseline.overflow -eq 0) $baseline
    $phase='create-owned-records'
    $user=Api 'POST' '/api/v1/users' @{externalId=$Prefix;displayName='Synthetic slow SSE'}
    $evidence.userId=GuidText ([string]$user.id);$evidence.users=1
    $prompt=([string][char]0x6D4B)*7800
    Api 'POST' '/api/v1/workflows' @{id=$Prefix;name=$Prefix;version=1;nodes=@(
        @{id='start';type='start'},@{id='delay';type='delay';params=@{millis=5000};timeoutSeconds=60},
        @{id='llm1';type='llm.chat';requires=@('chat');params=@{prompt=$prompt;history='none';includeSummary='false';longMemory='none';profilePreference='none'};retry=@{maxAttempts=1;backoffMillis=0};timeoutSeconds=60},
        @{id='llm2';type='llm.chat';requires=@('chat');params=@{prompt=$prompt;history='none';includeSummary='false';longMemory='none';profilePreference='none'};retry=@{maxAttempts=1;backoffMillis=0};timeoutSeconds=60},
        @{id='end';type='end';params=@{outputs=@{reply='reply'}}}
    );edges=@(@{from='start';to='delay'},@{from='delay';to='llm1'},@{from='llm1';to='llm2'},@{from='llm2';to='end'})}|Out-Null
    $evidence.workflows=1
    $session=Api 'POST' '/api/v1/sessions' @{userId=$evidence.userId;title=$Prefix;workflowName=$Prefix}
    $evidence.sessionId=GuidText ([string]$session.id);$evidence.sessions=1
    $accepted=Api 'POST' ('/api/v1/sessions/'+$evidence.sessionId+'/messages') @{clientMsgId=($Prefix+'-one');content='synthetic slow consumer validation'}
    $runId=GuidText ([string]$accepted.runId);$evidence.runId=$runId;$evidence.runs=1
    Check 'one-message-queued' ($accepted.status -eq 'QUEUED') @{runId=$runId;status=$accepted.status}
    $phase='connect-two-clients'
    $socket=[Net.Sockets.Socket]::new([Net.Sockets.AddressFamily]::InterNetwork,[Net.Sockets.SocketType]::Stream,[Net.Sockets.ProtocolType]::Tcp)
    $socket.ReceiveBufferSize=512;$socket.SendTimeout=2000;$socket.NoDelay=$true
    $evidence.raw.receiveBufferBeforeConnect=$socket.ReceiveBufferSize
    $connect=$socket.BeginConnect([Net.IPAddress]::Loopback,8082,$null,$null)
    if (-not $connect.AsyncWaitHandle.WaitOne(2000)) {throw 'RAW_CONNECT_TIMEOUT'}
    $socket.EndConnect($connect);$connect.AsyncWaitHandle.Dispose()
    $evidence.raw.receiveBufferActual=$socket.ReceiveBufferSize
    $evidence.raw.localPort=$socket.LocalEndPoint.Port;$evidence.raw.openedAt=[DateTime]::UtcNow.ToString('o')
    $http='GET /api/v1/runs/'+$runId+'/events HTTP/1.1'+"`r`n"+'Host: 127.0.0.1:8082'+"`r`n"+'Accept: text/event-stream'+"`r`n"+'Last-Event-ID: 0'+"`r`n"+'Connection: keep-alive'+"`r`n`r`n"
    $bytes=[Text.Encoding]::ASCII.GetBytes($http);$offset=0
    while ($offset -lt $bytes.Length) {
        $sent=$socket.Send($bytes,$offset,$bytes.Length-$offset,[Net.Sockets.SocketFlags]::None)
        if ($sent -lt 1) {throw 'RAW_SEND_FAILED'}
        $offset+=$sent;$evidence.raw.sendCalls++
    }
    $evidence.raw.bytesSent=$offset;$evidence.raw.requestCount=1
    # No Receive, Read or response drain is ever invoked on this socket.
    $normalShell=[PowerShell]::Create()
    $curlMax=[int][Math]::Max(1,[Math]::Floor(150-$watch.Elapsed.TotalSeconds))
    [void]$normalShell.AddScript($normalCode.ToString()).AddArgument($BaseUrl).AddArgument($runId).AddArgument($curlPath).AddArgument($curlMax).AddArgument($normalState).AddArgument($owned)
    $normalAsync=$normalShell.BeginInvoke()
    $phase='delay-readiness'
    $ready=$false
    do {
        Budget
        CollectNormal
        if ($normalState.failed) {throw 'NORMAL_CLIENT_FAILED'}
        $state=State;$samples.Add($state)
        $db=Database
        if ($state.overflow -ne $baseline.overflow) {throw 'OVERFLOW_BEFORE_DELAY_READY'}
        if ($state.connections -eq 2 -and $state.subscriptions -eq 2 -and $state.readers -eq 1 -and $db.delayRunning -eq 1 -and $normalState.deltaCount -eq 0) {
            $evidence.delayReady=[ordered]@{state=$state;database=$db;normalDeltaCount=0;rawReadCalls=0};$ready=$true;break
        }
        if ($db.delayRunning -eq 0 -and $db.nodes -gt 2) {throw 'DELAY_READINESS_MISSED'}
        Start-Sleep -Milliseconds 100
    } while ($watch.Elapsed.TotalSeconds -lt 150)
    Check 'two-clients-same-reader-during-delay' $ready $evidence.delayReady
    $phase='observe-stream-and-overflow'
    do {
        Budget
        CollectNormal
        if ($normalState.failed) {throw 'NORMAL_CLIENT_FAILED'}
        $state=State;$samples.Add($state)
        if ($state.publishFailures -ne $baseline.publishFailures -or $state.readFailures -ne $baseline.readFailures -or $state.leaseLost -ne $baseline.leaseLost) {throw 'INFRASTRUCTURE_FAILURE_COUNTER_INCREASED'}
        $delta=$state.overflow-$baseline.overflow
        if ($delta -gt 1 -or $delta -lt 0) {throw 'UNEXPECTED_OVERFLOW_COUNT'}
        if ($delta -eq 1 -and $null -eq $evidence.firstOverflow) {
            $evidence.firstOverflow=[ordered]@{state=$state;normalDeltaCount=[int]$normalState.deltaCount;normalLastSeq=[long]$normalState.lastSeq;rawClientDisposed=$false}
        }
        if ($normalCollected -and $state.connections -eq 0 -and $state.subscriptions -eq 0 -and $state.readers -eq 0 -and $state.activeWorkers -eq 0) {break}
        Start-Sleep -Milliseconds 200
    } while ($watch.Elapsed.TotalSeconds -lt 150)
    CollectNormal
    Check 'normal-client-successful-contiguous-terminal-and-eof' ($normalCollected -and $evidence.normal.passed) $evidence.normal
    $runTerminal=$true
    $phase='owned-database-and-redis'
    $db=Database;$evidence.database=$db
    Check 'one-owned-run-fully-succeeded' ($db.users -eq 1 -and $db.sessions -eq 1 -and $db.workflows -eq 1 -and $db.runs -eq 1 -and
        $db.tasks -eq 1 -and $db.processTasks -eq 1 -and $db.doneTasks -eq 1 -and $db.nodes -eq 5 -and $db.llmNodes -eq 2 -and
        $db.userMessages -eq 1 -and $db.assistantMessages -eq 1 -and $db.successfulRuns -eq 1 -and $db.activeRuns -eq 0 -and
        $db.activeTasks -eq 0 -and $db.activeLeases -eq 0 -and $db.activeNodes -eq 0 -and $db.unsuccessfulNodes -eq 0 -and
        $db.badMockNodes -eq 0 -and $db.retries -eq 0 -and $db.duplicateNodes -eq 0 -and $db.cancelRequests -eq 0) $db
    $redis=RedisSnapshot $runId;$evidence.redis=$redis
    Check 'redis-complete-retained-stream-matches-normal-client' ($redis.entryCount -gt 0 -and $redis.entryCount -le 2000 -and
        $redis.firstSeq -eq 1 -and $redis.lastSeq -eq $redis.latestSeq -and $redis.contiguous -and $redis.matchingRun -and $redis.invalidEvents -eq 0 -and
        $redis.terminalCount -eq 1 -and $redis.terminalType -ceq 'RUN_SUCCEEDED' -and $redis.terminalStatus -ceq 'SUCCEEDED' -and
        $redis.terminalSeq -eq $redis.lastSeq -and $redis.entryCount -eq $evidence.normal.eventCount -and $redis.lastSeq -eq $evidence.normal.lastSeq -and
        $redis.deltaCount -eq $evidence.normal.deltaCount -and $redis.ttlMillis -gt 0 -and $redis.ttlMillis -le 86400000) $redis
    $final=State;$evidence.final=$final
    $evidence.raw.undisposedAtVerification=$null -ne $socket -and $null -eq $evidence.raw.closedAt
    Check 'resources-reclaimed-before-raw-client-disposal' ($final.connections -eq 0 -and $final.subscriptions -eq 0 -and $final.readers -eq 0 -and $final.activeWorkers -eq 0 -and
        $final.publishFailures -eq $baseline.publishFailures -and $final.readFailures -eq $baseline.readFailures -and $final.leaseLost -eq $baseline.leaseLost -and
        $evidence.raw.undisposedAtVerification -and $evidence.raw.readCalls -eq 0 -and $evidence.raw.requestCount -eq 1) $final
    $overflowDelta=$final.overflow-$baseline.overflow
    if ($overflowDelta -eq 1) {
        Check 'exactly-one-live-slow-client-overflow' ($null -ne $evidence.firstOverflow -and $evidence.normal.requestCount -eq 1) @{overflowDelta=$overflowDelta;normalClientPassed=$evidence.normal.passed;rawReadCalls=0}
        $outcome='PASSED'
    } elseif ($overflowDelta -eq 0) {
        $outcome='INCONCLUSIVE'
        $checks.Add([pscustomobject]@{name='slow-client-overflow-not-induced';passed=$null;evidence=@{overflowDelta=0;normalClientPassed=$evidence.normal.passed}})
    } else {throw 'UNEXPECTED_FINAL_OVERFLOW_COUNT'}
    $verificationComplete=$true;$phase='complete'
} catch {
    $outcome='FAILED';$failureKind=$_.Exception.GetType().Name
    $message=[string]$_.Exception.Message
    $failureCode=if ($message -cmatch '^(?:[A-Z][A-Z0-9_]{1,80}|CHECK_FAILED:[A-Za-z0-9-]{1,100})$') {$message} else {'VERIFICATION_EXCEPTION'}
    $checks.Add([pscustomobject]@{name=('exception-'+$phase);passed=$false;evidence=@{kind=$failureKind;code=$failureCode}})
    Write-Host ('FAIL: '+$phase+' ('+$failureCode+')')
} finally {
    # On failure/timeout, cancel only the owned run. Keep the raw client open while
    # observing cleanup; its disposal never supplies positive overflow evidence.
    if ($outcome -eq 'FAILED' -and $null -ne $runId -and $safeOrigin -and -not $runTerminal -and $watch.Elapsed.TotalSeconds -lt 162) {
        try {
            $current=Api 'GET' ('/api/v1/runs/'+$runId) $null 165
            if ($current.status -in @('PENDING','RUNNING','WAITING_APPROVAL')) {
                $cancelAt=[DateTime]::UtcNow.ToString('o')
                $cancel=Api 'POST' ('/api/v1/runs/'+$runId+'/cancel?actor=slow-sse') $null 165
                $evidence.cancel=[ordered]@{attempted=$true;at=$cancelAt;status=[string]$cancel.status;reason=$(if ($timedOut) {'timeout'} else {'verification-failure'})}
            } else {$evidence.cancel=[ordered]@{attempted=$false;reason='already-terminal';status=[string]$current.status}}
        } catch {$evidence.cancel=[ordered]@{attempted=$true;acknowledged=$false;failureKind=$_.Exception.GetType().Name}}
        try {
            do {
                $cleanupDb=Database 165;$cleanupState=State 165
                $evidence.cleanup=[ordered]@{database=$cleanupDb;state=$cleanupState;rawClientDisposed=$false}
                if ($cleanupDb.activeRuns -eq 0 -and $cleanupDb.activeTasks -eq 0 -and $cleanupDb.activeNodes -eq 0 -and $cleanupState.activeWorkers -eq 0) {break}
                Start-Sleep -Milliseconds 100
            } while ($watch.Elapsed.TotalSeconds -lt 164)
        } catch {}
    }
    # Touch only Process objects registered by this script, never a PID search.
    foreach ($process in @($owned.Values)) {
        try {if (-not $process.HasExited) {$process.Kill();[void]$process.WaitForExit(500)}} catch {}
    }
    if ($null -ne $normalAsync -and -not $normalCollected) {
        try {
            if ($normalAsync.AsyncWaitHandle.WaitOne(1000)) {CollectNormal}
            else {if ($null -ne $normalShell) {$normalShell.Stop()}}
        } catch {}
    }
    if ($null -ne $socket) {
        try {$socket.Dispose()} catch {}
        $evidence.raw.closedAt=[DateTime]::UtcNow.ToString('o')
    }
    foreach ($process in @($owned.Values)) {try {$process.Dispose()} catch {}}
    if ($null -ne $normalShell) {try {$normalShell.Dispose()} catch {}}
    $watch.Stop()
    $report=[ordered]@{
        schemaVersion=1;stage='M4-3C-slow-consumer-fault';startedAt=$startedAt.ToString('o');finishedAt=[DateTime]::UtcNow.ToString('o');elapsedMs=$watch.ElapsedMilliseconds
        baseUrl=$(if ($safeOrigin) {$BaseUrl} else {'(invalid)'});status=$outcome;passed=($outcome -eq 'PASSED');phase=$phase
        failureKind=$failureKind;failureCode=$failureCode;timedOut=$timedOut;verificationCompleteBeforeRawDisposal=$verificationComplete
        scope='Two-client correctness fault diagnostic; does not establish default-queue or 1000-connection capacity'
        expected=@{users=1;sessions=1;workflows=1;runs=1;nodes=5;sseQueueCapacity=16;overflowDelta=1;rawReadCalls=0;observationBudgetSeconds=150;overallBudgetSeconds=165}
        checks=$checks.ToArray();samples=$samples.ToArray();evidence=$evidence
    }
    [IO.File]::WriteAllText($reportPath,($report|ConvertTo-Json -Depth 18),[Text.UTF8Encoding]::new($false))
}
Write-Output ('Report: '+$reportPath)
if ($outcome -eq 'PASSED') {exit 0}
if ($outcome -eq 'INCONCLUSIVE') {exit 2}
exit 1
