# Upscaling and frame generation matrix

Raw results for the Reflexium upscaling and frame generation matrix. The FPS Lab Upscaling tab is fed from
these files by `Benchmark Mod/tools/mctest/build_upscaling.py`.

## Files

- `upscaling-set-A-runs.jsonl`: every attempt for Set A (no shader pack). One JSON line per attempt, with
  `outcome` (`accepted`, `rejected`, `refused`, `failed`, `error`), the status verification reasons, and all
  measured values for accepted attempts. Pass 0 is the conditioning run. It is written to the file but excluded from the summary.
- `upscaling-set-A.csv` and `.json`: per-mode summary, rebuilt by `tools/benchmark-upscaling.ps1 -Set A -Summarize`.
- `upscaling-set-B-*`: the same for Set B (Vitrail, Complementary Reimagined r5.9.3).
- `logs/`: driver console output and attempts from runs that were superseded or aborted. These are kept,
  not used. See the notes below.

## Protocol

- Client: Reflexium dev client on Minecraft 26.3, launched by `mc-agent-harness` on port 25601 with
  `-Width 1920 -Height 1080` on the secondary monitor. Vulkan, VSync off, Max Framerate unlimited (`maxFps:260`),
  Reflex on (`reflex_mode=on`), render and simulation distance 12.
- World: `Reflexium Vitrail Smoke`, camera at (-25.5, 69, 23.5), yaw 0, pitch 0, reset before every run.
- Per run: apply the mode, verify it through `/status` and `run/logs/latest.log` (upscaler built and active,
  quality label and render width, requested DLSS model in the log, frame generation string and multiplier),
  wait 5 s (100 ticks), verify again, then a fixed route of 10 steps × 60 ticks (30 s): five turns alternating
  with five moves of 4 to 8 blocks.
  After the route, verify again and require generated presents during the route for FG modes.
- Attempts that fail verification are recorded as `rejected` and repeated up to three times. A refused
  upscaler (Vitrail paused it) is recorded as `refused` and gets no numbers.
- Frame generation: DLSS FG and FSR FG run at their fixed 2x, so no multiplier is set. MFG rows set `fg_multiplier` 3 to 6. The client accepts 3 to 8 and runtime max is 6, so there is no MFG 2x row.
- Order: one unrecorded native run, then pass 1 forward, then pass 2 reversed (ABBA).
- Each set is one client session. Compare numbers only within a set. Sets are not comparable with each other.

## Metrics

- `displayed_fps_mean` (Avg FPS): mean of Minecraft `getFps` samples from the status endpoint. Frame generation
  counts generated frames here.
- `avg_fps` (Base FPS): rendered frames per second from the render-thread frame stamps.
- `low_1pct_fps` (1% low): mean of the slowest 1% of base frame intervals, as FPS.
- `gpu_mean_pct`: `nvidia-smi` utilization, sampled once per second during the route.
- `render`: status `render_size` for upscaled modes. Status keeps the last upscaled size after the upscaler turns off, so native rows use the display size.
- `logged_dlss_model`: the model requested by the setting, read from the client log. NGX does not report
  which model it selected, so this is not proof of the model NGX ran.

## Known limits

- The route is one scene and one 30 s path on one machine. It does not establish a universal result.
- Status polling runs once every 40 ms during the route. The first attempt polled without a delay. Status
  calls compete with the render thread, so those attempts were archived in `logs/` and the run restarted.
  The size of that effect was not measured separately.
- FG rows: the frame stamps measure base frames only. Displayed numbers come from the status `fps` samples.

## Quick paired verification with Benchmark Mod

`tools/bm-quick-bench.ps1` launches the old and new Reflexium jars in four fresh JVMs in old, new, new, old order. The harness opens each client on the secondary monitor without changing desktop focus. Its debug endpoint switches modes; Benchmark Mod records the frame intervals. The script copies FPS Lab's existing `benchmark-route` scene from `C:\Users\Ryan\Documents\Minecraft FPS Lab\baseline\scene`, without writing to that source. This scene has a 48-second route and render distance 16, taken from its `scene.json`. No world or keyframes are authored by this script. `-SceneSource` may select another existing BM scene with a world and at least two keyframes. The built-in `vanilla-tour` and `hermitcraft-s10` entries currently contain no keyframes and are rejected.

Benchmark Mod performs its own first-capture conditioning and world reload in each JVM. The script adds no conditioning capture. It checks the copied world's hashes and writes raw BM results, `protocol.json`, `runs.jsonl` and `summary.json` under `build/bm-bench/<label>-<set>/`. Both arms use a fixed 4 GiB heap and the selected jar is isolated from this worktree's compiled classes.

Build the candidate and driver first, then run the shaderless reduced set:

```powershell
$env:JAVA_HOME = 'C:\Program Files\GraalVM\graalvm-jdk-25.0.4+7.1'
.\gradlew.bat build bmDriverJar
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\bm-quick-bench.ps1 -SelfCheck
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\bm-quick-bench.ps1 -OldJar build\perf-jars\ac1e339.jar -NewJar build\libs\Reflexium-1.2.0-alpha-fabric-26.3.jar -Label candidate-A -Set A -IncludeFG
```

The default modes are native, DLSS CNN Performance, DLSS Transformer Performance, DLSS Transformer DLAA, and FSR Performance. `-IncludeFG` adds DLSS FG 2x and MFG 4x when FG or present code changed. Use `-Set B` with a new label when shader or pass code changed; it enables Complementary Reimagined before the world loads. The normal capture uses three BM passes and a 10-second warmup. Jar paths may be absolute. The script requires an unused port (default 25601), the local harness and BM jar, and the frozen FPS Lab scene.

`-Explore` compares runtime variants forward and reversed in one JVM. Overrides follow `@`, for example `-Modes 'tf-performance,tf-performance@reflex_mode=off'`; each touched override resets before the next mode that omits it. `-Diagnostics` records a five-second CPU/GPU profile and driver latency reports during each capture. Its FPS is instrumented diagnostic evidence and must not be treated as final acceptance. Do not repeat exploratory sweeps.

The A/A noise bands recorded in `ai-brain/minecraft/fps-lab.md` are about 2.4% for average FPS and 16.8% for 1% low for one launch per arm; the summary scales these by the square root of two for its two launches per arm. The reported FG FPS is the rate of real frames, so multiply by the FG factor only when describing displayed frames. Keep both native controls and all raw BM files; no single 1% low pair is conclusive. Do not resume a partial ABBA block: run the entire command with a new label.
