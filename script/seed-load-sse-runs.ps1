<#
.SYNOPSIS
  Fill a bounded SSE sample shortfall with mock basic-chat runs through local APIs.
.NOTES
  This prepares samples, not a performance measurement. One fresh synthetic user,
  one independent session/message/run per sample, batches of at most 16. No SSE,
  SQL, Redis command, migration, file deletion, process launch or provider change.
  Config and live capabilities must prove mock-only operation before any POST.
  Failures cancel only known active runs created by this invocation. All records
  and reports remain. No request/response text or credentials are saved.
  Save with UTF-8 BOM; compatible with Windows PowerShell 5.1.
#>
param(
    [Parameter(Mandatory=$true)][ValidateRange(1,1000)][int]$Count,
    [Parameter(Mandatory=$true)][string]$EngineConfigFile,
    [Parameter(Mandatory=$true)][string]$ReportFile,
    [string]$BaseUrl = 'http://localhost:8082',
    [string]$Prefix = ('m43c-s3seed-'+[Guid]::NewGuid().ToString('N').Substring(0,20))
)
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$reportRoot = [IO.Path]::GetFullPath((Join-Path $repositoryRoot 'target/m4-3c'))
$rootPrefix = $reportRoot.TrimEnd([char[]]@('\','/'))+[IO.Path]::DirectorySeparatorChar
$startedAt = [DateTime]::UtcNow
$overallDeadline = $startedAt.AddMinutes(20)
# Reserve time inside the total twenty-minute limit for bounded cancellation.
$workDeadline = $overallDeadline.AddSeconds(-45)
$phase = 'parameters'
$failed = $false
$failureKind = $null
$failureCode = $null
$userId = $null
$userCreated = $false
$safeOrigin = $false
$engineMarker = $null
$configurationVerified = $false
$capabilitiesVerified = $false
$currentBatch = $null
$configChecks = New-Object 'System.Collections.Generic.List[object]'
$records = New-Object 'System.Collections.Generic.List[object]'
$batches = New-Object 'System.Collections.Generic.List[object]'
$seenSessions = @{}
$seenRuns = @{}
$terminalStatuses = @('SUCCEEDED','DEGRADED','FAILED','CANCELED')
$activeStatuses = @('PENDING','RUNNING','WAITING_APPROVAL')
$cleanupFailures = 0
$cancellationAttempts = 0

function Artifact-Path([string]$Requested, [bool]$MustExist = $false) {
    if ([string]::IsNullOrWhiteSpace($Requested)) { throw 'ARTIFACT_PATH_REQUIRED' }
    $candidate = if ([IO.Path]::IsPathRooted($Requested)) { $Requested } else { Join-Path $repositoryRoot $Requested }
    $path = [IO.Path]::GetFullPath($candidate)
    if (-not $path.StartsWith($rootPrefix,[StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetExtension($path) -cne '.json') { throw 'ARTIFACT_PATH_OUTSIDE_TARGET_M43C' }
    if ($MustExist -and -not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'ENGINE_CONFIG_FILE_MISSING' }
    if (-not $MustExist -and (Test-Path -LiteralPath $path)) { throw 'REPORT_FILE_ALREADY_EXISTS' }
    $ancestor = Split-Path -Parent $path
    while (-not [string]::IsNullOrEmpty($ancestor)) {
        if (Test-Path -LiteralPath $ancestor) {
            if (((Get-Item -LiteralPath $ancestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw 'ARTIFACT_REPARSE_POINT_REJECTED'
            }
        }
        $ancestor = Split-Path -Parent $ancestor
    }
    return $path
}

function Write-NewReport([string]$Path, $Value) {
    [IO.Directory]::CreateDirectory((Split-Path -Parent $Path)) | Out-Null
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes(($Value | ConvertTo-Json -Depth 10))
    $stream = [IO.File]::Open($Path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
    try { $stream.Write($bytes,0,$bytes.Length) } finally { $stream.Dispose() }
}

function Guid-Text([string]$Value) {
    $guid = [Guid]::Empty
    if ($Value -cnotmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' -or
        -not [Guid]::TryParseExact($Value,'D',[ref]$guid)) { throw 'API_UUID_INVALID' }
    return $Value
}

function Api([string]$Method, [string]$Path, $Body = $null,
    [DateTime]$Deadline = $workDeadline, [int]$TimeoutSeconds = 8, [switch]$AllowCancelConflict) {
    $remaining = ($Deadline - [DateTime]::UtcNow).TotalSeconds
    if ($remaining -lt 1) { throw 'TIME_BUDGET_EXCEEDED' }
    $timeout = [Math]::Min($TimeoutSeconds,[int][Math]::Floor($remaining))
    $request = @{Uri=($BaseUrl+$Path);Method=$Method;UseBasicParsing=$true;MaximumRedirection=0;TimeoutSec=$timeout}
    if ($null -ne $Body) {
        $request.ContentType = 'application/json; charset=utf-8'
        $request.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 5 -Compress))
    }
    try { $response = Invoke-WebRequest @request } catch {
        $status = $null
        if ($null -ne $_.Exception.Response) {
            try { $status = [int]$_.Exception.Response.StatusCode } catch { }
        }
        if ($AllowCancelConflict -and $status -eq 409) { return [pscustomobject]@{httpStatus=409;data=$null} }
        throw 'API_REQUEST_FAILED'
    }
    if ([int]$response.StatusCode -ne 200) { throw 'API_HTTP_STATUS_UNEXPECTED' }
    $text = $response.Content
    if ($text -is [byte[]]) { $text = [Text.Encoding]::UTF8.GetString($text) }
    try { $parsed = ConvertFrom-Json -InputObject $text } catch { throw 'API_JSON_INVALID' }
    if ($null -eq $parsed -or $null -eq $parsed.PSObject.Properties['code'] -or $parsed.code -ne 0 -or
        $null -eq $parsed.data) { throw 'API_RESPONSE_CONTRACT_INVALID' }
    return [pscustomobject]@{httpStatus=200;data=$parsed.data}
}

function Verify-EngineConfig {
    try { $config = ConvertFrom-Json -InputObject ([IO.File]::ReadAllText($configPath,[Text.Encoding]::UTF8)) }
    catch { throw 'ENGINE_CONFIG_JSON_INVALID' }
    if ($null -eq $config -or $config.args -isnot [Array] -or [int]$config.port -ne $origin.Port -or
        [string]$config.marker -cnotmatch '^m43c-[A-Za-z0-9-]+$') { throw 'ENGINE_CONFIG_SHAPE_INVALID' }
    $required = [ordered]@{
        'server.port'=[string]$origin.Port
        'spring.flyway.enabled'='false'
        'aiflow.provider.mode'='mock'
        'aiflow.provider.probe.enabled'='false'
        'aiflow.provider.endpoints.local.enabled'='false'
        'aiflow.provider.endpoints.cloud.enabled'='false'
        'aiflow.provider.endpoints.qwen.enabled'='false'
        'aiflow.provider.endpoints.ark.enabled'='false'
        'aiflow.provider.endpoints.deepseek.enabled'='false'
        'aiflow.retrieval.embedding.mode'='mock'
        'aiflow.retrieval.embedding.tiers[0].enabled'='false'
        'aiflow.retrieval.embedding.tiers[1].enabled'='false'
        'aiflow.retrieval.embedding.tiers[2].enabled'='false'
        'aiflow.retrieval.cloud.enabled'='false'
        'aiflow.memory.summary-enabled'='false'
        'aiflow.long-memory.extract-enabled'='false'
        'aiflow.ingest.ocr.enabled'='false'
        'aiflow.tools.enabled'='false'
        'aiflow.tools.audit.persist'='false'
        'aiflow.approval.sweep-enabled'='false'
        'aiflow.approval.notify.enabled'='false'
        'aiflow.auth.enabled'='false'
    }
    foreach ($entry in $required.GetEnumerator()) {
        $argumentPrefix = '--'+$entry.Key+'='
        $matches = @($config.args | Where-Object {([string]$_).StartsWith($argumentPrefix,[StringComparison]::Ordinal)})
        $ok = $matches.Count -eq 1 -and [string]$matches[0] -ceq ($argumentPrefix+$entry.Value)
        $configChecks.Add([pscustomobject]@{property=$entry.Key;expected=$entry.Value;passed=$ok})
        if (-not $ok) { throw 'ENGINE_CONFIG_NOT_MOCK_ISOLATED' }
    }
    $enabledEndpoints = @($config.args | Where-Object {
        [string]$_ -cmatch '^--aiflow\.provider\.endpoints\.[^.]+\.enabled=' -and [string]$_ -cnotmatch '=false$'
    })
    $markers = @($config.args | Where-Object {[string]$_ -ceq ('-Daiflow.validation.run-id='+[string]$config.marker)})
    if ($enabledEndpoints.Count -gt 0 -or $markers.Count -ne 1) { throw 'ENGINE_CONFIG_ENDPOINT_OR_MARKER_INVALID' }
    $script:engineMarker = [string]$config.marker
    $script:configurationVerified = $true
}

function Verify-MockCapabilities {
    $caps = (Api 'GET' '/api/v1/capabilities').data
    $chat = @($caps.channels | Where-Object {$_.channel -ceq 'chat'})
    $embedding = @($caps.channels | Where-Object {$_.channel -ceq 'embedding'})
    if ($chat.Count -ne 1 -or [string]$chat[0].activeProvider -cne 'mock' -or $embedding.Count -ne 1 -or
        [string]$embedding[0].activeProvider -cne 'mock-embedding' -or
        @($chat[0].candidates | Where-Object {$_.available -eq $true -and [string]$_.id -cne 'mock'}).Count -ne 0) {
        throw 'LIVE_CAPABILITIES_NOT_MOCK_ONLY'
    }
    $script:capabilitiesVerified = $true
}

function Observe-Run($Record, [switch]$Cleanup) {
    $deadline = $overallDeadline
    $timeout = 2
    if (-not $Cleanup) {
        $deadline = [DateTime]::Parse($Record.submittedAt).ToUniversalTime().AddSeconds(120)
        if ($deadline -gt $workDeadline) { $deadline = $workDeadline }
        $timeout = 8
    }
    $snapshot = (Api 'GET' ('/api/v1/runs/'+$Record.runId) -Deadline $deadline -TimeoutSeconds $timeout).data
    if ([string]$snapshot.runId -cne [string]$Record.runId -or
        [string]$snapshot.status -cnotin ($terminalStatuses+$activeStatuses)) { throw 'RUN_STATUS_CONTRACT_INVALID' }
    $Record.status = [string]$snapshot.status
    $Record.polls++
    if ($Record.status -cin $terminalStatuses) {
        if ($null -eq $snapshot.finishedAt) { throw 'TERMINAL_RUN_FINISHED_TIME_MISSING' }
        $Record.terminalAt = [string]$snapshot.finishedAt
        $Record.terminalObservedAt = [DateTime]::UtcNow.ToString('o')
    }
}

function Cancel-OwnedActiveRuns {
    foreach ($record in $records) {
        if (-not $record.runId -or $record.status -cin $terminalStatuses) { continue }
        if (($overallDeadline-[DateTime]::UtcNow).TotalSeconds -lt 1) { break }
        try {
            Observe-Run $record -Cleanup
            if ($record.status -cin $terminalStatuses) { continue }
            # Only IDs returned by this invocation's own accepted message POSTs
            # enter the records list. A concurrent terminal transition may be 409.
            $script:cancellationAttempts++
            $response = Api 'POST' ('/api/v1/runs/'+$record.runId+'/cancel?actor=sse-seed') `
                -Deadline $overallDeadline -TimeoutSeconds 2 -AllowCancelConflict
            $record.cancelRequested = $response.httpStatus -eq 200
            $record.cancelHttpStatus = $response.httpStatus
            if ($response.httpStatus -eq 200 -and ([string]$response.data.runId -cne [string]$record.runId -or
                [string]$response.data.status -cnotin @('CANCEL_REQUESTED','CANCELED'))) { throw 'CANCEL_RESPONSE_INVALID' }
        } catch { $script:cleanupFailures++ }
    }
    $cleanupDeadline = [DateTime]::UtcNow.AddSeconds(10)
    if ($cleanupDeadline -gt $overallDeadline) { $cleanupDeadline = $overallDeadline }
    do {
        $active = @($records | Where-Object {$_.runId -and $_.status -cnotin $terminalStatuses})
        if ($active.Count -eq 0) { break }
        foreach ($record in $active) {
            if (($cleanupDeadline-[DateTime]::UtcNow).TotalSeconds -lt 1) { break }
            try { Observe-Run $record -Cleanup } catch { $script:cleanupFailures++ }
        }
        if ([DateTime]::UtcNow -lt $cleanupDeadline) { Start-Sleep -Milliseconds 250 }
    } while ([DateTime]::UtcNow -lt $cleanupDeadline)
}

function Save-Batch($Batch, [bool]$Passed) {
    if ($Batch.reportWritten) { return }
    $batchRuns = @($records | Where-Object {$_.batch -eq $Batch.index})
    $Batch.finishedAt = [DateTime]::UtcNow.ToString('o')
    $Batch.submitted = @($batchRuns | Where-Object {$_.runId}).Count
    $Batch.succeeded = @($batchRuns | Where-Object {$_.status -ceq 'SUCCEEDED'}).Count
    $Batch.active = @($batchRuns | Where-Object {$_.runId -and $_.status -cnotin $terminalStatuses}).Count
    $Batch.passed = $Passed
    Write-NewReport $Batch.reportPath ([pscustomobject]@{
        scope='S3b mock sample preparation; not a performance measurement';batch=$Batch.index
        expected=$Batch.expected;startedAt=$Batch.startedAt;finishedAt=$Batch.finishedAt;passed=$Passed
        submitted=$Batch.submitted;succeeded=$Batch.succeeded;active=$Batch.active;runs=$batchRuns
        payloadsRecorded=$false
    })
    $Batch.reportWritten = $true
}

$reportPath = Artifact-Path $ReportFile
$configPath = Artifact-Path $EngineConfigFile $true
$batchReportStem = [IO.Path]::Combine((Split-Path -Parent $reportPath),[IO.Path]::GetFileNameWithoutExtension($reportPath))
$batchCount = [int][Math]::Ceiling($Count / 16.0)
for ($i = 1; $i -le $batchCount; $i++) { [void](Artifact-Path ($batchReportStem+'-batch-'+$i.ToString('000')+'.json')) }

try {
    if ($Prefix -cnotmatch '^m43c-[A-Za-z0-9-]{1,34}$') { throw 'SYNTHETIC_PREFIX_INVALID' }
    $origin = $null
    $safeOrigin = [Uri]::TryCreate($BaseUrl,[UriKind]::Absolute,[ref]$origin) -and $origin.Scheme -ceq 'http' -and
        $origin.Host -in @('localhost','127.0.0.1') -and $origin.Port -eq 8082 -and
        -not $origin.UserInfo -and -not $origin.Query -and -not $origin.Fragment -and $origin.AbsolutePath -eq '/'
    if (-not $safeOrigin) { throw 'LOCAL_MOCK_ORIGIN_REQUIRED' }
    $BaseUrl = $origin.GetLeftPart([UriPartial]::Authority)
    $phase = 'mock-preflight'
    Verify-EngineConfig
    Verify-MockCapabilities
    $phase = 'create-fresh-user'
    $userCreationStartedAt = [DateTime]::UtcNow
    $user = (Api 'POST' '/api/v1/users' @{externalId=$Prefix;displayName='Synthetic SSE sample preparation'}).data
    $userId = Guid-Text ([string]$user.id)
    $createdAt = [DateTimeOffset]::Parse([string]$user.createdAt).UtcDateTime
    if ([string]$user.externalId -cne $Prefix -or $createdAt -lt $userCreationStartedAt -or
        $createdAt -gt [DateTime]::UtcNow.AddSeconds(1)) { throw 'SYNTHETIC_USER_PREFIX_ALREADY_USED_OR_INVALID' }
    $userCreated = $true

    for ($batchIndex = 1; $batchIndex -le $batchCount; $batchIndex++) {
        if ([DateTime]::UtcNow -ge $workDeadline) { throw 'TIME_BUDGET_EXCEEDED' }
        # Recheck live routing before every batch rather than relying only on the
        # initial configuration snapshot.
        $phase = 'batch-mock-preflight'
        Verify-MockCapabilities
        $firstIndex = ($batchIndex-1)*16+1
        $lastIndex = [Math]::Min($batchIndex*16,$Count)
        $currentBatch = [pscustomobject]@{
            index=$batchIndex;expected=($lastIndex-$firstIndex+1);startedAt=[DateTime]::UtcNow.ToString('o')
            finishedAt=$null;submitted=0;succeeded=0;active=0;passed=$false;reportWritten=$false
            reportPath=($batchReportStem+'-batch-'+$batchIndex.ToString('000')+'.json')
        }
        $batches.Add($currentBatch)
        $phase = 'batch-submit'
        for ($index = $firstIndex; $index -le $lastIndex; $index++) {
            $session = (Api 'POST' '/api/v1/sessions' @{userId=$userId;title=$Prefix;workflowName='basic-chat'}).data
            $sessionId = Guid-Text ([string]$session.id)
            if ([string]$session.userId -cne $userId -or $seenSessions.ContainsKey($sessionId)) { throw 'SESSION_OWNERSHIP_OR_UNIQUENESS_INVALID' }
            $seenSessions[$sessionId] = $true
            $record = [pscustomobject]@{
                index=$index;batch=$batchIndex;sessionId=$sessionId;clientMsgId=($Prefix+'-'+$index)
                runId=$null;status='SESSION_CREATED';submittedAt=$null;terminalAt=$null;terminalObservedAt=$null
                polls=0;cancelRequested=$false;cancelHttpStatus=$null
            }
            $records.Add($record)
            $record.submittedAt = [DateTime]::UtcNow.ToString('o')
            $accepted = (Api 'POST' ('/api/v1/sessions/'+$sessionId+'/messages') `
                @{clientMsgId=$record.clientMsgId;content='synthetic SSE sample'}).data
            $record.runId = Guid-Text ([string]$accepted.runId)
            $record.status = 'PENDING'
            if ($seenRuns.ContainsKey($record.runId) -or [string]$accepted.status -cne 'QUEUED') { throw 'RUN_ACCEPTANCE_OR_UNIQUENESS_INVALID' }
            $seenRuns[$record.runId] = $true
        }
        $phase = 'batch-terminal-poll'
        do {
            $active = @($records | Where-Object {$_.batch -eq $batchIndex -and $_.runId -and $_.status -cnotin $terminalStatuses})
            foreach ($record in $active) {
                Observe-Run $record
                if ($record.status -cin $terminalStatuses -and $record.status -cne 'SUCCEEDED') { throw 'MOCK_BASIC_CHAT_UNEXPECTED_TERMINAL' }
            }
            $remaining = @($records | Where-Object {$_.batch -eq $batchIndex -and $_.runId -and $_.status -cnotin $terminalStatuses})
            if ($remaining.Count -eq 0) { break }
            Start-Sleep -Milliseconds 250
        } while ([DateTime]::UtcNow -lt $workDeadline)
        if ($remaining.Count -gt 0) { throw 'TIME_BUDGET_EXCEEDED' }
        $phase = 'batch-report'
        Save-Batch $currentBatch $true
        Write-Output "Batch $batchIndex/$batchCount complete: $($currentBatch.succeeded) successful mock runs."
    }
    $phase = 'complete'
} catch {
    $failed = $true
    $failureKind = $_.Exception.GetType().Name
    if ($_.Exception.Message -cmatch '^[A-Z][A-Z0-9_]{1,100}$') { $failureCode = $_.Exception.Message }
} finally {
    if ($failed -and $configurationVerified -and $capabilitiesVerified) { Cancel-OwnedActiveRuns }
    if ($null -ne $currentBatch -and -not $currentBatch.reportWritten) {
        try { Save-Batch $currentBatch $false } catch { $failed=$true;$cleanupFailures++ }
    }
    $successful = @($records | Where-Object {$_.status -ceq 'SUCCEEDED'}).Count
    $submitted = @($records | Where-Object {$_.runId}).Count
    $active = @($records | Where-Object {$_.runId -and $_.status -cnotin $terminalStatuses})
    $ready = -not $failed -and $userCreated -and $records.Count -eq $Count -and $submitted -eq $Count -and
        $successful -eq $Count -and $active.Count -eq 0
    Write-NewReport $reportPath ([pscustomobject]@{
        schemaVersion=1;scope='S3b mock sample preparation; not a performance measurement'
        startedAt=$startedAt.ToString('o');finishedAt=[DateTime]::UtcNow.ToString('o');phase=$phase
        passed=$ready;failureKind=$failureKind;failureCode=$failureCode
        prefix=$Prefix;userId=$userId;createdNewUser=$userCreated;expectedCount=$Count
        sessionsCreated=$records.Count;runsAccepted=$submitted;succeeded=$successful;remainingActiveRuns=$active.Count
        unconfirmedSubmissions=@($records | Where-Object {$_.submittedAt -and -not $_.runId}).Count
        batchSize=16;perRunTimeoutSeconds=120;overallLimitSeconds=1200
        engineMarker=$engineMarker;configurationVerified=$configurationVerified;liveMockCapabilitiesVerified=$capabilitiesVerified
        configChecks=$configChecks.ToArray();batches=$batches.ToArray();runs=$records.ToArray()
        cancellationAttempts=$cancellationAttempts;cleanupFailures=$cleanupFailures
        payloadsRecorded=$false;realProvidersUsed=$false;recordsDeleted=$false
        nextStep='Re-run prepare-load-sse-runs.ps1 for fresh PG terminal and Redis cursor/TTL verification before SSE load.'
    })
}
Write-Output "Prepared: $successful/$Count successful mock runs; report: $reportPath"
if (-not $ready) { exit 1 }
