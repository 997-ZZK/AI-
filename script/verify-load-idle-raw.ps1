<#
.SYNOPSIS
  Ten independent raw curl SSE clients on the dedicated mock-only M4-3C engine.
.NOTES
  Reads only the existing, verified s3-different-smoke-runs.csv. No run is created.
  Each ready runspace starts one curl after the shared start gate, using HTTP/1.1,
  Last-Event-ID and a 95 second overall timeout. curlrc, redirects and retries are
  disabled. Headers and event bodies are parsed in memory and never saved.
  Exit 28 at the deadline is expected; early EOF is a failure. Every client must
  confirm at least four empty PING frames spanning at least 60 seconds.
  Reports go into a new target/m4-3c subdirectory, including on runtime failure.
  Cleanup touches only the curl Process objects started by this script.
  Save with UTF-8 BOM; compatible with Windows PowerShell 5.1.
#>
param(
    [string]$BaseUrl = 'http://localhost:8082',
    [string]$Prefix = ('m43c-raw10-' + [Guid]::NewGuid().ToString('N').Substring(0,24))
)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$reportRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'target/m4-3c'))
$validPrefix = $Prefix -cmatch '^[A-Za-z0-9-]{1,40}$'
$safePrefix = if ($validPrefix) {$Prefix} else {'invalid-' + [Guid]::NewGuid().ToString('N')}
$outputRoot = Join-Path $reportRoot $safePrefix
$outputAlreadyExists = Test-Path -LiteralPath $outputRoot
if ($outputAlreadyExists) {$outputRoot = Join-Path $reportRoot ('invalid-' + [Guid]::NewGuid().ToString('N'))}
$inputPath = Join-Path $reportRoot 's3-different-smoke-runs.csv'
foreach ($candidate in @($outputRoot,$inputPath)) {
    $pathToCheck = $candidate
    while ($pathToCheck -and $pathToCheck.StartsWith($repoRoot,[StringComparison]::OrdinalIgnoreCase)) {
        if ((Test-Path -LiteralPath $pathToCheck) -and
            (((Get-Item -LiteralPath $pathToCheck -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)) {
            throw 'Raw10 paths must not traverse a junction or symbolic link.'
        }
        if ($pathToCheck -eq $repoRoot) {break}
        $pathToCheck = Split-Path -Parent $pathToCheck
    }
}
[IO.Directory]::CreateDirectory($outputRoot) | Out-Null
$reportPath = Join-Path $outputRoot 'raw10-report.json'
$startedAt = [DateTime]::UtcNow
$phase = 'parameters'
$failed = $false
$failureKind = $null
$origin = $null
$inputSha256 = $null
$readyGate = $null
$startGate = $null
$cancel = $null
$pool = $null
$readyPassed = $false
$jobs = New-Object System.Collections.Generic.List[object]
$clients = New-Object System.Collections.Generic.List[object]
$ownedProcesses = New-Object 'System.Collections.Concurrent.ConcurrentDictionary[int,System.Diagnostics.Process]'

$clientCode = {
    param([int]$Index,[string]$Origin,[string]$RunId,[long]$LastSeq,[string]$CurlPath,
        [System.Threading.CountdownEvent]$Ready,[System.Threading.ManualResetEventSlim]$Start,
        [System.Threading.CancellationTokenSource]$Cancel,
        [System.Collections.Concurrent.ConcurrentDictionary[int,System.Diagnostics.Process]]$Owned)
    $ErrorActionPreference = 'Stop'
    $result = [ordered]@{
        client=$Index;runId=$RunId;lastSeq=$LastSeq;requestCount=0;curlPid=$null
        startedUtc=$null;endedUtc=$null;durationMs=$null;exitCode=$null;killed=$false
        httpStatus=$null;contentType=$null;headerBlockCount=0
        connectCount=$null;redirectCount=$null;localPort=$null;metadataCount=0
        pingCount=0;firstPingUtc=$null;lastPingUtc=$null;observedMs=$null
        unexpectedFrames=0;stderrPresent=$false;passed=$false;failureKind=$null
    }
    $process = $null
    $stderrTask = $null
    $watch = [Diagnostics.Stopwatch]::new()
    $firstPingMs = $null
    $lastPingMs = $null
    $inHeaders = $false
    $headersComplete = $false
    $eventName = $null
    $dataCount = 0
    $emptyData = $false
    $frameInvalid = $false
    $hasFrame = $false
    try {
        [void]$Ready.Signal()
        if (-not $Start.Wait(30000,$Cancel.Token)) {
            $result.failureKind = 'start_gate_timeout'
            throw 'Client start gate timed out.'
        }
        $Cancel.Token.ThrowIfCancellationRequested()
        $info = [Diagnostics.ProcessStartInfo]::new()
        $info.FileName = $CurlPath
        # All substituted values were strictly validated before opening runspaces.
        # ProcessStartInfo starts curl directly; no shell interprets these arguments.
        $info.Arguments = '--disable --silent --show-error --no-buffer --http1.1 --noproxy "*" --max-time 95 --dump-header - --header "Accept: text/event-stream" --header "Last-Event-ID: ' + $LastSeq + '" --write-out "\nRAW_CURL_META|%{num_connects}|%{num_redirects}|%{local_port}|%{http_code}\n" "' + $Origin + '/api/v1/runs/' + $RunId + '/events"'
        $info.UseShellExecute = $false
        $info.CreateNoWindow = $true
        $info.RedirectStandardOutput = $true
        $info.RedirectStandardError = $true
        $process = [Diagnostics.Process]::new()
        $process.StartInfo = $info
        $watch.Start()
        if (-not $process.Start()) {throw 'curl process did not start.'}
        $result.requestCount = 1
        $result.curlPid = $process.Id
        $result.startedUtc = [DateTime]::UtcNow.ToString('o')
        if (-not $Owned.TryAdd($Index,$process)) {throw 'Owned process registration failed.'}
        # Covers cancellation racing with Process.Start and registration.
        $Cancel.Token.ThrowIfCancellationRequested()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        while ($null -ne ($line = $process.StandardOutput.ReadLine())) {
            if ($line -match '^RAW_CURL_META\|([0-9]+)\|([0-9]+)\|([0-9]+)\|([0-9]{3})$') {
                $result.metadataCount++
                $result.connectCount = [int]$Matches[1]
                $result.redirectCount = [int]$Matches[2]
                $result.localPort = [int]$Matches[3]
                if ($result.httpStatus -ne [int]$Matches[4]) {$result.unexpectedFrames++}
                continue
            }
            if ($line -match '^HTTP/1\.[01] ([0-9]{3})(?: |$)') {
                $result.headerBlockCount++
                $result.httpStatus = [int]$Matches[1]
                $inHeaders = $true
                $headersComplete = $false
                continue
            }
            if ($inHeaders) {
                if ($line.Length -eq 0) {
                    $inHeaders = $false
                    $headersComplete = $true
                } elseif ($line -match '(?i)^Content-Type\s*:\s*(.+)$') {
                    $media = ($Matches[1] -split ';',2)[0].Trim().ToLowerInvariant()
                    $result.contentType = if ($media -eq 'text/event-stream') {$media} else {'(other)'}
                }
                continue
            }
            if (-not $headersComplete) {
                if ($line.Length -gt 0) {$result.unexpectedFrames++}
                continue
            }
            if ($line.Length -eq 0) {
                if ($hasFrame) {
                    if ($eventName -ceq 'PING' -and $dataCount -eq 1 -and $emptyData -and -not $frameInvalid) {
                        $pingMs = $watch.Elapsed.TotalMilliseconds
                        $pingUtc = [DateTime]::UtcNow.ToString('o')
                        if ($result.pingCount -eq 0) {
                            $firstPingMs = $pingMs
                            $result.firstPingUtc = $pingUtc
                        }
                        $lastPingMs = $pingMs
                        $result.lastPingUtc = $pingUtc
                        $result.pingCount++
                    } else {$result.unexpectedFrames++}
                }
                $eventName=$null;$dataCount=0;$emptyData=$false;$frameInvalid=$false;$hasFrame=$false
            } elseif ($line.StartsWith(':')) {
                # SSE comments are not heartbeat evidence.
                continue
            } elseif ($line -match '^event: ?(.*)$') {
                if ($null -ne $eventName) {$frameInvalid=$true}
                $eventName=$Matches[1];$hasFrame=$true
            } elseif ($line -match '^data: ?(.*)$') {
                $dataCount++
                $emptyData = $Matches[1] -match '^\{\s*\}$'
                $hasFrame=$true
            } else {
                $frameInvalid=$true;$hasFrame=$true
            }
        }
        if ($hasFrame) {$result.unexpectedFrames++}
        $process.WaitForExit()
        $result.exitCode = $process.ExitCode
        if ($null -ne $stderrTask) {$result.stderrPresent = $stderrTask.GetAwaiter().GetResult().Length -gt 0}
        $result.durationMs = [Math]::Round($watch.Elapsed.TotalMilliseconds,3)
        if ($null -ne $firstPingMs) {$result.observedMs = [Math]::Round($lastPingMs-$firstPingMs,3)}
        if ($result.httpStatus -ne 200 -or $result.contentType -ne 'text/event-stream' -or $result.headerBlockCount -ne 1) {
            $result.failureKind='http_contract'
        } elseif ($result.metadataCount -ne 1 -or $result.connectCount -ne 1 -or $result.redirectCount -ne 0 -or
            $null -eq $result.localPort -or $result.localPort -lt 1 -or $result.localPort -gt 65535) {
            $result.failureKind='curl_metadata'
        } elseif ($result.exitCode -ne 28 -or $result.durationMs -lt 94000) {
            $result.failureKind='early_or_unexpected_close'
        } elseif ($result.unexpectedFrames -ne 0) {
            $result.failureKind='unexpected_sse_frame'
        } elseif ($result.pingCount -lt 4 -or $null -eq $result.observedMs -or $result.observedMs -lt 60000) {
            $result.failureKind='ping_evidence'
        } else {$result.passed=$true}
    } catch {
        if ($null -eq $result.failureKind) {$result.failureKind='client_exception'}
        # Exception text and curl stderr may include response details: never persist.
        $result.passed=$false
    } finally {
        if ($null -ne $process) {
            try {
                if ($result.requestCount -eq 1 -and -not $process.HasExited) {
                    $process.Kill();$result.killed=$true;$result.passed=$false
                    if ($null -eq $result.failureKind) {$result.failureKind='owned_process_killed'}
                    [void]$process.WaitForExit(5000)
                }
                if ($result.requestCount -eq 1 -and $process.HasExited -and $null -eq $result.exitCode) {
                    $result.exitCode=$process.ExitCode
                }
            } catch {$result.passed=$false;if ($null -eq $result.failureKind) {$result.failureKind='cleanup_exception'}}
            [Diagnostics.Process]$removedProcess=$null
            [void]$Owned.TryRemove($Index,[ref]$removedProcess)
            $process.Dispose()
        }
        $watch.Stop()
        if ($result.requestCount -eq 1 -and $null -eq $result.durationMs) {$result.durationMs=[Math]::Round($watch.Elapsed.TotalMilliseconds,3)}
        if ($null -ne $firstPingMs -and $null -eq $result.observedMs) {$result.observedMs=[Math]::Round($lastPingMs-$firstPingMs,3)}
        $result.endedUtc=[DateTime]::UtcNow.ToString('o')
    }
    [pscustomobject]$result
}

try {
    if (-not $validPrefix -or $outputAlreadyExists) {throw 'A valid, unused output Prefix is required.'}
    if ($BaseUrl -cnotmatch '^http://(?:localhost|127\.0\.0\.1):8082/?$') {throw 'Only the dedicated local mock port 8082 is permitted.'}
    $origin = $BaseUrl.TrimEnd('/')
    $phase = 'input'
    if (-not (Test-Path -LiteralPath $inputPath -PathType Leaf)) {throw 'Verified ten-run CSV is missing.'}
    $inputLines = [IO.File]::ReadAllLines($inputPath)
    if ($inputLines.Count -lt 1 -or $inputLines[0] -cne 'runId,lastSeq') {throw 'CSV header must be runId,lastSeq.'}
    $runs = @(Import-Csv -LiteralPath $inputPath)
    if ($runs.Count -ne 10) {throw 'CSV must contain exactly ten runs.'}
    $seen = New-Object 'System.Collections.Generic.HashSet[string]'
    foreach ($run in $runs) {
        [long]$seq=0
        if ($run.runId -cnotmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' -or
            -not $seen.Add([string]$run.runId) -or $run.lastSeq -cnotmatch '^[1-9][0-9]{0,17}$' -or
            -not [long]::TryParse($run.lastSeq,[ref]$seq) -or $seq -le 0) {throw 'CSV run identity or sequence is invalid.'}
    }
    $sha = [Security.Cryptography.SHA256]::Create()
    try {$inputSha256 = ([BitConverter]::ToString($sha.ComputeHash([IO.File]::ReadAllBytes($inputPath)))).Replace('-','').ToLowerInvariant()}
    finally {$sha.Dispose()}
    $curlPath = (Get-Command curl.exe -CommandType Application -ErrorAction Stop).Source
    if (-not [IO.Path]::IsPathRooted($curlPath)) {throw 'curl must resolve to a local executable.'}
    $phase='ready'
    $readyGate=[Threading.CountdownEvent]::new(10)
    $startGate=[Threading.ManualResetEventSlim]::new($false)
    $cancel=[Threading.CancellationTokenSource]::new()
    $pool=[RunspaceFactory]::CreateRunspacePool(10,10)
    $pool.Open()
    for ($i=0;$i -lt $runs.Count;$i++) {
        $ps=[PowerShell]::Create()
        $ps.RunspacePool=$pool
        [void]$ps.AddScript($clientCode.ToString()).AddArgument($i+1).AddArgument($origin).AddArgument([string]$runs[$i].runId).AddArgument([long]$runs[$i].lastSeq).AddArgument($curlPath).AddArgument($readyGate).AddArgument($startGate).AddArgument($cancel).AddArgument($ownedProcesses)
        $handle=$ps.BeginInvoke()
        $jobs.Add([pscustomobject]@{client=$i+1;run=$runs[$i];ps=$ps;handle=$handle;collected=$false})
    }
    if (-not $readyGate.Wait(15000)) {throw 'Ten-client readiness barrier timed out.'}
    $readyPassed=$true
    $phase='stream'
    $runWatch=[Diagnostics.Stopwatch]::StartNew()
    $startGate.Set()
    while (@($jobs | Where-Object {-not $_.handle.IsCompleted}).Count -gt 0) {
        if ($runWatch.Elapsed.TotalSeconds -gt 110) {throw 'Raw10 clients exceeded the deadline.'}
        Start-Sleep -Milliseconds 100
    }
    $phase='results'
    foreach ($job in $jobs) {
        $row=@($job.ps.EndInvoke($job.handle))
        $job.collected=$true
        if ($row.Count -ne 1 -or $job.ps.HadErrors) {throw 'Client did not return one clean result.'}
        $clients.Add($row[0])
    }
    if ($clients.Count -ne 10 -or @($clients | Where-Object {-not $_.passed}).Count -gt 0) {throw 'At least one raw10 client failed.'}
    $phase='complete'
} catch {
    $failed=$true
    $failureKind=$phase + '_failed'
} finally {
    if ($null -ne $cancel) {$cancel.Cancel()}
    if ($null -ne $startGate) {$startGate.Set()}
    foreach ($entry in $ownedProcesses.ToArray()) {
        try {if (-not $entry.Value.HasExited) {$entry.Value.Kill()}}
        catch {$failed=$true;if ($null -eq $failureKind) {$failureKind='owned_cleanup_failed'}}
    }
    foreach ($job in $jobs) {
        if (-not $job.collected) {
            try {
                if (-not $job.handle.IsCompleted) {$job.ps.Stop()}
                $rows=@($job.ps.EndInvoke($job.handle))
                if ($rows.Count -eq 1 -and -not $job.ps.HadErrors) {$clients.Add($rows[0]);$job.collected=$true}
            } catch {}
            if (-not $job.collected) {
                $clients.Add([pscustomobject]@{client=$job.client;runId=$job.run.runId;lastSeq=[long]$job.run.lastSeq;passed=$false;failureKind='client_incomplete'})
            }
        }
        $job.ps.Dispose()
    }
    if ($null -ne $pool) {$pool.Close();$pool.Dispose()}
    if ($null -ne $readyGate) {$readyGate.Dispose()}
    if ($null -ne $startGate) {$startGate.Dispose()}
    if ($null -ne $cancel) {$cancel.Dispose()}
    $orderedClients=@($clients | Sort-Object client)
    $passed= -not $failed -and $readyPassed -and $orderedClients.Count -eq 10 -and @($orderedClients | Where-Object {-not $_.passed}).Count -eq 0
    $report=[ordered]@{
        schemaVersion=1;scenario='raw10';topology='different-runs';scope='10-client diagnostic only'
        startedUtc=$startedAt.ToString('o');endedUtc=[DateTime]::UtcNow.ToString('o')
        baseUrl=$origin;inputFile=$inputPath;inputSha256=$inputSha256;expectedClients=10
        readinessBarrierPassed=$readyPassed;maxTimeSeconds=95;minimumPings=4;minimumObservedMs=60000
        httpVersion='HTTP/1.1';retries=0;redirects=0;curlrcEnabled=$false;secretsRecorded=$false;bodiesRecorded=$false
        passed=$passed;failureKind=$failureKind;clients=$orderedClients
    }
    [IO.File]::WriteAllText($reportPath,($report | ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
}
Write-Output "Report: $reportPath"
if (-not $passed) {exit 1}
