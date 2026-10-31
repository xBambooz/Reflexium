. "$PSScriptRoot/iq-session.ps1" -Port 25723
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
$ev="$root/docs/validation/iq-issues-2026-10-30/world-text-depth-followup"
$raw="$root/run/iq-depth-followup"
Setting world_text_ignore_depth 0
Call camera @{x=200.5;y=151;z=22.042213861078043;yaw=0;pitch=-35}|Out-Null
Call key @{name='key.togglePerspective';down=$true;ticks=1}|Out-Null
Setting upscaler off
Call wait @{ms=10000}|Out-Null
Status|ConvertTo-Json -Depth 40|Set-Content "$ev/head-native-settled.json"
Call screenshot @{path="$raw/head-native-settled.png"}|Out-Null
Write-Output 'Settled native F5 head captured'
