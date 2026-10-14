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
