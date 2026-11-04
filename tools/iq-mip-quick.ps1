param([int]$Port)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/iq-session.ps1" -Port $Port
$root=Split-Path $PSScriptRoot
$evidence=Join-Path $root 'docs/validation/iq-issues-2026-10-30/continuation/mip-quick'
$raw=Join-Path $root 'run/iq-continuation/mip-quick'
New-Item -ItemType Directory -Force $evidence,$raw|Out-Null
$lock=Get-Content C:/Users/Ryan/source/repos/Reflexium-gpu.lock|ConvertFrom-Json
if($lock.owner -ne 'worker-A' -or (Status).game_directory -ne "$root\run"){throw 'ownership mismatch'}
Call command @{command='gamemode spectator @a'}|Out-Null
Call command @{command='tick freeze'}|Out-Null
Setting frame_generation off
Setting shaders 1
try{
    foreach($arm in @('native','0','1')){
        Setting upscaler $(if($arm -eq 'native'){'off'}else{'dlss'})
        if($arm -ne 'native'){Setting quality ultra_performance; Setting mip_bias_offset $arm}
        Call camera @{x=200.5;y=110;z=150;yaw=90;pitch=10}|Out-Null
        Call wait @{ms=10000}|Out-Null
        $s=Status
        if($s.plugins.reflexium.last_error){throw 'mip render error'}
        SaveStatus "$arm-before"
        Call screenshot @{path="$raw/$arm.png"}|Out-Null
        if($arm -eq 'native'){continue}
        Call reflexium @{capture="bias$arm";dir=$raw}|ConvertTo-Json -Depth 40|Set-Content "$evidence/$arm-input.json"
        foreach($motion in @('static','pan')){
            $seq=Call capture_sequence @{dir="$raw/$arm-$motion";count=10;interval_ms=150;dyaw=$(if($motion -eq 'pan'){4}else{0});ticks=40}
            $seq|ConvertTo-Json -Depth 40|Set-Content "$evidence/$arm-$motion.json"
            $seq.frames|ForEach-Object{"$($_.path)`t$($_.camera.yaw)"}|Set-Content "$evidence/$arm-$motion.tsv"
        }
        SaveStatus "$arm-after"
        Write-Output "Captured quick Ultra pack bias $arm"
    }
}finally{Setting mip_bias_offset 0}
