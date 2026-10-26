# Content floors for one or more crops (round 17 thresholds): mean luma above 8, variance above 80
# and more than 150 horizontal or vertical neighbour pairs with luma difference above 12.
# Prints one object per file. Usage: content-check.ps1 -Files <png>[,<png>...]
param([Parameter(Mandatory)][string[]]$Files)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type -ReferencedAssemblies System.Drawing @'
using System.Drawing;
public static class DlssCostContent {
	public static double[] Measure(string file) {
		using (var image = new Bitmap(file)) {
			int w = image.Width, h = image.Height;
			var luma = new double[w * h];
			for (int y = 0; y < h; y++)
				for (int x = 0; x < w; x++) {
					Color p = image.GetPixel(x, y);
					luma[y * w + x] = 0.2126 * p.R + 0.7152 * p.G + 0.0722 * p.B;
				}
			double sum = 0, square = 0; int edges = 0;
			for (int i = 0; i < luma.Length; i++) {
				sum += luma[i]; square += luma[i] * luma[i];
				if (i % w > 0 && System.Math.Abs(luma[i] - luma[i - 1]) > 12) edges++;
				if (i >= w && System.Math.Abs(luma[i] - luma[i - w]) > 12) edges++;
			}
			double mean = sum / luma.Length;
			return new double[] { w, h, mean, square / luma.Length - mean * mean, edges };
		}
	}
}
'@
foreach ($file in $Files) {
	$m = [DlssCostContent]::Measure((Resolve-Path -LiteralPath $file).Path)
	$mean = [math]::Round($m[2], 3); $variance = [math]::Round($m[3], 3); $edges = [int]$m[4]
	[pscustomobject]@{
		file = [System.IO.Path]::GetFileName($file)
		width = [int]$m[0]; height = [int]$m[1]
		mean_luma = $mean; variance = $variance; edges = $edges
		content_valid = ($mean -gt 8 -and $variance -gt 80 -and $edges -gt 150)
	}
}
