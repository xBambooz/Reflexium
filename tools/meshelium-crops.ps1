param([string]$Directory = "$PSScriptRoot/../run/evidence")
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$area = [Drawing.Rectangle]::new(80, 650, 640, 480)
$pairs = @(
    @('mesh-up', 'no-mesh-up'),
    @('mesh-quality', 'no-mesh-quality'),
    @('pack-up-frozen', 'no-mesh-pack-up'),
    @('pack-quality', 'no-mesh-pack-quality'),
    @('mesh-up-frozen', 'no-mesh-up')
)
$results = foreach ($pair in $pairs) {
    $images = @()
    try {
        foreach ($name in $pair) {
            $image = [Drawing.Bitmap]::new((Join-Path $Directory "$name.png"))
            $images += $image
            $crop = $image.Clone($area, $image.PixelFormat)
            $crop.Save((Join-Path $Directory "$name-crop.png"), [Drawing.Imaging.ImageFormat]::Png)
            $crop.Dispose()
        }
        $sum = 0.0
        for ($y=$area.Y; $y -lt $area.Bottom; $y+=2) {
            for ($x=$area.X; $x -lt $area.Right; $x+=2) {
                $a=$images[0].GetPixel($x,$y); $b=$images[1].GetPixel($x,$y)
                $sum += [Math]::Abs([int]$a.R-$b.R)+[Math]::Abs([int]$a.G-$b.G)+[Math]::Abs([int]$a.B-$b.B)
            }
        }
        [pscustomobject]@{a=$pair[0]; b=$pair[1]; roi=@($area.X,$area.Y,$area.Width,$area.Height); rgb_mae_0_255=$sum/(320*240*3)}
    } finally { foreach ($image in $images) { $image.Dispose() } }
}
$results | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $Directory 'crop-metrics.json')
$results
