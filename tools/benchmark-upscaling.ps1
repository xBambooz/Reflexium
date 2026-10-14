<#
.SYNOPSIS
  Upscaling and frame generation matrix on the Reflexium dev client (Set A no shaders, Set B Vitrail).

.DESCRIPTION
  Drives a client launched by the agent harness (port 25601, see docs/benchmarks/README.md). Every
  mode is applied through the "reflexium" endpoint, verified against status and the client log, then
  measured over a fixed route: a 5 s warmup (100 ticks) followed by 10 steps of 60 ticks (30 s). Pass 1
  runs the matrix forward and pass 2 runs it in reverse (ABBA). Every attempt, accepted or not, is
  appended to docs/benchmarks/upscaling-set-<Set>-runs.jsonl. -Summarize rebuilds the CSV and JSON.

.PARAMETER Set
  A runs with Vitrail disabled. B needs Vitrail enabled in run\vitrail\pack.txt and a rejoined world.

.PARAMETER Summarize
  Rebuild upscaling-set-<Set>.csv and .json from the runs file without touching the game.
#>
param(
	[Parameter(Mandatory = $true)][ValidateSet('A', 'B')][string]$Set,
	[int]$Port = 25601,
	[switch]$Summarize
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

$root = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $root 'docs\benchmarks'
$runsFile = Join-Path $outDir "upscaling-set-$Set-runs.jsonl"
$latestLog = Join-Path $root 'run\logs\latest.log'
$world = 'Reflexium Vitrail Smoke'
$warmupTicks = 100
$stepTicks = 60
$camera = @{ x = -25.5; y = 69; z = 23.5; yaw = 0; pitch = 0 }
$route = @(
	@{ k = 'turn'; dyaw = 90; dpitch = 0 },
	@{ k = 'move'; dx = 0; dz = 8 },
	@{ k = 'turn'; dyaw = 90; dpitch = -8 },
	@{ k = 'move'; dx = 8; dz = 0 },
	@{ k = 'turn'; dyaw = -180; dpitch = 8 },
	@{ k = 'move'; dx = 0; dz = -8 },
	@{ k = 'turn'; dyaw = 90; dpitch = -8 },
	@{ k = 'move'; dx = -8; dz = 0 },
	@{ k = 'turn'; dyaw = 45; dpitch = 0 },
	@{ k = 'move'; dx = 0; dz = 4 }
)

# Quality labels as they appear in the status line, expected render width, and tolerance in pixels.
$qualityLabel = @{ native_aa = 'DLAA|Native AA'; quality = 'Quality'; balanced = 'Balanced'; performance = '(?<!Ultra )Performance'; ultra_performance = 'Ultra Performance' }
$qualityWidth = @{ native_aa = 1920; quality = 1280; balanced = 1114; performance = 960; ultra_performance = 640 }

function New-Mode([string]$id, [string]$label, [string]$upscaler, [string]$quality, [string]$model, [string]$fg, [int]$mult) {
	@{ id = $id; label = $label; upscaler = $upscaler; quality = $quality; model = $model; fg = $fg; mult = $mult }
}

$modes = @(
	(New-Mode 'native' 'Native, upscaler off' 'off' '' '' 'off' 2),
	(New-Mode 'cnn-dlaa' 'DLSS CNN, DLAA' 'dlss' 'native_aa' 'cnn' 'off' 2),
	(New-Mode 'cnn-quality' 'DLSS CNN, Quality' 'dlss' 'quality' 'cnn' 'off' 2),
	(New-Mode 'cnn-balanced' 'DLSS CNN, Balanced' 'dlss' 'balanced' 'cnn' 'off' 2),
	(New-Mode 'cnn-performance' 'DLSS CNN, Performance' 'dlss' 'performance' 'cnn' 'off' 2),
	(New-Mode 'cnn-ultra' 'DLSS CNN, Ultra Performance' 'dlss' 'ultra_performance' 'cnn' 'off' 2),
	(New-Mode 'tf-dlaa' 'DLSS Transformer, DLAA' 'dlss' 'native_aa' 'transformer' 'off' 2),
	(New-Mode 'tf-quality' 'DLSS Transformer, Quality' 'dlss' 'quality' 'transformer' 'off' 2),
	(New-Mode 'tf-balanced' 'DLSS Transformer, Balanced' 'dlss' 'balanced' 'transformer' 'off' 2),
	(New-Mode 'tf-performance' 'DLSS Transformer, Performance' 'dlss' 'performance' 'transformer' 'off' 2),
	(New-Mode 'tf-ultra' 'DLSS Transformer, Ultra Performance' 'dlss' 'ultra_performance' 'transformer' 'off' 2),
	(New-Mode 'fsr-native-aa' 'FSR 3.1, Native AA' 'fsr' 'native_aa' 'transformer' 'off' 2),
	(New-Mode 'fsr-quality' 'FSR 3.1, Quality' 'fsr' 'quality' 'transformer' 'off' 2),
	(New-Mode 'fsr-balanced' 'FSR 3.1, Balanced' 'fsr' 'balanced' 'transformer' 'off' 2),
	(New-Mode 'fsr-performance' 'FSR 3.1, Performance' 'fsr' 'performance' 'transformer' 'off' 2),
	(New-Mode 'fsr-ultra' 'FSR 3.1, Ultra Performance' 'fsr' 'ultra_performance' 'transformer' 'off' 2),
	(New-Mode 'fg-dlss-fg' 'DLSS Transformer Quality, DLSS FG (2x)' 'dlss' 'quality' 'transformer' 'dlss' 2),
	(New-Mode 'fg-dlss-mfg3' 'DLSS Transformer Quality, DLSS MFG 3x' 'dlss' 'quality' 'transformer' 'dlss_multi' 3),
	(New-Mode 'fg-dlss-mfg4' 'DLSS Transformer Quality, DLSS MFG 4x' 'dlss' 'quality' 'transformer' 'dlss_multi' 4),
	(New-Mode 'fg-dlss-mfg5' 'DLSS Transformer Quality, DLSS MFG 5x' 'dlss' 'quality' 'transformer' 'dlss_multi' 5),
	(New-Mode 'fg-dlss-mfg6' 'DLSS Transformer Quality, DLSS MFG 6x' 'dlss' 'quality' 'transformer' 'dlss_multi' 6),
	(New-Mode 'fg-fsr-fg' 'FSR 3.1 Quality, FSR FG (2x)' 'fsr' 'quality' 'transformer' 'fsr' 2)
)

$fgExpect = @{
	'off' = '^Frame generation: off$'
	'dlss' = '^Frame generation: DLSS FG$'
	'dlss_multi' = $null
	'fsr' = '^Frame generation: FSR FG$'
}

# ---------------------------------------------------------------------------------------------------
# Harness and Reflexium access

$session = Get-Content (Join-Path 'C:\Users\Ryan\source\repos\mc-agent-harness\instances' "$Port\session.json") -Raw | ConvertFrom-Json
$base = "http://127.0.0.1:$Port"
$http = New-Object System.Net.Http.HttpClient
$http.Timeout = [TimeSpan]::FromSeconds(300)
$http.DefaultRequestHeaders.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $session.token)

