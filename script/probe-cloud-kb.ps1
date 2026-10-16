# 云端知识库「探形」脚本：在写适配器之前，先用真实厂商跑一发，把它的请求 / 响应形状抓下来。
#
# 为什么需要它：引擎里的 HttpKnowledgeBaseProvider 目前吃的是**自定义契约**
# （POST /retrieve {dataset_id, query, top_k} -> {chunks:[{id, document_id, content, score, ...}]}），
# 没有哪家厂商原生说这套话。凭想象写适配器 = 拿猜出来的字段喂测试，真联调时全部返工；
# 先抓到真实响应，映射关系就是抄下来的，不是猜出来的。
#
# 用法（项目根目录，密钥只从环境变量读）：
#   $env:AIFLOW_CLOUD_KB_API_KEY='xxx'
#   powershell -File script\probe-cloud-kb.ps1 -BaseUrl https://<厂商网关> -Path /<检索路径> `
#       -Body '{"question":"{query}","dataset_ids":["{dataset}"],"top_k":{topK}}'
#
# -Body 是请求体模板：{query} / {dataset} / {topK} 三个占位符，替换时按 JSON 字符串转义且不带引号，
# 所以模板里该写引号的地方照常写。不传 -Body 就按引擎当前的自定义契约试一发。
# -AuthHeader / -AuthPrefix 覆盖鉴权形态（默认 Authorization: Bearer <key>；X-Api-Key 形态传
#   -AuthHeader X-Api-Key -AuthPrefix ''；端点不要鉴权传 -NoAuth）。
#
# 输出三块：HTTP 结果 -> 原始响应（截断）-> 结构摘要（数组路径 / 首元素字段与取值样例 / 疑似分数字段跨全部元素聚合）
# 及其取值范围）。最后一块直接对应 aiflow.retrieval.cloud 的字段映射与 score-min / score-max 量程。
param(
    [Parameter(Mandatory = $true)][string]$BaseUrl,
    [string]$Path = '/retrieve',
    [string]$Body = '',
    [string]$Query = '年假怎么算',
    [string]$Dataset = 'default',
    [int]$TopK = 3,
    [string]$AuthHeader = 'Authorization',
    [string]$AuthPrefix = 'Bearer ',
    [switch]$NoAuth,
    [int]$TimeoutSec = 30,
    [int]$RawLimit = 4000,
    # 分数扫描覆盖多少个数组元素（只影响摘要，不影响请求）
    [int]$ScanItems = 200,
    [switch]$ShowKey
)

$ErrorActionPreference = 'Stop'
chcp 65001 | Out-Null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Get-Escaped([string]$value) { ($value | ConvertTo-Json -Compress).Trim('"') }

$key = if ($env:AIFLOW_CLOUD_KB_API_KEY) { $env:AIFLOW_CLOUD_KB_API_KEY } else { '' }
if (-not $NoAuth -and -not $key) {
    throw '缺少密钥：设置环境变量 AIFLOW_CLOUD_KB_API_KEY，或对无鉴权端点传 -NoAuth'
}

if ($Body) {
    $json = $Body.Replace('{query}', (Get-Escaped $Query)).Replace('{dataset}', (Get-Escaped $Dataset)).Replace('{topK}', [string]$TopK)
} else {
    $json = @{ dataset_id = $Dataset; query = $Query; top_k = $TopK } | ConvertTo-Json -Compress
}

$url = $BaseUrl.TrimEnd('/') + $Path
Write-Host "POST $url"
Write-Host ("body: " + $json)
if ($ShowKey -and $key) {
    $masked = if ($key.Length -le 8) { '****' } else { $key.Substring(0, 4) + '****' + $key.Substring($key.Length - 2) }
    Write-Host ("auth: " + $AuthHeader + ": " + $AuthPrefix + $masked)
}

Add-Type -AssemblyName System.Net.Http
$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds($TimeoutSec)
$request = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, $url)
$request.Content = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, 'application/json')
if (-not $NoAuth -and $key) {
    $request.Headers.TryAddWithoutValidation($AuthHeader, ($AuthPrefix + $key)) | Out-Null
}
$text = ""
$status = 0
try {
    $response = $client.SendAsync($request).GetAwaiter().GetResult()
    $status = [int]$response.StatusCode
    $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    Write-Host ""
    Write-Host ("HTTP {0} {1}  ({2} 字节)" -f $status, $response.ReasonPhrase, $text.Length)
}
finally {
    $client.Dispose()
}

$shown = if ($text.Length -gt $RawLimit) { $text.Substring(0, $RawLimit) + "...(截断)" } else { $text }
Write-Host ""
Write-Host '=== 原始响应 ==='
Write-Host $shown

if ($status -ge 400) {
    Write-Host ""
    Write-Host '非 2xx：先核对 base-url / path / 鉴权形态 / 请求体字段名 —— 这一步的结论就是适配器的输入。'
    exit 2
}

Write-Host ''
Write-Host '=== 结构摘要 ==='
$node = $text | ConvertFrom-Json

