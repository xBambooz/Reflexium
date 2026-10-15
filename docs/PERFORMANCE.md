# Upscaling performance

Continuation status, 14 October 2026: the apparent FG regression is session drift. The remaining Reflex throughput penalty is reproduced with Benchmark Mod on FPS Lab's standard scene, but a safe throughput fix has not been established. The changes below add diagnostics and correct latency reporting; they do not claim an FPS optimization. See the continuation evidence and the latest sleep audit at the end of this document.

Measured on 14 October 2026, RTX 5070, NVIDIA 617.42, Minecraft 26.3, GraalVM Java 25, Vulkan, 1920x1080, VSync off, unlimited FPS and Reflex On. Frame generation is off unless a test explicitly enables it. This is a shaderless fixed-pose investigation, separate from the older moving-route Set A and shader Set B results.

The outline timings below describe the earlier post-upscale renderer. That renderer was removed after visual checks showed depth-clipped edges even with corrected line indices. Current builds use vanilla's world-pass outline and skip the outline depth reconstruction entirely. The uniform and scene-depth optimizations measured here remain in place.

## Measurement

`tools/measure-per-frame.ps1` drives the authenticated background harness on port 25601. The pose is (-25.5, 69, 23.5), yaw 0, pitch 0, in `Reflexium Vitrail Smoke`. The comparison freezes server ticks, conditions and reloads the world, waits four seconds after each mode change, then measures four seconds of render-thread frame intervals. It reads status only before and after a sample. The matrix uses A forward, B forward, B reverse, A reverse, in one JVM. A enables the legacy paths; B enables candidate optimizations. Both arms retain Reflex On. Native controls are present in both arms. The `final-*` files are the full candidate matrix. After rejecting the F preset, CNN Ultra Performance was retested in that JVM with three seconds warmup and five seconds measurement, under `retained-*`. FSR uniform and depth candidates were also rejected; `fsr-accepted-*` is a fresh ABBA on the final build, including its own native controls, with three seconds warmup and four seconds measurement. Each reported before/after pair is from the same JVM. Absolute FPS from different JVMs is not used as a paired result.

Headline FPS is calculated from frame intervals with profiling off. Per-pass measurements use a separate run with `POST /reflexium {"profile":"reset"}`, followed by `{"profile":"off"}`. CPU spans use `System.nanoTime`; Vulkan timestamp pairs are recorded once every 16 frames and read from a 16-slot ring only when available. No query uses `VK_QUERY_RESULT_WAIT_BIT`. Disabling profiling removes timestamp commands and result polling. Sampling with bottom-of-pipe timestamps can perturb GPU scheduling, so those values describe the instrumented pass boundaries, not a zero-overhead trace. Parent spans include their children and must not be added together.

Raw attempts and experimental arms are retained under [benchmarks/per-frame](benchmarks/per-frame). The original pre-change measurements and the historical moving-route table are context, not a paired comparison with a later JVM. The scripts do not poll status inside a timed interval.

<!-- measurements:start -->
## Measured results

Observed means of two samples per arm, with profiling off. These are not universal FPS improvements. The unchanged native control itself shifts +5.2% in the first matrix; individual candidate runs vary further. Small deltas are inconclusive. FSR retains the original preparation paths, and the visible-outline pose performs the same outline work in both arms, so its raw differences measure run variation rather than a claimed speedup.

| Mode | Before FPS | After FPS | Raw change |
| --- | ---: | ---: | ---: |
| native | 1940 | 2041.6 | 5.23% |
| CNN DLAA | 1076.2 | 1100.2 | 2.22% |
| CNN Quality | 1174.5 | 1236.3 | 5.26% |
| CNN Balanced | 1263.4 | 1329.6 | 5.24% |
| CNN Performance | 1372.1 | 1392.1 | 1.46% |
| CNN Ultra Performance | 1253 | 1275.1 | 1.76% |
| Transformer DLAA | 586.8 | 588.4 | 0.26% |
| Transformer Quality | 614.2 | 623.6 | 1.53% |
| Transformer Balanced | 631.9 | 637.8 | 0.94% |
| Transformer Performance | 636.9 | 648.7 | 1.86% |
| Transformer Ultra Performance | 648.4 | 655.4 | 1.07% |
| FSR Native AA | 753.9 | 753.6 | -0.05% |
| FSR Quality | 1057.5 | 1022.3 | -3.32% |
| FSR Balanced | 1069.6 | 1094.6 | 2.34% |
| FSR Performance | 1142.8 | 1199.6 | 4.97% |
| FSR Ultra Performance | 1246.5 | 1265.1 | 1.49% |

CNN DLAA improved only 2.2% in this capture, within the observed control variation; Transformer DLAA was essentially unchanged. A large DLAA cost reduction and a monotonic CNN Performance/Ultra Performance result were not achieved. The retained changes eliminate work, but do not remove the neural-network or Reflex driver scheduling floor.

## GPU pass breakdown

Microseconds, from profile-shipped.jsonl on the final build. World includes scene rendering and every listed child pass. Zero object draw time is expected in the frozen scene. Native has no Reflexium pass scope; its zero world value means unmeasured, not zero GPU work. Across upscaled rows, 97 to 226 sparse samples completed and zero query slots were skipped. Copy spans include the engine dependency after the copy. The selected pose has a visible outline; the separate spectator ABBA removes a 26.5 to 29.8 us depth pass when no outline exists.

| Mode | Depth copy | Camera MV | Object MV | Outline depth | Outline | Evaluate | Barriers | Opaque copy | Reactive | World total |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| native | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| CNN DLAA | 0.0 | 43.6 | 0.0 | 26.4 | 5.1 | 301.1 | 3.1 | 0.0 | 0.0 | 575.1 |
| CNN Quality | 0.0 | 23.2 | 0.0 | 18.5 | 5.4 | 272.3 | 3.2 | 0.0 | 0.0 | 495.6 |
| CNN Balanced | 0.0 | 19.6 | 0.0 | 19.2 | 7.9 | 271.9 | 3.0 | 0.0 | 0.0 | 501.8 |
| CNN Performance | 0.0 | 15.0 | 0.0 | 16.8 | 5.3 | 272.4 | 3.2 | 0.0 | 0.0 | 483.3 |
| CNN Ultra Performance | 0.0 | 9.1 | 0.0 | 13.7 | 4.9 | 326.5 | 3.1 | 0.0 | 0.0 | 524.3 |
| Transformer DLAA | 0.0 | 50.6 | 0.0 | 36.2 | 5.4 | 658.7 | 3.1 | 0.0 | 0.0 | 1016.7 |
| Transformer Quality | 0.0 | 37.1 | 0.0 | 20.4 | 5.7 | 667.5 | 3.3 | 0.0 | 0.0 | 951.0 |
| Transformer Balanced | 0.0 | 39.1 | 0.0 | 17.2 | 5.7 | 738.3 | 5.9 | 0.0 | 0.0 | 1006.0 |
| Transformer Performance | 0.0 | 16.1 | 0.0 | 15.8 | 5.7 | 760.3 | 3.4 | 0.0 | 0.0 | 993.0 |
| Transformer Ultra Performance | 0.0 | 16.2 | 0.0 | 13.5 | 5.6 | 749.2 | 3.1 | 0.0 | 0.0 | 952.7 |
| FSR Native AA | 15.3 | 67.0 | 0.0 | 28.7 | 5.2 | 518.4 | 5.3 | 14.6 | 25.4 | 881.0 |
| FSR Quality | 9.8 | 27.2 | 0.0 | 18.5 | 5.3 | 345.6 | 4.0 | 10.1 | 13.6 | 602.4 |
| FSR Balanced | 9.0 | 22.6 | 0.0 | 17.6 | 5.3 | 324.5 | 4.0 | 8.2 | 12.7 | 572.3 |
| FSR Performance | 7.9 | 18.1 | 0.0 | 17.6 | 5.2 | 304.8 | 4.0 | 7.2 | 12.9 | 545.2 |
| FSR Ultra Performance | 6.0 | 12.3 | 0.0 | 12.7 | 5.1 | 282.9 | 4.0 | 5.9 | 8.5 | 492.9 |

