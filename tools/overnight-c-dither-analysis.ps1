$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
$evidence="$root/docs/validation/overnight-c-2026-10-31/dither"
foreach($arm in Get-ChildItem $evidence -Directory) {
    if (!(Test-Path "$($arm.FullName)/generation.json")) {continue}
    $lock='C:/Users/Ryan/source/repos/Reflexium-gpu.lock'
    if ((Test-Path $lock) -and (Get-Content $lock -Raw|ConvertFrom-Json).purpose -eq 'timed') {throw 'Defer analysis during timed lock'}
    $raw="$root/run/overnight-c/dither/$($arm.Name)/wgc-static"
    $frames=Import-Csv "$raw/frames.csv" | Sort-Object {[int]$_.index}
    $tsv="$($arm.FullName)/wgc-static.tsv"
    $frames | ForEach-Object {"$($_.png)`t150"} | Set-Content $tsv
    $metrics=& java -cp "$root/run/overnight-c" CaptureAnalysis registered-stability 77 150 12 1 1 125 125 $tsv
    if ($LASTEXITCODE) {throw 'WGC analysis failed'}
    $residual=$metrics|ConvertFrom-Json
    $gaps=for($i=1;$i -lt $frames.Count;$i++){[double]$frames[$i].system_relative_ms-[double]$frames[$i-1].system_relative_ms}
    @{callbacks=$frames.Count;distinct_hashes=($frames.hash|Sort-Object -Unique).Count;
      max_callback_gap_ms=($gaps|Measure-Object -Maximum).Maximum;
      mean_luma_change=$residual.registered_mean_luma_change;
      every_application_present_verified=$false} | ConvertTo-Json | Set-Content "$($arm.FullName)/wgc-metrics.json"
    Copy-Item -LiteralPath "$raw/frames.csv" -Destination "$($arm.FullName)/wgc-frames.csv"
    Copy-Item -LiteralPath $frames[0].png -Destination "$($arm.FullName)/wgc-first.png"
    Copy-Item -LiteralPath $frames[-1].png -Destination "$($arm.FullName)/wgc-last.png"
    $sequenceMetrics=foreach($kind in @('static','pan','cut','sunlight')) {
        $sequence=Get-Content "$($arm.FullName)/$kind.json" -Raw|ConvertFrom-Json
        $sequence | ForEach-Object {"$($_.path)`t$($_.camera.yaw)"} | Set-Content "$($arm.FullName)/$kind.tsv"
        $yaw=$sequence[0].camera.yaw
        $value=& java -cp "$root/run/overnight-c" CaptureAnalysis registered-stability 77 $yaw 12 940 500 128 128 "$($arm.FullName)/$kind.tsv"
        if ($LASTEXITCODE) {throw "Sequence analysis failed: $kind"}
        @{sequence=$kind;metrics=($value|ConvertFrom-Json)}
    }
    $sequenceMetrics | ConvertTo-Json -Depth 4 | Set-Content "$($arm.FullName)/sequence-metrics.json"
}
