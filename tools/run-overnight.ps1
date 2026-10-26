param(
    [string]$OldJar,
    [string]$NewJar,
    [string]$Label = 'overnight-08381e6'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (!$OldJar) { $OldJar = Join-Path $root 'build\perf-jars\ac1e339.jar' }
if (!$NewJar) { $NewJar = Join-Path $root 'build\perf-jars\08381e6.jar' }

$benchDir = Join-Path $root 'build\bm-bench'
if (!(Test-Path $benchDir)) { New-Item -ItemType Directory -Force $benchDir | Out-Null }
$transcriptFile = Join-Path $benchDir 'overnight-runner.log'
$doneFile = Join-Path $benchDir 'overnight-runner-done.json'
$errFile = Join-Path $benchDir 'overnight-runner-error.json'
if (Test-Path $doneFile) { Remove-Item -Force $doneFile }
if (Test-Path $errFile) { Remove-Item -Force $errFile }

Start-Transcript -Path $transcriptFile -Append -Force

try {
    Write-Host "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] Step 1: Running Set A (no shaders)..."
    & (Join-Path $PSScriptRoot 'bm-quick-bench.ps1') -OldJar $OldJar -NewJar $NewJar -IncludeFG -Set A -Label $Label

    Write-Host "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] Step 2: Running Set B (Vitrail shaders)..."
    & (Join-Path $PSScriptRoot 'bm-quick-bench.ps1') -OldJar $OldJar -NewJar $NewJar -IncludeFG -Set B -Label $Label

    Write-Host "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] Step 3: Exporting upscaling site..."
    & (Join-Path $PSScriptRoot 'export-upscaling-site.ps1')

    $doneData = [ordered]@{
        status = 'success'
        completed_at = (Get-Date -Format 'o')
        label = $Label
        old_jar = $OldJar
        new_jar = $NewJar
        set_a_summary = (Join-Path $benchDir "$Label-A\summary.json")
        set_b_summary = (Join-Path $benchDir "$Label-B\summary.json")
    }
    [IO.File]::WriteAllText($doneFile, ($doneData | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
    Write-Host "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] All steps completed successfully."
}
catch {
    $errData = [ordered]@{
        status = 'failed'
        failed_at = (Get-Date -Format 'o')
        error = $_.Exception.ToString()
    }
    [IO.File]::WriteAllText($errFile, ($errData | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
    Write-Error $_
}
finally {
    Stop-Transcript
}
