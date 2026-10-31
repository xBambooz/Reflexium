param([int]$Port,[string]$Label,[switch]$ThinOnly)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/iq-session.ps1" -Port $Port
$root=Split-Path $PSScriptRoot
$ev="$root/docs/validation/overnight-A-2026-10-31/$Label"
New-Item -ItemType Directory -Force $ev|Out-Null
Setting frame_generation off
Setting shaders 1
Call command @{command='tick freeze'}|Out-Null
Call command @{command='kill @e[tag=rx_text_quick]'}|Out-Null
Call command @{command='kill @e[tag=rx_overnight_pig]'}|Out-Null
Call command @{command='execute positioned 200.5 151 28 run kill @e[type=!minecraft:player,distance=..32]'}|Out-Null
Call command @{command='tick step 40'}|Out-Null
Call wait @{ms=2500}|Out-Null
Call command @{command='time set noon'}|Out-Null
Call camera @{x=200.5;y=151;z=20;yaw=0;pitch=0}|Out-Null
if(!$ThinOnly){foreach($shade in @(0,1)){
 Call command @{command="fill 194 157 22 207 157 35 $(if($shade){'stone'}else{'air'})"}|Out-Null
 Call command @{command='tick step 40'}|Out-Null
 foreach($mode in @('native','ultra_performance')){
  Setting upscaler $(if($mode -eq 'native'){'off'}else{'dlss'})
  if($mode -ne 'native'){Setting quality $mode}
  Call wait @{ms=10000}|Out-Null
  $name="shade$shade-$mode"
  Status|ConvertTo-Json -Depth 40|Set-Content "$ev/$name.json"
  Call screenshot @{path="$ev/$name.png"}|Out-Null
  if($mode -ne 'native'){Call reflexium @{capture=$name;dir=$ev}|ConvertTo-Json -Depth 40|Set-Content "$ev/$name-capture.json"}
 }
}}
Setting upscaler dlss
Setting quality ultra_performance
foreach($x in @(199,201)){foreach($y in @(152,154)){
 $glow=if($x -eq 201){'1b'}else{'0b'};$color=if($x -eq 201){'white'}else{'black'}
 $text='{messages:["READ ME","Sharp glyphs","0123456789","Reflexium"],color:"'+$color+'",has_glowing_text:'+$glow+'}'
 Call command @{command=("data merge block $x $y 28 {front_text:{messages:[`"`",`"`",`"`",`"`"]},back_text:"+$text+'}')}|Out-Null
}}
Call command @{command='fill 194 157 22 207 157 35 air'}|Out-Null
Call command @{command='fill 198 151 26 202 155 26 iron_bars'}|Out-Null
Call command @{command='tick step 4'}|Out-Null
foreach($ignore in @(0,1,1,0)){
 Setting world_text_ignore_depth $ignore
 Call wait @{ms=1000}|Out-Null
 $name="thin-ignore$ignore-$(Get-Date -Format HHmmssfff)"
 Status|ConvertTo-Json -Depth 40|Set-Content "$ev/$name.json"
 Call reflexium @{capture=$name;dir=$ev}|ConvertTo-Json -Depth 40|Set-Content "$ev/$name-capture.json"
 Call screenshot @{path="$ev/$name.png"}|Out-Null
}
Setting world_text_ignore_depth 0
Call command @{command='fill 198 151 26 202 155 26 air'}|Out-Null
