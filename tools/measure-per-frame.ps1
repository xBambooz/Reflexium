param(
    [string]$Label = 'baseline',
    [int]$Port = 25601,
    [int]$Seconds = 5,
    [int]$Passes = 2,
	[int]$WarmupSeconds = 2,
	[float]$Pitch = 0,
	[ValidateSet('current','legacy','optimized')][string]$Paths = 'current',
    [switch]$Profile,
    [string[]]$Modes = @('native','cnn-native_aa','cnn-quality','cnn-balanced','cnn-performance','cnn-ultra_performance','transformer-native_aa','transformer-quality','transformer-balanced','transformer-performance','transformer-ultra_performance','fsr-native_aa','fsr-quality','fsr-balanced','fsr-performance','fsr-ultra_performance')
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$session = Get-Content "C:\Users\Ryan\source\repos\mc-agent-harness\instances\$Port\session.json" -Raw | ConvertFrom-Json
$headers = @{ Authorization = "Bearer $($session.token)" }
function Call-Harness([string]$Endpoint, [hashtable]$Data = @{}) {
    $r = Invoke-RestMethod "http://127.0.0.1:$Port/$Endpoint" -Method Post -Headers $headers -ContentType application/json -Body ($Data | ConvertTo-Json -Compress) -TimeoutSec 120
    if ($r.ok -eq $false) { throw ($r | ConvertTo-Json -Compress) }
    return $r
}
function Set-Rx([string]$Key, [string]$Value) { Call-Harness reflexium @{key=$Key;value=$Value} | Out-Null }
function Stop-TestClient {
    $s = Call-Harness status
    if ($s.pid -ne $session.game_pid -or $s.game_directory -ne $session.game_directory) { throw 'Shutdown ownership mismatch' }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($session.game_pid)"
    $line = $process.CommandLine.Replace('\','/').ToLowerInvariant()
    if (!$line.Contains($session.jar.Replace('\','/').ToLowerInvariant()) -or !$line.Contains($session.game_directory.Replace('\','/').ToLowerInvariant()) -or !$line.Contains('-dfabric.addmods=')) { throw 'Not the recorded harness JVM' }
    $s = Call-Harness status
    if ($s.pid -ne $session.game_pid -or $s.game_directory -ne $session.game_directory) { throw 'Shutdown process changed' }
    Call-Harness quit @{exit=$true} | Out-Null
}
function Measure-Modes {
    $outDir = Join-Path $root 'docs\benchmarks\per-frame'
    New-Item -ItemType Directory -Force $outDir | Out-Null
    $status = Call-Harness status
    if (!$status.world_loaded -or $status.pid -ne $session.game_pid) { throw 'Expected recorded client with a loaded world' }
    Set-Rx frame_generation off
	if ($Paths -ne 'current') {
		foreach ($key in 'perf_uniforms','perf_depth') { Set-Rx $key $(if ($Paths -eq 'legacy') {'0'} else {'1'}) }
	}
    Call-Harness camera @{x=-25.5;y=69;z=23.5;yaw=0;pitch=$Pitch} | Out-Null
    for ($pass=1; $pass -le $Passes; $pass++) {
        $order = @($Modes)
        if ($pass % 2 -eq 0) { [array]::Reverse($order) }
        foreach ($mode in $order) {
            $parts = $mode.Split('-',2)
			if ($Paths -ne 'current') {
				Set-Rx dlss_preset $(if ($parts[0] -eq 'cnn') {'5'} else {'-1'})
				Set-Rx perf_uniforms $(if ($Paths -eq 'optimized' -and $parts[0] -ne 'fsr') {'1'} else {'0'})
				Set-Rx perf_depth $(if ($Paths -eq 'optimized' -and $parts[0] -ne 'fsr') {'1'} else {'0'})
			}
            if ($mode -eq 'native') { Set-Rx upscaler off }
            else {
                Set-Rx upscaler $(if ($parts[0] -eq 'fsr') {'fsr'} else {'dlss'})
                Set-Rx quality $parts[1]
                if ($parts[0] -ne 'fsr') { Set-Rx dlss_model $parts[0] }
            }
            Start-Sleep -Seconds $WarmupSeconds
            $before = (Call-Harness status).plugins.reflexium
            if ($mode -ne 'native' -and !$before.upscaling_active) { throw "Inactive $mode : $($before.status_line)" }
			if ($mode -ne 'native' -and $before.quality -ne $parts[1]) { throw "Incorrect quality for $mode" }
            if ($Profile) { Call-Harness reflexium @{profile='reset'} | Out-Null }
            $mark = (Call-Harness reflexium @{frame_mark='1'}).frames
            Start-Sleep -Seconds $Seconds
            $stats = Call-Harness reflexium @{frame_stats_from="$mark"}
            $timings = if ($Profile) { Call-Harness reflexium @{profile='off'} } else { $null }
            $after = (Call-Harness status).plugins.reflexium
            if ($mode -ne 'native' -and $after.evaluated_frames -le $before.evaluated_frames) { throw "No evaluations for $mode" }
            $row = [ordered]@{label=$Label;mode=$mode;pass=$pass;utc=(Get-Date).ToUniversalTime().ToString('o');pid=$session.game_pid;stats=$stats;profile=$timings;status=$after}
            $json = $row | ConvertTo-Json -Depth 9 -Compress
            [IO.File]::AppendAllText((Join-Path $outDir "$Label.jsonl"), $json + "`n", [Text.UTF8Encoding]::new($false))
            Write-Host "$Label pass $pass $mode : $($stats.avg_fps) FPS, $($stats.mean_ms) ms"
        }
    }
}
if ($MyInvocation.InvocationName -ne '.') { Measure-Modes }
