param([int]$Port, [int]$DebugPort, [string]$Jar)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$lock = Get-Content C:/Users/Ryan/source/repos/Reflexium-gpu.lock | ConvertFrom-Json
if ($lock.owner -ne 'worker-A') { throw 'worker A GPU lock required' }
$properties = @{rxDebug=[string]$DebugPort;rxIqUser='RxWhiteA'}
if ($Jar) { $properties.rxBenchJar = $Jar }
$env:MC_HARNESS_JVM_ARGS='["-XX:StackShadowPages=32"]'
& C:/Users/Ryan/source/repos/mc-agent-harness/tools/launch.ps1 -Project $root -RunDirectory "$root/run" `
    -Port $Port -Monitor secondary -Width 2560 -Height 1440 -GradleProperties $properties -TimeoutSeconds 40 -Pending
