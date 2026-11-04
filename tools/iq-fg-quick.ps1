param([int]$Port)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/iq-session.ps1" -Port $Port
$root=Split-Path $PSScriptRoot
$evidence=Join-Path $root 'docs/validation/iq-issues-2026-10-30/continuation/quick'
$raw=Join-Path $root 'run/iq-continuation/quick/fg'
New-Item -ItemType Directory -Force $raw|Out-Null
$lock=Get-Content C:/Users/Ryan/source/repos/Reflexium-gpu.lock|ConvertFrom-Json
if($lock.owner -ne 'worker-A' -or (Status).game_directory -ne "$root\run"){throw 'ownership mismatch'}
Add-Type 'using System; using System.Runtime.InteropServices; public static class RxAFocus { [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow(); [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hwnd); }'
$previous=[RxAFocus]::GetForegroundWindow().ToInt64()
@{previous_foreground=$previous;recorded=(Get-Date).ToString('o')}|ConvertTo-Json|Set-Content "$evidence/fg-focus.json"
Setting vsync 0
Setting frame_generation dlss_multi
Setting fg_multiplier 3
Call wait @{ms=3000}|Out-Null
SaveStatus fg-unfocused
try{
    Call window @{monitor='secondary';x=0;y=0;width=2560;height=1440;behind=$false;activate=$true}|Out-Null
    Call wait @{ms=1000}|Out-Null
    $before=Status
    SaveStatus fg-focused-before
    Call wait @{ms=5000}|Out-Null
    $after=Status
    SaveStatus fg-focused-after
    Call reflexium @{capture='fg-names';dir=$raw}|ConvertTo-Json -Depth 40|Set-Content "$evidence/fg-names-capture.json"
    Call screenshot @{path="$raw/fg-names.png"}|Out-Null
    $delta=$after.plugins.reflexium.fg_generated_presents-$before.plugins.reflexium.fg_generated_presents
    @{generated_delta=$delta;foreground=$after.focus.os_foreground;input_focus=$after.focus.os_input_focus;
        requested_multiplier=$after.plugins.reflexium.fg_multiplier;last_error=$after.plugins.reflexium.last_error;
        overlay_confounds_generation=($delta -le 0)}|ConvertTo-Json|Set-Content "$evidence/fg-result.json"
    if($after.plugins.reflexium.last_error){throw 'FG render error'}
    Write-Output "FG focused generated-present delta: $delta"
}finally{
    $restored=[RxAFocus]::SetForegroundWindow([IntPtr]::new($previous))
    @{restored=$restored;previous_foreground=$previous;foreground=[RxAFocus]::GetForegroundWindow().ToInt64()}|ConvertTo-Json|Set-Content "$evidence/fg-focus-restored.json"
    Setting frame_generation off
}
