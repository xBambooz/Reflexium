param(
    [int]$Port = 25601,
    [int]$RxPort = 25599
)
$ErrorActionPreference = 'Stop'
$props = '{"rxDebug":"' + $RxPort + '"}'
& 'C:\Users\Ryan\source\repos\mc-agent-harness\tools\launch.ps1' -Project 'C:\Users\Ryan\source\repos\Reflexium' -RunDirectory 'C:\Users\Ryan\source\repos\Reflexium\run' -GradlePropertiesJson $props -Port $Port
$sessionFile = "C:\Users\Ryan\source\repos\mc-agent-harness\instances\$Port\session.json"
$session = Get-Content $sessionFile | ConvertFrom-Json
Write-Host "Minecraft running as PID $($session.game_pid). Waiting for process to exit..."
Wait-Process -Id $session.game_pid
