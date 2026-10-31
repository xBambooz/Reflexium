param([int]$Port,[int]$DebugPort,[string]$Jar,[string]$Label,[ValidateSet('fg','text','hand','mesh')][string]$Task,[switch]$NoNameMod,[int]$Skin=1,[int]$Animations=1)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
$lock='C:/Users/Ryan/source/repos/Reflexium-gpu.lock'
while($true){
 try{
  $f=[IO.File]::Open($lock,'CreateNew','Write','None')
  try{$b=[Text.Encoding]::UTF8.GetBytes((@{owner='worker-A-overnight';pid=$PID;started=(Get-Date).ToString('o');purpose="IQ $Label"}|ConvertTo-Json));$f.Write($b,0,$b.Length)}finally{$f.Dispose()}
  break
 }catch [IO.IOException]{Write-Output "GPU held, waiting for $Label";Start-Sleep -Seconds 30}
}
$ev="$root/docs/validation/overnight-A-2026-10-31/$Label-session"
New-Item -ItemType Directory -Force $ev|Out-Null
try{
 if($Task -eq 'mesh'){
  $runRoot=[IO.Path]::GetFullPath("$root/run")
  foreach($p in @("$root/run/mods","$root/run/mods-real","$root/run/mods-minimal")){if(![IO.Path]::GetFullPath($p).StartsWith($runRoot+'\')){throw 'mod move outside isolated run'}}
  if(Test-Path "$root/run/mods-minimal"){throw 'mod backup already exists'}
  Move-Item -LiteralPath "$root/run/mods" -Destination "$root/run/mods-minimal"
  Move-Item -LiteralPath "$root/run/mods-real" -Destination "$root/run/mods"
  $swapped=$true
 }
 foreach($spec in @(@('perspectivenametag*',[int](!$NoNameMod)),@('skinlayers3d*',$Skin),@('notenoughanimations*',$Animations))){
  foreach($f in Get-ChildItem "$root/run/mods/$($spec[0])"){
   if($spec[1] -eq 0 -and $f.Name.EndsWith('.jar')){Move-Item -LiteralPath $f.FullName -Destination ($f.FullName+'.disabled')}
   elseif($spec[1] -eq 1 -and $f.Name.EndsWith('.jar.disabled')){Move-Item -LiteralPath $f.FullName -Destination ($f.FullName -replace '\.disabled$','')}
  }
 }
 & "$PSScriptRoot/iq-overnight-launch-A.ps1" -Port $Port -DebugPort $DebugPort -Jar $Jar | ConvertTo-Json -Depth 20 | Set-Content "$ev/launch.json"
 . "$PSScriptRoot/iq-session.ps1" -Port $Port
 $s=Status
 if($s.game_directory -ne "$root\run"){throw 'wrong run directory'}
 $l=Get-Content $lock|ConvertFrom-Json;$l.pid=$s.pid;$l|ConvertTo-Json|Set-Content $lock
 Get-ChildItem "$root/run/mods/*.jar"|ForEach-Object{[pscustomobject]@{file=$_.Name;sha256=(Get-FileHash $_.FullName).Hash}}|ConvertTo-Json|Set-Content "$ev/mods.json"
 Call join @{world='Reflexium Vitrail Smoke';timeout_s=120}|Out-Null
 switch($Task){
  fg {& "$PSScriptRoot/iq-overnight-A.ps1" -Port $Port -Task fg -Label $Label -Interval 60000}
  text {
   & "$PSScriptRoot/iq-overnight-A.ps1" -Port $Port -Task signs -Label "$Label-signs"
   & "$PSScriptRoot/iq-overnight-light-A.ps1" -Port $Port -Label "$Label-thin" -ThinOnly
   & "$PSScriptRoot/iq-overnight-names-A.ps1" -Port $Port -Label "$Label-names"
  }
  hand {& "$PSScriptRoot/iq-overnight-hand-A.ps1" -Port $Port -Label $Label}
  mesh {
   & "$PSScriptRoot/iq-overnight-A.ps1" -Port $Port -Task signs -Label "$Label-signs" -Distances @(8) -Modes @('native','quality','ultra_performance')
   & "$PSScriptRoot/iq-overnight-names-A.ps1" -Port $Port -Label "$Label-names"
  }
 }
}catch{
 $_|Out-String|Set-Content "$ev/error.txt"
 throw
}finally{
 try{
  . "$PSScriptRoot/iq-session.ps1" -Port $Port
  $s=Status
  & "$PSScriptRoot/iq-followup-quit.ps1" -Port $Port | Set-Content "$ev/quit.json"
  for($i=0;$i -lt 60 -and (Get-Process -Id $s.pid -ErrorAction SilentlyContinue);$i++){Start-Sleep -Seconds 1}
  if(Get-Process -Id $s.pid -ErrorAction SilentlyContinue){throw 'owned client did not exit, retaining lock'}
  if($swapped){
   Move-Item -LiteralPath "$root/run/mods" -Destination "$root/run/mods-real"
   Move-Item -LiteralPath "$root/run/mods-minimal" -Destination "$root/run/mods"
  }
  $l=Get-Content $lock|ConvertFrom-Json
  if($l.owner -ne 'worker-A-overnight'){throw 'lock owner changed'}
  Remove-Item -LiteralPath $lock
  "Exited PID $($s.pid) and released lock"|Set-Content "$ev/released.txt"
 }catch{$_|Out-String|Set-Content "$ev/cleanup-error.txt";throw}
}
