param([Parameter(Mandatory)][string]$Reference,[Parameter(Mandatory)][string]$Candidate)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Drawing
$a=[System.Drawing.Bitmap]::new($Reference)
$b=[System.Drawing.Bitmap]::new($Candidate)
try {
 $sumA=0.0; $sumB=0.0; $squareA=0.0; $squareB=0.0; $cross=0.0; $n=0
 for($y=0;$y -lt 90;$y++) {for($x=0;$x -lt 160;$x++) {
  $pa=$a.GetPixel([int][math]::Floor(($x+0.5)*$a.Width/160),[int][math]::Floor(($y+0.5)*$a.Height/90))
  $pb=$b.GetPixel([int][math]::Floor(($x+0.5)*$b.Width/160),[int][math]::Floor(($y+0.5)*$b.Height/90))
  $va=.2126*$pa.R+.7152*$pa.G+.0722*$pa.B; $vb=.2126*$pb.R+.7152*$pb.G+.0722*$pb.B
  $sumA+=$va; $sumB+=$vb; $squareA+=$va*$va; $squareB+=$vb*$vb; $cross+=$va*$vb; $n++
 }}
 $den=[math]::Sqrt(($squareA-$sumA*$sumA/$n)*($squareB-$sumB*$sumB/$n))
 $corr=if($den -gt 0){($cross-$sumA*$sumB/$n)/$den}else{0}
 [pscustomobject]@{reference=$Reference;candidate=$Candidate;normalized_correlation=$corr;floor=0.6;valid=($corr -ge 0.6)}|ConvertTo-Json
} finally {$a.Dispose();$b.Dispose()}
