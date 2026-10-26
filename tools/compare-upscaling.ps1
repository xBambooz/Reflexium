# Matched-crop detail metrics for the upscaling validation matrix (see docs/validation/upscaling-quality-2026-10-14.md).
# Each phase folder is build\validation\<phase>\shaders-<on|off>\<config>\<scene>.png from tools\upscaling-quality.ps1.
# The native capture of the same shader state is the reference. Detail correlation is high-pass correlation with it,
# so shimmer and noise do not count as detail; Laplacian energy is reported only next to that correlation.
param(
	[string[]]$Phases = @('baseline', 'after'),
	[string[]]$Shaders = @('on', 'off'),
	[string[]]$Configs = @('dlaa', 'dlss-quality', 'dlss-performance'),
	[string]$Output = 'build\validation\metrics.json'
)
$ErrorActionPreference = 'Stop'
$java = 'C:\Program Files\GraalVM\graalvm-jdk-25.0.4+7.1\bin\java.exe'
$tool = Join-Path $PSScriptRoot 'CaptureAnalysis.java'
$root = Split-Path -Parent $PSScriptRoot
# x y w h on the 1920x1080 display image, away from the HUD and the hand
$crops = [ordered]@{
	wall      = @(200, 150, 700, 450)
	floor     = @(0, 580, 1000, 420)
	cutouts   = @(380, 230, 740, 640)
	blockents = @(150, 520, 800, 460)
	mobs      = @(560, 460, 900, 420)
}
$rows = @()
foreach ($phase in $Phases) {
	foreach ($shader in $Shaders) {
		foreach ($config in $Configs) {
			foreach ($scene in $crops.Keys) {
				$reference = Join-Path $root "build\validation\$phase\shaders-$shader\native\$scene.png"
				$test = Join-Path $root "build\validation\$phase\shaders-$shader\$config\$scene.png"
				if (!(Test-Path $reference) -or !(Test-Path $test)) { continue }
				$c = $crops[$scene]
				$json = & $java $tool metrics $reference $test $c[0] $c[1] $c[2] $c[3] | ConvertFrom-Json
				$rows += [pscustomobject]@{ phase = $phase; shaders = $shader; config = $config; scene = $scene;
					detail_correlation = $json.detail_correlation; laplacian_test = $json.laplacian_test;
					laplacian_reference = $json.laplacian_reference; mae = $json.mae; psnr = $json.psnr }
			}
		}
	}
}
New-Item -ItemType Directory -Force -Path (Split-Path (Join-Path $root $Output)) | Out-Null
$rows | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $root $Output) -Encoding utf8
$rows | Format-Table phase, shaders, config, scene, detail_correlation, laplacian_test, laplacian_reference, mae, psnr -AutoSize
