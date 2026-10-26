$cmd = 'powershell.exe -NoProfile -ExecutionPolicy Bypass -File C:\Users\Ryan\source\repos\Reflexium\tools\run-overnight.ps1'
$res = Invoke-CimMethod -ClassName Win32_Process -Name Create -Arguments @{
    CommandLine = $cmd
    CurrentDirectory = 'C:\Users\Ryan\source\repos\Reflexium'
}
$res | Select-Object ProcessId, ReturnValue
