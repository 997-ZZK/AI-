param(
    [string]$Maven = "",
    [string]$JavaHome = ""
)

$ErrorActionPreference = "Stop"
$resolvedJavaHome = $JavaHome
if ([string]::IsNullOrWhiteSpace($resolvedJavaHome)) {
    $resolvedJavaHome = $env:JAVA_HOME
}
if ([string]::IsNullOrWhiteSpace($resolvedJavaHome)) {
    # Use the documented local JDK when the interactive shell does not export
    # JAVA_HOME. This only affects the child Maven process.
    $javaHomeCandidate = "D:\tools\jdk21\jdk-21.0.12.1+1"
    if (Test-Path -LiteralPath (Join-Path $javaHomeCandidate "bin\java.exe")) {
        $resolvedJavaHome = $javaHomeCandidate
    }
}
if (-not [string]::IsNullOrWhiteSpace($resolvedJavaHome)) {
    $env:JAVA_HOME = $resolvedJavaHome
    $env:Path = (Join-Path $resolvedJavaHome "bin") + ";" + $env:Path
}
$resolvedMaven = $Maven
if ([string]::IsNullOrWhiteSpace($resolvedMaven)) {
    # PowerShell invocation through & does not consistently resolve a bare
    # `mvn` to mvn.cmd. Resolve the executable explicitly for Windows while
    # keeping the script usable with a native mvn on other hosts.
    $command = Get-Command "mvn.cmd" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $command) {
        $command = Get-Command "mvn" -ErrorAction SilentlyContinue | Select-Object -First 1
    }
    if ($null -ne $command) {
        # ApplicationInfo exposes Path on PowerShell 7 and Source on older
        # Windows PowerShell versions. Prefer the fully resolved path.
        $resolvedMaven = if (-not [string]::IsNullOrWhiteSpace($command.Path)) {
            $command.Path
        } elseif (-not [string]::IsNullOrWhiteSpace($command.Source)) {
            $command.Source
        } else {
            $command.Definition
        }
    }

    if ([string]::IsNullOrWhiteSpace($resolvedMaven)) {
        # CI and developer shells commonly expose Maven through MAVEN_HOME or
        # M2_HOME without adding its bin directory to PATH. Resolve those
        # explicit installations before asking the caller for -Maven.
        $homeCandidates = @($env:MAVEN_HOME, $env:M2_HOME) |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
            ForEach-Object { Join-Path $_ "bin\mvn.cmd" }
        $workspaceToolRoot = Join-Path ([IO.Path]::GetPathRoot($PSScriptRoot)) "tools\maven"
        $homeCandidates += Get-ChildItem -Path $workspaceToolRoot -Filter "mvn.cmd" -Recurse -File -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty FullName
        $resolvedMaven = $homeCandidates |
            Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
            Select-Object -First 1
    }

    if ([string]::IsNullOrWhiteSpace($resolvedMaven)) {
        throw "找不到 Maven：请把 Maven 的 bin 加入 PATH，或使用 -Maven 指定 mvn.cmd 路径"
    }
}
$repo = Join-Path $env:USERPROFILE ".m2\repository"
$settings = Join-Path $PSScriptRoot "maven-settings.xml"
$tests = "OfflineProfileContractTest,OfflineProfileIntegrationTest"

Write-Host "M5-A offline profile checks: running $tests (offline Maven mode)"
& $resolvedMaven "-s" $settings "-o" "-Dmaven.repo.local=$repo" `
    "-Dmaven.compiler.fork=true" "-Dmaven.compiler.forceJavacCompilerUse=true" "-Dtest=$tests" test
if ($LASTEXITCODE -ne 0) {
    throw "M5-A offline profile checks failed with exit code $LASTEXITCODE"
}

Write-Host "M5-A offline profile checks: PASS"
