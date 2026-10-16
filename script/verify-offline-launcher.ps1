<#
.SYNOPSIS
  Verify offline launcher boundaries without starting an engine or touching business data.
#>
param(
    [string]$JavaHome='D:\tools\jdk21\jdk-21.0.12.1+1',
    [string]$JarPath='',
    [string]$Prefix=('launcher-gate-'+[Guid]::NewGuid().ToString('N').Substring(0,12))
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'offline-engine-common.ps1')
$checks=[Collections.Generic.List[object]]::new()

function Verify-LauncherCase {
    param([string]$Name,[scriptblock]$Assertions)
    try {
        & $Assertions
        $checks.Add([pscustomobject]@{name=$Name;passed=$true})
        Write-Host ('PASS: '+$Name)
    } catch {
        $checks.Add([pscustomobject]@{name=$Name;passed=$false;failureType=$_.Exception.GetType().Name})
        Write-Host ('FAIL: '+$Name)
    }
}

function Require-LauncherGate {
    param([bool]$Condition)
    if(-not $Condition) {throw 'LAUNCHER_GATE_ASSERTION_FAILED'}
}

function Require-LauncherRejection {
    param([scriptblock]$Action)
    $rejected=$false
    try {& $Action | Out-Null} catch {$rejected=$true}
    Require-LauncherGate $rejected
}

