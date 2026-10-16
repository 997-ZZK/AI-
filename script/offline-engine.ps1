<#
.SYNOPSIS
  Check, start, inspect or stop a dedicated local mock engine; no migrations or credential discovery.
#>
param(
    [ValidateSet('Check','Start','Status','Stop')][string]$Action='Check',
    [int]$Port=8083,
    [string]$JavaHome='D:\tools\jdk21\jdk-21.0.12.1+1',
    [string]$JarPath='',
    [string]$Marker='',
    [int]$ReadyTimeoutSeconds=60
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'offline-engine-common.ps1')
$startOwnsSpec=$false
$startPaths=$null
try {
    Assert-OfflinePort $Port
    if($ReadyTimeoutSeconds -lt 5 -or $ReadyTimeoutSeconds -gt 120) {throw 'OFFLINE_TIMEOUT_INVALID'}
    if([string]::IsNullOrWhiteSpace($JarPath)) {$JarPath=Join-Path (Split-Path -Parent $PSScriptRoot) 'target\aiflow-engine-0.1.0-SNAPSHOT.jar'}
    if($Action -in @('Status','Stop') -and [string]::IsNullOrWhiteSpace($Marker)) {throw 'OFFLINE_MARKER_REQUIRED'}
    if(-not [string]::IsNullOrWhiteSpace($Marker)) {Assert-OfflineMarker $Marker}
    if($Action -eq 'Check') {
        $check=Get-OfflinePrerequisites -JavaHome $JavaHome -JarPath $JarPath -Port $Port
        [pscustomobject]@{action='Check';passed=$check.passed;port=$Port;checks=$check.checks}|ConvertTo-Json -Depth 6
        if(-not $check.passed) {exit 1};exit 0
    }
    if($Action -in @('Status','Stop')) {
        $paths=Get-OfflinePaths $Marker
        $instance=Read-OfflineJson $paths.instance
        if($instance.marker -cne $Marker) {throw 'OFFLINE_DESCRIPTOR_INVALID'}
        $identity=Test-OfflineInstanceIdentity $instance
        if($Action -eq 'Status') {
            $ready=$null
            $statusReason=$identity.reason
            if($identity.verified) {
                $listenerBefore=Test-OfflineListenerOwnership -Port $instance.port -ExpectedPid $instance.pid
                $ready=Get-OfflineReadiness $instance.port
                $listenerAfter=Test-OfflineListenerOwnership -Port $instance.port -ExpectedPid $instance.pid
                $listenerOwned=$listenerBefore -and $listenerAfter
                $ready.checks=@($ready.checks)+[pscustomobject]@{name='listener-owned-by-instance';passed=$listenerOwned}
                if(-not $listenerOwned) {$ready.passed=$false;$statusReason='OFFLINE_LISTENER_UNVERIFIED'}
            }
            [pscustomobject]@{action='Status';marker=$Marker;port=$instance.port;pid=$instance.pid;state=$identity.state;
                identityVerified=$identity.verified;readiness=$ready;reason=$statusReason}|ConvertTo-Json -Depth 7
            if($identity.state -eq 'STOPPED' -or ($identity.verified -and $ready.passed)) {exit 0};exit 1
        }
        $stopResult=Stop-OfflineOwnedInstance $instance
        if(-not $stopResult.stopped) {throw $stopResult.reason}
        if(-not (Test-Path -LiteralPath $paths.stopped)) {
            Write-OfflineNewJson $paths.stopped ([pscustomobject]@{schemaVersion=1;marker=$Marker;pid=$instance.pid;state='STOPPED';atUtc=[DateTime]::UtcNow.ToString('o')})
        }
        [pscustomobject]@{action='Stop';marker=$Marker;pid=$instance.pid;state='STOPPED';passed=$true}|ConvertTo-Json
        exit 0
    }
    if([string]::IsNullOrWhiteSpace($Marker)) {$Marker='offline-'+[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8)}
    $paths=Get-OfflinePaths $Marker
    $check=Get-OfflinePrerequisites -JavaHome $JavaHome -JarPath $JarPath -Port $Port
    if(-not $check.passed) {
        [pscustomobject]@{action='Start';marker=$Marker;passed=$false;reason='OFFLINE_PREREQUISITES_FAILED';checks=$check.checks}|ConvertTo-Json -Depth 6
        exit 1
    }
    $spec=New-OfflineLaunchSpec -JavaHome $JavaHome -JarPath $JarPath -Port $Port -Marker $Marker
    [IO.Directory]::CreateDirectory($paths.ops)|Out-Null
    foreach($path in @($paths.spec,$paths.instance,$paths.stdout,$paths.stderr,$paths.workerStdout,$paths.workerStderr,$paths.stopped)) {
        if(Test-Path -LiteralPath $path) {throw 'OFFLINE_MARKER_ALREADY_USED'}
    }
    Write-OfflineNewJson $paths.spec $spec
    $startOwnsSpec=$true
    $startPaths=$paths
    $shell=Join-Path $PSHOME 'powershell.exe'
    if(-not (Test-Path -LiteralPath $shell)) {$shell=Join-Path $PSHOME 'pwsh.exe'}
    if(-not (Test-Path -LiteralPath $shell)) {throw 'OFFLINE_SHELL_UNAVAILABLE'}
    $worker=Join-Path $PSScriptRoot 'offline-engine-worker.ps1'
    $workerArgs=@('-NoLogo','-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',$worker,'-SpecPath',$paths.spec)
    # Give the short-lived worker its own pipes instead of inheriting the caller's output handles.
    # The worker's Java stdout/stderr are separate log files.
    $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=$shell;$info.UseShellExecute=$false;$info.CreateNoWindow=$true
    $info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true;$info.RedirectStandardInput=$true
    $info.WorkingDirectory=$paths.root
    $info.Arguments=(@($workerArgs|ForEach-Object {ConvertTo-OfflineWindowsArgument $_}) -join ' ')
    $info.EnvironmentVariables.Clear()
    foreach($entry in (Get-OfflineEnvironment).GetEnumerator()) {$info.EnvironmentVariables[$entry.Key]=$entry.Value}
    $workerProcess=[Diagnostics.Process]::Start($info)
    $workerProcess.StandardInput.Close()
    try {
        if(-not $workerProcess.WaitForExit(15000)) {throw 'OFFLINE_WORKER_DID_NOT_EXIT'}
        $workerExitCode=$workerProcess.ExitCode
    } finally {
        # Java may inherit an unused duplicate of the worker's pipes on PS 5.1. Its actual
        # output goes to its own files; do not wait for EOF on those inherited pipe handles.
        $workerProcess.StandardOutput.Dispose()
        $workerProcess.StandardError.Dispose()
        $workerProcess.Dispose()
    }
    # The worker emits only a short fixed code. Suppress shell text and keep the exit status.
    Write-OfflineNewJson $paths.workerStdout ([pscustomobject]@{schemaVersion=1;outputCaptured=$false;exitCode=$workerExitCode})
    Write-OfflineNewJson $paths.workerStderr ([pscustomobject]@{schemaVersion=1;outputCaptured=$false;exitCode=$workerExitCode})
    if($workerExitCode -ne 0) {throw 'OFFLINE_WORKER_FAILED'}
    $deadline=[DateTime]::UtcNow.AddSeconds($ReadyTimeoutSeconds)
    $ready=$null;$instance=$null
    do {
        if(Test-Path -LiteralPath $paths.instance) {
            try {
                $instance=Read-OfflineJson $paths.instance
                $identity=Test-OfflineInstanceIdentity $instance
                if($identity.state -eq 'STOPPED') {break}
                if($identity.verified) {
                    $listenerBefore=Test-OfflineListenerOwnership -Port $Port -ExpectedPid $instance.pid
                    $ready=Get-OfflineReadiness $Port
                    $listenerAfter=Test-OfflineListenerOwnership -Port $Port -ExpectedPid $instance.pid
                    $listenerOwned=$listenerBefore -and $listenerAfter
                    $ready.checks=@($ready.checks)+[pscustomobject]@{name='listener-owned-by-instance';passed=$listenerOwned}
                    if(-not $listenerOwned) {$ready.passed=$false}
                    if($ready.passed){break}
                }
            } catch { }
        } else {break}
        Start-Sleep -Milliseconds 250
    } while([DateTime]::UtcNow -lt $deadline)
    if($null -eq $ready -or -not $ready.passed) {
        $stopped=$false
        if($null -ne $instance) {
            $stopResult=Stop-OfflineOwnedInstance $instance
            $stopped=$stopResult.stopped
        }
        [pscustomobject]@{action='Start';marker=$Marker;passed=$false;reason='OFFLINE_READINESS_FAILED';ownedProcessStopped=$stopped;readiness=$ready}|ConvertTo-Json -Depth 7
        exit 1
    }
    [pscustomobject]@{action='Start';marker=$Marker;passed=$true;port=$Port;pid=$instance.pid;state='RUNNING';
        instance=$paths.instance;jarSha256=$spec.jarSha256;readiness=$ready}|ConvertTo-Json -Depth 7
    exit 0
} catch {
    $reason='OFFLINE_OPERATION_FAILED'
    if($_.Exception.Message -cmatch '^OFFLINE_[A-Z0-9_]+$') {$reason=$_.Exception.Message}
    $ownedProcessStopped=$false
    if($Action -eq 'Start' -and $startOwnsSpec -and $null -ne $startPaths) {
        try {
            if(Test-Path -LiteralPath $startPaths.instance -PathType Leaf) {
                $failedInstance=Read-OfflineJson $startPaths.instance
                if($failedInstance.marker -ceq $Marker) {
                    $stopResult=Stop-OfflineOwnedInstance $failedInstance
                    $ownedProcessStopped=$stopResult.stopped
                }
            }
        } catch { }
    }
    [pscustomobject]@{action=$Action;passed=$false;reason=$reason;ownedProcessStopped=$ownedProcessStopped}|ConvertTo-Json
    exit 1
}
