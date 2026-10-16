# 本地 embedding 服务（离线兜底）一键安装：Ollama + bge-m3。
#
# 为什么用它：RAG 的 embedding 一旦只依赖云 API，断网就整条检索链不可用。
# 本地跑一个 1024 维模型（bge-m3，与 document_chunks.embedding vector(1024) 对齐），
# 断网降级到它，联网时再按分级策略切回云端加速。
#
# 装完必做两件事（见 docs/quality-plan.md）：
#   1. 重建索引：POST /api/v1/kbs/{kbId}/reindex
#      —— 旧向量不是 bge-m3 算的，两个向量空间之间的余弦没有意义，系统会显式报错拒绝检索。
#   2. 重校准阈值：真实模型的余弦分布比 mock 高，沿用 mock 校准出的 min-score 会把噪声全放进来。
#
# 用法：pwsh -File script\setup-local-embedding.ps1                 # 只装 embedding（RAG 离线兜底）
#      pwsh -File script\setup-local-embedding.ps1 -WithChatModel  # 再加一个对话模型（约 1GB，可选）
# 注意：安装包 + 模型合计约 2.3GB，慢网下可能要几小时；脚本支持断点续传（curl -C -），中断后重跑即可。
#
# 切换与校准（在项目根目录执行，PowerShell 传 -D 必须加单引号）：
#   改 src/main/resources/application.yml -> aiflow.retrieval.embedding.mode: http
#   $env:AIFLOW_EMBEDDING_MODE='http'; $env:AIFLOW_EMBEDDING_MODEL='bge-m3-cpu'
#   $env:AIFLOW_EMBEDDING_MIN_SCORE='<校准值>'      # 2026-09-20 实测 bge-m3 = 0.50
#   mvn spring-boot:run                             # 启动日志会印生效阈值与来源
#   curl -X POST localhost:8080/api/v1/kbs/demo/reindex
#   mvn test -Dtest=RagRealEmbeddingCalibrationTest '-Daiflow.eval.embedding.mode=http' '-Daiflow.eval.embedding.model=bge-m3-cpu'
#   把报告里建议的阈值写回 application.yml 的 embedding.min-score 与 docs/quality-plan.md §2.1.1
#
# 模型目录注意：Ollama 只在**启动它的那次环境**里读 OLLAMA_MODELS。托盘/开机自启拉起的服务用的是默认目录
# （%USERPROFILE%\.ollama\models），本脚本自带 OLLAMA_MODELS=D:\tools\ollama\models，两者不是同一个目录 ——
# 换个启动方式就等于换模型目录，会重新下一份 1.16GB。先确认服务是从哪儿起的，别重复下载。
param(
    # 对话档要的是 chat 模型（aiflow.provider.endpoints.local.model），与 embedding 是两回事：
    # 不装它时 /api/v1/capabilities 会显示 local 档 down（明文可见的降级），不会静默出错。
    [switch]$WithChatModel
)

$ErrorActionPreference = 'Stop'

$root = 'D:\tools\ollama'
$installer = Join-Path $root 'OllamaSetup.exe'
$models = Join-Path $root 'models'
New-Item -ItemType Directory -Force -Path $root, $models | Out-Null

if (-not (Test-Path $installer) -or (Get-Item $installer).Length -lt 100MB) {
    Write-Host '[1/4] 下载 Ollama（断点续传，约 1.1GB）...'
    curl.exe -L -C - --retry 20 --retry-delay 5 --retry-all-errors -o $installer 'https://ollama.com/download/OllamaSetup.exe'
} else {
    Write-Host '[1/4] 安装包已就绪，跳过下载'
}

$ollamaExe = Join-Path $env:LOCALAPPDATA 'Programs\Ollama\ollama.exe'
if (-not (Test-Path $ollamaExe)) {
    Write-Host '[2/4] 静默安装到当前用户目录（无需管理员）...'
    Start-Process -FilePath $installer -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART' -Wait
} else {
    Write-Host '[2/4] Ollama 已安装，跳过'
}

# 模型权重放 D 盘，别塞满 C 盘
$env:OLLAMA_MODELS = $models
if (-not (Get-Process ollama -ErrorAction SilentlyContinue)) {
    Write-Host '[3/4] 启动 ollama serve ...'
    Start-Process -FilePath $ollamaExe -ArgumentList 'serve' -WindowStyle Hidden
    Start-Sleep -Seconds 5
}

Write-Host '[4/4] 拉取 bge-m3（约 1.2GB，1024 维）...'
& $ollamaExe pull bge-m3

# 同一份权重派生一个「不上 GPU」的别名。
# 为什么需要它：Ollama 在 Windows 的 CUDA 路径上会禁用 mmap，把 1.1GB 模型整块读进内存；
# 本机实测可用内存剩 ~700MB 时 llama-server 直接以 0xc0000005 崩溃（关掉 Flash Attention 一样崩，
# 与 FA 无关，就是内存不够）。CPU 路径走 mmap，稳定。向量空间与 bge-m3 完全一致。
Write-Host '派生 CPU 别名 bge-m3-cpu（避免 Windows CUDA 路径的整块内存加载）...'
$modelfile = Join-Path $root 'bge-m3-cpu.Modelfile'
"FROM bge-m3$([Environment]::NewLine)PARAMETER num_gpu 0" | Set-Content -Encoding utf8 $modelfile
& $ollamaExe create bge-m3-cpu -f $modelfile

Write-Host '探活：/v1/embeddings 是否返回 1024 维（用 bge-m3-cpu）'
$probe = curl.exe -s -X POST 'http://localhost:11434/v1/embeddings' -H 'Content-Type: application/json' -d '{"model":"bge-m3-cpu","input":"探活"}'
$dim = ([regex]::Match($probe, '"embedding":\[(.*?)\]')).Groups[1].Value.Split(',').Count
Write-Host ("provider=http-embedding:bge-m3-cpu  dimensions={0}" -f $dim)
if ($dim -ne 1024) {
    if ($probe -match '0xc0000005|terminated|memory') {
        Write-Warning 'llama-server 崩了：多半是显存/内存不足，模型没能整块加载。用 bge-m3-cpu（CPU 路径）跑，或先腾出内存。'
    } else {
        Write-Warning '维度不是 1024，与 document_chunks.embedding vector(1024) 不一致：先确认模型，别直接改配置'
    }
}
if ($WithChatModel) {
    Write-Host '[5/5] 拉取对话模型 qwen2.5:1.5b（约 1GB，云端档不需要这一步）...'
    & $ollamaExe pull qwen2.5:1.5b
}

Write-Host '完成。切换与校准步骤见本脚本头部注释。'
Write-Host '验证：curl -s localhost:8080/api/v1/capabilities  ->  local 档应变为 available=true'