function Get-Json([string]$path) {
	$text = $http.GetStringAsync("$base$path").GetAwaiter().GetResult()
	return ($text | ConvertFrom-Json)
}

function Post([string]$path, [hashtable]$data) {
	$body = $data | ConvertTo-Json -Compress
	$content = New-Object System.Net.Http.StringContent($body, [System.Text.Encoding]::UTF8, 'application/json')
	$response = $http.PostAsync("$base$path", $content).GetAwaiter().GetResult()
	$text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
	if (-not $response.IsSuccessStatusCode) { throw "POST $path returned $([int]$response.StatusCode): $text" }
	return ($text | ConvertFrom-Json)
}

function Set-Opt([string]$key, [string]$value) {
	Post '/reflexium' @{ key = $key; value = $value } | Out-Null
}

function Get-Reflexium { (Get-Json '/status').plugins.reflexium }

function Get-GpuPct {
	$line = & nvidia-smi --query-gpu=utilization.gpu --format=csv,noheader,nounits | Select-Object -First 1
	if ($line) { return [double]("$line".Trim()) }
	return $null
}

function Get-LoggedModel {
	$match = Get-Content -LiteralPath $latestLog -Tail 600 | Select-String -Pattern 'DLSS model (AUTO|CNN|TRANSFORMER)' | Select-Object -Last 1
	if ($match) { return $match.Matches[0].Groups[1].Value }
	return ''
}

