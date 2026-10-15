# Shaderless upscaling performance investigation and fix

Prepared 2026-10-15 against `c821f63`. Implementation owner: Opus 5.5 Low. Delegate GPU/BM operation to Haiku 5.5, with one GPU owner at a time. This is an evidence-led implementation brief: the regression is measured, but its precise causal change is not yet isolated. Do not claim that all upscaling can beat native in this workload.

## Context and required reading

Reflexium is a Java 25 Fabric mod for Minecraft 26.3, integrating DLSS/FSR through native bridges and RenderPearl Vulkan mixins. Windows 11, RTX 5070, NVIDIA 617.42. Repository: `C:\Users\Ryan\source\repos\Reflexium`. Build with `.\gradlew.bat build --console=plain`; its install task must leave the matching jar in `C:\Users\Ryan\AppData\Roaming\ModrinthApp\profiles\26.3 Testing Instance\mods`. Pin benchmark jars and SHA-256 before running, because later builds replace the installed jar.

Read the shared ai-brain INDEX, preferences, `minecraft/vulkan-frame-generation.md` and `minecraft/fps-lab.md`, then `docs/PERFORMANCE.md`, `docs/benchmarks/README.md`, `docs/DEBUGGING.md`, `docs/validation/fg-sustained-recovery-2026-10-15.md`, and `FRAME_GENERATION_REFLEX_SPEC.md`. No root AGENTS.md or CLAUDE.md exists at preparation time. Preserve the quality fixes described by `UPSCALING_QUALITY_SPEC.md` and its validation report. Use existing profiling, debug and benchmark infrastructure; no new framework.

Order: preserve recovered FG, close measurement gaps, isolate the regression, implement only the supported correction, then benchmark without instrumentation. Do not repeat the already rejected experiments below.

## Verified starting state

`050d799` fixes the Reflex timeout latch; `c821f63` records Haiku's verification and fixes the runner's missing-counter diagnostic. The pinned current runtime is `build/perf-jars/reflex-recovery-050d799.jar`, SHA-256 `B9560554713A5D5352E2AAB46489CA147299EE14EA1A5AF5A2AE906896C7B049`. Historical baseline `build/perf-jars/ac1e339.jar` has SHA-256 `BB0EE641F7250A75F4747675BFB81F4A6807665376D9D2CCE2E323F24DBD7781`.

Parent inspected the actual summaries, profile JSON, source and implementation report. Latest Set A Quick (`build/bm-bench/reflexrecover-quick-20261015-A-A/summary.json`):

| Mode | ac1e339 real FPS | 050d799 real FPS | Difference |
| --- | ---: | ---: | ---: |
| native | 1910.37 | 1724.23 | -9.74% |
| fg2 | 326.27 | 288.61 | -11.54% |
| fg6 | 156.35 | 155.11 | -0.79% |

Set A diagnostic steady generation ratios are exactly 1/1 for fg2 and 5/5 for fg6 on the candidate. No rejected batches or latched FG failure. Set B Quick is within the 2.4% FPS comparison band for all three modes. Recovery itself was not exercised live because no timeout recurred; keep the lifecycle regression check. Historical old diagnostic rows remain invalid as recorded, due to the old jar lacking the real-present counter. Do not rewrite their validity or treat diagnostic FPS as acceptance.

The native gap exists in the earlier overnight 08381e6 run too. This establishes a regression interval `ac1e339..08381e6`, NOT proof that commit 08381e6 alone caused it. That interval contains quality, pacing and marker changes. The 2180 FPS native result from fix b had Reflex sleep disabled after a timeout and is not a comparable full-Reflex baseline.

## Finding 1: distinguish the new pacing penalty from GPU work (high priority)

Files: `src/client/java/com/bambooz/reflexium/reflex/ReflexController.java:196,229,240,246`; `mixin/MinecraftMixin.java:45`; `mixin/VulkanGpuSurfaceMixin.java:82,141`; `vulkan/VulkanLifecycle.java:111,135` (all under the same package root).

