$session = Get-Content -Raw "C:\Users\Ryan\source\repos\mc-agent-harness\instances\25601\session.json" | ConvertFrom-Json
$headers = @{ Authorization = "Bearer $($session.token)" }
$base = "http://127.0.0.1:25601"

function Call([string]$path, [hashtable]$data) {
    Invoke-RestMethod "$base/$path" -Method Post -Headers $headers -ContentType "application/json" -Body ($data | ConvertTo-Json -Compress) -TimeoutSec 45
}
function SetOpt([string]$key, [string]$value) {
    Call "reflexium" @{ key = $key; value = $value } | Out-Null
}

# 1080p window
Call "window" @{ width = 1920; height = 1080 } | Out-Null
Start-Sleep -Seconds 2

# Look slightly down at block in front
Call "call" @{ method = "setCamera"; args = @(-25.5, 96.0, 26.0, 0.0, 15.0) } | Out-Null
Start-Sleep -Seconds 1

$targets = @(
    @{ upscaler = "off"; quality = "quality"; path = "screenshots/iq_native_1080p.png"; label = "Native" },
    @{ upscaler = "dlss"; quality = "quality"; path = "screenshots/iq_dlss_quality_1080p.png"; label = "DLSS Quality" },
    @{ upscaler = "dlss"; quality = "ultra_performance"; path = "screenshots/iq_dlss_ultra_perf_1080p.png"; label = "DLSS Ultra Performance" }
)

$outDir = "C:\Users\Ryan\source\repos\Reflexium\run\screenshots"
if (!(Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir -Force | Out-Null }

foreach ($t in $targets) {
    SetOpt "frame_generation" "off"
    SetOpt "upscaler" $t.upscaler
    SetOpt "quality" $t.quality
    Start-Sleep -Seconds 3
    Call "wait" @{ ticks = 40 } | Out-Null
    
    $shot = Call "screenshot" @{ path = $t.path }
    Write-Host "Captured $($t.label) to $($shot.path)"
}
