param([int]$Port,[string]$Label,[switch]$GatesOnly)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/iq-session.ps1" -Port $Port
$root=Split-Path $PSScriptRoot
$ev="$root/docs/validation/overnight-A-2026-10-31/$Label"
New-Item -ItemType Directory -Force $ev|Out-Null
if((Status).game_directory -ne "$root\run" -or (Get-Content C:/Users/Ryan/source/repos/Reflexium-gpu.lock|ConvertFrom-Json).owner -ne 'worker-A-overnight'){throw 'ownership'}
function Snap($name,[bool]$layers=$false){$s=Status;$s|ConvertTo-Json -Depth 40|Set-Content "$ev/$name.json";if($s.plugins.reflexium.last_error){throw 'render error'};Call screenshot @{path="$ev/$name.png"}|Out-Null;if($layers){Call reflexium @{capture=$name;dir=$ev}|ConvertTo-Json -Depth 40|Set-Content "$ev/$name-capture.json"}}
Setting frame_generation off
Setting bob_view 1
foreach($c in @('gamemode creative @a','tick freeze','fill 194 150 0 207 150 30 stone','fill 194 151 4 207 155 10 air','fill 202 151 6 204 154 6 stone','kill @e[tag=rx_text_quick]','summon armor_stand 203.5 151 8 {Tags:["rx_text_quick"],CustomName:"Behind Wall",CustomNameVisible:1b,NoGravity:1b}','item replace entity @a weapon.mainhand with carrot')){Call command @{command=$c}|Out-Null}
if(!$GatesOnly){foreach($pack in @(0,1)){
 Setting shaders $pack
 foreach($mode in @('native','quality','ultra_performance')){
  Setting upscaler $(if($mode -eq 'native'){'off'}else{'dlss'})
  if($mode -ne 'native'){Setting quality $mode}
  Call camera @{x=200.5;y=151;z=.5;yaw=0;pitch=0}|Out-Null
  foreach($c in @('kill @e[tag=rx_overnight_pig]','summon pig 200.5 151 8 {Tags:["rx_overnight_pig"],CustomName:"Walking Pig",CustomNameVisible:1b,PersistenceRequired:1b}','tick step 4')){Call command @{command=$c}|Out-Null}
  Call wait @{ms=10000}|Out-Null
  $name="p$pack-$mode"
  Snap "$name-wall" ($mode -ne 'native')
  Call command @{command='tick unfreeze'}|Out-Null
  Call capture_sequence @{dir="$ev/$name-walk";count=16;interval_ms=200}|ConvertTo-Json -Depth 40|Set-Content "$ev/$name-walk.json"
  Call command @{command='tick freeze'}|Out-Null
  Snap "$name-firstperson"
  Call key @{name='key.togglePerspective';down=$true;ticks=1}|Out-Null
  Call wait @{ms=500}|Out-Null
  Snap "$name-F5"
  Call key @{name='key.togglePerspective';down=$true;ticks=1}|Out-Null
  Call wait @{ms=100}|Out-Null
  Call key @{name='key.togglePerspective';down=$true;ticks=1}|Out-Null
  Write-Output "Completed names $Label $name"
 }
}}
Setting shaders 0
Setting improved_transparency 1
Call wait @{ms=2000}|Out-Null
Snap oit-fallback
Setting improved_transparency 0
Call wait @{ms=2000}|Out-Null
Snap oit-restored
Setting shaders 0
try{
 Call command @{command='posteffect add @a minecraft:creeper'}|Out-Null
 Call wait @{ms=2000}|Out-Null
 Snap post-effect-fallback
}finally{Call command @{command='posteffect clear @a'}|Out-Null}
Call wait @{ms=2000}|Out-Null
Snap post-effect-restored
Call fullscreen @{fullscreen=0}|Out-Null
Call window @{monitor='primary';x=0;y=0;width=1920;height=1080;behind=$false;activate=$true}|Out-Null
Call wait @{ms=2000}|Out-Null
Snap resized
Call fullscreen @{fullscreen=1;activate=1}|Out-Null
Call wait @{ms=2000}|Out-Null
Snap fullscreen-restored
Setting upscaler off
Call wait @{ms=1000}|Out-Null
Setting upscaler dlss
Call wait @{ms=2000}|Out-Null
Snap toggle-restored
Call quit @{}|Out-Null
Call wait @{ms=2000}|Out-Null
Call join @{world='Reflexium Vitrail Smoke';timeout_s=120}|Out-Null
Call wait @{ms=3000}|Out-Null
Snap world-restored
Setting upscaler off
Setting frame_generation dlss_multi
Setting fg_multiplier 3
Call wait @{ms=3000}|Out-Null
Snap native-fg-before
Call wait @{ms=3000}|Out-Null
Snap native-fg-after
Setting frame_generation off
