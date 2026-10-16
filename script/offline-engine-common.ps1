# Shared local-only launcher functions. Dot-sourcing this file never starts processes or writes files.
Set-StrictMode -Version 2.0

function Assert-OfflineMarker {
    param([string]$Marker)
    if ($Marker -cnotmatch '^[A-Za-z0-9][A-Za-z0-9-]{0,47}$') { throw 'OFFLINE_MARKER_INVALID' }
}

function Assert-OfflinePort {
    param([int]$Port)
    if ($Port -lt 1024 -or $Port -gt 65535) { throw 'OFFLINE_PORT_INVALID' }
}

function ConvertTo-OfflineWindowsArgument {
    param([AllowEmptyString()][string]$Value)
    if ($null -eq $Value -or $Value.IndexOf([char]0) -ge 0 -or $Value -match '[\r\n]') { throw 'OFFLINE_ARGUMENT_INVALID' }
    # Windows CommandLineToArgvW / C runtime quoting: quote every argument, double trailing slashes.
    $quoted = [regex]::Replace($Value, '(\\*)"', '$1$1\"')
    $quoted = [regex]::Replace($quoted, '(\\+)$', '$1$1')
    return '"' + $quoted + '"'
}

function Get-OfflineEnvironment {
    $result = @{}
    foreach ($name in @('SystemRoot','WINDIR','SystemDrive','ComSpec','TEMP','TMP','PATHEXT','OS',
            'PROCESSOR_ARCHITECTURE','PROCESSOR_IDENTIFIER','NUMBER_OF_PROCESSORS')) {
        $value = [Environment]::GetEnvironmentVariable($name, 'Process')
        if (-not [string]::IsNullOrEmpty($value)) { $result[$name] = $value }
    }
    # Use only Windows tools. The Java and shell executables are always absolute paths.
    $windows = [Environment]::GetEnvironmentVariable('SystemRoot', 'Process')
    if ([string]::IsNullOrWhiteSpace($windows)) { throw 'OFFLINE_OS_ENVIRONMENT_INVALID' }
    $result['Path'] = (Join-Path $windows 'System32') + ';' + $windows
    return $result
}

function Get-OfflinePaths {
    param([string]$Marker)
    Assert-OfflineMarker $Marker
    $root = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
    $ops = Join-Path $root 'target\ops'
    foreach($directory in @($root,(Join-Path $root 'target'),$ops,(Join-Path $ops $Marker),
        (Join-Path (Join-Path $ops $Marker) 'attachments'))) {
        Assert-OfflinePlainDirectory $directory
    }
    return [pscustomobject][ordered]@{
        root=$root; ops=$ops; marker=$Marker
        spec=(Join-Path $ops ($Marker+'-launch.json'))
        instance=(Join-Path $ops ($Marker+'-instance.json'))
        stdout=(Join-Path $ops ($Marker+'.out.log'))
        stderr=(Join-Path $ops ($Marker+'.err.log'))
        workerStdout=(Join-Path $ops ($Marker+'-worker.out.log'))
        workerStderr=(Join-Path $ops ($Marker+'-worker.err.log'))
        stopped=(Join-Path $ops ($Marker+'-stopped.json'))
    }
}

function Assert-OfflinePlainDirectory {
    param([string]$Path)
    try {$item=Get-Item -LiteralPath $Path -Force -ErrorAction Stop}
    catch {
        if($_.CategoryInfo.Category -eq [Management.Automation.ErrorCategory]::ObjectNotFound) {return}
        throw 'OFFLINE_OUTPUT_DIRECTORY_INVALID'
    }
    if(-not $item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'OFFLINE_OUTPUT_DIRECTORY_INVALID'
    }
}

