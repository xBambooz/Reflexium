param(
    [string]$Source = 'C:\Users\Ryan\source\repos\Reflexium\docs\benchmarks',
    [string]$Out = 'C:\Users\Ryan\Documents\Minecraft FPS Lab\site\upscaling.json'
)
$ErrorActionPreference = 'Stop'
$setTitles = @{ A = 'No shaders'; B = 'Vitrail, Complementary Reimagined' }
$sets = [System.Collections.Generic.List[object]]::new()
foreach ($setId in @('A', 'B')) {
    $path = Join-Path $Source "upscaling-set-$setId-runs.jsonl"
    if (!(Test-Path $path)) { continue }
    $attempts = @(Get-Content -LiteralPath $path | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json })
    $measured = @($attempts | Where-Object { $_.pass -gt 0 })
    $accepted = @($measured | Where-Object { $_.outcome -eq 'accepted' })
    $modes = [System.Collections.Generic.List[string]]::new()
    $labels = @{}
    foreach ($a in $measured) {
        if (!$modes.Contains($a.mode)) { $modes.Add($a.mode) }
        $labels[$a.mode] = $a.label
    }
    $native = @($accepted | Where-Object { $_.mode -eq 'native' })
    $nativeDisplayed = if ($native.Count) { ($native | Measure-Object -Property displayed_fps_mean -Average).Average } else { $null }
    $rows = [System.Collections.Generic.List[object]]::new()
    foreach ($mode in $modes) {
        $runs = @($accepted | Where-Object { $_.mode -eq $mode })
        if ($runs.Count -eq 0) {
            $failed = @($measured | Where-Object { $_.mode -eq $mode })
            $last = $failed[-1]
            $rows.Add([ordered]@{ mode = $mode; label = $labels[$mode]; status = $last.outcome; reason = $last.reasons })
            continue
        }
        $displayed = ($runs | Measure-Object -Property displayed_fps_mean -Average).Average
        $base = ($runs | Measure-Object -Property avg_fps -Average).Average
        $low1 = ($runs | Measure-Object -Property low_1pct_fps -Average).Average
        $gpu = ($runs | Measure-Object -Property gpu_mean_pct -Average).Average
        $p1 = @($runs | Where-Object { $_.pass -eq 1 })
        $p2 = @($runs | Where-Object { $_.pass -eq 2 })
        $p1fps = if ($p1.Count) { ($p1 | Measure-Object -Property displayed_fps_mean -Average).Average } else { $null }
        $p2fps = if ($p2.Count) { ($p2 | Measure-Object -Property displayed_fps_mean -Average).Average } else { $null }
        $vsNative = if ($nativeDisplayed) { [math]::Round(100.0 * ($displayed / $nativeDisplayed - 1.0), 1) } else { $null }
        $rows.Add([ordered]@{
            mode = $mode
            label = $labels[$mode]
            status = 'ok'
            runs = $runs.Count
            displayed_fps = [math]::Round($displayed, 1)
            base_fps = [math]::Round($base, 1)
            low_1pct_fps = [math]::Round($low1, 1)
            vs_native_pct = $vsNative
            render = if ($mode -eq 'native') { $runs[0].display } else { $runs[0].render }
            gpu_pct = [math]::Round($gpu, 1)
            pass1_fps = if ($p1fps) { [math]::Round($p1fps, 1) } else { $null }
            pass2_fps = if ($p2fps) { [math]::Round($p2fps, 1) } else { $null }
            status_line = $runs[0].status_line
            fg_status = $runs[0].fg_status
        })
    }
    $nativePasses = @($native | ForEach-Object { $_.displayed_fps_mean })
    $spread = if ($nativePasses.Count -gt 1) {
        $avg = ($nativePasses | Measure-Object -Average).Average
        [math]::Round(100.0 * [math]::Abs($nativePasses[0] - $nativePasses[-1]) / $avg, 2)
    } else { $null }
    $counts = [ordered]@{}
    foreach ($outcome in @('accepted', 'rejected', 'refused', 'failed', 'error')) {
        $counts[$outcome] = @($measured | Where-Object { $_.outcome -eq $outcome }).Count
    }
    $sets.Add([ordered]@{
        id = $setId
        title = $setTitles[$setId]
        updated = ($attempts | ForEach-Object { $_.utc } | Measure-Object -Maximum).Maximum
        session_pid = if ($accepted.Count) { $accepted[0].game_pid } else { $null }
        window = if ($accepted.Count) { $accepted[0].window } else { $null }
        route_seconds = if ($accepted.Count) { [math]::Round(($accepted | Measure-Object -Property route_seconds -Average).Average, 1) } else { $null }
        native_pass_spread_pct = $spread
        counts = $counts
        rows = $rows
    })
}
$doc = [ordered]@{
    schema = 1
    updated = ($sets | ForEach-Object { $_.updated } | Measure-Object -Maximum).Maximum
    sets = $sets
}
$doc | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $Out -Encoding UTF8
Write-Host "Updated $Out successfully: $($sets.Count) sets."
