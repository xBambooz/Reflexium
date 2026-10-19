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

### Indicative uninstrumented FPS, two captures per arm

Each cell lists forward / reverse captures. Cross-JVM differences are not accepted optimization gains. Some captures overlap other clients; per-capture PIDs are in the raw rows.

| Scene | Mode | Before FPS | After FPS |
| --- | --- | ---: | ---: |
| shaderless | native | 509.4 / 2298.7 | 2361.8 / 2318.5 |
| shaderless | cnn-quality | 400.1 / 719.4 | 725.6 / 706.8 |
| shaderless | cnn-balanced | 410.6 / 456.2 | 807.5 / 812.4 |
| shaderless | cnn-performance | 434.0 / 426.7 | 864.0 / 865.9 |
| shaderless | transformer-quality | 351.5 / 272.7 | 495.8 / 498.3 |
| shaderless | transformer-balanced | 283.1 / 276.5 | 497.8 / 498.0 |
| shaderless | transformer-performance | 245.2 / 247.6 | 493.3 / 496.3 |
| bsl | native | 299.4 / 143.0 | 303.8 / 300.3 |
| bsl | cnn-quality | 315.7 / 159.0 | 311.4 / 314.8 |
| bsl | cnn-balanced | 297.7 / 176.7 | 343.2 / 339.3 |
| bsl | cnn-performance | 188.9 / 189.4 | 378.9 / 381.4 |
| bsl | transformer-quality | 147.7 / 131.3 | 280.5 / 280.0 |
| bsl | transformer-balanced | 143.3 / 155.5 | 297.5 / 298.1 |
| bsl | transformer-performance | 144.2 / 141.3 | 297.0 / 295.3 |

### GPU attribution, milliseconds per sampled real frame

Paired cells are before / after. Extras include motion, object motion, pack hand, sharpen and barriers; world residual excludes those and NGX. Residual also includes unseparated native/Iris rendering operations. Final output blit lies outside world and was added only for the candidate.

| Scene | Mode | World total | NGX | Our extras | World residual |
| --- | --- | ---: | ---: | ---: | ---: |
| shaderless | native | 0.227 / 0.204 | 0.000 / 0.000 | 0.000 / 0.000 | 0.227 / 0.204 |
| shaderless | cnn-quality | 0.956 / 0.762 | 0.642 / 0.503 | 0.109 / 0.087 | 0.205 / 0.171 |
| shaderless | cnn-balanced | 0.848 / 0.712 | 0.490 / 0.462 | 0.109 / 0.082 | 0.249 / 0.168 |
| shaderless | cnn-performance | 1.186 / 0.693 | 0.851 / 0.458 | 0.094 / 0.074 | 0.240 / 0.161 |
| shaderless | transformer-quality | 2.629 / 1.249 | 2.200 / 0.988 | 0.186 / 0.090 | 0.243 / 0.172 |
| shaderless | transformer-balanced | 2.828 / 1.238 | 2.360 / 0.991 | 0.230 / 0.082 | 0.239 / 0.165 |
| shaderless | transformer-performance | 2.596 / 1.460 | 2.144 / 1.231 | 0.121 / 0.073 | 0.331 / 0.157 |
| bsl | native | 3.987 / 3.229 | 0.000 / 0.000 | 0.000 / 0.000 | 3.987 / 3.229 |
| bsl | cnn-quality | 3.733 / 2.702 | 0.580 / 0.447 | 0.229 / 0.091 | 2.924 / 2.164 |
| bsl | cnn-balanced | 4.378 / 2.484 | 0.793 / 0.423 | 0.222 / 0.082 | 3.363 / 1.978 |
| bsl | cnn-performance | 4.906 / 2.313 | 1.064 / 0.444 | 0.219 / 0.084 | 3.623 / 1.785 |
| bsl | transformer-quality | 6.424 / 3.410 | 2.127 / 1.019 | 0.182 / 0.095 | 4.114 / 2.296 |
| bsl | transformer-balanced | 6.294 / 3.081 | 1.731 / 1.009 | 0.342 / 0.085 | 4.220 / 1.988 |
| bsl | transformer-performance | 6.051 / 3.162 | 2.490 / 1.304 | 0.226 / 0.074 | 3.335 / 1.783 |

