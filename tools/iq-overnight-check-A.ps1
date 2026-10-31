param([string]$Label='current-present-signs',[int]$Expected=32)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
$ev="$root/docs/validation/overnight-A-2026-10-31/$Label"
$arms=@(Get-ChildItem $ev -Filter '*.json'|Where-Object {$_.Name -match '^p[01]-(native|native_aa|quality|ultra_performance)-d(4|8|16|24)\.json$'})
if($arms.Count -ne $Expected){throw "expected $Expected sign arms, found $($arms.Count)"}
$rows=@()
foreach($f in $arms){
 $s=Get-Content $f.FullName|ConvertFrom-Json;$r=$s.plugins.reflexium
 $static=Get-Content "$ev/$($f.BaseName)-static.json"|ConvertFrom-Json
 $pan=Get-Content "$ev/$($f.BaseName)-pan.json"|ConvertFrom-Json
 if($static.frames.Count -ne 3 -or $pan.frames.Count -ne 26){throw "incomplete sequence $($f.Name)"}
 if([Math]::Abs($s.camera.yaw) -gt .01 -or [Math]::Abs($pan.frames[0].camera.yaw) -gt .5 -or [Math]::Abs($pan.frames[-1].camera.yaw-15) -gt .01){throw "bad pan pose $($f.Name)"}
 foreach($frame in @($s)+@($static.frames)+@($pan.frames)){
  $fr=$frame.plugins.reflexium
  if($fr.last_error -or $fr.frame_generation -ne 'off'){throw "render error/FG enabled $($f.Name)"}
  if($f.BaseName -notmatch '-native-d' -and (!$fr.upscaling_active -or !$fr.observed.world_text_prepared)){throw "inactive reconstruction/text $($f.Name)"}
  if(!$frame.window.fullscreen.borderless -or $frame.window.fullscreen.exclusive_fullscreen -or $frame.window.fullscreen.display_id -ne 1){throw "wrong monitor/fullscreen $($f.Name)"}
 }
 $rows+=@{arm=$f.BaseName;static_count=$static.frames.Count;pan_count=$pan.frames.Count;first_yaw=$pan.frames[0].camera.yaw;last_yaw=$pan.frames[-1].camera.yaw;render=$r.render_size;display=$r.display_size;nodes=$r.observed.world_text_nodes;pack=$r.pack}
}
$rows|ConvertTo-Json -Depth 5|Set-Content "$ev/verified-matrix.json"
Write-Output "Verified $($arms.Count) sign arms, $($arms.Count*30) snapshots/sequence frames. This checks acquisition, not visual acceptance."