## CPU breakdown

Microseconds per frame or per pass invocation. Completion checks are summed per frame, including cached checks and polling; they are not a count of distinct blocking Vulkan waits. Submit includes queue submission and its completion wait. World includes Evaluate, so parent and child columns overlap. Engine backpressure remains under its two-submission limit. Query-pool reset is a few microseconds per sampled frame, and does not explain the large driver sleep. Full fields, including acquire, present and command-pool reset, are in cpu-breakdown.csv.

| Mode | Evaluate CPU | World CPU | Reflex driver | Reflex semaphore | Completion checks/frame | Submit total | Queue call |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| native | 0.0 | 0.0 | 22.6 | 5.1 | 91.2 | 139.5 | 77.8 |
| CNN DLAA | 50.1 | 388.2 | 54.5 | 4.4 | 181.2 | 215.4 | 60.5 |
| CNN Quality | 48.9 | 377.9 | 34.5 | 6.0 | 134.9 | 170.1 | 63.0 |
| CNN Balanced | 51.4 | 399.1 | 49.8 | 5.6 | 190.3 | 227.1 | 69.7 |
| CNN Performance | 52.6 | 413.0 | 49.5 | 5.5 | 172.3 | 214.6 | 73.4 |
| CNN Ultra Performance | 53.6 | 402.4 | 56.8 | 5.3 | 200.7 | 237.4 | 70.6 |
| Transformer DLAA | 48.2 | 393.7 | 827.9 | 5.0 | 212.6 | 247.9 | 50.9 |
| Transformer Quality | 51.7 | 396.1 | 673.9 | 4.8 | 232.1 | 264.1 | 51.3 |
| Transformer Balanced | 50.3 | 425.9 | 491.0 | 5.5 | 293.1 | 326.6 | 65.9 |
| Transformer Performance | 49.7 | 421.7 | 485.1 | 6.4 | 277.2 | 310.0 | 64.4 |
| Transformer Ultra Performance | 49.1 | 416.8 | 473.6 | 6.7 | 275.6 | 307.5 | 64.7 |
| FSR Native AA | 96.5 | 472.5 | 297.5 | 4.4 | 245.1 | 268.0 | 50.0 |
| FSR Quality | 90.9 | 437.0 | 48.8 | 4.4 | 176.7 | 208.7 | 60.5 |
| FSR Balanced | 97.8 | 472.7 | 43.0 | 5.1 | 221.5 | 251.3 | 64.1 |
| FSR Performance | 102.1 | 486.8 | 44.1 | 5.4 | 185.0 | 221.7 | 65.6 |
| FSR Ultra Performance | 98.2 | 483.6 | 33.7 | 5.3 | 162.9 | 203.1 | 67.6 |

## Native input-resolution controls and reference gap

Native controls use the same first JVM and pose, with each real window size measured forward then reversed. FSR final pairs use a later JVM, so the FSR reference is cross-session guidance, not another paired performance claim. Native rendering contains a substantial resolution-independent floor.

| Input size | Native ms | Native FPS |
| --- | ---: | ---: |
| 1920x1080 | 0.51 | 1961.2 |
| 1280x720 | 0.447 | 2235.3 |
| 1114x626 | 0.452 | 2214.5 |
| 1129x635 | 0.4435 | 2254.3 |
| 960x540 | 0.466 | 2145.7 |
| 640x360 | 0.391 | 2557.7 |

The requested additive reference is shown below. E/F uses 0.27 ms and J/K uses 0.64 ms from NVIDIA, with the input-size limitations stated above. FSR uses its measured dispatch GPU cost, not an NGX number. Values above 100% can occur because CPU and GPU work overlap and the published sample is not this renderer; this formula is not a hard physical upper bound. Residual is observed frame time minus the additive reference.

| Mode | Native input ms | Library ms | Reference FPS | After FPS | Reference attained | Residual ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| CNN DLAA | 0.51 | 0.27 | 1282.1 | 1100.2 | 85.8% | 0.129 |
| CNN Quality | 0.447 | 0.27 | 1394.7 | 1236.3 | 88.6% | 0.092 |
| CNN Balanced | 0.452 | 0.27 | 1385 | 1329.6 | 96.0% | 0.030 |
| CNN Performance | 0.466 | 0.27 | 1358.7 | 1392.1 | 102.5% | -0.018 |
| CNN Ultra Performance | 0.391 | 0.27 | 1512.9 | 1275.1 | 84.3% | 0.123 |
| Transformer DLAA | 0.51 | 0.64 | 869.6 | 588.4 | 67.7% | 0.550 |
| Transformer Quality | 0.447 | 0.64 | 920 | 623.6 | 67.8% | 0.517 |
| Transformer Balanced | 0.452 | 0.64 | 915.8 | 637.8 | 69.6% | 0.476 |
| Transformer Performance | 0.466 | 0.64 | 904.2 | 648.7 | 71.7% | 0.436 |
| Transformer Ultra Performance | 0.391 | 0.64 | 969.9 | 655.4 | 67.6% | 0.495 |
| FSR Native AA | 0.51 | 0.5184 | 972.4 | 753.6 | 77.5% | 0.299 |
| FSR Quality | 0.447 | 0.3456 | 1261.7 | 1022.3 | 81.0% | 0.186 |
| FSR Balanced | 0.4435 | 0.3245 | 1302.1 | 1094.6 | 84.1% | 0.146 |
| FSR Performance | 0.466 | 0.3048 | 1297.4 | 1199.6 | 92.5% | 0.063 |
| FSR Ultra Performance | 0.391 | 0.2829 | 1483.9 | 1265.1 | 85.3% | 0.117 |

<!-- measurements:end -->

## Findings and retained changes

