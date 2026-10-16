<#
  M4-3B 用户画像真实栈验收（真实 PG + Redis，引擎必须以 mock chat/embedding 启动）。

  覆盖：GET 不建行/默认关闭、启用与时区、运行统计、显式偏好预览、工作流禁用、
  版本冲突、非法时区、软重置停用与长期记忆保留、再次启用的新统计起点。
  本脚本只使用合成用户，不删除用户、运行或长期记忆条目；报告只保存合成 ID 与断言。
#>
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$WorkflowName = "basic-chat",
    [string]$PostgresContainer = "aiflow-postgres",
    [string]$Database = "aiflow",
    [string]$DatabaseUser = "aiflow",
    [string]$OutFile = "target/m4-3b/profile-flow-verify.json"
)

$ErrorActionPreference = "Stop"
$results = [ordered]@{}
$failures = New-Object System.Collections.Generic.List[string]
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"

function Check([string]$Name, [bool]$Ok, $Detail) {
    $results[$Name] = [ordered]@{ ok = $Ok; detail = $Detail }
    if (-not $Ok) { $failures.Add($Name) }
    $flag = if ($Ok) { "  [PASS] " } else { "  [FAIL] " }
    Write-Output ($flag + $Name + " = " + ($Detail | Out-String).Trim())
}

function Invoke-Json([string]$Method, [string]$Path, $Body) {
    $uri = $BaseUrl + $Path
    try {
        if ($null -eq $Body) {
            $response = Invoke-WebRequest -UseBasicParsing -Method $Method -Uri $uri
        } else {
            $json = $Body | ConvertTo-Json -Depth 12 -Compress
            $bytes = [Text.Encoding]::UTF8.GetBytes($json)
            $response = Invoke-WebRequest -UseBasicParsing -Method $Method -Uri $uri -ContentType "application/json; charset=utf-8" -Body $bytes
        }
        $text = [Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())
        return [pscustomobject]@{ status = [int]$response.StatusCode; json = ($text | ConvertFrom-Json) }
    } catch {
        $response = $_.Exception.Response
        if ($null -eq $response) { throw }
        # PS 7 是 HttpResponseMessage，PS 5.1 是 HttpWebResponse；断言只需要共同的 StatusCode。
        return [pscustomobject]@{ status = [int]$response.StatusCode; json = $null }
    }
}

function Api([string]$Method, [string]$Path, $Body) {
    $response = Invoke-Json $Method $Path $Body
    if ($response.status -lt 200 -or $response.status -ge 300 -or $response.json.code -ne 0) {
        throw ("接口失败 {0} {1}: HTTP {2}, code {3}, {4}" -f $Method, $Path, $response.status, $response.json.code, $response.json.message)
    }
    return $response.json.data
}

function Api-Status([string]$Method, [string]$Path, $Body) {
    return (Invoke-Json $Method $Path $Body).status
}

function Sql([string]$Query) {
    if (-not $Query.TrimStart().StartsWith("select ", [StringComparison]::OrdinalIgnoreCase)) { throw "Only SELECT is allowed" }
    $lines = @(& docker exec $PostgresContainer psql -X -U $DatabaseUser -d $Database -At -F '|' -c $Query)
    if ($LASTEXITCODE -ne 0) { throw "read-only PostgreSQL assertion failed" }
    return @($lines | Where-Object { $_ -ne "" })
}

function Wait-Run([string]$RunId) {
    $deadline = (Get-Date).AddSeconds(40)
    $last = $null
    while ((Get-Date) -lt $deadline) {
        $last = Api "GET" ("/api/v1/runs/{0}" -f $RunId) $null
        if ($last.status -in @("SUCCEEDED", "DEGRADED", "FAILED", "CANCELED")) { return $last }
        Start-Sleep -Milliseconds 200
    }
    throw "运行未在 40 秒内收口：$RunId（last=$($last.status)）"
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutFile) | Out-Null
$capabilities = Api "GET" "/api/v1/capabilities" $null
$chat = @($capabilities.channels | Where-Object { $_.channel -eq "chat" })[0]
$realChatCandidates = @($chat.candidates | Where-Object { $_.available -and $_.id -ne "mock" })
if ($chat.activeProvider -ne "mock" -or $realChatCandidates.Count -gt 0) { throw "画像验收仅允许 mock chat，请使用 mock 验证实例" }
Check "onlyMockChatIsAvailable" ($chat.activeProvider -eq "mock" -and $realChatCandidates.Count -eq 0) $chat.activeProvider
Write-Output "== 0/9 合成用户与默认画像（$WorkflowName）"
$user = Api "POST" "/api/v1/users" @{ externalId = "profile-verify-$stamp"; displayName = "画像验证用户" }
$before = Api "GET" ("/api/v1/users/{0}/profile" -f $user.id) $null
$profileRowsBefore = @(Sql ("select user_id from user_profiles where user_id='{0}'" -f $user.id))
Check "getDoesNotCreateProfileRow" ($profileRowsBefore.Count -eq 0) ("rows=" + $profileRowsBefore.Count)
Check "profileDefaultsDisabled" (-not $before.aggregationEnabled -and $before.version -eq 0 -and $before.timezone -eq "UTC") $before

