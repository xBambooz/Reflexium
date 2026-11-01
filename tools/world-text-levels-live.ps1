param([int]$Port=25921)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/iq-session.ps1" -Port $Port
$root=Split-Path $PSScriptRoot
$ev="$root/docs/validation/world-text-shading-2026-11-01/live-levels"
$raw="$root/run/text-level-live"
New-Item -ItemType Directory -Force $ev,$raw|Out-Null
if((Status).game_directory -ne "$root\run"){throw 'wrong game directory'}
if((Get-Content C:/Users/Ryan/source/repos/Reflexium-gpu.lock|ConvertFrom-Json).owner -ne 'world-text-shading-19c0'){throw 'wrong GPU owner'}
function Snap($name,$capture=$false){
 $s=Status
 if($s.plugins.reflexium.last_error){throw $s.plugins.reflexium.last_error}
 $s|ConvertTo-Json -Depth 40|Set-Content "$ev/$name-status.json"
 Call screenshot @{path="$ev/$name.png"}|Out-Null
 if($capture){Call reflexium @{capture=$name;dir=$raw}|ConvertTo-Json -Depth 20|Set-Content "$ev/$name-capture.json"}
 Write-Output $name
}
Setting frame_generation off
Setting shaders 1
Setting dlss_model transformer
Setting dlss_preset 12
Call command @{command='tick freeze'}|Out-Null
Call command @{command='weather clear'}|Out-Null
Call command @{command='clear @a'}|Out-Null
Call camera @{x=200.5;y=151;z=20;yaw=0;pitch=0}|Out-Null
foreach($c in @('fill 197 151 27 203 156 29 air','setblock 199 152 28 oak_sign[rotation=0]','setblock 201 152 28 oak_sign[rotation=0]','setblock 199 155 28 stone','setblock 199 154 28 oak_hanging_sign[rotation=0]','setblock 201 155 28 stone','setblock 201 154 28 oak_hanging_sign[rotation=0]')){Call command @{command=$c}|Out-Null}
foreach($x in @(199,201)){foreach($y in @(152,154)){
 $glow=if($x -eq 201){'1b'}else{'0b'};$color=if($x -eq 201){'white'}else{'black'}
 $text='{messages:["READ ME","Sharp glyphs","0123456789","Reflexium"],color:"'+$color+'",has_glowing_text:'+$glow+'}'
 Call command @{command=("data merge block $x $y 28 {front_text:"+$text+',back_text:'+$text+'}')}|Out-Null
}}
foreach($light in @('day','shade','night')){
 Call command @{command="time set $(if($light -eq 'night'){'midnight'}else{'noon'})"}|Out-Null
 Call command @{command="fill 194 157 22 207 157 35 $(if($light -eq 'shade'){'stone'}else{'air'})"}|Out-Null
 Call command @{command='tick step 60'}|Out-Null
 Setting upscaler off
 Call wait @{ms=10000}|Out-Null
 Snap "$light-native"
 Setting upscaler dlss
 Setting quality ultra_performance
 Call wait @{ms=10000}|Out-Null
 Setting world_text_pack_levels 0
 Call wait @{ms=1000}|Out-Null
 Snap "$light-before" $true
 Setting world_text_pack_levels 1
 Call wait @{ms=1000}|Out-Null
 $s=Status
 if(!$s.plugins.reflexium.observed.world_text_pack_levels_applied){throw 'pack levels not applied'}
 Snap "$light-after"
}
Setting shaders 0
Setting jitter 0
Call command @{command='time set noon'}|Out-Null
Call command @{command='tick step 60'}|Out-Null
Call wait @{ms=10000}|Out-Null
foreach($on in @(0,1,0)){
 Setting world_text_pack_levels $on
 Call wait @{ms=2000}|Out-Null
 Snap "shaderless-$on-$(Get-Date -Format HHmmssfff)"
}
Setting jitter 1
Setting shaders 1
Call command @{command='fill 194 148 18 207 158 35 stone'}|Out-Null
Call command @{command='fill 195 149 19 206 156 34 air'}|Out-Null
foreach($c in @('setblock 199 152 28 oak_sign[rotation=0]','setblock 201 152 28 oak_sign[rotation=0]','setblock 199 155 28 stone','setblock 199 154 28 oak_hanging_sign[rotation=0]','setblock 201 155 28 stone','setblock 201 154 28 oak_hanging_sign[rotation=0]')){Call command @{command=$c}|Out-Null}
foreach($x in @(199,201)){foreach($y in @(152,154)){
 $glow=if($x -eq 201){'1b'}else{'0b'};$color=if($x -eq 201){'white'}else{'black'}
 $text='{messages:["READ ME","Sharp glyphs","0123456789","Reflexium"],color:"'+$color+'",has_glowing_text:'+$glow+'}'
 Call command @{command=("data merge block $x $y 28 {front_text:"+$text+',back_text:'+$text+'}')}|Out-Null
}}
Call command @{command='time set midnight'}|Out-Null
Call command @{command='tick step 100'}|Out-Null
Setting upscaler off
Call wait @{ms=10000}|Out-Null
Snap cave-native
Setting upscaler dlss
Call wait @{ms=10000}|Out-Null
Setting world_text_pack_levels 0
Call wait @{ms=1000}|Out-Null
Snap cave-before $true
Setting world_text_pack_levels 1
Call wait @{ms=1000}|Out-Null
Snap cave-after
Setting world_text_pack_levels 0
