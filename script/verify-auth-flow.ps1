<#
.SYNOPSIS
   鉴权最小切片的端到端验证（真实引擎 + 真令牌）：401 / 403 / 400 / 200 四种口径与令牌身份落库。

.DESCRIPTION
   跑之前引擎要以**开启鉴权**的方式活着（见 docs/auth-design.md）：

     $env:AIFLOW_AUTH_ENABLED='true'
     $env:AIFLOW_AUTH_TOKEN_REVIEWER_A='<令牌A>'      # 对应主体 id = -ReviewerId
     $env:AIFLOW_AUTH_TOKEN_REVIEWER_B='<令牌B>'

   脚本自己**不生成**令牌：令牌必须与引擎进程里的一致，所以从参数传进来，报告不记录值或前缀。

   钉住的四条：
     1) 匿名 -> 401（不是「静默放行」）；坏令牌 -> 401（不是「降级成匿名」）；
     2) 令牌有效但不在名单 -> 403，且审批**不消耗**（仍是 PENDING）；
     3) 自报 decidedBy 与令牌身份不一致 -> 400，且审批**不消耗**；
     4) 令牌身份 + body 省略 decidedBy -> 200，且落库 decidedBy = 令牌主体 id。

   取消同一条规则：匿名 cancel -> 401；带令牌 cancel 才会走到业务层（本脚本用「运行已结束」拿 409 证明确实过闸）。

.NOTES
   必须 UTF-8 带 BOM 保存：Windows PowerShell 5.1 会把无 BOM 的 UTF-8 当 GBK 解析，中文直接乱码报错。
#>
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$ReviewerId = "reviewer-auth",
    [string]$ReviewerToken = $env:AIFLOW_AUTH_TOKEN_REVIEWER_A,
    [string]$OutsiderToken = $env:AIFLOW_AUTH_TOKEN_REVIEWER_B,
    [int]$WaitSeconds = 600,
    [string]$OutFile
)

$ErrorActionPreference = "Stop"
if (-not $OutFile) { $OutFile = "target/auth-flow-verify.json" }
if (-not $ReviewerToken -or -not $OutsiderToken) {
    throw "必须传 -ReviewerToken / -OutsiderToken（或设同名环境变量）：令牌要与引擎进程里的一致"
}

function Call {
    param([string]$Method, [string]$Path, $Body = $null, [string]$Token)
    $uri = "$BaseUrl$Path"
    $headers = @{}
    if ($Token) { $headers["Authorization"] = "Bearer $Token" }
    try {
        if ($null -eq $Body) {
            $resp = Invoke-WebRequest -Method $Method -Uri $uri -Headers $headers -UseBasicParsing
        } else {
            $json = $Body | ConvertTo-Json -Depth 10 -Compress
            # 显式转 UTF-8 字节：PowerShell 5.1 传字符串会按 ASCII 编码，中文变 "?"
            $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
            $resp = Invoke-WebRequest -Method $Method -Uri $uri -Headers $headers -ContentType "application/json; charset=utf-8" -Body $bytes -UseBasicParsing
        }
        $bodyText = [System.Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
        return @{ status = [int]$resp.StatusCode; body = ($bodyText | ConvertFrom-Json); raw = $bodyText }
    } catch {
        $response = $_.Exception.Response
        if ($null -eq $response) { throw }
        # PowerShell 5.1 读错误流不稳定（同一次运行里有的 4xx 能读、有的读到空串），
        # 优先用 PowerShell 自己解析好的 ErrorDetails.Message，读流只做兜底。
        $text = $null
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $text = $_.ErrorDetails.Message }
        if (-not $text) {
            try {
                $reader = New-Object System.IO.StreamReader($response.GetResponseStream())
                $text = $reader.ReadToEnd()
            } catch {
                $text = $null
            }
        }
        $parsed = $null
        try { $parsed = $text | ConvertFrom-Json } catch { }
        return @{ status = [int]$response.StatusCode; body = $parsed; raw = $text }
    }
}

function Wait-Run {
    param([string]$RunId, [string]$Expected, [int]$TimeoutSec = 30)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $last = $null
    while ((Get-Date) -lt $deadline) {
        $resp = Call 'Get' "/api/v1/runs/$RunId"
        $last = $resp.body.data.status
        if ($last -eq $Expected) { return $resp.body.data }
        Start-Sleep -Milliseconds 250
    }
    throw "运行 $RunId 未在 ${TimeoutSec}s 内变为 $Expected（最后状态：$last）"
}

