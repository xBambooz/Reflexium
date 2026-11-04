param([int]$Port=25911)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/iq-session.ps1" -Port $Port
$root=Split-Path $PSScriptRoot
$ev="$root/docs/validation/world-text-shading-2026-11-01"
if((Status).game_directory -ne "$root\run"){throw 'wrong game directory'}
if((Get-Content C:/Users/Ryan/source/repos/Reflexium-gpu.lock|ConvertFrom-Json).owner -ne 'world-text-shading-19c0'){throw 'wrong GPU owner'}
New-Item -ItemType Directory -Force $ev|Out-Null
Setting frame_generation off
Setting shaders 1
Setting dlss_model transformer
Setting dlss_preset 12
Call command @{command='tick freeze'}|Out-Null
Call command @{command='weather clear'}|Out-Null
Call camera @{x=200.5;y=151;z=20;yaw=0;pitch=0}|Out-Null
foreach($c in @('fill 197 151 27 203 156 29 air','setblock 199 152 28 oak_sign[rotation=0]','setblock 201 152 28 oak_sign[rotation=0]','setblock 199 155 28 stone','setblock 199 154 28 oak_hanging_sign[rotation=0]')){Call command @{command=$c}|Out-Null}
& "$PSScriptRoot/iq-followup-fixture.ps1" -Port $Port
function Snap($name,$capture){
 if($capture){
  $rx=(Status).plugins.reflexium
  if($rx.upscaler_built -ne 'dlss' -or $rx.evaluated_frames -le 0 -or $rx.observed.world_text_nodes -le 0){throw 'capture requires evaluated DLSS and deferred text'}
 }
 Status|ConvertTo-Json -Depth 40|Set-Content "$ev/$name-status.json"
 Call screenshot @{path="$ev/$name.png"}|Out-Null
 if($capture){Call reflexium @{capture=$name;dir=$ev}|ConvertTo-Json -Depth 10|Set-Content "$ev/$name-capture.json"}
 Write-Output $name
}
foreach($light in @('day','shade','night')){
 Call command @{command="time set $(if($light -eq 'night'){'midnight'}else{'noon'})"}|Out-Null
 Call command @{command="fill 194 157 22 207 157 35 $(if($light -eq 'shade'){'stone'}else{'air'})"}|Out-Null
 Call command @{command='tick step 60'}|Out-Null
 foreach($mode in @('native','deferred')){
  Setting upscaler $(if($mode -eq 'native'){'off'}else{'dlss'})
  if($mode -eq 'deferred'){Setting quality ultra_performance}
  Call wait @{ms=10000}|Out-Null
  Snap "$light-$mode" ($mode -eq 'deferred')
 }
}
Setting shaders 0
Call command @{command='time set noon'}|Out-Null
Call command @{command='tick step 60'}|Out-Null
foreach($mode in @('native','deferred')){
 Setting upscaler $(if($mode -eq 'native'){'off'}else{'dlss'})
 Call wait @{ms=10000}|Out-Null
 Snap "shaderless-$mode" ($mode -eq 'deferred')
}
Setting shaders 1
Call wait @{ms=10000}|Out-Null
Call reflexium @{profile='reset'}|Out-Null
Call wait @{ms=3000}|Out-Null
Call reflexium @{profile='off'}|ConvertTo-Json -Depth 40|Set-Content "$ev/profile.json"
Setting frame_generation dlss_multi
Setting fg_multiplier 3
Call wait @{ms=3000}|Out-Null
Snap 'fg3' $true
Setting frame_generation off
