param([int]$Port,[ValidateSet('setup','shot','quit')][string]$Action,[string]$Mode)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
$lockPath='C:/Users/Ryan/source/repos/Reflexium-gpu.lock'
if((Get-Content $lockPath|ConvertFrom-Json).owner -ne 'worker-B-dither'){throw 'worker B GPU lock required'}
$session=Get-Content "C:/Users/Ryan/source/repos/mc-agent-harness/instances/$Port/session.json"|ConvertFrom-Json
$headers=@{Authorization="Bearer $($session.token)"}
function Call($endpoint,$body=@{}) {
    $r=Invoke-RestMethod "http://127.0.0.1:$Port/$endpoint" -Method Post -Headers $headers -ContentType application/json -Body ($body|ConvertTo-Json -Compress) -TimeoutSec 60
    if($r.ok -eq $false){throw ($r|ConvertTo-Json -Depth 6)}
    $r
}
function Status {Invoke-RestMethod "http://127.0.0.1:$Port/status" -Headers $headers -TimeoutSec 15}
function Setting($key,$value){Call reflexium @{key=$key;value=[string]$value}|Out-Null}
$dir="$root/docs/validation/pack-dither-2026-10-31"
if($Action -eq 'setup') {
    Setting exclusive_fullscreen 0
    Call fullscreen @{fullscreen=1;activate=1}|Out-Null
    Call join @{world='Reflexium Vitrail Smoke';timeout_s=60}|Out-Null
    foreach($cmd in @('tick unfreeze','gamemode spectator @a','time set 9000','weather clear','gamerule minecraft:advance_time false','fill 395 159 30 405 166 42 air','fill 395 159 30 405 159 42 smooth_stone','fill 397 160 34 403 164 34 smooth_sandstone','fill 397 160 35 397 164 40 smooth_sandstone','fill 397 165 34 400 165 38 smooth_sandstone')) {Call command @{command=$cmd}|Out-Null}
    Call camera @{x=399.1;y=161.2;z=36.1;yaw=150;pitch=12}|Out-Null
    Setting upscaler dlss
    Setting dlss_preset 12
    Setting quality ultra_performance
    Setting frame_generation dlss_multi
    Setting fg_multiplier 3
    Setting bob_view 0
    Call wait @{ms=3000}|Out-Null
    $status=Status
    $status|ConvertTo-Json -Depth 30|Set-Content "$dir/setup-$Port.json"
    $lock=Get-Content $lockPath|ConvertFrom-Json
    $lock.pid=$status.pid
    $lock|ConvertTo-Json|Set-Content $lockPath
    $status
} elseif($Action -eq 'shot') {
    Call wait @{ms=3000}|Out-Null
    Call capture_sequence @{dir="$dir/$Mode";count=8;interval_ms=50}|ConvertTo-Json -Depth 30|Set-Content "$dir/$Mode-sequence.json"
    Status|ConvertTo-Json -Depth 30|Set-Content "$dir/$Mode-status.json"
    Copy-Item "$root/run/logs/latest.log" "$dir/$Mode-client.log"
    Write-Output "Captured $Mode"
} else {
    $status=Status
    $clientPid=$status.pid
    Call quit @{exit=$true}|Out-Null
    Add-Type 'using System;using System.Runtime.InteropServices;public class RxBRestore{[DllImport("user32.dll")]public static extern bool SetForegroundWindow(IntPtr h);[DllImport("user32.dll")]public static extern IntPtr GetForegroundWindow();}'
    $restored=[RxBRestore]::SetForegroundWindow([IntPtr]::new([long]$session.foreground_before))
    @{restored=$restored;foreground=[RxBRestore]::GetForegroundWindow().ToInt64();previous=$session.foreground_before;clientPid=$clientPid}|ConvertTo-Json|Set-Content "$dir/focus-restored-$Port.json"
    Write-Output "Quit requested for owned client $clientPid; confirm process exit before releasing lock"
}

