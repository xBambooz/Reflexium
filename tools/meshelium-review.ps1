param([int]$Port, [string]$Label, [switch]$PackComparison)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$out = "$root/run/evidence/review-$Label"
New-Item -ItemType Directory -Force $out | Out-Null
$session = Get-Content "C:/Users/Ryan/source/repos/mc-agent-harness/instances/$Port/session.json" | ConvertFrom-Json
$headers = @{Authorization="Bearer $($session.token)"}
function Call([string]$endpoint, [hashtable]$body=@{}) {
    Invoke-RestMethod "http://127.0.0.1:$Port/$endpoint" -Method Post -Headers $headers -ContentType 'application/json' -Body ($body | ConvertTo-Json -Compress) -TimeoutSec 90
}
function Set-Rx([string]$key,[string]$value) { Call reflexium @{key=$key;value=$value} | Out-Null }
function Save([string]$tag) {
    Call screenshot @{path="$out/$tag.png"} | Out-Null
    $status = Call status
    $status | ConvertTo-Json -Depth 35 | Set-Content "$out/$tag.json"
    $rx=$status.plugins.reflexium
    Write-Output "$tag : $($rx.status_line), jitter=$($rx.jitter_index), resets=$($rx.history_reset_frames), fg=$($rx.fg_status)"
}
function Observe([string]$tag,[scriptblock]$change) {
    & $change
    $timer=[Diagnostics.Stopwatch]::StartNew()
    Start-Sleep -Milliseconds 1000
    Save "$tag-1s"
    $remaining=5000-[int]$timer.ElapsedMilliseconds
    if($remaining -gt 0){Start-Sleep -Milliseconds $remaining}
    Save "$tag-5s"
}
Set-Rx exclusive_fullscreen off
Call fullscreen @{fullscreen=1;activate=1} | Out-Null
Call join @{world='Reflexium Vitrail Smoke';timeout_s=60} | Out-Null
Call camera @{x=200.5;y=110;z=150;yaw=94;pitch=10} | Out-Null
Call command @{command='tick unfreeze'} | Out-Null
Call command @{command='time set noon'} | Out-Null
Call command @{command='weather clear'} | Out-Null
Set-Rx shaders off
Set-Rx frame_generation off
Set-Rx fg_multiplier 3
Set-Rx upscaler dlss
Set-Rx quality ultra_performance
Set-Rx jitter on
Start-Sleep -Seconds 5
Save baseline
Observe freeze {Call command @{command='tick freeze'} | Out-Null}
Observe jitter-off {Set-Rx jitter off}
Observe jitter-on {Set-Rx jitter on}
Observe fg-on {Set-Rx frame_generation dlss}
Observe fg-off {Set-Rx frame_generation off}
Observe unfreeze {Call command @{command='tick unfreeze'} | Out-Null}
if($PackComparison) {
    Set-Rx shaders on
    foreach($scale in 'off','dlss') {
        Set-Rx upscaler $scale
        foreach($delegation in 'on','off') {
            Set-Rx meshelium_delegation $delegation
            Start-Sleep -Seconds 5
            $tag="pack-$scale-delegation-$delegation"
            Save $tag
            $first=Call status
            $timer=[Diagnostics.Stopwatch]::StartNew()
            Start-Sleep -Seconds 3
            $last=Call status
            $fps=($last.frames-$first.frames)/$timer.Elapsed.TotalSeconds
            [pscustomobject]@{tag=$tag;frames=$last.frames-$first.frames;seconds=$timer.Elapsed.TotalSeconds;fps=$fps;camera=$last.camera} |
                ConvertTo-Json -Depth 5 | Set-Content "$out/$tag-fps.json"
            Write-Output "$tag : mean rendered FPS $fps"
        }
    }
    Set-Rx meshelium_delegation on
}
Copy-Item "$root/run/logs/latest.log" "$out/latest.log"
