<#
  长期记忆的端到端验证（真实引擎 + 真实 PG + 真实 Redis）。

  跑之前引擎要活着，且 embedding 链可用（mock 档也能跑：本脚本的查询词与条目正文刻意词面重叠）。
  想连抽取一起验：服务端加 AIFLOW_LONG_MEMORY_EXTRACT_ENABLED=true，并带上 -ExpectExtraction
  （抽取是旁路任务，脚本会轮询等 -ExtractionWaitSeconds 秒，默认 20）。抽取要真的跑起来还得把节流调松，
  否则一个两句话的会话根本够不着门槛：AIFLOW_LONG_MEMORY_EXTRACT_MIN_MESSAGES=1、
  AIFLOW_LONG_MEMORY_EXTRACT_EVERY_MESSAGES=1、AIFLOW_LONG_MEMORY_EXTRACT_MIN_INTERVAL=1s。
  而且抽取走「优先便宜档」，默认便宜档是 local/mock —— 不改这一项它会落到 mock 上（回显提示词、解析不出 JSON），
  所以测试实例要把 cloud 也放进便宜档：
    $env:SPRING_APPLICATION_JSON = '{"aiflow":{"provider":{"cheap-tiers":["cloud","local","mock"]}}}'

  钉住六件事：
    1) 手工新增落库：source=MANUAL、confidence=1.00、embeddingFresh=true；
    2) 同 key 再写一次是**纠正**而不是新增第二行（人工优先的单赢家）；
    3) 召回预览给得出分数与注入判定（不是「没报错」）；
    4) **换一个会话**仍然召回得到（作用域是用户，不是会话）——这条是整切片的立项理由；
    5) 硬删之后列表与召回里都没有它；
    6) 禁写清单生效（手机号一类的手工写入直接 400）、未知用户 404。

  为什么能在真实栈上断言「模型到底看到了什么」：/memories/preview 把命中、分数、丢弃原因与
  最终注入块都摊开了（M4-1 的教训：没有这个出口就只能断言「没报错」）。

.NOTES
  必须 UTF-8 带 BOM 保存：Windows PowerShell 5.1 会把无 BOM 的 UTF-8 当 GBK 解析，中文直接乱码报错。
  请求体必须显式转 UTF-8 字节再发：否则 PS 5.1 按 ASCII 编码，中文全变 "?"（M4-1 踩过）。
#>
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$WorkflowName = "basic-chat",
    [string]$Query = "公司报销规则：报销要附行程单",
    [switch]$ExpectExtraction,
    [int]$ExtractionWaitSeconds = 20,
    [string]$OutFile
)

$ErrorActionPreference = "Stop"
if (-not $OutFile) { $OutFile = "target/long-memory-flow-verify.json" }

$results = [ordered]@{}
$failures = New-Object System.Collections.Generic.List[string]

function Check([string]$name, [bool]$ok, $detail) {
    $results[$name] = [ordered]@{ ok = $ok; detail = $detail }
    if (-not $ok) { $failures.Add($name) }
    $flag = if ($ok) { "PASS" } else { "FAIL" }
    Write-Output ("  [{0}] {1} = {2}" -f $flag, $name, ($detail | Out-String).Trim())
}

function Invoke-Json([string]$method, [string]$path, $body) {
    $uri = $BaseUrl + $path
    if ($null -eq $body) {
        $resp = Invoke-WebRequest -UseBasicParsing -Method $method -Uri $uri
    } else {
        $json = $body | ConvertTo-Json -Depth 6 -Compress
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
        $resp = Invoke-WebRequest -UseBasicParsing -Method $method -Uri $uri -ContentType "application/json; charset=utf-8" -Body $bytes
    }
    return ([System.Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray()) | ConvertFrom-Json)
}

function Api([string]$method, [string]$path, $body) {
    $json = Invoke-Json $method $path $body
    if ($json.code -ne 0) { throw ("接口失败 {0} {1}: {2} {3}" -f $method, $path, $json.code, $json.message) }
    return $json.data
}

function Api-Status([string]$method, [string]$path, $body) {
    # 返回 **HTTP 状态码**。上一版拿正则从异常消息里抠三位数字，抠到的是业务码（40000/40400）的
    # 前三位，护栏明明生效也可能报成别的数：改成读响应对象上的真实状态码。
    try {
        $json = Invoke-Json $method $path $body
    } catch {
        $response = $_.Exception.Response
        if ($null -eq $response) { throw }
        return [int]$response.StatusCode
    }
    if ($json.code -ne 0) {
        # HTTP 通了但业务码非 0（本项目两者一一对应：40000 -> 400）
        return [int]([math]::Floor($json.code / 100))
    }
    return 200
}

$stamp = Get-Date -Format "HHmmss"
Write-Output "== 0/6 建用户与会话（工作流 $WorkflowName）"
$user = Api "POST" "/api/v1/users" @{ externalId = "long-memory-verify-$stamp"; displayName = "长期记忆验证用户" }
$sessionA = Api "POST" "/api/v1/sessions" @{ userId = $user.id; title = "长期记忆-A $stamp"; workflowName = $WorkflowName }
Write-Output ("  userId={0} sessionA={1}" -f $user.id, $sessionA.id)

