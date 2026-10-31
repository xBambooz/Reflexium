param([int]$Port,[string]$Label)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/iq-session.ps1" -Port $Port
$root=Split-Path $PSScriptRoot
$ev="$root/docs/validation/overnight-A-2026-10-31/$Label"
New-Item -ItemType Directory -Force $ev|Out-Null
if((Status).game_directory -ne "$root\run" -or (Get-Content C:/Users/Ryan/source/repos/Reflexium-gpu.lock|ConvertFrom-Json).owner -ne 'worker-A-overnight'){throw 'ownership'}
$s=Status;$identity=$s.plugins.reflexium
if($identity.player_name -and ($identity.player_name -ne 'RxWhiteA' -or $identity.player_uuid -ne '81c98b84-3355-3072-ad0c-53a63b1196ac' -or $identity.player_skin_model -ne 'WIDE')){throw 'white identity mismatch'}
$cmd=(Get-CimInstance Win32_Process -Filter "ProcessId=$($s.pid)").CommandLine
@{runtime_name=$identity.player_name;runtime_uuid=$identity.player_uuid;runtime_model=$identity.player_skin_model;username_in_command=$cmd.Contains('RxWhiteA');uuid_in_command=$cmd.Contains('81c98b8433553072ad0c53a63b1196ac');legacy_runtime_model_unobservable=(!$identity.player_skin_model)}|ConvertTo-Json|Set-Content "$ev/identity.json"
if(!$cmd.Contains('RxWhiteA') -or !$cmd.Contains('81c98b8433553072ad0c53a63b1196ac')){throw 'offline command identity mismatch'}
if(!(Select-String -Path "$root/run/logs/latest.log" -SimpleMatch 'file/rx-white')){throw 'white texture pack missing'}
function Snap($name){$s=Status;$s|ConvertTo-Json -Depth 40|Set-Content "$ev/$name.json";if($s.plugins.reflexium.last_error){throw 'render error'};Call screenshot @{path="$ev/$name.png"}|Out-Null}
Setting exclusive_fullscreen 0
Call fullscreen @{fullscreen=0}|Out-Null
Call fullscreen @{fullscreen=1;activate=1}|Out-Null
Setting frame_generation off
Setting bob_view 1
foreach($c in @('tick unfreeze','gamemode survival @a','time set noon','weather clear','item replace entity @a weapon.mainhand with air','item replace entity @a weapon.offhand with air','fill 196 150 0 205 150 30 stone','kill @e[tag=rx_text_quick]','kill @e[tag=rx_overnight_pig]')){Call command @{command=$c}|Out-Null}
foreach($pack in @(0,1)){
 Setting shaders $pack
 foreach($mode in @('native','quality','ultra_performance')){
  Setting upscaler $(if($mode -eq 'native'){'off'}else{'dlss'})
  if($mode -ne 'native'){Setting quality $mode}
  Call camera @{x=200.5;y=151;z=.5;yaw=0;pitch=3}|Out-Null
  Call wait @{ms=10000}|Out-Null
  $name="p$pack-$mode"
  Snap "$name-idle"
  Call capture_sequence @{dir="$ev/$name-pan";count=12;interval_ms=460;dyaw=15;ticks=100}|ConvertTo-Json -Depth 40|Set-Content "$ev/$name-pan.json"
  Call camera @{x=200.5;y=151;z=.5;yaw=0;pitch=3}|Out-Null
  try{
   Call key @{name='key.forward';down=$true}|Out-Null
   Call capture_sequence @{dir="$ev/$name-walk";count=8;interval_ms=150}|ConvertTo-Json -Depth 40|Set-Content "$ev/$name-walk.json"
   if($mode -ne 'native'){Call reflexium @{capture="$name-walk";dir=$ev}|ConvertTo-Json -Depth 40|Set-Content "$ev/$name-walk-capture.json"}
  }finally{Call key @{name='key.forward';down=$false}|Out-Null}
  Call wait @{ms=1000}|Out-Null
  $a=(Status).camera
  Call wait @{ms=500}|Out-Null
  $b=(Status).camera
  @{before=$a;after=$b;stopped=([Math]::Abs($a.z-$b.z) -lt .01)}|ConvertTo-Json|Set-Content "$ev/$name-key-release.json"
  if([Math]::Abs($a.z-$b.z) -ge .01){throw 'walking did not stop'}
  Call key @{name='key.attack';down=$true;ticks=1}|Out-Null
  Snap "$name-swing"
  Call capture_sequence @{dir="$ev/$name-swing";count=4;interval_ms=120}|ConvertTo-Json -Depth 40|Set-Content "$ev/$name-swing-sequence.json"
  Call command @{command='item replace entity @a weapon.mainhand with diamond_sword'}|Out-Null
  Snap "$name-equip"
  Call capture_sequence @{dir="$ev/$name-equip";count=4;interval_ms=120}|ConvertTo-Json -Depth 40|Set-Content "$ev/$name-equip-sequence.json"
  Call command @{command='item replace entity @a weapon.mainhand with carrot'}|Out-Null
  Snap "$name-item-swap"
  Call capture_sequence @{dir="$ev/$name-item-swap";count=4;interval_ms=120}|ConvertTo-Json -Depth 40|Set-Content "$ev/$name-item-swap-sequence.json"
  Call command @{command='item replace entity @a weapon.mainhand with air'}|Out-Null
  Write-Output "Completed hand $Label $name"
 }
}