1. The motion passes uploaded 80-byte and 208-byte uniform blocks into persistent buffers through `writeToBuffer`. Each upload recorded a copy and RenderPearl's global memory barrier. DLSS now uses aligned transient mapped uniform slices, whose storage lifetime follows the encoder's in-flight submission ring. DLAA also skips the two redundant global uniform updates at unchanged 1:1 dimensions. FSR retains its previous uniform path: the candidate did not show a repeatable FSR benefit, and Balanced had a state-sensitive FPS regression. The isolated depth retest remained positive; uniform behavior was not consistent enough to retain for FSR.
2. World depth was copied before vanilla cleared it for the hand. With DLSS or native-resolution FG active, vanilla now renders the hand into its existing separate hand-depth target. Camera and object velocity passes sample the retained scene depth directly. The motion pass still combines scene and hand depth for the upscaler and FG. Vanilla's hand-depth integration is retained when actual post effects require it. Shader packs retain their own hand-mask path. FSR retains its depth copy: repeated FSR candidate results did not establish a frame-time benefit, so the more invasive path was not retained there.
3. Outline depth reconstruction ran before checking whether an outline existed. It now runs only for a visible outline. Its full-resolution depth and outline rendering remain unchanged when a block is targeted. Installing Vitrail without enabling a pack also no longer clears an unused hand-mask image each frame.
4. CNN is preset E, numeric value 5, including Ultra Performance. The parallel research handoff calls it F in one paragraph, but the source and SDK enum establish E=5 and F=6. E's measured Ultra Performance GPU evaluation was slower than E Performance. F improved one paired FPS test while making DLAA slower; subsequent GPU measurements still put Ultra Performance near 0.32 ms, versus about 0.277 ms for E Performance. It also changed edge appearance materially. The F change was rejected. Explicit Transformer remains K; Auto remains the runtime's default. J and K share NVIDIA's published cost category, and J's documented extra flickering is not a free faster K path.
5. `waitForGpu()` previously called `submit()`, which waits for an older submission under RenderPearl's two-submission limit. It did not guarantee completion of the latest work before releasing NGX/FSR features. It now submits and waits for the device only at resource replacement or shutdown. There is no added device-idle wait in a steady-state frame.

NGX feature creation, its parameter map, Java FFM argument memory and Vulkan image views were already persistent. NVIDIA's resource-wrapper helper initializes stack descriptors; it does not allocate image views. Replacing those wrappers with a cache would not eliminate a Vulkan allocation. Object velocity rendering already skips drawing when no tracked vertices move. Geometry capture remains necessary to detect animation and preserve entity vectors.

## Reflex and the remaining floor

The initial GPU trace placed Transformer K evaluation around 0.65 ms at 1080p, close to NVIDIA's official 0.64 ms Performance-input reference. The final trace spans 0.66 to 0.76 ms across K qualities, so GPU runtime differences also contribute to the remaining gap. The CPU NGX/FFM span is about 0.05 ms. It is not a 1 ms render-thread NGX evaluation. Reflex On adds several hundred microseconds of sleep in this workload, reaching about 0.8 ms. An On/Off/Off/On comparison measured Transformer Performance at about 907 FPS with Reflex Off, versus 644 FPS in the settled Reflex On arm. The first On sample was faster, so the exact penalty is state-dependent. Native also changes under Reflex. Final results keep Reflex On.

The sampled `nvidia-smi` GPU percentage did not establish that NGX was CPU-bound. Library GPU compute, submission backpressure, CPU recording and Reflex scheduling all contribute. The finer span split locates most of the delay inside `vkLatencySleepNV`; the semaphore wait afterward is only about 5 to 9 microseconds. Reducing that wait's timeout would not eliminate the delay. Removing sleep would change the requested latency mode, so the Off experiment is diagnostic evidence only. The extension has no `lowLatencyMarkerOptimization` field; the similarly named legacy Reflex option cannot simply be copied into `VkLatencySleepModeInfoNV`.

A 20-second JFR recording of Transformer Performance showed native downcall samples dominating render-thread samples, with Java samples also identifying object-motion vertex writing and vanilla entity preparation. On this GraalVM build, the native downcall sample stacks did not retain enough Java caller information to distinguish driver calls reliably. The explicit CPU spans provide that attribution. The local recording is `run/reflexium-perf.jfr`; it is intentionally excluded from Git.

The two explicit evaluation memory barriers measured only about 1 to 3 microseconds each in the initial trace. Narrowing the black-box NGX/FSR dependency contract was not justified as a material gain. The depth copy and full-screen motion pass were larger. The 72-sample Ultra Performance Halton cycle follows NVIDIA's ratio-squared rule. Its tiny CPU calculation does not account for a roughly 45-microsecond increase inside E's GPU evaluation. Every steady-state quality run retained one reset after feature creation; the cycle did not trigger repeated resets. No phase-count reduction or periodic history reset is introduced. CNN Ultra Performance remains slower than Performance: the extra library cost and state-dependent CPU pacing exceed the small rendering savings in this scene. This target was not met; lowering quality or slowing Performance to force a monotonic table would not be an optimization.

## DLAA and sharpening

DLAA still evaluates the neural network at full 1920x1080 input and output. NVIDIA publishes no DLAA-specific timing table in the cited guide. Its F/K Performance-input timings are optimistic proxies for DLAA, not a promise that DLAA should cost 0.27/0.64 ms at full input resolution. Retained changes remove redundant work at 1.0 scale without reducing its input resolution or discarding motion vectors.

DLSS receives zero-initialized evaluation sharpness and runs through no RCAS or separate sharpening pass. The FSR sharpness setting is passed only to FSR's own dispatch. Native AA has zero mip bias. There is no extra DLSS sharpening slider to turn down. Visual checks compare frozen-scene captures with the same crop, excluding the animated hand and HUD; Laplacian variance alone is not a temporal-quality score.

Initial image checks: CNN DLAA luma PSNR 63.2 dB, CNN Performance 50.5 dB, Transformer DLAA 80.2 dB and FSR Quality 41.6 dB. The rejected CNN Ultra Performance E-to-F candidate had PSNR 39.8 dB and Laplacian variance 223.2 to 88.4. Inspection showed smoother leaf and block edges with F, a substantial network quality change. It is not the shipped mapping. Metrics are reproducible with `tools/compare-frame-images.ps1`; the original captures stay in `run/perf-images`. [Image metrics](benchmarks/per-frame/image-metrics.json) and [hashes](benchmarks/per-frame/image-hashes.json) are retained.

Final retained-path captures, with CNN E preserved, give Ultra Performance PSNR 43.6 dB and FSR Quality 63.2 dB. FSR Quality Laplacian variance is 205.78 before and 205.86 after. See [retained metrics](benchmarks/per-frame/image-metrics-retained.json). These captures and visual inspection did not identify a new sharpening or hand-depth artifact. Temporal jitter means a still comparison is not necessarily pixel-identical.

