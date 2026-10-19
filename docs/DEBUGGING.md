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
| `hand_motion` | `0`, `1` | `0` returns the first-person hand to zero motion, for matched hand A/B captures |
| `mip_bias_offset` | `-4` to `4` | Dev-only offset added to the vendor texture LOD baseline `log2(render/display) - 1` while upscaling. `1` reproduces the pre-2026-10-14 `log2(render/display)` bias. Replacement samplers are rebuilt after a device idle. Never applied at native size |
| `dlss_preset` | `-1`, `5`, `6`, `10`, `11`, `12`, `13` | `-1` is the quality-aware mapping (Transformer: K for DLAA/Quality/Balanced, M Performance, L Ultra Performance). The value is a hint passed to NGX, not the model NGX reports |
| `shaders` | `0`, `1` | Vitrail only: flips `vitrail/pack.txt` `enabled=` in the active game directory and reloads the pack |
| `pack_taa` | `retain`, `off` | Known Complementary packs only: keep or drop the pack's own full-scene TAA (in memory) while upscaling, for A/B |

### Frame timing (`/reflexium` with `frame_mark` or `frame_stats_from`)

The render thread stamps every frame (about 30 s of history). Status `fps` and `frame_time_ms` are
smoothed values, so percentile lows need the stamps:

- `POST /reflexium` with `frame_mark=1` returns the current `frames` index.
- `POST /reflexium` with `frame_stats_from=<index>` returns the intervals rendered after that index:
  `intervals`, `mean_ms`, `p50_ms`, `p99_ms`, `avg_fps`, `low_1pct_fps` (mean of the slowest 1%), and
  `low_0_1pct_fps`. These count rendered (base) frames, not frames generated by frame generation.

`tools/benchmark-upscaling.ps1` uses both to measure the upscaling matrix in `docs/benchmarks/`.

### Per-pass CPU and GPU timing

The authenticated harness endpoint accepts `profile=on|reset|off|status`. `reset` clears counters and enables profiling; `off` returns the collected spans and disables it. Each span includes `cpu_us`, `cpu_samples`, `gpu_us` and `gpu_samples`. GPU timestamp pairs are sampled every 16 frames. The result ring is read only when available, with no query wait. Parent spans include their children. Headline FPS should be measured separately with profiling off.

`tools/measure-per-frame.ps1 -Label profile-example -Paths optimized -Passes 1 -Profile` runs a fixed-pose matrix. `-Paths legacy` selects the previous uniform/depth paths and CNN E for paired tests in the same JVM. `perf_uniforms` and `perf_depth` accept `0|1`; `dlss_preset` accepts `-1` (normal mapping), 5 (E), 6 (F), 10 (J) or 11 (K). `reflex_mode` accepts `off|on|on_boost`. Only the existing Reflex setting persists. Status includes the built preset hint and history reset count. See [performance analysis](PERFORMANCE.md).

Use Benchmark Mod and `tools/bm-quick-bench.ps1` for new performance measurements. The older fixed-pose script above documents the historical captures. `-Diagnostics` adds opt-in `fg_evaluate`/`fg_gui` CPU and GPU spans and raw driver reports; its FPS is diagnostic, not uninstrumented acceptance. Production defaults to implicit attribution (`reflex_submission=0`). Diagnostic `reflex_submission=1` requests explicit IDs only when `VK_NV_low_latency2` revision 3 or later is available. Returning to `0` clears a previously tagged graphics queue. The override resets on JVM restart; keep attribution comparisons separate from performance acceptance.

`reflex_trace=1..32` arms bounded `[FG API]` logging of logical frame IDs, marker calls, submit tags and physical present roles/results. Collect FG-off, 2x and maximum-MFG traces separately after terrain loads, with no competing GPU workload. This records instrumented call boundaries, not physical scanout or every internal vendor submission. Those new live traces remain pending in the [FG/Reflex report](validation/frame-generation-reflex-2026-10-14.md).

`reflex_interval_us=-1|0..1000000` imposes a diagnostic minimum frame interval in microseconds while Reflex is On. `-1` restores the normal cap; an explicit/VRR cap still takes precedence when it is slower. The override is runtime only and appears in status. For example, `800` requests a 1250 FPS ceiling. `-Quick -Explore` runs the selected runtime variants once in one JVM, with no reverse pass or conditioning capture. Use `smart_reflex=0` throughout Reflex investigations.

`POST /reflexium {"reflex_timings":"1"}` returns up to 64 complete driver reports with microsecond timestamps relative to the first returned simulation start. Preserve these fields even when driver timestamps are inconsistent. F3's `Reflex render latency` uses input sample (or simulation start fallback) through GPU completion, excludes scanout, and refuses reports with inverted driver start/end. CPU present return alone is not render-complete latency. Status includes `reflex_status` and `reflex_submission`, allowing a diagnostic to verify caps and the attribution variant.

Settings written through `set upscaler|quality|dlss_model|fsr_sharpness` are saved to
`run\config\reflexium.properties` like menu changes. The jitter and `mv_debug` overrides are runtime
only and reset on restart.

## Upscaler input and output capture

`POST /reflexium` through the authenticated harness with `capture=<name>` (optional `dir=`, `extras=0|1`)
arms one capture of the next evaluated frame. The render thread schedules readbacks of the render-resolution
color the upscaler consumed, the display-resolution output of that same evaluation, the motion vectors and the
scene depth. It writes `<name>_input.png`, `<name>_output.png`, `<name>_motion.png`, `<name>_depth.png` and
`<name>.json` (sizes, requested model and preset, jitter index, phase count, sign and values sent, world and
hand matrices, camera, reset reason, pack identity and the `observed` block). The normal frame path only reads
one volatile reference and does no readback while no request is armed. A request that does not complete within
20 seconds is cancelled, and late readbacks close their images.

