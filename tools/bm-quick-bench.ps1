<#
.SYNOPSIS
Benchmark Mod captures two Reflexium jars in fresh-JVM ABBA order without desktop input, on the FPS Lab standard scene.
.EXAMPLE
powershell -NoProfile -ExecutionPolicy Bypass -File tools/bm-quick-bench.ps1 -OldJar build/perf-jars/ac1e339.jar -NewJar build/libs/Reflexium-1.2.0-alpha-fabric-26.3.jar -IncludeFG -Label candidate
.EXAMPLE
Quick rough check (about 8 minutes): only the modes the change touches, one launch per jar, one pass. Full ABBA runs are for overnight.
powershell -NoProfile -ExecutionPolicy Bypass -File tools/bm-quick-bench.ps1 -Quick -OldJar build/perf-jars/ac1e339.jar -NewJar build/libs/Reflexium-1.2.0-alpha-fabric-26.3.jar -Modes native,tf-performance,fg2 -Label quick
#>
param(
    [string]$OldJar,
    [string]$NewJar,
    [string]$Label = (Get-Date -Format 'yyyyMMdd-HHmmss'),
    [ValidateSet('A','B')][string]$Set = 'A',
    [switch]$IncludeFG,
    # Rough verification: one launch per jar, one measured pass, no BM conditioning run (about 1 min per mode).
    [switch]$Quick,
    [switch]$Explore,
    [switch]$Diagnostics,
    # Benchmark Mod scene the FPS Lab freezes as its standard (route plus world). Copied read-only into the run's data directory.
    [string]$SceneSource = 'C:\Users\Ryan\Documents\Minecraft FPS Lab\baseline\scene',
    [string]$Modes,
    [ValidateRange(1,5)][int]$Passes = 3,
    [ValidateRange(0,60)][int]$WarmupSeconds = 10,
    [int]$Port = 25601,
    [string]$Harness = 'C:\Users\Ryan\source\repos\mc-agent-harness',
    [string]$BenchmarkJar = 'C:\Users\Ryan\source\repos\Benchmark Mod\build\libs\benchmark-mod-1.0.0.jar',
    [string]$JavaHome = 'C:\Program Files\GraalVM\graalvm-jdk-25.0.4+7.1',
    [switch]$SelfCheck
)
$ErrorActionPreference = 'Stop'
if ($Quick) {
    if (!$PSBoundParameters.ContainsKey('Passes')) { $Passes = 1 }
    if (!$PSBoundParameters.ContainsKey('WarmupSeconds')) { $WarmupSeconds = 5 }
}
Add-Type -AssemblyName System.Net.Http
Add-Type @'
using System;
public static class BmQuickStats {
    public static double[] Calculate(double[] values) {
        if (values.Length == 0) throw new ArgumentException("Empty BM frames");
        Array.Sort(values); double sum=0, tail=0;
        foreach (double v in values) { if (v <= 0 || double.IsNaN(v) || double.IsInfinity(v)) throw new ArgumentException("Invalid BM frame"); sum+=v; }
        int n=Math.Max(1,(int)Math.Ceiling(values.Length*.01));
        for(int i=values.Length-n;i<values.Length;i++) tail+=values[i];
        return new[]{1000*values.Length/sum,1000*n/tail,values[(int)Math.Ceiling(values.Length*.99)-1]};
    }
}
'@
function SceneInfo($json) {
    if ($json.id -notmatch '^[a-zA-Z0-9_-]+$' -or $json.keyframes.Count -lt 2) { throw 'Source must be an existing BM scene with at least two keyframes.' }
    $distance = [int]$json.environment.renderDistanceHint
    if ($distance -lt 2 -or $distance -gt 32) { throw 'Scene must specify renderDistanceHint in 2..32.' }
    return $distance
}
function FindCompetingGpuWork($processes, [uint32[]]$ignored) {
    @($processes | Where-Object {
        $_.ProcessId -notin $ignored -and (
            ($_.Name -match '^javaw?\.exe$' -and $_.CommandLine -match 'net\.fabricmc\.(devlaunchinjector\.Main|loader\..*KnotClient)|net\.minecraft\.client\.main\.Main') -or
            ($_.Name -match '^(powershell|pwsh|python|pythonw)\.exe$' -and $_.CommandLine -match 'bm-quick-bench\.ps1|upscaling-quality\.ps1|mctest\.ps1.{0,250}\brun\b|lab\.py.{0,250}\brun\b') -or
            $_.Name -eq 'reflex-reference.exe'
        )
    })
}
function AssertGpuIdle {
    $processes = @(Get-CimInstance Win32_Process)
    # The invoking shell can contain this script's name, so exclude our full ancestor chain.
    $ignored = [Collections.Generic.List[uint32]]::new()
    $current = [uint32]$PID
    while ($current -gt 0 -and !$ignored.Contains($current)) {
        $ignored.Add($current)
        $parent = $processes | Where-Object ProcessId -eq $current | Select-Object -First 1
        if (!$parent) { break }; $current = [uint32]$parent.ParentProcessId
    }
    $busy = @(FindCompetingGpuWork $processes $ignored.ToArray())
    if ($busy.Count) { throw ('GPU measurement refused while other Minecraft/benchmark work is active: ' + (($busy | ForEach-Object { "$($_.Name) PID $($_.ProcessId)" }) -join ', ')) }
}
$fgCounterKeys = @('fg_evaluated_frames','fg_generated_presents','fg_real_presents','fg_batches_started','fg_batches_completed','fg_metering_batch_configs','fg_metering_reset_configs','fg_metering_unreported','fg_metering_accepted','fg_metering_status','fg_metering_rejected')
function FgSnapshot($status) {
    $snap = [ordered]@{}
    foreach ($key in $fgCounterKeys) { $snap[$key] = if ($status.PSObject.Properties.Name -contains $key) { [long]$status.$key } else { $null } }
    foreach ($key in @('fg_status','fg_failed','fg_problem','fg_metering_problem','fg_metering_feedback','fg_metering_feedback_raw','fg_metering_rejection_streak','fg_metering_reset_pending','fg_multiplier','fg_max_multiplier','frame_generation')) {
        $snap[$key] = if ($status.PSObject.Properties.Name -contains $key) { $status.$key } else { $null }
    }
    $snap
}
function FgDelta($before, $after) {
    $d = [ordered]@{}
    foreach ($key in $fgCounterKeys) { $d[$key] = if ($null -eq $before[$key] -or $null -eq $after[$key]) { $null } else { $after[$key] - $before[$key] } }
    $d
}
# Sustained FG acceptance. Counter deltas only, never cumulative totals. NVIDIA modes expect (multiplier - 1)
# generated presents per real present. The whole-capture ratio includes menu, load and cut frames in its real
# denominator, so it only rejects (CoarseFraction of the expected ratio). Functional acceptance needs at least two
# instrumented steady windows (Diagnostics), each within WindowFraction of the expected ratio.
$FgCoarseFraction = 0.6; $FgWindowFraction = 0.9
function FgAcceptance($mode, $before, $after, $windows, [int]$maxMultiplier, [long]$bmFrames = 0) {
    $effective = if ($mode.fg -eq 'dlss_multi') { [Math]::Min([int]$mode.mult, [Math]::Max(2, $maxMultiplier)) } else { 2 }
    $expected = $effective - 1
    $delta = FgDelta $before $after
    $r = [ordered]@{fg_requested_multiplier=[int]$mode.mult;fg_effective_multiplier=$effective;fg_expected_ratio=$expected;fg_before=$before;fg_after=$after;fg_delta=$delta;fg_ratio=$null;fg_windows=@($windows);fg_steady='not measured (uninstrumented capture)';fg_valid=$false;fg_invalid_reason=''}
    $gen = $delta.fg_generated_presents; $real = $delta.fg_real_presents; $eval = $delta.fg_evaluated_frames; $r.fg_denominator = 'fg_real_presents delta'
    # Builds without fg_real_presents: BM measured frames exclude load and warmup, so the ratio is only a coarse upper bound.
    if ($null -eq $real -and $bmFrames -gt 0) { $real = $bmFrames; $r.fg_denominator = 'BM measured frames (build lacks fg_real_presents)' }
    if ($null -eq $gen -or $null -eq $real -or $null -eq $eval) { $r.fg_invalid_reason = 'missing FG counters in this build'; return $r }
    if ($eval -le 0 -or $gen -le 0) { $r.fg_invalid_reason = "no FG activity (evaluated $eval, generated $gen)"; return $r }
    if ($after.fg_failed -eq $true) { $r.fg_invalid_reason = "FG failure latched: $($after.fg_problem)"; return $r }
    if ($mode.fg -eq 'fsr') { $r.fg_valid = $true; $r.fg_invalid_reason = ''; $r.fg_steady = 'FSR proxy route: generated scanout not counted'; return $r }
    if ($real -le 0) { $r.fg_invalid_reason = 'no real presents'; return $r }
    $r.fg_ratio = $gen / $real
    if ($r.fg_ratio -lt $FgCoarseFraction * $expected) { $r.fg_invalid_reason = ('generation not sustained: {0} generated per real, expected {1}' -f [Math]::Round($r.fg_ratio,3),$expected); return $r }
    if ($null -ne $delta.fg_metering_rejected -and $null -ne $delta.fg_metering_batch_configs -and $delta.fg_metering_batch_configs -gt 0 -and $delta.fg_metering_rejected -gt 0.1 * $delta.fg_metering_batch_configs) {
        $r.fg_invalid_reason = "persistent metering rejection: $($delta.fg_metering_rejected) of $($delta.fg_metering_batch_configs) batch configs"; return $r
    }
    $w = @($windows)
    if ($w.Count -ge 3) {
        $ratios = for ($i = 1; $i -lt $w.Count; $i++) {
            $wd = FgDelta $w[$i-1] $w[$i]
            if ($wd.fg_real_presents -gt 0) { $wd.fg_generated_presents / $wd.fg_real_presents } else { 0 }
        }
        $r.fg_window_ratios = @($ratios)
        $low = @($ratios | Where-Object { $_ -lt $FgWindowFraction * $expected })
        if ($low.Count) { $r.fg_steady = 'failed'; $r.fg_invalid_reason = ('steady window below {0} of expected ratio: {1}' -f $FgWindowFraction,(($ratios | ForEach-Object { [Math]::Round($_,3) }) -join ', ')); return $r }
        $r.fg_steady = "passed ($($ratios.Count) windows)"
    }
    $r.fg_valid = $true
    $r
}
if ($SelfCheck) {
    $fgMode = @{fg='dlss';mult=2}; $mfgMode = @{fg='dlss_multi';mult=6}
    function Snap($eval,$gen,$real,$rej=0,$cfg=0) { [ordered]@{fg_evaluated_frames=$eval;fg_generated_presents=$gen;fg_real_presents=$real;fg_batches_started=$null;fg_batches_completed=$null;fg_metering_batch_configs=$cfg;fg_metering_reset_configs=0;fg_metering_unreported=0;fg_metering_accepted=0;fg_metering_rejected=$rej;fg_failed=$false} }
    $b = Snap 100 100 1000
    if ((FgAcceptance $fgMode $b (Snap 100 100 21000) @() 6).fg_valid) { throw 'Zero generated frames accepted' }
    if ((FgAcceptance $fgMode $b (Snap 20100 101 21000) @() 6).fg_valid) { throw 'One generated frame accepted' }
    if (!(FgAcceptance $fgMode $b (Snap 19100 18100 21000) @() 6).fg_valid) { throw 'Correct 2x ratio rejected' }
    if (!(FgAcceptance $mfgMode $b (Snap 19100 90100 21000) @() 6).fg_valid) { throw 'Correct 6x ratio rejected' }
    if ((FgAcceptance $mfgMode $b (Snap 19100 18100 21000) @() 6).fg_valid) { throw '2x ratio accepted for 6x' }
    if ((FgAcceptance $fgMode $b (Snap 10100 10100 21000) @() 6).fg_valid) { throw 'Generation stopped mid-capture accepted' }
    $windows = @((Snap 5000 4000 6000),(Snap 8000 7000 9000),(Snap 8000 7000 12000))
    $stopped = FgAcceptance $fgMode $b (Snap 19100 18100 21000) $windows 6
    if ($stopped.fg_valid -or $stopped.fg_steady -ne 'failed') { throw 'Stopped steady window accepted' }
    $windows[2] = Snap 11000 10000 12000
    if ((FgAcceptance $fgMode $b (Snap 19100 18100 21000) $windows 6).fg_steady -notmatch '^passed') { throw 'Steady windows rejected' }
    if ((FgAcceptance $fgMode $b (Snap 19100 18100 21000 500 1000) @() 6).fg_valid) { throw 'Persistent rejection accepted' }
    $legacy = [ordered]@{fg_evaluated_frames=100;fg_generated_presents=100;fg_real_presents=1000}
    $legacyAfter = [ordered]@{fg_evaluated_frames=19100;fg_generated_presents=18100;fg_real_presents=21000}
    $legacyResult = FgAcceptance $fgMode (FgSnapshot ([pscustomobject]$legacy)) (FgSnapshot ([pscustomobject]$legacyAfter)) @() 6
    if (!$legacyResult.fg_valid -or $null -ne $legacyResult.fg_delta.fg_metering_rejected) { throw 'Old-build missing fields mishandled' }
    if ((FgAcceptance $fgMode (FgSnapshot ([pscustomobject]@{})) (FgSnapshot ([pscustomobject]@{})) @() 6).fg_invalid_reason -notmatch 'missing') { throw 'Missing counters accepted' }
    $noReal = @{fg_evaluated_frames=100;fg_generated_presents=100}; $noRealAfter = @{fg_evaluated_frames=19100;fg_generated_presents=18100}
    $fallback = FgAcceptance $fgMode (FgSnapshot ([pscustomobject]$noReal)) (FgSnapshot ([pscustomobject]$noRealAfter)) @() 6 17000
    if (!$fallback.fg_valid -or $fallback.fg_denominator -notmatch 'BM measured') { throw 'Old-build BM frame denominator failed' }
    if ((FgAcceptance $fgMode (FgSnapshot ([pscustomobject]$noReal)) (FgSnapshot ([pscustomobject]@{fg_evaluated_frames=19100;fg_generated_presents=101})) @() 6 17000).fg_valid) { throw 'Old-build single generated frame accepted' }
    Write-Host 'FG sustained acceptance checks passed'
    $s = [BmQuickStats]::Calculate(@(1,2,3,4))
    if ($s[0] -ne 400 -or $s[1] -ne 250 -or $s[2] -ne 4) { throw 'BM statistics check failed' }
    $fixture = @{id='check';keyframes=@(@{},@{});environment=@{renderDistanceHint=16}}
    if ((SceneInfo $fixture) -ne 16) { throw 'Scene distance check failed' }
    $fixture.keyframes = @(); $rejected = $false
    try { SceneInfo $fixture | Out-Null } catch { $rejected = $true }
    if (!$rejected) { throw 'Placeholder scene accepted' }
    $processes = @(
        @{ProcessId=1;Name='java.exe';CommandLine='net.fabricmc.devlaunchinjector.Main'},
        @{ProcessId=2;Name='javaw.exe';CommandLine='net.fabricmc.loader.impl.launch.knot.KnotClient'},
        @{ProcessId=3;Name='java.exe';CommandLine='org.gradle.launcher.daemon.bootstrap.GradleDaemon'},
        @{ProcessId=4;Name='powershell.exe';CommandLine='-File tools/bm-quick-bench.ps1 -Label other'},
        @{ProcessId=5;Name='powershell.exe';CommandLine='-File tools/bm-quick-bench.ps1 -Label ours'},
        @{ProcessId=6;Name='reflex-reference.exe';CommandLine='reflex-reference.exe'},
        @{ProcessId=7;Name='python.exe';CommandLine='lab.py run --experiment other'}
    )
    $busy = @(FindCompetingGpuWork $processes @(5))
    if (($busy.ProcessId -join ',') -ne '1,2,4,6,7') { throw 'Concurrent GPU work detection failed' }
    Write-Host 'BM statistics, scene and concurrent GPU work checks passed'; exit
}
if ($Explore -and !$NewJar) { throw 'Specify -NewJar for -Explore.' }
if (!$Explore -and (!$OldJar -or !$NewJar)) { throw 'Specify -OldJar and -NewJar.' }
if ($Label -notmatch '^[a-zA-Z0-9_-]+$') { throw 'Label must contain letters, digits, underscores or hyphens' }
AssertGpuIdle
$root = Split-Path -Parent $PSScriptRoot
$old = if ($OldJar) { (Resolve-Path -LiteralPath $OldJar).Path } else { $null }
$new = (Resolve-Path -LiteralPath $NewJar).Path
$bm = (Resolve-Path -LiteralPath $BenchmarkJar).Path
$out = Join-Path $root "build\bm-bench\$Label-$Set"
$client = Join-Path $out 'client'
$data = Join-Path $out 'data'
$sceneSource = (Resolve-Path -LiteralPath $SceneSource).Path
$sceneJson = Get-Content (Join-Path $sceneSource 'scene.json') -Raw | ConvertFrom-Json
$sceneId = $sceneJson.id
$renderDistance = SceneInfo $sceneJson
if (!(Test-Path (Join-Path $sceneSource 'world'))) { throw 'Source scene has no world.' }
$scene = Join-Path $data "scenes\$sceneId"

