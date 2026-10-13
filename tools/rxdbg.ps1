<#
.SYNOPSIS
  Command line for the Reflexium dev debug server (docs/DEBUGGING.md).

.DESCRIPTION
  Wraps the loopback HTTP endpoints that start only when the client runs with
  -Dreflexium.debugPort=<port> (use tools\run-debug.ps1). Prints the JSON reply.
  Exit code 0 when the server answered ok, 1 otherwise.

.EXAMPLE
  .\tools\rxdbg.ps1 status
  .\tools\rxdbg.ps1 set upscaler fsr
  .\tools\rxdbg.ps1 set jitter_sign_x 1
  .\tools\rxdbg.ps1 screenshot out.png
  .\tools\rxdbg.ps1 move 0 5 40
  .\tools\rxdbg.ps1 turn 90 40
  .\tools\rxdbg.ps1 sequence 10 100 out\seq turn 90 40
  .\tools\rxdbg.ps1 join "Reflexium Vitrail Smoke"
#>
param(
	[Parameter(Position = 0)][string]$Command = 'help',
	[Parameter(Position = 1, ValueFromRemainingArguments = $true)][string[]]$Rest = @()
)

$ErrorActionPreference = 'Stop'
$port = if ($env:RXDBG_PORT) { $env:RXDBG_PORT } else { '25599' }
$base = "http://127.0.0.1:$port"

function Resolve-Full([string]$path) {
	$ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($path)
}

function Invoke-Rx([string]$endpoint, [hashtable]$query = @{}) {
	$pairs = foreach ($key in $query.Keys) {
		if ($null -ne $query[$key] -and "$($query[$key])" -ne '') {
			'{0}={1}' -f $key, [uri]::EscapeDataString("$($query[$key])")
		}
	}
	$url = "$base$endpoint"
	if ($pairs) { $url += '?' + ($pairs -join '&') }
	try {
		return Invoke-RestMethod -Uri $url -Method Get -TimeoutSec 900
	} catch {
		$body = $null
		if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
			$body = $_.ErrorDetails.Message
		} elseif ($_.Exception.Response) {
			try {
				$reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
				$body = $reader.ReadToEnd()
			} catch { }
		}
		if ($body) {
			try { return ($body | ConvertFrom-Json) } catch { }
		}
		return [pscustomobject]@{ ok = $false; error = "request to $url failed: $($_.Exception.Message)" }
	}
}

function Show-Result($result) {
	$result | ConvertTo-Json -Depth 10
	if ($result.ok -ne $true) { exit 1 }
}

function Arg([int]$index, $default = $null) {
	if ($Rest.Count -gt $index) { return $Rest[$index] }
	return $default
}

switch ($Command.ToLowerInvariant()) {
	'status' { Show-Result (Invoke-Rx '/status') }
	'set' {
		if ($Rest.Count -lt 2) { throw 'usage: set <key> <value>' }
		Show-Result (Invoke-Rx '/set' @{ key = $Rest[0]; value = $Rest[1] })
	}
	'screenshot' {
		$path = Arg 0
		$gui = if ((Arg 1) -eq 'gui') { '1' } else { '0' }
		$full = if ($path) { Resolve-Full $path } else { $null }
		Show-Result (Invoke-Rx '/screenshot' @{ path = $full; gui = $gui })
	}
	'camera' {
		Show-Result (Invoke-Rx '/camera' @{ x = Arg 0; y = Arg 1; z = Arg 2; yaw = Arg 3; pitch = Arg 4 })
	}
	'move' {
		Show-Result (Invoke-Rx '/move' @{ dx = Arg 0 '0'; dz = Arg 1 '0'; ticks = Arg 2 '20' })
	}
	'turn' {
		Show-Result (Invoke-Rx '/turn' @{ dyaw = Arg 0 '0'; ticks = Arg 1 '20'; dpitch = Arg 2 '0' })
	}
	'sequence' {
		# sequence <count> <interval_ms> <dir> [turn <dyaw> <ticks> | move <dx> <dz> <ticks>]
		$query = @{ count = Arg 0 '8'; interval_ms = Arg 1 '100' }
		$dir = Arg 2
		if ($dir) { $query.dir = Resolve-Full $dir }
		switch ((Arg 3 '')) {
			'turn' { $query.dyaw = Arg 4 '0'; $query.ticks = Arg 5 '20' }
			'move' { $query.dx = Arg 4 '0'; $query.dz = Arg 5 '0'; $query.ticks = Arg 6 '20' }
			'' { }
			default { throw "sequence motion must be 'turn' or 'move', got '$(Arg 3)'" }
		}
		Show-Result (Invoke-Rx '/capture_sequence' $query)
	}
	'join' {
		if ($Rest.Count -lt 1) { throw 'usage: join <world folder name>' }
		Show-Result (Invoke-Rx '/join' @{ world = ($Rest -join ' ') })
	}
	'quit' {
		Show-Result (Invoke-Rx '/quit' @{ exit = $(if ((Arg 0) -eq 'exit') { '1' } else { '0' }) })
	}
	'raw' {
		# raw /endpoint key=value key=value
		if ($Rest.Count -lt 1) { throw 'usage: raw /endpoint key=value ...' }
		$query = @{}
		foreach ($pair in $Rest[1..($Rest.Count - 1)]) {
			if (-not $pair) { continue }
			$k, $v = $pair -split '=', 2
			$query[$k] = $v
		}
		Show-Result (Invoke-Rx $Rest[0] $query)
	}
	default {
		@'
rxdbg.ps1 <command> [args]
  status
  set <upscaler|quality|dlss_model|fsr_sharpness|jitter_sign_x|jitter_sign_y|jitter_sign|mv_debug|jitter> <value>
  screenshot [path.png] [gui]
  camera [x y z yaw pitch]
  move <dx> <dz> [ticks]          total blocks on world X/Z over ticks (default 20)
  turn <dyaw> [ticks] [dpitch]    total degrees over ticks
  sequence <count> <interval_ms> <dir> [turn <dyaw> <ticks> | move <dx> <dz> <ticks>]
  join <world folder name>
  quit [exit]
  raw </endpoint> key=value ...
Port: $env:RXDBG_PORT (default 25599).
'@
	}
}
