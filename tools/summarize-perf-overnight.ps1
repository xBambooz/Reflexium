$root=(Split-Path -Parent $PSScriptRoot)
$dir=Join-Path $root 'docs/benchmarks/perf-overnight-2026-10-19'
$rows=@(foreach($phase in @('before','after')) {Get-Content (Join-Path $dir "$phase.jsonl")|ForEach-Object{$_|ConvertFrom-Json}})
function Span($profiles,[string]$Name,[bool]$Cpu=$false) {
    $total=0.0;$frames=0.0
    foreach($r in $profiles) {
        $s=$r.profile.spans.$Name
        $world=$r.profile.spans.world_including_upscaling
        if($Cpu) {$total += $s.cpu_us*$s.cpu_samples; $frames += $world.cpu_samples}
        else {$total += $s.gpu_us*$s.gpu_samples; $frames += $world.gpu_samples}
    }
    if($frames -eq 0){return 0.0}; return $total/$frames
}
$summary=@(foreach($phase in @('before','after')) { foreach($scene in @('shaderless','bsl')) {
    foreach($mode in @('native','cnn-quality','cnn-balanced','cnn-performance','transformer-quality','transformer-balanced','transformer-performance')) {
        $group=@($rows|Where-Object{$_.label -eq $phase -and $_.scene -eq $scene -and $_.mode -eq $mode})
        $fps=@($group|Where-Object {!$_.instrumented}|ForEach-Object{$_.stats.avg_fps})
        $profiles=@($group|Where-Object instrumented)
        $gpu=[ordered]@{};$cpu=[ordered]@{}
        foreach($name in @('world_including_upscaling','evaluate','camera_motion','object_motion','pack_hand','depth_copy','sharpen','barrier_before','barrier_after','opaque_copy','reactive','display_depth','hdr_copy','final_blit')) { $gpu[$name]=if($phase -eq 'before' -and $name -eq 'final_blit') {$null} else {Span $profiles $name} }
        foreach($name in @('reflex_sleep','reflex_driver_call','reflex_semaphore_wait','submit_including_wait','submit_fence_wait','acquire_reuse_wait','acquire','present','queue_submit','command_pool_reset')) {$cpu[$name]=Span $profiles $name $true}
        $extra=0.0
        foreach($name in @('camera_motion','object_motion','pack_hand','depth_copy','sharpen','barrier_before','barrier_after','opaque_copy','reactive','hdr_copy')) {$extra+=$gpu[$name]}
        [pscustomobject]@{phase=$phase;scene=$scene;mode=$mode;fps=$fps;fps_mean=($fps|Measure-Object -Average).Average;gpu_us=$gpu;cpu_us_per_real_frame=$cpu;extras_us=$extra;world_residual_us=$gpu.world_including_upscaling-$gpu.evaluate-$extra;hand_scopes_per_frame=($profiles|ForEach-Object{$_.profile.spans.pack_hand.cpu_samples/[math]::Max(1,$_.profile.spans.world_including_upscaling.cpu_samples)}|Measure-Object -Average).Average}
    }
}})
$summary|ConvertTo-Json -Depth 8|Set-Content (Join-Path $dir 'summary.json') -Encoding UTF8
$lines=[Collections.Generic.List[string]]::new()
$lines.Add('### Indicative uninstrumented FPS, two captures per arm')
$lines.Add('')
$lines.Add('Each cell lists forward / reverse captures. Cross-JVM differences are not accepted optimization gains. Some captures overlap other clients; per-capture PIDs are in the raw rows.')
$lines.Add('')
$lines.Add('| Scene | Mode | Before FPS | After FPS |')
$lines.Add('| --- | --- | ---: | ---: |')
foreach($b in $summary|Where-Object {$_.phase -eq 'before'}) {
    $a=$summary|Where-Object {$_.phase -eq 'after' -and $_.scene -eq $b.scene -and $_.mode -eq $b.mode}
    $lines.Add(('| {0} | {1} | {2} | {3} |' -f $b.scene,$b.mode,(($b.fps|ForEach-Object{'{0:F1}' -f $_}) -join ' / '),(($a.fps|ForEach-Object{'{0:F1}' -f $_}) -join ' / ')))
}
$lines.Add('');$lines.Add('### GPU attribution, milliseconds per sampled real frame');$lines.Add('')
$lines.Add('Paired cells are before / after. Extras include motion, object motion, pack hand, sharpen and barriers; world residual excludes those and NGX. Residual also includes unseparated native/Iris rendering operations. Final output blit lies outside world and was added only for the candidate.');$lines.Add('')
$lines.Add('| Scene | Mode | World total | NGX | Our extras | World residual |')
$lines.Add('| --- | --- | ---: | ---: | ---: | ---: |')
foreach($b in $summary|Where-Object {$_.phase -eq 'before'}) {
    $a=$summary|Where-Object {$_.phase -eq 'after' -and $_.scene -eq $b.scene -and $_.mode -eq $b.mode}
    $lines.Add(('| {0} | {1} | {2:F3} / {3:F3} | {4:F3} / {5:F3} | {6:F3} / {7:F3} | {8:F3} / {9:F3} |' -f $b.scene,$b.mode,($b.gpu_us.world_including_upscaling/1000),($a.gpu_us.world_including_upscaling/1000),($b.gpu_us.evaluate/1000),($a.gpu_us.evaluate/1000),($b.extras_us/1000),($a.extras_us/1000),($b.world_residual_us/1000),($a.world_residual_us/1000)))
}
$lines.Add('');$lines.Add('### Candidate pass details, microseconds per sampled real frame');$lines.Add('')
$lines.Add('| Scene | Mode | Camera MV | Object MV | Pack hand | Sharpen | Before+after barriers | Final blit |')
$lines.Add('| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |')
foreach($a in $summary|Where-Object {$_.phase -eq 'after'}) {
    $g=$a.gpu_us
    $lines.Add(('| {0} | {1} | {2:F1} | {3:F1} | {4:F1} | {5:F1} | {6:F1} | {7:F1} |' -f $a.scene,$a.mode,$g.camera_motion,$g.object_motion,$g.pack_hand,$g.sharpen,($g.barrier_before+$g.barrier_after),$g.final_blit))
}
$lines.Add('');$lines.Add('### CPU waits, milliseconds per real frame');$lines.Add('')
$lines.Add('Paired cells are before / after. Submit includes its fence waits; do not add them. Reflex total contains driver call and semaphore wait.');$lines.Add('')
$lines.Add('| Scene | Mode | Reflex total | Driver call | Sleep semaphore wait | Submit incl. wait | Fence waits | Acquire reuse |')
$lines.Add('| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |')
foreach($b in $summary|Where-Object {$_.phase -eq 'before'}) {
    $a=$summary|Where-Object {$_.phase -eq 'after' -and $_.scene -eq $b.scene -and $_.mode -eq $b.mode}
    $cells=@($b.scene,$b.mode)
    foreach($name in @('reflex_sleep','reflex_driver_call','reflex_semaphore_wait','submit_including_wait','submit_fence_wait','acquire_reuse_wait')) {$cells+=('{0:F3} / {1:F3}' -f ($b.cpu_us_per_real_frame[$name]/1000),($a.cpu_us_per_real_frame[$name]/1000))}
    $lines.Add('| '+($cells -join ' | ')+' |')
}
$lines|Set-Content (Join-Path $root 'build/perf-tables.md') -Encoding UTF8
$summary|Select-Object phase,scene,mode,fps_mean,extras_us,hand_scopes_per_frame|Format-Table