Write-Output "== 1/6 手工新增（人工录入：confidence 1.00、source MANUAL）"
$created = Api "POST" ("/api/v1/users/{0}/memories" -f $user.id) @{ kind = "FACT"; key = "公司报销规则"; value = "报销要附行程单和发票" }
Check "manualEntryIsCreated" ($created.source -eq "MANUAL") ("source=" + $created.source)
Check "manualConfidenceIsOne" ([math]::Abs([double]$created.confidence - 1.0) -lt 0.001) ("confidence=" + $created.confidence)
Check "manualEntryHasCurrentModelVector" ($created.embeddingFresh -eq $true) ("embeddingModel=" + $created.embeddingModel)

$corrected = Api "POST" ("/api/v1/users/{0}/memories" -f $user.id) @{ kind = "FACT"; key = "公司 报销规则"; value = "报销要附行程单、发票和审批截图" }
Check "sameKeyIsCorrectedNotDuplicated" ($corrected.id -eq $created.id) ("id=" + $corrected.id + " vs " + $created.id)
# 必须 @() 包一层：PowerShell 的函数输出会把**单元素数组**解包成标量，那个标量没有 .Count（第一版就少算成空）。
$listed = @(Api "GET" ("/api/v1/users/{0}/memories" -f $user.id) $null)
Check "userHasExactlyOneEntry" ($listed.Count -eq 1) ("count=" + $listed.Count)
Check "valueIsTheNewOne" ($listed[0].value -eq "报销要附行程单、发票和审批截图") ("value=" + $listed[0].value)

Write-Output "== 2/6 召回预览（分数 + 注入判定 + 丢弃原因）"
$previewA = Api "GET" ("/api/v1/users/{0}/memories/preview?input={1}" -f $user.id, [uri]::EscapeDataString($Query)) $null
Check "recallIsEnabled" ($previewA.injectionEnabled -eq $true) ("injectionEnabled=" + $previewA.injectionEnabled)
Check "recallHitsTheEntry" ($previewA.hits.Count -ge 1) ("hits=" + $previewA.hits.Count)
$injectedCount = @($previewA.hits | Where-Object { $_.injected }).Count
Check "recallInjectsTheEntry" ($injectedCount -ge 1) ("injected=" + $injectedCount + " threshold=" + $previewA.threshold)
Check "injectedBlockCarriesTheFact" ($previewA.injectedText -match "报销要附行程单") ("injectedText=" + $previewA.injectedText)

Write-Output "== 3/6 换一个会话仍然记得（作用域是用户，不是会话）"
$sessionB = Api "POST" "/api/v1/sessions" @{ userId = $user.id; title = "长期记忆-B $stamp"; workflowName = $WorkflowName }
$assembled = Api "GET" ("/api/v1/sessions/{0}/memory/preview?input={1}" -f $sessionB.id, [uri]::EscapeDataString($Query)) $null
$systemBlocks = @($assembled.messages | Where-Object { $_.role -eq "system" })
$longBlocks = @($systemBlocks | Where-Object { $_.content -match "长期记忆" })
Check "newSessionSeesLongMemorySystemBlock" ($longBlocks.Count -ge 1) ("systemBlocks=" + $systemBlocks.Count + " longMemoryBlocks=" + $longBlocks.Count)
Check "newSessionBlockHasNoHistoryNoise" ($assembled.historyCount -eq 0) ("historyCount=" + $assembled.historyCount)
Check "longMemoryPrecedesSummary" ($assembled.messages[0].role -eq "system" -and $assembled.messages[0].content -match "长期记忆") ("first=" + $assembled.messages[0].role)

Write-Output "== 4/6 真实跑一轮对话：长期记忆进入请求、运行照常成功"
$accepted = Api "POST" ("/api/v1/sessions/{0}/messages" -f $sessionB.id) @{ content = "报销要附什么材料？"; clientMsgId = "long-mem-$stamp-turn1" }
$deadline = (Get-Date).AddSeconds(30)
$run = $null
while ((Get-Date) -lt $deadline) {
    $run = Api "GET" ("/api/v1/runs/{0}" -f $accepted.runId) $null
    if ($run.status -in @("SUCCEEDED", "FAILED", "DEGRADED", "CANCELED")) { break }
    Start-Sleep -Milliseconds 200
}
Check "runSucceededWithLongMemoryInjected" ($run.status -in @("SUCCEEDED", "DEGRADED")) ("status=" + $run.status)
$afterRun = @(Api "GET" ("/api/v1/users/{0}/memories" -f $user.id) $null)
$hitEntry = $afterRun | Where-Object { $_.id -eq $created.id }
Check "recallCountedInHits" ([int]$hitEntry.hits -ge 1) ("hits=" + $hitEntry.hits + " lastUsedAt=" + $hitEntry.lastUsedAt)

