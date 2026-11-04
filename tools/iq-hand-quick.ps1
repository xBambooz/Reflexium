param([int]$Port, [ValidateSet('before','after')][string]$Label)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/iq-session.ps1" -Port $Port
$root=Split-Path $PSScriptRoot
$evidence=Join-Path $root "docs/validation/iq-issues-2026-10-30/continuation/hand/$Label"
$raw=Join-Path $root "run/iq-continuation/hand/$Label"
New-Item -ItemType Directory -Force $evidence,$raw|Out-Null
$lock=Get-Content C:/Users/Ryan/source/repos/Reflexium-gpu.lock|ConvertFrom-Json
if($lock.owner -ne 'worker-A' -or (Status).game_directory -ne "$root\run"){throw 'ownership mismatch'}
if(!(Status).world_loaded){Call join @{world='Reflexium Vitrail Smoke';timeout_s=120}|Out-Null}
Call window @{monitor='secondary';x=0;y=0;width=2560;height=1440;behind=$true}|Out-Null
Setting frame_generation off
Setting upscaler dlss
Setting quality ultra_performance
Setting shaders 1
Setting bob_view 1
foreach($command in @('tick unfreeze','gamemode survival @a','time set 6000','weather clear',
    'execute positioned 200.5 151 8 run kill @e[type=minecraft:pig,distance=..32]',
    'execute positioned 200.5 151 8 run kill @e[type=minecraft:item,distance=..32]',
    'item replace entity @a weapon.mainhand with minecraft:air','item replace entity @a weapon.offhand with minecraft:air')){
    Call command @{command=$command}|Out-Null
}
Call camera @{x=200.5;y=151;z=.5;yaw=0;pitch=3}|Out-Null
Call wait @{ms=10000}|Out-Null
$s=Status
$r=$s.plugins.reflexium
if($r.last_error -or $r.upscaler_effective -ne 'dlss' -or $r.render_size.w -ne 853){throw 'invalid mode'}
if($r.player_name -and $r.player_name -ne 'RxWhiteA'){throw 'wrong offline identity'}
if(!(Select-String -Path "$root/run/logs/latest.log" -SimpleMatch 'file/rx-white')){throw 'white pack not loaded'}
SaveStatus initial
Call turn @{dyaw=8;ticks=40;async=$true}|Out-Null
Call reflexium @{capture="$Label-pan";dir=$raw}|ConvertTo-Json -Depth 40|Set-Content "$evidence/pan-input.json"
Call capture_sequence @{dir="$raw/pan";count=8;interval_ms=250}|ConvertTo-Json -Depth 40|Set-Content "$evidence/pan.json"
Call wait @{ms=1000}|Out-Null
Call camera @{x=200.5;y=151;z=.5;yaw=0;pitch=3}|Out-Null
Call wait @{ms=2500}|Out-Null
try{
    Call key @{name='key.forward';down=$true;ticks=10}|Out-Null
    Call wait @{ms=200}|Out-Null
    Call reflexium @{capture="$Label-walk";dir=$raw}|ConvertTo-Json -Depth 40|Set-Content "$evidence/walk-input.json"
    Call capture_sequence @{dir="$raw/walk";count=6;interval_ms=100}|ConvertTo-Json -Depth 40|Set-Content "$evidence/walk.json"
}finally{Call key @{name='key.forward';down=$false}|Out-Null}
Call wait @{ms=1000}|Out-Null
SaveStatus stopped
$a=(Status).camera
Call wait @{ms=500}|Out-Null
$b=(Status).camera
if([math]::Abs($a.z-$b.z) -gt .01){throw 'forward movement did not stop'}
SaveStatus final
Write-Output "Captured white hand $Label pan/walk and verified key release"
