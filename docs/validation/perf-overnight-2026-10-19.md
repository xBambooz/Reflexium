# 1440p DLSS performance investigation, 19 October 2026

Base: `5776235`. Branch: `t3/dlss-upscaling-performance`. No merge to main.

## Evidence boundary

These are indicative fixed-camera diagnostics, not isolated performance acceptance. Other agents' Minecraft JVMs appeared and relaunched during the baseline (observed PIDs include 3552, 21108 and 19048). Port 25612 was unavailable when checked before each sample, demonstrating that an unavailable expected port is not proof of exclusive GPU access. Other clients were left untouched. Shared GPU scheduling can inflate GPU timestamp intervals as well as reduce FPS. Do not attribute the entire excess over NVIDIA's library table to Reflexium, or claim a repeatable throughput improvement from these captures.

The expected black topmost overlay was left in place. Render-target images can establish rendering output, but these measurements do not establish physical scanout, cadence or end-to-end latency.

## Method

- RTX 5070, NVIDIA 617.42, GraalVM 25.0.4+7.1, Minecraft 26.3 Vulkan, experimental Iris `1.12.0-snapshot+mc26.3-local`, Sodium 0.9.2.
- Owned harness client only, port 25631, isolated copy of the main repo's `run` inside this worktree. Primary PG27AQDP at (0,0), actual window 2560x1440, 479.99 Hz. Launch reported unchanged foreground HWND, no focus and no minimization.
- Copy of FPS Lab's frozen `baseline/scene/world`, save `PerfFrozen`, using the existing first `benchmark-route` keyframe: x=-199.5, z=-199.5, yaw=-45, pitch=15, eye y=111.62 (player y=110), spectator. Server `tick freeze`, noon and clear weather are set explicitly. The source baseline is untouched. This is a stationary view of the standard scene, not moving-route acceptance. Render distance stays 12 for every arm, rather than the moving route's hint of 16.
- FG off, Reflex requested/applied On, Smart Reflex off, interval override 0, VSync off, unlimited Minecraft setting (260 sentinel). Sharpness 20 throughout. No model/preset or quality reductions to manufacture a win.
- Native, CNN E Quality/Balanced/Performance, Transformer K Quality/Balanced and M Performance, shaderless and BSL v10.1.8 HIGH. Forward then reverse mode order in each JVM, one baseline JVM and one candidate JVM. Eight seconds after shader toggle, four seconds after each mode change. This is not fresh-JVM ABBA with BM conditioning; cold-capture and launch drift remain additional reasons not to accept an FPS delta.
- Separate four-second uninstrumented frame-interval samples and four-second instrumented samples. No status polling within either interval. Raw before/after state and port-25612 availability retained per sample. FPS comes only from uninstrumented rows.
- GPU timestamp pairs at bottom-of-pipe, one sampled frame per 16, 16-slot ring, availability-only reads, no query WAIT. CPU timings cover every instrumented frame. Pending tail GPU queries need not have drained at stop. Nested spans are not additive. GPU world residual is world-including-upscaling minus its measured child spans, an attribution estimate rather than an independently measured world-only pass.
- `tools/perf-overnight.ps1` reproduces the matrix. Raw JSONL and environment/mod hashes: `docs/benchmarks/perf-overnight-2026-10-19/`.

## Reference costs and interpretation