function Get-OfflineJavaArguments {
    param([string]$JarPath,[int]$Port,[string]$Marker,[string]$DataRoot)
    Assert-OfflineMarker $Marker
    Assert-OfflinePort $Port
    return @(
        '-Xms128m','-Xmx768m','-Dfile.encoding=UTF-8',('-Daiflow.offline.marker='+$Marker),
        '-jar',$JarPath,('--server.port='+$Port),'--server.address=127.0.0.1',
        '--spring.config.location=classpath:/application.yml','--spring.config.additional-location=',
        '--spring.config.import=','--spring.profiles.active=','--spring.profiles.include=',
        '--spring.flyway.enabled=false','--spring.jpa.hibernate.ddl-auto=none','--spring.sql.init.mode=never',
        '--spring.datasource.url=jdbc:postgresql://127.0.0.1:15432/aiflow',
        '--spring.datasource.username=aiflow',
        '--spring.data.redis.host=127.0.0.1','--spring.data.redis.port=16379',
        '--spring.data.redis.timeout=2s','--spring.data.redis.connect-timeout=2s',
        '--aiflow.dispatch.enabled=false','--aiflow.dispatch.worker-enabled=false',
        '--aiflow.engine.session.redis-lock-enabled=true','--aiflow.engine.session.lock-ttl-ms=30000',
        '--aiflow.events.mode=redis','--aiflow.events.max-entries=2000','--aiflow.events.retention=24h',
        '--aiflow.budget.enabled=true','--aiflow.budget.store=redis',
        '--aiflow.provider.mode=mock','--aiflow.provider.order=mock','--aiflow.provider.probe.enabled=false',
        '--aiflow.provider.endpoints.local.enabled=false','--aiflow.provider.endpoints.cloud.enabled=false',
        '--aiflow.provider.endpoints.qwen.enabled=false','--aiflow.provider.endpoints.ark.enabled=false',
        '--aiflow.provider.endpoints.deepseek.enabled=false',
        '--aiflow.provider.mock.latency-ms=0','--aiflow.provider.mock.chunk-delay-ms=5','--aiflow.provider.mock.chunk-size=8',
        '--aiflow.retrieval.embedding.mode=mock','--aiflow.retrieval.embedding.tiers[0].enabled=false',
        '--aiflow.retrieval.embedding.tiers[1].enabled=false','--aiflow.retrieval.embedding.tiers[2].enabled=false',
        '--aiflow.retrieval.cloud.enabled=false','--aiflow.ingest.ocr.enabled=false','--aiflow.storage.enabled=false',
        '--aiflow.tools.enabled=false','--aiflow.tools.mcp.enabled=false','--aiflow.tools.skills.enabled=false',
        '--aiflow.tools.audit.enabled=false','--aiflow.tools.audit.persist=false','--aiflow.memory.enabled=true','--aiflow.memory.summary-enabled=false',
        '--aiflow.long-memory.extract-enabled=false','--aiflow.approval.sweep-enabled=false',
        '--aiflow.approval.notify.enabled=false','--aiflow.approval.notify.webhook.enabled=false',
        '--aiflow.approval.notify.webhook.url=','--aiflow.approval.notify.webhook.secret=','--aiflow.auth.enabled=false',
        '--aiflow.audio.enabled=true','--aiflow.audio.mode=mock',
        '--aiflow.audio.asr.enabled=true','--aiflow.audio.asr.provider=mock','--aiflow.audio.asr.model=mock-asr-1',
        '--aiflow.audio.tts.enabled=true','--aiflow.audio.tts.provider=mock','--aiflow.audio.tts.model=mock-tts-1',
        ('--aiflow.attachments.dir='+(Join-Path $DataRoot 'attachments')),
        '--logging.level.root=INFO','--logging.level.com.aiflow.engine=INFO',
        '--management.endpoints.web.exposure.include=health,info,metrics,prometheus',
        '--management.health.redis.enabled=true','--management.health.db.enabled=true'
    )
}