function Expect {
    param([string]$Name, $Result, [int]$Status, [string]$CodeLike)
    if ($Result.status -ne $Status) {
        throw "$Name 期望 $Status，实际 $($Result.status)（body: $($Result.raw)）"
    }
    if ($CodeLike -and "$($Result.body.code)" -ne $CodeLike) {
        throw "$Name 期望业务码 $CodeLike，实际 $($Result.body.code)（body: $($Result.raw)）"
    }
}

$suffix = [guid]::NewGuid().ToString("N").Substring(0, 8)
$workflowName = "e2e-auth-$suffix"

"== 1/8 匿名 whoami（应当承认「没认出我」而不是 401）"
$anon = Call 'Get' '/api/v1/auth/whoami'
if ($anon.status -ne 200) { throw "匿名 whoami 期望 200，实际 $($anon.status)" }
if (-not $anon.body.data.enabled) { throw "服务端没开鉴权（aiflow.auth.enabled=false）：先按文档起一个开鉴权的实例" }
if ($anon.body.data.authenticated) { throw "匿名请求被认成了已认证" }

"== 2/8 带令牌 whoami（应当认出主体）"
$me = Call 'Get' '/api/v1/auth/whoami' -Token $ReviewerToken
if ($me.status -ne 200 -or -not $me.body.data.authenticated) { throw "带令牌 whoami 未认证：$($me.raw)" }
$subjectId = $me.body.data.subject.id
if ($subjectId -ne $ReviewerId) { throw "令牌身份是 $subjectId，与 -ReviewerId=$ReviewerId 不一致（脚本按 id 配审批人名单）" }

