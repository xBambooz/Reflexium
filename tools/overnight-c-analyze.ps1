$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
Set-Location $root
$raw="$root/run/overnight-c/mip"
$out="$root/docs/validation/overnight-c-2026-10-31/mip"
function Untimed {
    $lock='C:/Users/Ryan/source/repos/Reflexium-gpu.lock'
    if ((Test-Path $lock) -and (Get-Content $lock -Raw | ConvertFrom-Json).purpose -eq 'timed') { throw 'Timed GPU lock: defer analysis' }
}
Untimed
& javac -d "$root/run/overnight-c" tools/CaptureAnalysis.java
if ($LASTEXITCODE) {throw 'Analyzer compilation failed'}
function Analyze($arguments) {
    Untimed
    $result=& java -cp "$root/run/overnight-c" CaptureAnalysis @arguments
    if ($LASTEXITCODE) {throw "Analyzer failed: $arguments"}
    $result | ConvertFrom-Json
}
Untimed
& java -cp "$root/run/overnight-c" CaptureAnalysis self-check
if ($LASTEXITCODE) {throw 'Analyzer self-check failed'}
$rows=foreach($f in Get-ChildItem $out -Filter '*-before.json') {
    $name=$f.BaseName.Replace('-before','')
    if ($name -notmatch '^p([01])-(quality|ultra_performance)-a([0-3])-b([01])-pass([1-3])$') {continue}
    $pack=[int]$Matches[1]; $quality=$Matches[2]; $bias=[int]$Matches[4]; $pass=[int]$Matches[5]
    $inputCapture=(Get-Content "$out/$name-input.json" -Raw | ConvertFrom-Json).capture
    if ($inputCapture.pack_drawing -ne [bool]$pack) {continue}
    $before=Get-Content $f.FullName -Raw | ConvertFrom-Json
    $after=Get-Content "$out/$name-after.json" -Raw | ConvertFrom-Json
    $fov=$before.plugins.reflexium.observed.physical_camera.vertical_fov_rad * 180 / [math]::PI
    # Same bare stone-gully ROI used by the quick mip pair, excluding foliage and clouds.
    $roi=@('1435','1110','35','60')
    $detail=Analyze (@('metrics',"$raw/p$pack-native-pass$pass.png","$raw/$name.png")+$roi)
    $static=Analyze (@('registered-stability',"$fov",'90','10')+$roi+@("$out/$name-static.tsv"))
    $pan=Analyze (@('registered-stability',"$fov",'90','10')+$roi+@("$out/$name-pan.tsv"))
    [pscustomobject]@{arm=$name;pack=$pack;quality=$quality;offset=$bias;pass=$pass;
        correlation=$detail.detail_correlation;sharpness=$detail.laplacian_test;native_sharpness=$detail.laplacian_reference;
        mse=$detail.mse;static=$static.registered_mean_luma_change;pan=$pan.registered_mean_luma_change;
        applied_bias=$before.plugins.reflexium.observed.material_binds.u_BlockTex.applied_bias;
        evaluations=$after.plugins.reflexium.evaluated_frames-$before.plugins.reflexium.evaluated_frames}
}
$rows | ConvertTo-Json -Depth 5 | Set-Content "$out/registered-metrics.json"
$rows | Export-Csv "$out/registered-metrics.csv" -NoTypeInformation
$rows | Format-Table -AutoSize
