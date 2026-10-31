param([int]$Port = 25731, [int]$DebugPort = 25732)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$lockPath = 'C:/Users/Ryan/source/repos/Reflexium-gpu.lock'
$stream = $null
for ($attempt = 0; $attempt -lt 6; $attempt++) {
    try {
        $stream = [IO.File]::Open($lockPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        break
    } catch [IO.IOException] {
        if ($attempt -eq 5) { Write-Output 'GPU busy, retry after current owner finishes'; exit 2 }
        Start-Sleep -Seconds 5
    }
}
$writer = [IO.StreamWriter]::new($stream)
$writer.Write((@{owner='worker-C-meshelium';pid=$PID;started=(Get-Date).ToString('o');purpose='quick Meshelium compatibility'} | ConvertTo-Json -Compress))
$writer.Dispose()
# Keep the lock until authenticated quit and exact process exit are confirmed by the caller.
$env:MC_HARNESS_JVM_ARGS='["-XX:StackShadowPages=32","-Dmixin.debug.export=true"]'
& C:/Users/Ryan/source/repos/mc-agent-harness/tools/launch.ps1 -Project $root -RunDirectory "$root/run" `
    -Port $Port -Monitor primary -Width 2560 -Height 1440 -Fullscreen `
    -GradleProperties @{rxDebug=[string]$DebugPort;rxIqUser='RxMeshC'} -TimeoutSeconds 40 -Pending