Write-Output "== 1/9 启用画像与时区"
$enabled = Api "PUT" ("/api/v1/users/{0}/profile" -f $user.id) @{ aggregationEnabled = $true; timezone = "Asia/Shanghai"; expectedVersion = 0 }
$firstSince = [DateTime]::Parse($enabled.statisticsSince)
Check "enableStartsStatistics" ($enabled.aggregationEnabled -and $enabled.version -eq 1 -and $enabled.timezone -eq "Asia/Shanghai") $enabled
Check "freshProfileHasEmptyStatistics" ($enabled.statistics.runCount -eq 0) $enabled.statistics

Write-Output "== 2/9 真实 mock 对话产生运行统计"
$session = Api "POST" "/api/v1/sessions" @{ userId = $user.id; title = "画像验证 $stamp"; workflowName = $WorkflowName }
$accepted = Api "POST" ("/api/v1/sessions/{0}/messages" -f $session.id) @{ clientMsgId = "profile-$stamp-turn1"; content = "画像统计验证" }
$run = Wait-Run $accepted.runId
Check "mockRunSucceeds" ($run.status -in @("SUCCEEDED", "DEGRADED")) $run.status
$afterRun = Api "GET" ("/api/v1/users/{0}/profile" -f $user.id) $null
Check "runCountAndWorkflowAreReported" ($afterRun.statistics.runCount -eq 1 -and @($afterRun.statistics.workflows).Count -eq 1 -and $afterRun.statistics.workflows[0].count -eq 1) $afterRun.statistics
$pgHour = [int]@(Sql ("select extract(hour from created_at at time zone 'Asia/Shanghai')::int from workflow_runs where id='{0}'" -f $accepted.runId))[0]
$hourTotal = ($afterRun.statistics.hours | Measure-Object -Sum).Sum
Check "localHourUsesProfileTimezone" (@($afterRun.statistics.hours).Count -eq 24 -and $hourTotal -eq 1 -and $afterRun.statistics.hours[$pgHour] -eq 1) "localHour=$pgHour total=$hourTotal"
$pgTokens = [long]@(Sql ("select coalesce(sum((output->>'tokensUsed')::bigint),0) from node_runs where run_id='{0}' and node_type in ('llm.chat','llm.vision')" -f $accepted.runId))[0]
Check "recordedMockTokensMatchPostgres" ($afterRun.statistics.tokens.mockTokens -eq $pgTokens -and $pgTokens -gt 0 -and $afterRun.statistics.tokens.realTokens -eq 0 -and $afterRun.statistics.tokens.unknownNodes -eq 0) $afterRun.statistics.tokens

Write-Output "== 3/9 显式偏好与共用预览"
$manualStyle = Api "POST" ("/api/v1/users/{0}/memories" -f $user.id) @{ kind = "PREFERENCE"; key = "response-style"; value = "detailed" }
$manualPolicy = Api "POST" ("/api/v1/users/{0}/memories" -f $user.id) @{ kind = "PREFERENCE"; key = "model-policy"; value = "cheap-first" }
$preview = Api "GET" ("/api/v1/users/{0}/profile/preview?profilePreference=inherit" -f $user.id) $null
Check "manualResponseStyleIsApplied" ($preview.profileEnabled -and $preview.responseStyle -eq "detailed" -and @($preview.preferences | Where-Object { $_.key -eq "response-style" -and $_.applied }).Count -eq 1) $preview
Check "manualCheapFirstIsApplied" ($preview.modelPolicy -eq "cheap-first" -and $preview.preferCheapTiers) $preview
$disabledPreview = Api "GET" ("/api/v1/users/{0}/profile/preview?profilePreference=none" -f $user.id) $null
Check "workflowCanDisableProfilePreference" (-not $disabledPreview.workflowAllowed -and -not $disabledPreview.preferCheapTiers) $disabledPreview

