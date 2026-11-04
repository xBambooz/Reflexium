param([int]$Port=25721)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/iq-session.ps1" -Port $Port
$root=Split-Path $PSScriptRoot
if ((Status).game_directory -ne "$root\run") {throw "isolated directory mismatch"}
$ev="$root/docs/validation/iq-issues-2026-10-30/world-text-followup"
$raw="$root/run/iq-followup"
New-Item -ItemType Directory -Force $ev,$raw|Out-Null
function Snap($name){$s=Status;if($s.game_directory -ne "$root\run" -or $s.plugins.reflexium.last_error){throw 'ownership/render error'};$s|ConvertTo-Json -Depth 40|Set-Content "$ev/$name.json";Call screenshot @{path="$raw/$name.png"}|Out-Null;Write-Output "$name nodes=$($s.plugins.reflexium.observed.world_text_nodes)"}
if((Get-Content C:/Users/Ryan/source/repos/Reflexium-gpu.lock|ConvertFrom-Json).owner -ne 'worker-A'){throw 'lock'}
if (!(Status).world_loaded) { Call join @{world='Reflexium Vitrail Smoke';timeout_s=120}|Out-Null }
Setting exclusive_fullscreen 0
Call fullscreen @{fullscreen=0}|Out-Null
Call fullscreen @{fullscreen=1;activate=1}|Out-Null
Setting frame_generation off
Setting shaders 1
Setting dlss_preset 12
Call command @{command='gamemode creative @a'}|Out-Null
Call command @{command='tick freeze'}|Out-Null
Call command @{command='time set noon'}|Out-Null
Call command @{command='weather clear'}|Out-Null
Call camera @{x=200.5;y=151;z=20;yaw=0;pitch=0}|Out-Null
foreach($c in @('fill 197 151 27 203 156 29 air','kill @e[tag=rx_text_quick]','setblock 199 152 28 oak_sign[rotation=0]','setblock 201 152 28 oak_sign[rotation=0]','setblock 199 155 28 stone','setblock 199 154 28 oak_hanging_sign[rotation=0]')){Call command @{command=$c}|Out-Null}
& "$PSScriptRoot/iq-followup-fixture.ps1" -Port $Port
foreach($mode in @('off','ultra_performance')){
 Setting upscaler $(if($mode -eq 'off'){'off'}else{'dlss'})
 if($mode -ne 'off'){Setting quality $mode}
 foreach($d in @(8,16)){foreach($angle in @(0,65)){
  $r=$angle*[Math]::PI/180
  Call camera @{x=(200.5+$d*[Math]::Sin($r));y=151;z=(28-$d*[Math]::Cos($r));yaw=$angle;pitch=0}|Out-Null
  Call wait @{ms=2500}|Out-Null
  Snap "sign-$mode-d$d-a$angle"
 }}
}
Setting quality quality
Call camera @{x=200.5;y=151;z=20;yaw=0;pitch=0}|Out-Null
Call wait @{ms=2500}|Out-Null
Snap sign-quality-d8-a0
Setting quality ultra_performance
Call camera @{x=200.5;y=151;z=20;yaw=0;pitch=-35}|Out-Null
Call command @{command='tick unfreeze'}|Out-Null
Call key @{name='key.forward';down=$true;ticks=4}|Out-Null
Call wait @{ms=400}|Out-Null
Call key @{name='key.forward';down=$false}|Out-Null
Snap first-person-up
Call command @{command='tick freeze'}|Out-Null
Call key @{name='key.togglePerspective';down=$true;ticks=1}|Out-Null
Call wait @{ms=1000}|Out-Null
Snap third-person-back
Call key @{name='key.togglePerspective';down=$true;ticks=1}|Out-Null
Call wait @{ms=300}|Out-Null
Call key @{name='key.togglePerspective';down=$true;ticks=1}|Out-Null
Call camera @{x=200.5;y=151;z=20;yaw=0;pitch=0}|Out-Null
Call command @{command='summon minecraft:armor_stand 203.5 151 28 {Tags:["rx_text_quick"],CustomName:"FG Name",CustomNameVisible:1b,NoGravity:1b}'}|Out-Null
Call command @{command='tick step 4'}|Out-Null
Setting vsync 0
Setting frame_generation dlss_multi
Setting fg_multiplier 3
Call window @{monitor='primary';x=0;y=0;width=2560;height=1440;behind=$false;activate=$true}|Out-Null
Call wait @{ms=3000}|Out-Null
Snap fg-before
Call reflexium @{capture='fg-contract';dir=$raw}|ConvertTo-Json -Depth 40|Set-Content "$ev/fg-contract.json"
Call capture_sequence @{dir="$raw/fg-pan";count=5;interval_ms=150;dyaw=8;ticks=30}|ConvertTo-Json -Depth 40|Set-Content "$ev/fg-pan.json"
& C:/Users/Ryan/source/repos/Reflexium/tools/wgc-capture/bin/Release/net10.0-windows10.0.26100.0/WgcCapture.exe --pid (Status).pid --out "$raw/presentation" --count 12 --seconds 4 --png true --every 1 | Set-Content "$ev/presentation-result.json"
Snap fg-after
Write-Output 'Quick captures complete'
