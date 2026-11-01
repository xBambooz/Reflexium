param([int]$Port=25783)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/iq-session.ps1" -Port $Port
$root=Split-Path $PSScriptRoot
$pack='ComplementaryReimagined_r5.9.3 + EuphoriaPatches_1.10.5'
$file="$root/run/shaderpacks/$pack.txt"
$dir="$root/docs/validation/overnight-c-2026-10-31/options"
New-Item -ItemType Directory -Force $dir | Out-Null
$lock=Get-Content C:/Users/Ryan/source/repos/Reflexium-gpu.lock -Raw|ConvertFrom-Json
if ($lock.owner -ne 'worker-C-overnight' -or (Status).game_directory -ne "$root\run") {throw 'Wrong owner/run'}
$original=[IO.File]::ReadAllText($file)
function Reload($name) {
    Setting shader_pack $pack
    Call wait @{ms=3000} | Out-Null
    Copy-Item -LiteralPath $file -Destination "$dir/$name.properties"
    Status | ConvertTo-Json -Depth 40 | Set-Content "$dir/$name.json"
    Copy-Item "$root/run/reflexium/iris-options/*.properties" -Destination $dir -Force
}
try {
    Setting frame_generation off
    Setting upscaler dlss
    [IO.File]::WriteAllText($file,"AAA_THIS_IS_A_EUPHORIA_PATCHES_SETTINGS_FILE=true`nTAA=true`nTAA_DEFINE=1`nTAA_JITTER=1`nFXAA_DEFINE=1`nIMAGE_SHARPENING=2`n")
    Reload nondefault-recorded
    Copy-Item -LiteralPath "$root/docs/validation/overnight-c-2026-10-31/leaked-input.properties" -Destination $file -Force
    Reload recovered-nondefault
    $recovered=[IO.File]::ReadAllText($file)
    if ($recovered -notmatch '(?m)^FXAA_DEFINE=1\s*$' -or $recovered -notmatch '(?m)^IMAGE_SHARPENING=2\s*$') {throw 'Nondefault values not recovered'}
    $partial=[IO.File]::ReadAllText("$root/docs/validation/overnight-c-2026-10-31/leaked-input.properties") -replace '(?m)^TAA_JITTER=.*\r?\n',''
    [IO.File]::WriteAllText($file,$partial)
    Reload partial-signature
    $result=[IO.File]::ReadAllText($file)
    if ($result -notmatch '(?m)^TAA=false\s*$' -or $result -match '(?m)^TAA_JITTER=') {throw 'Partial signature changed'}
} finally {
    [IO.File]::WriteAllText($file,$original)
    Reload restored
}
