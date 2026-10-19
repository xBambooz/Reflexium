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
