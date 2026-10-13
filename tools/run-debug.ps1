<#
.SYNOPSIS
  Starts the dev client with the Reflexium debug server and waits until /status answers.

.DESCRIPTION
  Sets JAVA_HOME to the GraalVM 25 install, runs gradlew runClient in the background with
  -PrxDebug=<port> (build.gradle then adds -Dreflexium.debugPort=<port>), and polls
  http://127.0.0.1:<port>/status. Stop it with: .\tools\rxdbg.ps1 quit exit
#>
param(
	[int]$Port = 25599,
	[int]$TimeoutSeconds = 900
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = 'C:\Program Files\GraalVM\graalvm-jdk-25.0.4+7.1'
$env:RX_DEBUG_PORT = "$Port"
$statusUrl = "http://127.0.0.1:$Port/status"

function Get-RxStatus {
	try { return Invoke-RestMethod -Uri $statusUrl -TimeoutSec 3 } catch { return $null }
}

$existing = Get-RxStatus
if ($existing) {
	Write-Host "Debug server already answering on port $Port."
	$existing | ConvertTo-Json -Depth 6
	exit 0
}

$logDir = Join-Path $repo 'run\logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$out = Join-Path $logDir 'rxdebug-gradle.out.log'
$err = Join-Path $logDir 'rxdebug-gradle.err.log'

$process = Start-Process -FilePath (Join-Path $repo 'gradlew.bat') `
	-ArgumentList @('runClient', '--console=plain', "-PrxDebug=$Port") `
	-WorkingDirectory $repo -PassThru -WindowStyle Hidden `
	-RedirectStandardOutput $out -RedirectStandardError $err
Write-Host "Started gradlew runClient (pid $($process.Id)); logs: $out"

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while ((Get-Date) -lt $deadline) {
	$status = Get-RxStatus
	if ($status) {
		Write-Host "Debug server is up on port $Port."
		$status | ConvertTo-Json -Depth 6
		exit 0
	}
	if ($process.HasExited) {
		Write-Host "gradlew exited with code $($process.ExitCode) before the debug server answered. Last output:"
		Get-Content $out -Tail 40
		Get-Content $err -Tail 20
		exit 1
	}
	Start-Sleep -Seconds 2
}
Write-Host "Timed out after $TimeoutSeconds s waiting for $statusUrl."
exit 1
