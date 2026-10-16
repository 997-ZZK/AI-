<#
.SYNOPSIS
   真实向量档（云端 1024 维 embedding）的端到端验证：合成语料 -> 语义改写查询 -> 命中检查。

.DESCRIPTION
   为什么要这份合成语料：验证「换真实 embedding 之后同义改写能召回」需要一份**答案已知**的小语料，
   而且要能用改写得面目全非的查询去问（与原文没有字面重叠，mock 的哈希向量必然召不回）。
   语料是脚本自带的合成文本，不含任何用户数据 —— 跑它只会把这几句话发给 embedding 服务。

   自检方式：每个查询都写死期望命中的文档标题，排在第一位才算通过（不是「能返回东西」就算过）。

.NOTES
   必须 UTF-8 带 BOM 保存：Windows PowerShell 5.1 会把无 BOM 的 UTF-8 当 GBK 解析，中文直接乱码报错。
#>
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$KbId = "e2e-realvec",
    [double]$MinScore = 0,
    [int]$TopK = 3,
    [string]$OutFile
)

$ErrorActionPreference = "Stop"

$documents = @(
    @{ title = "差旅报销标准.md"; content = "# 差旅报销标准`n`n住宿费上限：一线城市每晚 600 元，二线城市 450 元；市内交通实报实销。" },
    @{ title = "年假规则.md";     content = "# 年假规则`n`n入职满一年享有 5 天带薪年假，满三年 10 天，满五年 15 天。" },
    @{ title = "发票要求.md";     content = "# 发票要求`n`n报销必须提供增值税专用发票，抬头须为公司全称，不接受个人名义发票。" },
    @{ title = "设备申领.md";     content = "# 设备申领`n`n笔记本申请需部门主管审批，标准配置为 16GB 内存、512GB 固态硬盘。" },
    @{ title = "远程办公.md";     content = "# 远程办公`n`n每周最多两天远程办公，需提前一天在系统里报备。" },
    @{ title = "离职流程.md";     content = "# 离职流程`n`n离职需提前 30 天提交书面申请，交接完成后由 HR 办理离职证明。" }
)

# 查询与原文刻意没有字面重叠：mock（哈希）向量召不回，真实向量才认得。"出差住酒店最多能报多少钱" vs "住宿费上限"
$cases = @(
    @{ query = "出差住酒店最多能报多少钱"; expect = "差旅报销标准.md" },
    @{ query = "我在家上班可以吗";         expect = "远程办公.md" },
    @{ query = "辞职要提前多久说";         expect = "离职流程.md" },
    @{ query = "买电脑找谁批";             expect = "设备申领.md" }
)

"== 1) 入库合成语料（kb=$KbId） =="
foreach ($doc in $documents) {
    $body = @{ title = $doc.title; sourceType = "MARKDOWN"; content = $doc.content } | ConvertTo-Json -Compress
    $resp = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/kbs/$KbId/documents" -ContentType "application/json" -Body $body
    "{0,-24} status={1} chunks={2} duplicate={3}" -f $doc.title, $resp.data.status, $resp.data.chunkCount, $resp.data.duplicate
}

"== 2) 语义改写查询（topK=$TopK, minScore=$MinScore） =="
$results = @()
foreach ($case in $cases) {
    $body = @{ query = $case.query; topK = $TopK; minScore = $MinScore; sources = @("local"); includeContext = $false } |
        ConvertTo-Json -Compress
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $resp = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/kbs/$KbId/retrieve" -ContentType "application/json" -Body $body
    $sw.Stop()
    $chunks = @($resp.data.chunks)
    $hit = $chunks.Count -gt 0 -and $chunks[0].docTitle -eq $case.expect
    "{0,-18} -> 首位={1,-18} 期望={2,-18} {3}" -f $case.query,
        ($(if ($chunks.Count -gt 0) { $chunks[0].docTitle } else { "(空)" })), $case.expect,
        ($(if ($hit) { "OK" } else { "MISS" }))
    foreach ($c in $chunks) { "        {0,-22} raw={1}" -f $c.docTitle, $c.rawScore }
    $results += [ordered]@{
        query = $case.query; expect = $case.expect; hit = $hit;
        elapsedMs = [math]::Round($sw.Elapsed.TotalMilliseconds, 1);
        chunkIds = @($chunks | ForEach-Object { $_.chunkId });
        chunks = @($chunks | ForEach-Object { [ordered]@{ docTitle = $_.docTitle; rawScore = $_.rawScore; score = $_.score } });
        stats = $resp.data.stats
    }
}

$passed = @($results | Where-Object { $_.hit }).Count
"== 结果：$passed/$($results.Count) 个语义改写查询命中首位 =="

if (-not $OutFile) { $OutFile = "target/realvec-$KbId.json" }
[ordered]@{ kbId = $KbId; minScore = $MinScore; topK = $TopK; at = (Get-Date).ToString("s"); results = $results } |
    ConvertTo-Json -Depth 8 | Set-Content -Path $OutFile -Encoding UTF8
"wrote $OutFile"
if ($passed -ne $results.Count) { exit 1 }
