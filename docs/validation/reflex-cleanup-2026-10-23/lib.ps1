# Helpers for the 2026-10-23 Reflex cleanup validation. Dot-source this file.
# Reads the harness token from session.json at call time; the token is never written to evidence.
$script:Repo = 'C:\Users\Ryan\source\repos\Reflexium'
$script:Ev = Join-Path $script:Repo 'docs\validation\reflex-cleanup-2026-10-23'
$script:Rx = Join-Path $script:Repo 'tools\rxdbg.ps1'
$script:Session = 'C:\Users\Ryan\source\repos\mc-agent-harness\instances\25910\session.json'
$script:Utf8 = [Text.UTF8Encoding]::new($false)
$script:Smoke = @()

function Append-Evidence([string]$name, [string]$line) {
	[IO.File]::AppendAllText((Join-Path $script:Ev $name), $line + "`n", $script:Utf8)
}

function Log-Step([string]$text) {
	Append-Evidence 'protocol.log.txt' ("{0} {1}" -f (Get-Date -Format o), $text)
}

function Rx {
	param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
	$out = (& $script:Rx @Args) | Out-String
	$parsed = $out | ConvertFrom-Json
	return $parsed
}

function Harness {
	$s = Get-Content -LiteralPath $script:Session -Raw | ConvertFrom-Json
	$headers = @{ Authorization = "Bearer $($s.token)" }
	return Invoke-RestMethod 'http://127.0.0.1:25910/status' -Headers $headers -TimeoutSec 10
}

# Frame marks, frame stats and reflex_timings live on the harness /reflexium POST endpoint (the debug port returns help for them).
function Post-Reflexium([hashtable]$body) {
	$s = Get-Content -LiteralPath $script:Session -Raw | ConvertFrom-Json
	$headers = @{ Authorization = "Bearer $($s.token)" }
	return Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:25910/reflexium' -Headers $headers -ContentType 'application/json' -Body ($body | ConvertTo-Json -Compress) -TimeoutSec 60
}

function Gpu {
	$smi = 'C:\Windows\System32\nvidia-smi.exe'
	if (-not (Test-Path $smi)) { return 'nvidia-smi missing' }
	return ((& $smi --query-gpu=utilization.gpu,memory.used,memory.total --format=csv,noheader) | Out-String).Trim()
}

function Schema-Flags($reflexium) {
	$names = @($reflexium.PSObject.Properties.Name)
	$bad = @($names | Where-Object { $_ -match 'smart|pacing|paused' })
	$textBad = @()
	foreach ($k in 'reflex_status', 'reflex_latency') {
		if ([string]$reflexium.$k -match 'paused') { $textBad += $k }
	}
	return [ordered]@{ forbidden_keys = $bad; forbidden_text_in = $textBad }
}

function Snapshot([string]$scene, [string]$mode, [string]$phase) {
	$h = Harness
	$r = $h.plugins.reflexium
	$row = [ordered]@{
		t = (Get-Date -Format o); scene = $scene; phase = $phase; mode_requested = $mode
		reflex_mode = $r.reflex_mode; reflex_status = $r.reflex_status; reflex_sleeping = $r.reflex_sleeping
		reflex_latency = $r.reflex_latency; reflex_interval_us = $r.reflex_interval_us; reflex_submission = $r.reflex_submission
		reflex_attribution = $r.reflex_attribution; reflex_extension_revision = $r.reflex_extension_revision
		fps = $r.fps; frame_time_ms = $r.frame_time_ms; frames = $r.frames
		upscaler = $r.upscaler; upscaler_effective = $r.upscaler_effective; upscaling_active = $r.upscaling_active
		frame_generation = $r.frame_generation; fg_status = $r.fg_status; world_loaded = $r.world_loaded; screen = $r.screen
		camera = $r.camera; last_error = $r.last_error
		harness_focus = $h.focus; game_pid = $h.pid
		schema = (Schema-Flags $r)
		plugins_reflexium = $r
	}
	Append-Evidence 'status.jsonl' ($row | ConvertTo-Json -Depth 12 -Compress)
	return $row
}

# One timed window: switch mode, 3 s warmup, mark, 10 s, stats. Writes one fps.jsonl row.
function Measure-Window([string]$scene, [string]$mode, [string]$phase, [int]$seq) {
	$null = Rx set reflex_mode $mode
	Start-Sleep -Seconds 3
	$gpuBefore = Gpu
	$mark = Post-Reflexium @{ frame_mark = '1' }
	$t0 = Get-Date
	Start-Sleep -Seconds 10
	$stats = Post-Reflexium @{ frame_stats_from = "$($mark.frames)" }
	$t1 = Get-Date
	$gpuAfter = Gpu
	$snap = Snapshot $scene $mode $phase
	$row = [ordered]@{
		scene = $scene; phase = $phase; seq = $seq; mode = $mode
		mark_index = $mark.frames; window_s = [math]::Round(($t1 - $t0).TotalSeconds, 3)
		intervals = $stats.intervals; avg_fps = $stats.avg_fps; mean_ms = $stats.mean_ms; p50_ms = $stats.p50_ms
		p99_ms = $stats.p99_ms; low_1pct_fps = $stats.low_1pct_fps; low_0_1pct_fps = $stats.low_0_1pct_fps
		reflex_status = $snap.reflex_status; reflex_sleeping = $snap.reflex_sleeping; reflex_latency = $snap.reflex_latency
		status_fps = $snap.fps; status_frame_time_ms = $snap.frame_time_ms
		gpu_before = $gpuBefore; gpu_after = $gpuAfter; game_pid = $snap.game_pid; t = (Get-Date -Format o)
	}
	Append-Evidence 'fps.jsonl' ($row | ConvertTo-Json -Depth 6 -Compress)
	return $row
}
