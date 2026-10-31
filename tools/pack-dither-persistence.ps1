param([int]$Port,[ValidateSet('start','reload','native','native-reload','escape','pause','click','snapshot')][string]$Action,[int]$X,[int]$Y,[string]$Name)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
if((Get-Content C:/Users/Ryan/source/repos/Reflexium-gpu.lock|ConvertFrom-Json).owner -ne 'worker-B-dither'){throw 'worker B GPU lock required'}
$session=Get-Content "C:/Users/Ryan/source/repos/mc-agent-harness/instances/$Port/session.json"|ConvertFrom-Json
$headers=@{Authorization="Bearer $($session.token)"}
function Call($endpoint,$body=@{}) {
    $r=Invoke-RestMethod "http://127.0.0.1:$Port/$endpoint" -Method Post -Headers $headers -ContentType application/json -Body ($body|ConvertTo-Json -Compress) -TimeoutSec 60
    if($r.ok -eq $false){throw ($r|ConvertTo-Json -Depth 6)}
    $r
}
function Status {Invoke-RestMethod "http://127.0.0.1:$Port/status" -Headers $headers -TimeoutSec 15}
function Setting($key,$value){Call reflexium @{key=$key;value=[string]$value}|Out-Null}
$dir="$root/docs/validation/pack-dither-2026-10-31/persistence"
$pack='ComplementaryReimagined_r5.9.3 + EuphoriaPatches_1.10.5'
function Snapshot($label) {
    $status=Status
    $status|ConvertTo-Json -Depth 30|Set-Content "$dir/$label-status.json"
    if(Test-Path -LiteralPath "$root/run/shaderpacks/$pack.txt") {Copy-Item -LiteralPath "$root/run/shaderpacks/$pack.txt" -Destination "$dir/$label.properties"}
    else {'# Iris removed the empty option file'|Set-Content "$dir/$label.properties"}
    Call screenshot @{path="$dir/$label.png"}|Out-Null
    Copy-Item "$root/run/logs/latest.log" "$dir/client.log"
}
if($Action -eq 'start') {
    Setting exclusive_fullscreen 0
    Call fullscreen @{fullscreen=1;activate=1}|Out-Null
    Call join @{world='Reflexium Vitrail Smoke';timeout_s=60}|Out-Null
    Call wait @{ms=2000}|Out-Null
    Snapshot loaded
    $status=Status; $lock=Get-Content C:/Users/Ryan/source/repos/Reflexium-gpu.lock|ConvertFrom-Json; $lock.pid=$status.pid; $lock|ConvertTo-Json|Set-Content C:/Users/Ryan/source/repos/Reflexium-gpu.lock
} elseif($Action -eq 'reload' -or $Action -eq 'native-reload') {
    Setting shader_pack $pack
    Call wait @{ms=2000}|Out-Null
    Snapshot $Action
} elseif($Action -eq 'native') {
    Setting upscaler off
    Call wait @{ms=2000}|Out-Null
    # Rejoin once to start the native reload control from visible terrain.
    Call quit @{}|Out-Null
    Call join @{world='Reflexium Vitrail Smoke';timeout_s=60}|Out-Null
    Call wait @{ms=2000}|Out-Null
    Snapshot native
} elseif($Action -eq 'pause') {Setting pause 1}
elseif($Action -eq 'escape') {Call key @{scancode=41}|Out-Null}
elseif($Action -eq 'click') {Call click @{x=$X;y=$Y;button=1}|Out-Null}
elseif($Action -eq 'snapshot') {Snapshot $Name}
$status=Status
@{screen=$status.screen;widgets=$status.widgets;pack=$status.plugins.reflexium.pack}|ConvertTo-Json -Depth 8
