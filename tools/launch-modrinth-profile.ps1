# Launches Ryan's Modrinth profile client fully detached and returns at once.
# Uses Win32_Process.Create so the game inherits no handles from the calling (agent) shell:
# Start-Process with -RedirectStandardOutput/-Error ties the shell to the game and the call never returns.
# Game output goes to the profile's logs\latest.log. Prints {"pid":..} and exits.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File tools\launch-modrinth-profile.ps1 [-Profile '26.3 Testing Instance']
param(
	[string]$Profile = '26.3 Testing Instance',
	[string]$Version = '26.3-0.19.5',
	[string]$JavaArgsFile = (Join-Path $env:TEMP 'opencode\modrinth-java.args'),
	[string]$HarnessJar
)
$ErrorActionPreference = 'Stop'
$meta = Join-Path $env:APPDATA 'ModrinthApp\meta'
$profileDir = Join-Path $env:APPDATA "ModrinthApp\profiles\$Profile"
$javaDirs = @(Get-ChildItem (Join-Path $meta 'java_versions') -Directory)
$javaRoot = $javaDirs | Where-Object { $_.Name -like 'zulu25*' } | Select-Object -First 1
if (-not $javaRoot) { $javaRoot = $javaDirs | Sort-Object Name -Descending | Select-Object -First 1 }
# javaw.exe: no console window (java.exe opens a log console on the primary monitor).
$java = Join-Path $javaRoot.FullName 'bin\javaw.exe'
if (-not (Test-Path -LiteralPath $java)) { throw "Missing javaw.exe under $($javaRoot.FullName)" }
if (-not (Test-Path -LiteralPath $JavaArgsFile)) { throw "Missing JVM/game args file: $JavaArgsFile" }

$json = Get-Content (Join-Path $meta "versions\$Version\$Version.json") -Raw | ConvertFrom-Json
$libs = foreach ($l in $json.libraries) {
	if ($l.downloads.artifact.path) { Join-Path $meta "libraries\$($l.downloads.artifact.path)" }
	elseif ($l.name) {
		$p = $l.name.Split(':')
		if ($p.Count -ge 3) { Join-Path $meta ("libraries\" + $p[0].Replace('.', '\') + "\$($p[1])\$($p[2])\$($p[1])-$($p[2]).jar") }
	}
}
$libs = @($libs) + (Join-Path $meta "versions\$Version\$Version.jar")
$cp = ($libs | Where-Object { Test-Path -LiteralPath $_ }) -join ';'

# Keep the command line short: the classpath goes in its own java @argfile.
$cpFile = Join-Path $env:TEMP 'opencode\modrinth-cp.args'
Set-Content -LiteralPath $cpFile -Value ('-cp "' + $cp.Replace('\', '/') + '"') -Encoding ASCII

# Minecraft 26.3 requires this shadow-stack reserve for native resource compilation. The cached
# Fabric version JSON omits it even though Mojang's 26.3 manifest includes it. Xss is not a substitute.
$harnessArgs = ''
if ($HarnessJar) {
	if (-not (Test-Path -LiteralPath $HarnessJar -PathType Leaf)) { throw "Missing harness jar: $HarnessJar" }
	# Keep both ownership paths visible to the harness shutdown check even when Java uses @argfiles.
	$harnessArgs = " `"-Dfabric.addMods=$HarnessJar`" `"-Duser.dir=$profileDir`""
}
$cmd = "`"$java`" -XX:StackShadowPages=32$harnessArgs `"@$cpFile`" `"@$JavaArgsFile`""
$r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = $cmd; CurrentDirectory = $profileDir }
if ($r.ReturnValue -ne 0) { throw "Win32_Process.Create failed: $($r.ReturnValue)" }
Set-Content -LiteralPath (Join-Path $env:TEMP 'opencode\modrinth-pid.txt') -Value $r.ProcessId -Encoding ASCII
"{`"pid`":$($r.ProcessId),`"log`":`"$($profileDir.Replace('\','\\'))\\logs\\latest.log`"}"