Retained profiles in `build/bm-bench/reflexrecover-diag-20261015-A-A/` provide a stronger lead than the headline FPS:

| Metric, microseconds | old native s0f1 | new native s1f1 | old fg2 s0f2 | new fg2 s1f2 |
| --- | ---: | ---: | ---: | ---: |
| Reflex sleep per real frame | 129.61 | 177.32 | 21.79 | 583.18 |
| Driver sleep call | 125.78 | 173.31 | 15.86 | 3.97 |
| Sleep semaphore wait | 3.26 | 3.37 | 5.77 | 578.98 |
| Submit including wait, per invocation | 163.95 | 173.53 | 1227.67 | 1227.72 |
| SR evaluation GPU | unmeasured | unmeasured | 630.03 | 626.73 |
| World including SR GPU | unmeasured | unmeasured | 908.99 | 925.12 |

FG2 has two submit invocations per real frame in both arms. Current FG2 FG evaluation is 1356.75 us GPU, GUI replay 5.17 us. Old jar has no FG evaluation span; absence is not zero. These are short sampled diagnostics, not an additive causal decomposition. Parent/child spans overlap. The new FG2 sleep wait is about 0.573 ms higher, while SR cost and submit means are virtually unchanged. The older general SR driver-call oversleep diagnosis does not describe this particular FG2 wait distribution.

Current marker path: Minecraft brackets the entire `GpuSurface.present()` wrapper with ordinary PRESENT markers. Inside that wrapper every batch present, including the real image, receives out-of-band PRESENT markers; replacement submits receive out-of-band render-submit markers. `beforeBatchPresent(boolean generated)` currently ignores the argument. This was an intentional intermediate-layer integration change, not automatically a bug. Audit against the actual raw Vulkan integration contract before changing it. Compare `git diff ac1e339 08381e6` and the intermediate commits; do not blindly restore the old sequence merely because it is faster.

Implementation steps:

1. Record one bounded 32-frame API trace for native, SR-only Transformer Performance, and FG2 on current source. Retain frame IDs, ordinary and out-of-band markers, submit batches, metering batch boundaries, and actual sleep configuration. Verify one sleep per real frame before input sampling. Record smart pacing disabled, requested/applied Reflex On, zero interval and no failed/suspended pacing.
2. Isolate native and FG2 regressions separately. Use pinned intermediate builds or one-variable diagnostic variants. Prioritize the marker placement changes and the acquire completion change below. Keep SR-only measurements in every narrowed comparison so an FG fix cannot hide an SR regression.
3. For any marker variant, document the reference-supported intended attribution, including the real image versus the application's aggregate present wrapper. A faster variant that hides work from Reflex is not acceptable. Explicit attribution defaults false (`debug/DebugSettings.java:18`); the empty clear submission at `VulkanLifecycle.java:135` is conditional on `queueTagged`. Do not claim a default extra queue submit without tracing that it occurs.
4. Keep the smallest verified correction. Preserve timeout suspension/recovery, pending-signal lifetime, generated/real delivery accounting, and the FG gate requiring working Reflex. No disabling sleep, Smart Reflex auto-pause, shorter timeout, minimum-interval floor, or hidden FPS cap as a throughput fix.

Verification: existing `ReflexTimingCheck` and `FrameDeliveryCheck`, one bounded marker-order regression check if marker behavior changes, trace with no duplicate/unmatched spans, then Haiku's paired uninstrumented runs. Label any unresolved driver behavior accurately; do not declare it unavoidable from a single profile.

## Finding 2: acquire reuse waits are outside the acquire timer (measurement bug, medium priority)

Files: `mixin/VulkanGpuSurfaceMixin.java:39-47,63-68`; `mixin/VulkanSubmitTimingMixin.java:18`; `debug/FrameProfiler.java:12-21`.