Write-Output "== 5/6 硬删（列表与召回里都不该再有它）"
$deleted = Api "DELETE" ("/api/v1/users/{0}/memories/{1}" -f $user.id, $created.id) $null
Check "deleteAcknowledged" ($deleted.deleted -eq $true) ("deleted=" + $deleted.deleted)
$afterDelete = @(Api "GET" ("/api/v1/users/{0}/memories" -f $user.id) $null)
Check "listIsEmptyAfterDelete" ($afterDelete.Count -eq 0) ("count=" + $afterDelete.Count)
$previewAfterDelete = Api "GET" ("/api/v1/users/{0}/memories/preview?input={1}" -f $user.id, [uri]::EscapeDataString($Query)) $null
Check "previewNoLongerHits" ($previewAfterDelete.hits.Count -eq 0) ("hits=" + $previewAfterDelete.hits.Count)

Write-Output "== 6/6 护栏：禁写清单 / 未知用户 / 未知 kind"
$guardStatus = Api-Status "POST" ("/api/v1/users/{0}/memories" -f $user.id) @{ kind = "FACT"; key = "手机号"; value = "我的手机号是13800001111" }
Check "sensitiveManualWriteIsRejected" ($guardStatus -eq 400) ("http=" + $guardStatus)
$unknownUser = Api-Status "GET" ("/api/v1/users/{0}/memories" -f [guid]::NewGuid()) $null
Check "unknownUserIs404" ($unknownUser -eq 404) ("http=" + $unknownUser)
$badKind = Api-Status "GET" ("/api/v1/users/{0}/memories?kind=MOOD" -f $user.id) $null
Check "unknownKindIs400" ($badKind -eq 400) ("http=" + $badKind)

$extractionNote = "服务端未开抽取（默认）：本脚本不校验自动抽取"
if ($ExpectExtraction) {
    Write-Output ("== 附加：抽取能落条目吗？先发一句值得长期记住的话，再看是否出现 EXTRACTED 条目（最多等 {0}s）" -f $ExtractionWaitSeconds)
    # 这一句刻意是「模型一眼能抽成 PREFERENCE」的自我陈述：拿问句去试的话，
    # 模型返回空数组是**正确行为**，脚本会把「抽取正常」误判成失败。
    $factMessage = "另外记一下：我习惯用中文回答，而且希望你先给结论、再给理由。"
    $factAccepted = Api "POST" ("/api/v1/sessions/{0}/messages" -f $sessionB.id) @{ content = $factMessage; clientMsgId = "long-mem-$stamp-turn2" }
    $factDeadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $factDeadline) {
        $factRun = Api "GET" ("/api/v1/runs/{0}" -f $factAccepted.runId) $null
        if ($factRun.status -in @("SUCCEEDED", "FAILED", "DEGRADED", "CANCELED")) { break }
        Start-Sleep -Milliseconds 200
    }
    # 抽取挂在 RunCoordinator 的收尾旁路上，与 run 状态没有先后保证：必须轮询等，
    # 读一次就断言会把「还在写」误判成「没写」。
    $extractStarted = Get-Date
    $extractDeadline = $extractStarted.AddSeconds($ExtractionWaitSeconds)
    $extracted = @()
    while ($true) {
        $relisted = Api "GET" ("/api/v1/users/{0}/memories" -f $user.id) $null
        $extracted = @($relisted | Where-Object { $_.source -eq "EXTRACTED" })
        if ($extracted.Count -ge 1 -or (Get-Date) -ge $extractDeadline) { break }
        Start-Sleep -Milliseconds 500
    }
    $waited = [math]::Round(((Get-Date) - $extractStarted).TotalSeconds, 1)
    Check "extractionProducedEntries" ($extracted.Count -ge 1) ("extractedCount=" + $extracted.Count + " waited=" + $waited + "s")
    $extractionNote = "断言了抽取会写条目（依赖真实模型按 JSON 回复，n=" + $extracted.Count + "，等了 " + $waited + "s）"
}

$report = [ordered]@{
    at                 = (Get-Date).ToString("s")
    baseUrl            = $BaseUrl
    userId             = $user.id
    sessions           = @($sessionA.id, $sessionB.id)
    query              = $Query
    created            = $created
    previewA           = $previewA
    assembledInNewSess = $assembled
    run                = $run
    extractionNote     = $extractionNote
    checks             = $results
    failures           = $failures
}
$report | ConvertTo-Json -Depth 8 | Set-Content -Path $OutFile -Encoding UTF8

Write-Output ""
Write-Output "== 结果"
if ($failures.Count -eq 0) {
    Write-Output ("  全部通过（{0} 项）" -f $results.Count)
    Write-Output ("  " + $extractionNote)
    Write-Output ("report -> {0}" -f $OutFile)
    Write-Output "PASS"
    exit 0
} else {
    Write-Output ("  失败：" + ($failures -join ", "))
    Write-Output ("report -> {0}" -f $OutFile)
    Write-Output "FAIL"
    exit 1
}
