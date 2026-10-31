param([int]$Port,[int]$DebugPort,[string]$Jar,[string]$Label,[ValidateSet('fg','fgmob','text','hand','mesh')][string]$Task,[switch]$NoNameMod,[int]$Skin=1,[int]$Animations=1,[switch]$WaitForP,[switch]$WaitForC,[int]$ResumeLockPid=0,[switch]$KeepSlot)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
$lock='C:/Users/Ryan/source/repos/Reflexium-gpu.lock'
$resuming=$false
if($ResumeLockPid){
 $held=Get-Content $lock -Raw|ConvertFrom-Json
 $callerSlot=$ResumeLockPid -eq $PID -and $held.worker_pid -eq $PID
 $live=if($callerSlot){Get-Process -Id $held.client_pid -ErrorAction SilentlyContinue}else{Get-Process -Id $ResumeLockPid -ErrorAction SilentlyContinue}
 if($held.owner -ne 'worker-A-overnight' -or $held.pid -ne $ResumeLockPid -or $live){throw 'Cannot resume an unowned or live-client reservation'}
 $resuming=$true
}
foreach($requiredOwner in @($(if($WaitForC -and !$resuming){'worker-C-overnight'}),$(if($WaitForP -and !$resuming){'worker-P'}))){
 if(!$requiredOwner){continue}
 Write-Output "Scheduling gate: waiting for $requiredOwner to start and end before $Label"
 $seen=$false
 while($true){
  $owner=$null
  if(Test-Path $lock){try{$owner=(Get-Content $lock -Raw|ConvertFrom-Json).owner}catch{}}
  if($owner -eq $requiredOwner){$seen=$true}
  elseif($seen){break}
  Start-Sleep -Seconds 2
 }
}
while(!$resuming){
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
 if($Task -eq 'mesh'){
  $env:MC_HARNESS_PRODUCTION='1'
  Copy-Item -LiteralPath $Jar -Destination "$root/run/mods/Reflexium-overnight-A.jar"
 }
 & "$PSScriptRoot/iq-overnight-launch-A.ps1" -Port $Port -DebugPort $DebugPort -Jar $Jar | ConvertTo-Json -Depth 20 | Set-Content "$ev/launch.json"
 . "$PSScriptRoot/iq-session.ps1" -Port $Port
 $s=$null
 for($ready=0;$ready -lt 120 -and !$s;$ready++){
  try{$s=Status}catch{Start-Sleep -Seconds 1}
 }
 if(!$s){throw 'client harness did not become ready'}
 for($ready=0;$ready -lt 30 -and !$s.plugins.reflexium;$ready++){Start-Sleep -Seconds 1;$s=Status}
 if(!$s.plugins.reflexium){throw 'Reflexium plugin absent; runtime classpath alone is not a production mod install'}
 if($s.game_directory -ne "$root\run"){throw 'wrong run directory'}
 $command=(Get-CimInstance Win32_Process -Filter "ProcessId=$($s.pid)").CommandLine
 $arguments=($command+(Get-Content "$root/build/loom-cache/argFiles/runClient" -Raw)).Replace('\','/')
 $selected=$arguments.Contains($Jar.Replace('\','/'))
 @{jar=$Jar;sha256=(Get-FileHash $Jar).Hash;pid=$s.pid;jar_in_runtime_arguments=$selected;run_directory=$s.game_directory}|ConvertTo-Json|Set-Content "$ev/artifact.json"
 if(!$selected){throw 'selected jar absent from runtime arguments'}
 $l=Get-Content $lock|ConvertFrom-Json;$l.pid=$s.pid;$l|ConvertTo-Json|Set-Content $lock
 Get-ChildItem "$root/run/mods/*.jar"|ForEach-Object{[pscustomobject]@{file=$_.Name;sha256=(Get-FileHash $_.FullName).Hash}}|ConvertTo-Json|Set-Content "$ev/mods.json"
 Call join @{world='Reflexium Vitrail Smoke';timeout_s=120}|Out-Null
 switch($Task){
  fg {& "$PSScriptRoot/iq-overnight-A.ps1" -Port $Port -Task fg -Label $Label -Interval 60000}
  fgmob {& "$PSScriptRoot/iq-overnight-A.ps1" -Port $Port -Task fg -Label $Label -Interval 60000 -Scenes @('mob')}
  text {
   & "$PSScriptRoot/iq-overnight-A.ps1" -Port $Port -Task signs -Label "$Label-signs"
   & "$PSScriptRoot/iq-overnight-light-A.ps1" -Port $Port -Label "$Label-thin" -ThinOnly
   & "$PSScriptRoot/iq-overnight-names-A.ps1" -Port $Port -Label "$Label-names"
  }
  hand {
   & "$PSScriptRoot/iq-overnight-hand-A.ps1" -Port $Port -Label $Label
   if($Label -eq 'hand-current-s1a1'){& "$PSScriptRoot/iq-overnight-names-A.ps1" -Port $Port -Label 'current-present-names-rerun'}
  }
  mesh {
   & "$PSScriptRoot/iq-overnight-A.ps1" -Port $Port -Task signs -Label "$Label-signs" -Distances @(8) -Modes @('native','quality','ultra_performance')
   & "$PSScriptRoot/iq-overnight-light-A.ps1" -Port $Port -Label "$Label-thin" -ThinOnly
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
   Remove-Item -LiteralPath "$root/run/mods/Reflexium-overnight-A.jar"
   Move-Item -LiteralPath "$root/run/mods" -Destination "$root/run/mods-real"
   Move-Item -LiteralPath "$root/run/mods-minimal" -Destination "$root/run/mods"
  }
  $l=Get-Content $lock|ConvertFrom-Json
  if($l.owner -ne 'worker-A-overnight'){throw 'lock owner changed'}
  if($Label -eq 'hand-current-s1a1'){
   try{
    $ErrorActionPreference='Continue'
    & "$root/gradlew.bat" build -x installReflexium --no-daemon --console=plain *> "$ev/final-build.log"
   }finally{$ErrorActionPreference='Stop'}
   if($LASTEXITCODE -ne 0){throw 'Final build failed; retaining lock for review'}
  }
  # Keep CPU-heavy evidence compression inside the GPU reservation, away from timed workers.
  try{
   Write-Output "Checkpointing completed evidence for $Label"
   $ErrorActionPreference='Continue'
   & git -C $root add -A -- docs/validation/overnight-A-2026-10-31 docs/validation/iq-issues-2026-10-30.md tools/iq-overnight-A.ps1 tools/iq-overnight-session-A.ps1 tools/iq-overnight-hand-A.ps1 tools/iq-overnight-names-A.ps1 tools/iq-overnight-light-A.ps1 tools/WgcPeriodEvidence.java *> "$root/run/overnight-stage-$Port.log"
   if($LASTEXITCODE -ne 0){throw "git add failed, see run/overnight-stage-$Port.log"}
   & git -C $root commit -qm "Checkpoint overnight A $Label evidence" *> "$root/run/overnight-commit-$Port.log"
   if($LASTEXITCODE -ne 0){throw "git commit failed, see run/overnight-commit-$Port.log"}
   & git -C $root rev-parse HEAD | Set-Content "$ev/checkpoint-sha.txt"
  }catch{$_|Out-String|Set-Content "$ev/checkpoint-error.txt"}finally{$ErrorActionPreference='Stop'}
  if($KeepSlot){
   $l.pid=$PID
   $l|Add-Member -NotePropertyName worker_pid -NotePropertyValue $PID -Force
   $l|Add-Member -NotePropertyName client_pid -NotePropertyValue $s.pid -Force
   $l|ConvertTo-Json|Set-Content $lock
   "Exited PID $($s.pid); retained slot for worker PID $PID"|Set-Content "$ev/retained.txt"
  }else{
   Remove-Item -LiteralPath $lock
   "Exited PID $($s.pid) and released lock"|Set-Content "$ev/released.txt"
  }
 }catch{$_|Out-String|Set-Content "$ev/cleanup-error.txt";throw}
}
