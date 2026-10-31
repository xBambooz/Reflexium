param([int]$Port = 25731)
$ErrorActionPreference = 'Stop'
$session = Get-Content "C:/Users/Ryan/source/repos/mc-agent-harness/instances/$Port/session.json" | ConvertFrom-Json
$headers = @{Authorization="Bearer $($session.token)"}
$base = "http://127.0.0.1:$Port"
$status = Invoke-RestMethod "$base/status" -Headers $headers
$process = Get-CimInstance Win32_Process -Filter "ProcessId=$($session.game_pid)"
if ($status.pid -ne $session.game_pid -or $status.game_directory -ne $session.game_directory `
    -or !$process.CommandLine.Contains($session.jar) -or !$process.CommandLine.Contains($session.game_directory)) {
    throw 'Client ownership mismatch'
}
$status = Invoke-RestMethod "$base/status" -Headers $headers
if ($status.pid -ne $session.game_pid) { throw 'Client PID changed' }
Invoke-RestMethod "$base/quit" -Headers $headers -Method Post -ContentType 'application/json' -Body '{"exit":true}' | Out-Null
for ($attempt=0; $attempt -lt 20; $attempt++) {
    if (!(Get-Process -Id $session.game_pid -ErrorAction SilentlyContinue)) { break }
    Start-Sleep -Milliseconds 500
}
if (Get-Process -Id $session.game_pid -ErrorAction SilentlyContinue) { throw 'Client still alive; keeping GPU lock' }
Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public static class MeshFocus { [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hwnd); }'
[MeshFocus]::SetForegroundWindow([IntPtr][long]$session.foreground_before) | Out-Null
$lockPath = 'C:/Users/Ryan/source/repos/Reflexium-gpu.lock'
$lock = Get-Content $lockPath | ConvertFrom-Json
if ($lock.owner -ne 'worker-C-meshelium') { throw 'GPU lock owner changed' }
Remove-Item -LiteralPath $lockPath
Write-Output "Owned client $($session.game_pid) exited; restored foreground and released GPU lock"