function New-ReadinessFixture {
    # Independent API fixtures. No response is copied from a running instance.
    return @{
        '/actuator/health'=('{"status":"UP"}'|ConvertFrom-Json)
        '/api/v1/capabilities'=(@'
{"code":0,"data":{"level":"L3","channels":[
 {"channel":"chat","status":"ok","activeProvider":"mock","activeTier":"mock","candidates":[{"id":"mock","available":true,"expected":true}]},
 {"channel":"vision","status":"ok","activeProvider":"mock","activeTier":"mock","candidates":[{"id":"mock","available":true,"expected":true}]},
 {"channel":"embedding","status":"ok","activeProvider":"mock-embedding","activeTier":"mock","candidates":[{"id":"mock-embedding","available":true,"expected":true}]},
 {"channel":"asr","status":"mock","activeProvider":"mock","activeTier":"mock","candidates":[{"id":"mock","available":true,"expected":true}]},
 {"channel":"tts","status":"mock","activeProvider":"mock","activeTier":"mock","candidates":[{"id":"mock","available":true,"expected":true}]},
 {"channel":"cloud-knowledge-base","status":"ok","activeProvider":null,"activeTier":"cloud","candidates":[{"id":"cloud-kb","available":false,"expected":false}]}
]}}
'@|ConvertFrom-Json)
        '/api/v1/runs/events/status'=('{"code":0,"data":{"bus":{"mode":"redis","publishFailures":0,"readFailures":0,"readerCount":0,"subscriptionCount":0},"connectionCount":0,"sseOverflow":0}}'|ConvertFrom-Json)
        '/api/v1/runs/dispatch/status'=('{"code":0,"data":{"enabled":false,"workerEnabled":false,"activeWorkers":0,"claimed":0,"recovered":0,"leaseLost":0}}'|ConvertFrom-Json)
    }
}

try {
    Assert-OfflineMarker $Prefix
    $paths=Get-OfflinePaths $Prefix
    [IO.Directory]::CreateDirectory($paths.ops)|Out-Null
    if([string]::IsNullOrWhiteSpace($JarPath)) {$JarPath=Join-Path $paths.root 'target\aiflow-engine-0.1.0-SNAPSHOT.jar'}

    foreach($bad in @('', '..', '../escape', 'bad marker', 'bad_marker', '-leading', ('a'*49))) {
        $invalidMarker=$bad
        Verify-LauncherCase ('marker-rejected-'+$checks.Count) {Require-LauncherRejection {Assert-OfflineMarker $invalidMarker}}
    }
    Verify-LauncherCase 'marker-valid' {Assert-OfflineMarker 'offline-20261014-demo'}
    foreach($badPort in @(0,1023,65536)) {
        $invalidPort=$badPort
        Verify-LauncherCase ('port-rejected-'+$badPort) {Require-LauncherRejection {Assert-OfflinePort $invalidPort}}
    }
    Verify-LauncherCase 'port-valid' {Assert-OfflinePort 8083}
    Verify-LauncherCase 'paths-stay-in-project-ops' {
        $rootPrefix=$paths.ops+[IO.Path]::DirectorySeparatorChar
        foreach($name in @('spec','instance','stdout','stderr','stopped')) {
            Require-LauncherGate ([IO.Path]::GetFullPath($paths.$name).StartsWith($rootPrefix,[StringComparison]::OrdinalIgnoreCase))
        }
    }
    Verify-LauncherCase 'environment-is-an-explicit-whitelist' {
        $allowed=@('SystemRoot','WINDIR','SystemDrive','ComSpec','TEMP','TMP','PATHEXT','OS',
            'PROCESSOR_ARCHITECTURE','PROCESSOR_IDENTIFIER','NUMBER_OF_PROCESSORS','Path')
        $environment=Get-OfflineEnvironment
        Require-LauncherGate ($environment.Count -gt 0)
        foreach($key in $environment.Keys) {Require-LauncherGate ($key -in $allowed)}
        foreach($key in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH','SPRING_APPLICATION_JSON',
                'SPRING_CONFIG_LOCATION','SPRING_PROFILES_ACTIVE','AIFLOW_QWEN_API_KEY','DB_PASSWORD','REDIS_PASSWORD')) {
            Require-LauncherGate (-not $environment.ContainsKey($key))
        }
    }
    Verify-LauncherCase 'injected-application-variables-do-not-propagate' {
        $variableName='AIFLOW_SYNTHETIC_GATE_'+[Guid]::NewGuid().ToString('N')
        try {
            [Environment]::SetEnvironmentVariable($variableName,'synthetic-value','Process')
            Require-LauncherGate (-not (Get-OfflineEnvironment).ContainsKey($variableName))
        } finally {[Environment]::SetEnvironmentVariable($variableName,$null,'Process')}
    }
    $arguments=@(Get-OfflineJavaArguments -JarPath $JarPath -Port 8083 -Marker $Prefix -DataRoot (Join-Path $paths.ops $Prefix))
    Verify-LauncherCase 'fixed-no-migration-and-loopback-settings' {
        foreach($value in @('--server.address=127.0.0.1','--server.port=8083','--spring.flyway.enabled=false',
                '--spring.jpa.hibernate.ddl-auto=none','--spring.sql.init.mode=never',
                '--spring.config.location=classpath:/application.yml','--spring.config.additional-location=',
                '--spring.config.import=','--spring.profiles.active=','--spring.profiles.include=')) {
            Require-LauncherGate ($value -cin $arguments)
        }
    }
    Verify-LauncherCase 'all-external-capabilities-disabled' {
        foreach($value in @('--aiflow.provider.mode=mock','--aiflow.provider.order=mock','--aiflow.provider.probe.enabled=false',
                '--aiflow.retrieval.embedding.mode=mock','--aiflow.retrieval.cloud.enabled=false',
                '--aiflow.ingest.ocr.enabled=false','--aiflow.storage.enabled=false','--aiflow.tools.enabled=false',
                '--aiflow.tools.mcp.enabled=false','--aiflow.tools.skills.enabled=false',
                '--aiflow.tools.audit.enabled=false','--aiflow.tools.audit.persist=false',
                '--aiflow.memory.summary-enabled=false','--aiflow.long-memory.extract-enabled=false',
                '--aiflow.approval.sweep-enabled=false','--aiflow.approval.notify.enabled=false',
                '--aiflow.approval.notify.webhook.enabled=false','--aiflow.approval.notify.webhook.url=',
                '--aiflow.approval.notify.webhook.secret=','--aiflow.auth.enabled=false')) {
            Require-LauncherGate ($value -cin $arguments)
        }
        foreach($id in @('local','cloud','qwen','ark','deepseek')) {
            Require-LauncherGate (('--aiflow.provider.endpoints.'+$id+'.enabled=false') -cin $arguments)
        }
        foreach($index in @(0,1,2)) {Require-LauncherGate (('--aiflow.retrieval.embedding.tiers['+$index+'].enabled=false') -cin $arguments)}
    }
    Verify-LauncherCase 'single-instance-and-mock-audio-settings' {
        foreach($value in @('--aiflow.dispatch.enabled=false','--aiflow.dispatch.worker-enabled=false',
                '--aiflow.engine.session.redis-lock-enabled=true','--aiflow.events.mode=redis',
                '--aiflow.events.max-entries=2000','--aiflow.events.retention=24h','--aiflow.budget.store=redis',
                '--aiflow.audio.enabled=true','--aiflow.audio.mode=mock',
                '--aiflow.audio.asr.provider=mock','--aiflow.audio.tts.provider=mock',
                '--management.health.db.enabled=true','--management.health.redis.enabled=true')) {
            Require-LauncherGate ($value -cin $arguments)
        }
    }
    Verify-LauncherCase 'configuration-properties-have-no-duplicate-overrides' {
        $keys=@($arguments | Where-Object {$_.StartsWith('--')} | ForEach-Object {($_ -split '=',2)[0]})
        Require-LauncherGate (@($keys|Group-Object|Where-Object {$_.Count -gt 1}).Count -eq 0)
    }
    Verify-LauncherCase 'reject-nul-or-multiline-native-arguments' {
        Require-LauncherRejection {ConvertTo-OfflineWindowsArgument ("bad`nargument")}
        Require-LauncherRejection {ConvertTo-OfflineWindowsArgument ("bad"+[char]0+"argument")}
    }
    Verify-LauncherCase 'missing-jdk-rejected' {
        Require-LauncherRejection {New-OfflineLaunchSpec -JavaHome (Join-Path $paths.ops 'absent-jdk') -JarPath $JarPath -Port 8083 -Marker $Prefix}
    }
    Verify-LauncherCase 'missing-jar-rejected' {
        Require-LauncherRejection {New-OfflineLaunchSpec -JavaHome $JavaHome -JarPath (Join-Path $paths.ops 'absent.jar') -Port 8083 -Marker $Prefix}
    }
    Verify-LauncherCase 'jar-outside-target-rejected' {
        Require-LauncherRejection {New-OfflineLaunchSpec -JavaHome $JavaHome -JarPath (Join-Path $paths.root 'pom.xml') -Port 8083 -Marker $Prefix}
    }
    Verify-LauncherCase 'valid-spec-roundtrip-and-hash-pinning' {
        $spec=New-OfflineLaunchSpec -JavaHome $JavaHome -JarPath $JarPath -Port 8083 -Marker $Prefix
        Assert-OfflineLaunchSpec $spec
        $copy=$spec|ConvertTo-Json|ConvertFrom-Json
        $copy.jarSha256='0'*64
        Require-LauncherRejection {Assert-OfflineLaunchSpec $copy}
        $copy=$spec|ConvertTo-Json|ConvertFrom-Json
        $copy|Add-Member -NotePropertyName extraArguments -NotePropertyValue 'synthetic'
        Require-LauncherRejection {Assert-OfflineLaunchSpec $copy}
    }
    Verify-LauncherCase 'invalid-identity-cannot-authorize-stop' {
        $identity=Test-OfflineInstanceIdentity ([pscustomobject]@{pid=$PID;marker='../escape'})
        Require-LauncherGate (-not $identity.verified -and $identity.state -eq 'UNKNOWN')
    }
    Verify-LauncherCase 'utc-identity-accepts-string-and-json-date' {
        $instant='2026-10-14T04:42:19.2657005Z'
        $date=[DateTime]::ParseExact($instant,'o',[Globalization.CultureInfo]::InvariantCulture,[Globalization.DateTimeStyles]::RoundtripKind)
        Require-LauncherGate (Test-OfflineUtcInstantEqual -Left $instant -Right $date)
        Require-LauncherGate (Test-OfflineUtcInstantEqual -Left $date -Right $instant)
    }
    Verify-LauncherCase 'utc-identity-rejects-one-tick-difference' {
        Require-LauncherGate (-not (Test-OfflineUtcInstantEqual -Left '2026-10-14T04:42:19.2657005Z' -Right '2026-10-14T04:42:19.2657006Z'))
        Require-LauncherGate (-not (Test-OfflineUtcInstantEqual -Left '2026-10-14T04:42:19.2657005Z' -Right '2026-10-14T04:42:19.2657004Z'))
    }
    Verify-LauncherCase 'utc-identity-rejects-local-or-invalid-time' {
        Require-LauncherGate (-not (Test-OfflineUtcInstantEqual -Left 'invalid' -Right 'invalid'))
        Require-LauncherGate (-not (Test-OfflineUtcInstantEqual -Left '2026-10-14T04:42:19.2657005+08:00' -Right '2026-10-14T04:42:19.2657005Z'))
        Require-LauncherGate (-not (Test-OfflineUtcInstantEqual -Left ([DateTime]::SpecifyKind([DateTime]::UtcNow,[DateTimeKind]::Unspecified)) -Right '2026-10-14T04:42:19.2657005Z'))
    }
    Verify-LauncherCase 'command-identity-allows-only-trailing-native-whitespace' {
        $executable='D:\synthetic path\java.exe'
        $arguments='"-jar" "D:\synthetic path\engine.jar" "--server.port=8083"'
        foreach($prefix in @(('"'+$executable+'" '), ($executable+' '))) {
            Require-LauncherGate (Test-OfflineCommandLine -CommandLine ($prefix+$arguments) -Executable $executable -ArgumentLine $arguments)
            Require-LauncherGate (Test-OfflineCommandLine -CommandLine ($prefix+$arguments+" `t ") -Executable $executable -ArgumentLine $arguments)
        }
    }
    Verify-LauncherCase 'command-identity-rejects-changed-argument-or-executable' {
        $executable='D:\synthetic path\java.exe'
        $arguments='"-jar" "D:\synthetic path\engine.jar" "--server.port=8083"'
        $command='"'+$executable+'" '+$arguments
        Require-LauncherGate (-not (Test-OfflineCommandLine -CommandLine ($command.Replace('8083','8084')) -Executable $executable -ArgumentLine $arguments))
        Require-LauncherGate (-not (Test-OfflineCommandLine -CommandLine ($command.Replace('java.exe','other.exe')) -Executable $executable -ArgumentLine $arguments))
        Require-LauncherGate (-not (Test-OfflineCommandLine -CommandLine ($command+' "extra"') -Executable $executable -ArgumentLine $arguments))
        Require-LauncherGate (-not (Test-OfflineCommandLine -CommandLine ($command+"`n") -Executable $executable -ArgumentLine $arguments))
    }

    # An actual Java argv probe tests spaces, quotes, slash escaping, empty and Unicode arguments.
    Verify-LauncherCase 'native-argv-and-environment-roundtrip' {
        $probePath=Join-Path $paths.ops ($Prefix+'-ArgumentProbe.java')
        $probe=@'
import java.util.Arrays;
class ArgumentProbe {
    public static void main(String[] args) {
        String[] expected={"plain","two words","literal\"quote","C:\\synthetic space\\","中文值","$(synthetic)&literal",""};
        boolean clean=System.getenv().keySet().stream().noneMatch(k -> k.matches("(?i)(AIFLOW_.*|SPRING_.*|DB_.*|REDIS_.*|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS|CLASSPATH)"));
        boolean matches=Arrays.equals(args,expected);
        System.out.print("{\"matches\":"+matches+",\"environmentClean\":"+clean+",\"count\":"+args.length+"}");
        if(!matches || !clean) System.exit(2);
    }
}
'@
        [IO.File]::WriteAllText($probePath,$probe,[Text.UTF8Encoding]::new($false))
        $probeArgs=@($probePath,'plain','two words','literal"quote','C:\synthetic space\','中文值','$(synthetic)&literal','')
        $result=Invoke-OfflineNative -Executable (Join-Path $JavaHome 'bin\java.exe') -Arguments $probeArgs -TimeoutSeconds 20
        Require-LauncherGate ($result.exitCode -eq 0)
        $observed=$result.output|ConvertFrom-Json
        Require-LauncherGate ($observed.matches -and $observed.environmentClean -and $observed.count -eq 7)
    }

    $originalGet=(Get-Command Invoke-OfflineGetJson -CommandType Function).ScriptBlock
    try {
        function Invoke-OfflineGetJson {param([int]$Port,[string]$Path) return $script:gateBodies[$Path]}
        Verify-LauncherCase 'readiness-accepts-complete-independent-fixture' {
            $script:gateBodies=New-ReadinessFixture
            Require-LauncherGate ((Get-OfflineReadiness 8083).passed)
        }
        Verify-LauncherCase 'readiness-rejects-health-down' {
            $script:gateBodies=New-ReadinessFixture
            $script:gateBodies['/actuator/health'].status='DOWN'
            Require-LauncherGate (-not (Get-OfflineReadiness 8083).passed)
        }
        foreach($channelName in @('chat','vision','embedding','asr','tts')) {
            $changedChannel=$channelName
            Verify-LauncherCase ('readiness-rejects-extra-'+$changedChannel+'-candidate') {
                $script:gateBodies=New-ReadinessFixture
                $channel=@($script:gateBodies['/api/v1/capabilities'].data.channels|Where-Object {$_.channel -eq $changedChannel})[0]
                $channel.candidates+=@([pscustomobject]@{id='synthetic-http';available=$false;expected=$false})
                Require-LauncherGate (-not (Get-OfflineReadiness 8083).passed)
            }
        }
        Verify-LauncherCase 'readiness-rejects-active-cloud-knowledge-base' {
            $script:gateBodies=New-ReadinessFixture
            $cloud=@($script:gateBodies['/api/v1/capabilities'].data.channels|Where-Object {$_.channel -eq 'cloud-knowledge-base'})[0]
            $cloud.activeProvider='synthetic-cloud'
            Require-LauncherGate (-not (Get-OfflineReadiness 8083).passed)
        }
        Verify-LauncherCase 'readiness-rejects-missing-channel' {
            $script:gateBodies=New-ReadinessFixture
            $data=$script:gateBodies['/api/v1/capabilities'].data
            $data.channels=@($data.channels|Where-Object {$_.channel -ne 'tts'})
            Require-LauncherGate (-not (Get-OfflineReadiness 8083).passed)
        }
        Verify-LauncherCase 'readiness-rejects-memory-events' {
            $script:gateBodies=New-ReadinessFixture
            $script:gateBodies['/api/v1/runs/events/status'].data.bus.mode='memory'
            Require-LauncherGate (-not (Get-OfflineReadiness 8083).passed)
        }
        foreach($metric in @('activeWorkers','claimed','recovered','leaseLost')) {
            $changedMetric=$metric
            Verify-LauncherCase ('readiness-rejects-dispatch-'+$metric) {
                $script:gateBodies=New-ReadinessFixture
                $script:gateBodies['/api/v1/runs/dispatch/status'].data.$changedMetric=1
                Require-LauncherGate (-not (Get-OfflineReadiness 8083).passed)
            }
        }
        Verify-LauncherCase 'readiness-rejects-enabled-worker' {
            $script:gateBodies=New-ReadinessFixture
            $script:gateBodies['/api/v1/runs/dispatch/status'].data.workerEnabled=$true
            Require-LauncherGate (-not (Get-OfflineReadiness 8083).passed)
        }
        Verify-LauncherCase 'readiness-rejects-missing-response' {
            $script:gateBodies=New-ReadinessFixture
            $null=$script:gateBodies.Remove('/api/v1/runs/dispatch/status')
            Require-LauncherGate (-not (Get-OfflineReadiness 8083).passed)
        }
    } finally {Set-Item Function:Invoke-OfflineGetJson -Value $originalGet}

    $passed=@($checks|Where-Object {$_.passed}).Count
    $report=[pscustomobject][ordered]@{schemaVersion=1;atUtc=[DateTime]::UtcNow.ToString('o');
        total=$checks.Count;passed=$passed;failed=($checks.Count-$passed);gate=$(if($passed -eq $checks.Count){'PASS'}else{'FAIL'});checks=$checks.ToArray()}
    $reportPath=Join-Path $paths.ops ($Prefix+'-report.json')
    Write-OfflineNewJson $reportPath $report
    Write-Output ('Report: '+$reportPath)
    if($report.gate -ne 'PASS') {exit 1}
    exit 0
} catch {
    Write-Output 'OFFLINE_LAUNCHER_GATE_FAILED'
    exit 1
}
