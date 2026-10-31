param([int]$Port, [string]$Quality='ultra_performance', [int]$Multiplier=3, [string]$Label='mesh-ultra-3x')
$ErrorActionPreference='Stop'
. "$PSScriptRoot/iq-session.ps1" -Port $Port
$root=Split-Path $PSScriptRoot
$evidence="$root/docs/validation/overnight-c-2026-10-31/dither/$Label"
$raw="$root/run/overnight-c/dither/$Label"
New-Item -ItemType Directory -Force $evidence,$raw | Out-Null
$lock=Get-Content C:/Users/Ryan/source/repos/Reflexium-gpu.lock | ConvertFrom-Json
$s=Status
if($lock.owner -ne 'worker-C-overnight' -or $s.game_directory -ne "$root\run" -or !$s.window.fullscreen.borderless -or $s.window.fullscreen.exclusive_fullscreen) {throw 'C fullscreen session required'}
function Snapshot($name) {
    Status | ConvertTo-Json -Depth 40 | Set-Content "$evidence/$name.json"
    Call screenshot @{path="$raw/$name.png"} | Out-Null
}
function CaptureSeries($name,$count,$interval,$yaw=0,$ticks=100) {
    $r=Call capture_sequence @{dir="$raw/$name";count=$count;interval_ms=$interval;dyaw=$yaw;ticks=$ticks}
    $r | ConvertTo-Json -Depth 40 | Set-Content "$raw/$name.json"
    $r.frames | ForEach-Object { @{path=$_.path;camera=$_.camera;frames=$_.frames;reflexium=$_.plugins.reflexium} } | ConvertTo-Json -Depth 30 | Set-Content "$evidence/$name.json"
}
try {
    Setting frame_generation off
    Setting shaders 1
    Setting upscaler dlss
    Setting quality $Quality
    Call quit @{} | Out-Null
    Call join @{world='Reflexium Vitrail Smoke';timeout_s=120} | Out-Null
    foreach($cmd in @('gamemode spectator @a','tick freeze','time set 9000','weather clear','gamerule minecraft:advance_time false','fill 395 159 30 405 166 42 air','fill 395 159 30 405 159 42 smooth_stone','fill 397 160 34 403 164 34 smooth_sandstone','fill 397 160 35 397 164 40 smooth_sandstone','fill 397 165 34 400 165 38 smooth_sandstone')) { Call command @{command=$cmd} | Out-Null }
    if($Multiplier -gt 1) { Setting frame_generation dlss; Setting fg_multiplier $Multiplier }
    Call camera @{x=399.1;y=161.2;z=36.1;yaw=150;pitch=12} | Out-Null
    Call wait @{ms=10000} | Out-Null
    $s=Status
    if(!$s.plugins.reflexium.upscaling_active -or $s.plugins.reflexium.last_error) {throw 'Inactive/error SR'}
    Snapshot before
    $wgc="$root/tools/wgc-capture/bin/Release/net10.0-windows10.0.26100.0/WgcCapture.exe"
    & $wgc --pid $s.pid --out "$raw/wgc-static" --seconds 4 --count 4000 --crop 940,500,128,128 --min-interval-ms 0 | Set-Content "$evidence/wgc-result.json"
    CaptureSeries static 12 100
    CaptureSeries pan 24 250 4 100
    Call camera @{x=399.1;y=161.2;z=36.1;yaw=105;pitch=12} | Out-Null
    CaptureSeries cut 12 100
    Call camera @{x=399.1;y=161.2;z=36.1;yaw=150;pitch=12} | Out-Null
    Call wait @{ms=10000} | Out-Null
    Call command @{command='gamerule minecraft:advance_time true'} | Out-Null
    Call command @{command='tick rate 100'} | Out-Null
    Call command @{command='tick unfreeze'} | Out-Null
    CaptureSeries sunlight 24 150
    Call command @{command='time query minecraft:day'} | Out-Null
    Snapshot after
    $after=Status
    @{generated_delta=$after.plugins.reflexium.fg_generated_presents-$s.plugins.reflexium.fg_generated_presents;
      requested_multiplier=$Multiplier;effective_multiplier=$after.plugins.reflexium.fg_effective_multiplier;
      foreground=$after.focus.os_foreground;covered_by_oled_overlay=$true} | ConvertTo-Json | Set-Content "$evidence/generation.json"
} finally {
    foreach($cmd in @('tick rate 20','tick freeze','gamerule minecraft:advance_time false','time set 9000')) { Call command @{command=$cmd} | Out-Null }
    Setting frame_generation off
    Setting mip_bias_offset 0
}
