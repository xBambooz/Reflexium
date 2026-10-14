param(
    [string]$Session = 'C:\Users\Ryan\source\repos\mc-agent-harness\instances\25601\session.json',
    [string]$Output = (Join-Path $PSScriptRoot '..\build\frame-generation-check.json')
)
$ErrorActionPreference = 'Stop'
$sessionInfo = Get-Content -Raw -LiteralPath $Session | ConvertFrom-Json
$headers = @{ Authorization = "Bearer $($sessionInfo.token)" }
$base = "http://127.0.0.1:$($sessionInfo.port)"
function Status { Invoke-RestMethod "$base/status" -Headers $headers -TimeoutSec 20 }
function Call([string]$path, [hashtable]$data) {
    $result = Invoke-RestMethod "$base/$path" -Method Post -Headers $headers -ContentType application/json -Body ($data | ConvertTo-Json -Compress) -TimeoutSec 45
    if (!$result.ok) { throw "Harness $path failed" }
    $result
}
function SetOption([string]$key, [string]$value) { Call 'reflexium' @{key=$key; value=$value} | Out-Null }
$initial = Status
$processInfo = Get-CimInstance Win32_Process -Filter "ProcessId=$($sessionInfo.game_pid)"
if ($initial.pid -ne $sessionInfo.game_pid -or $initial.game_directory -ne $sessionInfo.game_directory -or
    !$processInfo.CommandLine.Contains($sessionInfo.jar) -or !$processInfo.CommandLine.Contains($sessionInfo.game_directory)) {
    throw 'Session does not identify the injected debug process. No settings changed.'
}
if (!$initial.world_loaded -or $initial.screen -ne 'none') { throw 'Join a debug world and close menus before running this check.' }
$saved = $initial.plugins.reflexium
$results = [Collections.Generic.List[object]]::new()
try {
    $modes = @('fsr')
    if (!$saved.fg_sdk_scheduler -and $saved.fg_max_multiplier -ge 2) { $modes += 'dlss' }
    if (!$saved.fg_sdk_scheduler -and $saved.fg_max_multiplier -ge 3) { $modes += 'dlss_multi' }
    foreach ($mode in $modes) {
        SetOption 'frame_generation' $mode
        $multipliers = if ($mode -eq 'dlss_multi') { 3..$saved.fg_max_multiplier } else { @(2) }
        foreach ($multiplier in $multipliers) {
            if ($mode -eq 'dlss_multi') { SetOption 'fg_multiplier' ([string]$multiplier) }
            foreach ($upscaler in @('auto', 'fsr', 'off')) {
                SetOption 'upscaler' $upscaler
                Call 'wait' @{ticks=20} | Out-Null
                $before = (Status).plugins.reflexium
                Call 'wait' @{ticks=30} | Out-Null
                $after = (Status).plugins.reflexium
				$expectedStatus = switch ($mode) {
					'dlss' { 'Frame generation: DLSS FG' }
					'fsr' { 'Frame generation: FSR FG' }
					'dlss_multi' { "Frame generation: DLSS MFG ${multiplier}x" }
				}
                if ($after.fg_status -ne $expectedStatus -or $after.fg_evaluated_frames -le $before.fg_evaluated_frames -or $after.last_error) {
                    throw "Interpolation failed for $mode/${multiplier}x/$upscaler`: $($after.fg_status) $($after.last_error)"
                }
                $generated = $after.fg_generated_presents - $before.fg_generated_presents
                $sdkPresents = $after.fg_sdk_present_count - $before.fg_sdk_present_count
                if ($generated -le 0 -and $sdkPresents -le 0) { throw "Presentation did not advance for $mode/$upscaler" }
                $evaluations = $after.fg_evaluated_frames - $before.fg_evaluated_frames
                if (!$after.fg_sdk_scheduler -and [math]::Abs($generated - $evaluations * ($multiplier - 1)) -gt 2 * ($multiplier - 1)) {
                    throw "Generated present count does not match evaluations for $mode/${multiplier}x/$upscaler"
                }
                if ($after.fg_sdk_scheduler -and [math]::Abs($sdkPresents - $evaluations * 2) -gt 4) {
                    throw "SDK present count does not match evaluations for $mode/$upscaler"
                }
                $results.Add([pscustomobject]@{mode=$mode; multiplier=$multiplier; upscaler=$upscaler;
                    evaluations=$after.fg_evaluated_frames-$before.fg_evaluated_frames; generatedPresents=$generated; sdkPresents=$sdkPresents;
                    meteringFeedback=$after.fg_metering_feedback; renderedFps=$after.fps; status=$after.fg_status})
            }
        }
    }
} finally {
    SetOption 'frame_generation' 'off'
    SetOption 'upscaler' $saved.upscaler
    if ($saved.fg_multiplier) { SetOption 'fg_multiplier' ([string]$saved.fg_multiplier) }
    SetOption 'frame_generation' $saved.frame_generation
    $results | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $Output
}
$results | Format-Table mode,multiplier,upscaler,evaluations,generatedPresents,sdkPresents,meteringFeedback