### Candidate pass details, microseconds per sampled real frame

| Scene | Mode | Camera MV | Object MV | Pack hand | Sharpen | Before+after barriers | Final blit |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| shaderless | native | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 21.8 |
| shaderless | cnn-quality | 38.6 | 0.0 | 0.0 | 44.7 | 4.1 | 29.4 |
| shaderless | cnn-balanced | 30.8 | 0.0 | 0.0 | 47.3 | 4.1 | 21.1 |
| shaderless | cnn-performance | 24.1 | 0.0 | 0.0 | 45.4 | 4.6 | 19.1 |
| shaderless | transformer-quality | 36.9 | 0.0 | 0.0 | 48.5 | 4.1 | 19.7 |
| shaderless | transformer-balanced | 29.5 | 0.0 | 0.0 | 48.3 | 4.1 | 19.0 |
| shaderless | transformer-performance | 23.4 | 0.0 | 0.0 | 45.0 | 4.2 | 19.0 |
| bsl | native | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 21.9 |
| bsl | cnn-quality | 38.5 | 0.0 | 0.0 | 48.5 | 4.2 | 19.3 |
| bsl | cnn-balanced | 33.4 | 0.0 | 0.0 | 44.9 | 4.2 | 19.4 |
| bsl | cnn-performance | 31.2 | 0.0 | 0.0 | 48.5 | 4.2 | 20.5 |
| bsl | transformer-quality | 39.0 | 0.0 | 0.0 | 51.7 | 4.2 | 20.0 |
| bsl | transformer-balanced | 31.0 | 0.0 | 0.0 | 49.4 | 4.2 | 19.8 |
| bsl | transformer-performance | 23.3 | 0.0 | 0.0 | 46.8 | 4.2 | 19.9 |

### CPU waits, milliseconds per real frame

Paired cells are before / after. Submit includes its fence waits; do not add them. Reflex total contains driver call and semaphore wait.

| Scene | Mode | Reflex total | Driver call | Sleep semaphore wait | Submit incl. wait | Fence waits | Acquire reuse |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| shaderless | native | 0.043 / 0.013 | 0.023 / 0.009 | 0.019 / 0.003 | 0.368 / 0.131 | 0.308 / 0.082 | 0.000 / 0.000 |
| shaderless | cnn-quality | 0.659 / 0.653 | 0.483 / 0.649 | 0.175 / 0.004 | 0.514 / 0.231 | 0.456 / 0.182 | 0.000 / 0.000 |
| shaderless | cnn-balanced | 0.657 / 0.498 | 0.487 / 0.494 | 0.169 / 0.003 | 0.514 / 0.234 | 0.462 / 0.188 | 0.000 / 0.000 |
| shaderless | cnn-performance | 0.725 / 0.371 | 0.308 / 0.367 | 0.416 / 0.003 | 1.098 / 0.239 | 1.036 / 0.191 | 0.000 / 0.000 |
| shaderless | transformer-quality | 1.271 / 1.433 | 0.271 / 1.428 | 0.999 / 0.004 | 1.799 / 0.057 | 1.741 / 0.008 | 0.000 / 0.000 |
| shaderless | transformer-balanced | 1.243 / 1.351 | 0.285 / 1.346 | 0.958 / 0.005 | 1.805 / 0.088 | 1.749 / 0.034 | 0.000 / 0.000 |
| shaderless | transformer-performance | 1.471 / 1.382 | 0.165 / 1.377 | 1.306 / 0.005 | 2.050 / 0.074 | 1.990 / 0.020 | 0.000 / 0.000 |
| bsl | native | 1.737 / 1.359 | 0.004 / 0.004 | 1.734 / 1.355 | 1.405 / 0.535 | 1.343 / 0.472 | 0.000 / 0.000 |
| bsl | cnn-quality | 0.995 / 1.072 | 0.005 / 0.004 | 0.991 / 1.068 | 1.141 / 0.235 | 1.056 / 0.175 | 0.000 / 0.000 |
| bsl | cnn-balanced | 1.401 / 0.686 | 0.004 / 0.013 | 1.396 / 0.673 | 1.755 / 0.399 | 1.672 / 0.339 | 0.000 / 0.000 |
| bsl | cnn-performance | 1.594 / 0.297 | 0.007 / 0.164 | 1.586 / 0.133 | 1.855 / 0.483 | 1.782 / 0.425 | 0.000 / 0.000 |
| bsl | transformer-quality | 2.903 / 1.281 | 0.004 / 0.004 | 2.899 / 1.277 | 2.461 / 0.416 | 2.388 / 0.353 | 0.000 / 0.000 |
| bsl | transformer-balanced | 2.515 / 1.272 | 0.004 / 0.004 | 2.511 / 1.268 | 2.574 / 0.235 | 2.498 / 0.173 | 0.000 / 0.000 |
| bsl | transformer-performance | 2.818 / 1.205 | 0.004 / 0.004 | 2.814 / 1.201 | 2.412 / 0.320 | 2.342 / 0.256 | 0.000 / 0.000 |

