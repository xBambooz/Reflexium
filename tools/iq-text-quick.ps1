param([int]$Port, [switch]$NamesOnly)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/iq-session.ps1" -Port $Port
$root = Split-Path $PSScriptRoot
$evidence = Join-Path $root 'docs/validation/iq-issues-2026-10-30/continuation/quick'
$raw = Join-Path $root 'run/iq-continuation/quick'
New-Item -ItemType Directory -Force $evidence,$raw | Out-Null
function CheckText($name, [bool]$deferred) {
    $s=Status
    $r=$s.plugins.reflexium
    if ($s.game_directory -ne "$root\run" -or $r.last_error -or $r.upscaler_effective -ne 'dlss') { throw 'ownership/mode/error mismatch' }
    if ($deferred -and (!$r.observed.world_text_prepared -or $r.observed.world_text_nodes -lt 1 -or !$r.observed.post_depth_resolved)) {
        $s|ConvertTo-Json -Depth 35|Set-Content "$evidence/$name-failed.json"
        throw 'world text was not prepared against display depth'
    }
    SaveStatus "$name-before"
    Call reflexium @{capture=$name;dir=$raw}|ConvertTo-Json -Depth 40|Set-Content "$evidence/$name-capture.json"
    Call screenshot @{path="$raw/$name.png"}|Out-Null
    $after=Status
    if ($after.plugins.reflexium.last_error -or $after.plugins.reflexium.evaluated_frames -le $r.evaluated_frames) { throw 'evaluation/error mismatch' }
    SaveStatus "$name-after"
    Write-Output "Verified $name, nodes=$($r.observed.world_text_nodes)"
}
$lock=Get-Content C:/Users/Ryan/source/repos/Reflexium-gpu.lock|ConvertFrom-Json
if ($lock.owner -ne 'worker-A') { throw 'GPU ownership mismatch' }
if (!(Status).world_loaded) { Call join @{world='Reflexium Vitrail Smoke';timeout_s=120}|Out-Null }
Call window @{monitor='secondary';x=0;y=0;width=2560;height=1440;behind=$true}|Out-Null
Setting frame_generation off
Call command @{command='gamemode spectator @a'}|Out-Null
Call command @{command='tick freeze'}|Out-Null
Setting upscaler dlss
if (!$NamesOnly) { foreach ($pack in @(0,1)) {
    Setting shaders $pack
    foreach ($quality in @('quality','ultra_performance')) {
        Setting quality $quality
        foreach ($distance in @(8,16)) {
            Call camera @{x=200.5;y=151;z=(28-$distance);yaw=0;pitch=0}|Out-Null
            foreach ($deferred in @(0,1)) {
                $name="sign-p$pack-d$distance-$quality-defer$deferred"
                if ($deferred -eq 0 -and (Test-Path "$evidence/$name-after.json")) { continue }
                Setting world_text_defer $deferred
                Call wait @{ms=10000}|Out-Null
                CheckText $name ([bool]$deferred)
            }
        }
    }
}
}
Setting world_text_defer 1
Setting quality ultra_performance
Setting shaders 1
Call command @{command='gamemode creative @a'}|Out-Null
Call camera @{x=200.5;y=151;z=.5;yaw=0;pitch=0}|Out-Null
foreach($command in @(
    'kill @e[tag=rx_text_quick]',
    'execute positioned 200.5 151 8 run kill @e[type=minecraft:pig,distance=..32]',
    'tick step 40',
    'summon minecraft:pig 200.5 151 8 {Tags:["rx_text_quick"],CustomName:"Quick Moving Name",CustomNameVisible:1b,PersistenceRequired:1b,NoAI:1b}',
    'fill 202 151 5 204 154 5 minecraft:stone',
    'summon minecraft:armor_stand 203.5 151 8 {Tags:["rx_text_quick"],CustomName:"Behind Wall",CustomNameVisible:1b,NoGravity:1b}',
    'item replace entity @a weapon.mainhand with minecraft:carrot',
    'tick step 40')) { Call command @{command=$command}|Out-Null }
Call wait @{ms=10000}|Out-Null
CheckText 'names-static-wall' $true
Call command @{command='data merge entity @e[tag=rx_text_quick,type=minecraft:pig,limit=1] {NoAI:0b}'}|Out-Null
Call command @{command='tick unfreeze'}|Out-Null
Call capture_sequence @{dir="$raw/names-moving";count=10;interval_ms=100}|ConvertTo-Json -Depth 40|Set-Content "$evidence/names-moving.json"
Call command @{command='tick freeze'}|Out-Null
Call wait @{ms=1000}|Out-Null
CheckText 'names-after-moving' $true
Write-Output 'Quick sign/name checks complete; focused FG check remains manual.'
