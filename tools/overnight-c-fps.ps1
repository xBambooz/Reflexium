param([int]$Port=25783,[string]$Label='mesh-on')
$ErrorActionPreference='Stop'
. "$PSScriptRoot/iq-session.ps1" -Port $Port
$root=Split-Path $PSScriptRoot
$dir="$root/docs/validation/overnight-c-2026-10-31/meshelium/$Label"
New-Item -ItemType Directory -Force $dir | Out-Null
$lockPath='C:/Users/Ryan/source/repos/Reflexium-gpu.lock'
$lock=Get-Content $lockPath -Raw|ConvertFrom-Json
if ($lock.owner -ne 'worker-C-overnight' -or (Status).game_directory -ne "$root\run") {throw 'Wrong owner/run'}
$purpose=$lock.purpose
$lock.purpose='timed'
$lock|ConvertTo-Json|Set-Content $lockPath
try {
    Setting frame_generation off
    Setting upscaler off
    Call wait @{ms=1000}|Out-Null
    Setting shaders 0
    Setting vsync 0
    Call quit @{}|Out-Null
    Call join @{world='Reflexium Vitrail Smoke';timeout_s=120}|Out-Null
    Call command @{command='tick freeze'}|Out-Null
    Call camera @{x=200.5;y=110;z=150;yaw=90;pitch=10}|Out-Null
    foreach($mode in @('native','ultra')) {
        if ($mode -eq 'ultra') { Setting upscaler dlss; Setting quality ultra_performance }
        Call wait @{ms=10000}|Out-Null
        $before=Status
        $before|ConvertTo-Json -Depth 40|Set-Content "$dir/$mode-before.json"
        if (!$before.focus.os_foreground -or $before.plugins.reflexium.pack -notmatch 'no shader pack drawing') {throw 'Wrong focus/pack'}
        for($repeat=1;$repeat -le 3;$repeat++) {
            $mark=Call reflexium @{frame_mark=1}
            Call wait @{ms=5000}|Out-Null
            Call reflexium @{frame_stats_from=$mark.frames}|ConvertTo-Json -Depth 30|Set-Content "$dir/$mode-$repeat.json"
        }
        Status|ConvertTo-Json -Depth 40|Set-Content "$dir/$mode-after.json"
    }
} finally {
    $lock=Get-Content $lockPath -Raw|ConvertFrom-Json
    if ($lock.owner -ne 'worker-C-overnight') {throw 'Lost GPU lock'}
    $lock.purpose=$purpose
    $lock|ConvertTo-Json|Set-Content $lockPath
}
