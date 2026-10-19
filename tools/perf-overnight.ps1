param(
    [string]$Label = 'before',
    [int]$Port = 25631,
    [int]$Seconds = 4,
    [int]$Passes = 2,
    [string[]]$Scenes = @('shaderless','bsl'),
    [string[]]$Modes = @('native','cnn-quality','cnn-balanced','cnn-performance','transformer-quality','transformer-balanced','transformer-performance')
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'docs/benchmarks/perf-overnight-2026-10-19'
New-Item -ItemType Directory -Force $out | Out-Null
$session = Get-Content "C:/Users/Ryan/source/repos/mc-agent-harness/instances/$Port/session.json" -Raw | ConvertFrom-Json
$headers = @{Authorization="Bearer $($session.token)"}
function Call([string]$Endpoint, [hashtable]$Data = @{}) {
    $r = Invoke-RestMethod "http://127.0.0.1:$Port/$Endpoint" -Method Post -Headers $headers -ContentType application/json -Body ($Data | ConvertTo-Json -Compress) -TimeoutSec 150
    if ($r.ok -eq $false) { throw ($r | ConvertTo-Json -Compress) }
    return $r
}
function Set-Rx([string]$Key,[string]$Value) { Call reflexium @{key=$Key;value=$Value} | Out-Null }
function Other-Client {
    $games = @(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -in @('java.exe','javaw.exe') -and $_.ProcessId -ne $session.game_pid -and $_.CommandLine -match '-Dagentharness.launchDirectory='
    } | Select-Object -ExpandProperty ProcessId)
    try {
        $s = Get-Content 'C:/Users/Ryan/source/repos/mc-agent-harness/instances/25612/session.json' -Raw | ConvertFrom-Json
        $r = Invoke-RestMethod 'http://127.0.0.1:25612/status' -Headers @{Authorization="Bearer $($s.token)"} -TimeoutSec 2
        return @{running=$true;status_available=$true;pid=$r.pid;world_loaded=$r.world_loaded;other_game_pids=$games;utc=(Get-Date).ToUniversalTime().ToString('o')}
    } catch { return @{running=$false;status_available=$false;other_game_pids=$games;utc=(Get-Date).ToUniversalTime().ToString('o')} }
}
$s = Call status
if ($s.pid -ne $session.game_pid -or $s.game_directory -ne $session.game_directory) { throw 'Wrong client' }
if (!$s.world_loaded) { Call join @{world='PerfFrozen';timeout_s=120} | Out-Null }
Set-Rx frame_generation off
Set-Rx reflex_mode on
Set-Rx smart_reflex false
Set-Rx reflex_interval_us 0
Call command @{command='tick freeze'} | Out-Null
Call command @{command='gamemode spectator @a'} | Out-Null
Call command @{command='time set 6000'} | Out-Null
Call command @{command='weather clear'} | Out-Null
Call camera @{x=-199.5;y=110;z=-199.5;yaw=-45;pitch=15} | Out-Null
foreach ($scene in $Scenes) {
    Set-Rx upscaler off
    Set-Rx shaders $(if ($scene -eq 'bsl') {'true'} else {'false'})
    Start-Sleep -Seconds 8
    for ($pass=1; $pass -le $Passes; $pass++) {
        $order = @($Modes)
        if ($pass % 2 -eq 0) { [array]::Reverse($order) }
        foreach ($mode in $order) {
            $parts = $mode.Split('-',2)
            if ($mode -eq 'native') { Set-Rx upscaler off }
            else {
                Set-Rx dlss_model $parts[0]
                Set-Rx quality $parts[1]
                Set-Rx upscaler dlss
            }
            Start-Sleep -Seconds 4
            $before = Call status
            $rx = $before.plugins.reflexium
            if ($before.window.width -ne 2560 -or $before.window.height -ne 1440 -or $before.focus.os_minimized) { throw 'Window mismatch' }
            if ($rx.frame_generation -ne 'off' -or $rx.reflex_mode -ne 'ON' -or $rx.reflex_pacing_paused) { throw 'Pacing mismatch' }
            if ($mode -ne 'native' -and (!$rx.upscaling_active -or $rx.quality -ne $parts[1])) { throw 'Inactive upscaling' }
            foreach ($profile in @($false,$true)) {
                $other = Other-Client
                Call reflexium @{profile=$(if ($profile) {'reset'} else {'off'})} | Out-Null
                $mark = (Call reflexium @{frame_mark='1'}).frames
                Start-Sleep -Seconds $Seconds
                $stats = Call reflexium @{frame_stats_from="$mark"}
                $timings = Call reflexium @{profile='off'}
                $after = Call status
                foreach ($axis in @('x','y','z','yaw','pitch')) {
                    if ($before.camera.$axis -ne $after.camera.$axis) { throw "Camera moved: $axis" }
                }
                $row = [ordered]@{label=$Label;scene=$scene;mode=$mode;pass=$pass;instrumented=$profile;utc=(Get-Date).ToUniversalTime().ToString('o');pid=$s.pid;other_client=$other;stats=$stats;profile=$timings;before=$before;after=$after}
                [IO.File]::AppendAllText((Join-Path $out "$Label.jsonl"),($row | ConvertTo-Json -Depth 15 -Compress)+"`n",[Text.UTF8Encoding]::new($false))
                Write-Output "$Label $scene $mode pass=$pass profile=$profile fps=$($stats.avg_fps) world_us=$($timings.spans.world_including_upscaling.gpu_us) ngx_us=$($timings.spans.evaluate.gpu_us)"
            }
        }
    }
}