The wrapper waits for the selected acquire semaphore's previous consuming submission BEFORE starting the ACQUIRE span. A short `acquire` mean therefore excludes that wait. The nested `awaitSubmitCompletion` hook counts it in generic `submit_fence_wait`, which also contains other callers. Its mean is per call, with many cached checks, and cannot locate stalls by itself.

Add a CPU-only `acquire_reuse_wait` span around just `completion.awaitCompletion`, with `try/finally`, using FrameProfiler's existing start/end API. Keep the existing acquire API span meaning unchanged. Include count and total-per-real-frame in the diagnostic report; the current JSON already exposes count and mean, so derive the total rather than introducing a second metrics subsystem. If the new span is materially large, add only the minimum provenance needed to associate it with the submit index and batch role in the bounded trace. Do not emit per-frame logs during performance capture.

Important source evidence: local decompiled RenderPearl files `build/VulkanGpuSurface.java:263,395` and `build/VulkanCommandEncoder.java:213,602,632` show that acquire rotates a two-entry semaphore ring; blit records the wait; createFence captures currentSubmitIndex; submit increments the index and waits for index minus two. Cached completed indices return immediately. Reconfirm these sources against the installed dependency before implementation. In a normal one-submit-per-present loop, the two-slots-old acquire wait is likely already covered by the previous submit completion, so do not assume the fence added an entire GPU-frame stall. Prove the indices.

If measured reuse stalls justify changing the ring, retain completion tracking on EVERY slot. Select capacity from the actual bounded usage and prove lifetime on native, FG2, FG6, failed acquire/present, resize and world changes. Do not simply restore the old untracked 16-semaphore ring. An increased ring cannot waive completion. Preserve command-pool and transient-memory waits. Avoid redesigning submission ownership unless the measurements require it.

Verification: profiler check/build, diagnostic showing non-overlapping acquire API and reuse scopes, and core plus synchronization validation for any synchronization change. Keep validation/profiling off in headline FPS captures.

## Finding 3: SR cost exceeds the rendering saved in this light scene (performance boundary)

Files: `dlss/UpscalingRenderer.java:563,617,1100-1165`; `docs/PERFORMANCE.md` sections GPU breakdown and native input-resolution controls.

The shaderless complaint has two parts: avoidable integration overhead and a real reconstruction cost. Historical same-scene native controls were 0.510 ms at 1080p and 0.466 ms at 960x540. Reducing pixel count saved only about 0.044 ms there, while measured CNN evaluation was about 0.27 ms and Transformer about 0.63-0.76 ms. Those historical fixed-pose controls are not today's BM route and are not a current speed limit. They do show why "Performance mode must beat native" is not a valid unconditional acceptance criterion. DLAA also has no pixel-count saving.

Current evaluate writes directly into the display target. Device-idle waits are on feature replacement/shutdown, not the steady path. Descriptor helpers are not per-frame Vulkan image-view allocation. Existing DLSS motion uniforms use transient mapped slices; existing direct scene depth avoids the redundant copy. Do not implement these optimizations again. Preserve object/hand motion, jitter, mip bias and all quality fixes. The OFF branch copying full-size color at evaluate line 1121 is the native-resolution FG path, not ordinary native with both SR/FG off.

After locating the regression, collect current SR-only cost decomposition for native, CNN Performance, Transformer Performance, Transformer DLAA and FSR Performance. Reuse the existing reduced BM set. Compare actual input-resolution native controls separately if needed; never compare different resolution or preset rows as the same-quality win. Optimize only a measured avoidable pass. Do not switch K to E/F, drop motion vectors, alter the sampler bias, reduce jitter, or enable extra temporal accumulation to manufacture throughput. If remaining cost is the runtime itself, report the measured break-even with uncertainty and preserve explicit user choices.

## Experiments already rejected