function Get-VitrailLogLine {
	$match = Get-Content -LiteralPath $latestLog -Tail 2000 | Select-String -Pattern 'Vitrail' | Select-Object -Last 1
	if ($match) { return $match.Line }
	return ''
}

# ---------------------------------------------------------------------------------------------------
# Mode application and verification

function Apply-Mode($m) {
	Set-Opt 'frame_generation' 'off'
	Set-Opt 'upscaler' $m.upscaler
	if ($m.quality) { Set-Opt 'quality' $m.quality }
	if ($m.model) { Set-Opt 'dlss_model' $m.model }
	if ($m.fg -eq 'dlss_multi') { Set-Opt 'fg_multiplier' ([string]$m.mult) }
	if ($m.fg -ne 'off') { Set-Opt 'frame_generation' $m.fg }
}

function Test-Mode($m, $r) {
	$reasons = New-Object System.Collections.Generic.List[string]
	$refused = $false
	if ($m.upscaler -eq 'off') {
		if ($r.upscaling_active) { $reasons.Add('upscaling active with upscaler off') }
	}
	else {
		if (-not $r.upscaling_active) {
			$reasons.Add("upscaling inactive: $($r.status_line)")
			if ($r.status_line -match 'pause|unsupported|not supported|refus|problem|failed|unavailable') { $refused = $true }
		}
		if ($r.upscaler_built -ne $m.upscaler) { $reasons.Add("built=$($r.upscaler_built)") }
		if ($r.status_line -notmatch $qualityLabel[$m.quality]) { $reasons.Add("status line lacks $($m.quality): $($r.status_line)") }
		$expectWidth = $qualityWidth[$m.quality]
		if ([math]::Abs($r.render_size.w - $expectWidth) -gt 16) { $reasons.Add("render width $($r.render_size.w), expected $expectWidth") }
		if ($m.upscaler -eq 'dlss') {
			$logged = Get-LoggedModel
			$expectModel = $m.model.ToUpperInvariant()
			if ($logged -ne $expectModel) { $reasons.Add("logged DLSS model '$logged', expected $expectModel") }
		}
	}
	if ($m.fg -eq 'dlss_multi') {
		$want = "^Frame generation: DLSS MFG $($m.mult)x$"
	}
	else {
		$want = $fgExpect[$m.fg]
	}
	if ($r.fg_status -notmatch $want) { $reasons.Add("fg_status '$($r.fg_status)' lacks $want") }
	if ($m.fg -ne 'off' -and $r.fg_max_multiplier -lt $m.mult) { $reasons.Add("runtime max multiplier $($r.fg_max_multiplier) below $($m.mult)") }
	[pscustomobject]@{ ok = ($reasons.Count -eq 0); refused = $refused; reasons = ($reasons -join '; ') }
}

function Wait-Mode($m, [int]$timeoutSeconds) {
	$deadline = (Get-Date).AddSeconds($timeoutSeconds)
	do {
		$check = Test-Mode $m (Get-Reflexium)
		if ($check.ok) { return $check }
		Start-Sleep -Milliseconds 500
	} while ((Get-Date) -lt $deadline)
	return $check
}

# ---------------------------------------------------------------------------------------------------
# Measurement

function Start-RouteStep($st) {
	if ($st.k -eq 'turn') {
		Post '/turn' @{ dyaw = $st.dyaw; dpitch = $st.dpitch; ticks = $stepTicks; async = $true } | Out-Null
	}
	else {
		Post '/move' @{ dx = $st.dx; dy = 0; dz = $st.dz; ticks = $stepTicks; async = $true } | Out-Null
	}
	# Wait for the step to register as running so the loop cannot start the next step early.
	$started = [Diagnostics.Stopwatch]::StartNew()
	while (-not (Get-Json '/status').motion_running -and $started.ElapsedMilliseconds -lt 2000) {
		Start-Sleep -Milliseconds 25
	}
}