## Findings and remaining work

The candidate BSL controls average 302.0 FPS native, 313.1 CNN Quality, 341.3 CNN Balanced and 380.2 CNN Performance. Those are indicative same-JVM differences of +3.7%, +13.0% and +25.9% against native. Transformer averages 280.3, 297.8 and 296.2 FPS respectively, so it does not beat native in this scene. The large before/after differences above are not accepted optimization gains because baseline contention and JVM drift were uncontrolled.

Candidate CNN Performance NGX GPU time is 0.444 ms with BSL and 0.458 ms shaderless, close to the library's 0.43 ms reference. Transformer Performance is 1.304 ms with BSL, close to its matching M reference of 1.33 ms. The remaining measured auxiliary passes total about 0.074 to 0.095 ms, plus roughly 0.020 ms final blit. Sharpen and camera motion dominate those auxiliary passes. Depth copy, HDR copy and object-motion scopes recorded no work in this fixed scene; that does not establish their cost in scenes where they execute.

Shaderless native averages 2340.1 FPS, while CNN Performance averages 865.0 and Transformer Performance 494.8. Its native world GPU budget is only 0.204 ms. CNN Performance reduces the estimated world residual to 0.161 ms, saving approximately 0.043 ms while adding 0.458 ms NGX and 0.074 ms auxiliary work. Removing the remaining auxiliary passes would still not recover that reconstruction cost. This scene cannot support the requested universal faster-than-native outcome while preserving DLSS reconstruction.

The Iris no-hand fix removes four pack-hand scope invocations per real frame in the spectator BSL controls. The baseline's pack-hand depth snapshots were unnecessary because Iris returned without drawing a hand. Actual hand capture remains active: the creative glass-hand smoke records 136 GPU samples at 9.72 microseconds per invocation, an enabled pack-hand mask and an empty last-error field.

Candidate CPU acquire-reuse waits round below 0.001 ms per real frame. Reflex and submit waits remain workload-dependent and overlap GPU progress; the table is not a sum of independent penalties. No measured evidence here justifies removing completion waits or bypassing Reflex. Isolated fresh-JVM ABBA runs are still required to quantify each fix's throughput effect and investigate any remaining driver pacing penalty.

## Final smoke and build

Opened and inspected the retained BSL Performance still, camera-motion output, packed motion image and creative glass-hand screenshot. The scene renders, the glass hand is visible and the camera-motion field is nonuniform. These are functional smoke checks, not quantitative temporal image-quality acceptance or proof that motion magnitudes are correct. Evidence is retained beside the raw rows as `after-bsl-cnn-performance.png`, `camera-motion_output.png`, `camera-motion_motion.png` and `real-hand.png`.

On continuation, no owned game JVM remained. Another worktree's harness client was running and was left untouched. No new GPU measurements were taken. Re-ran `gradlew.bat build --console=plain -PreflexiumInstallDir=<this-worktree>/build/perf-install`: BUILD SUCCESSFUL, including profiler, Iris ABI, DLSS math, object motion, upscaling contract, frame delivery and Reflex timing checks. No source changes were needed after runtime commit `8150bbc`.

`tools/summarize-perf-overnight.ps1` regenerates `summary.json` and `build/perf-tables.md` from the retained raw JSONL without requiring a running client. The branch remains unmerged.
