param([int]$Port=25783)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
# Wait behind the session controller, which acquires the GPU lock before launching.
while (!(Test-Path "C:/Users/Ryan/source/repos/mc-agent-harness/instances/$Port/session.json")) { Start-Sleep 10 }
. "$PSScriptRoot/iq-session.ps1" -Port $Port
try {
    do {
        Start-Sleep 5
        try { $s=Status } catch { $s=$null }
    } until ($s)
    if ($s.game_directory -ne "$root\run") {throw 'Wrong run directory'}
    if (!$s.world_loaded) { Call join @{world='Reflexium Vitrail Smoke';timeout_s=120} | Out-Null }
    Call fullscreen @{fullscreen=1;activate=1} | Out-Null
    Call window @{monitor='primary';x=0;y=0;width=2561;height=1440} | Out-Null
    Call wait @{ms=1000} | Out-Null
    $s=Status
    if (!$s.window.fullscreen.borderless -or $s.window.fullscreen.exclusive_fullscreen -or $s.game_directory -ne "$root\run") {throw 'Wrong fullscreen or run directory'}
    $s | ConvertTo-Json -Depth 40 | Set-Content "$root/docs/validation/overnight-c-2026-10-31/session3-start.json"
    & "$PSScriptRoot/overnight-c-mip.ps1" -Port $Port -PackFilter 0 -Pass 3
    foreach ($q in @('quality','performance','ultra_performance')) {
        foreach ($m in @(1,3)) {
            & "$PSScriptRoot/overnight-c-dither.ps1" -Port $Port -Quality $q -Multiplier $m -Label "mesh-$q-$($m)x"
        }
    }
} finally {
    & "$PSScriptRoot/iq-followup-quit.ps1" -Port $Port | Out-File "$root/docs/validation/overnight-c-2026-10-31/quit-resume-$Port.json"
}
