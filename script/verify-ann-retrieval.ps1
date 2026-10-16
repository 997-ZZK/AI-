<#
.SYNOPSIS
   ANN 与精确检索路径的对照验证：同一 kb、同一批查询，两次启动各跑一遍，逐字段比对结果。

.DESCRIPTION
   为什么要两次启动：ann.enabled 是启动期读的配置（装配时决定 provider 走哪条路径），
   运行期改不了。所以对照流程是：
     1) 用 AIFLOW_ANN_ENABLED=true  起引擎，跑本脚本 -Label on
     2) 停机，用 AIFLOW_ANN_ENABLED=false 起引擎，跑本脚本 -Label off
     3) 比对两个 JSON：chunkIds 与 rawScore 应逐条一致（同一批向量、同一种子，mock embedding 是确定性的）。
   不一致就是 ANN 召回掉了东西，先调大 ann.ef-search / ann.candidates 再复测。

.NOTES
   必须 UTF-8 带 BOM 保存：Windows PowerShell 5.1 会把无 BOM 的 UTF-8 当 GBK 解析，中文直接乱码报错。
#>
param(
    [Parameter(Mandatory = $true)][string]$Label,
    [string]$BaseUrl = "http://localhost:8080",
    [string]$KbId = "e2e-ann",
    [string]$OutFile,
    [double]$MinScore = 0,
    [int]$TopK = 8
)

$ErrorActionPreference = "Stop"
if (-not $OutFile) { $OutFile = "target/ann-retrieval-$Label.json" }

$queries = @(
    "发票要求是什么",
    "差旅住宿标准是多少",
    "年假有多少天",
    "接口鉴权怎么配置",
    "服务起不来怎么排查"
)

$rows = @()
foreach ($q in $queries) {
    $body = @{ query = $q; topK = $TopK; minScore = $MinScore; sources = @("local"); includeContext = $false } | ConvertTo-Json -Compress
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $resp = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/kbs/$KbId/retrieve" -ContentType "application/json" -Body $body
    $sw.Stop()
    $chunks = @($resp.data.chunks | ForEach-Object {
        [ordered]@{ chunkId = $_.chunkId; docTitle = $_.docTitle; rawScore = $_.rawScore; score = $_.score }
    })
    $rows += [ordered]@{
        query     = $q
        elapsedMs = [math]::Round($sw.Elapsed.TotalMilliseconds, 1)
        chunkIds  = @($chunks | ForEach-Object { $_.chunkId })
        chunks    = $chunks
        stats     = $resp.data.stats
    }
}

[ordered]@{ label = $Label; kbId = $KbId; minScore = $MinScore; topK = $TopK; at = (Get-Date).ToString("s"); results = $rows } |
    ConvertTo-Json -Depth 8 | Set-Content -Path $OutFile -Encoding UTF8
"wrote $OutFile"
