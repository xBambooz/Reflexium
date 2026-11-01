param([int]$Port = 25781, [int]$DebugPort = 25881, [switch]$NoMesh, [switch]$DisableReflexium, [switch]$RemainingChecks)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$lockPath = 'C:/Users/Ryan/source/repos/Reflexium-gpu.lock'
$evidence = "$root/docs/validation/overnight-c-2026-10-31"
Set-Location $root
while ($true) {
    try { $stream = [IO.File]::Open($lockPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None); break }
    catch [IO.IOException] { Write-Output "$(Get-Date -Format o) GPU occupied, retrying in 10 seconds"; Start-Sleep 10 }
}
$started = [datetimeoffset]::Now
try {
    $bytes = [Text.Encoding]::UTF8.GetBytes((@{owner='worker-C-overnight';pid=$PID;started=$started.ToString('o');purpose='mip and pack IQ validation'} | ConvertTo-Json))
    $stream.Write($bytes, 0, $bytes.Length)
} finally { $stream.Dispose() }
Write-Output "$(Get-Date -Format o) Acquired GPU lock"
$build=Start-Process -FilePath "$root/gradlew.bat" -ArgumentList 'build','-x','installReflexium','--no-daemon','--console=plain' -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput "$evidence/build-$Port.log" -RedirectStandardError "$evidence/build-$Port.err.log" -Wait -PassThru
$build.WaitForExit()
if ($build.ExitCode -ne 0) {
    Remove-Item -LiteralPath $lockPath
    throw 'Full build failed before client launch'
}
Add-Type 'using System; using System.Runtime.InteropServices; public static class CFocus { [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr w); }'
$stages=if($RemainingChecks){@('mesh-off','mesh-off-retry','no-reflexium')}else{@('manual')}
foreach($stage in $stages) {
if ($RemainingChecks -and (([datetimeoffset]::Now-$started).TotalMinutes -gt 30 -or ($stage -eq 'mesh-off-retry' -and (Test-Path "$evidence/meshelium/mesh-off/native-1.json")))) { $Port++;$DebugPort++;continue }
if ($RemainingChecks) { $NoMesh=$stage -like 'mesh-off*'; $DisableReflexium=$stage -eq 'no-reflexium' }
$options = Get-Content "$root/run/options.txt"
$chatBinding=$options | Where-Object {$_ -match '^key_key.chat:'}
$irisBefore=$null
if ($DisableReflexium) {
    # Harness mapping lookup requires a key.* name; alias chat to Iris's reload key in this copy only.
    $options=$options -replace '^key_key.chat:.*','key_key.chat:key.keyboard.r'
    $irisBefore=[IO.File]::ReadAllText("$root/run/config/iris.properties")
    [IO.File]::WriteAllText("$root/run/config/iris.properties",($irisBefore -replace 'enableDebugOptions=false','enableDebugOptions=true'))
}
$options = $options -replace '^preferredGraphicsBackend:.*','preferredGraphicsBackend:"vulkan"' -replace '^soundCategory_master:.*','soundCategory_master:0.0' -replace '^exclusiveFullscreen:.*','exclusiveFullscreen:false'
[IO.File]::WriteAllLines("$root/run/options.txt", $options)
$meshBackup=$null
$meshOriginal=$null
if ($NoMesh) {
    $mesh=Get-ChildItem "$root/run/mods" -Filter 'meshelium*.jar' -File
    if (@($mesh).Count -ne 1) {throw 'Expected one isolated Meshelium jar'}
    $meshBackup="$root/run/$($mesh.Name).disabled"
    $meshOriginal=$mesh.FullName
    if (Test-Path -LiteralPath $meshBackup) {throw 'Meshelium backup already exists'}
    Move-Item -LiteralPath $mesh.FullName -Destination $meshBackup
}
$env:MC_HARNESS_JVM_ARGS = '["-XX:StackShadowPages=32"]'
$launchProject=if($DisableReflexium){'C:/Users/Ryan/source/repos/mc-agent-harness/examples/empty-project'}else{$root}
$clientStarted=[datetimeoffset]::Now
& C:/Users/Ryan/source/repos/mc-agent-harness/tools/launch.ps1 -Project $launchProject -RunDirectory "$root/run" -Port $Port -Monitor primary -Width 2560 -Height 1440 -Fullscreen -GradleProperties @{rxDebug=[string]$DebugPort;rxIqUser='RxWhiteA'} -TimeoutSeconds 40 -Pending
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
} until ($session.game_pid -or ([datetimeoffset]::Now - $clientStarted).TotalMinutes -gt 5)
if (!$session.game_pid) { throw 'No owned game PID. Inspect launch before releasing GPU lock.' }
Write-Output "Owned client PID $($session.game_pid), harness $Port"
$workload=$null
if ($RemainingChecks) {
    $script=if($NoMesh){'overnight-c-mesh-off.ps1'}else{'overnight-c-no-reflexium.ps1'}
    $workload=Start-Process powershell.exe -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File',"$PSScriptRoot/$script",'-Port',"$Port" -WindowStyle Hidden -RedirectStandardOutput "$root/run/workload-$Port.log" -RedirectStandardError "$root/run/workload-$Port.err.log" -PassThru
    [void]$workload.Handle
}
while (Get-Process -Id $session.game_pid -ErrorAction SilentlyContinue) {
    if (([datetimeoffset]::Now - $started).TotalMinutes -gt 38) {
        & "$PSScriptRoot/iq-followup-quit.ps1" -Port $Port | Out-File "$evidence/quit-$Port.json"
    }
    Start-Sleep 5
}
if ($workload -and !$workload.WaitForExit(10000)) {Stop-Process -Id $workload.Id}
@{previous_foreground=$session.foreground_before;restored=[CFocus]::SetForegroundWindow([IntPtr]::new($session.foreground_before));client_pid=$session.game_pid;exited=$true} | ConvertTo-Json | Set-Content "$evidence/exit-$Port.json"
$lock = Get-Content $lockPath | ConvertFrom-Json
if ($lock.owner -ne 'worker-C-overnight' -or $lock.pid -ne $PID) { throw 'Lock ownership changed' }
if ($meshBackup) {Move-Item -LiteralPath $meshBackup -Destination $meshOriginal}
if ($DisableReflexium) {
    $options=Get-Content "$root/run/options.txt"
    [IO.File]::WriteAllLines("$root/run/options.txt",($options -replace '^key_key.chat:.*',$chatBinding))
    [IO.File]::WriteAllText("$root/run/config/iris.properties",$irisBefore)
}
$Port++;$DebugPort++
}
Remove-Item -LiteralPath $lockPath
Write-Output "$(Get-Date -Format o) Owned clients exited, foreground restored, lock released"