function WriteJson($path,$value) { [IO.File]::WriteAllText($path,($value | ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false)) }
function WorldHashes { @(Get-ChildItem (Join-Path $scene 'world') -File -Recurse | Where-Object { $_.Name -ne 'session.lock' -and $_.FullName -notmatch '\\players\\(stats|advancements)\\' } | Sort-Object FullName | ForEach-Object { @{path=$_.FullName.Substring($scene.Length);sha256=(Get-FileHash -LiteralPath $_.FullName).Hash} }) }

$resuming = Test-Path -LiteralPath $out
if (!$resuming) {
    New-Item -ItemType Directory -Force $client,$scene | Out-Null
    foreach ($name in @('mods','config','vitrail','shaderpacks')) {
        if (Test-Path (Join-Path $root "run\$name")) { Copy-Item (Join-Path $root "run\$name") $client -Recurse }
    }
    Copy-Item (Join-Path $root 'run\options.txt') $client
    # Only remove duplicate instrumentation/mod jars inside the new, isolated client.
    foreach ($pattern in @('Reflexium-*.jar','benchmark-mod-*.jar','reflexium-bm-driver.jar')) {
        Get-ChildItem (Join-Path $client 'mods') -Filter $pattern | ForEach-Object { Remove-Item -LiteralPath $_.FullName }
    }
    Copy-Item $bm (Join-Path $client 'mods')
    Copy-Item (Join-Path $root 'build\libs\reflexium-bm-driver.jar') (Join-Path $client 'mods')
    Copy-Item (Join-Path $sceneSource '*') $scene -Recurse
    WriteJson (Join-Path $client 'config\benchmarkmod.json') @{dataDirectory=$data}
    [IO.File]::WriteAllText((Join-Path $client 'config\reflexium.properties'), "reflex_mode=on`nupscaler=off`nframe_generation=off`nfsr_sharpness=20`n")
    WriteJson (Join-Path $data 'settings.json') @{schema=1;warmupSeconds=$WarmupSeconds;measuredRuns=$Passes;conditioningRun=!$Quick;recordMetrics=$true;gpuUsageSampling=$true;onboardingSeen=$true;overlay=@{enabled=$false}}
    $pack = Join-Path $client 'vitrail\pack.txt'
    if (Test-Path $pack) { [IO.File]::WriteAllText($pack,((Get-Content $pack -Raw) -replace 'enabled=(true|false)',('enabled=' + ($Set -eq 'B').ToString().ToLower()))) }
    $options = Join-Path $client 'options.txt'
    $optionText = Get-Content $options -Raw
    foreach ($entry in @{enableVsync='false';maxFps='260';renderDistance=[string]$renderDistance;simulationDistance='12';fullscreen='false';preferredGraphicsBackend='"vulkan"'}.GetEnumerator()) { $optionText = $optionText -replace "(?m)^$($entry.Key):[^\r\n]*", "$($entry.Key):$($entry.Value)" }
    [IO.File]::WriteAllText($options,$optionText)
    $hashes = WorldHashes
    WriteJson (Join-Path $out 'world-before.json') $hashes
    WriteJson (Join-Path $out 'protocol.json') @{scene_source=$sceneSource;scene_sha256=(Get-FileHash (Join-Path $sceneSource 'scene.json')).Hash;render_distance=$renderDistance;route_seconds=$sceneJson.routeSeconds;diagnostics=[bool]$Diagnostics;quick=[bool]$Quick;passes=$Passes;warmup_seconds=$WarmupSeconds;conditioning=$(if ($Quick) { 'disabled (-Quick)' } else { 'Benchmark Mod per JVM' })}
} else {
    $beforeJson = Join-Path $out 'world-before.json'
    if (Test-Path $beforeJson) {
        $hashes = Get-Content $beforeJson -Raw | ConvertFrom-Json
    } else {
        $hashes = WorldHashes
    }
}

