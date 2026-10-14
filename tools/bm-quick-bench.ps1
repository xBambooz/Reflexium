<#
.SYNOPSIS
Benchmark Mod captures two Reflexium jars in fresh-JVM ABBA order without desktop input, on the FPS Lab standard scene.
.EXAMPLE
powershell -NoProfile -ExecutionPolicy Bypass -File tools/bm-quick-bench.ps1 -OldJar build/perf-jars/ac1e339.jar -NewJar build/libs/Reflexium-1.2.0-alpha-fabric-26.3.jar -IncludeFG -Label candidate
#>
param(
    [string]$OldJar,
    [string]$NewJar,
    [string]$Label = (Get-Date -Format 'yyyyMMdd-HHmmss'),
    [ValidateSet('A','B')][string]$Set = 'A',
    [switch]$IncludeFG,
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
if ($SelfCheck) {
    $s = [BmQuickStats]::Calculate(@(1,2,3,4))
    if ($s[0] -ne 400 -or $s[1] -ne 250 -or $s[2] -ne 4) { throw 'BM statistics check failed' }
    $fixture = @{id='check';keyframes=@(@{},@{});environment=@{renderDistanceHint=16}}
    if ((SceneInfo $fixture) -ne 16) { throw 'Scene distance check failed' }
    $fixture.keyframes = @(); $rejected = $false
    try { SceneInfo $fixture | Out-Null } catch { $rejected = $true }
    if (!$rejected) { throw 'Placeholder scene accepted' }
    Write-Host 'BM statistics and scene checks passed'; exit
}
if ($Explore -and !$NewJar) { throw 'Specify -NewJar for -Explore.' }
if (!$Explore -and (!$OldJar -or !$NewJar)) { throw 'Specify -OldJar and -NewJar.' }
if ($Label -notmatch '^[a-zA-Z0-9_-]+$') { throw 'Label must contain letters, digits, underscores or hyphens' }
$root = Split-Path -Parent $PSScriptRoot
$old = if ($OldJar) { (Resolve-Path -LiteralPath $OldJar).Path } else { $null }
$new = (Resolve-Path -LiteralPath $NewJar).Path
$bm = (Resolve-Path -LiteralPath $BenchmarkJar).Path
$out = Join-Path $root "build\bm-bench\$Label-$Set"
if (Test-Path -LiteralPath $out) { throw "Output already exists: $out. Use a new label; partial ABBA blocks cannot resume." }
$client = Join-Path $out 'client'
$data = Join-Path $out 'data'
$sceneSource = (Resolve-Path -LiteralPath $SceneSource).Path
$sceneJson = Get-Content (Join-Path $sceneSource 'scene.json') -Raw | ConvertFrom-Json
$sceneId = $sceneJson.id
$renderDistance = SceneInfo $sceneJson
if (!(Test-Path (Join-Path $sceneSource 'world'))) { throw 'Source scene has no world.' }
$scene = Join-Path $data "scenes\$sceneId"
New-Item -ItemType Directory -Force $client,$scene | Out-Null
foreach ($name in @('mods','config','vitrail','shaderpacks')) {
    if (Test-Path (Join-Path $root "run\$name")) { Copy-Item (Join-Path $root "run\$name") $client -Recurse }
}
Copy-Item (Join-Path $root 'run\options.txt') $client
# Only remove duplicate instrumentation/mod jars inside the new, verified isolated client.
foreach ($pattern in @('Reflexium-*.jar','benchmark-mod-*.jar','reflexium-bm-driver.jar')) {
    Get-ChildItem (Join-Path $client 'mods') -Filter $pattern | ForEach-Object { Remove-Item -LiteralPath $_.FullName }
}
Copy-Item $bm (Join-Path $client 'mods')
Copy-Item (Join-Path $root 'build\libs\reflexium-bm-driver.jar') (Join-Path $client 'mods')
Copy-Item (Join-Path $sceneSource '*') $scene -Recurse
function WriteJson($path,$value) { [IO.File]::WriteAllText($path,($value | ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false)) }
WriteJson (Join-Path $client 'config\benchmarkmod.json') @{dataDirectory=$data}
[IO.File]::WriteAllText((Join-Path $client 'config\reflexium.properties'), "reflex_mode=on`nupscaler=off`nframe_generation=off`nfsr_sharpness=20`n")
WriteJson (Join-Path $data 'settings.json') @{schema=1;warmupSeconds=$WarmupSeconds;measuredRuns=$Passes;conditioningRun=$true;recordMetrics=$true;gpuUsageSampling=$true;onboardingSeen=$true;overlay=@{enabled=$false}}
$pack = Join-Path $client 'vitrail\pack.txt'
if (Test-Path $pack) { [IO.File]::WriteAllText($pack,((Get-Content $pack -Raw) -replace 'enabled=(true|false)',('enabled=' + ($Set -eq 'B').ToString().ToLower()))) }
$options = Join-Path $client 'options.txt'
$optionText = Get-Content $options -Raw
foreach ($entry in @{enableVsync='false';maxFps='260';renderDistance=[string]$renderDistance;simulationDistance='12';fullscreen='false';preferredGraphicsBackend='"vulkan"'}.GetEnumerator()) { $optionText = $optionText -replace "(?m)^$($entry.Key):[^\r\n]*", "$($entry.Key):$($entry.Value)" }
[IO.File]::WriteAllText($options,$optionText)
function WorldHashes { @(Get-ChildItem (Join-Path $scene 'world') -File -Recurse | Where-Object { $_.Name -ne 'session.lock' -and $_.FullName -notmatch '\\players\\(stats|advancements)\\' } | Sort-Object FullName | ForEach-Object { @{path=$_.FullName.Substring($scene.Length);sha256=(Get-FileHash -LiteralPath $_.FullName).Hash} }) }
$hashes = WorldHashes
WriteJson (Join-Path $out 'world-before.json') $hashes
WriteJson (Join-Path $out 'protocol.json') @{scene_source=$sceneSource;scene_sha256=(Get-FileHash (Join-Path $sceneSource 'scene.json')).Hash;render_distance=$renderDistance;route_seconds=$sceneJson.routeSeconds;diagnostics=[bool]$Diagnostics;passes=$Passes;warmup_seconds=$WarmupSeconds;conditioning='Benchmark Mod per JVM'}
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
# A mode may carry runtime overrides after '@', for example tf-performance@reflex_marker=1. Only keys listed
# here are accepted; each is restored to its shipped value before the next capture that does not set it.
$knobs = @{reflex_marker='0';reflex_submission='1';perf_uniforms='1';perf_depth='1';dlss_preset='-1';reflex_mode='on'}
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
$sessionFile = Join-Path $Harness "instances\$Port\session.json"
$http = [Net.Http.HttpClient]::new(); $http.Timeout=[TimeSpan]::FromSeconds(45)
function Call([string]$endpoint,[hashtable]$params=@{}) {
    $content = [Net.Http.StringContent]::new(($params | ConvertTo-Json -Compress),[Text.Encoding]::UTF8,'application/json')
    try {
        $r=$http.PostAsync("http://127.0.0.1:$Port/$endpoint",$content).GetAwaiter().GetResult()
        try { $body=$r.Content.ReadAsStringAsync().GetAwaiter().GetResult(); if (!$r.IsSuccessStatusCode) { throw $body }; $v=$body | ConvertFrom-Json; if (!$v.ok) { throw $body }; return $v } finally { $r.Dispose() }
    } finally { $content.Dispose() }
}
function Status { $http.GetStringAsync("http://127.0.0.1:$Port/status").GetAwaiter().GetResult() | ConvertFrom-Json }
function ReadCapture($path) {
    $file=[IO.File]::OpenRead($path)
    $gzip=[IO.Compression.GZipStream]::new($file,[IO.Compression.CompressionMode]::Decompress)
    $reader=[IO.StreamReader]::new($gzip)
    try { $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose(); $gzip.Dispose(); $file.Dispose() }
}
function SetOption($key,$value) { Call 'reflexium' @{key=$key;value=[string]$value} | Out-Null }
function StopOwned {
    $s=Status
    $p=Get-CimInstance Win32_Process -Filter "ProcessId = $($session.game_pid)"
    if ($s.pid -ne $session.game_pid -or $s.game_directory -ne $client -or !$p -or !$p.CommandLine.Contains($session.jar) -or !$p.CommandLine.Contains($client)) { throw 'Shutdown ownership mismatch' }
    Call 'quit' @{exit=$true} | Out-Null
    $deadline=(Get-Date).AddSeconds(100)
    while (Get-Process -Id $session.game_pid -ErrorAction SilentlyContinue) {
        if ((Get-Date) -gt $deadline) { throw 'Owned client shutdown timed out; inspect its watchdog report' }
        Start-Sleep -Seconds 1
    }
}
$rows = [Collections.Generic.List[object]]::new()
$owned=$false
try {
    # ABBA across fresh JVMs: old forward, new forward, new reversed, old reversed. -Explore is one JVM
    # of the new jar, forward then reversed, for comparing runtime overrides cheaply.
    $launches = @(if ($Explore) { @{arm='explore';jar=$new;orders=@('f','r')} }
        else { @(@{arm='old';jar=$old;orders=@('f')},@{arm='new';jar=$new;orders=@('f')},@{arm='new';jar=$new;orders=@('r')},@{arm='old';jar=$old;orders=@('r')}) })
    $touched = [Collections.Generic.HashSet[string]]::new()
    for ($slot=0;$slot -lt $launches.Count;$slot++) {
        $arm=$launches[$slot].arm
        $jar=$launches[$slot].jar
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
        $order=@()  # Benchmark Mod conditions the first capture of a JVM itself (conditioningRun), so no extra warm-up capture.
        foreach ($direction in $launches[$slot].orders) {
            $matrix=@($selected); if ($direction -eq 'r') { [array]::Reverse($matrix) }
            foreach ($m in $matrix) { $copy=$m.Clone(); $copy.direction=$direction; $order += $copy }
        }
        $index=0
        foreach ($mode in $order) {
            $index++
            foreach ($key in @($touched)) { if (!$mode.overrides.ContainsKey($key)) { SetOption $key $knobs[$key]; [void]$touched.Remove($key) } }
            foreach ($key in $mode.overrides.Keys) { SetOption $key $mode.overrides[$key]; [void]$touched.Add($key) }
            SetOption 'frame_generation' 'off'; SetOption 'upscaler' $mode.upscaler
            SetOption 'quality' $mode.quality; SetOption 'dlss_model' $mode.model
            if ($mode.mult -gt 2) { SetOption 'fg_multiplier' $mode.mult }
            SetOption 'frame_generation' $mode.fg
            $beforeMode=Call 'reflexium'
            if ($beforeMode.upscaler -ne $mode.upscaler -or $beforeMode.quality -ne $mode.quality -or $beforeMode.dlss_model -ne $mode.model -or $beforeMode.frame_generation -ne $mode.fg) { throw "Mode switch failed: $($mode.id)" }
            if ($mode.mult -gt 2 -and $beforeMode.fg_multiplier -ne $mode.mult) { throw "FG multiplier switch failed: $($mode.id)" }
            $captureLabel="s$slot$($mode.direction)$index"
            $results=Join-Path $data 'results'
            $existing=@(Get-ChildItem $results -Filter '*.bmresult.json.gz' -Recurse -ErrorAction SilentlyContinue | ForEach-Object Name)
            Call 'bm' @{scene=$sceneId;label=$captureLabel} | Out-Null
            $diagnosticAt = (Get-Date).AddSeconds(30); $diagnosticTaken = !$Diagnostics
            # Only enumerate atomic result files during capture. No render-thread polling.
            $deadline=(Get-Date).AddMinutes(8); $fresh=$null
            while (!$fresh) {
                if ((Get-Date) -gt $deadline -or !(Get-Process -Id $session.game_pid -ErrorAction SilentlyContinue)) { throw "BM capture did not finish: $captureLabel" }
                Start-Sleep -Seconds 1
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
            $afterMode=Call 'reflexium'
            if ($result.label -ne $captureLabel -or $result.runs.Count -ne $Passes -or $result.scene.id -ne $sceneId -or $result.flags.invalid -or $result.flags.loadTimeout -or $result.flags.renderDistanceMismatch -or @($result.runs | Where-Object { $_.settleTimeout -or $_.frames.ms.Count -eq 0 }).Count) { throw "BM capture validation failed: $captureLabel" }
            if ($mode.upscaler -ne 'off' -and ($afterMode.evaluated_frames -eq $beforeMode.evaluated_frames -or $afterMode.last_error)) { throw "Upscaler failed during $captureLabel (evaluated $($beforeMode.evaluated_frames) to $($afterMode.evaluated_frames)): $($afterMode.last_error)" }
            if ($mode.fg -ne 'off' -and ($afterMode.fg_evaluated_frames -eq $beforeMode.fg_evaluated_frames -or $afterMode.fg_generated_presents -eq $beforeMode.fg_generated_presents)) { throw "FG did not evaluate and present: $captureLabel" }
            # Use BM's raw real-frame intervals; displayed FG FPS is not a throughput measurement.
            $frames=@($result.runs | ForEach-Object { $_.frames.ms })
            $stats=[BmQuickStats]::Calculate([double[]]$frames)
            $row=[ordered]@{slot=$slot;arm=$arm;direction=$mode.direction;label=$captureLabel;set=$Set;mode=$mode.id;conditioning=($mode.id -eq 'conditioning');diagnostics=[bool]$Diagnostics;jar_sha256=(Get-FileHash $jar).Hash;bm_sha256=(Get-FileHash $bm).Hash;avg_fps=$stats[0];low_1pct_fps=$stats[1];p99_ms=$stats[2];focusLost=$result.flags.focusLost;background_capture=$true;capture=$fresh[0].FullName;frames=$frames.Count;route_hash=$result.scene.routeHash;fg_presents=$afterMode.fg_generated_presents;visible_sections=@($result.runs | ForEach-Object {$_.chunks.visible})}
            $rows.Add($row)
            [IO.File]::AppendAllText((Join-Path $out 'runs.jsonl'),(($row | ConvertTo-Json -Depth 8 -Compress)+[Environment]::NewLine))
            Write-Host ("{0}: {1:N1} base FPS, {2:N1} 1% low" -f $captureLabel,$stats[0],$stats[1])
        }
        Copy-Item (Join-Path $client 'logs\latest.log') (Join-Path $out "client-$slot.log")
        StopOwned; $owned=$false
    }
    $after=WorldHashes; WriteJson (Join-Path $out 'world-after.json') $after
    if (($hashes | ConvertTo-Json -Compress) -ne ($after | ConvertTo-Json -Compress)) { throw 'BM scene world changed' }
    # Real-frame throughput per mode. The noise band is the A/A band for one launch per arm, scaled for the number of launches averaged.
    $ids = @($selected.id)
    if ($Explore) {
        $baseRows = @($rows | Where-Object { $_.mode -eq $ids[0] -and !$_.conditioning })
        $baseFps = ($baseRows.avg_fps | Measure-Object -Average).Average; $baseLow = ($baseRows.low_1pct_fps | Measure-Object -Average).Average
        $summary = foreach ($id in $ids) {
            $r = @($rows | Where-Object { $_.mode -eq $id -and !$_.conditioning })
            $f = ($r.avg_fps | Measure-Object -Average).Average; $l = ($r.low_1pct_fps | Measure-Object -Average).Average
            [ordered]@{mode=$id;captures=$r.Count;fps=$f;fps_vs_first_pct=100*($f/$baseFps-1);low1=$l;low1_vs_first_pct=100*($l/$baseLow-1);forward_fps=(($r | Where-Object direction -eq 'f').avg_fps | Measure-Object -Average).Average;reverse_fps=(($r | Where-Object direction -eq 'r').avg_fps | Measure-Object -Average).Average}
        }
        WriteJson (Join-Path $out 'summary.json') @($summary)
        $summary | ForEach-Object { [pscustomobject]$_ } | Format-Table mode,captures,fps,fps_vs_first_pct,low1,low1_vs_first_pct,forward_fps,reverse_fps
    } else {
        $summary = foreach ($id in $ids) {
            $a=@($rows | Where-Object { $_.mode -eq $id -and $_.arm -eq 'old' }); $b=@($rows | Where-Object { $_.mode -eq $id -and $_.arm -eq 'new' })
            $af=($a.avg_fps | Measure-Object -Average).Average; $bf=($b.avg_fps | Measure-Object -Average).Average
            $al=($a.low_1pct_fps | Measure-Object -Average).Average; $bl=($b.low_1pct_fps | Measure-Object -Average).Average
            [ordered]@{mode=$id;old_fps=$af;new_fps=$bf;fps_change_pct=100*($bf/$af-1);old_low1=$al;new_low1=$bl;low1_change_pct=100*($bl/$al-1);fps_noise_band_pct=2.4/[Math]::Sqrt(2);low1_noise_band_pct=16.8/[Math]::Sqrt(2)}
        }
        WriteJson (Join-Path $out 'summary.json') @($summary)
        $summary | ForEach-Object { [pscustomobject]$_ } | Format-Table mode,old_fps,new_fps,fps_change_pct,old_low1,new_low1,low1_change_pct
    }
    Write-Host "Artifacts: $out"
} finally { if ($owned) { StopOwned }; $http.Dispose() }
