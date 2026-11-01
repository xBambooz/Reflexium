param([int]$Port,[switch]$Before)
$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot)
$lockPath='C:/Users/Ryan/source/repos/Reflexium-gpu.lock'
$reservation=Get-Content $lockPath -Raw|ConvertFrom-Json
if($reservation.owner -ne 'view-bob-c9f451d0'){throw 'Not own lock'}
$clients=@(Get-CimInstance Win32_Process|Where-Object Name -Match '^javaw?\.exe$')
if($clients|Where-Object {$_.CommandLine -match 'ModrinthApp[\/]profiles[\/]Fabric 26\.3[\/"]'}){throw 'Real profile active'}
if($clients|Where-Object {$_.CommandLine -match 'devlaunchinjector.Main|KnotClient'}){throw 'Client active'}
$argsList=@('-Dreflexium.streamline=true')
if($Before){$argsList+='-Dreflexium.debug.earlyViewHistory=true'}
$env:MC_HARNESS_JVM_ARGS=ConvertTo-Json -Compress -InputObject $argsList
# Fullscreen permission makes the window focusable. It remains windowed and is not activated here.
& C:/Users/Ryan/source/repos/mc-agent-harness/tools/launch.ps1 -Project $root -RunDirectory "$root/run" -Port $Port -Monitor secondary -Width 1920 -Height 1080 -Fullscreen -GradleProperties @{rxDebug=[string]($Port+1);rxIqUser='RxBobCheck';reflexiumInstallDir='build/install'} -TimeoutSeconds 35 -Pending
$session=Get-Content "C:/Users/Ryan/source/repos/mc-agent-harness/instances/$Port/session.json" -Raw|ConvertFrom-Json
if($session.game_pid){$reservation.pid=$session.game_pid;$reservation|ConvertTo-Json|Set-Content $lockPath}