"== 3/8 造一条待办（工作流 start -> approval -> end，审批人 = $ReviewerId）"
$definition = @{
    id      = $workflowName
    name    = $workflowName
    version = 1
    nodes   = @(
        @{ id = "start"; type = "start"; name = "开始" },
        @{ id = "approve"; type = "approval"; name = "人工审批"; params = @{
            title       = '鉴权用例：${input}'
            detail      = '提交人留言：${input}'
            assignees   = $ReviewerId
            waitSeconds = $WaitSeconds
            onTimeout   = "reject"
            maxLevel    = 1
            onExhausted = "reject"
            onReject    = "fallback"
            fallback    = "很抱歉，本次申请未通过审批。"
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
Call 'Post' '/api/v1/workflows' $definition | Out-Null
$user = Call 'Post' '/api/v1/users' @{ externalId = "e2e-auth-$suffix"; displayName = "鉴权端到端" }
$session = Call 'Post' '/api/v1/sessions' @{ userId = $user.body.data.id; title = "鉴权 e2e"; workflowName = $workflowName }
$sessionId = $session.body.data.id
$first = Call 'Post' "/api/v1/sessions/$sessionId/messages" @{ clientMsgId = "auth-e2e-1-$suffix"; content = "鉴权用例：购买两台显示器" }
$runId = $first.body.data.runId
Wait-Run $runId 'WAITING_APPROVAL' | Out-Null
$todos = Call 'Get' "/api/v1/approvals?assignee=$ReviewerId"
$todo = @($todos.body.data.approvals | Where-Object { $_.runId -eq $runId })
if ($todo.Count -ne 1) { throw "待办里没有 $runId 的审批单（count=$($todo.Count)）" }
$approvalId = $todo[0].approvalId

function Approval-Status {
    # 注意：详情接口是 data.approval.*（列表接口才是 data.approvals[]）—— 读错过一次，这里写死说明
    return (Call 'Get' "/api/v1/approvals/$approvalId").body.data.approval.status
}

"== 4/8 匿名决策 -> 401（审批必须原封不动）"
$r4 = Call 'Post' "/api/v1/approvals/$approvalId/decision" @{ decision = "APPROVE"; decidedBy = $ReviewerId }
Expect '匿名决策' $r4 401 '40100'
$s4 = Approval-Status

"== 5/8 坏令牌 -> 401（不是降级成匿名）"
$r5 = Call 'Post' "/api/v1/approvals/$approvalId/decision" @{ decision = "APPROVE" } -Token "definitely-not-a-token-$suffix"
Expect '坏令牌决策' $r5 401 '40100'
$s5 = Approval-Status

"== 6/8 令牌有效但不在名单 -> 403（且不消耗这次审批）"
$r6 = Call 'Post' "/api/v1/approvals/$approvalId/decision" @{ decision = "APPROVE" } -Token $OutsiderToken
Expect '名单外决策' $r6 403 '40300'
$s6 = Approval-Status

"== 7/8 自报 decidedBy 与令牌身份不一致 -> 400（且不消耗）"
$r7 = Call 'Post' "/api/v1/approvals/$approvalId/decision" @{ decision = "APPROVE"; decidedBy = "somebody-else" } -Token $ReviewerToken
Expect '自报冲突' $r7 400 '40000'
$s7 = Approval-Status

"== 8/8 令牌身份 + 省略 decidedBy -> 200，落库 decisionBy = 令牌主体"
$r8 = Call 'Post' "/api/v1/approvals/$approvalId/decision" @{ decision = "APPROVE"; comment = "鉴权端到端通过" } -Token $ReviewerToken
Expect '令牌身份决策' $r8 200 '0'
$finished = Wait-Run $runId 'SUCCEEDED'
$detail = Call 'Get' "/api/v1/approvals/$approvalId" -Token $ReviewerToken

"== 附加：取消的两条路（匿名 401；带令牌才进业务层）"
$anonCancel = Call 'Post' "/api/v1/runs/$runId/cancel"
Expect '匿名取消' $anonCancel 401 '40100'
$tokenCancel = Call 'Post' "/api/v1/runs/$runId/cancel" -Token $ReviewerToken
if ($tokenCancel.status -ne 409) {
    throw "带令牌取消已结束的运行期望 409（证明过闸），实际 $($tokenCancel.status)"
}

$report = [ordered]@{
    at             = (Get-Date).ToString("s")
    baseUrl        = $BaseUrl
    credentialsRecorded = $false
    workflowName   = $workflowName
    sessionId      = $sessionId
    runId          = $runId
    approvalId     = $approvalId
    subject        = [ordered]@{ whoamiAnonymous = $anon.body.data; whoamiWithToken = $me.body.data.subject }
    statuses       = [ordered]@{
        anonymousDecision   = $r4.status
        badTokenDecision    = $r5.status
        outsiderDecision    = $r6.status
        conflictDecision    = $r7.status
        tokenDecision       = $r8.status
        anonymousCancel     = $anonCancel.status
        tokenCancel         = $tokenCancel.status
    }
    approvalTrail  = [ordered]@{ after401 = $s4; afterBadToken = $s5; after403 = $s6; after400 = $s7; finalStatus = $detail.body.data.approval.status }
    persistedDecidedBy = $detail.body.data.approval.decidedBy
    finalRunStatus = $finished.status
    checks         = [ordered]@{
        anonymousIsNotAuthenticated = (-not $anon.body.data.authenticated)
        tokenResolvesToSubject      = ($subjectId -eq $ReviewerId)
        anonymousRejected           = ($r4.status -eq 401)
        badTokenRejected            = ($r5.status -eq 401)
        outsiderForbidden           = ($r6.status -eq 403)
        conflictRejected            = ($r7.status -eq 400)
        untouchedBeforeRealDecision = ($s4 -eq "PENDING" -and $s5 -eq "PENDING" -and $s6 -eq "PENDING" -and $s7 -eq "PENDING")
        identityWinsOverSelfReport  = ($r8.status -eq 200 -and $detail.body.data.approval.decidedBy -eq $ReviewerId)
        runResumed                  = ($finished.status -eq "SUCCEEDED")
        cancelNeedsIdentity         = ($anonCancel.status -eq 401 -and $tokenCancel.status -eq 409)
    }
}
$report | ConvertTo-Json -Depth 12 | Set-Content -Path $OutFile -Encoding UTF8

""
"== 结论"
$report.checks.GetEnumerator() | ForEach-Object { "   {0} = {1}" -f $_.Key, $_.Value }
"report -> $OutFile"
$failed = @($report.checks.GetEnumerator() | Where-Object { -not $_.Value })
if ($failed.Count -gt 0) { throw "有检查项未通过：$($failed.Key -join ', ')" }
"PASS"
