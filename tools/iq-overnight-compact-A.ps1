param([string]$Label)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Drawing
$root=Split-Path $PSScriptRoot
$ev="$root/docs/validation/overnight-A-2026-10-31/$Label"
$raw="$root/run/overnight-raw/$Label"
New-Item -ItemType Directory -Force $raw|Out-Null
$manifest=@()
function CropSign($path,$s){
 if(!$path){return}
 $relative=$path.Substring($ev.Length).TrimStart('\','/')
 if(!(Test-Path -LiteralPath $path)){$path=Join-Path $raw $relative}
 if(!(Test-Path -LiteralPath $path)){throw "missing raw sign image $relative"}
 $im=[Drawing.Bitmap]::FromFile($path)
 try{
  $c=$s.camera;$eye=$s.plugins.reflexium.camera.eye_y
  if(!$eye){$eye=$c.y+1.62}
  $fov=$s.plugins.reflexium.observed.physical_camera.vertical_fov_rad
  if(!$fov){throw 'missing physical FOV'}
  $f=$im.Height/(2*[Math]::Tan($fov/2));$a=$c.yaw*[Math]::PI/180
  $xs=@();$ys=@()
  foreach($x in @(198.8,202.2)){foreach($y in @(151.9,155.1)){
   $dx=$x-$c.x;$dz=28.5-$c.z;$vz=$dz*[Math]::Cos($a)-$dx*[Math]::Sin($a)
   $vx=$dx*[Math]::Cos($a)+$dz*[Math]::Sin($a)
   $xs+=($im.Width/2-$f*$vx/$vz);$ys+=($im.Height/2-$f*($y-$eye)/$vz)
  }}
  $left=[int][Math]::Max(0,[Math]::Floor(($xs|Measure-Object -Minimum).Minimum)-24)
  $top=[int][Math]::Max(0,[Math]::Floor(($ys|Measure-Object -Minimum).Minimum)-24)
  $right=[int][Math]::Min($im.Width,[Math]::Ceiling(($xs|Measure-Object -Maximum).Maximum)+24)
  $bottom=[int][Math]::Min($im.Height,[Math]::Ceiling(($ys|Measure-Object -Maximum).Maximum)+24)
  if($right -le $left -or $bottom -le $top){throw 'empty crop'}
  $dest=Join-Path "$ev/crops" $relative
  New-Item (Split-Path $dest) -ItemType Directory -Force|Out-Null
  $r=[Drawing.Rectangle]::new($left,$top,$right-$left,$bottom-$top)
  $out=$im.Clone($r,$im.PixelFormat);try{$out.Save($dest,[Drawing.Imaging.ImageFormat]::Png)}finally{$out.Dispose()}
  [pscustomobject]@{file=$relative;sha256=(Get-FileHash $path).Hash;crop=@($left,$top,$r.Width,$r.Height);crop_sha256=(Get-FileHash $dest).Hash;camera=$c}
 }finally{$im.Dispose()}
}
foreach($meta in Get-ChildItem $ev -Filter '*.json'){
 if($meta.Name -eq 'crop-manifest.json'){continue}
 $s=Get-Content $meta.FullName|ConvertFrom-Json
 if($s.frames -is [array]){foreach($frame in $s.frames){CropSign $frame.path $frame | ForEach-Object {$manifest+=$_}}}
 elseif($s.camera){CropSign ($meta.FullName -replace '\.json$','.png') $s|ForEach-Object {$manifest+=$_}}
}
$manifest=@($manifest|Group-Object file|ForEach-Object {$_.Group[-1]})
$manifest|ConvertTo-Json -Depth 8|Set-Content "$ev/crop-manifest.json"
foreach($m in $manifest){
 if(!($m.file -is [string]) -or [string]::IsNullOrWhiteSpace($m.file) -or $m.file.Contains('..')){throw 'invalid manifest file path'}
 $from=Join-Path $ev $m.file;$to=Join-Path $raw $m.file
 if(!(Test-Path -LiteralPath $from -PathType Leaf)){continue}
 New-Item (Split-Path $to) -ItemType Directory -Force|Out-Null
 Move-Item -LiteralPath $from -Destination $to
}
Write-Output "Cropped and archived $($manifest.Count) sign images to $raw"