function Measure-Route {
	$mark = [long](Post '/reflexium' @{ frame_mark = '1' }).frames
	$before = Get-Json '/status'
	$sw = [Diagnostics.Stopwatch]::StartNew()
	$gpu = New-Object System.Collections.Generic.List[double]
	$fps = New-Object System.Collections.Generic.List[double]
	$lastGpu = -1000.0
	$step = 0
	while ($true) {
		$s = Get-Json '/status'
		$now = $sw.Elapsed.TotalMilliseconds
		$fps.Add([double]$s.plugins.reflexium.fps)
		if ($now - $lastGpu -ge 1000) {
			$g = Get-GpuPct
			if ($null -ne $g) { $gpu.Add($g) }
			$lastGpu = $now
		}
		if ($s.motion_running) { Start-Sleep -Milliseconds 40; continue }
		if ($step -lt $route.Count) {
			Start-RouteStep $route[$step]
			$step++
			continue
		}
		break
	}
	$seconds = $sw.Elapsed.TotalSeconds
	$stats = Post '/reflexium' @{ frame_stats_from = "$mark" }
	$after = Get-Json '/status'
	$gpuSorted = $gpu.ToArray()
	[pscustomobject]@{
		route_seconds = [math]::Round($seconds, 3)
		route_ticks = [int]($after.ticks - $before.ticks)
		frames = [long]($stats.frames_to - $stats.frames_from)
		intervals = [int]$stats.intervals
		avg_fps = $stats.avg_fps
		mean_frame_ms = $stats.mean_ms
		p50_frame_ms = $stats.p50_ms
		p99_frame_ms = $stats.p99_ms
		low_1pct_fps = $stats.low_1pct_fps
		low_0_1pct_fps = $stats.low_0_1pct_fps
		displayed_fps_mean = [math]::Round((($fps | Measure-Object -Average).Average), 1)
		displayed_fps_samples = $fps.Count
		gpu_mean_pct = $(if ($gpuSorted.Count) { [math]::Round((($gpuSorted | Measure-Object -Average).Average), 1) } else { $null })
		gpu_max_pct = $(if ($gpuSorted.Count) { [math]::Round((($gpuSorted | Measure-Object -Maximum).Maximum), 1) } else { $null })
		gpu_samples = $gpuSorted.Count
		after = $after
	}
}

# ---------------------------------------------------------------------------------------------------
# Run control

function Write-Attempt($row) {
	$utf8 = New-Object System.Text.UTF8Encoding($false)
	[System.IO.File]::AppendAllText($runsFile, (($row | ConvertTo-Json -Compress -Depth 6) + "`n"), $utf8)
}

function New-Record($m, [int]$pass, [int]$seq, [int]$attempt, [string]$outcome, [string]$reasons) {
	[ordered]@{
		set = $Set
		mode = $m.id
		label = $m.label
		pass = $pass
		seq = $seq
		attempt = $attempt
		outcome = $outcome
		reasons = $reasons
		utc = (Get-Date).ToUniversalTime().ToString('o')
		game_pid = $session.game_pid
	}
}

