# Debugging upscaling without menus

Dev-only harness for tuning DLSS and FSR jitter and motion vectors fast. A tiny HTTP server inside the
mod answers JSON commands, so an agent (or you) can switch upscaler settings, move the camera, take
screenshots and join a world from a terminal.

It exists only when the JVM property `-Dreflexium.debugPort=<port>` is set. It binds `127.0.0.1` only
and is never started in normal play or from the release jar's defaults.

## Quick start

For unattended testing on the second monitor, use the standalone harness at
`C:\Users\Ryan\source\repos\mc-agent-harness`. Its launcher injects the test mod without changing
this project's dependencies and exposes authenticated screenshot, camera, world and window commands
through HTTP or MCP. See that repository's `README.md`. Reflexium registers a `reflexium` endpoint
and a status contributor when the harness is active. No debug port is needed for this integration.

Shutdown must verify the recorded exact game PID and game directory against authenticated status,
then verify that PID's Windows command line contains the injected harness jar and test directory.
The MCP `quit` command performs these checks. Never close clients by process name or close a launcher
instance to free the test port.

```powershell
.\tools\run-debug.ps1                          # starts runClient in the background, waits for /status
.\tools\rxdbg.ps1 join "Reflexium Vitrail Smoke"   # loads the save folder, returns when terrain is ready
.\tools\rxdbg.ps1 status
.\tools\rxdbg.ps1 screenshot out\shot.png
.\tools\rxdbg.ps1 move 0 5 40
.\tools\rxdbg.ps1 quit exit                    # leaves the game (or just: quit, to go back to the title screen)
```

`run-debug.ps1` sets `JAVA_HOME` to `C:\Program Files\GraalVM\graalvm-jdk-25.0.4+7.1`, runs
`gradlew.bat runClient -PrxDebug=25599` and polls `/status`. `build.gradle` adds the debug port vmArg
only when the Gradle property `-PrxDebug[=port]` or the environment variable `RX_DEBUG_PORT` is set.
Use `-Port` on `run-debug.ps1` and `$env:RXDBG_PORT` for `rxdbg.ps1` to change the port (default 25599).
Gradle and game logs go to `run\logs\rxdebug-gradle.out.log`.

