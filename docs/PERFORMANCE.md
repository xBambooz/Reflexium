# Upscaling performance

Measured on 14 October 2026, RTX 5070, NVIDIA 617.42, Minecraft 26.3, GraalVM Java 25, Vulkan, 1920x1080, VSync off, unlimited FPS and Reflex On. Frame generation is off unless a test explicitly enables it. This is a shaderless fixed-pose investigation, separate from the older moving-route Set A and shader Set B results.

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

## Official reference and target calculation

[NVIDIA's DLSS guide, revision 310.6.0, pages 5 to 8](https://github.com/NVIDIA/DLSS/blob/main/doc/DLSS_Programming_Guide_Release.pdf) gives RTX 5070 library-only 1080p Performance-input costs of 0.27 ms for E/F and 0.64 ms for J/K. It does not publish costs for every input quality or DLAA. See [the research handoff](PERFORMANCE_RESEARCH.md) for the full table. Lower-resolution native controls replace a pixel-count estimate of render cost.

The requested reference is `1000 / (measured native ms at the mode's render resolution + official NGX ms)`. It is a reference envelope, since CPU/GPU work can overlap and the official library sample has different rendering, scheduling and input-size conditions. For Quality, Balanced, Ultra Performance and DLAA the official cost is an explicitly optimistic proxy. FSR has no corresponding NVIDIA timing; use its separately measured dispatch GPU cost and label that estimate accordingly. Do not label these estimates as an official per-mode guarantee.

## Reproducing

Launch with `mc-agent-harness/tools/launch.ps1`, not the MCP launch call. Use `-RunDirectory C:\Users\Ryan\source\repos\Reflexium\run -Width 1920 -Height 1080 -Monitor secondary`, join the test world, disable the Vitrail pack and set Reflex On, VSync off, unlimited FPS.

```powershell
.\tools\measure-per-frame.ps1 -Label example -Paths optimized
.\tools\measure-per-frame.ps1 -Label profile-example -Paths optimized -Passes 1 -Profile
```

The debug-only `perf_uniforms`, `perf_depth`, `perf_outline` switches select retained versus legacy paths without restarting the JVM. `dlss_preset=-1` restores the model's normal mapping; 5, 6, 10 and 11 are experimental explicit hints. `reflex_mode=on|off|on_boost` supports diagnostic pairs. These overrides are runtime-only except the existing persisted Reflex mode setting. They are not new player-facing settings.

Primary sources: [Vulkan timeline semaphore polling](https://docs.vulkan.org/refpages/latest/refpages/source/vkGetSemaphoreCounterValue.html), [Vulkan latency sleep contract](https://docs.vulkan.org/refpages/latest/refpages/source/VkLatencySleepModeInfoNV.html), [NVIDIA's Reflex performance FAQ](https://developer.nvidia.com/rtx/streamline/get-started), and the installed NGX SDK headers. Existing-mod research: [Salt's Anti-Aliasing](https://modrinth.com/mod/salts-anti-aliasing) and [its source](https://github.com/Salt-and-Fries/Salt-s-Anti-Aliasing). Its scene-only rendering and HUD separation match the established integration strategy; no new dependency was needed.