function Assert-OfflineObjectFields {
    param($Object,[string[]]$Fields)
    if ($null -eq $Object) { throw 'OFFLINE_DESCRIPTOR_INVALID' }
    $actual = @($Object.PSObject.Properties | ForEach-Object { $_.Name } | Sort-Object)
    $wanted = @($Fields | Sort-Object)
    if (($actual -join '|') -cne ($wanted -join '|')) { throw 'OFFLINE_DESCRIPTOR_INVALID' }
}

function New-OfflineLaunchSpec {
    param([string]$JavaHome,[string]$JarPath,[int]$Port,[string]$Marker)
    Assert-OfflineMarker $Marker
    Assert-OfflinePort $Port
    $paths = Get-OfflinePaths $Marker
    try {
        $java = [IO.Path]::GetFullPath((Join-Path $JavaHome 'bin\java.exe'))
        $release = Join-Path $JavaHome 'release'
        if (-not (Test-Path -LiteralPath $java -PathType Leaf) -or
            -not (Test-Path -LiteralPath $release -PathType Leaf) -or (Get-Item -LiteralPath $release).Length -gt 16384) {
            throw 'OFFLINE_JDK_INVALID'
        }
        $version = [regex]::Match([IO.File]::ReadAllText($release),'(?m)^JAVA_VERSION="(21(?:\.[0-9]+)*(?:[-+][A-Za-z0-9._-]+)?)"\s*$')
        if (-not $version.Success) { throw 'OFFLINE_JDK_NOT_21' }
        $jar = [IO.Path]::GetFullPath($JarPath)
        $allowed = (Join-Path $paths.root 'target') + [IO.Path]::DirectorySeparatorChar
        if (-not $jar.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase) -or
            [IO.Path]::GetExtension($jar) -cne '.jar' -or -not (Test-Path -LiteralPath $jar -PathType Leaf)) {
            throw 'OFFLINE_JAR_INVALID'
        }
        $hash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
    } catch {
        if ($_.Exception.Message -cmatch '^OFFLINE_[A-Z0-9_]+$') { throw $_.Exception.Message }
        throw 'OFFLINE_TOOLCHAIN_CHECK_FAILED'
    }
    return [pscustomobject][ordered]@{schemaVersion=1;marker=$Marker;port=$Port;javaPath=$java;jarPath=$jar;
        jarSha256=$hash;javaVersion=$version.Groups[1].Value;createdAtUtc=[DateTime]::UtcNow.ToString('o')}
}

function Assert-OfflineLaunchSpec {
    param($Spec)
    Assert-OfflineObjectFields $Spec @('schemaVersion','marker','port','javaPath','jarPath','jarSha256','javaVersion','createdAtUtc')
    if ($Spec.schemaVersion -ne 1 -or ($Spec.port -isnot [int] -and $Spec.port -isnot [long]) -or $Spec.marker -isnot [string] -or
        $Spec.jarSha256 -cnotmatch '^[a-f0-9]{64}$' -or $Spec.javaPath -isnot [string] -or $Spec.jarPath -isnot [string]) {
        throw 'OFFLINE_DESCRIPTOR_INVALID'
    }
    $javaHome = Split-Path -Parent (Split-Path -Parent $Spec.javaPath)
    $rebuilt = New-OfflineLaunchSpec -JavaHome $javaHome -JarPath $Spec.jarPath -Port $Spec.port -Marker $Spec.marker
    if (-not $rebuilt.javaPath.Equals($Spec.javaPath,[StringComparison]::OrdinalIgnoreCase) -or
        -not $rebuilt.jarPath.Equals($Spec.jarPath,[StringComparison]::OrdinalIgnoreCase) -or
        $rebuilt.jarSha256 -cne $Spec.jarSha256 -or $rebuilt.javaVersion -cne $Spec.javaVersion) {
        throw 'OFFLINE_DESCRIPTOR_CHANGED'
    }
    $when = [DateTime]::MinValue
    if (-not [DateTime]::TryParse($Spec.createdAtUtc,[ref]$when)) { throw 'OFFLINE_DESCRIPTOR_INVALID' }
}

