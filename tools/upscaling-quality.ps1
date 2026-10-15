# Driver for the upscaling quality validation (docs/validation/upscaling-quality-2026-10-14.md).
# Dot-source it, then call the functions. It talks to the authenticated mc-agent-harness session of an
# instance this repository launched; it never prints the session token.
#
#   . .\tools\upscaling-quality.ps1
#   Connect-Session 25631
#   Set-Rx upscaler dlss; Set-Rx quality quality
#   Move-Camera -25.5 69 23.5 0 0
#   Save-Shot out\a.png
param()
$ErrorActionPreference = 'Stop'

$script:Base = $null
$script:Headers = $null
$script:Session = $null

function Connect-Session([int]$Port = 25631) {
	$path = "C:\Users\Ryan\source\repos\mc-agent-harness\instances\$Port\session.json"
	$s = Get-Content -Raw -LiteralPath $path | ConvertFrom-Json
	$script:Session = $s
	$script:Base = "http://127.0.0.1:$($s.port)"
	$script:Headers = @{ Authorization = "Bearer $($s.token)" }
	$status = Invoke-RestMethod "$script:Base/status" -Headers $script:Headers -TimeoutSec 20
	$proc = Get-CimInstance Win32_Process -Filter "ProcessId=$($s.game_pid)"
	if ($status.pid -ne $s.game_pid -or $status.game_directory -ne $s.game_directory -or !$proc `
			-or !$proc.CommandLine.Contains($s.jar) -or !$proc.CommandLine.Contains($s.game_directory)) {
		throw 'Session does not identify the owned injected client.'
	}
	"connected pid=$($s.game_pid) dir=$($s.game_directory)"
}

function Invoke-Harness([string]$Endpoint, [hashtable]$Data = @{}, [int]$TimeoutSec = 90) {
	$r = Invoke-RestMethod "$script:Base/$Endpoint" -Method Post -Headers $script:Headers -ContentType application/json `
		-Body ($Data | ConvertTo-Json -Compress) -TimeoutSec $TimeoutSec
	if (!$r.ok) { throw "Harness $Endpoint failed: $($r | ConvertTo-Json -Compress -Depth 4)" }
	$r
}

function Get-Rx { (Invoke-RestMethod "$script:Base/status" -Headers $script:Headers -TimeoutSec 20) }

function Get-RxPlugin { (Get-Rx).plugins.reflexium }

function Set-Rx([string]$Key, [string]$Value) {
	Invoke-Harness 'reflexium' @{ key = $Key; value = $Value } | Out-Null
}

function Move-Camera([double]$X, [double]$Y, [double]$Z, [double]$Yaw = 0, [double]$Pitch = 0) {
	Invoke-Harness 'camera' @{ x = $X; y = $Y; z = $Z; yaw = $Yaw; pitch = $Pitch } | Out-Null
}

function Wait-Ticks([int]$Ticks = 20) { Invoke-Harness 'wait' @{ ticks = $Ticks } | Out-Null }

function Save-Shot([string]$Path) {
	$full = [IO.Path]::GetFullPath([IO.Path]::Combine($PWD.Path, $Path))
	New-Item -ItemType Directory -Force -Path (Split-Path $full) | Out-Null
	Invoke-Harness 'screenshot' @{ path = $full } | Out-Null
	$full
}

# Joins the shared smoke world and waits for chunks.
function Join-World([string]$World = 'Reflexium Vitrail Smoke') {
	Invoke-Harness 'join' @{ world = $World; timeout_s = 300 } 330
}

function Send-Command([string]$Command) {
	Invoke-Harness 'command' @{ command = $Command } | Out-Null
}

# Builds the fixed validation arena high above the smoke world's terrain (idempotent).
function Build-Arena {
	$cmds = @(
		'gamemode creative @a',
		'gamerule send_command_feedback false',
		'gamerule spawn_mobs false',
		'gamerule advance_weather false',
		'gamerule advance_time false',
		'weather clear',
		'time set 6000',
		# floor: four material bands for grazing-angle texture checks
		'fill -62 129 -2 -18 129 42 stone',
		'fill -62 130 -2 -18 130 8 stone',
		'fill -62 130 9 -18 130 18 grass_block',
		'fill -62 130 19 -18 130 28 cobblestone',
		'fill -62 130 29 -18 130 42 oak_planks',
		# vertical texture wall at z=5 (2 wide columns)
		'fill -58 131 5 -57 138 5 stone_bricks',
		'fill -56 131 5 -55 138 5 bricks',
		'fill -54 131 5 -53 138 5 mossy_cobblestone',
		'fill -52 131 5 -51 138 5 spruce_planks',
		'fill -50 131 5 -49 138 5 hay_block',
		'fill -48 131 5 -47 138 5 iron_block',
		'fill -46 131 5 -45 138 5 netherrack',
		'fill -44 131 5 -43 138 5 dirt',
		'fill -42 131 5 -41 138 5 sandstone',
		'fill -40 131 5 -39 138 5 deepslate_bricks',
		'fill -38 131 5 -37 138 5 purpur_block',
		# thin cutouts and foliage row at z=22 (cobblestone band)
		'fill -60 131 22 -20 134 22 air',
		'fill -60 131 22 -56 133 22 iron_bars',
		'fill -55 131 22 -51 133 22 glass_pane',
		'fill -50 131 22 -46 132 22 oak_fence',
		'fill -45 131 22 -41 135 24 oak_leaves[persistent=true]',
		'fill -40 131 22 -37 133 22 ladder[facing=south]',
		'fill -36 131 22 -32 131 22 short_grass',
		'fill -31 131 22 -27 131 22 fern',
		'fill -26 131 22 -22 134 22 jungle_leaves[persistent=true]',
		# animated block entities and a water pool at z=33..38
		'setblock -56 131 34 enchanting_table',
		'setblock -52 131 34 conduit',
		'setblock -48 131 34 campfire[lit=true]',
		'setblock -44 131 34 soul_campfire[lit=true]',
		'fill -40 130 33 -36 130 37 water',
		'fill -40 131 33 -40 131 37 stone_bricks',
		'setblock -30 131 34 chest[facing=south]',
		'setblock -26 131 34 lantern',
		'fill -24 131 33 -22 131 35 lava'
	)
	foreach ($c in $cmds) { Send-Command $c }
	Start-Sleep -Milliseconds 300
}

# Kills and respawns the static validation mobs (NoAI so image comparisons stay deterministic).
function Reset-ArenaEntities {
	Send-Command 'kill @e[type=!player]'
	Send-Command 'summon pig -34 131 12 {NoAI:1b,Rotation:[150f,0f]}'
	Send-Command 'summon villager -31 131 12 {NoAI:1b,Rotation:[180f,0f]}'
	Send-Command 'summon armor_stand -28 131 12 {Rotation:[200f,0f],ShowArms:1b}'
	Send-Command 'summon item_frame -28 133 5 {Facing:3b,Item:{id:"minecraft:filled_map",count:1}}'
}

# Named camera poses (x y z yaw pitch). Yaw 180 faces -Z.
$script:Scenes = [ordered]@{
	'wall'     = @(-47.5, 133, 14, 180, 5)       # texture wall, frontal
	'floor'    = @(-40.5, 132.6, 41, 168, 20)    # four material bands receding at a diagonal
	'cutouts'  = @(-41, 133, 30, 180, 8)         # bars, panes, fences, leaves, ladder, foliage
	'blockents'= @(-46, 133, 42, 180, 25)        # enchanting table, conduit, campfires, water, chest, lava
	'mobs'     = @(-31, 132.4, 19, 180, 6)       # pig, villager, armor stand, map frame
	'hand'     = @(-47.5, 133, 14, 180, 5)       # same as wall: hand visible lower right
}

$script:Configs = [ordered]@{
	'native'      = @{ upscaler = 'off';  quality = 'quality' }
	'dlaa'        = @{ upscaler = 'dlss'; quality = 'native_aa' }
	'dlss-quality'     = @{ upscaler = 'dlss'; quality = 'quality' }
	'dlss-performance' = @{ upscaler = 'dlss'; quality = 'performance' }
}

# Captures one scene still plus status. Returns the status snapshot used in index.json.
function Capture-Still([string]$Dir, [string]$Scene, [int]$Settle = 60) {
	$c = $script:Scenes[$Scene]
	Move-Camera $c[0] $c[1] $c[2] $c[3] $c[4]
	Start-Sleep -Milliseconds 500
	Wait-Ticks $Settle
	$status = Get-RxPlugin
	$file = Save-Shot (Join-Path $Dir "$Scene.png")
	[pscustomobject]@{ scene = $Scene; file = $file; fps = $status.fps; evaluated = $status.evaluated_frames; mip_bias = $status.mip_bias;
		jitter_index = $status.jitter_index; resets = $status.history_reset_frames; render = "$($status.render_size.w)x$($status.render_size.h)";
		last_error = $status.last_error; status_line = $status.status_line }
}

function Capture-Motion([string]$Dir, [string]$Name, [string]$Scene, [hashtable]$Motion, [int]$Count = 8, [int]$IntervalMs = 90) {
	$c = $script:Scenes[$Scene]
	Move-Camera $c[0] $c[1] $c[2] $c[3] $c[4]
	Start-Sleep -Milliseconds 500
	Wait-Ticks 40
	$data = @{ dir = (Join-Path $Dir $Name); count = $Count; interval_ms = $IntervalMs } + $Motion
	$r = Invoke-Harness 'capture_sequence' $data 180
	$status = Get-RxPlugin
	[pscustomobject]@{ sequence = $Name; dir = (Join-Path $Dir $Name); frames = $r.count; resets = $status.history_reset_frames; last_error = $status.last_error }
}

function Capture-Hand([string]$Dir) {
	Send-Command 'item replace entity @p hotbar.0 with diamond_sword'
	Send-Command 'item replace entity @p hotbar.1 with diamond_pickaxe'
	Invoke-Harness 'key' @{ name = 'key.hotbar.1'; down = $true; ticks = 1 } | Out-Null
	Wait-Ticks 25
	$c = $script:Scenes['hand']
	Move-Camera $c[0] $c[1] $c[2] $c[3] $c[4]
	Wait-Ticks 30
	$out = @()
	# swing: press attack, capture while the arm animates
	Invoke-Harness 'key' @{ name = 'key.attack'; down = $true; ticks = 2 } | Out-Null
	$r = Invoke-Harness 'capture_sequence' @{ dir = (Join-Path $Dir 'hand-swing'); count = 8; interval_ms = 35 } 120
	$out += [pscustomobject]@{ sequence = 'hand-swing'; frames = $r.count }
	Wait-Ticks 30
	# equip: switch hotbar slot, capture the raise animation
	Invoke-Harness 'key' @{ name = 'key.hotbar.2'; down = $true; ticks = 1 } | Out-Null
	$r = Invoke-Harness 'capture_sequence' @{ dir = (Join-Path $Dir 'hand-equip'); count = 8; interval_ms = 35 } 120
	$out += [pscustomobject]@{ sequence = 'hand-equip'; frames = $r.count }
	Invoke-Harness 'key' @{ name = 'key.hotbar.1'; down = $true; ticks = 1 } | Out-Null
	$out
}

function Invoke-Matrix([string]$Phase, [string]$Shader, [string[]]$ConfigNames = $script:Configs.Keys, [string[]]$Scenes = @('wall','floor','cutouts','blockents','mobs')) {
	$root = Join-Path $PWD.Path "build\validation\$Phase\shaders-$Shader"
	$index = @()
	foreach ($name in $ConfigNames) {
		$cfg = $script:Configs[$name]
		Set-Rx upscaler $cfg.upscaler
		Set-Rx quality $cfg.quality
		Wait-Ticks 60
		$dir = Join-Path $root $name
		$entry = [ordered]@{ config = $name; upscaler = $cfg.upscaler; quality = $cfg.quality; shaders = $Shader; stills = @(); motion = @(); hand = @() }
		foreach ($scene in $Scenes) { $entry.stills += Capture-Still $dir $scene }
		$entry.motion += Capture-Motion $dir 'turn' 'wall' @{ dyaw = 36; ticks = 12 }
		$entry.motion += Capture-Motion $dir 'strafe' 'wall' @{ dx = 4; ticks = 16 }
		$entry.motion += Capture-Motion $dir 'cutout-turn' 'cutouts' @{ dyaw = 36; ticks = 12 }
		$entry.hand += Capture-Hand $dir
		$status = Get-RxPlugin
		$entry.final_status = $status
		$index += [pscustomobject]$entry
		Write-Host "done $name"
	}
	$index | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $root 'index.json') -Encoding utf8
	$root
}
