param([int]$Port,[int]$DebugPort,[string]$Jar)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
$lockPath='C:/Users/Ryan/source/repos/Reflexium-gpu.lock'
$stream=[IO.File]::Open($lockPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
try {
    $bytes=[Text.Encoding]::UTF8.GetBytes((@{owner='worker-B-dither';pid=$PID;started=(Get-Date).ToString('o');purpose='quick pack dither captures'}|ConvertTo-Json -Compress))
    $stream.Write($bytes,0,$bytes.Length)
} finally { $stream.Dispose() }
$properties=@{rxDebug=[string]$DebugPort;rxIqUser='RxDitherB'}
if($Jar){$properties.rxBenchJar=$Jar}
$env:MC_HARNESS_JVM_ARGS='["-XX:StackShadowPages=32"]'
$result = & C:/Users/Ryan/source/repos/mc-agent-harness/tools/launch.ps1 -Project $root -RunDirectory "$root/run" -Port $Port -Monitor primary -Width 2560 -Height 1440 -Fullscreen -GradleProperties $properties -TimeoutSeconds 40 -Pending


$launch = ($result | Out-String) | ConvertFrom-Json
if ($launch.pid) {
    $lock=Get-Content $lockPath|ConvertFrom-Json
    $lock.pid=$launch.pid
    $lock|ConvertTo-Json|Set-Content $lockPath
}
$result