function Invoke-ModeRun($m, [int]$pass, [int]$seq) {
	for ($attempt = 1; $attempt -le 3; $attempt++) {
		try {
			Apply-Mode $m
			$check = Wait-Mode $m 15
			if (-not $check.ok) {
				$outcome = if ($check.refused) { 'refused' } else { 'rejected' }
				Write-Attempt (New-Record $m $pass $seq $attempt $outcome $check.reasons)
				if ($check.refused -and $attempt -ge 2) { return }
				continue
			}
			Post '/camera' $camera | Out-Null
			Post '/wait' @{ ticks = $warmupTicks } | Out-Null
			$pre = Get-Reflexium
			$preCheck = Test-Mode $m $pre
			if (-not $preCheck.ok) {
				Write-Attempt (New-Record $m $pass $seq $attempt 'rejected' $preCheck.reasons)
				continue
			}
			$logBefore = Get-VitrailLogLine
			$measure = Measure-Route
			$post = $measure.after.plugins.reflexium
			$postCheck = Test-Mode $m $post
			$reasons = New-Object System.Collections.Generic.List[string]
			if (-not $postCheck.ok) { $reasons.Add($postCheck.reasons) }
			if ($m.upscaler -ne 'off' -and ([long]$post.evaluated_frames - [long]$pre.evaluated_frames) -le 0) { $reasons.Add('upscaler did not evaluate during route') }
			$presents = [long]$post.fg_generated_presents - [long]$pre.fg_generated_presents
			if ($m.fg -ne 'off' -and $presents -le 0) { $reasons.Add('frame generation produced no generated presents during route') }
			if ($m.fg -eq 'off' -and $presents -gt 0) { $reasons.Add('generated presents while frame generation off') }
			if ($reasons.Count -gt 0) {
				Write-Attempt (New-Record $m $pass $seq $attempt 'rejected' ($reasons -join '; '))
				continue
			}
			$row = New-Record $m $pass $seq $attempt 'accepted' ''
			$row['requested'] = [ordered]@{ upscaler = $m.upscaler; quality = $m.quality; dlss_model = $m.model; frame_generation = $m.fg; fg_multiplier = $m.mult }
			$row['window'] = "$($post.window.width)x$($post.window.height)"
			$row['render'] = "$($post.render_size.w)x$($post.render_size.h)"
			$row['display'] = "$($post.display_size.w)x$($post.display_size.h)"
			$row['upscaler_built'] = $post.upscaler_built
			$row['status_line'] = $post.status_line
			$row['fg_status'] = $post.fg_status
			$row['fg_max_multiplier'] = $post.fg_max_multiplier
			$row['logged_dlss_model'] = Get-LoggedModel
			$row['generated_presents'] = $presents
			$row['evaluated_frames'] = [long]$post.evaluated_frames - [long]$pre.evaluated_frames
			$row['route_seconds'] = $measure.route_seconds
			$row['route_ticks'] = $measure.route_ticks
			$row['frames'] = $measure.frames
			$row['avg_fps'] = $measure.avg_fps
			$row['mean_frame_ms'] = $measure.mean_frame_ms
			$row['p50_frame_ms'] = $measure.p50_frame_ms
			$row['p99_frame_ms'] = $measure.p99_frame_ms
			$row['low_1pct_fps'] = $measure.low_1pct_fps
			$row['low_0_1pct_fps'] = $measure.low_0_1pct_fps
			$row['displayed_fps_mean'] = $measure.displayed_fps_mean
			$row['displayed_fps_samples'] = $measure.displayed_fps_samples
			$row['gpu_mean_pct'] = $measure.gpu_mean_pct
			$row['gpu_max_pct'] = $measure.gpu_max_pct
			$row['gpu_samples'] = $measure.gpu_samples
			$row['vitrail_log'] = $logBefore
			Write-Attempt $row
			Write-Host ("{0,-3} {1,-44} avg {2,8} base  disp {3,8}  1%low {4,8}  {5}  gpu {6}%" -f $pass, $m.label, $measure.avg_fps, $measure.displayed_fps_mean, $measure.low_1pct_fps, $row.render, $measure.gpu_mean_pct)
			return
		}
		catch {
			Write-Attempt (New-Record $m $pass $seq $attempt 'error' $_.Exception.Message)
			Write-Host "error on $($m.id): $($_.Exception.Message)"
		}
	}
	Write-Attempt (New-Record $m $pass $seq 3 'failed' 'three attempts rejected')
}

# ---------------------------------------------------------------------------------------------------
# Summary