## Acceptance on the installed build

- `gradlew.bat build --console=plain` passed `dlssMathCheck`, `objectMotionCheck`, `frameProfilerCheck`, `test`, `check` and installation. The final JAR and Modrinth-installed JAR have the same SHA-256 in [build-verification.json](benchmarks/per-frame/build-verification.json). Native bridge sources and binaries were not changed.
- A moving, gravity-disabled pig with AI enabled produced vectors on visible surfaces. Server position changes were confirmed. The initial AI-disabled/occluded fixture was insufficient as visual evidence, even with nonzero geometry counters. The final overlay check finds 1,858 sampled colored pixels for DLSS and 3,184 for FSR, versus zero with object motion disabled and a static camera. [DLSS overlay](benchmarks/per-frame/motion-dlss.png), [FSR overlay](benchmarks/per-frame/motion-fsr.png), [camera-only control](benchmarks/per-frame/motion-camera-only.png), [numeric check](benchmarks/per-frame/motion-visual-check.json). A depth-occluded DLSS capture also leaves foreground leaves black while the visible pig surfaces have velocity.
- Twelve supported FG combinations passed: FSR 2x with Auto/FSR, DLSS 2x with Auto/DLSS, and MFG 3x through 6x with Auto/DLSS. Generated presents matched evaluations times the generated-frame count. Generated DLSS and FSR target readbacks contained the world and full-resolution HUD. The existing configuration rejects explicit mismatched backends and upscaler Off; the old acceptance script assumed those combinations were supported and was corrected without changing product policy. [Counters](benchmarks/per-frame/frame-generation-check.json). This is functional integration evidence, not physical scanout certification.
- Vitrail 0.12.0-beta and Complementary Reimagined r5.9.3 passed DLSS Quality and FSR Quality after world rejoin, including a scripted camera turn. Both advanced evaluation counts, reported a valid pack hand mask and no upscaler error. The short acceptance intervals measured 388.9 and 365.3 base FPS respectively; these are smoke timings, not before/after shader benchmarks. Its hand-copy/mask hooks ran four times per frame, totaling about 42 us GPU for DLSS and 38 us for FSR. [Status and intervals](benchmarks/per-frame/vitrail-check.json), [shader pass profile](benchmarks/per-frame/vitrail-profile.json). Generated-frame and shader screenshots remain under `run/perf-images`.

The client remains running on the secondary monitor with the original pack enabled, Reflex On, Auto DLSS model, Ultra Performance quality, FG off, object motion on and debug overlays/profiling off. The temporary tagged pigs were removed. The harness launches reported `focus_unchanged=true`.

Final-session note: the long-running client exited after the measurements without a new Minecraft crash report or Windows Java fault event. Its cause was not established. A fresh harness session was launched and checked for advancing evaluations, a valid Vitrail hand mask and inactive profiling; see [final-session.json](benchmarks/per-frame/final-session.json). Separate watchdog reports during earlier deliberate quits were `Client shutdown from post-main`, with `DestroyJavaVM` and an HTTP dispatcher still alive and the render thread already gone. Those indicate a debug HTTP-server shutdown lifetime issue, rather than evidence of a GPU wait. The external harness was not modified in this task.

## Official reference and target calculation

