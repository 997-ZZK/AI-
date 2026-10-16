param([Parameter(Mandatory=$true)][string]$SpecPath,[switch]$Detached)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'offline-engine-common.ps1')
$process=$null
$processStartUtc=$null
try {
    $spec=Read-OfflineJson $SpecPath
    Assert-OfflineLaunchSpec $spec
    $paths=Get-OfflinePaths $spec.marker
    if(-not ([IO.Path]::GetFullPath($SpecPath)).Equals($paths.spec,[StringComparison]::OrdinalIgnoreCase) -or
        (Test-Path -LiteralPath $paths.instance) -or (Test-Path -LiteralPath $paths.stdout) -or (Test-Path -LiteralPath $paths.stderr)) {
        throw 'OFFLINE_OUTPUT_ALREADY_EXISTS_OR_UNWRITABLE'
    }
    # The normal caller already supplies a clean environment. Sanitize again for direct worker invocation.
    $allowedEnvironment=Get-OfflineEnvironment
    foreach($name in @([Environment]::GetEnvironmentVariables('Process').Keys)) {
        if(-not $allowedEnvironment.ContainsKey([string]$name)) {[Environment]::SetEnvironmentVariable([string]$name,$null,'Process')}
    }
    foreach($entry in $allowedEnvironment.GetEnumerator()) {[Environment]::SetEnvironmentVariable($entry.Key,$entry.Value,'Process')}
    if(-not $Detached) {
        # A shell launch cuts inherited handles, including nonstandard output pipes held by
        # terminal/tool hosts. This worker has already sanitized only its own environment;
        # ShellExecute inherits that whitelist without changing the caller's environment.
        $shell=Join-Path $PSHOME 'powershell.exe'
        if(-not (Test-Path -LiteralPath $shell)) {$shell=Join-Path $PSHOME 'pwsh.exe'}
        if(-not (Test-Path -LiteralPath $shell)) {throw 'OFFLINE_SHELL_UNAVAILABLE'}
        $detachedArgs=@('-NoLogo','-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass',
            '-File',$PSCommandPath,'-SpecPath',$paths.spec,'-Detached')
        $detachedInfo=[Diagnostics.ProcessStartInfo]::new()
        $detachedInfo.FileName=$shell;$detachedInfo.UseShellExecute=$true
        $detachedInfo.WindowStyle=[Diagnostics.ProcessWindowStyle]::Hidden
        $detachedInfo.WorkingDirectory=$paths.root
        $detachedInfo.Arguments=(@($detachedArgs|ForEach-Object {ConvertTo-OfflineWindowsArgument $_}) -join ' ')
        $detachedWorker=$null
        try {
            $detachedWorker=[Diagnostics.Process]::Start($detachedInfo)
            if($null -eq $detachedWorker -or -not $detachedWorker.WaitForExit(10000)) {throw 'OFFLINE_WORKER_DID_NOT_EXIT'}
            if($detachedWorker.ExitCode -ne 0) {throw 'OFFLINE_WORKER_FAILED'}
        } finally {if($null -ne $detachedWorker) {$detachedWorker.Dispose()}}
        exit 0
    }
    # The detached stage repeats validation and environment sanitation before Java.
    $arguments=Get-OfflineJavaArguments -JarPath $spec.jarPath -Port $spec.port -Marker $spec.marker -DataRoot (Join-Path $paths.ops $spec.marker)
    $argumentLine=(@($arguments | ForEach-Object {ConvertTo-OfflineWindowsArgument $_}) -join ' ')
    $process=Start-Process -FilePath $spec.javaPath -ArgumentList $argumentLine -WorkingDirectory $paths.root `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput $paths.stdout -RedirectStandardError $paths.stderr
    $null=$process.Handle
    $processStartUtc=$process.StartTime.ToUniversalTime()
    $instance=[pscustomobject][ordered]@{schemaVersion=1;marker=$spec.marker;port=$spec.port;pid=$process.Id;
        startTimeUtc=$processStartUtc.ToString('o');javaPath=$spec.javaPath;jarPath=$spec.jarPath;
        jarSha256=$spec.jarSha256;createdAtUtc=[DateTime]::UtcNow.ToString('o')}
    Write-OfflineNewJson $paths.instance $instance
    exit 0
} catch {
    $reason='OFFLINE_WORKER_FAILED'
    if($null -ne $process) {
        $reason='OFFLINE_WORKER_FAILED_PROCESS_UNCONFIRMED'
        try {
            if($process.HasExited) {$reason='OFFLINE_WORKER_FAILED_PROCESS_STOPPED'}
            elseif($null -ne $processStartUtc -and
                (Test-OfflineUtcInstantEqual $process.StartTime.ToUniversalTime() $processStartUtc) -and
                ([string]$process.Path).Equals($spec.javaPath,[StringComparison]::OrdinalIgnoreCase)) {
                # This retained handle belongs to the Java process created above, never a PID lookup.
                $process.Kill()
                if($process.WaitForExit(10000)) {$reason='OFFLINE_WORKER_FAILED_PROCESS_STOPPED'}
            }
        } catch { }
    }
    Write-Output $reason
    exit 1
} finally {if($null -ne $process) {$process.Dispose()}}