$definitions = @(
    @{id='native';upscaler='off';quality='performance';model='transformer';fg='off';mult=2},
    @{id='cnn-performance';upscaler='dlss';quality='performance';model='cnn';fg='off';mult=2},
    @{id='tf-performance';upscaler='dlss';quality='performance';model='transformer';fg='off';mult=2},
    @{id='tf-dlaa';upscaler='dlss';quality='native_aa';model='transformer';fg='off';mult=2},
    @{id='fsr-performance';upscaler='fsr';quality='performance';model='transformer';fg='off';mult=2},
    @{id='fg2';upscaler='dlss';quality='quality';model='transformer';fg='dlss';mult=2},
    @{id='fg4';upscaler='dlss';quality='quality';model='transformer';fg='dlss_multi';mult=4},
    @{id='fg5';upscaler='dlss';quality='quality';model='transformer';fg='dlss_multi';mult=5},
    @{id='fg6';upscaler='dlss';quality='quality';model='transformer';fg='dlss_multi';mult=6},
    @{id='fsr-fg';upscaler='fsr';quality='quality';model='transformer';fg='fsr';mult=2}
)
$chosen = if ($Modes) { @($Modes.Split(',')) } elseif ($IncludeFG) { @('native','cnn-performance','tf-performance','tf-dlaa','fsr-performance','fg2','fg4') } else { @('native','cnn-performance','tf-performance','tf-dlaa','fsr-performance') }
# A mode may carry runtime overrides after '@', for example tf-performance@reflex_mode=off. Only keys listed
# here are accepted; each is restored to its shipped value before the next capture that does not set it.
$knobs = @{reflex_submission='1';perf_uniforms='1';perf_depth='1';dlss_preset='-1';reflex_mode='on';smart_reflex='0';reflex_interval_us='-1'}
$selected = foreach ($entry in $chosen) {
    $parts = $entry.Split('@')
    $base = $definitions | Where-Object id -eq $parts[0]
    if (!$base) { throw "Unknown mode '$($parts[0])'" }
    $mode = $base.Clone(); $mode.id = ($entry -replace '[@=]','-'); $mode.overrides = @{}
    if ($parts.Count -gt 1) {
        foreach ($override in $parts[1..($parts.Count - 1)]) {
            $kv = $override.Split('=')
            if ($kv.Count -ne 2 -or !$knobs.ContainsKey($kv[0])) { throw "Unknown override '$override'; keys: $($knobs.Keys -join ', ')" }
            $mode.overrides[$kv[0]] = $kv[1]
        }
    }
    $mode
}
$selected = @($selected)