function Read-OfflineJson {
    param([string]$Path)
    try {
        if (-not (Test-Path -LiteralPath $Path -PathType Leaf) -or (Get-Item -LiteralPath $Path).Length -gt 65536) {
            throw 'OFFLINE_DESCRIPTOR_INVALID'
        }
        return ConvertFrom-Json -InputObject ([IO.File]::ReadAllText($Path,[Text.Encoding]::UTF8))
    } catch { throw 'OFFLINE_DESCRIPTOR_INVALID' }
}

function Write-OfflineNewJson {
    param([string]$Path,$Value)
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes(($Value | ConvertTo-Json -Depth 10))
    $stream = $null
    try {
        $stream = [IO.File]::Open($Path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
        $stream.Write($bytes,0,$bytes.Length)
    } catch { throw 'OFFLINE_OUTPUT_ALREADY_EXISTS_OR_UNWRITABLE' }
    finally { if ($null -ne $stream) { $stream.Dispose() } }
}

function Test-OfflineTcpPort {
    param([int]$Port)
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $operation = $client.BeginConnect('127.0.0.1',$Port,$null,$null)
        if (-not $operation.AsyncWaitHandle.WaitOne(700)) { return $false }
        $client.EndConnect($operation)
        return [bool]$client.Connected
    } catch { return $false }
    finally { $client.Dispose() }
}

