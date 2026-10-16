<#
.SYNOPSIS
   人工审批的端到端验证（真实 PG + 真引擎）：提交 -> 挂起 -> 待办 -> 决策 -> 恢复 -> 终态。

.DESCRIPTION
   跑之前引擎要在 -BaseUrl 上活着（默认 8080；想把验证跑在独立实例上就换端口，别踩掉正在用的那个）。

   脚本除了走通主链路，还钉住两条容易退化的语义：
     1) 挂起期间**不持会话锁**：同会话再发一条消息，它必须能自己跑到「等待审批」——
        如果恢复逻辑把锁扣住了，第二条会永远停在 PENDING，这一步就会超时失败；
     2) 取消挂起中的运行：待办要一起收口成 CANCELED，且之后再决策拿 409。

   工作流不挂 llm 节点（start -> approval -> end）：这样 mock / 云端任何 provider 档位下都能跑。

.NOTES
   必须 UTF-8 带 BOM 保存：Windows PowerShell 5.1 会把无 BOM 的 UTF-8 当 GBK 解析，中文直接乱码报错。
#>
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Assignee = "reviewer-e2e",
    [int]$WaitSeconds = 600,
    [string]$OutFile
)

$ErrorActionPreference = "Stop"
if (-not $OutFile) { $OutFile = "target/approval-flow-verify.json" }

function Api {
    param([string]$Method, [string]$Path, $Body = $null)
    $uri = "$BaseUrl$Path"
    if ($null -eq $Body) {
        return Invoke-RestMethod -Method $Method -Uri $uri
    }
    $json = $Body | ConvertTo-Json -Depth 10 -Compress
    # 必须显式转 UTF-8 字节再发：Windows PowerShell 5.1 直接传字符串会按 ASCII 编码，中文全变 "?"
    # （症状：引擎里存的审批标题是 "????:E2E ????"，通知投出去也是问号）
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    return Invoke-RestMethod -Method $Method -Uri $uri -ContentType "application/json; charset=utf-8" -Body $bytes
}

function Wait-Run {
    param([string]$RunId, [string]$Expected, [int]$TimeoutSec = 30)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $last = $null
    while ((Get-Date) -lt $deadline) {
        $resp = Api 'Get' "/api/v1/runs/$RunId"
        $last = $resp.data.status
        if ($last -eq $Expected) { return $resp.data }
        Start-Sleep -Milliseconds 250
    }
    throw "运行 $RunId 未在 ${TimeoutSec}s 内变为 $Expected（最后状态：$last）"
}

$runId = [guid]::NewGuid().ToString("N").Substring(0, 8)
$workflowName = "e2e-approval-$runId"

"== 1/7 注册工作流 $workflowName"
$definition = @{
    id      = $workflowName
    name    = $workflowName
    version = 1
    nodes   = @(
        @{ id = "start"; type = "start"; name = "开始" },
        @{ id = "approve"; type = "approval"; name = "人工审批"; params = @{
            title        = '审批申请：${input}'
            detail       = '提交人留言：${input}'
            assignees    = $Assignee
            waitSeconds  = $WaitSeconds
            onTimeout    = "reject"
            maxLevel     = 1
            onExhausted  = "reject"
            onReject     = "fallback"
            fallback     = "很抱歉，本次申请未通过审批。"
        } },
        @{ id = "end"; type = "end"; name = "结束"; params = @{
            outputs = @{ reply = "reply"; fallback = "fallback"; approvalStatus = "approvalStatus"; approvalBy = "approvalBy" }
        } }
    )
    edges   = @(
        @{ from = "start"; to = "approve" },
        @{ from = "approve"; to = "end" }
    )
}
Api 'Post' '/api/v1/workflows' $definition | Out-Null

"== 2/7 建用户/会话并提交第一条消息"
$user = Api 'Post' '/api/v1/users' @{ externalId = "e2e-approval-$runId"; displayName = "审批端到端" }
$session = Api 'Post' '/api/v1/sessions' @{ userId = $user.data.id; title = "审批 e2e"; workflowName = $workflowName }
$sessionId = $session.data.id
$first = Api 'Post' "/api/v1/sessions/$sessionId/messages" @{ clientMsgId = "approval-e2e-1-$runId"; content = "E2E 审批申请：购买两台显示器" }
$run1 = $first.data.runId