- `docs/PERFORMANCE.md` records an 800 us Reflex floor that made high-FPS throughput worse, including in the standalone native Vulkan reference.
- Explicit queue IDs versus implicit attribution did not remove the historical SR sleep penalty. A native Win32 reference reproduces it without Java/NGX. This does not settle the new FG2 marker regression.
- `perf/fg-submit-batch` recorded all present blits before one submit. Native drifted -17.7%, FG2 only +2.2%; apparent MFG improvement was unconfirmed and the patch was removed. Do not revive it as an established fix. If revisited later, obey the surface acquisition bound and preserve every resource lifetime, with stable controls first.
- CNN E-to-F changed image appearance and regressed other modes. FSR uniform/depth candidates were not consistently beneficial. Do not recycle them without new evidence.

## Benchmark and completion contract

Haiku owns GPU runs, using `tools/bm-quick-bench.ps1`. No simultaneous Minecraft, FPS Lab, native reference or other GPU experiment. Its process guard must include benchmark parents between JVM launches. Never kill another owner's client. Use the existing frozen FPS Lab scene, existing background harness and fixed heap; preserve foreground focus and source-world hashes. Do not alter the route, view distance, scene, window size, presets or Reflex mode between paired arms.

1. Run runner `-SelfCheck`. Pin the current recovered jar as the immediate baseline, with ac1e339 used only for regression localization. Run current-vs-candidate Quick for the narrow implicated modes first. A diagnostic capture uses `-Diagnostics` and is never headline acceptance. Each label is fresh; the runner appends `-A` or `-B` itself.
2. For an accepted candidate, run the reduced SR set plus native, fg2 and fg6, with an independent reverse-order pair or full ABBA. Reuse the runner's full method for final acceptance; report Quick as provisional. Set B is the regression control for shared presentation changes. Verify actual applied state and sustained-generation ratios, not merely requested settings or one generated present.
3. Require repeatable improvement outside the 2.4% Quick FPS noise band in the targeted mode, with stable native controls and no unexplained regressions. The 16.8% Quick 1%-low band is a noise warning, not permission for a permanent 16% loss. Final performance acceptance requires repeated evidence. Do not compare instrumented and uninstrumented FPS or subtract overlapping timing scopes.
4. Image/interaction smoke: same quality/preset and motion, moving hand/entities, world rejoin, resize, pause menu, FG off/on and 2x/6x. For presentation changes retain submitted rates versus measured scanout distinctions. FrameView remains necessary to claim smooth display delivery or physical latency improvement.
5. Write `docs/validation/shaderless-performance-2026-10-15.md` with root cause confidence, final code diff, raw artifact paths/hashes, matched controls, wins and unresolved limits. Commit changes, build/install the final retained code and confirm hashes. No push or site publication requested. Return to the parent if two focused variants fail; do not keep farming the same unresolved problem to cheaper agents.

## Primary references

- [Khronos acquire requirements](https://docs.vulkan.org/refpages/latest/refpages/source/vkAcquireNextImageKHR.html): VUID 01779 requires no pending signal/wait on the acquire semaphore. Completion protection cannot be removed for FPS.
- [Khronos latency sleep](https://docs.vulkan.org/refpages/latest/refpages/source/vkLatencySleepNV.html): use the actual Vulkan pacing contract.
- [NVIDIA Reflex integration](https://github.com/NVIDIA-RTX/Streamline/blob/main/docs/ProgrammingGuideReflex.md): sleep placement, unchanged-option handling, and QA criterion of no significant (>4%) On-mode FPS impact. This is a target to investigate, not permission to bypass Reflex.
- [NVIDIA DLSS guide](https://github.com/NVIDIA/DLSS/blob/main/doc/DLSS_Programming_Guide_Release.pdf): performance guidance; the local performance report retains the reviewed 310.6 library-only timing table and its Performance-input limitations. It is not an engine or DLAA guarantee.

The parent made no runtime edits or GPU launches while preparing this spec. Sustained FG recovery is functionally verified; shaderless performance is still open.
