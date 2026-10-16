<#
.SYNOPSIS
  对已启动的本机离线实例执行合成端到端演示，不启动或停止服务。
.DESCRIPTION
  先核对 marker 对应的启动器实例、监听进程与严格离线就绪状态，再验证对话、SSE、窗口记忆、
  本地 RAG、内存音频往返、人工审批恢复与取消。仅新增合成记录，不删除或重置数据。
  报告只保留固定检查名、状态、合成 ID、计数及时间；不保存正文、SSE 或音频。
.EXAMPLE
  .\script\verify-offline-demo.ps1 -Marker offline-example -BaseUrl http://127.0.0.1:8083 -Prefix offline-demo
.PARAMETER Marker
  offline-engine.ps1 Start 返回的实例 marker；必须对应仍在运行的完整受控实例。
.PARAMETER Prefix
  1 至 15 个英文字母、数字或连字符，首位必须为英文字母或数字。
  脚本追加 32 位随机 GUID；最长 clientMsgId 仍须落在数据库的 64 字符上限内。
.NOTES
  UTF-8 BOM；兼容 Windows PowerShell 5.1 / PowerShell 7；不需要额外模块。
  BaseUrl 仅接受与实例端口相符的 http://127.0.0.1 origin；报告直接写入本项目 target/ops/。