Screenshot readback reads four 8-bit channels and forces alpha opaque, so motion and depth are packed into
RGB (see `dlss_capture_encode.fsh`): motion is two 12-bit values at 1/32 pixel over ±64 pixels, depth is a
24-bit fixed point value. `tools/CaptureAnalysis.java` decodes them:

```powershell
java tools\CaptureAnalysis.java capture-check build\validation\x\wall.json       # motion against the metadata's own matrices
java tools\CaptureAnalysis.java motion-stats <x_motion.png> 0 0 960 540
java tools\CaptureAnalysis.java metrics <native.png> <test.png> 200 150 700 450   # detail correlation, Laplacian, PSNR
java tools\CaptureAnalysis.java crop <in.png> <out.png> 200 150 300 200 3
```

Numerical reprojection inverts PNG row Y: Minecraft's screenshot readback writes GPU row `y` into
image row `height-y-1`. The encoded motion components retain their original render-pixel signs.
The generic camera checker includes hand/object pixels and cannot establish their independent motion.
`tools/iq-vector-metrics.py` checks known ground and leaf regions in the Round 2 no-wave camera control.
Its assertions require actual camera movement and a dispatched BSL foliage pass. It needs numpy and Pillow
only for analysis, and runs with `python -I`. `observed.bsl_foliage_motion` reports dispatch; the two
`bsl_*_time` values record the deformation times. This is reviewed BSL frame-time leaf/vine coverage,
not grass, blended layers or cloud vectors.

`tools\upscaling-quality.ps1` is the single driver for the validation matrix (arena, scenes, stills,
motion sequences, hand swing and equip) and `tools\compare-upscaling.ps1` computes the matched-crop metrics.
Absolute Laplacian energy rewards shimmer, so the metrics report a high-pass correlation with the native image.

## Motion vector view (`mv_debug 1`)

A fullscreen overlay of the motion vector texture drawn after upscaling and before the GUI:

- hue is the direction of motion (render pixels, previous position minus current position),
- brightness is the magnitude, full at 4 render pixels per frame (square-root scaled so sub-pixel
  motion still shows),
- black means no motion. The first-person hand is black while it is still on screen, and carries its own
  vector while the swing or equip animation moves it (`hand_motion 0` restores the all-black hand).

What to expect: with a still camera, static terrain is black while moving opaque objects have their
own vectors, including animated limbs. A pure `turn` gives a smooth color field across the scene.
`move` forward gives a radial pattern around the screen center, brighter for near blocks. The hand
stays black unless it animates. Frames are very short at several hundred fps, so per-frame motion is small; use
`set mv_debug 1` with a fast `turn` (for example `turn 180 20`) to see it clearly.

Status includes `evaluated_frames` (must advance to prove the upscaler ran), `pack_hand_mask`,
`object_vertices_captured`, `object_vertices_drawn`, tracked entity counts, `mip_bias` and
`reactive_mask`. Vertex counts report geometry submitted to the velocity pass, not the number of
fragments that passed its depth test. A nonzero count alone does not prove correct velocities.

The `observed` object holds what the last frame actually did, separate from the requested settings:

- `material_binds`: per binding path (`Sampler0` vanilla, `u_BlockTex` Sodium terrain, `pack_atlas` and
  `pack_pbr` Vitrail) the number of binds, how many carried a sampler whose real Vulkan `mipLodBias` equals
  the requested bias, and the bias read back from the sampler actually bound. `mip_bias_requested` alone does
  not prove a material used it.
- `jitter_phases`, `resets_by_reason` and `last_reset_reason` (first frame, level, teleport, frame gap, camera
  mode or entity, rotation cut, loading frame, feature rebuild, menu), `physical_camera` (near, far and
  vertical FOV taken from the unbobbed projection, with a counter of rejected values), `hand_vertices_drawn`,
  `post_effects_need_depth` and `post_depth_resolved`.

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

The dev-only settings endpoint also accepts `vsync`, `fullscreen` and `pause` booleans for lifecycle checks. The background harness can enforce window placement, so setting fullscreen does not establish a successful fullscreen transition. `-PrxPackHdr=true` enables the experimental pinned-pack HDR route; it remains off in ordinary launches. See [pipeline validation](validation/pipeline-improvements-2026-10-15.md).

Frame generation adds `frame_generation=off|dlss|dlss_multi|fsr` and `fg_multiplier=3..8` to `/set`.
Status includes the runtime MFG maximum, FG evaluations, successful generated present submissions,
metering feedback and AMD scheduler present counts. These counters do not measure physical scanout.
`/screenshot?fg_index=0&gui=1` reads the first generated target. See
[frame-generation checks](FRAME_GENERATION.md) for the authenticated harness test script.

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

### BSL foliage diagnostics

`foliage_depth_debug=1` displays the BSL foliage merge gate after reconstruction: green accepted, red rejected by scene/hand depth, black no foliage coverage. The display uses the render-size attachment with nearest sampling and does not feed diagnostic vectors into DLSS. `foliage_clear=0` disables the explicit frame-start attachment clear for a dev A/B; restore `1` afterward. Both reset with the JVM. `observed.bsl_foliage_surface_cleared` reports a clear actually issued for the captured frame. See IQ Round 5 and `tools/iq-round5.mjs` for the owned-client reproduction.