function Build-Summary {
	if (-not (Test-Path -LiteralPath $runsFile)) { throw "No runs file at $runsFile" }
	$rows = Get-Content -LiteralPath $runsFile | ForEach-Object { $_ | ConvertFrom-Json }
	$accepted = @($rows | Where-Object { $_.outcome -eq 'accepted' })
	$native = @($accepted | Where-Object { $_.mode -eq 'native' })
	$nativeDisplayed = ($native | Measure-Object -Property displayed_fps_mean -Average).Average
	$nativeBase = ($native | Measure-Object -Property avg_fps -Average).Average
	$summary = foreach ($m in $modes) {
		$runs = @($accepted | Where-Object { $_.mode -eq $m.id })
		$others = @($rows | Where-Object { $_.mode -eq $m.id -and $_.outcome -ne 'accepted' })
		if ($runs.Count -eq 0) {
			$reason = if ($others.Count) { ($others | Select-Object -Last 1).outcome + ': ' + ($others | Select-Object -Last 1).status_line + ' ' + ($others | Select-Object -Last 1).reasons } else { 'not run' }
			[pscustomobject]@{ set = $Set; mode = $m.id; label = $m.label; runs = 0; status = $reason }
			continue
		}
		$p1 = @($runs | Where-Object { $_.pass -eq 1 })
		$p2 = @($runs | Where-Object { $_.pass -eq 2 })
		$disp = ($runs | Measure-Object -Property displayed_fps_mean -Average).Average
		$base = ($runs | Measure-Object -Property avg_fps -Average).Average
		[pscustomobject]@{
			set = $Set
			mode = $m.id
			label = $m.label
			runs = $runs.Count
			status = 'ok'
			displayed_fps = [math]::Round($disp, 1)
			base_fps = [math]::Round($base, 1)
			low_1pct_fps = [math]::Round(($runs | Measure-Object -Property low_1pct_fps -Average).Average, 1)
			mean_frame_ms = [math]::Round(($runs | Measure-Object -Property mean_frame_ms -Average).Average, 3)
			vs_native_displayed_pct = $(if ($nativeDisplayed) { [math]::Round(100 * ($disp / $nativeDisplayed - 1), 1) } else { $null })
			vs_native_base_pct = $(if ($nativeBase) { [math]::Round(100 * ($base / $nativeBase - 1), 1) } else { $null })
			render = $runs[0].render
			gpu_mean_pct = [math]::Round(($runs | Measure-Object -Property gpu_mean_pct -Average).Average, 1)
			pass1_displayed_fps = $(if ($p1.Count) { [math]::Round(($p1 | Measure-Object -Property displayed_fps_mean -Average).Average, 1) } else { $null })
			pass2_displayed_fps = $(if ($p2.Count) { [math]::Round(($p2 | Measure-Object -Property displayed_fps_mean -Average).Average, 1) } else { $null })
			status_line = $runs[0].status_line
		}
	}
	$csv = Join-Path $outDir "upscaling-set-$Set.csv"
	$summary | Where-Object { $_.status -eq 'ok' } | Export-Csv -LiteralPath $csv -NoTypeInformation -Encoding UTF8
	$summary | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $outDir "upscaling-set-$Set.json") -Encoding UTF8
	$summary | Format-Table -AutoSize | Out-String | Write-Host
}

if ($Summarize) {
	Build-Summary
	return
}

# ---------------------------------------------------------------------------------------------------
# Main

New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$status = Get-Reflexium
$game = Get-Json '/status'
if ($game.pid -ne $session.game_pid) { throw "Harness pid $($game.pid) does not match session $($session.game_pid)" }
if (-not $game.world_loaded) { throw "No world loaded. Join '$world' first." }
Write-Host "Set $Set on pid $($game.pid), window $($game.window.width)x$($game.window.height), world loaded."

# Conditioning run: the first capture per JVM is cold (see minecraft/fps-lab.md). Recorded as pass 0.
Invoke-ModeRun ($modes[0]) 0 0

$forward = @($modes)
$reverse = @($modes); [array]::Reverse($reverse)
$seq = 0
foreach ($m in $forward) { $seq++; Invoke-ModeRun $m 1 $seq }
foreach ($m in $reverse) { $seq++; Invoke-ModeRun $m 2 $seq }

Build-Summary
