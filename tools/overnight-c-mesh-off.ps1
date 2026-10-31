param([int]$Port=25784)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
while (!(Test-Path "C:/Users/Ryan/source/repos/mc-agent-harness/instances/$Port/session.json")) {Start-Sleep 5}
. "$PSScriptRoot/iq-session.ps1" -Port $Port
try {
    do {Start-Sleep 3;try {$s=Status}catch{$s=$null}} until($s)
    if ($s.game_directory -ne "$root\run") {throw 'Wrong run'}
    Call fullscreen @{fullscreen=1;activate=1}|Out-Null
    Call window @{monitor='primary';x=0;y=0;width=2561;height=1440}|Out-Null
    if (!$s.world_loaded) {Call join @{world='Reflexium Vitrail Smoke';timeout_s=120}|Out-Null}
    & "$PSScriptRoot/overnight-c-fps.ps1" -Port $Port -Label mesh-off
    $dir="$root/docs/validation/overnight-c-2026-10-31/meshelium/mesh-off"
    $raw="$root/run/overnight-c/mesh-off"
    New-Item -ItemType Directory -Force $raw|Out-Null
    Setting upscaler off
    Call wait @{ms=10000}|Out-Null
    Call capture_sequence @{dir="$raw/p0-native-static";count=24;interval_ms=250}|ConvertTo-Json -Depth 40|Set-Content "$dir/p0-native-static.json"
    foreach($pack in @(0,1)) {
        Setting upscaler off
        Call wait @{ms=1000}|Out-Null
        Setting shaders $pack
        Call quit @{}|Out-Null
        Call join @{world='Reflexium Vitrail Smoke';timeout_s=120}|Out-Null
        Setting upscaler dlss
        foreach($q in @('quality','ultra_performance')) {
            Setting quality $q
            Call camera @{x=200.5;y=110;z=150;yaw=90;pitch=10}|Out-Null
            Call wait @{ms=10000}|Out-Null
            $name="p$pack-$q"
            $capture=Call reflexium @{capture=$name;dir=$raw}
            $capture|ConvertTo-Json -Depth 40|Set-Content "$dir/$name-input.json"
            if ($capture.capture.pack_drawing -ne [bool]$pack) {throw 'Wrong pack state'}
            Call screenshot @{path="$raw/$name.png"}|Out-Null
            Call capture_sequence @{dir="$raw/$name-static";count=24;interval_ms=250}|ConvertTo-Json -Depth 40|Set-Content "$dir/$name-static.json"
        }
    }
} finally {
    & "$PSScriptRoot/iq-followup-quit.ps1" -Port $Port | Out-File "$root/docs/validation/overnight-c-2026-10-31/quit-$Port.json"
}