# 形状摘要只展开数组首元素（看得清即可）；分数扫描要覆盖所有元素，所以单独一个不打印的收集器。
# 数值判定不认类型、认内容：PS 7 把 JSON 小数解成 double，PS 5.1 解成 decimal，只判 double/int/long 会漏。
# 固定用不变文化解析，避免小数点 / 千分位随区域设置漂移。
function Get-NumericValues($values) {
    $numbers = @()
    $inv = [System.Globalization.CultureInfo]::InvariantCulture
    foreach ($v in $values) {
        if ($null -eq $v -or $v -is [bool]) { continue }
        $d = 0.0
        if ([double]::TryParse($v.ToString(), [System.Globalization.NumberStyles]::Float, $inv, [ref]$d)) { $numbers += $d }
    }
    return $numbers
}

function Get-LeavesAll($node, [string]$prefix, [int]$depth, [int]$budget) {
    $result = @()
    if ($budget -le 0 -or $depth -gt 6 -or $null -eq $node) { return $result }
    if ($node -is [System.Collections.IEnumerable] -and $node -isnot [string]) {
        $items = @($node)
        $limit = [Math]::Min($items.Count, $budget)
        for ($i = 0; $i -lt $limit; $i++) {
            $result += Get-LeavesAll $items[$i] ($prefix + "[$i]") ($depth + 1) ($limit - $i)
        }
        return $result
    }
    if ($node -is [System.Management.Automation.PSCustomObject]) {
        foreach ($p in $node.PSObject.Properties) {
            $result += Get-LeavesAll $p.Value ($prefix + "." + $p.Name) ($depth + 1) $budget
        }
        return $result
    }
    return @([pscustomobject]@{ Path = $prefix; Value = $node })
}

function Get-Leaves($node, [string]$prefix, [int]$depth) {
    $result = @()
    if ($depth -gt 6) { return $result }
    if ($null -eq $node) { return @([pscustomobject]@{ Path = $prefix; Value = $null }) }
    if ($node -is [System.Collections.IEnumerable] -and $node -isnot [string]) {
        $items = @($node)
        Write-Host ("{0}[] 共 {1} 条" -f $prefix, $items.Count)
        if ($items.Count -gt 0) {
            $result += Get-Leaves $items[0] ($prefix + "[0]") ($depth + 1)
        }
        return $result
    }
    if ($node -is [System.Management.Automation.PSCustomObject]) {
        foreach ($p in $node.PSObject.Properties) {
            $result += Get-Leaves $p.Value ($prefix + "." + $p.Name) ($depth + 1)
        }
        return $result
    }
    $v = [string]$node
    if ($v.Length -gt 80) { $v = $v.Substring(0, 80) + "..." }
    Write-Host ("{0} = {1}" -f $prefix, $v)
    return @([pscustomobject]@{ Path = $prefix; Value = $node })
}

$leaves = Get-Leaves $node "$" 0
$scan = Get-LeavesAll $node "$" 0 $ScanItems

Write-Host ''
Write-Host '=== 疑似分数字段（对应 score-field 与 score-min / score-max）==='
$scoreLeaves = @($scan | Where-Object { $_.Path -match '(?i)score|similarity|relevance|distance|rank' })
if ($scoreLeaves.Count -eq 0) {
    Write-Host '（一个都没认出：厂商可能把分数换了名字，或检索结果里根本不带分数 —— 后者意味着阈值过滤只能靠本地重排）'
} else {
    $groups = $scoreLeaves | Group-Object { ($_.Path -replace "\[\d+\]", "[*]") }
    $allNumbers = @()
    foreach ($g in $groups) {
        $values = @($g.Group | ForEach-Object { $_.Value })
        $numbers = Get-NumericValues $values
        if ($numbers.Count -gt 0) {
            $allNumbers += $numbers
            $lo = ($numbers | Measure-Object -Minimum).Minimum
            $hi = ($numbers | Measure-Object -Maximum).Maximum
            Write-Host ("{0} = {1} 个数字，范围 {2} ~ {3}" -f $g.Name, $numbers.Count, $lo, $hi)
            if ($g.Name -match '(?i)distance') {
                Write-Host '    ^ distance 类字段越小越像，映射时要反向（取负或 1-x）再进归一化，否则阈值方向是反的'
            }
        } else {
            $sample = ($values | Select-Object -First 2) -join " / "
            Write-Host ("{0} = 非数字（{1} 条）：{2}" -f $g.Name, $values.Count, $sample)
        }
    }
    if ($allNumbers.Count -gt 0) {
        $min = ($allNumbers | Measure-Object -Minimum).Minimum
        $max = ($allNumbers | Measure-Object -Maximum).Maximum
        Write-Host ("全部候选字段观测范围 {0} ~ {1}（扫描前 {2} 个数组元素）" -f $min, $max, $ScanItems)
        if ($min -ge 0 -and $max -le 1) {
            Write-Host '=> 量程看着像 0 ~ 1：score-min: 0 / score-max: 1'
        } else {
            Write-Host ("=> 量程不是 0~1，先按观测范围起点配：score-min: {0} / score-max: {1}（多跑几个问题再收敛）" -f $min, $max)
        }
    }
}

Write-Host ''
Write-Host '把这份输出（去掉密钥）连同厂商协议文档贴回来，我照着写字段映射与适配器。'
