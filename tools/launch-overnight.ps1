$root = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot 'run-overnight.ps1'

$cmd = "powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$scriptPath`""
$res = Invoke-CimMethod -ClassName Win32_Process -Name Create -Arguments @{CommandLine = $cmd; CurrentDirectory = $root}
if ($res.ReturnValue -ne 0) {
    throw "Failed to create runner process via WMI, return code: $($res.ReturnValue)"
}
Write-Host "Runner started via WMI with PID: $($res.ProcessId)"
