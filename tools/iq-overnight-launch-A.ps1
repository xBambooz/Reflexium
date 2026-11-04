param([int]$Port,[int]$DebugPort,[string]$Jar)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
if((Get-Content C:/Users/Ryan/source/repos/Reflexium-gpu.lock|ConvertFrom-Json).owner -ne 'worker-A-overnight'){throw 'lock ownership'}
$options=Get-Content "$root/run/options.txt"
$options=$options -replace '^preferredGraphicsBackend:.*','preferredGraphicsBackend:"vulkan"' -replace '^soundCategory_master:.*','soundCategory_master:0.0' -replace '^maxFps:.*','maxFps:60'
[IO.File]::WriteAllLines("$root/run/options.txt",$options)
$env:MC_HARNESS_JVM_ARGS='["-XX:StackShadowPages=32","-Dreflexium.streamline=true"]'
$props=@{rxDebug=[string]$DebugPort;rxIqUser='RxWhiteA'}
if($Jar){$props.rxBenchJar=$Jar}
& C:/Users/Ryan/source/repos/mc-agent-harness/tools/launch.ps1 -Project $root -RunDirectory "$root/run" -Port $Port -Monitor primary -Width 2560 -Height 1440 -Fullscreen -GradleProperties $props -TimeoutSeconds 40 -Pending
