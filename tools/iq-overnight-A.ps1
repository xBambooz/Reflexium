param([int]$Port=25740,[string]$Label='current-present',[ValidateSet('fg','signs')][string]$Task='fg')
$ErrorActionPreference='Stop'
. "$PSScriptRoot/iq-session.ps1" -Port $Port
$root=Split-Path $PSScriptRoot
$ev="$root/docs/validation/overnight-A-2026-10-31/$Label"
New-Item -ItemType Directory -Force $ev|Out-Null
if((Status).game_directory -ne "$root\run" -or (Get-Content C:/Users/Ryan/source/repos/Reflexium-gpu.lock|ConvertFrom-Json).owner -ne 'worker-A-overnight'){throw 'ownership'}
function Snap($name){$s=Status;$s|ConvertTo-Json -Depth 40|Set-Content "$ev/$name.json";if($s.plugins.reflexium.last_error){throw 'render error'};if($s.plugins.reflexium.upscaler_effective -eq 'dlss' -and !$s.plugins.reflexium.upscaling_active){throw 'DLSS inactive'};Call screenshot @{path="$ev/$name.png"}|Out-Null}
Setting exclusive_fullscreen 0
Call fullscreen @{fullscreen=0}|Out-Null
Call fullscreen @{fullscreen=1;activate=1}|Out-Null
Setting frame_generation off
Setting vsync 0
Setting dlss_preset 12
foreach($c in @('gamemode creative @a','tick freeze','time set noon','weather clear','kill @e[tag=rx_text_quick]','execute positioned 200 152 28 run kill @e[type=minecraft:horse,distance=..32]','fill 196 151 25 205 158 30 air')){Call command @{command=$c}|Out-Null}
foreach($x in @(199,201)){foreach($y in @(152,154)){
 $kind=if($y -eq 152){'oak_sign'}else{'oak_hanging_sign'}
 if($y -eq 154){Call command @{command="setblock $x 155 28 stone"}|Out-Null}
 Call command @{command="setblock $x $y 28 ${kind}[rotation=0]"}|Out-Null
 $glow=if($x -eq 201){'1b'}else{'0b'};$color=if($x -eq 201){'white'}else{'black'}
 $text='{messages:["READ ME","Sharp glyphs","0123456789","Reflexium"],color:"'+$color+'",has_glowing_text:'+$glow+'}'
 Call command @{command=("data merge block $x $y 28 {front_text:"+$text+',back_text:'+$text+'}')}|Out-Null
}}
Call command @{command='tick step 4'}|Out-Null
Call camera @{x=200.5;y=151;z=20;yaw=0;pitch=0}|Out-Null
if($Task -eq 'fg'){
 Call command @{command='summon pig 197.5 151 28 {Tags:["rx_text_quick"],CustomName:"Moving Name",CustomNameVisible:1b,PersistenceRequired:1b}'}|Out-Null
 Call command @{command='tick unfreeze'}|Out-Null
 Setting upscaler dlss
 Setting quality ultra_performance
 Setting frame_generation dlss_multi
 Setting fg_multiplier 3
 foreach($pack in @(0,1)){
  Setting shaders $pack
  Call camera @{x=200.5;y=151;z=20;yaw=0;pitch=0}|Out-Null
  Call wait @{ms=10000}|Out-Null
  Snap "p$pack-before"
  $before=(Status).plugins.reflexium.fg_generated_presents
  Call turn @{dyaw=15;ticks=100;async=$true}|Out-Null
  & C:/Users/Ryan/source/repos/Reflexium/tools/wgc-capture/bin/Release/net10.0-windows10.0.26100.0/WgcCapture.exe --pid (Status).pid --out "$ev/p$pack-wgc-sign" --count 300 --seconds 4 --png true --every 1 --crop 1120,620,320,180 --min-interval-ms 0 | Set-Content "$ev/p$pack-wgc-result.json"
  Snap "p$pack-after"
  $delta=(Status).plugins.reflexium.fg_generated_presents-$before
  if($delta -le 0){throw 'no generated presents'}
  Write-Output "FG p$pack generated delta=$delta"
 }
 Setting frame_generation off
}else{
 foreach($pack in @(0,1)){
  Setting shaders $pack
  foreach($mode in @('native','dlaa','quality','ultra_performance')){
   Setting upscaler $(if($mode -eq 'native'){'off'}else{'dlss'})
   if($mode -ne 'native'){Setting quality $mode}
   foreach($d in @(4,8,16,24)){
    $name="p$pack-$mode-d$d"
    Call camera @{x=200.5;y=151;z=(28-$d);yaw=0;pitch=0}|Out-Null
    Call wait @{ms=10000}|Out-Null
    Snap $name
    Call capture_sequence @{dir="$ev/$name-static";count=3;interval_ms=120}|ConvertTo-Json -Depth 40|Set-Content "$ev/$name-static.json"
    Call capture_sequence @{dir="$ev/$name-pan";count=26;interval_ms=200;dyaw=15;ticks=100}|ConvertTo-Json -Depth 40|Set-Content "$ev/$name-pan.json"
    Write-Output "Completed $Label $name"
   }
  }
 }
}
