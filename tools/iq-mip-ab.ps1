param([int]$Port)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/iq-session.ps1" -Port $Port
$root = Split-Path $PSScriptRoot
$evidence = Join-Path $root 'docs/validation/iq-issues-2026-10-30/continuation/mip'
$raw = Join-Path $root 'run/iq-continuation/mip'
New-Item -ItemType Directory -Force $evidence,$raw | Out-Null
function Owned {
    $lock = Get-Content C:/Users/Ryan/source/repos/Reflexium-gpu.lock | ConvertFrom-Json
    if ($lock.owner -ne 'worker-A' -or ([datetimeoffset]::Now - [datetimeoffset]::Parse($lock.started)).TotalMinutes -gt 38) { throw 'GPU ownership/session limit' }
    $s = Status
    if ([IO.Path]::GetFullPath($s.game_directory) -ne [IO.Path]::GetFullPath("$root/run")) { throw 'wrong game directory' }
    if (!$s.world_loaded -or $s.screen -ne 'none' -or $s.paused -or $s.plugins.reflexium.last_error) { throw 'invalid world state' }
    $s
}
function SaveJson($name, $value) { $value | ConvertTo-Json -Depth 40 | Set-Content "$evidence/$name.json" }
function CaptureSequence($name, [float]$yaw) {
    $result = Call capture_sequence @{dir="$raw/$name";count=24;interval_ms=250;dyaw=$yaw;ticks=100}
    $result | ConvertTo-Json -Depth 40 | Set-Content "$raw/$name.json"
    $compact = foreach ($f in $result.frames) {
        $r = $f.plugins.reflexium
        if ($r.last_error -or [math]::Abs($f.camera.y-110) -gt .001) { throw 'invalid sequence frame' }
        @{path=$f.path;camera=$f.camera;frame=$f.frames;jitter_index=$r.jitter_index;evaluated=$r.evaluated_frames;render_size=$r.render_size;last_error=$r.last_error}
    }
    SaveJson $name $compact
    $result.frames | ForEach-Object { "$($_.path)`t$($_.camera.yaw)" } | Set-Content "$evidence/$name.tsv"
}
Owned | Out-Null
Setting frame_generation off
Call command @{command='gamemode spectator @a'} | Out-Null
Call command @{command='tick freeze'} | Out-Null
try {
    foreach ($pack in @(0,1)) {
        Setting shaders $pack
        Setting upscaler off
        Call camera @{x=200.5;y=110;z=150;yaw=90;pitch=10} | Out-Null
        Call wait @{ms=10000} | Out-Null
        SaveJson "p$pack-native" (Owned)
        Shot "p$pack-native"
        CaptureSequence "p$pack-native-static" 0
        CaptureSequence "p$pack-native-pan" 4
        foreach ($quality in @('quality','ultra_performance')) {
            Setting upscaler dlss
            Setting quality $quality
            $arm=0
            foreach ($bias in @(0,1,1,0)) {
                Owned | Out-Null
                $name="p$pack-$quality-a$arm-b$bias"
                Setting mip_bias_offset $bias
                Call camera @{x=200.5;y=110;z=150;yaw=90;pitch=10} | Out-Null
                Call wait @{ms=10000} | Out-Null
                $before=Owned
                $r=$before.plugins.reflexium
                if ($r.upscaler_effective -ne 'dlss' -or [math]::Abs($before.camera.yaw-90) -gt .001) { throw 'mode/pose mismatch' }
                SaveJson "$name-before" $before
                SaveJson "$name-input" (Call reflexium @{capture=$name;dir=$raw})
                Shot $name
                CaptureSequence "$name-static" 0
                CaptureSequence "$name-pan" 4
                $after=Owned
                if ($after.plugins.reflexium.evaluated_frames -le $r.evaluated_frames) { throw 'no evaluated frames' }
                SaveJson "$name-after" $after
                Write-Output "Captured $name"
                $arm++
            }
        }
    }
} finally {
    Setting mip_bias_offset 0
}
