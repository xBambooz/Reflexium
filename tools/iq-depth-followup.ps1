param([int]$Port=25723)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/iq-session.ps1" -Port $Port
$root=Split-Path $PSScriptRoot
$ev="$root/docs/validation/iq-issues-2026-10-30/world-text-depth-followup"
$raw="$root/run/iq-depth-followup"
New-Item -ItemType Directory -Force $ev,$raw|Out-Null
if ((Get-Content C:/Users/Ryan/source/repos/Reflexium-gpu.lock|ConvertFrom-Json).owner -ne 'worker-A' -or (Status).game_directory -ne "$root\run") {throw 'ownership mismatch'}
function Snap($name,[bool]$layers=$false){$s=Status;if($s.plugins.reflexium.last_error){throw 'render error'};$s|ConvertTo-Json -Depth 40|Set-Content "$ev/$name.json";Call screenshot @{path="$raw/$name.png"}|Out-Null;if($layers){Call reflexium @{capture=$name;dir=$raw}|ConvertTo-Json -Depth 40|Set-Content "$ev/$name-capture.json"};Write-Output "$name nodes=$($s.plugins.reflexium.observed.world_text_nodes)"}
Setting exclusive_fullscreen 0
Call fullscreen @{fullscreen=0}|Out-Null
Call fullscreen @{fullscreen=1;activate=1}|Out-Null
if(!(Status).world_loaded){Call join @{world='Reflexium Vitrail Smoke';timeout_s=120}|Out-Null}
Setting frame_generation off
Setting shaders 1
Setting dlss_preset 12
Setting world_text_ignore_depth 0
Setting world_text_defer 1
foreach($c in @('gamemode creative @a','tick freeze','time set noon','weather clear','kill @e[tag=rx_text_quick]','fill 197 151 27 203 156 29 air','fill 202 151 25 204 154 25 air','setblock 200 152 28 oak_sign[rotation=8]','data merge block 200 152 28 {front_text:{messages:["READ ME","Sharp glyphs","0123456789","Reflexium"],color:"black"}}','tick step 4')){Call command @{command=$c}|Out-Null}
Call camera @{x=200.5;y=151;z=22.042213861078043;yaw=0;pitch=-35}|Out-Null
Call key @{name='key.togglePerspective';down=$true;ticks=1}|Out-Null
Setting upscaler off
Call wait @{ms=10000}|Out-Null
Snap head-native
Setting upscaler dlss
Setting quality ultra_performance
Call wait @{ms=2000}|Out-Null
Snap head-ultra
Call key @{name='key.togglePerspective';down=$true;ticks=1}|Out-Null
Call wait @{ms=200}|Out-Null
Call key @{name='key.togglePerspective';down=$true;ticks=1}|Out-Null
$r=65*[Math]::PI/180
Call camera @{x=(200.5+8*[Math]::Sin($r));y=151;z=(28-8*[Math]::Cos($r));yaw=65;pitch=0}|Out-Null
Call wait @{ms=2000}|Out-Null
foreach($arm in @('on-a','off-a','off-b','on-b')){
 Setting world_text_ignore_depth $(if($arm.StartsWith('off')){'1'}else{'0'})
 Call wait @{ms=500}|Out-Null
 Snap "h2-depth-$arm" $true
}
Setting world_text_ignore_depth 0
Call camera @{x=200.5;y=151;z=20;yaw=0;pitch=0}|Out-Null
foreach($c in @('fill 202 151 25 204 154 25 stone','summon armor_stand 203.5 151 28 {Tags:["rx_text_quick"],CustomName:"Behind Wall",CustomNameVisible:1b,NoGravity:1b}','summon armor_stand 197.5 151 28 {Tags:["rx_text_quick"],CustomName:"Visible Name",CustomNameVisible:1b,NoGravity:1b}','tick step 4')){Call command @{command=$c}|Out-Null}
Call wait @{ms=1000}|Out-Null
Snap wall-normal $true
# Remove both kinds, proving an empty frame does not expose the preceding prepared layer.
foreach($c in @('kill @e[tag=rx_text_quick]','setblock 200 152 28 air','tick step 4')){Call command @{command=$c}|Out-Null}
Call wait @{ms=500}|Out-Null
Snap empty-kinds
Write-Output 'Depth diagnostic and native head checks complete'
