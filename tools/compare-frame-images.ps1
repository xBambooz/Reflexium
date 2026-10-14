param([string]$Directory = (Join-Path (Split-Path -Parent $PSScriptRoot) 'run\perf-images'))
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @'
using System;
using System.Drawing;
public static class FrameImageMetrics {
    public static double[] Compare(string before, string after) {
        using (var a = new Bitmap(before)) using (var b = new Bitmap(after)) {
            if (a.Size != b.Size) throw new ArgumentException("Image sizes differ");
            double la=0, lb=0, la2=0, lb2=0, square=0, absolute=0; long n=0;
            for (int y=2; y<a.Height-140; y+=2) for (int x=2; x<a.Width-2; x+=2) {
                // Exclude the animated hand. Compare the scene, with the same fixed crop in both arms.
                if (x>a.Width*0.65 && y>a.Height*0.55) continue;
                double av=Luma(a,x,y), bv=Luma(b,x,y);
                double aa=4*av-Luma(a,x-1,y)-Luma(a,x+1,y)-Luma(a,x,y-1)-Luma(a,x,y+1);
                double bb=4*bv-Luma(b,x-1,y)-Luma(b,x+1,y)-Luma(b,x,y-1)-Luma(b,x,y+1);
                la+=aa; lb+=bb; la2+=aa*aa; lb2+=bb*bb;
                square+=(av-bv)*(av-bv); absolute+=Math.Abs(av-bv); n++;
            }
            return new [] {la2/n-Math.Pow(la/n,2),lb2/n-Math.Pow(lb/n,2),absolute/n,
                square==0 ? 999 : 10*Math.Log10(255*255/(square/n))};
        }
    }
    private static double Luma(Bitmap b,int x,int y) { var p=b.GetPixel(x,y); return 0.2126*p.R+0.7152*p.G+0.0722*p.B; }
}
'@
$rows = foreach($before in Get-ChildItem $Directory -Filter 'before-*.png') {
    $after=Join-Path $Directory ($before.Name.Replace('before-','after-'))
    if (!(Test-Path -LiteralPath $after)) { continue }
    $m=[FrameImageMetrics]::Compare($before.FullName,$after)
    [pscustomobject]@{mode=$before.BaseName.Substring(7);laplacian_before=$m[0];laplacian_after=$m[1];mean_luma_difference=$m[2];psnr_db=$m[3]}
}
$rows | ConvertTo-Json | Set-Content (Join-Path $Directory 'metrics.json')
$rows | Format-Table -AutoSize