# Check if existing summary already covers all selected modes
$summaryFile = Join-Path $out 'summary.json'
if (Test-Path $summaryFile) {
    try {
        $existingSummary = Get-Content $summaryFile -Raw | ConvertFrom-Json
        if ($existingSummary.Count -eq $selected.Count) {
            Write-Host "Set $Set summary ($summaryFile) already complete; skipping benchmark."
            $existingSummary | ForEach-Object { [pscustomobject]$_ } | Format-Table mode,old_fps,new_fps,fps_change_pct,old_low1,new_low1,low1_change_pct
            Write-Host "Artifacts: $out"
            return
        }
    } catch {
        Write-Warning "Could not read existing summary: $_"
    }
}

$sessionFile = Join-Path $Harness "instances\$Port\session.json"
$http = [Net.Http.HttpClient]::new(); $http.Timeout=[TimeSpan]::FromSeconds(45)
function Call([string]$endpoint,[hashtable]$params=@{}) {
    for ($attempt = 0; $attempt -lt 15; $attempt++) {
        $content = [Net.Http.StringContent]::new(($params | ConvertTo-Json -Compress),[Text.Encoding]::UTF8,'application/json')
        try {
            $r=$http.PostAsync("http://127.0.0.1:$Port/$endpoint",$content).GetAwaiter().GetResult()
            try {
                $body=$r.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                if (!$r.IsSuccessStatusCode) {
                    if ($attempt -lt 14 -and ($body -match 'TimeoutException' -or $r.StatusCode -eq 504)) {
                        Start-Sleep -Seconds 2
                        continue
                    }
                    throw $body
                }
                $v=$body | ConvertFrom-Json
                if (!$v.ok) {
                    if ($attempt -lt 14 -and ($v.error -match 'TimeoutException' -or $body -match 'TimeoutException')) {
                        Start-Sleep -Seconds 2
                        continue
                    }
                    throw $body
                }
                return $v
            } finally { $r.Dispose() }
        } catch {
            if ($attempt -lt 14 -and ($_.Exception.Message -match 'TimeoutException' -or $_.Exception.Message -match 'timed out' -or $_.Exception.Message -match '504')) {
                Start-Sleep -Seconds 2
                continue
            }
            throw
        } finally { $content.Dispose() }
    }
}
function Status { $http.GetStringAsync("http://127.0.0.1:$Port/status").GetAwaiter().GetResult() | ConvertFrom-Json }
function ReadCapture($path) {
    $file=[IO.File]::OpenRead($path)
    $gzip=[IO.Compression.GZipStream]::new($file,[IO.Compression.CompressionMode]::Decompress)
    $reader=[IO.StreamReader]::new($gzip)
    try { $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose(); $gzip.Dispose(); $file.Dispose() }
}
function SetOption($key,$value) { Call 'reflexium' @{key=$key;value=[string]$value} | Out-Null }
function ReadReflexium {
    try { return Call 'reflexium' }
    catch {
        if ($_.Exception.Message -notmatch 'TimeoutException') { throw }
        Write-Warning 'Read-only Reflexium status timed out; retrying once.'
        Start-Sleep -Seconds 2
        return Call 'reflexium'
    }
}
function StopOwned {
    $s=Status
    $p=Get-CimInstance Win32_Process -Filter "ProcessId = $($session.game_pid)"
    if ($s.pid -ne $session.game_pid -or $s.game_directory -ne $client -or !$p -or !$p.CommandLine.Contains($session.jar) -or !$p.CommandLine.Contains($client)) { throw 'Shutdown ownership mismatch' }
    try { Call 'quit' @{exit=$true} | Out-Null } catch { Write-Warning "Graceful quit request threw: $_" }
    $deadline=(Get-Date).AddSeconds(45)
    while (Get-Process -Id $session.game_pid -ErrorAction SilentlyContinue) {
        if ((Get-Date) -gt $deadline) {
            Write-Warning 'Owned client shutdown timed out; terminating process.'
            Stop-Process -Id $session.game_pid -Force -ErrorAction SilentlyContinue
            break
        }
        Start-Sleep -Seconds 1
    }
}

