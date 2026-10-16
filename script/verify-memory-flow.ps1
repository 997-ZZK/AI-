#  短期记忆的端到端验证（真实引擎 + mock 档位）：窗口注入 / 滚动摘要 / 三个接口。
#
#  跑之前引擎要以“记忆开着、摘要也开着”的方式活着，且把窗口与 chunk 调小（否则要聊很多轮才能触发摘要）：
#
#    $env:AIFLOW_MEMORY_ENABLED='true'
#    $env:AIFLOW_MEMORY_WINDOW_SIZE='4'
#    $env:AIFLOW_MEMORY_SUMMARY_ENABLED='true'
#    $env:AIFLOW_MEMORY_SUMMARY_CHUNK_SIZE='2'
#
#  钉住四条：
#    1) 空会话装配里没有历史，且当前输入只出现一次（不能把当前输入当历史）；
#    2) 第二轮开始，装配结果里真的带上了上一轮（而不是“没报错”）；
#    3) 攒够 chunk 后摘要落库（summarizedCount / tokens / model 都要对得上），且装配里多了一条 system 摘要；
#    4) rebuild 能把摘要清零（token 累计保留），未知会话返 404。
#
#  为什么能在真实栈上断言“模型到底看到了什么”：因为 /memory/preview 把**装配结果**摊开了（
#  没有它就只能断言“没报错”——那不算验证）。
#
#.NOTES
#  必须 UTF-8 带 BOM 保存：Windows PowerShell 5.1 会把无 BOM 的 UTF-8 当 GBK 解析，中文直接乱码报错。
#>
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$WorkflowName = "basic-chat",
    [int]$WindowSize = 4,
    [int]$SummaryChunk = 2,
    [string]$OutFile
)

$ErrorActionPreference = "Stop"
if (-not $OutFile) { $OutFile = "target/memory-flow-verify.json" }

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
        # 必须显式转 UTF-8 字节再发：Windows PowerShell 5.1 直接传字符串会按 ASCII 编码，中文全变 "?"
        # （真实栈第一次跑就踩了：三轮中文都变成 "???"，clientMsgId 因此撞车，被幂等去重成了同一轮）
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

$stamp = Get-Date -Format "HHmmss"
Write-Output "== 0/6 建用户与会话（工作流 $WorkflowName）"
$user = Api "POST" "/api/v1/users" @{ externalId = "memory-verify-$stamp"; displayName = "记忆验证用户" }
$session = Api "POST" "/api/v1/sessions" @{ userId = $user.id; title = "记忆验证 $stamp"; workflowName = $WorkflowName }
Write-Output ("  userId={0} sessionId={1}" -f $user.id, $session.id)

$script:turn = 0
function Send-Turn([string]$text) {
    # clientMsgId 是幂等键，必须每轮唯一：它撞了就会把新一轮当成重复提交（第一版脚本正是这么被骗过去的）
    $script:turn++
    $accepted = Api "POST" ("/api/v1/sessions/{0}/messages" -f $session.id) @{ content = $text; clientMsgId = "mem-$stamp-turn$($script:turn)" }
    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $deadline) {
        $run = Api "GET" ("/api/v1/runs/{0}" -f $accepted.runId) $null
        if ($run.status -in @("SUCCEEDED", "FAILED", "DEGRADED", "CANCELED")) { return $run }
        Start-Sleep -Milliseconds 200
    }
    throw "运行未在 30s 内收口：$($accepted.runId)"
}

Write-Output "== 1/6 空会话的装配（只有本轮输入，没有任何历史 / 摘要）"
$preview0 = Api "GET" ("/api/v1/sessions/{0}/memory/preview?input={1}" -f $session.id, [uri]::EscapeDataString("第一句")) $null
Check "emptySessionHasNoHistory" ($preview0.historyCount -eq 0) ("historyCount=" + $preview0.historyCount)
Check "emptySessionAssemblyIsJustCurrent" ($preview0.messages.Count -eq 1) ("messages=" + $preview0.messages.Count)
Check "firstTurnNoSummary" ($preview0.summaryIncluded -eq $false) ("summaryIncluded=" + $preview0.summaryIncluded)

$run1 = Send-Turn "第一句"
Check "firstRunSucceeded" ($run1.status -in @("SUCCEEDED", "DEGRADED")) $run1.status
$preview1 = Api "GET" ("/api/v1/sessions/{0}/memory/preview?input={1}" -f $session.id, [uri]::EscapeDataString("第二轮")) $null
Check "afterFirstTurnHistoryIsExactlyItsPair" ($preview1.historyCount -eq 2) ("historyCount=" + $preview1.historyCount)