NVIDIA's RTX 5070 library-only table uses Performance input, with no engine attached. At 1440p output it lists CNN E/F 0.43 ms, Transformer J/K 1.02 ms, L 1.87 ms and M 1.33 ms. The project's current Transformer Performance requests M, so 1.02 ms is not its matching reference. The table does not establish a cost ceiling for Quality/Balanced, DLAA, shared-GPU execution or complete engine frames. Reference: [NVIDIA DLSS programming guide](https://github.com/NVIDIA/DLSS/blob/main/doc/DLSS_Programming_Guide_Release.pdf), execution timing tables.

The initial historical smoke-pose matrix is rejected: the player settled from requested y=69 to y=65 inside a dark wall. The inspected image `before-bsl-native.png` establishes this invalid coverage; its raw rows remain in `rejected-wall-pose.jsonl`. Its 0.18 to 0.19 ms native world budget cannot represent normal gameplay. The retained matrix uses the open village/desert/forest view, visually checked in `before-frozen-bsl-native.png` before measurement.

## Changes

- `72a3d63`: measure native world rendering at the same world boundary as upscaling; add sharpen, display-depth and HDR-copy scopes. Profiling-off native calls do not create a Vulkan command buffer, including on OpenGL. This is instrumentation, not an FPS optimization.
- `0377582`, corrected by `8150bbc`: separately meter the final surface blit, profiling only. RenderPearl uses its own transient command buffer for this operation. The retained implementation wraps `vkCmdBlitImage` with timestamps on the supplied open transient buffer; it does not keep an encoder command-buffer handle across the surface call. The baseline does not include this additional scope.
- `4393c8b`: guard the FG call site's matrix/reprojection preparation with the callee's existing `wantsMotion()` condition. FG off no longer computes arguments that `sceneReady` immediately discards. No allocation-rate or isolated FPS saving is claimed.
- `1a56a2f`: move the Iris hand depth snapshot/mask hooks from method HEAD/RETURN to the actual draw path after Iris's early returns. The cleared pack mask remains the no-hand input, so skipping a hand cannot classify all world depth as hand coverage. Both real solid and translucent hand draws retain capture. The build's Iris ABI check verifies the early-return and projection boundaries against the supported jar.
- `e97bf69`: remove sharpen's unused display-depth attachment. RCAS reads only color and writes only color; filter math, strength and output remain identical. No isolated speedup is claimed for this one-line change.

No CPU resource-completion wait, acquire semaphore tracking, NGX synchronization barrier, Reflex sleep or visual-quality input was removed.

## Results and validation

Baseline runtime: `72a3d63`, pinned jar `build/perf-jars/before-72a3d63.jar`, SHA-256 `04677F181BBE02EE8E46FB6F0146B4151EB7F47B23E1876E50443A9FDDE8F624`.

Candidate runtime: `8150bbc`, pinned jar `build/perf-jars/after.jar`, SHA-256 `5532941C066573F17B3F456578EB6BADB39DCE1EC70B84117A1D6D9AAE55CC4D`.

Rejected profiler attempt: `e97bf69` included an invalid whole-surface-call timestamp scope. Its first instrumented native sample crashed in NVIDIA `vkCmdWriteTimestamp` because the retained command buffer was no longer recording after the surface call. `rejected-blit-timer-crash.txt` preserves the native/Java stack excerpt; `rejected-blit-timer.jsonl` contains only the preceding uninstrumented sample. `8150bbc` fixes the scope and adds an ABI check for its actual blit call. This was a new diagnostic bug, not an NGX or performance-fix failure. The failed jar is retained as `build/perf-jars/rejected-blit-timer-e97bf69.jar` (SHA-256 `84C7BFC5E50B0B8712406145936AEB9BF304B81692210233F00997FC868EDED3`).

`gradlew.bat build --console=plain -PreflexiumInstallDir=<this-worktree>/build/perf-install` passed with all check tasks. The install target override keeps the shared Modrinth profile untouched. Build log: `build/perf-build.log`. The owned client was shut down with authenticated `quit` and `exit=true`, with exact PID/jar/game-directory ownership verification, before rebuilding.

## Round 2: what does not scale with render resolution (BSL v10.1.8 HIGH, 1440p)

Same scene, hardware, harness client (port 25632, isolated copy of `run`), FG off, Reflex On, sharpness 20 and method as above, run on the primary monitor with no other Minecraft client rendering (every sample waits until ports 25612 and 25641 and all other harness JVMs are gone). Raw rows: `docs/benchmarks/perf-overnight-2026-10-19/r2-*.jsonl`; `tools/perf-overnight-summary.ps1` rebuilds the table. Two passes per mode, forward then reverse, one JVM per jar. These are the same indicative fixed-camera diagnostics as Round 1, not acceptance.

Jars: before `build/perf-jars/r2-before-iris-timers.jar` (`d89f552`, SHA-256 `92DCA0FCFC84D286662BB7667E2EF71BFA6E6D3DA5315A508D7625A2F3841FC7`), after `build/perf-jars/r2-after-shadowcap.jar` (`191690b`, SHA-256 `96E90055C06D9E8D51A8D015ABEB36690A3285EB21F9C61F5DB21EB1D895EDC0`). The "before" jar only adds GPU timestamps, so it is the Round 1 behavior.

### Findings

Iris sizes its colortex and gbuffer targets from the main render target at `beginLevelRendering`, and Reflexium swaps the render-size textures into that target first, so every Iris target and composite/deferred/final pass runs at render size. Measured composite, deferred and final cost fall to 0.30 to 0.33 of native at Performance (25% of pixels), which is the expected scaling plus per-pass overhead. There is no duplicate full-resolution pass and no display-size Iris work. Reflexium's own non-DLSS GPU work is small: camera motion 0.03 ms, sharpen 0.045 ms, barriers under 0.01 ms. HDR copy, pack surface motion, display depth and hand mask are idle in the BSL path (the HDR and surface paths are Complementary only).

The cost that does not shrink is the pack's geometry and shadow work, GPU microseconds per frame (Reflex On, two-pass mean):

| Pass | Native | CNN Quality | CNN Performance |
| --- | --- | --- | --- |
| Shadow pass and prepare | 490 | 481 | 481 |
| Terrain gbuffer phase | 770 | 759 | 627 |
| Clouds and weather phase | 100 | 95 | 87 |
| Deferred composite | 473 | 216 | 144 |
| Composite chain | 888 | 501 | 272 |
| Final pass | 82 | 36 | 22 |
| DLSS evaluate (CNN E) | 0 | 448 | 420 |
| World total (scope) | 3230 | 2921 | 2346 |

At Performance about 1.2 ms of the 2.35 ms world scope (shadow pass 0.48, terrain 0.63, clouds 0.09) is resolution independent, plus 0.42 ms of DLSS and about 0.25 ms of unattributed pass setup and barriers. Only deferred, composite and final scale, and together they are 1.44 ms native. That bounds the saving to about 1 ms, which the 0.42 ms evaluate cost cuts to 0.6 ms. Transformer evaluate is 1.0 ms (1.3 ms at M Performance), so it cannot beat native here. Terrain and shadow cost are vertex and draw bound, so a 300 FPS frame is dominated by them. NVIDIA titles at 60 FPS are dominated by pixel work, which is why their gains are larger.

Shadow work also costs CPU: `renderShadows` is 0.60 to 0.73 ms of the roughly 1.5 ms world CPU span. The frame is not CPU bound. With Reflex off the GPU world scope equals the frame time (CNN Performance 389.7 FPS is 2.566 ms against 2.50 ms world GPU), and Reflex On sleeps 0.3 to 1.4 ms with fence waits of 0.1 to 0.5 ms.

Measured shadow option sensitivity at CNN Performance, Reflex off, one 4 second sample each (BSL options written to the pack's option file, not shipped):

| BSL option set | Shadow pass GPU us | World GPU us | FPS |
| --- | --- | --- | --- |
| HIGH default (2048, 256) | 505 | 2531 | 385 |
| shadowMapResolution 1536 | 520 | 2419 | 404 |
| shadowMapResolution 1024 | 492 | 2309 | 422 |
| shadowDistance 192 | 540 | 2557 | 385 |
| shadowDistance 128 | 231 | 2092 | 460 |
| 1024 and 128 | 156 | 1978 | 490 |
| SHADOW off | 0 | 1612 | 590 |

Shadow distance sets the shadow pass cost (geometry). Map resolution barely moves the pass itself but cuts the later terrain phase by about 0.15 ms. Reflex itself costs 12 to 14% at CNN Performance in this scene (335 to 343 On versus 385 to 390 Off, driver sleep call about 0.7 ms) and nothing at native; `on_boost` did not change it. This is the same unresolved driver behavior documented earlier, not a Reflexium wait.

### Changes

- `d89f552`: opt-in GPU timestamps for the shadow pass, begin/deferred/composite/final chains and each world phase (sky, terrain, cutout, entities, translucent, clouds, hand), CPU spans included. Instrumentation only, the baseline for this round.
- `ea792b0`: `tools/perf-overnight.ps1` waits for competing harness clients before each sample and takes `-Reflex`; raw Round 2 captures.
- `191690b`: opt-in `pack_shadow_resolution_cap` and `pack_shadow_distance_cap` in `reflexium.properties` (debug key `pack_shadow_cap=<resolution>,<distance>`). They clamp the pack's `shadowMapResolution` and `shadowDistance` option values for the compile only, to the largest allowed value at or below the cap, and only while Reflexium owns upscaling. Both default to 0, which never touches a pack's values; the saved option file is not written. Packs without those option names are unaffected. A change recompiles the pack.

No default behavior changed, so the default rows are a regression check, not a gain.

### Before and after (FPS, uninstrumented, Reflex On, mean of 2 passes)

| BSL 1440p | Before | After, caps off | After, caps 1024 and 128 |
| --- | --- | --- | --- |
| Native | 301.1 | 301.4 | not applicable |
| CNN Quality | 315.2 | 310.3 | 338.0 |
| CNN Performance | 335.4 | 343.2 | 478.5 |
| Transformer Quality | 271.9 | 271.6 | 308.0 |
| Transformer Performance | 285.8 | 286.7 | 325.5 |

World GPU scope (us): CNN Performance 2346, 2360, 1933; Transformer Quality 3596, 3565, 3015. Caps off matches the before jar within noise (Quality 310 versus 315 is the largest gap). With the caps, CNN Performance is +59% over native instead of +11%, and Transformer Quality passes native (+2%). The native row of the capped run is not a baseline, because one native pass kept the capped pack across a delayed recompile; use the native row from the first run. Visual check: `r2-shadow-cap-1024-128.png` and `r2-shadow-uncapped.png` at CNN Quality in the same view show sharp, correctly placed shadows with no blur or breakage. The caps do lower shadow distance and map resolution, so far shadows are cruder; that is why they are opt-in.

### Remaining

- Reflex On costs about 12% at BSL CNN Performance (driver sleep call, as in Round 1). No Reflexium wait explains it.
- About 0.25 ms per frame of Iris pass setup and barriers is unattributed, and terrain draw cost does not scale with pixels. Both are inside Iris/Sodium.
- Transformer M at Performance costs 0.3 ms more than K at 1440p. Switching is a quality decision that needs a matched visual A/B.
- Overlapping DLSS evaluate with the next frame's shadow and terrain work would need an async compute queue. Not attempted.
- A shadow cap UI was not added; the caps are config-file and debug-key only.
