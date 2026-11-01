param([int]$Port=25740,[string]$Label='current-present',[ValidateSet('fg','signs')][string]$Task='fg',[int]$Interval=60000,[int[]]$Distances=@(4,8,16,24),[string[]]$Modes=@('native','native_aa','quality','ultra_performance'),[string[]]$Scenes=@('sign','mob','hand-turn','hand-walk'))
$ErrorActionPreference='Stop'
. "$PSScriptRoot/iq-session.ps1" -Port $Port
$root=Split-Path $PSScriptRoot
$ev="$root/docs/validation/overnight-A-2026-10-31/$Label"
New-Item -ItemType Directory -Force $ev|Out-Null
if((Status).game_directory -ne "$root\run" -or (Get-Content C:/Users/Ryan/source/repos/Reflexium-gpu.lock|ConvertFrom-Json).owner -ne 'worker-A-overnight'){throw 'ownership'}
function Snap($name){$s=Status;$s|ConvertTo-Json -Depth 40|Set-Content "$ev/$name.json";if($s.plugins.reflexium.last_error){throw 'render error'};if($s.plugins.reflexium.upscaler_effective -eq 'dlss' -and !$s.plugins.reflexium.upscaling_active){throw 'DLSS inactive'};if(!$s.window.fullscreen.borderless -or $s.window.fullscreen.exclusive_fullscreen -or $s.window.fullscreen.display_id -ne 1){throw 'Primary borderless requirement failed'};Call screenshot @{path="$ev/$name.png"}|Out-Null}
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
 Setting reflex_interval_us $Interval
 foreach($pack in @(0,1)){
  Setting shaders $pack
  Call camera @{x=200.5;y=151;z=20;yaw=0;pitch=0}|Out-Null
  Call wait @{ms=10000}|Out-Null
  Call fullscreen @{fullscreen=1;activate=1}|Out-Null
  if(!(Status).focus.os_foreground -or !(Status).focus.os_input_focus){
   $activation=& "$PSScriptRoot/iq-foreground-A.ps1" -Port $Port
   Call wait @{ms=500}|Out-Null
   @{activation=($activation|ConvertFrom-Json);status=(Status)}|ConvertTo-Json -Depth 40|Set-Content "$ev/p$pack-focus-retry.json"
  }
  if(!(Status).focus.os_foreground -or !(Status).focus.os_input_focus){throw 'FG visual acquisition requires owned-window OS foreground and input focus'}
  $actualPack=(Status).plugins.reflexium.pack
  if(($pack -eq 1 -and $actualPack -notlike '*ComplementaryReimagined_r5.9.3 + EuphoriaPatches_1.10.5*') -or ($pack -eq 0 -and $actualPack -notlike '*no shader pack drawing*')){throw "Wrong effective shader pack: $actualPack"}
  Snap "p$pack-before"
  $before=(Status).plugins.reflexium.fg_generated_presents
  foreach($scene in $Scenes){
   Call camera @{x=200.5;y=151;z=20;yaw=0;pitch=0}|Out-Null
   $crop='1120,620,320,180'
   if($scene -eq 'mob'){
    Call camera @{x=200.5;y=151;z=.5;yaw=0;pitch=0}|Out-Null
    Call command @{command='tp @e[tag=rx_text_quick,type=pig] 200.5 151 16'}|Out-Null
    Call command @{command='item replace entity @a weapon.mainhand with carrot'}|Out-Null
    $crop='1120,700,320,180'
   }else{Call command @{command='item replace entity @a weapon.mainhand with air'}|Out-Null}
   if($scene.StartsWith('hand')){$crop='1750,950,320,180';Call camera @{x=200.5;y=151;z=.5;yaw=0;pitch=3}|Out-Null}
   Call wait @{ms=$(if($scene -eq 'mob'){200}else{2000})}|Out-Null
   try{
    if($scene -eq 'hand-walk'){Call key @{name='key.forward';down=$true}|Out-Null}
    elseif($scene -ne 'mob'){Call turn @{dyaw=15;ticks=100;async=$true}|Out-Null}
    $count=if($scene -eq 'mob'){48}else{200};$seconds=if($scene -eq 'mob'){1.5}else{5.5}
    & C:/Users/Ryan/source/repos/Reflexium/tools/wgc-capture/bin/Release/net10.0-windows10.0.26100.0/WgcCapture.exe --pid (Status).pid --out "$ev/p$pack-wgc-$scene" --count $count --seconds $seconds --png true --every 1 --crop $crop --min-interval-ms 0 | Set-Content "$ev/p$pack-wgc-$scene-result.json"
   }finally{Call key @{name='key.forward';down=$false}|Out-Null}
   Snap "p$pack-$scene-after"
  }
  Snap "p$pack-after"
  $delta=(Status).plugins.reflexium.fg_generated_presents-$before
  if($delta -le 0){throw 'no generated presents'}
  Write-Output "FG p$pack generated delta=$delta"
 }
 Setting frame_generation off
 Setting reflex_interval_us -1
}else{
 foreach($pack in @(0,1)){
  Setting shaders $pack
  foreach($mode in $Modes){
   Setting upscaler $(if($mode -eq 'native'){'off'}else{'dlss'})
   if($mode -ne 'native'){Setting quality $mode}
   foreach($d in $Distances){
    $name="p$pack-$mode-d$d"
    if(Test-Path "$ev/$name-pan.json"){continue}
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
