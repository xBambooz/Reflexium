# DLSS / FG FPS regression spec (2026-10-22)

Owner of integration: Claude lead. Implementer: Codex worker. Repo: `C:\Users\Ryan\source\repos\Reflexium` (Fabric 26.3, Java 25).
Build: `$env:JAVA_HOME='C:\Program Files\GraalVM\graalvm-jdk-25.0.4+7.1'; .\gradlew build` (runs contract checks; must pass).

## User report

1. FPS is much worse with DLSS than with upscaling off.
2. F3 sometimes shows `Frame generation: ... (waiting for consecutive world frames)` and FG seems not to work half the time.

User setup: 2560x1369 window on a 480 Hz monitor, VSync off, maxFps unlimited, Iris + Mellow v3.4.1a, Distant Horizons 3.3.5, Reflex On+Boost, Transformer Performance, RTX 5070.

## Measured evidence (lead, 1920x1080 secondary 240 Hz, same mods, Mellow loaded, screenshots in `build/night/r1/`)

| Mode | avg FPS | 1% low |
| --- | --- | --- |
| Native | 511 / 490 | 198 / 201 |
| DLSS CNN Quality | 475 | 189 |
| DLSS Transformer Performance | 445 | 174 |
| DLSS CNN Performance | 486 | 198 |

Per-pass GPU (profile endpoint): native world 1.80 ms. Transformer Performance world 2.11 ms: evaluate 0.615 ms, while the scaled Iris passes only saved about 0.2 ms (deferred 87->26 us, composite 112->78, final 56->29, terrain 297->211). Most Mellow+DH GPU time (shadow, LOD, fixed-size work) does not scale with render size. SR cannot win in this workload; that is physics, not a bug. The bugs are that the mod lets it lose and that FG makes it far worse:

- FG fixed 2x: output capped at 223 (refresh formula), real FPS 111 (was 500).
- FG fixed 4x: output 223, real 56.
- FG Auto (`fg_multiplier 0`): policy estimate 260 to 297 FPS (Reflex simStart->gpuRenderEnd) while actual uncapped real FPS is ~500, so Auto picks "rendered only", yet the 223 FPS output cap stays applied. Turning FG on cut FPS from ~500 to 223 while generating nothing.
- Every multiplier change / bypass toggle sets `resetNext`, so oscillation near the threshold produces repeated history resets and the "waiting for consecutive world frames" status.

## Required changes

### 1. No FG cap unless frames are actually being generated
`ReflexController.frameGenerationCap` (src/client/java/com/bambooz/reflexium/reflex/ReflexController.java ~301) must return NO_CAP when the refresh policy is in bypass (`multiplier() < 2`). The policy still needs to know the cap value to choose a multiplier, so keep `refreshPolicy().cap(...)` but do not apply it as the Reflex interval while bypassed. Acceptance: FG Auto in a scene whose real FPS exceeds the refresh cap reports the same avg FPS as FG off (within 5%).

### 2. Fixed multiplier is a maximum, never a real-FPS cut
In `FrameGenerationRefreshPolicy`, treat a non-zero `requested` as the upper bound of Auto selection, not a forced value: effective multiplier = `min(requested, maximum, floor(cap / baseFps))`, minimum 1 (bypass). Never select a multiplier whose required real FPS (`cap / m`) is below the measured base FPS by more than 10%. Keep an explicit opt-out: a new config/debug boolean `fg_force_multiplier` (default false) restores the old forced behavior for testing.

### 3. Base FPS from measurement, not the Reflex render-cost estimate
The simStart->gpuRenderEnd reciprocal underestimated base FPS by ~45%. Use, in priority order:
- the measured real present rate while bypassed (uncapped, `delivery.realRate`) once at least 1 s of gameplay samples exist;
- while generating, the GPU frame cost from existing timestamp/Reflex data minus FG evaluate cost, as an upper-bound sanity check; keep the last bypass measurement and re-probe by briefly dropping to bypass only when the estimate suggests the multiplier should go down/up, at most once per 10 s, and record probes in status.
Hysteresis: hold a selection at least 5 s; upshift only with 10% headroom; downshift when the budget is exceeded by 5%.

### 4. Honest FG status line
`FrameGenerationRenderer.statusLine()`: show "waiting for consecutive world frames" only if `resetNext` has stayed set for more than 30 consecutive world frames; otherwise show the active mode. When it persists, show the actual ledger reason (Reflex not sleeping, history reset, GUI blur, surface lost, present failed) in plain words. Add `fg_resets_per_s` to the debug status JSON and an F3 suffix like `resets 3/s` when >0.

### 5. Upscaler Auto must never be slower than native
When `upscaler=auto` (the default), add a measured guard: after a world is loaded and the camera is in gameplay, compare GPU `world_including_upscaling` time (or avg frame interval when GPU timing unavailable) for native vs the selected DLSS mode over ~2 s each, at most once per pack/resolution/quality change. If DLSS is not at least 5% faster, run native and show F3 `Upscaling: native (DLSS not faster here: X vs Y ms)`. Explicit `upscaler=dlss` keeps DLSS but F3 shows the same comparison once measured. Keep the probe cheap: no per-frame readbacks beyond the existing sampled timestamps; reuse `FrameProfiler`.

### 6. Do not regress
- FG still engages (2x+) when base FPS is well below refresh (test by setting maxFps or Reflex `reflex_interval_us` so base ~100 FPS: Auto must pick >=2x and output must be ~ cap).
- All gradle checks pass. Do not touch NGX native code.

## Verification protocol (each change)
Launch only through the harness on the secondary monitor, muted, never stealing focus:
`powershell.exe -NoProfile -ExecutionPolicy Bypass -File C:\Users\Ryan\source\repos\mc-agent-harness\tools\launch.ps1 -Project <worktree> -Port <new port> -Monitor secondary -Width 1920 -Height 1080 -RunDirectory <worktree>\run -GradlePropertiesJson '{"rxDebug":"25599"}' -TimeoutSeconds 240 -Pending` (run in background; set `soundCategory_master:0.0` in run/options.txt first). Join "Reflexium Vitrail Smoke", spectator, `time set 6000`, camera `x0 y110 z0 yaw -45 pitch 15`. Use `tools/mc.sh` and `tools/ab.sh` for endpoint calls and A/B with screenshots. Look at every screenshot: shaders must be visibly on and the F3/status must match the claim. Quit with `quit exit=true`. Never kill java by name. Only one Minecraft client on the GPU at a time.

Report: a table of native / DLSS / FG Auto / FG fixed 2x and 4x avg FPS and real FPS before vs after, with screenshot paths. Commit on your branch with clear messages.
