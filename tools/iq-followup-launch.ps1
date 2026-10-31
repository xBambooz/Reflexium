param([int]$Port=25721, [int]$DebugPort=25591)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
$lockPath='C:/Users/Ryan/source/repos/Reflexium-gpu.lock'
$stream=[IO.File]::Open($lockPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
try {$bytes=[Text.Encoding]::UTF8.GetBytes((@{owner='worker-A';pid=$PID;started=(Get-Date).ToString('o');purpose='quick world text follow-up'}|ConvertTo-Json));$stream.Write($bytes,0,$bytes.Length)} finally {$stream.Dispose()}
$options=Get-Content "$root/run/options.txt"
$options=$options -replace '^preferredGraphicsBackend:.*','preferredGraphicsBackend:"vulkan"' -replace '^soundCategory_master:.*','soundCategory_master:0.0' -replace '^exclusiveFullscreen:.*','exclusiveFullscreen:false'
[IO.File]::WriteAllLines("$root/run/options.txt",$options)
$env:MC_HARNESS_JVM_ARGS='["-XX:StackShadowPages=32"]'
& C:/Users/Ryan/source/repos/mc-agent-harness/tools/launch.ps1 -Project $root -RunDirectory "$root/run" -Port $Port -Monitor primary -Width 2560 -Height 1440 -Fullscreen -GradleProperties @{rxDebug=[string]$DebugPort;rxIqUser='RxWhiteA'} -TimeoutSeconds 40 -Pending