Relative output paths given to `rxdbg.ps1` resolve against your current directory. Raw HTTP paths
that are relative resolve against the `run\` game directory.

## Commands

All replies are JSON with `"ok": true|false` (and `"error"` when false). Every command that touches the
game runs on the render thread.

| CLI | Endpoint | What it does |
| --- | --- | --- |
| `status` | `/status` | Upscaler, quality, render and display size, jitter index and jitter (px), jitter sign, fps, frame time, camera pos and rotation, world loaded, screen, last upscaler error |
| `set <key> <value>` | `/set?key=&value=` | See keys below. Returns the new status |
| `screenshot [path] [gui]` | `/screenshot?path=&gui=0\|1` | PNG of the final display-resolution image, after upscaling. The HUD is hidden unless `gui` is given |
| `camera x y z [yaw pitch]` | `/camera` | Teleports the player (integrated server `teleportTo`) and sets rotation. Missing values keep the current ones. Returns when the client player arrived |
| `move dx dz [ticks]` | `/move` | Moves the player by a total of dx, dz blocks (world axes) spread evenly over `ticks` client ticks (20 per second). Blocks until done; `async=1` returns at once. Uses normal collision, so walls stop it |
| `turn dyaw [ticks] [dpitch]` | `/turn` | Rotates by a total of dyaw degrees (and dpitch) over `ticks`. Same blocking behavior |
| `sequence count interval_ms dir [turn dyaw ticks \| move dx dz ticks]` | `/capture_sequence` | Takes `count` screenshots `interval_ms` apart into `dir` (`frame_000.png`, ...), starting the motion first when given. Each entry reports frame number, jitter index and camera |
| `join <world>` | `/join?world=` | Opens the save by its folder name from the title screen and returns when the world and terrain are ready |
| `quit [exit]` | `/quit?exit=0\|1` | Leaves the world to the title screen, or exits the client with `exit` |
| `raw /endpoint k=v ...` | any | Calls an endpoint directly |

### `set` keys

| Key | Values | Notes |
| --- | --- | --- |
| `upscaler` | `off`, `auto`, `dlss`, `fsr` | Same as the config value |
| `quality` | `native_aa`, `quality`, `balanced`, `performance`, `ultra_performance` | |
| `dlss_model` | `auto`, `transformer`, `cnn` | |
| `fsr_sharpness` | `0` to `100` | |
| `jitter_sign_x`, `jitter_sign_y` | `1`, `-1`, `default` | Runtime override of the sign UpscalingRenderer applies to the jitter handed to the upscaler. `default` restores the built-in value (`1`, measured in build/jitter-tuning, or `-Dreflexium.dlss.jitterSign`). `jitter_sign` sets both |
| `jitter` | `0`, `1` | `0` zeroes the projection jitter and the jitter given to the upscaler, for A/B |
| `mv_debug` | `0`, `1` | Draws the motion vector texture over the upscaled world (see below) |
| `object_motion` | `0`, `1` | Enables the opaque entity/block-entity velocity pass; `0` gives a camera-only A/B |
| `object_static` | `0`, `1` | Also redraws static tracked objects for depth-match debugging |

Settings written through `set upscaler|quality|dlss_model|fsr_sharpness` are saved to
`run\config\reflexium.properties` like menu changes. The jitter and `mv_debug` overrides are runtime
only and reset on restart.

## Motion vector view (`mv_debug 1`)

A fullscreen overlay of the motion vector texture drawn after upscaling and before the GUI:

- hue is the direction of motion (render pixels, previous position minus current position),
- brightness is the magnitude, full at 4 render pixels per frame (square-root scaled so sub-pixel
  motion still shows),
- black means no motion; the first-person hand is always black.

What to expect: with a still camera, static terrain is black while moving opaque objects have their
own vectors, including animated limbs. A pure `turn` gives a smooth color field across the scene.
`move` forward gives a radial pattern around the screen center, brighter for near blocks. The hand
stays black. Frames are very short at several hundred fps, so per-frame motion is small; use
`set mv_debug 1` with a fast `turn` (for example `turn 180 20`) to see it clearly.

Status includes `evaluated_frames` (must advance to prove the upscaler ran), `pack_hand_mask`,
`object_vertices_captured`, `object_vertices_drawn`, tracked entity counts, `mip_bias` and
`reactive_mask`. Vertex counts report geometry submitted to the velocity pass, not the number of
fragments that passed its depth test. A nonzero count alone does not prove correct velocities.

## Worked recipe: tuning the jitter sign

Wrong jitter sign means the upscaler removes the jitter in the wrong direction. Symptoms: edges
shimmer, crawl or double while the camera moves, and may even swim while it stands still. A correct
sign gives stable edges and clean reconstruction. Each axis can be wrong independently, so test all
four combinations.

1. Start and join, then pick the upscaler and a quality with a real upscale ratio:

   ```powershell
   .\tools\run-debug.ps1
   .\tools\rxdbg.ps1 join "Reflexium Vitrail Smoke"
   .\tools\rxdbg.ps1 set upscaler fsr          # or dlss
   .\tools\rxdbg.ps1 set quality performance   # higher ratio makes jitter errors easier to see
   .\tools\rxdbg.ps1 camera -25.5 69 23.5 0 0  # a view with high contrast edges (leaves, fences, horizon)
   ```

2. Reference with no jitter. This is the "nothing can be wrong" image:

   ```powershell
   .\tools\rxdbg.ps1 set jitter 0
   .\tools\rxdbg.ps1 sequence 4 100 out\nojitter_still
   .\tools\rxdbg.ps1 sequence 8 100 out\nojitter_turn turn 30 40
   .\tools\rxdbg.ps1 set jitter 1
   ```

3. Stand-still test. With the camera still, the jitter changes every frame, and a correct pipeline
   converges to a stable image. A wrong sign makes consecutive frames differ.

   ```powershell
   foreach ($sx in -1, 1) { foreach ($sy in -1, 1) {
       .\tools\rxdbg.ps1 set jitter_sign_x $sx | Out-Null
       .\tools\rxdbg.ps1 set jitter_sign_y $sy | Out-Null
       Start-Sleep -Seconds 1      # let history settle
       .\tools\rxdbg.ps1 sequence 4 100 "out\still_x${sx}_y${sy}" | Out-Null
   } }
   ```

4. Motion test. Same four combinations, now while turning (and once with `move 0 4 40`):

   ```powershell
   foreach ($sx in -1, 1) { foreach ($sy in -1, 1) {
       .\tools\rxdbg.ps1 set jitter_sign_x $sx | Out-Null
       .\tools\rxdbg.ps1 set jitter_sign_y $sy | Out-Null
       .\tools\rxdbg.ps1 camera -25.5 69 23.5 0 0 | Out-Null
       .\tools\rxdbg.ps1 sequence 8 120 "out\turn_x${sx}_y${sy}" turn 30 40 | Out-Null
   } }
   ```

5. Judge. Open the same frame index from each folder side by side. The right combination has
   straight, stable edges on leaves, fence posts and the horizon, no sparkle trail behind edges and no
   frame to frame wobble in the still sequences. The wrong ones show shimmer, double edges or
   crawling. Optional numeric check of the still sequences (lower is more stable):

   ```powershell
   Add-Type -AssemblyName System.Drawing
   function Get-FrameDiff([string]$a, [string]$b) {
       $x = [Drawing.Bitmap]::FromFile($a); $y = [Drawing.Bitmap]::FromFile($b)
       try {
           $sum = 0.0; $n = 0
           for ($i = 0; $i -lt $x.Width; $i += 4) { for ($j = 0; $j -lt $x.Height; $j += 4) {
               $p = $x.GetPixel($i, $j); $q = $y.GetPixel($i, $j)
               $sum += [math]::Abs($p.R - $q.R) + [math]::Abs($p.G - $q.G) + [math]::Abs($p.B - $q.B); $n += 3
           } }
           $sum / $n
       } finally { $x.Dispose(); $y.Dispose() }
   }
   Get-FrameDiff out\still_x-1_y-1\frame_001.png out\still_x-1_y-1\frame_002.png
   ```

6. Cross check with the motion vectors: `set mv_debug 1`, run `turn 90 40` and `sequence 4 100 out\mv turn 90 40`.
   The overlay should be one smooth color field. If direction or brightness jumps, or the hand is not
   black, the problem is in the motion vector pass (`dlss_motion.fsh`, `DlssMath.reprojection`), not the
   jitter sign. `set jitter 0` removes jitter from the overlay as well, which isolates reprojection.

7. Put the winning signs into the code (`JITTER_SIGN` in `UpscalingRenderer`) or keep using
   `-Dreflexium.dlss.jitterSign`. The runtime overrides never persist.

## Notes and limits

- The harness forces `pauseOnLostFocus` off while the debug server runs, so a background window keeps
  ticking and scripted motion is not frozen by the pause menu.
- Window minimized or unfocused for a long time can hit vanilla's AFK frame limits and slow frames.
- `screenshot` and `sequence` read back the main render target, which is the display-resolution image
  after upscaling. The HUD is toggled off for the capture (and restored); the first-person hand stays.
  Pass `gui` to keep the HUD or any open screen.
- `join` expects the save's folder name under `run\saves\`. Joining from inside a world fails with a
  message; `quit` first.
- A command that waits (`move`, `turn`, `join`, `sequence`) holds its HTTP connection open. Use
  `async=1` on `/move` and `/turn` through `rxdbg.ps1 raw` if you want to poll `/status` meanwhile.
- If the render thread is blocked (world loading), `/status` answers `504` after 30 seconds.
- Only one scripted motion runs at a time; a new one replaces the old.
