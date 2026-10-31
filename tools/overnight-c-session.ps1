param([int]$Port = 25781, [int]$DebugPort = 25881)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$lockPath = 'C:/Users/Ryan/source/repos/Reflexium-gpu.lock'
$evidence = "$root/docs/validation/overnight-c-2026-10-31"
Set-Location $root
while ($true) {
    try { $stream = [IO.File]::Open($lockPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None); break }
    catch [IO.IOException] { Write-Output "$(Get-Date -Format o) GPU occupied, retrying in 60 seconds"; Start-Sleep 60 }
}
$started = [datetimeoffset]::Now
try {
    $bytes = [Text.Encoding]::UTF8.GetBytes((@{owner='worker-C-overnight';pid=$PID;started=$started.ToString('o');purpose='mip and pack IQ validation'} | ConvertTo-Json))
    $stream.Write($bytes, 0, $bytes.Length)
} finally { $stream.Dispose() }
Write-Output "$(Get-Date -Format o) Acquired GPU lock"
$options = Get-Content "$root/run/options.txt"
$options = $options -replace '^preferredGraphicsBackend:.*','preferredGraphicsBackend:"vulkan"' -replace '^soundCategory_master:.*','soundCategory_master:0.0' -replace '^exclusiveFullscreen:.*','exclusiveFullscreen:false'
[IO.File]::WriteAllLines("$root/run/options.txt", $options)
$env:MC_HARNESS_JVM_ARGS = '["-XX:StackShadowPages=32"]'
& C:/Users/Ryan/source/repos/mc-agent-harness/tools/launch.ps1 -Project $root -RunDirectory "$root/run" -Port $Port -Monitor primary -Width 2560 -Height 1440 -Fullscreen -GradleProperties @{rxDebug=[string]$DebugPort;rxIqUser='RxWhiteA'} -TimeoutSeconds 40 -Pending
$sessionFile = "C:/Users/Ryan/source/repos/mc-agent-harness/instances/$Port/session.json"
do {
    Start-Sleep 5
    $session = Get-Content $sessionFile | ConvertFrom-Json
    if (!$session.game_pid) {
        try {
            $status = Invoke-RestMethod "http://127.0.0.1:$Port/status" -Headers @{Authorization="Bearer $($session.token)"} -TimeoutSec 5
            $client = Get-CimInstance Win32_Process -Filter "ProcessId=$($status.pid)"
            if ($status.game_directory -ne $session.game_directory -or !$client.CommandLine.Contains($session.jar) -or !$client.CommandLine.Contains($session.game_directory)) { throw 'Pending launch ownership mismatch' }
            $session | Add-Member -NotePropertyName game_pid -NotePropertyValue $status.pid -Force
            $session | ConvertTo-Json -Depth 10 | Set-Content $sessionFile
        } catch { Write-Output 'Waiting for authenticated owned client status' }
    }
} until ($session.game_pid -or ([datetimeoffset]::Now - $started).TotalMinutes -gt 5)
if (!$session.game_pid) { throw 'No owned game PID. Inspect launch before releasing GPU lock.' }
Write-Output "Owned client PID $($session.game_pid), harness $Port"
while (Get-Process -Id $session.game_pid -ErrorAction SilentlyContinue) {
    if (([datetimeoffset]::Now - $started).TotalMinutes -gt 38) {
        & "$PSScriptRoot/iq-followup-quit.ps1" -Port $Port | Out-File "$evidence/quit-$Port.json"
    }
    Start-Sleep 5
}
Add-Type 'using System; using System.Runtime.InteropServices; public static class CFocus { [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr w); }'
@{previous_foreground=$session.foreground_before;restored=[CFocus]::SetForegroundWindow([IntPtr]::new($session.foreground_before));client_pid=$session.game_pid;exited=$true} | ConvertTo-Json | Set-Content "$evidence/exit-$Port.json"
$lock = Get-Content $lockPath | ConvertFrom-Json
if ($lock.owner -ne 'worker-C-overnight' -or $lock.pid -ne $PID) { throw 'Lock ownership changed' }
Remove-Item -LiteralPath $lockPath
Write-Output "$(Get-Date -Format o) Client exited, foreground restored, lock released"
