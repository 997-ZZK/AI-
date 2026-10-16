# 对 OpenAI 兼容网关跑真实 embedding 阈值重校准（云端档路线：不下载模型）。
#
# 为什么必须重校准：真实模型对同义改写也给高分，余弦分布整体高于 mock；沿用 mock 量纲下的 min-score，
# 「低相似度丢弃」会失效（该丢的丢不掉），拒答正确率随之崩掉。详见 docs/quality-plan.md。
#
# 用法（在项目根目录）：
#   $env:AIFLOW_EVAL_EMBEDDING_API_KEY='sk-xxx'
#   powershell -File script\run-cloud-embedding-calibration.ps1 -BaseUrl https://api.siliconflow.cn/v1 -Model BAAI/bge-m3
#
# 密钥只从环境变量读：写进命令行会进进程参数与历史记录，等于把密钥散出去。
# 报告落在 target\eval\rag-report-http-embedding-<model>.json：含完整阈值曲线与每条问题的失败明细。
# 收尾两件事（顺序不能反）：把建议 min-score 写回 application.yml，再 POST /api/v1/kbs/{kbId}/reindex 重建索引。
param(
    [Parameter(Mandatory = $true)][string]$BaseUrl,
    [string]$Model = 'BAAI/bge-m3',
    # 需要更密/更疏的阈值网格时传，例如 -Sweep 0.2,0.25,0.3,0.35,0.4
    [string]$Sweep = ''
)

$ErrorActionPreference = 'Stop'
chcp 65001 | Out-Null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

if (-not $env:AIFLOW_EVAL_EMBEDDING_API_KEY) {
    throw '缺少环境变量 AIFLOW_EVAL_EMBEDDING_API_KEY：密钥只从环境变量读，不进脚本、仓库与命令行'
}
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Host '当前会话没有 mvn，先加载 .\script\dev-env.ps1'
    if (Test-Path .\script\dev-env.ps1) { . .\script\dev-env.ps1 } else { throw '未找到 script\dev-env.ps1' }
}

Set-Location (Split-Path $PSScriptRoot -Parent)
$arguments = @(
    '-B', '-ntp', '-o', '-s', 'script\maven-settings.xml',
    '-Dmaven.repo.local=C:\Users\99730\.m2\repository',
    'test', '-Dtest=RagRealEmbeddingCalibrationTest',
    '-Daiflow.eval.embedding.mode=http',
    "-Daiflow.eval.embedding.base-url=$BaseUrl",
    "-Daiflow.eval.embedding.model=$Model"
)
if ($Sweep) { $arguments += "-Daiflow.eval.threshold-sweep=$Sweep" }

Write-Host ("校准 provider=base-url:{0} model:{1}（密钥来自环境变量，不打印）" -f $BaseUrl, $Model)
& mvn @arguments

Write-Host ''
Write-Host '报告：target\eval\rag-report-*.json'
Write-Host '收尾：把建议 min-score 写回 aiflow.retrieval.min-score 与 docs/quality-plan.md，再 POST /api/v1/kbs/{kbId}/reindex'