Write-Output "== 4/9 版本与输入护栏"
$oldVersionStatus = Api-Status "PUT" ("/api/v1/users/{0}/profile" -f $user.id) @{ aggregationEnabled = $true; timezone = "UTC"; expectedVersion = 0 }
Check "staleVersionIsConflict" ($oldVersionStatus -eq 409) ("http=" + $oldVersionStatus)
$badZoneStatus = Api-Status "PUT" ("/api/v1/users/{0}/profile" -f $user.id) @{ aggregationEnabled = $true; timezone = "Not/AZone"; expectedVersion = $afterRun.version }
Check "invalidTimezoneIsBadRequest" ($badZoneStatus -eq 400) ("http=" + $badZoneStatus)

Write-Output "== 5/9 软重置：停用统计，保留源记录与长期记忆"
$reset = Api "DELETE" ("/api/v1/users/{0}/profile?expectedVersion={1}" -f $user.id, $afterRun.version) $null
Check "resetDisablesAndIncrementsVersion" (-not $reset.aggregationEnabled -and $reset.version -eq ($afterRun.version + 1)) $reset
$resetSince = [DateTime]::Parse($reset.statisticsSince)
Check "resetMovesStatisticsStart" ($resetSince -ge $firstSince) ("first=$firstSince reset=$resetSince")
Check "resetReturnsEmptyStatistics" ($reset.statistics.runCount -eq 0 -and @($reset.statistics.workflows).Count -eq 0) $reset.statistics
$memoryRows = @(Api "GET" ("/api/v1/users/{0}/memories" -f $user.id) $null)
Check "resetKeepsLongMemoryPreferences" ($memoryRows.Count -ge 2) ("memoryCount=" + $memoryRows.Count)
$stoppedPreview = Api "GET" ("/api/v1/users/{0}/profile/preview" -f $user.id) $null
Check "disabledProfileDoesNotApplyPreferences" (-not $stoppedPreview.profileEnabled -and -not $stoppedPreview.preferCheapTiers) $stoppedPreview

Write-Output "== 6/9 再启用从新起点统计"
$reEnabled = Api "PUT" ("/api/v1/users/{0}/profile" -f $user.id) @{ aggregationEnabled = $true; timezone = "UTC"; expectedVersion = $reset.version }
$secondSince = [DateTime]::Parse($reEnabled.statisticsSince)
Check "reenableStartsNewStatisticsWindow" ($reEnabled.aggregationEnabled -and $reEnabled.version -eq ($reset.version + 1) -and $secondSince -ge $resetSince) $reEnabled
Check "oldRunIsOutsideFreshWindow" ($reEnabled.statistics.runCount -eq 0) $reEnabled.statistics

Write-Output "== 7/9 记忆装配预览带画像标签字段"
$sessionPreview = Api "GET" ("/api/v1/sessions/{0}/memory/preview?input={1}" -f $session.id, [uri]::EscapeDataString("画像装配回读")) $null
Check "memoryPreviewExposesProfileIncluded" ($null -ne $sessionPreview.profileIncluded -and $sessionPreview.profileIncluded -eq $true) $sessionPreview.profileIncluded

Write-Output "== 8/9 记录工具审计覆盖边界"
$coverage = $afterRun.toolCoverage
Check "toolCoverageDeclaresCompletenessBoundary" ($null -ne $coverage.mayBeIncomplete -and $coverage.counterScope -eq "current-instance") $coverage

$report = [ordered]@{ at=(Get-Date).ToString("s"); baseUrl=$BaseUrl; userId=$user.id; sessionId=$session.id; runId=$accepted.runId; checks=$results; failures=$failures }
$report | ConvertTo-Json -Depth 12 | Set-Content -Path $OutFile -Encoding UTF8
Write-Output ""
if ($failures.Count -eq 0) { Write-Output ("全部通过（{0} 项）`nreport -> {1}`nPASS" -f $results.Count, $OutFile); exit 0 }
Write-Output ("失败：{0}`nreport -> {1}`nFAIL" -f ($failures -join ", "), $OutFile); exit 1