[NVIDIA's DLSS guide, revision 310.6.0, pages 5 to 8](https://github.com/NVIDIA/DLSS/blob/main/doc/DLSS_Programming_Guide_Release.pdf) gives RTX 5070 library-only 1080p Performance-input costs of 0.27 ms for E/F and 0.64 ms for J/K. It does not publish costs for every input quality or DLAA. See [the research handoff](PERFORMANCE_RESEARCH.md) for the full table. Lower-resolution native controls replace a pixel-count estimate of render cost.

The requested reference is `1000 / (measured native ms at the mode's render resolution + official NGX ms)`. It is a reference envelope, since CPU/GPU work can overlap and the official library sample has different rendering, scheduling and input-size conditions. For Quality, Balanced, Ultra Performance and DLAA the official cost is an explicitly optimistic proxy. FSR has no corresponding NVIDIA timing; use its separately measured dispatch GPU cost and label that estimate accordingly. Do not label these estimates as an official per-mode guarantee.

## Reproducing

Launch with `mc-agent-harness/tools/launch.ps1`, not the MCP launch call. Use `-RunDirectory C:\Users\Ryan\source\repos\Reflexium\run -Width 1920 -Height 1080 -Monitor secondary`, join the test world, disable the Vitrail pack and set Reflex On, VSync off, unlimited FPS.

```powershell
.\tools\measure-per-frame.ps1 -Label example -Paths optimized
.\tools\measure-per-frame.ps1 -Label profile-example -Paths optimized -Passes 1 -Profile
```

The debug-only `perf_uniforms` and `perf_depth` switches select retained versus legacy paths without restarting the JVM. `dlss_preset=-1` restores the model's normal mapping; 5, 6, 10 and 11 are experimental explicit hints. `reflex_mode=on|off|on_boost` supports diagnostic pairs. These overrides are runtime-only except the existing persisted Reflex mode setting. They are not new player-facing settings.

Primary sources: [Vulkan timeline semaphore polling](https://docs.vulkan.org/refpages/latest/refpages/source/vkGetSemaphoreCounterValue.html), [Vulkan latency sleep contract](https://docs.vulkan.org/refpages/latest/refpages/source/VkLatencySleepModeInfoNV.html), [NVIDIA's Reflex performance FAQ](https://developer.nvidia.com/rtx/streamline/get-started), and the installed NGX SDK headers. Existing-mod research: [Salt's Anti-Aliasing](https://modrinth.com/mod/salts-anti-aliasing) and [its source](https://github.com/Salt-and-Fries/Salt-s-Anti-Aliasing). Its scene-only rendering and HUD separation match the established integration strategy; no new dependency was needed.

## Benchmark Mod continuation

The historical ABBA comparing `3a8e12d` with `ac1e339` found MFG 6x +0.82% and FSR FG +1.25%, within noise. MFG 4x was +4.94%, alongside a +3.79% native control shift. There is no evidence of a code regression in FG. The earlier matrix drops were session drift. These [bisect rows](benchmarks/bm/bisect-3a8e12d-vs-ac1e339-A.jsonl) used the former custom scene and are historical diagnostics, not acceptance under the current scene rule.

The inherited [partial marker experiment](benchmarks/bm/explore-reflex-marker-mode-A-partial.jsonl) measured Transformer Performance at 734 FPS On, 748 with submission-end moved before engine backpressure, 737 with Boost and 978 Off. It was interrupted and also used the old custom scene. It supports investigation of sleep, but does not certify a marker fix. The marker override and its surface/submission hooks were discarded. The shipped marker order remains unchanged.

A previously completed [standard-scene On/Off diagnostic](benchmarks/bm/standard-reflex-cost-summary.json) averaged Transformer Performance 633 On / 893 Off and CNN Performance 1033 On / 1276 Off. CNN being flat in the former scene does not generalize to this route.

One new diagnostic set used the existing frozen FPS Lab `benchmark-route`, render distance 16, a 48-second route, one measured pass per capture, five-second warmups, BM's per-JVM conditioning, one JVM and forward/reverse order. A five-second sparse GPU/CPU profile and one driver-report query were taken during each capture. These FPS values include diagnostic instrumentation and are not final jar acceptance. The secondary-monitor launch preserved foreground focus, all six captures completed, and all 63 protected world files retained their hashes. [Protocol, raw-capture hashes and command](benchmarks/bm/reflex-attribution/protocol.json), [rows](benchmarks/bm/reflex-attribution/runs.jsonl), [summary](benchmarks/bm/reflex-attribution/summary.json).

| Transformer Performance variant | Forward FPS | Reverse FPS | Mean FPS |
| --- | ---: | ---: | ---: |
| Reflex On, explicit submission IDs | 641.5 | 634.3 | 637.9 |
| Reflex Off, explicit submission IDs | 913.9 | 910.2 | 912.1 |
| Reflex On, implicit attribution | 649.9 | 641.3 | 645.6 |

On was 30.1% slower than Off. Clearing explicit queue IDs changed On throughput by only +1.21%; it did not solve the penalty. The profiled On snapshots spent about 1.03 to 1.11 ms in `vkLatencySleepNV`, versus about 4 to 5 us in the following semaphore wait. NGX evaluation remained roughly 0.63 to 0.71 ms GPU and about 0.05 ms CPU. Status showed `Reflex: On` without a cap, so the application minimum frame interval was zero. There is one sleep hook before SDL polling per outer frame. RenderPearl's steady-state submit still waits for the preceding submission before reusing its command pool and transient storage; deleting that wait would violate resource lifetime.

NVIDIA's [Reflex checklist and FAQ](https://developer.nvidia.com/rtx/streamline/get-started) expects at most about 4% FPS impact for On and calls out duplicate sleep and competing pacing. The current penalty exceeds that guidance. The [Vulkan marker contract](https://docs.vulkan.org/refpages/latest/refpages/source/VkLatencyMarkerNV.html) places simulation after sleep, input immediately before sampling, render markers around CPU rendering/submission and present markers around the present call. NGX recording is already inside the render interval. Moving the submission-end marker alone and changing Boost did not establish a fix. The delay is localized to the Reflex driver call; why its scheduling budget is excessive remains unresolved. A correlated CPU/GPU API trace is needed before changing pacing or synchronization. No arbitrary sleep bypass, quality reduction or default Reflex Off workaround was shipped.

The explicit-ID timing snapshots contain `driver_start > driver_end` in every returned report, while the two implicit-ID snapshots do not. A read-only adapter probe confirmed `VK_NV_low_latency2` revision 3, which supports explicit attribution. This is a reproducible timing inconsistency, not proof that clearing IDs improves real input latency. GPU timestamps, marker reports and FrameView/Nsight evidence must be correlated before trusting a latency comparison. The snapshots remain in [the evidence directory](benchmarks/bm/reflex-attribution). BM skipped visible-section telemetry because Sodium was installed, even though this run used Vulkan. These captures cannot establish equal section counts from that telemetry.

The F3 latency calculation previously stopped at CPU `presentEndTimeUs`, before GPU completion. It now reports input sample (or simulation start fallback) to `gpuRenderEndTimeUs`, selects a GPU-complete report, and rejects inverted driver timestamps. The label is `Reflex render latency`; it excludes display scanout and physical input-device latency. `reflexTimingCheck` verifies GPU completion, fallback, incomplete reports and the observed inverted timestamps. This is a metric correction with no measured throughput gain.

Retained inherited edits: opt-in `fg_evaluate` and `fg_gui` profiler spans, with 128 timestamp slots per sample to accommodate MFG; the raw `reflex_timings` endpoint; and benchmark runtime overrides. The `reflex_submission=0|1` diagnostic clears/restores queue attribution, defaults to the existing explicit-ID behavior, and does not persist. FG GUI replay, outline rendering and Vitrail TAA/grain behavior retain their existing paths.

DLAA audit: explicit Transformer is K=11, CNN is E=5, and Auto leaves per-quality selection to NGX. Creation flags remain low-resolution motion vectors and inverted depth; HDR, auto exposure and sharpening flags are unset. Evaluation provides no exposure texture, pre-exposure and exposure scale are 1, and sharpness is zero. DLAA mip bias is zero. NVIDIA's Streamline path [uses auto exposure when no exposure texture is provided](https://github.com/NVIDIA-RTX/Streamline/blob/main/docs/ProgrammingGuideDLSS.md#40-tag-all-required-resources), but this raw-NGX LDR integration needs a paired image check before changing exposure semantics. No exposure or preset change was shipped. The earlier measured E Ultra Performance evaluation (326.5 us) exceeded E Performance (272.4 us); its preset-F alternative changed edges and was rejected. Neither the CNN monotonic-quality target nor a DLAA cost/appearance improvement is achieved.

The final reduced-set, uninstrumented ABBA against `ac1e339` remains for the verification agent. It must use a fresh label, defaults of three BM passes and ten-second warmups, and `-IncludeFG` because FG profiling code changed. The exact command is in [the benchmark README](benchmarks/README.md). Do not treat this diagnostic set as that final comparison.

### Pinned candidate quick check, 14 October 2026

A clean `d057dbf` worktree built and passed `build bmDriverJar` with its install directory redirected away from the active Modrinth profile. Its jar was pinned as `build/perf-jars/d057dbf.jar`, SHA-256 `36E7F13025848664376B9FF06FE1D129E574097BCAE85BDCB1F1F7A7FD587085`. A `-Quick` Benchmark Mod run then compared it with `ac1e339` on the frozen shaderless scene. Each arm used one fresh JVM, one BM pass and a five-second warmup, with no conditioning capture. [Protocol, six capture hashes, rows, world hashes and summary](benchmarks/bm/quick-perf-d057/) are retained. Both launches preserved desktop focus; all six captures had the same route hash, and the 63 protected world files were unchanged.

| Mode | Old FPS | Candidate FPS | Change | 1% low change |
| --- | ---: | ---: | ---: | ---: |
| Native | 1830.7 | 1835.0 | +0.24% | +1.77% |
| Transformer Performance | 638.2 | 644.7 | +1.02% | -3.87% |
| DLSS FG 2x, real frames | 318.7 | 319.1 | +0.12% | -2.50% |

All throughput changes are within the quick run's 2.4% single-pair noise band; its 1% low band is 16.8%. This is a regression screen, not evidence of an optimization. The full reduced-set ABBA remains the verification handoff. No performance code change was retained from this quick check.

## VK_NV_low_latency2 sleep audit, 14 October 2026

The frozen `benchmark-route` was captured in one JVM per set, forward and reverse, with one BM pass, five seconds of warmup and `-Diagnostics`. All sets used `reflex_submission=0` so the driver reports avoided the previously observed inverted explicit-attribution timestamps. The second set changed only the benchmark process environment to `DISABLE_VULKAN_OBS_CAPTURE=1`, the disable variable in the installed OBS Vulkan layer manifest. The third used `DISABLE_LAYER_NV_PRESENT_1=1` to isolate NVIDIA's presentation layer; a separate `vulkaninfo` probe still reported `VK_NV_low_latency2` revision 3 with that layer disabled. The copied world's hashes matched before and after all sets, and the launcher reported unchanged foreground focus. These instrumented FPS values are diagnostic, not final uninstrumented acceptance. [Capture summaries, profiles, driver reports, world hashes and raw BM capture hashes](benchmarks/bm/reflex-sleep-audit/).

| Set | Reflex On FPS | Reflex Off FPS | On FPS cost | On render latency | Off render latency | On driver sleep |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Normal process environment | 655.5 | 910.5 | 28.0% | 1.42 ms | 2.10 ms | 0.94 to 1.01 ms |
| OBS Vulkan hook disabled for benchmark client | 645.1 | 907.6 | 28.9% | 1.50 ms | 2.08 ms | 1.02 to 1.10 ms |
| NVIDIA presentation layer disabled for benchmark client | 652.6 | 913.0 | 28.5% | 1.41 ms | 2.07 ms | 0.97 to 1.07 ms |

Render latency is the mean of two driver-report snapshots per mode, each with 60 to 64 valid frames, measured from input sample to GPU render completion. This is the same scope as the F3 `Reflex render latency` calculation, not display scanout or physical input latency. Neither process-scoped layer experiment improved the On/Off ratio beyond the established noise band. The earlier explicit-attribution diagnostic measured 637.9 On and 912.1 Off, consistent with a large penalty.

The integration audit found no application frame cap: `minimumIntervalUs` is zero with unlimited FPS and VSync off. `lowLatencyMode` is true for On; `vkSetLatencySleepModeNV` is sent when the mode or cap changes and after swapchain creation. NVIDIA's [Streamline Reflex guide](https://github.com/NVIDIA-RTX/Streamline/blob/main/docs/ProgrammingGuideReflex.md) says unchanged options need no per-frame resend. The single pre-input sleep per outer frame increments a device-lifetime timeline value and waits for that exact value; the measured semaphore wait was 4 to 5 us, with no observed timeout. NVIDIA's [Reflex SDK integration guide](https://github.com/NVIDIA-RTX/REFLEX/blob/main/NVIDIA%20Reflex%20SDK%20Integration%20Guide.pdf) and [Streamline's Vulkan implementation](https://github.com/NVIDIA-RTX/Streamline/blob/main/source/platforms/sl.chi/vknvll2.cpp) use the same sleep, incremented timeline value and host wait sequence. Streamline also calls sleep in Off mode. Reflexium currently skips it when Off and uncapped; that difference cannot explain the On-mode driver-call delay.

The simulation, render-submit and present markers use the same `frameId`. `RENDERSUBMIT_END` follows NGX evaluation. The RenderPearl `vkQueueSubmit2KHR` hook tags every stage with the current frame ID, and the real present chains the same monotonically increasing number through `VkPresentIdKHR`. NGX Super Resolution records into RenderPearl's supplied command buffer, with no separate application-owned submit in its steady-state bridge. The sampled engine submit and sleep counts match one per frame. The [Vulkan attribution rules](https://docs.vulkan.org/spec/latest/chapters/VK_KHR_surface/wsi.html#low-latency-frame-attribution) explicitly say `VkLatencySubmissionPresentIdNV` is independent of `VkPresentIdKHR`; matching numbers are a convention, not an attribution requirement. An out-of-band queue designation on the graphics queue would exclude or redirect the actual render submission. This audit did not trace hidden submissions inside the proprietary NGX driver library, so it cannot exclude them categorically. FG's generated presents use out-of-band present markers, but FG was disabled for these captures.

In the normal set, Off spent about 0.76 ms per frame in RenderPearl submit including its required resource-lifetime wait. On reduced that span to 0.18 to 0.19 ms but spent 0.94 to 1.01 ms inside `vkLatencySleepNV` before simulation. The net additional wait closely matches the measured 0.43 ms frame-time increase. The [Vulkan specification](https://docs.vulkan.org/refpages/latest/refpages/source/vkLatencySleepNV.html) says `vkLatencySleepNV` returns immediately and the semaphore wait delays host work; [NVIDIA confirmed that expectation](https://forums.developer.nvidia.com/t/vk-nv-low-latency2-latency-sleep-semaphore-appears-to-under-sleep-by-1000x/366452) in a separate report. This driver stack instead spent the time in the driver call. Disabling the OBS hook or NVIDIA presentation layer did not change that behavior. Reflex On reduced reported render latency by about 0.68 ms, but its throughput remains roughly 28% below Off and fails NVIDIA's approximately 4% QA criterion. The driver scheduling reason is not proven to be expected behavior. No safe throughput fix emerged: bypassing sleep would turn Reflex off, and excluding the graphics queue would hide real rendering. A correlated Vulkan API and GPU trace is still needed to isolate the driver scheduling decision.

## Reflex isolation and 800 us interval check, 14 October 2026

Smart Reflex is disabled by default on this investigation branch. Every new measurement also explicitly sets `smart_reflex=0`; a pacing pause is not an accepted optimization. The test jar is SHA-256 `1F32E0EB5873B01C662E7457FC305A1FCEB6B1FA01BC7935FFCFF31EBBDB269C`.

The inherited `build/trace-tools/api-reflex-route.txt` is a detailed LunarG API dump of 50 sampled frames, numbered 10000 through 59000 at intervals of 1000. Each sampled frame has one `vkQueueSubmit2KHR` call with two `VkSubmitInfo2` batches, both tagged with the simulation ID, followed by one present carrying that same ID in `VkPresentIdKHR`. All simulation/render/present-start marker IDs match. There are no extra loader-visible NGX submissions in these samples. NGX receives the application's Vulkan resolver functions and records its SR work into supplied command buffers. Driver-private submissions remain outside this observation. [Trace summary and retained queue/pNext packets](benchmarks/bm/reflex-investigation/trace-summary.json).

The separate startup dump confirms `latencyModeEnable=VK_TRUE`, Immediate presentation, `minImageCount=8`, eight actual images, and a successful `vkSetLatencySleepModeNV` with zero minimum interval. A startup submit before the first simulation marker is untagged. That is outside an active Reflex frame. LunarG increments its own frame counter at present, so `PRESENT_END` for frame N shares the dump-frame number of `SIMULATION_START` for N+1. This counter boundary is not an off-by-one in the application IDs. The creation hook resets the applied mode/cap and reapplies them on every normal swapchain recreation.

A fresh dump of 32 consecutive active frames (LunarG 12000 through 12031, application IDs 11921 through 11952) confirms the same result continuously: 32 sleeps, 32 submit calls containing 64 tagged batches, and 32 real presents. Every active batch and marker matches its frame's present ID, and no additional loader-visible queue submission appears. The retained packets and capture environment are in the trace summary above. API logging perturbs pacing; that capture's FPS is excluded from performance conclusions.

The old `reflex-images-20261014-A` measurements overlap the other agent's benchmark campaign and are excluded from performance conclusions. Nsight Systems is not installed. New GPU work waits until both Minecraft clients and benchmark parent processes have exited, including gaps between launches.

The runtime-only `reflex_interval_us` debug override adds a minimum interval while retaining full Reflex sleep and any slower user/VRR cap. `-1` restores the normal setting. The 800 us hypothesis is that requesting an explicit 1250 FPS ceiling selects a better driver pacing path even when the actual rendered rate is below that ceiling. Status must show the requested interval, `reflex_sleeping=true` and `reflex_pacing_paused=false`.

The native [reflex-reference.cpp](../tools/reflex-reference.cpp) isolates the driver without Minecraft, NGX, SDL or Java. It creates a non-activating secondary-monitor Win32 window, an Immediate three-image latency-enabled swapchain and a timeline sleep semaphore. It calibrates device-local buffer fills to roughly 1.1 ms GPU work, adds 350 us of simulated CPU work after sleep/input markers, and compares Off, On/0 us and On/800 us. It can use one submission batch or RenderPearl's two-batch layout. Its timestamps and driver reports are diagnostics for this transfer-heavy workload, not a game latency or mouse-feel acceptance test.

### Quick interval result

One forward-only `-Quick -Explore -Diagnostics` JVM captured the frozen route with five-second warmups and one measured pass. It used implicit submission attribution to obtain valid latency reports. All six captures preserved focus, produced no FG presents, and retained all 63 protected world hashes. The initial CNN Off capture has the known possible cold-start bias. These instrumented, single-capture values are rough diagnostics, not final acceptance. [Command, raw capture hashes, profiles, status and driver reports](benchmarks/bm/reflex-investigation/interval-800/protocol.json).

| Model | Reflex | Minimum interval | FPS | Driver call | Semaphore wait | Input to GPU end |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Transformer Performance | Off | 0 us | 894.5 | not called | not called | 2.167 ms |
| Transformer Performance | On | 0 us | 642.6 | 1.038 ms | 4.75 us | 1.437 ms |
| Transformer Performance | On | 800 us | 619.8 | 1.159 ms | 4.97 us | 1.411 ms |
| CNN Performance | Off | 0 us | 1259.4 | not called | not called | 1.491 ms |
| CNN Performance | On | 0 us | 1040.7 | 0.312 ms | 3.89 us | 1.196 ms |
| CNN Performance | On | 800 us | 620.4 | 1.221 ms | 4.80 us | 0.980 ms |

Each latency number averages 64 complete, consistently ordered reports from the matching profile snapshot. It excludes scanout and physical device latency. Status verified full sleep and no pause in every On capture. The 800 us override reduced Transformer FPS by 3.5% and CNN FPS by 40.4% against 0 us. Both 800 us variants ran near 620 FPS, roughly a 1.61 ms frame interval despite the requested 0.8 ms minimum. This does not establish a universal internal Reflex cap, but it rules out this floor as a fix on the tested stack. The earlier approximately flat CNN comparison was not reproduced here.

### Isolated driver reproduction

NVIDIA 617.42 on the RTX 5070 reproduces both problems outside Minecraft. [Native console output, raw timing reports and commands](benchmarks/bm/reflex-investigation/native-reference/protocol.json).

| Reference variant | Off FPS | On/0 FPS | On driver call | On GPU workload | Inverted explicit reports |
| --- | ---: | ---: | ---: | ---: | ---: |
| Implicit attribution, one batch | 886.7 | 554.4 | 1.315 ms | 1.025 ms | 0/64 |
| Explicit attribution, one batch | 886.6 | 538.9 | 1.364 ms | 1.019 ms | 64/64 |
| Explicit attribution, two batches | 886.0 | 522.1 | 1.417 ms | 1.024 ms | 64/64 |

The implicit one-batch reference has no application ID ambiguity. On cuts throughput by 37.5% while reducing its input-to-GPU-end report from 4.350 to 1.459 ms. Its Off `vkLatencySleepNV` calls take about 6 us, and its On semaphore waits about 5 us. Thus neither NGX nor RenderPearl's submission layout is necessary for the blocking-call penalty. The native explicit variants reproduce the inverted driver timestamps in Off and On, with both batch layouts. This rules out Java struct bindings and a Minecraft-only present-ID mismatch as necessary causes of that defect. Invalid explicit latency values printed as zero by the reference mean unavailable, not zero latency.

A lighter implicit, one-batch workload with 100 us CPU work and about 0.24 ms GPU work measured 3841.1 FPS Off, 3250.4 On/0, and 650.1 On/800. At 800 us, the driver call averaged 1.304 ms, while the semaphore wait remained 5.08 us. This independently reproduces the unexpectedly low ceiling. The OS's current global timer resolution was 500 us, measured with `NtQueryTimerResolution`; a coarse global 15.625 ms timer is not the explanation. That query alone does not establish each process's timer policy.

The native explicit two-batch test also completed under Khronos core validation and a separate run with synchronization validation (`VK_LAYER_VALIDATE_SYNC=1`). Both exited zero with empty warning/error callback logs, while reproducing the inverted reports and On penalty. Validation timings are retained separately and are not the headline reference measurements.

The strongest remaining cause is excessive pacing inside this driver/OS implementation of `VK_NV_low_latency2`, alongside an independently reproducible explicit-attribution reporting defect. This is stronger than the earlier integration-only hypothesis, but it does not identify the driver's internal scheduling error. No safe production throughput fix is demonstrated, and the requested 5% target remains unmet. The default minimum interval stays zero; the 800 us knob is diagnostic only. Reflex remains fully active when On.

Build the reference from an x64 Visual Studio 2022 developer prompt at the repository root, using the already installed local Vulkan SDK:

```bat
cl /nologo /O2 /EHsc /std:c++17 /Ibuild\trace-tools\sdk\Include tools\reflex-reference.cpp /Fobuild\trace-tools\reflex-reference.obj /Febuild\trace-tools\reflex-reference.exe /link /NOLOGO /LIBPATH:build\trace-tools\sdk\Lib vulkan-1.lib user32.lib
```

Arguments are explicit IDs (`0|1`), CPU work in us, split batches (`0|1`), and target GPU work in us (default 1100). The first phase calibrates, then each variant has two seconds to settle and four seconds of measurement. Launch its console hidden; its Win32 window stays visible on the secondary monitor without activation:

```powershell
Start-Process build\trace-tools\reflex-reference.exe -ArgumentList '0','350','0','1100' -WindowStyle Hidden -Wait -RedirectStandardOutput build\trace-tools\reference.txt -RedirectStandardError build\trace-tools\reference-errors.txt
```

### The apparent 306 FPS shader and 317 FPS FG ceilings

The inherited `quick-smartreflex-B` arms are distinct captures with different SHA-256 hashes and different frame intervals. Both happen to contain 13777 frames, but their full-second rates range from 258 to 367 FPS and 260 to 370 FPS. They average 96.9% and 97.7% GPU utilization. Thus the matching average is not a hard 306 FPS cap. A new two-capture `-Quick -Explore -Diagnostics -Set B` check measured **308.0 FPS On and 307.8 Off**, with 97.8% and 98.4% GPU use. World GPU spans are 3.15 and 3.09 ms, present calls 33 and 36 us, and acquire calls about 4 to 5 us. The cause is the Complementary/Vitrail shader workload saturating the GPU. On correctly returns from `vkLatencySleepNV` in 4.67 us and waits about 1.50 ms on the semaphore in this workload. Turning Reflex off does not raise throughput. The installed Vitrail bytecode contains no reference to the vanilla FPS limiter or Vulkan swapchain/present-mode setters; its `ChainPresent` is a fullscreen shader draw. [Capture hashes, full-second ranges and diagnostic profiles](benchmarks/bm/reflex-investigation/ceilings/measurements.json).

The shaderless FG2 result has a separate workload/completion bottleneck. Its inherited arms average 318.0 and 316.8 real FPS, with full-second rates up to 330 and 327, at about 80% GPU use. One new FG2 diagnostic measured 311.2 real FPS, with full seconds from 290 to 327. World rendering costs 0.915 ms GPU and FG evaluation adds 1.434 ms. There are two renderer submits per real frame: their CPU spans total 2.526 ms, including about **2.435 ms in resource-completion waits** (9587 wait calls / 1579 real frames, averaging 401 us per call). The extra submit presents the real image after the generated image and encounters RenderPearl's resource-lifetime synchronization again. These spans include one another and must not be added as independent costs. Reflex sleep totals only **25.8 us per real frame**. The two presents average 29 us each and two acquires about 4 us each. This localizes the cost to FG work and renderer submission/completion serialization, rather than a 317 FPS Reflex or out-of-band presentation cap. The base rate counts real frames; generated/displayed cadence is a separate metric.

Both diagnostic campaigns used the same frozen route, preserved focus and protected world hashes, and ran without another game or benchmark parent. No new pacing option or FPS-cap workaround was added for either result.

## Reflex oversleep scales with how little CPU work a frame has (2026-10-14)

Native reference (`tools/reflex-reference.cpp`, Streamline sequence, 1100 us GPU target, RTX 5070, driver 617.42):

| Simulated CPU work | Reflex Off | Reflex On | `vkLatencySleepNV` median |
|---|---|---|---|
| 50 us | 942 FPS | 604 FPS | 1338 us |
| 350 us | 924 FPS | 618 FPS | 1056 us |
| 700 us | 940 FPS | 785 FPS | 6 us |

The driver keeps the GPU queue empty by sleeping roughly a whole GPU frame when the CPU part of a frame is
tiny, instead of overlapping the next frame's CPU work with the current GPU frame. The cost is therefore
largest when CPU time is small and the GPU frame is around 1 ms, which is exactly Minecraft with DLSS
Transformer at 650 to 900 FPS. At ordinary GPU-bound rates it disappears: Vitrail Set B measured 308 FPS On
against 307.8 Off. Raising Windows timer resolution (1 ms or 0.5 ms) did not change it.

This matches the hard ceiling of about 1250 FPS players see with Reflex On in Valorant and Fortnite: a fixed
driver pacing cost of roughly 0.8 ms per frame. Reflexium's behavior with Reflex On is the same as those games.

## Preventing overlapping measurements

The BM runner now refuses to start while another Minecraft client, BM/FPS Lab parent process or native Reflex reference is active, and checks again before each fresh JVM. This includes other worktrees and the gap between another campaign's launches. The check ignores its own shell ancestors and Gradle daemons. Its offline check covers client, parent, native-reference and allowed-process cases; a live invocation correctly refused the concurrent BM campaign without creating a new client. It does not close another agent's process or reserve the GPU against a later launch. This changes measurement reliability, with no claimed rendering speedup.

## Rejected FG submit batching experiment, 14 October 2026

The isolated `perf/fg-submit-batch` experiment recorded every generated/real swapchain blit before the ordinary encoder submit, then issued the same metered presents in order. It retained per-image acquire waits, present signals and RenderPearl's command-pool/transient-memory completion wait. It used the sequential path when the actual swapchain could not safely supply the entire batch. The [WSI acquisition limit](https://docs.vulkan.org/spec/latest/chapters/VK_KHR_surface/wsi.html#vkAcquireNextImageKHR) bounds the number held simultaneously to `imageCount - surface.minImageCount + 1`.

One completed, uninstrumented `-Quick` pair used the unchanged frozen 48-second BM route, one pass per mode, five-second warmups, no conditioning and two fresh JVMs. Both launches preserved foreground focus and the copied world's hashes. Baseline runtime SHA-256 was `1F32E0EB5873B01C662E7457FC305A1FCEB6B1FA01BC7935FFCFF31EBBDB269C`; experimental runtime was `7F01BEE11E842BF2E5B41FDB9C054C04943038F65103C0AF2AF43D8CD504ABEB`. [Rows](benchmarks/bm/fg-batch/runs.jsonl), [summary](benchmarks/bm/fg-batch/summary.json), [protocol](benchmarks/bm/fg-batch/protocol.json), and [raw capture paths, hashes and BM summaries](benchmarks/bm/fg-batch/captures.json) are retained.

| Mode | Baseline real FPS | Candidate real FPS | Raw change |
| --- | ---: | ---: | ---: |
| Native | 1841.0 | 1515.1 | -17.7% |
| DLSS FG 2x | 302.0 | 308.5 | +2.2% |
| DLSS MFG 4x | 159.2 | 207.5 | +30.3% |

The unchanged native path drifted well beyond the 2.4% single-pair FPS noise band. FG 2x's change was within that band. The apparent MFG gain is therefore unconfirmed, and the batching runtime change was removed. This is not a completed ABBA or a measured shipped improvement. The rejected patch and experimental jars remain only under the ignored worktree build directory. No preset, exposure, sharpening, outline or Vitrail TAA change was retained.

The first attempt ended after a completed baseline MFG capture when the read-only `/reflexium` response timed out. Its partial artifacts remain at `build/perf-batch/build/bm-bench/fg-batch-quick1-A` and are excluded. The runner now retries that read once on `TimeoutException`, while setting mutations still fail immediately. A requested reverse pair was refused because another agent's quality-validation client had launched after this pair ended. No other client was stopped to obtain measurements. A trustworthy gain still requires a repeat with stable controls and exclusive access; the original cross-session FG regression's precise cause remains unproven.