"== 3/7 等运行挂起（WAITING_APPROVAL） run=$run1"
$waiting = Wait-Run $run1 'WAITING_APPROVAL'
$waitingSince = Get-Date

"== 4/7 待办列表里能看到它"
$todos = Api 'Get' "/api/v1/approvals?assignee=$Assignee"
$todo = @($todos.data.approvals | Where-Object { $_.runId -eq $run1 })
if ($todo.Count -ne 1) { throw "待办里没有 $run1 的审批单（count=$($todo.Count)）" }
$approvalId = $todo[0].approvalId

"== 5/7 挂起期间同会话第二条消息必须能自己跑到挂起（不持锁的证据）"
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$second = Api 'Post' "/api/v1/sessions/$sessionId/messages" @{ clientMsgId = "approval-e2e-2-$runId"; content = "E2E 第二条：审批期间提交的申请" }
$run2 = $second.data.runId
Wait-Run $run2 'WAITING_APPROVAL' | Out-Null
$sw.Stop()
$secondRunElapsedMs = [math]::Round($sw.Elapsed.TotalMilliseconds, 1)

"== 6/7 决策 APPROVE 并等第一条恢复完成"
$decision = Api 'Post' "/api/v1/approvals/$approvalId/decision" @{ decision = "APPROVE"; decidedBy = $Assignee; comment = "E2E 审批通过" }
$finished = Wait-Run $run1 'SUCCEEDED'
if ($finished.output.approvalStatus -ne "APPROVED") {
    throw "终态输出里 approvalStatus=$($finished.output.approvalStatus)，期望 APPROVED"
}
$chain = Api 'Get' "/api/v1/runs/$run1/approvals"

"== 7/7 取消仍在挂起的第二条（待办要一起收口）"
$cancel = Api 'Post' "/api/v1/runs/$run2/cancel?actor=verify-approval-flow"
$canceled = Wait-Run $run2 'CANCELED'
$todoAfterCancel = Api 'Get' "/api/v1/approvals?assignee=$Assignee"
$leftover = @($todoAfterCancel.data.approvals | Where-Object { $_.runId -eq $run2 })
if ($leftover.Count -ne 0) { throw "取消之后第二单的待办还在（count=$($leftover.Count)）" }

$detail = Api 'Get' "/api/v1/approvals/$approvalId"
$report = [ordered]@{
    at              = (Get-Date).ToString("s")
    baseUrl         = $BaseUrl
    workflowName    = $workflowName
    sessionId       = $sessionId
    assignee        = $Assignee
    approvedRun     = [ordered]@{
        runId        = $run1
        waitingAt    = $waitingSince.ToString("s")
        finalStatus  = $finished.status
        output       = $finished.output
        chain        = @($chain.data | ForEach-Object { [ordered]@{ level = $_.level; status = $_.status; decidedBy = $_.decidedBy } })
        events       = @($detail.data.events | ForEach-Object { $_.event })
        decision     = $decision.data.status
    }
    secondRun       = [ordered]@{
        runId        = $run2
        reachedSuspensionMs = $secondRunElapsedMs
        canceledAs   = $canceled.status
        cancelCall   = $cancel.data.status
    }
    checks          = [ordered]@{
        suspended        = ($waiting.status -eq "WAITING_APPROVAL")
        noSessionLock    = ($secondRunElapsedMs -lt ($WaitSeconds * 1000))
        resumedApproved  = ($finished.status -eq "SUCCEEDED" -and $finished.output.approvalStatus -eq "APPROVED")
        cancelClosesTodo = ($leftover.Count -eq 0)
    }
}
$report | ConvertTo-Json -Depth 12 | Set-Content -Path $OutFile -Encoding UTF8

""
"== 结论"
$report.checks.GetEnumerator() | ForEach-Object { "   {0} = {1}" -f $_.Key, $_.Value }
"report -> $OutFile"
if (-not $report.checks.suspended -or -not $report.checks.noSessionLock -or -not $report.checks.resumedApproved -or -not $report.checks.cancelClosesTodo) {
    throw "有检查项未通过，见 $OutFile"
}
"PASS"