function Test-OfflineListenerOwnership {
    param([int]$Port,[int]$ExpectedPid)
    try {
        Assert-OfflinePort $Port
        if($ExpectedPid -le 0) {return $false}
        # State 2 is LISTEN. Reject wildcard/IPv6/duplicate listeners, including another process.
        $listeners=@(Get-CimInstance -Namespace 'root/StandardCimv2' -ClassName MSFT_NetTCPConnection `
            -Filter ('LocalPort='+$Port+' AND State=2') -ErrorAction Stop)
        return $listeners.Count -eq 1 -and $listeners[0].LocalAddress -ceq '127.0.0.1' -and
            $listeners[0].OwningProcess -eq $ExpectedPid
    } catch {return $false}
}

function Invoke-OfflineNative {
    param([string]$Executable,[string[]]$Arguments,[int]$TimeoutSeconds=8)
    $process = [Diagnostics.Process]::new()
    try {
        $info = [Diagnostics.ProcessStartInfo]::new()
        $info.FileName=$Executable; $info.UseShellExecute=$false; $info.CreateNoWindow=$true
        $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
        $info.Arguments=(@($Arguments | ForEach-Object {ConvertTo-OfflineWindowsArgument $_}) -join ' ')
        $info.EnvironmentVariables.Clear()
        foreach ($entry in (Get-OfflineEnvironment).GetEnumerator()) { $info.EnvironmentVariables[$entry.Key]=$entry.Value }
        $process.StartInfo=$info
        if (-not $process.Start()) { throw 'OFFLINE_COMMAND_FAILED' }
        $stdout=$process.StandardOutput.ReadToEndAsync(); $stderr=$process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds*1000)) {
            # This is the short-lived command created in this function, never an existing service.
            $process.Kill(); throw 'OFFLINE_COMMAND_TIMEOUT'
        }
        $text=$stdout.GetAwaiter().GetResult(); $null=$stderr.GetAwaiter().GetResult()
        if ($text.Length -gt 65536) { throw 'OFFLINE_COMMAND_RESPONSE_LIMIT' }
        return [pscustomobject]@{exitCode=$process.ExitCode;output=$text.Trim()}
    } catch { throw 'OFFLINE_COMMAND_FAILED' }
    finally { $process.Dispose() }
}

function Get-OfflinePrerequisites {
    param([string]$JavaHome,[string]$JarPath,[int]$Port)
    $checks = [Collections.Generic.List[object]]::new()
    try { $null=New-OfflineLaunchSpec -JavaHome $JavaHome -JarPath $JarPath -Port $Port -Marker 'preflight'; $ok=$true }
    catch { $ok=$false }
    $checks.Add([pscustomobject]@{name='jdk21-and-existing-jar';passed=$ok;reason=$(if($ok){'OK'}else{'OFFLINE_TOOLCHAIN_INVALID'})})
    try { Assert-OfflinePort $Port; $ok= -not (Test-OfflineTcpPort $Port) } catch { $ok=$false }
    $checks.Add([pscustomobject]@{name='target-port-free';passed=$ok;reason=$(if($ok){'OK'}else{'OFFLINE_PORT_UNAVAILABLE'})})
    foreach($entry in @(@('postgres-local-port',15432),@('redis-local-port',16379))) {
        $ok=Test-OfflineTcpPort ([int]$entry[1])
        $checks.Add([pscustomobject]@{name=$entry[0];passed=$ok;reason=$(if($ok){'OK'}else{'OFFLINE_LOCAL_SERVICE_UNAVAILABLE'})})
    }
    $root=Split-Path -Parent $PSScriptRoot
    $docker=Join-Path ${env:ProgramFiles} 'Docker\Docker\resources\bin\docker.exe'
    $emptyConfig=Join-Path $root 'target\ops\docker-no-credentials'
    $dockerOk=(Test-Path -LiteralPath $docker -PathType Leaf) -and -not (Test-Path -LiteralPath (Join-Path $emptyConfig 'config.json'))
    $checks.Add([pscustomobject]@{name='docker-local-client';passed=$dockerOk;reason=$(if($dockerOk){'OK'}else{'OFFLINE_DOCKER_CLIENT_UNAVAILABLE'})})
    foreach ($container in @('aiflow-postgres','aiflow-redis')) {
        $ok=$false
        if($dockerOk) {
            try { $result=Invoke-OfflineNative $docker @('--config',$emptyConfig,'--host','npipe:////./pipe/dockerDesktopLinuxEngine','inspect','--format','{{.State.Running}}',$container); $ok=$result.exitCode -eq 0 -and $result.output -ceq 'true' } catch { }
        }
        $checks.Add([pscustomobject]@{name=($container+'-running');passed=$ok;reason=$(if($ok){'OK'}else{'OFFLINE_CONTAINER_UNAVAILABLE'})})
    }
    $ok=$false
    if($dockerOk) {
        $sql="BEGIN READ ONLY; SET LOCAL statement_timeout='3s'; SELECT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version='10' AND success) AND NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE NOT success) AND (SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1)='10' AND EXISTS (SELECT 1 FROM pg_extension WHERE extname='vector') AND NOT EXISTS (SELECT 1 FROM (VALUES ('users'),('workflows'),('sessions'),('messages'),('workflow_runs'),('node_runs'),('documents'),('document_chunks'),('tool_audit_events'),('approvals'),('approval_events'),('run_checkpoints'),('session_memories'),('user_memories'),('user_memory_events'),('session_dispatch_tasks'),('user_profiles')) AS required(name) WHERE to_regclass('public.' || required.name) IS NULL); COMMIT;"
        try { $result=Invoke-OfflineNative $docker @('--config',$emptyConfig,'--host','npipe:////./pipe/dockerDesktopLinuxEngine','exec','aiflow-postgres','psql','--no-psqlrc','--no-password','-U','aiflow','-d','aiflow','-v','ON_ERROR_STOP=1','-qAt','-c',$sql); $ok=$result.exitCode -eq 0 -and $result.output -ceq 't' } catch { }
    }
    $checks.Add([pscustomobject]@{name='postgres-existing-v10-and-tables';passed=$ok;reason=$(if($ok){'OK'}else{'OFFLINE_SCHEMA_UNVERIFIED'})})
    $ok=$false
    if($dockerOk) {
        try { $result=Invoke-OfflineNative $docker @('--config',$emptyConfig,'--host','npipe:////./pipe/dockerDesktopLinuxEngine','exec','aiflow-redis','redis-cli','--raw','PING'); $ok=$result.exitCode -eq 0 -and $result.output -ceq 'PONG' } catch { }
    }
    $checks.Add([pscustomobject]@{name='redis-ping';passed=$ok;reason=$(if($ok){'OK'}else{'OFFLINE_REDIS_UNVERIFIED'})})
    return [pscustomobject]@{passed=(@($checks | Where-Object {-not $_.passed}).Count -eq 0);checks=@($checks.ToArray())}
}

function Test-OfflineInstanceIdentity {
    param($Instance)
    try {
        Assert-OfflineObjectFields $Instance @('schemaVersion','marker','port','pid','startTimeUtc','javaPath','jarPath','jarSha256','createdAtUtc')
        if($Instance.schemaVersion -ne 1 -or ($Instance.pid -isnot [int] -and $Instance.pid -isnot [long]) -or
            $Instance.pid -le 0 -or $Instance.pid -gt [int]::MaxValue) { throw 'OFFLINE_DESCRIPTOR_INVALID' }
        $paths=Get-OfflinePaths $Instance.marker
        $spec=Read-OfflineJson $paths.spec
        Assert-OfflineLaunchSpec $spec
        foreach($field in @('marker','port','javaPath','jarPath','jarSha256')) {
            if($Instance.$field -cne $spec.$field) { throw 'OFFLINE_DESCRIPTOR_CHANGED' }
        }
        try {$process=Get-Process -Id $Instance.pid -ErrorAction Stop}
        catch {
            if($_.CategoryInfo.Category -eq [Management.Automation.ErrorCategory]::ObjectNotFound) {
                return [pscustomobject]@{state='STOPPED';verified=$false;reason='OFFLINE_PROCESS_NOT_RUNNING'}
            }
            throw 'OFFLINE_IDENTITY_UNVERIFIED'
        }
        if(-not (Test-OfflineUtcInstantEqual $process.StartTime.ToUniversalTime() $Instance.startTimeUtc) -or
            -not ([string]$process.Path).Equals($spec.javaPath,[StringComparison]::OrdinalIgnoreCase)) {
            return [pscustomobject]@{state='IDENTITY_MISMATCH';verified=$false;reason='OFFLINE_PROCESS_IDENTITY_MISMATCH'}
        }
        $view=Get-CimInstance Win32_Process -Filter ('ProcessId='+$Instance.pid) -ErrorAction Stop
        $arguments=Get-OfflineJavaArguments -JarPath $spec.jarPath -Port $spec.port -Marker $spec.marker -DataRoot (Join-Path $paths.ops $spec.marker)
        $argumentLine=(@($arguments | ForEach-Object {ConvertTo-OfflineWindowsArgument $_}) -join ' ')
        $command=[string]$view.CommandLine
        if(-not (Test-OfflineCommandLine -CommandLine $command -Executable $spec.javaPath -ArgumentLine $argumentLine)) {
            return [pscustomobject]@{state='IDENTITY_MISMATCH';verified=$false;reason='OFFLINE_PROCESS_COMMAND_MISMATCH'}
        }
        return [pscustomobject]@{state='RUNNING';verified=$true;reason='OK'}
    } catch { return [pscustomobject]@{state='UNKNOWN';verified=$false;reason='OFFLINE_IDENTITY_UNVERIFIED'} }
}

function Stop-OfflineOwnedInstance {
    param($Instance)
    $process=$null
    try {
        $identity=Test-OfflineInstanceIdentity $Instance
        if(-not $identity.verified) {throw 'OFFLINE_STOP_IDENTITY_UNVERIFIED'}
        $process=Get-Process -Id $Instance.pid -ErrorAction Stop
        # Retain the original process handle across the final identity check and termination.
        $null=$process.Handle
        if(-not (Test-OfflineUtcInstantEqual $process.StartTime.ToUniversalTime() $Instance.startTimeUtc) -or
            -not ([string]$process.Path).Equals($Instance.javaPath,[StringComparison]::OrdinalIgnoreCase)) {
            throw 'OFFLINE_STOP_IDENTITY_UNVERIFIED'
        }
        $identity=Test-OfflineInstanceIdentity $Instance
        if(-not $identity.verified) {throw 'OFFLINE_STOP_IDENTITY_UNVERIFIED'}
        $process.Kill()
        if(-not $process.WaitForExit(10000)) {throw 'OFFLINE_STOP_NOT_CONFIRMED'}
        $identity=Test-OfflineInstanceIdentity $Instance
        if($identity.state -cne 'STOPPED') {throw 'OFFLINE_STOP_NOT_CONFIRMED'}
        return [pscustomobject]@{stopped=$true;reason='OK'}
    } catch {
        $reason='OFFLINE_STOP_NOT_CONFIRMED'
        if($_.Exception.Message -cmatch '^OFFLINE_[A-Z0-9_]+$') {$reason=$_.Exception.Message}
        return [pscustomobject]@{stopped=$false;reason=$reason}
    } finally {if($null -ne $process) {$process.Dispose()}}
}

function Test-OfflineUtcInstantEqual {
    param($Left,$Right)
    # PS 5.1 preserves JSON dates as strings; newer PS 7 converts them to DateTime.
    # Compare their exact UTC ticks without a culture-dependent string conversion or tolerance.
    try {
        $ticks=@()
        foreach($value in @($Left,$Right)) {
            if($value -is [DateTime]) {
                if($value.Kind -ne [DateTimeKind]::Utc) {return $false}
                $ticks += $value.Ticks
            } elseif($value -is [string] -and $value -cmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{7}Z$') {
                $parsed=[DateTime]::MinValue
                if(-not [DateTime]::TryParseExact($value,'o',[Globalization.CultureInfo]::InvariantCulture,
                    [Globalization.DateTimeStyles]::RoundtripKind,[ref]$parsed)) {return $false}
                $ticks += $parsed.Ticks
            } else {return $false}
        }
        return $ticks.Count -eq 2 -and $ticks[0] -eq $ticks[1]
    } catch {return $false}
}

function Test-OfflineCommandLine {
    param([string]$CommandLine,[string]$Executable,[string]$ArgumentLine)
    # Start-Process may append a space after its final argument. Only terminal unquoted
    # space/tab is insignificant; the entire fixed quoted argument list must still match.
    if([string]::IsNullOrEmpty($CommandLine) -or [string]::IsNullOrEmpty($Executable) -or
        [string]::IsNullOrEmpty($ArgumentLine)) {return $false}
    $escaped=[regex]::Escape($Executable)
    $prefix='^(?:"'+$escaped+'"|'+$escaped+')[ \t]+'
    if(-not [regex]::IsMatch($CommandLine,$prefix,[Text.RegularExpressions.RegexOptions]::IgnoreCase)) {return $false}
    $actual=[regex]::Replace($CommandLine,$prefix,'',[Text.RegularExpressions.RegexOptions]::IgnoreCase)
    return $actual.TrimEnd([char[]]@(' ',"`t")) -ceq $ArgumentLine
}

function Invoke-OfflineGetJson {
    param([int]$Port,[string]$Path)
    Assert-OfflinePort $Port
    if($Path -cnotmatch '^/(actuator/health|api/v1/capabilities|api/v1/runs/events/status|api/v1/runs/dispatch/status)$') { throw 'OFFLINE_HTTP_PATH_INVALID' }
    $response=$null
    try {
        $request=[Net.HttpWebRequest]::Create(('http://127.0.0.1:'+ $Port + $Path))
        $request.Method='GET';$request.Proxy=$null;$request.AllowAutoRedirect=$false;$request.Timeout=2500;$request.ReadWriteTimeout=2500
        $response=$request.GetResponse()
        if([int]$response.StatusCode -ne 200) { throw 'OFFLINE_HTTP_FAILED' }
        $reader=[IO.StreamReader]::new($response.GetResponseStream(),[Text.Encoding]::UTF8)
        try {
            $buffer=New-Object char[] 4096
            $builder=[Text.StringBuilder]::new()
            while(($count=$reader.Read($buffer,0,$buffer.Length)) -gt 0) {
                if($builder.Length+$count -gt 262144) {throw 'OFFLINE_HTTP_RESPONSE_LIMIT'}
                $null=$builder.Append($buffer,0,$count)
            }
            $text=$builder.ToString()
        } finally { $reader.Dispose() }
        return ConvertFrom-Json -InputObject $text
    } catch { throw 'OFFLINE_HTTP_UNAVAILABLE' }
    finally { if($null -ne $response) {$response.Dispose()} }
}

function Get-OfflineReadiness {
    param([int]$Port)
    $checks=[Collections.Generic.List[object]]::new()
    $health=$false;$caps=$false;$events=$false;$dispatch=$false
    try { $body=Invoke-OfflineGetJson $Port '/actuator/health';$health=([string]$body.status -ceq 'UP') } catch { }
    $checks.Add([pscustomobject]@{name='health-up';passed=$health})
    try {
        $body=Invoke-OfflineGetJson $Port '/api/v1/capabilities'
        if($body.code -ne 0) { throw 'OFFLINE_CAPABILITIES_INVALID' }
        $channels=@($body.data.channels)
        $caps=$true
        foreach($pair in @(@('chat','mock'),@('vision','mock'),@('embedding','mock-embedding'),@('asr','mock'),@('tts','mock'))) {
            $channel=@($channels | Where-Object {$_.channel -ceq $pair[0]})
            if($channel.Count -ne 1 -or $channel[0].activeProvider -cne $pair[1] -or
                @($channel[0].candidates).Count -ne 1 -or @($channel[0].candidates | Where-Object {
                    $_.id -cne $pair[1] -or $_.available -isnot [bool] -or -not $_.available}).Count -gt 0) {$caps=$false}
            if($pair[0] -in @('asr','tts') -and $channel.Count -eq 1 -and $channel[0].status -cne 'mock') {$caps=$false}
        }
        $cloud=@($channels | Where-Object {$_.channel -ceq 'cloud-knowledge-base'})
        if($cloud.Count -ne 1 -or $null -ne $cloud[0].activeProvider -or @($cloud[0].candidates).Count -ne 1 -or
            @($cloud[0].candidates | Where-Object {$_.available -isnot [bool] -or $_.available -or
                $_.expected -isnot [bool] -or $_.expected}).Count -ne 0) {$caps=$false}
    } catch { $caps=$false }
    $checks.Add([pscustomobject]@{name='mock-only-capabilities';passed=$caps})
    try {
        $body=Invoke-OfflineGetJson $Port '/api/v1/runs/events/status'
        $events=$body.code -eq 0 -and $body.data.bus.mode -ceq 'redis'
    } catch { }
    $checks.Add([pscustomobject]@{name='redis-events';passed=$events})
    try {
        $body=Invoke-OfflineGetJson $Port '/api/v1/runs/dispatch/status'
        $dispatch=$body.code -eq 0 -and $body.data.enabled -is [bool] -and -not $body.data.enabled -and
            $body.data.workerEnabled -is [bool] -and -not $body.data.workerEnabled -and $body.data.activeWorkers -eq 0 -and
            $body.data.claimed -eq 0 -and $body.data.recovered -eq 0 -and $body.data.leaseLost -eq 0
    } catch { }
    $checks.Add([pscustomobject]@{name='persistent-dispatch-disabled';passed=$dispatch})
    return [pscustomobject]@{passed=($health -and $caps -and $events -and $dispatch);checks=@($checks.ToArray())}
}
