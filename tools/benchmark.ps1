param(
    [int]$Width = 1920,
    [int]$Height = 1080
)
$session = Get-Content -Raw "C:\Users\Ryan\source\repos\mc-agent-harness\instances\25601\session.json" | ConvertFrom-Json
$headers = @{ Authorization = "Bearer $($session.token)" }
$base = "http://127.0.0.1:25601"

function Call([string]$path, [hashtable]$data) {
    Invoke-RestMethod "$base/$path" -Method Post -Headers $headers -ContentType "application/json" -Body ($data | ConvertTo-Json -Compress) -TimeoutSec 45
}
function SetOpt([string]$key, [string]$value) {
    Call "reflexium" @{ key = $key; value = $value } | Out-Null
}
function Status {
    (Invoke-RestMethod "$base/status" -Headers $headers -TimeoutSec 20).plugins.reflexium
}

# Set window size
Call "window" @{ width = $Width; height = $Height } | Out-Null
Start-Sleep -Seconds 2

Write-Host "Benchmarking at ${Width}x${Height}..."
$configs = @(
    @{ upscaler = "off"; quality = "quality"; fg = "off"; mult = 2; label = "Native" },
    @{ upscaler = "dlss"; quality = "quality"; fg = "off"; mult = 2; label = "DLSS Quality" },
    @{ upscaler = "dlss"; quality = "performance"; fg = "off"; mult = 2; label = "DLSS Performance" },
    @{ upscaler = "dlss"; quality = "ultra_performance"; fg = "off"; mult = 2; label = "DLSS Ultra Performance" },
    @{ upscaler = "fsr"; quality = "quality"; fg = "off"; mult = 2; label = "FSR Quality" },
    @{ upscaler = "dlss"; quality = "quality"; fg = "dlss"; mult = 2; label = "DLSS Quality + DLSS FG (2x)" },
    @{ upscaler = "dlss"; quality = "quality"; fg = "dlss_multi"; mult = 6; label = "DLSS Quality + DLSS MFG (6x)" }
)

$results = @()
foreach ($c in $configs) {
    SetOpt "frame_generation" "off"
    SetOpt "upscaler" $c.upscaler
    SetOpt "quality" $c.quality
    if ($c.fg -ne "off") {
        if ($c.mult -gt 2) { SetOpt "fg_multiplier" "$($c.mult)" }
        SetOpt "frame_generation" $c.fg
    }
    Start-Sleep -Seconds 3
    
    # Warmup
    Call "wait" @{ ticks = 40 } | Out-Null
    
    # Measure
    $samples = @()
    for ($i = 0; $i -lt 5; $i++) {
        Start-Sleep -Milliseconds 500
        $s = Status
        $samples += $s.fps
    }
    $avgFps = [Math]::Round(($samples | Measure-Object -Average).Average)
    $avgMs = if ($avgFps -gt 0) { [Math]::Round(1000.0 / $avgFps, 2) } else { 0 }
    $stat = Status
    
    $results += [pscustomobject]@{
        Label = $c.label
        Upscaler = $c.upscaler
        Quality = $c.quality
        FG = $c.fg
        FPS = $avgFps
        FrameTimeMs = $avgMs
        Status = $stat.status_line
        FGStatus = $stat.fg_status
    }
}

$results | Format-Table Label, FPS, FrameTimeMs, Status, FGStatus -AutoSize
