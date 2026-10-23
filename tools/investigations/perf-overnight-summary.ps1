param([string[]]$Labels = @('r2-before'), [string]$Scene = 'bsl')
$dir = Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'docs/benchmarks/perf-overnight-2026-10-19'
$modes = 'native','cnn-quality','cnn-balanced','cnn-performance','transformer-quality','transformer-balanced','transformer-performance'
function Mean($v) { if (@($v).Count -eq 0) { return $null }; ($v | Measure-Object -Average).Average }
foreach ($label in $Labels) {
    $rows = Get-Content (Join-Path $dir "$label.jsonl") | ForEach-Object { $_ | ConvertFrom-Json } | Where-Object { $_.scene -eq $Scene }
    foreach ($mode in $modes) {
        $m = $rows | Where-Object { $_.mode -eq $mode }
        if (-not $m) { continue }
        $fps = Mean ($m | Where-Object { -not $_.instrumented } | ForEach-Object { $_.stats.avg_fps })
        $prof = $m | Where-Object { $_.instrumented }
        function Span([string]$n) { Mean ($prof | ForEach-Object { $_.profile.spans.$n } | Where-Object { $_.gpu_samples -gt 0 } | ForEach-Object { $_.gpu_us }) }
        function Cpu([string]$n) { Mean ($prof | ForEach-Object { $s = $_.profile.spans; $s.$n.cpu_us * $s.$n.cpu_samples / [math]::Max(1, $s.reflex_sleep.cpu_samples) }) }
        '{0},{1},{2:N1},world={3:N0},eval={4:N0},shadows={5:N0},terrain={6:N0},deferred={7:N0},composite={8:N0},final={9:N0},cpu_world={10:N0},cpu_shadows={11:N0},sleep={12:N0},fence={13:N0}' -f $label,$mode,$fps,(Span world_including_upscaling),(Span evaluate),(Span iris_shadows_and_prepare),(Span phase_terrain_solid),(Span iris_deferred),(Span iris_composite),(Span iris_final),(Cpu world_including_upscaling),(Cpu iris_shadows_and_prepare),(Cpu reflex_sleep),(Cpu submit_fence_wait)
    }
}