#>
param(
    [Parameter(Mandatory = $true)][string]$Marker,
    [string]$BaseUrl = 'http://127.0.0.1:8083',
    [string]$Prefix = 'offline-demo',
    [string]$OutFile,
    [int]$TimeoutSeconds = 30
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
Add-Type -AssemblyName System.Net.Http
. (Join-Path $PSScriptRoot 'offline-engine-common.ps1')
Assert-OfflineMarker $Marker

function Stop-Demo([string]$Code) {
    $script:FailureCode = $Code
    throw [InvalidOperationException]::new('offline_demo_failed')
}

function Require-Value([bool]$Condition, [string]$Code = 'contract_mismatch') {
    if (-not $Condition) { Stop-Demo $Code }
}

# 参数先验证，拒绝任意主机、重定向、路径逃逸和覆盖旧报告。
if ($Prefix -notmatch '^[A-Za-z0-9][A-Za-z0-9-]{0,14}$') {
    throw [ArgumentException]::new('invalid_prefix')
}
if ($TimeoutSeconds -lt 5 -or $TimeoutSeconds -gt 120) {
    throw [ArgumentException]::new('invalid_timeout')
}
$origin = $null
if (-not [Uri]::TryCreate($BaseUrl, [UriKind]::Absolute, [ref]$origin) -or
    $origin.Scheme -cne 'http' -or $origin.Host -cne '127.0.0.1' -or $origin.UserInfo -ne '' -or
    $origin.Query -ne '' -or $origin.Fragment -ne '' -or $origin.AbsolutePath -ne '/') {
    throw [ArgumentException]::new('offline_ipv4_origin_required')
}
$origin = [Uri]::new($origin.GetLeftPart([UriPartial]::Authority) + '/')
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$opsRoot = [IO.Path]::GetFullPath((Join-Path $repositoryRoot 'target/ops'))
$token = $Prefix + '-' + [Guid]::NewGuid().ToString('N')
# V1 external_id / client_msg_id 与 V2 kb_id 均为 varchar(64)。全部派生 ID 在网络与业务操作前预检。
# 最长 suffix 为 -approval-second（16 字符）：Prefix(15) + 连字符(1) + GUID(32) + suffix(16) = 64。
$syntheticSuffixes = @('', '-chat', '-rag', '-audio', '-approval', '-kb', '-empty-kb', '-reviewer',
    '-document', '-chat-message', '-rag-message', '-audio-message', '-approval-first', '-approval-second')
foreach ($suffix in $syntheticSuffixes) {
    if (($token.Length + $suffix.Length) -gt 64) {
        throw [ArgumentException]::new('synthetic_identifier_too_long')
    }
}
if ([string]::IsNullOrWhiteSpace($OutFile)) {
    $OutFile = Join-Path $opsRoot ('demo-' + $token + '.json')
} elseif (-not [IO.Path]::IsPathRooted($OutFile)) {
    $OutFile = Join-Path $repositoryRoot $OutFile
}
$reportPath = [IO.Path]::GetFullPath($OutFile)
if (-not [string]::Equals([IO.Path]::GetDirectoryName($reportPath), $opsRoot,
        [StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetExtension($reportPath) -ne '.json') {
    throw [ArgumentException]::new('report_must_be_json_directly_in_target_ops')
}
foreach ($directory in @($repositoryRoot, (Join-Path $repositoryRoot 'target'), $opsRoot)) {
    if (Test-Path -LiteralPath $directory) {
        $item = Get-Item -LiteralPath $directory -Force
        if (-not $item.PSIsContainer -or
            ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw [ArgumentException]::new('report_directory_must_be_plain_directory')
        }
    }
}
if (Test-Path -LiteralPath $reportPath) {
    throw [ArgumentException]::new('report_already_exists')
}
[IO.Directory]::CreateDirectory($opsRoot) | Out-Null

$checkNames = @(
    'ownedOfflineInstance', 'strictOfflineReadiness', 'instanceHealth', 'chatMock', 'embeddingMock', 'asrMock', 'ttsMock',
    'redisEvents', 'persistentDispatchDisabled', 'workflowsRegistered', 'syntheticUserAndSession',
    'chatAccepted', 'messageIdempotency', 'chatSucceeded', 'chatUtf8Reply',
    'sseContiguousSequence', 'sseSingleTerminal', 'sseDeltaMatchesReply', 'sseResumeSuffix',
    'sseMalformedCursor400', 'sseFutureCursor400', 'memoryStatistics', 'memoryPreview',
    'ragDocumentIndexed', 'ragChunkMetadata', 'ragLocalCitationMap', 'ragEmptyNoCitation',
    'ragWorkflowSucceeded', 'ragWorkflowSources', 'ttsResponseHeaders', 'ttsPcmWav',
    'asrMemoryRoundtrip', 'audioWorkflowSucceeded', 'audioOutputHasNoPayload',
    'approvalFirstWaiting', 'approvalPendingTodo', 'approvalWaitReleasesSessionLock',
    'approvalResumedApproved', 'approvalResumeHasNoReplay', 'approvalWaitingCanceled',
    'approvalCancelClosesTodo', 'approvalCanceledDecision409', 'canceledRunTerminalEvent'
)
$checks = [ordered]@{}
foreach ($name in $checkNames) {
    $checks[$name] = [ordered]@{ name = $name; result = 'NOT_RUN'; elapsedMs = 0; counts = @{} }
}
$ids = [ordered]@{ prefix = $token }
$script:ActiveCheck = $null
$script:CheckTimer = $null
$script:FailureCode = $null
$script:OfflineInstanceValidated = $false
$exitCode = 1
$startedAt = [DateTime]::UtcNow
$demoTimer = [Diagnostics.Stopwatch]::StartNew()
$handler = $null
$client = $null

function Begin-Check([string]$Name) {
    Require-Value ($checks.Contains($Name) -and $null -eq $script:ActiveCheck) 'invalid_check_sequence'
    $script:ActiveCheck = $Name
    $script:CheckTimer = [Diagnostics.Stopwatch]::StartNew()
}

function Complete-Check([bool]$Passed, [System.Collections.IDictionary]$Counts = @{}) {
    Require-Value $Passed 'assertion_failed'
    $script:CheckTimer.Stop()
    $checks[$script:ActiveCheck].result = 'PASS'
    $checks[$script:ActiveCheck].elapsedMs = [Math]::Round($script:CheckTimer.Elapsed.TotalMilliseconds, 1)
    # Counts 的键由脚本固定，值必须为数值，禁止正文通过证据字段进入报告。
    foreach ($value in $Counts.Values) {
        Require-Value ($value -is [ValueType] -and $value -isnot [bool]) 'invalid_report_metric'
    }
    $checks[$script:ActiveCheck].counts = $Counts
    Write-Host ('PASS: ' + $script:ActiveCheck)
    $script:ActiveCheck = $null
}

function Record-Id([string]$Name, $Value) {
    $parsedId = [Guid]::Empty
    Require-Value ([Guid]::TryParse([string]$Value, [ref]$parsedId)) 'invalid_synthetic_id'
    $ids[$Name] = $parsedId.ToString()
    return $parsedId.ToString()
}

function Remaining-Milliseconds([DateTime]$Deadline) {
    $remaining = [int][Math]::Ceiling(($Deadline - [DateTime]::UtcNow).TotalMilliseconds)
    if ($remaining -le 0) { Stop-Demo 'request_timeout' }
    return $remaining
}

function Wait-BoundedTask($Task, [DateTime]$Deadline) {
    if (-not $Task.Wait((Remaining-Milliseconds $Deadline))) { Stop-Demo 'request_timeout' }
    # 通过 GetAwaiter 读取异常，但外层仅记录受控类型，绝不输出 Message / InnerException。
    return $Task.GetAwaiter().GetResult()
}

function Local-Uri([string]$Path) {
    Require-Value ($Path.StartsWith('/') -and -not $Path.StartsWith('//') -and
        -not $Path.Contains('\') -and -not $Path.Contains('#')) 'invalid_request_path'
    $uri = [Uri]::new($origin, $Path)
    Require-Value ($uri.Scheme -eq $origin.Scheme -and $uri.Host -eq $origin.Host -and
        $uri.Port -eq $origin.Port -and $uri.UserInfo -eq '') 'request_origin_mismatch'
    return $uri
}

function Json-Content($Body) {
    $json = $Body | ConvertTo-Json -Depth 30 -Compress
    return [Net.Http.StringContent]::new($json, [Text.Encoding]::UTF8, 'application/json')
}

function Read-BoundedBytes($Content, [DateTime]$Deadline, [int]$MaximumBytes = 2097152) {
    if ($null -ne $Content.Headers.ContentLength) {
        Require-Value ($Content.Headers.ContentLength -le $MaximumBytes) 'response_too_large'
    }
    $stream = $null
    $memory = [IO.MemoryStream]::new()
    try {
        $stream = Wait-BoundedTask ($Content.ReadAsStreamAsync()) $Deadline
        $buffer = New-Object byte[] 8192
        while ($true) {
            $read = Wait-BoundedTask ($stream.ReadAsync($buffer, 0, $buffer.Length)) $Deadline
            if ($read -eq 0) { break }
            Require-Value (($memory.Length + $read) -le $MaximumBytes) 'response_too_large'
            $memory.Write($buffer, 0, $read)
        }
        return ,$memory.ToArray()
    } finally {
        if ($null -ne $stream) { $stream.Dispose() }
        $memory.Dispose()
    }
}

function Invoke-LocalHttp([string]$Method, [string]$Path, $Content = $null,
        [int]$ExpectedStatus = 200, [System.Collections.IDictionary]$Headers = @{},
        [int]$BudgetMilliseconds = ($TimeoutSeconds * 1000)) {
    $request = $null
    $response = $null
    $cancellation = [Threading.CancellationTokenSource]::new()
    $deadline = [DateTime]::UtcNow.AddMilliseconds($BudgetMilliseconds)
    try {
        if ($Method -eq 'POST') {
            Require-Value ($script:OfflineInstanceValidated -eq $true) 'offline_instance_not_verified'
        }
        $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method), (Local-Uri $Path))
        $request.Content = $Content
        foreach ($key in $Headers.Keys) {
            Require-Value ($request.Headers.TryAddWithoutValidation($key, [string]$Headers[$key])) 'invalid_request_header'
        }
        $cancellation.CancelAfter($BudgetMilliseconds)
        $response = Wait-BoundedTask ($client.SendAsync($request,
            [Net.Http.HttpCompletionOption]::ResponseHeadersRead, $cancellation.Token)) $deadline
        Require-Value ([int]$response.StatusCode -eq $ExpectedStatus) 'unexpected_http_status'
        $bytes = Read-BoundedBytes $response.Content $deadline
        $headersCopy = @{}
        foreach ($header in $response.Headers) { $headersCopy[$header.Key] = $header.Value -join ',' }
        foreach ($header in $response.Content.Headers) { $headersCopy[$header.Key] = $header.Value -join ',' }
        return [pscustomobject]@{ status = [int]$response.StatusCode; headers = $headersCopy; bytes = $bytes }
    } finally {
        $cancellation.Cancel()
        if ($null -ne $response) { $response.Dispose() }
        if ($null -ne $request) { $request.Dispose() }
        elseif ($null -ne $Content) { $Content.Dispose() }
        $cancellation.Dispose()
    }
}

function Api([string]$Method, [string]$Path, $Body = $null,
        [int]$BudgetMilliseconds = ($TimeoutSeconds * 1000)) {
    $content = $null
    if ($null -ne $Body) { $content = Json-Content $Body }
    $record = Invoke-LocalHttp $Method $Path $content 200 @{} $BudgetMilliseconds
    $envelope = [Text.Encoding]::UTF8.GetString($record.bytes) | ConvertFrom-Json
    Require-Value ($null -ne $envelope -and $envelope.code -eq 0) 'invalid_api_envelope'
    return $envelope.data
}

function Wait-Run([string]$RunId, [string]$Expected) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ($true) {
        $snapshot = Api 'GET' "/api/v1/runs/$RunId" $null (Remaining-Milliseconds $deadline)
        Require-Value ($snapshot.runId -eq $RunId) 'run_id_mismatch'
        if ($snapshot.status -eq $Expected) { return $snapshot }
        if ($snapshot.status -in @('SUCCEEDED', 'DEGRADED', 'FAILED', 'CANCELED')) {
            Stop-Demo 'unexpected_run_terminal'
        }
        $remaining = Remaining-Milliseconds $deadline
        Start-Sleep -Milliseconds ([Math]::Min(150, $remaining))
    }
}

function Read-RunEvents([string]$RunId, [long]$Cursor = 0) {
    $request = $null
    $response = $null
    $reader = $null
    $stream = $null
    $cancellation = [Threading.CancellationTokenSource]::new()
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $events = [Collections.Generic.List[object]]::new()
    try {
        $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Get,
            (Local-Uri "/api/v1/runs/$RunId/events"))
        $request.Headers.TryAddWithoutValidation('Last-Event-ID', [string]$Cursor) | Out-Null
        $request.Headers.TryAddWithoutValidation('Accept', 'text/event-stream') | Out-Null
        $cancellation.CancelAfter($TimeoutSeconds * 1000)
        $response = Wait-BoundedTask ($client.SendAsync($request,
            [Net.Http.HttpCompletionOption]::ResponseHeadersRead, $cancellation.Token)) $deadline
        Require-Value ([int]$response.StatusCode -eq 200 -and
            $response.Content.Headers.ContentType.MediaType -eq 'text/event-stream') 'invalid_sse_response'
        $stream = Wait-BoundedTask ($response.Content.ReadAsStreamAsync()) $deadline
        $reader = [IO.StreamReader]::new($stream, [Text.UTF8Encoding]::new($false, $true), $false, 4096, $true)
        $buffer = New-Object char[] 4096
        $pending = ''
        $eventId = $null
        $eventName = $null
        $dataLines = [Collections.Generic.List[string]]::new()
        $characters = 0
        $lineCount = 0
        while ($true) {
            $read = Wait-BoundedTask ($reader.ReadAsync($buffer, 0, $buffer.Length)) $deadline
            if ($read -eq 0) { break }
            $characters += $read
            Require-Value ($characters -le 1048576) 'sse_too_large'
            $pending += [string]::new($buffer, 0, $read)
            while ($true) {
                $newline = $pending.IndexOf("`n")
                if ($newline -lt 0) { break }
                Require-Value ($newline -le 65536) 'sse_line_too_large'
                $line = $pending.Substring(0, $newline).TrimEnd([char]13)
                $pending = $pending.Substring($newline + 1)
                $lineCount++
                Require-Value ($lineCount -le 40000) 'sse_too_many_lines'
                if ($line.Length -eq 0) {
                    if ($dataLines.Count -gt 0) {
                        Require-Value ($events.Count -lt 2000) 'sse_too_many_events'
                        $event = ($dataLines -join "`n") | ConvertFrom-Json
                        Require-Value ($event.runId -eq $RunId -and
                            [string]$event.seq -ceq $eventId -and $event.type -ceq $eventName) 'invalid_sse_frame'
                        $events.Add($event)
                    }
                    $eventId = $null
                    $eventName = $null
                    $dataLines.Clear()
                } elseif ($line.StartsWith('id:')) { $eventId = $line.Substring(3).TrimStart() }
                elseif ($line.StartsWith('event:')) { $eventName = $line.Substring(6).TrimStart() }
                elseif ($line.StartsWith('data:')) { $dataLines.Add($line.Substring(5).TrimStart()) }
                elseif (-not $line.StartsWith(':')) { Stop-Demo 'unsupported_sse_field' }
            }
            Require-Value ($pending.Length -le 65536) 'sse_line_too_large'
        }
        Require-Value ($pending.Length -eq 0 -and $dataLines.Count -eq 0 -and $events.Count -gt 0) 'incomplete_sse'
        Require-Value ($events[$events.Count - 1].type -in
            @('RUN_SUCCEEDED', 'RUN_DEGRADED', 'RUN_FAILED', 'RUN_CANCELED')) 'sse_missing_terminal'
        return $events.ToArray()
    } finally {
        $cancellation.Cancel()
        if ($null -ne $reader) { $reader.Dispose() }
        if ($null -ne $stream) { $stream.Dispose() }
        if ($null -ne $response) { $response.Dispose() }
        if ($null -ne $request) { $request.Dispose() }
        $cancellation.Dispose()
    }
}

function New-Session([string]$WorkflowName, [string]$IdName) {
    $session = Api 'POST' '/api/v1/sessions' @{
        userId = $ids.userId; title = '合成离线演示'; workflowName = $WorkflowName
    }
    return (Record-Id $IdName $session.id)
}

function Submit-Message([string]$SessionId, [string]$ClientId, [string]$Text, [string]$IdName) {
    $accepted = Api 'POST' "/api/v1/sessions/$SessionId/messages" @{ clientMsgId = $ClientId; content = $Text }
    Record-Id ($IdName + 'RunId') $accepted.runId | Out-Null
    Record-Id ($IdName + 'MessageId') $accepted.messageId | Out-Null
    Require-Value ($accepted.status -eq 'QUEUED') 'message_not_queued'
    return $accepted
}

try {
    Begin-Check 'ownedOfflineInstance'
    $offlinePaths = Get-OfflinePaths $Marker
    $offlineInstance = Read-OfflineJson $offlinePaths.instance
    Require-Value ($offlineInstance.marker -ceq $Marker -and $origin.Port -eq $offlineInstance.port) 'offline_instance_origin_mismatch'
    $offlineIdentity = Test-OfflineInstanceIdentity $offlineInstance
    Require-Value ($offlineIdentity.verified -is [bool] -and $offlineIdentity.verified -and
        $offlineIdentity.state -ceq 'RUNNING') 'offline_instance_identity_unverified'
    $listenerOwned = Test-OfflineListenerOwnership -Port $offlineInstance.port -ExpectedPid $offlineInstance.pid
    Complete-Check ($listenerOwned -is [bool] -and $listenerOwned)

    Begin-Check 'strictOfflineReadiness'
    $offlineReadiness = Get-OfflineReadiness -Port $offlineInstance.port
    Complete-Check ($offlineReadiness.passed -is [bool] -and $offlineReadiness.passed -and
        @($offlineReadiness.checks).Count -gt 0 -and
        @($offlineReadiness.checks | Where-Object { $_.passed -isnot [bool] -or -not $_.passed }).Count -eq 0) @{
        readinessChecks = @($offlineReadiness.checks).Count
    }
    $script:OfflineInstanceValidated = $true

    $handler = [Net.Http.HttpClientHandler]::new()
    $handler.AllowAutoRedirect = $false
    $handler.UseProxy = $false
    $client = [Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromMilliseconds(-1)

    Begin-Check 'instanceHealth'
    $healthRecord = Invoke-LocalHttp 'GET' '/actuator/health'
    $health = [Text.Encoding]::UTF8.GetString($healthRecord.bytes) | ConvertFrom-Json
    Complete-Check ($health.status -eq 'UP')

    $capabilities = $null
    foreach ($channelName in @('chat', 'embedding', 'asr', 'tts')) {
        Begin-Check ($channelName + 'Mock')
        if ($null -eq $capabilities) { $capabilities = Api 'GET' '/api/v1/capabilities' }
        $channels = @($capabilities.channels | Where-Object { $_.channel -eq $channelName })
        $providerId = 'mock'
        if ($channelName -eq 'embedding') { $providerId = 'mock-embedding' }
        $valid = $channels.Count -eq 1 -and $channels[0].activeProvider -eq $providerId -and
            $channels[0].activeTier -eq 'mock'
        if ($channelName -in @('asr', 'tts')) { $valid = $valid -and $channels[0].status -eq 'mock' }
        Complete-Check $valid @{ channelCount = $channels.Count }
    }

    Begin-Check 'redisEvents'
    $eventStatus = Api 'GET' '/api/v1/runs/events/status'
    Complete-Check ($eventStatus.bus.mode -eq 'redis')

    Begin-Check 'persistentDispatchDisabled'
    $dispatchStatus = Api 'GET' '/api/v1/runs/dispatch/status'
    Complete-Check ($dispatchStatus.enabled -eq $false -and $dispatchStatus.workerEnabled -eq $false -and
        $dispatchStatus.activeWorkers -eq 0) @{ activeWorkers = [int]$dispatchStatus.activeWorkers }

    Begin-Check 'workflowsRegistered'
    $chatName = $token + '-chat'
    $ragName = $token + '-rag'
    $audioName = $token + '-audio'
    $approvalName = $token + '-approval'
    $kbId = $token + '-kb'
    $emptyKbId = $token + '-empty-kb'
    $assignee = $token + '-reviewer'
    $ids.chatWorkflow = $chatName
    $ids.ragWorkflow = $ragName
    $ids.audioWorkflow = $audioName
    $ids.approvalWorkflow = $approvalName
    $ids.kbId = $kbId
    $ids.emptyKbId = $emptyKbId
    $ids.assignee = $assignee
    $retry = @{ maxAttempts = 1; backoffMillis = 0 }
    $definitions = @(
        @{
            id = $chatName; name = $chatName; version = 1
            nodes = @(
                @{ id = 'start'; type = 'start'; name = 'start' },
                @{ id = 'llm'; type = 'llm.chat'; name = 'llm'; requires = @('chat');
                    params = @{ prompt = '${input}' }; retry = $retry; timeoutSeconds = 10 },
                @{ id = 'end'; type = 'end'; name = 'end'; params = @{ outputs = @{ reply = 'reply'; fallback = 'fallback' } } }
            )
            edges = @(@{ from = 'start'; to = 'llm' }, @{ from = 'llm'; to = 'end' })
        },
        @{
            id = $ragName; name = $ragName; version = 1
            nodes = @(
                @{ id = 'start'; type = 'start'; name = 'start' },
                @{ id = 'retrieve'; type = 'retrieval.search'; name = 'retrieve'; requires = @('embedding');
                    params = @{ query = '${input}'; kbId = $kbId; topK = 3; minScore = 0.5; sources = 'local' };
                    retry = $retry; timeoutSeconds = 10 },
                @{ id = 'answer'; type = 'llm.chat'; name = 'answer'; requires = @('chat');
                    params = @{ prompt = '${context}' + "`n`n问题：" + '${input}' }; retry = $retry; timeoutSeconds = 10 },
                @{ id = 'end'; type = 'end'; name = 'end';
                    params = @{ outputs = @{ reply = 'reply'; citations = 'citations'; chunks = 'chunks' } } }
            )
            edges = @(@{ from = 'start'; to = 'retrieve' }, @{ from = 'retrieve'; to = 'answer' }, @{ from = 'answer'; to = 'end' })
        },
        @{
            id = $audioName; name = $audioName; version = 1
            nodes = @(
                @{ id = 'start'; type = 'start'; name = 'start' },
                @{ id = 'tts'; type = 'tts.speak'; name = 'tts'; requires = @('tts');
                    params = @{ text = '${input}'; format = 'wav' }; retry = $retry; timeoutSeconds = 10 },
                @{ id = 'asr'; type = 'asr.transcribe'; name = 'asr'; requires = @('asr');
                    params = @{ audioHandle = '${audioHandle}' }; retry = $retry; timeoutSeconds = 10 },
                @{ id = 'end'; type = 'end'; name = 'end'; params = @{ outputs = @{ reply = 'transcript' } } }
            )
            edges = @(@{ from = 'start'; to = 'tts' }, @{ from = 'tts'; to = 'asr' }, @{ from = 'asr'; to = 'end' })
        },
        @{
            id = $approvalName; name = $approvalName; version = 1
            nodes = @(
                @{ id = 'start'; type = 'start'; name = 'start' },
                @{ id = 'approve'; type = 'approval'; name = 'approve'; params = @{
                    title = '合成审批'; detail = '合成说明'; assignees = $assignee; waitSeconds = 600;
                    onTimeout = 'reject'; maxLevel = 1; onExhausted = 'reject'; onReject = 'fallback'; fallback = '合成拒绝'
                } },
                @{ id = 'end'; type = 'end'; name = 'end'; params = @{ outputs = @{
                    approvalStatus = 'approvalStatus'; approvalBy = 'approvalBy'; reply = 'reply'; fallback = 'fallback'
                } } }
            )
            edges = @(@{ from = 'start'; to = 'approve' }, @{ from = 'approve'; to = 'end' })
        }
    )
    foreach ($definition in $definitions) { Api 'POST' '/api/v1/workflows' $definition | Out-Null }
    $registered = @(Api 'GET' '/api/v1/workflows')
    $registeredCount = 0
    foreach ($definition in $definitions) {
        $matching = @($registered | Where-Object { $_.name -eq $definition.name -and $_.version -eq 1 })
        Require-Value ($matching.Count -eq 1) 'workflow_registration_mismatch'
        $registeredCount++
    }
    Complete-Check ($registeredCount -eq 4) @{ workflows = $registeredCount }

    Begin-Check 'syntheticUserAndSession'
    $user = Api 'POST' '/api/v1/users' @{ externalId = $token; displayName = '合成离线用户' }
    Record-Id 'userId' $user.id | Out-Null
    $chatSession = New-Session $chatName 'chatSessionId'
    Complete-Check ($null -ne $chatSession)

    $chatInput = '合成中文对话：蓝鲸计划离线检查'
    $chatClientId = $token + '-chat-message'
    Begin-Check 'chatAccepted'
    $accepted = Submit-Message $chatSession $chatClientId $chatInput 'chat'
    Complete-Check ($accepted.status -eq 'QUEUED')

    Begin-Check 'messageIdempotency'
    $duplicate = Api 'POST' "/api/v1/sessions/$chatSession/messages" @{ clientMsgId = $chatClientId; content = $chatInput }
    Complete-Check ($duplicate.runId -eq $accepted.runId -and $duplicate.messageId -eq $accepted.messageId)

    Begin-Check 'chatSucceeded'
    $chatRun = Wait-Run $ids.chatRunId 'SUCCEEDED'
    Complete-Check ($chatRun.steps -eq 3 -and $null -eq $chatRun.error -and $null -ne $chatRun.finishedAt) @{ steps = [int]$chatRun.steps }

    Begin-Check 'chatUtf8Reply'
    $reply = [string]$chatRun.output.reply
    Complete-Check ($reply.StartsWith('（mock）已收到：') -and $reply.Contains($chatInput)) @{ replyChars = $reply.Length }

    Begin-Check 'sseContiguousSequence'
    $events = @(Read-RunEvents $ids.chatRunId)
    $sequenceValid = $events.Count -gt 2
    for ($index = 0; $index -lt $events.Count; $index++) {
        if ($events[$index].seq -ne ($index + 1)) { $sequenceValid = $false }
    }
    Complete-Check $sequenceValid @{ events = $events.Count; lastSequence = [long]$events[-1].seq }

    Begin-Check 'sseSingleTerminal'
    $terminalEvents = @($events | Where-Object { $_.type -in @('RUN_SUCCEEDED', 'RUN_DEGRADED', 'RUN_FAILED', 'RUN_CANCELED') })
    Complete-Check ($terminalEvents.Count -eq 1 -and $events[-1].type -eq 'RUN_SUCCEEDED' -and
        $events[0].type -eq 'RUN_STARTED') @{ terminals = $terminalEvents.Count }

    Begin-Check 'sseDeltaMatchesReply'
    $deltas = @($events | Where-Object { $_.type -eq 'DELTA' })
    $deltaReply = ($deltas | ForEach-Object { $_.data.text }) -join ''
    Complete-Check ($deltas.Count -gt 0 -and $deltaReply -ceq $reply) @{ deltas = $deltas.Count; deltaChars = $deltaReply.Length }

    Begin-Check 'sseResumeSuffix'
    $cursor = [long][Math]::Floor($events.Count / 2)
    $resumedEvents = @(Read-RunEvents $ids.chatRunId $cursor)
    $suffixValid = $resumedEvents.Count -eq ($events.Count - $cursor)
    for ($index = 0; $index -lt $resumedEvents.Count; $index++) {
        $expectedEvent = $events[[int]$cursor + $index]
        $resumedEvent = $resumedEvents[$index]
        if ($resumedEvent.seq -ne $expectedEvent.seq -or $resumedEvent.type -cne $expectedEvent.type -or
            $resumedEvent.nodeId -cne $expectedEvent.nodeId -or
            ($resumedEvent | ConvertTo-Json -Depth 30 -Compress) -cne ($expectedEvent | ConvertTo-Json -Depth 30 -Compress)) {
            $suffixValid = $false
        }
    }
    Complete-Check $suffixValid @{ cursor = $cursor; events = $resumedEvents.Count }

    Begin-Check 'sseMalformedCursor400'
    $badCursor = Invoke-LocalHttp 'GET' "/api/v1/runs/$($ids.chatRunId)/events" $null 400 @{ 'Last-Event-ID' = 'invalid' }
    Complete-Check ($badCursor.status -eq 400)

    Begin-Check 'sseFutureCursor400'
    $futureCursor = Invoke-LocalHttp 'GET' "/api/v1/runs/$($ids.chatRunId)/events" $null 400 @{
        'Last-Event-ID' = [string]([long]$events[-1].seq + 1)
    }
    Complete-Check ($futureCursor.status -eq 400)

    Begin-Check 'memoryStatistics'
    # 运行终态先落库，assistant 消息随后保存；等待计数收口，不能凭短暂竞态判失败。
    $memoryDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ($true) {
        $memory = Api 'GET' "/api/v1/sessions/$chatSession/memory" $null (Remaining-Milliseconds $memoryDeadline)
        if ($memory.totalMessages -eq 2) { break }
        Require-Value ($memory.totalMessages -lt 2) 'unexpected_memory_count'
        Start-Sleep -Milliseconds ([Math]::Min(150, (Remaining-Milliseconds $memoryDeadline)))
    }
    Complete-Check ($memory.enabled -eq $true -and $memory.summaryEnabled -eq $false -and
        $memory.totalMessages -eq 2 -and $memory.summarizedCount -eq 0) @{ messages = [int]$memory.totalMessages }

    Begin-Check 'memoryPreview'
    $previewInput = '合成本轮预览输入只出现一次'
    $preview = Api 'GET' ("/api/v1/sessions/$chatSession/memory/preview?input=" + [Uri]::EscapeDataString($previewInput))
    $previewMessages = @($preview.messages)
    $inputOccurrences = @($previewMessages | Where-Object { $_.content -ceq $previewInput }).Count
    Complete-Check ($preview.sessionId -eq $chatSession -and $preview.historyCount -eq 2 -and
        $preview.summaryIncluded -eq $false -and $preview.longMemoryIncluded -eq $false -and $preview.profileIncluded -eq $false -and
        $previewMessages.Count -eq 3 -and $inputOccurrences -eq 1 -and
        $previewMessages[0].role -eq 'user' -and $previewMessages[0].content -ceq $chatInput -and
        $previewMessages[1].role -eq 'assistant' -and $previewMessages[1].content -ceq $reply -and
        $previewMessages[2].role -eq 'user' -and $previewMessages[2].content -ceq $previewInput) @{
        historyMessages = [int]$preview.historyCount; assembledMessages = $previewMessages.Count; currentInputOccurrences = $inputOccurrences
    }

    $documentText = '合成蓝鲸计划报销需要行程单和发票。'
    Begin-Check 'ragDocumentIndexed'
    $document = Api 'POST' "/api/v1/kbs/$kbId/documents" @{
        title = $token + '-document'; content = $documentText; sourceType = 'TEXT'; createdBy = $ids.userId
    }
    Record-Id 'documentId' $document.documentId | Out-Null
    Complete-Check ($document.status -eq 'INDEXED' -and $document.chunkCount -eq 1 -and $document.duplicate -eq $false) @{
        chunks = [int]$document.chunkCount
    }

    Begin-Check 'ragChunkMetadata'
    $chunkMetadata = @(Api 'GET' "/api/v1/kbs/$kbId/documents/$($ids.documentId)/chunks")
    Complete-Check ($chunkMetadata.Count -eq 1 -and $chunkMetadata[0].embeddingModel -eq 'mock-embedding' -and
        $chunkMetadata[0].charCount -eq $documentText.Length -and $chunkMetadata[0].preview -ceq $documentText) @{
        chunks = $chunkMetadata.Count; documentChars = $documentText.Length
    }

    Begin-Check 'ragLocalCitationMap'
    $retrieved = Api 'POST' "/api/v1/kbs/$kbId/retrieve" @{
        query = $documentText; topK = 3; minScore = 0.5; sources = @('local'); includeContext = $true
    }
    $retrievedChunks = @($retrieved.chunks)
    $citations = @($retrieved.citations)
    Complete-Check ($retrievedChunks.Count -eq 1 -and $citations.Count -eq 1 -and
        $retrievedChunks[0].index -eq 1 -and $retrievedChunks[0].source -eq 'local' -and
        $retrievedChunks[0].docId -eq $ids.documentId -and $retrievedChunks[0].chunkId -eq $chunkMetadata[0].chunkId -and
        $retrievedChunks[0].citation -ceq $citations[0] -and [double]$retrievedChunks[0].score -ge 0.5 -and
        $retrieved.contextBlock.Contains('[1]') -and $retrieved.contextBlock.Contains('<<<资料1>>>')) @{
        chunks = $retrievedChunks.Count; citations = $citations.Count
    }

    Begin-Check 'ragEmptyNoCitation'
    $empty = Api 'POST' "/api/v1/kbs/$emptyKbId/retrieve" @{
        query = $documentText; topK = 3; minScore = 0.5; sources = @('local'); includeContext = $true
    }
    Complete-Check (@($empty.chunks).Count -eq 0 -and @($empty.citations).Count -eq 0 -and
        $empty.contextBlock.Contains('本次没有检索到可用资料')) @{ chunks = @($empty.chunks).Count; citations = @($empty.citations).Count }

    Begin-Check 'ragWorkflowSucceeded'
    $ragSession = New-Session $ragName 'ragSessionId'
    Submit-Message $ragSession ($token + '-rag-message') $documentText 'rag' | Out-Null
    $ragRun = Wait-Run $ids.ragRunId 'SUCCEEDED'
    Complete-Check ($ragRun.steps -eq 4 -and $null -eq $ragRun.error -and
        ([string]$ragRun.output.reply).StartsWith('（mock）')) @{ steps = [int]$ragRun.steps }

    Begin-Check 'ragWorkflowSources'
    $ragChunks = @($ragRun.output.chunks)
    $ragCitations = @($ragRun.output.citations)
    Complete-Check ($ragChunks.Count -eq 1 -and $ragCitations.Count -eq 1 -and $ragChunks[0].docId -eq $ids.documentId -and
        $ragChunks[0].source -eq 'local' -and $ragChunks[0].index -eq 1 -and $ragChunks[0].citation -ceq $ragCitations[0]) @{
        chunks = $ragChunks.Count; citations = $ragCitations.Count
    }

    $audioText = '合成音频演示'
    Begin-Check 'ttsResponseHeaders'
    $speech = Invoke-LocalHttp 'POST' '/api/v1/audio/speech' (Json-Content @{ text = $audioText; format = 'wav' })
    $audioDuration = 0L
    $durationValid = [long]::TryParse($speech.headers['X-Audio-Duration-Ms'], [ref]$audioDuration)
    Complete-Check ($speech.headers['Content-Type'] -eq 'audio/wav' -and $speech.headers['Cache-Control'] -eq 'no-store' -and
        $speech.headers['X-Audio-Provider'] -eq 'mock' -and $speech.headers['X-Audio-Model'] -eq 'mock-tts-1' -and
        $durationValid -and $audioDuration -eq ($audioText.Length * 20)) @{ durationMs = $audioDuration; bytes = $speech.bytes.Length }

    Begin-Check 'ttsPcmWav'
    [byte[]]$wav = $speech.bytes
    $wavValid = $wav.Length -ge 44
    if ($wavValid) {
        $wavValid = [Text.Encoding]::ASCII.GetString($wav, 0, 4) -ceq 'RIFF' -and
            [Text.Encoding]::ASCII.GetString($wav, 8, 4) -ceq 'WAVE' -and
            [Text.Encoding]::ASCII.GetString($wav, 12, 4) -ceq 'fmt ' -and
            [Text.Encoding]::ASCII.GetString($wav, 36, 4) -ceq 'data' -and
            [BitConverter]::ToUInt32($wav, 4) -eq ($wav.Length - 8) -and [BitConverter]::ToUInt32($wav, 16) -eq 16 -and
            [BitConverter]::ToUInt16($wav, 20) -eq 1 -and [BitConverter]::ToUInt16($wav, 22) -eq 1 -and
            [BitConverter]::ToUInt32($wav, 24) -eq 16000 -and [BitConverter]::ToUInt32($wav, 28) -eq 32000 -and
            [BitConverter]::ToUInt16($wav, 32) -eq 2 -and [BitConverter]::ToUInt16($wav, 34) -eq 16 -and
            [BitConverter]::ToUInt32($wav, 40) -eq ($wav.Length - 44) -and ($wav.Length - 44) -eq ($audioDuration * 32)
    }
    Complete-Check $wavValid @{ bytes = $wav.Length; sampleRate = 16000; channels = 1; bitsPerSample = 16 }

    Begin-Check 'asrMemoryRoundtrip'
    $multipart = [Net.Http.MultipartFormDataContent]::new()
    try {
        $audioPart = [Net.Http.ByteArrayContent]::new($wav)
        $audioPart.Headers.ContentType = [Net.Http.Headers.MediaTypeHeaderValue]::new('audio/wav')
        $multipart.Add($audioPart, 'file', 'synthetic.wav')
        $transcriptionRecord = Invoke-LocalHttp 'POST' '/api/v1/audio/transcriptions' $multipart
        $transcriptionEnvelope = [Text.Encoding]::UTF8.GetString($transcriptionRecord.bytes) | ConvertFrom-Json
        Require-Value ($transcriptionEnvelope.code -eq 0) 'invalid_api_envelope'
        $transcription = $transcriptionEnvelope.data
    } finally { $multipart.Dispose() }
    Complete-Check ($transcriptionRecord.headers['Cache-Control'] -eq 'no-store' -and
        $transcription.provider -eq 'mock' -and $transcription.model -eq 'mock-asr-1' -and
        $transcription.durationMs -eq $audioDuration -and
        $transcription.text -ceq ('（mock ASR）已收到音频，时长 ' + $audioDuration + ' ms。') -and
        $transcription.limits.sampleRate -eq 16000 -and $transcription.limits.channels -eq 1 -and
        $transcription.limits.bitsPerSample -eq 16 -and $transcription.limits.maxBytes -ge $wav.Length -and
        $transcription.limits.maxDurationMs -ge $audioDuration) @{ durationMs = $audioDuration; bytes = $wav.Length }

    Begin-Check 'audioWorkflowSucceeded'
    $audioSession = New-Session $audioName 'audioSessionId'
    Submit-Message $audioSession ($token + '-audio-message') $audioText 'audio' | Out-Null
    $audioRun = Wait-Run $ids.audioRunId 'SUCCEEDED'
    Complete-Check ($audioRun.steps -eq 4 -and $null -eq $audioRun.error -and
        $audioRun.output.reply -ceq $transcription.text) @{ steps = [int]$audioRun.steps }

    Begin-Check 'audioOutputHasNoPayload'
    $outputKeys = @($audioRun.output.PSObject.Properties | ForEach-Object { $_.Name })
    Complete-Check ($outputKeys.Count -eq 1 -and $outputKeys[0] -ceq 'reply') @{ outputFields = $outputKeys.Count }

    Begin-Check 'approvalFirstWaiting'
    $approvalSession = New-Session $approvalName 'approvalSessionId'
    Submit-Message $approvalSession ($token + '-approval-first') '合成第一份申请' 'approved' | Out-Null
    $firstWaiting = Wait-Run $ids.approvedRunId 'WAITING_APPROVAL'
    Complete-Check ($firstWaiting.status -eq 'WAITING_APPROVAL')

    Begin-Check 'approvalPendingTodo'
    $todos = Api 'GET' ("/api/v1/approvals?assignee=" + [Uri]::EscapeDataString($assignee))
    $firstTodos = @($todos.approvals | Where-Object { $_.runId -eq $ids.approvedRunId })
    Require-Value ($firstTodos.Count -eq 1) 'approval_todo_mismatch'
    Record-Id 'approvedApprovalId' $firstTodos[0].approvalId | Out-Null
    Complete-Check ($firstTodos[0].status -eq 'PENDING' -and $firstTodos[0].nodeId -eq 'approve') @{ matchingTodos = $firstTodos.Count }

    Begin-Check 'approvalWaitReleasesSessionLock'
    Submit-Message $approvalSession ($token + '-approval-second') '合成第二份申请' 'canceled' | Out-Null
    $secondWaiting = Wait-Run $ids.canceledRunId 'WAITING_APPROVAL'
    $firstStillWaiting = Api 'GET' "/api/v1/runs/$($ids.approvedRunId)"
    $secondChain = @(Api 'GET' "/api/v1/runs/$($ids.canceledRunId)/approvals")
    Require-Value ($secondChain.Count -eq 1) 'approval_chain_mismatch'
    Record-Id 'canceledApprovalId' $secondChain[0].approvalId | Out-Null
    Complete-Check ($secondWaiting.status -eq 'WAITING_APPROVAL' -and $firstStillWaiting.status -eq 'WAITING_APPROVAL' -and
        $secondChain[0].status -eq 'PENDING') @{ concurrentWaitingRuns = 2 }

    Begin-Check 'approvalResumedApproved'
    Api 'POST' "/api/v1/approvals/$($ids.approvedApprovalId)/decision" @{
        decision = 'APPROVE'; decidedBy = $assignee; comment = '合成批准'
    } | Out-Null
    $approvedRun = Wait-Run $ids.approvedRunId 'SUCCEEDED'
    $approvedChain = @(Api 'GET' "/api/v1/runs/$($ids.approvedRunId)/approvals")
    Complete-Check ($approvedRun.steps -eq 3 -and $null -eq $approvedRun.error -and
        $approvedRun.output.approvalStatus -eq 'APPROVED' -and $approvedRun.output.approvalBy -eq $assignee -and
        $approvedChain.Count -eq 1 -and $approvedChain[0].status -eq 'APPROVED') @{ steps = [int]$approvedRun.steps; approvals = $approvedChain.Count }

    Begin-Check 'approvalResumeHasNoReplay'
    $approvalEvents = @(Read-RunEvents $ids.approvedRunId)
    $noReplay = @($approvalEvents | Where-Object { $_.type -eq 'RUN_STARTED' }).Count -eq 1 -and
        @($approvalEvents | Where-Object { $_.type -eq 'RUN_SUCCEEDED' }).Count -eq 1 -and
        @($approvalEvents | Where-Object { $_.type -eq 'RUN_WAITING' }).Count -eq 1 -and
        @($approvalEvents | Where-Object { $_.type -eq 'NODE_SUSPENDED' -and $_.nodeId -eq 'approve' }).Count -eq 1
    foreach ($nodeId in @('start', 'approve', 'end')) {
        $nodeStarts = @($approvalEvents | Where-Object { $_.type -eq 'NODE_STARTED' -and $_.nodeId -eq $nodeId })
        $nodeSuccesses = @($approvalEvents | Where-Object { $_.type -eq 'NODE_SUCCEEDED' -and $_.nodeId -eq $nodeId })
        $noReplay = $noReplay -and $nodeStarts.Count -eq 1 -and $nodeSuccesses.Count -eq 1
        if ($nodeId -eq 'approve' -and $nodeSuccesses.Count -eq 1) {
            $noReplay = $noReplay -and $nodeSuccesses[0].data.resumed -eq $true
        }
    }
    Complete-Check ($noReplay -and $approvalEvents[-1].type -eq 'RUN_SUCCEEDED') @{ events = $approvalEvents.Count; expectedNodeStarts = 3 }

    Begin-Check 'approvalWaitingCanceled'
    $beforeCancel = Api 'GET' "/api/v1/runs/$($ids.canceledRunId)"
    Require-Value ($beforeCancel.status -eq 'WAITING_APPROVAL') 'cancel_requires_waiting'
    $cancel = Api 'POST' "/api/v1/runs/$($ids.canceledRunId)/cancel?actor=offline-demo"
    $canceledRun = Wait-Run $ids.canceledRunId 'CANCELED'
    Complete-Check ($cancel.status -in @('CANCELED', 'CANCEL_REQUESTED') -and $canceledRun.status -eq 'CANCELED')

    Begin-Check 'approvalCancelClosesTodo'
    $canceledChain = @(Api 'GET' "/api/v1/runs/$($ids.canceledRunId)/approvals")
    $todosAfter = Api 'GET' ("/api/v1/approvals?assignee=" + [Uri]::EscapeDataString($assignee))
    $leftovers = @($todosAfter.approvals | Where-Object { $_.runId -eq $ids.canceledRunId })
    Complete-Check ($canceledChain.Count -eq 1 -and $canceledChain[0].status -eq 'CANCELED' -and $leftovers.Count -eq 0) @{
        approvals = $canceledChain.Count; pendingTodos = $leftovers.Count
    }

    Begin-Check 'approvalCanceledDecision409'
    $lateDecision = Invoke-LocalHttp 'POST' "/api/v1/approvals/$($ids.canceledApprovalId)/decision" (Json-Content @{
        decision = 'APPROVE'; decidedBy = $assignee; comment = '合成取消后决策'
    }) 409
    Complete-Check ($lateDecision.status -eq 409)

    Begin-Check 'canceledRunTerminalEvent'
    $cancelEvents = @(Read-RunEvents $ids.canceledRunId)
    $cancelTerminals = @($cancelEvents | Where-Object { $_.type -in @('RUN_SUCCEEDED', 'RUN_DEGRADED', 'RUN_FAILED', 'RUN_CANCELED') })
    $finalCanceled = Api 'GET' "/api/v1/runs/$($ids.canceledRunId)"
    Complete-Check ($cancelTerminals.Count -eq 1 -and $cancelEvents[-1].type -eq 'RUN_CANCELED' -and
        $finalCanceled.status -eq 'CANCELED') @{ events = $cancelEvents.Count; terminals = $cancelTerminals.Count }

    Require-Value (@($checks.Values | Where-Object { $_.result -ne 'PASS' }).Count -eq 0) 'incomplete_checks'
    $exitCode = 0
} catch {
    if ($null -eq $script:FailureCode) {
        if ($_.Exception -is [TimeoutException] -or $_.Exception -is [OperationCanceledException]) {
            $script:FailureCode = 'request_timeout'
        } else { $script:FailureCode = 'runtime_failure' }
    }
    if ($null -ne $script:ActiveCheck) {
        $checks[$script:ActiveCheck].result = 'FAIL'
        $checks[$script:ActiveCheck].elapsedMs = [Math]::Round($script:CheckTimer.Elapsed.TotalMilliseconds, 1)
    }
    Write-Host ('FAIL: offline_demo (' + $script:FailureCode + ')')
} finally {
    if ($null -ne $client) { $client.Dispose() }
    elseif ($null -ne $handler) { $handler.Dispose() }
    $demoTimer.Stop()
    $report = [ordered]@{
        version = 1
        startedAt = $startedAt.ToString('o')
        elapsedMs = [Math]::Round($demoTimer.Elapsed.TotalMilliseconds, 1)
        result = $(if ($exitCode -eq 0) { 'PASS' } else { 'FAIL' })
        errorType = $script:FailureCode
        counts = @{
            total = $checkNames.Count
            passed = @($checks.Values | Where-Object { $_.result -eq 'PASS' }).Count
            failed = @($checks.Values | Where-Object { $_.result -eq 'FAIL' }).Count
            notRun = @($checks.Values | Where-Object { $_.result -eq 'NOT_RUN' }).Count
        }
        syntheticIds = $ids
        checks = @($checks.Values)
    }
    $reportStream = $null
    $writer = $null
    try {
        $reportStream = [IO.File]::Open($reportPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        $writer = [IO.StreamWriter]::new($reportStream, [Text.UTF8Encoding]::new($false))
        $writer.Write(($report | ConvertTo-Json -Depth 12))
        $writer.Flush()
    } catch {
        $exitCode = 1
        Write-Host 'FAIL: report_write_failed'
    } finally {
        if ($null -ne $writer) { $writer.Dispose() }
        elseif ($null -ne $reportStream) { $reportStream.Dispose() }
    }
}
Write-Host ('Checks: ' + $report.counts.passed + '/' + $report.counts.total + '; report: ' + $reportPath)
exit $exitCode