Write-Output "== 2/6 第二轮对话（装配里必须带上第一轮的两条）"
$run2 = Send-Turn "第二句"
$preview2 = Api "GET" ("/api/v1/sessions/{0}/memory/preview?input={1}" -f $session.id, [uri]::EscapeDataString("第三轮")) $null
Check "secondTurnSeesFirstTurn" ($preview2.historyCount -eq 4) ("historyCount=" + $preview2.historyCount)
$roles2 = ($preview2.messages | ForEach-Object { $_.role }) -join ","
Check "secondTurnRolesAreUserAssistant" ($roles2 -eq "user,assistant,user,assistant,user") $roles2
$currentOccurrences = @($preview2.messages | Where-Object { $_.content -eq "第三轮" }).Count
Check "currentInputAppearsOnce" ($currentOccurrences -eq 1) ("occurrences=" + $currentOccurrences)

Write-Output "== 3/6 第三轮对话（6 条消息 -> 可折叠 2 条 = chunk，摘要必须落库）"
$run3 = Send-Turn "第三句"
# 摘要是运行收口后的旁路任务（不占会话队列），所以这里轮询到落库为止，而不是睡死一段固定时间
$memory = $null
$deadline = (Get-Date).AddSeconds(12)
while ((Get-Date) -lt $deadline) {
    $memory = Api "GET" ("/api/v1/sessions/{0}/memory" -f $session.id) $null
    if ($memory.summarizedCount -ge $SummaryChunk) { break }
    Start-Sleep -Milliseconds 300
}
Check "summaryFolded" ($memory.summarizedCount -eq $SummaryChunk) ("summarizedCount=" + $memory.summarizedCount + " / chunk=" + $SummaryChunk)
Check "summaryTokensCharged" ($memory.tokens -gt 0) ("tokens=" + $memory.tokens)
Check "summaryModelRecorded" (-not [string]::IsNullOrWhiteSpace($memory.model)) ("model=" + $memory.model)
Check "summaryBodyNonEmpty" ($memory.summaryChars -gt 0) ("summaryChars=" + $memory.summaryChars)
Check "windowIsHonoured" ($memory.windowSize -eq $WindowSize) ("windowSize=" + $memory.windowSize)

$preview3 = Api "GET" ("/api/v1/sessions/{0}/memory/preview?input={1}" -f $session.id, [uri]::EscapeDataString("第四轮")) $null
Check "previewIncludesSummary" ($preview3.summaryIncluded -eq $true) ("summaryIncluded=" + $preview3.summaryIncluded)
Check "previewMessagesAreSummaryPlusWindowPlusCurrent" ($preview3.messages.Count -eq ($WindowSize + 2)) ("messages=" + $preview3.messages.Count)
$summaryFirst = $preview3.messages[0].role -eq "system"
Check "summaryIsSystemMessage" $summaryFirst $preview3.messages[0].role

Write-Output "== 4/6 重建摘要（清摘要与计数，保留 token 累计）"
$rebuilt = Api "POST" ("/api/v1/sessions/{0}/memory/rebuild" -f $session.id) @{}
Check "rebuildClearsSummary" ($rebuilt.summarizedCount -eq 0 -and [string]::IsNullOrEmpty($rebuilt.summary)) ("summarizedCount=" + $rebuilt.summarizedCount)
Check "rebuildKeepsTokenCost" ($rebuilt.tokens -eq $memory.tokens) ("tokens=" + $rebuilt.tokens)

Write-Output "== 5/6 未知会话 -> 404（不能返回空状态假装正常）"
$notFound = $false
try {
    Api "GET" ("/api/v1/sessions/{0}/memory" -f [guid]::NewGuid()) $null | Out-Null
} catch {
    $notFound = $_.Exception.Message -match "404|不存在"
}
Check "unknownSessionIs404" $notFound "expect 404"

Write-Output "== 6/6 装配口径的回读（窗口条数上限）"
$preview4 = Api "GET" ("/api/v1/sessions/{0}/memory/preview?input={1}" -f $session.id, [uri]::EscapeDataString("第五轮")) $null
Check "windowNeverExceedsConfig" ($preview4.historyCount -le $WindowSize) ("historyCount=" + $preview4.historyCount)

$report = [ordered]@{
    at         = (Get-Date).ToString("s")
    baseUrl    = $BaseUrl
    sessionId  = $session.id
    runs       = @($run1.runId, $run2.runId, $run3.runId)
    memory     = $memory
    preview1   = $preview1
    preview2   = $preview2
    preview3   = $preview3
    checks     = $results
    failures   = $failures
}
$report | ConvertTo-Json -Depth 8 | Set-Content -Path $OutFile -Encoding UTF8

Write-Output ""
Write-Output "== 结果"
if ($failures.Count -eq 0) {
    Write-Output ("  全部通过（{0} 项）" -f $results.Count)
    Write-Output ("report -> {0}" -f $OutFile)
    Write-Output "PASS"
    exit 0
} else {
    Write-Output ("  失败：" + ($failures -join ", "))
    Write-Output ("report -> {0}" -f $OutFile)
    Write-Output "FAIL"
    exit 1
}
