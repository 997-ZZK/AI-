<#
.SYNOPSIS
  Start a dedicated mock-only M4-3C engine. Existing PostgreSQL V10 and Redis are required.
.NOTES
  Does not read/change credential files, run migrations, stop other processes or clean data.
  Startup arguments and logs go to target/m4-3c. Invoke with PowerShell 5.1 or newer.
#>
param(
    [int]$Port = 8082,
    [int]$Workers = 16,
    [int]$QueueCapacity = 1000,
    [int]$MockLatencyMs = 0,
    [int]$MockChunkDelayMs = 30,
    [int]$MockChunkSize = 8,
    [int]$SseQueueCapacity = 2048,
    [switch]$TraceHttp,
    [string]$JavaHome = 'D:\tools\jdk21\jdk-21.0.12.1+1',
    [string]$Marker = ('m43c-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))
)
$ErrorActionPreference = 'Stop'
if ($Port -lt 1024 -or $Port -gt 65535 -or $Workers -lt 1 -or $Workers -gt 64 -or $QueueCapacity -lt 1) { throw 'Invalid engine limits' }
if ($MockLatencyMs -lt 0 -or $MockChunkDelayMs -lt 0 -or $MockChunkSize -lt 1 -or $SseQueueCapacity -lt 1) { throw 'Invalid mock timing' }
if ($Marker -notmatch '^[a-zA-Z0-9-]+$') { throw 'Marker must contain letters, digits and hyphens only' }
$root = Split-Path -Parent $PSScriptRoot
$output = Join-Path $root 'target/m4-3c'
[IO.Directory]::CreateDirectory($output) | Out-Null
$java = Join-Path $JavaHome 'bin/java.exe'
$jar = Join-Path $root 'target/aiflow-engine-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $java) -or -not (Test-Path -LiteralPath $jar)) { throw 'Build the engine and provide a JDK 21 path first' }
$listener = [Net.Sockets.TcpClient]::new()
try { $listener.Connect('localhost', $Port); throw "Port $Port already has a listener" }
catch [Net.Sockets.SocketException] { }
finally { $listener.Dispose() }
$argsList = @(
    '-Xms128m', '-Xmx768m', '-Dfile.encoding=UTF-8', "-Daiflow.validation.run-id=$Marker",
    '-jar', $jar, "--server.port=$Port", '--spring.flyway.enabled=false',
    '--aiflow.dispatch.enabled=true', '--aiflow.dispatch.worker-enabled=true', "--aiflow.dispatch.workers=$Workers",
    '--aiflow.dispatch.lease=30s', '--aiflow.engine.session.redis-lock-enabled=true', '--aiflow.engine.session.lock-ttl-ms=30000',
    "--aiflow.engine.session-queue-capacity=$QueueCapacity", '--aiflow.events.mode=redis',
    '--aiflow.events.max-entries=2000', '--aiflow.events.retention=24h', "--aiflow.events.sse-queue-capacity=$SseQueueCapacity",
    '--aiflow.provider.mode=mock', '--aiflow.provider.probe.enabled=false',
    '--aiflow.provider.endpoints.local.enabled=false', '--aiflow.provider.endpoints.cloud.enabled=false',
    '--aiflow.provider.endpoints.qwen.enabled=false', '--aiflow.provider.endpoints.ark.enabled=false', '--aiflow.provider.endpoints.deepseek.enabled=false',
    "--aiflow.provider.mock.latency-ms=$MockLatencyMs", "--aiflow.provider.mock.chunk-delay-ms=$MockChunkDelayMs", "--aiflow.provider.mock.chunk-size=$MockChunkSize",
    '--aiflow.retrieval.embedding.mode=mock', '--aiflow.retrieval.embedding.tiers[0].enabled=false',
    '--aiflow.retrieval.embedding.tiers[1].enabled=false', '--aiflow.retrieval.embedding.tiers[2].enabled=false',
    '--aiflow.retrieval.cloud.enabled=false', '--aiflow.memory.summary-enabled=false', '--aiflow.long-memory.extract-enabled=false',
    '--aiflow.ingest.ocr.enabled=false', '--aiflow.storage.enabled=false', '--aiflow.tools.enabled=false', '--aiflow.tools.audit.persist=false',
    '--aiflow.approval.sweep-enabled=false', '--aiflow.approval.notify.enabled=false', '--aiflow.auth.enabled=false',
    '--logging.level.com.aiflow.engine=INFO'
)
if ($TraceHttp) {
    $argsList += @('--server.tomcat.accesslog.enabled=true', "--server.tomcat.accesslog.directory=$output",
        "--server.tomcat.accesslog.prefix=$Marker-http", '--server.tomcat.accesslog.suffix=.log',
        '--server.tomcat.accesslog.rotate=false', '--server.tomcat.accesslog.buffered=false',
        '--server.tomcat.accesslog.pattern=%m|%U|%s|%D|%{remote}p')
}
$process = Start-Process -FilePath $java -ArgumentList $argsList -WorkingDirectory $root -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $output "$Marker.out.log") -RedirectStandardError (Join-Path $output "$Marker.err.log")
$snapshot = [ordered]@{ at=[DateTime]::UtcNow.ToString('o'); pid=$process.Id; marker=$Marker; port=$Port; workers=$Workers; heapMaxMiB=768; traceHttp=$TraceHttp.IsPresent; sseQueueCapacity=$SseQueueCapacity; args=$argsList; schemaRequirement='existing V10; Flyway disabled'; secretsRecorded=$false }
[IO.File]::WriteAllText((Join-Path $output "$Marker-config.json"), ($snapshot | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
[pscustomobject]@{ pid=$process.Id; port=$Port; marker=$Marker; snapshot=(Join-Path $output "$Marker-config.json") }
