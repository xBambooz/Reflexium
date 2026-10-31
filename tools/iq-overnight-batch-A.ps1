param([string]$StartAt='baseline398-mid-rate',[int]$Port=25742,[int]$DebugPort=25572)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
$current="$root/build/libs/Reflexium-1.2.0-alpha-fabric-26.3.jar"
$oldFg="$env:TEMP/rxA-continuation/398258a.jar"
$oldHand='C:/Users/Ryan/.t3/worktrees/Reflexium/rxA-baseline-6df62db/build/libs/Reflexium-1.2.0-alpha-fabric-26.3.jar'
$jobs=@(
 @{Label='baseline398-mid-rate';Task='fg';Jar=$oldFg},
 @{Label='current-absent';Task='text';Jar=$current;NoNameMod=$true},
 @{Label='current-real-meshelium';Task='mesh';Jar=$current},
 @{Label='baseline398-mob-rerun';Task='fgmob';Jar=$oldFg},
 @{Label='current-mob-rerun';Task='fgmob';Jar=$current}
)
foreach($skin in @(0,1)){foreach($anim in @(0,1)){
 $jobs+=@{Label="hand-baseline-s${skin}a$anim";Task='hand';Jar=$oldHand;Skin=$skin;Animations=$anim}
 $jobs+=@{Label="hand-current-s${skin}a$anim";Task='hand';Jar=$current;Skin=$skin;Animations=$anim}
}}
$startIndex=[Array]::FindIndex([object[]]$jobs,[Predicate[object]]{param($job) $job.Label -eq $StartAt})
if($startIndex -lt 0){throw "Unknown starting session: $StartAt"}
$jobs=$jobs[$startIndex..($jobs.Count-1)]
foreach($job in $jobs){
 Write-Output "START $($job.Label) $(Get-Date -Format o)"
 try{& "$PSScriptRoot/iq-overnight-session-A.ps1" @job -Port $port -DebugPort $debugPort -WaitForC -WaitForP}
 catch{
  Write-Output "FAILED $($job.Label): $_"
  if(Test-Path 'C:/Users/Ryan/source/repos/Reflexium-gpu.lock'){
   $l=Get-Content 'C:/Users/Ryan/source/repos/Reflexium-gpu.lock'|ConvertFrom-Json
   if($l.owner -eq 'worker-A-overnight'){throw 'cleanup incomplete, retaining owned lock'}
  }
 }
 $port++;$debugPort++
 # Give waiting GPU workers an acquisition window after every owned client exits.
 for($i=0;$i -lt $(if($job.Task -eq 'text'){10}else{2});$i++){Start-Sleep -Seconds 30}
}