$runsFile = Join-Path $out 'runs.jsonl'
$rows = [Collections.Generic.List[object]]::new()
if (Test-Path $runsFile) {
    Get-Content $runsFile | ForEach-Object {
        if ($_.Trim()) {
            $rows.Add(($_ | ConvertFrom-Json))
        }
    }
    Write-Host "Loaded $($rows.Count) existing capture rows from $runsFile"
}

$owned=$false
try {
    # ABBA across fresh JVMs: old forward, new forward, new reversed, old reversed. -Explore is one JVM
    # of the new jar, forward then reversed, for comparing runtime overrides cheaply.
    $launches = @(if ($Explore) { @{arm='explore';jar=$new;orders=@(if ($Quick) { 'f' } else { 'f','r' })} }
        elseif ($Quick) { @(@{arm='old';jar=$old;orders=@('f')},@{arm='new';jar=$new;orders=@('f')}) }
        else { @(@{arm='old';jar=$old;orders=@('f')},@{arm='new';jar=$new;orders=@('f')},@{arm='new';jar=$new;orders=@('r')},@{arm='old';jar=$old;orders=@('r')}) })
    $touched = [Collections.Generic.HashSet[string]]::new()
    for ($slot=0;$slot -lt $launches.Count;$slot++) {
        $arm=$launches[$slot].arm
        $jar=$launches[$slot].jar

        $order=@()  # Benchmark Mod conditions the first capture of a JVM itself (conditioningRun), so no extra warm-up capture.
        foreach ($direction in $launches[$slot].orders) {
            $matrix=@($selected); if ($direction -eq 'r') { [array]::Reverse($matrix) }
            foreach ($m in $matrix) { $copy=$m.Clone(); $copy.direction=$direction; $order += $copy }
        }

        # Check if all captures for this slot are already present in runs.jsonl
        $slotComplete = $true
        $idx = 0
        foreach ($m in $order) {
            $idx++
            $lbl = "s$slot$($m.direction)$idx"
            if (!($rows | Where-Object { $_.label -eq $lbl })) {
                $slotComplete = $false
                break
            }
        }
        if ($slotComplete) {
            Write-Host "Launch $($slot+1)/$($launches.Count): $arm, Set $Set (all captures present in runs.jsonl; skipping launch)"
            continue
        }

        AssertGpuIdle
        Write-Host "Launch $($slot+1)/$($launches.Count): $arm, Set $Set"
        $touched.Clear()
        $props=@{rxBenchJar=$jar} | ConvertTo-Json -Compress
        & (Join-Path $Harness 'tools\launch.ps1') -Project $root -RunDirectory $client -GradlePropertiesJson $props -Port $Port -Monitor secondary -Width 1920 -Height 1080 -JavaHome $JavaHome | Out-File (Join-Path $out "launch-$slot.json")
        $session=Get-Content $sessionFile -Raw | ConvertFrom-Json
        $http.DefaultRequestHeaders.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$session.token)
        $owned=$true
        if (!$session.focus_unchanged) { throw 'Launch changed desktop foreground window' }
        $driver=Call 'bm'
        if ($driver.data_directory -ne $data) { throw 'BM data directory mismatch' }
        SetOption 'frame_generation' 'off'; SetOption 'upscaler' 'off'

        $index=0
        foreach ($mode in $order) {
            $index++
            $captureLabel="s$slot$($mode.direction)$index"
            if ($rows | Where-Object { $_.label -eq $captureLabel }) {
                Write-Host "${captureLabel}: already captured in runs.jsonl; skipping"
                continue
            }

            foreach ($key in @($touched)) { if (!$mode.overrides.ContainsKey($key)) { SetOption $key $knobs[$key]; [void]$touched.Remove($key) } }
            foreach ($key in $mode.overrides.Keys) { SetOption $key $mode.overrides[$key]; [void]$touched.Add($key) }
            SetOption 'frame_generation' 'off'; SetOption 'upscaler' $mode.upscaler
            SetOption 'quality' $mode.quality; SetOption 'dlss_model' $mode.model
            if ($mode.mult -gt 2) { SetOption 'fg_multiplier' $mode.mult }
            SetOption 'frame_generation' $mode.fg
            $beforeMode=ReadReflexium
            if ($beforeMode.upscaler -ne $mode.upscaler -or $beforeMode.quality -ne $mode.quality -or $beforeMode.dlss_model -ne $mode.model -or $beforeMode.frame_generation -ne $mode.fg) { throw "Mode switch failed: $($mode.id)" }
            if ($mode.mult -gt 2 -and $beforeMode.fg_multiplier -ne $mode.mult) { throw "FG multiplier switch failed: $($mode.id)" }
            $results=Join-Path $data 'results'
            $existing=@(Get-ChildItem $results -Filter '*.bmresult.json.gz' -Recurse -ErrorAction SilentlyContinue | ForEach-Object Name)
            $started = $false
            for ($bmAttempt = 0; $bmAttempt -lt 30; $bmAttempt++) {
                try {
                    Call 'bm' @{scene=$sceneId;label=$captureLabel} | Out-Null
                    $started = $true
                    break
                } catch {
                    if ($_.Exception.Message -match 'Start BM from a menu|TimeoutException|timed out') {
                        Start-Sleep -Seconds 2
                        continue
                    }
                    throw
                }
            }
            if (!$started) { throw "Could not start BM capture after waiting for menu: $captureLabel" }
            $diagnosticAt = (Get-Date).AddSeconds(30); $diagnosticTaken = !$Diagnostics
            # Bounded functional windows for FG modes, only on instrumented runs; throughput captures stay unpolled.
            $fgWindowAt = @(36,42,48 | ForEach-Object { (Get-Date).AddSeconds($_) }); $fgWindows = [Collections.Generic.List[object]]::new(); $fgWindowsWanted = $Diagnostics -and $mode.fg -ne 'off'
            # Only enumerate atomic result files during capture. No render-thread polling outside -Diagnostics.
            $deadline=(Get-Date).AddMinutes(8); $fresh=$null
            while (!$fresh) {
                if ((Get-Date) -gt $deadline -or !(Get-Process -Id $session.game_pid -ErrorAction SilentlyContinue)) { throw "BM capture did not finish: $captureLabel" }
                Start-Sleep -Seconds 1
                if ($fgWindowsWanted -and $fgWindows.Count -lt $fgWindowAt.Count -and (Get-Date) -ge $fgWindowAt[$fgWindows.Count]) { $fgWindows.Add((FgSnapshot (Call 'reflexium'))) }
                if (!$diagnosticTaken -and (Get-Date) -ge $diagnosticAt) {
                    Call 'reflexium' @{profile='reset'} | Out-Null
                    Start-Sleep -Seconds 5
                    WriteJson (Join-Path $out "$captureLabel-profile.json") (Call 'reflexium' @{profile='off'})
                    WriteJson (Join-Path $out "$captureLabel-latency.json") (Call 'reflexium' @{reflex_timings='1'})
                    WriteJson (Join-Path $out "$captureLabel-status.json") (Call 'reflexium')
                    $diagnosticTaken = $true
                }
                $fresh=@(Get-ChildItem $results -Filter '*.bmresult.json.gz' -Recurse -ErrorAction SilentlyContinue | Where-Object Name -NotIn $existing)
            }
            if ($fresh.Count -ne 1) { throw 'Unexpected concurrent BM writer' }
            $result=ReadCapture $fresh[0].FullName
            $afterMode=ReadReflexium
            if ($result.label -ne $captureLabel -or $result.runs.Count -ne $Passes -or $result.scene.id -ne $sceneId -or $result.flags.invalid -or $result.flags.loadTimeout -or $result.flags.renderDistanceMismatch -or @($result.runs | Where-Object { $_.settleTimeout -or $_.frames.ms.Count -eq 0 }).Count) { throw "BM capture validation failed: $captureLabel" }
            if ($mode.upscaler -ne 'off' -and ($afterMode.evaluated_frames -eq $beforeMode.evaluated_frames -or $afterMode.last_error)) { throw "Upscaler failed during $captureLabel (evaluated $($beforeMode.evaluated_frames) to $($afterMode.evaluated_frames)): $($afterMode.last_error)" }
            # Use BM's raw real-frame intervals; displayed FG FPS is not a throughput measurement.
            $frames=@($result.runs | ForEach-Object { $_.frames.ms })
            $stats=[BmQuickStats]::Calculate([double[]]$frames)
            # Failed FG captures are retained as invalid evidence, never as FG performance rows.
            $fg = if ($mode.fg -ne 'off') { FgAcceptance $mode (FgSnapshot $beforeMode) (FgSnapshot $afterMode) $fgWindows ([int]$afterMode.fg_max_multiplier) $frames.Count } else { $null }
            if ($fg -and !$fg.fg_valid) { Write-Warning "${captureLabel}: FG capture invalid: $($fg.fg_invalid_reason)" }
            $row=[ordered]@{slot=$slot;arm=$arm;direction=$mode.direction;label=$captureLabel;set=$Set;mode=$mode.id;conditioning=($mode.id -eq 'conditioning');diagnostics=[bool]$Diagnostics;jar_sha256=(Get-FileHash $jar).Hash;bm_sha256=(Get-FileHash $bm).Hash;avg_fps=$stats[0];low_1pct_fps=$stats[1];p99_ms=$stats[2];focusLost=$result.flags.focusLost;background_capture=$true;capture=$fresh[0].FullName;frames=$frames.Count;route_hash=$result.scene.routeHash;fg_presents=$afterMode.fg_generated_presents;visible_sections=@($result.runs | ForEach-Object {$_.chunks.visible})}
            $row.valid = !$fg -or $fg.fg_valid
            if ($fg) { foreach ($key in $fg.Keys) { $row[$key] = $fg[$key] } }
            $rows.Add($row)
            [IO.File]::AppendAllText((Join-Path $out 'runs.jsonl'),(($row | ConvertTo-Json -Depth 8 -Compress)+[Environment]::NewLine))
            Write-Host ("{0}: {1:N1} base FPS, {2:N1} 1% low{3}" -f $captureLabel,$stats[0],$stats[1],$(if ($fg) { ", FG $($fg.fg_ratio) generated/real (expected $($fg.fg_expected_ratio)), valid=$($fg.fg_valid)" } else { '' }))
        }
        Copy-Item (Join-Path $client 'logs\latest.log') (Join-Path $out "client-$slot.log")
        StopOwned; $owned=$false
    }
    $after=WorldHashes; WriteJson (Join-Path $out 'world-after.json') $after
    if (($hashes | ConvertTo-Json -Compress) -ne ($after | ConvertTo-Json -Compress)) { throw 'BM scene world changed' }
    # Real-frame throughput per mode. The noise band is the A/A band for one launch per arm, scaled for the number of launches averaged.
    $ids = @($selected.id)
    if ($Explore) {
        $baseRows = @($rows | Where-Object { $_.mode -eq $ids[0] -and !$_.conditioning -and $_.valid -ne $false })
        $baseFps = ($baseRows.avg_fps | Measure-Object -Average).Average; $baseLow = ($baseRows.low_1pct_fps | Measure-Object -Average).Average
        $summary = foreach ($id in $ids) {
            $r = @($rows | Where-Object { $_.mode -eq $id -and !$_.conditioning -and $_.valid -ne $false })
            $f = ($r.avg_fps | Measure-Object -Average).Average; $l = ($r.low_1pct_fps | Measure-Object -Average).Average
            $invalid=@($rows | Where-Object { $_.mode -eq $id -and $_.valid -eq $false })
            [ordered]@{mode=$id;captures=$r.Count;invalid=@($invalid | ForEach-Object { "$($_.label): $($_.fg_invalid_reason)" });fps=$f;fps_vs_first_pct=$(if ($r.Count -and $baseFps) { 100*($f/$baseFps-1) } else { $null });low1=$l;low1_vs_first_pct=$(if ($r.Count -and $baseLow) { 100*($l/$baseLow-1) } else { $null });forward_fps=(($r | Where-Object direction -eq 'f').avg_fps | Measure-Object -Average).Average;reverse_fps=(($r | Where-Object direction -eq 'r').avg_fps | Measure-Object -Average).Average}
        }
        WriteJson (Join-Path $out 'summary.json') @($summary)
        $summary | ForEach-Object { [pscustomobject]$_ } | Format-Table mode,captures,fps,fps_vs_first_pct,low1,low1_vs_first_pct,forward_fps,reverse_fps
    } else {
        $summary = foreach ($id in $ids) {
            $a=@($rows | Where-Object { $_.mode -eq $id -and $_.arm -eq 'old' -and $_.valid -ne $false }); $b=@($rows | Where-Object { $_.mode -eq $id -and $_.arm -eq 'new' -and $_.valid -ne $false })
            $af=($a.avg_fps | Measure-Object -Average).Average; $bf=($b.avg_fps | Measure-Object -Average).Average
            $al=($a.low_1pct_fps | Measure-Object -Average).Average; $bl=($b.low_1pct_fps | Measure-Object -Average).Average
            $invalid=@($rows | Where-Object { $_.mode -eq $id -and $_.valid -eq $false })
            $comparable = $a.Count -gt 0 -and $b.Count -gt 0
            [ordered]@{mode=$id;old_valid=$a.Count;new_valid=$b.Count;invalid=@($invalid | ForEach-Object { "$($_.arm) $($_.label): $($_.fg_invalid_reason)" });old_fps=$af;new_fps=$bf;fps_change_pct=$(if ($comparable) { 100*($bf/$af-1) } else { $null });old_low1=$al;new_low1=$bl;low1_change_pct=$(if ($comparable) { 100*($bl/$al-1) } else { $null });fps_noise_band_pct=$(if ($Quick) { 2.4 } else { 2.4/[Math]::Sqrt(2) });low1_noise_band_pct=$(if ($Quick) { 16.8 } else { 16.8/[Math]::Sqrt(2) })}
        }
        WriteJson (Join-Path $out 'summary.json') @($summary)
        $summary | ForEach-Object { [pscustomobject]$_ } | Format-Table mode,old_fps,new_fps,fps_change_pct,old_low1,new_low1,low1_change_pct
    }
    Write-Host "Artifacts: $out"
} finally { if ($owned) { StopOwned }; $http.Dispose() }
