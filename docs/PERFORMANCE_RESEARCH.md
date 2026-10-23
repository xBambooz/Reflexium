# Reflexium upscaling performance research

14 October 2026. Research handoff for the optimization work. No implementation change is proposed as a measured win until a paired retest confirms it.

This report describes the benchmark baseline. An optimization agent is actively testing uncommitted `lazyOutlineDepth` and `directDepth` toggles, so the corresponding recommendations below are hypotheses for its measurements, not a request to duplicate those edits.

## What the benchmark proves

Set A is a 1920x1080 Vulkan run on an RTX 5070 without shaders. The native base rate is about 2,560 FPS, or 0.391 ms per real frame. CNN Quality takes 0.738 ms (1,355 FPS); Transformer Quality takes 1.644 ms (608 FPS); CNN DLAA takes 0.938 ms (1,065 FPS); Transformer DLAA takes 1.714 ms (584 FPS); FSR Quality takes 0.882 ms (1,134 FPS); FSR Native AA takes 1.494 ms (669 FPS). Transformer rows have 63.6% to 66.0% mean GPU utilization. Set B, with Complementary Reimagined, is roughly 345 native FPS and gains 6% to 53% from upscaling depending on mode. Sources: [Set A](benchmarks/upscaling-set-A.csv), [Set B](benchmarks/upscaling-set-B.csv), and the [protocol and measurement limits](benchmarks/README.md).

The reported GPU percentage is `nvidia-smi` utilization sampled once per second. It cannot identify which CPU call or GPU pass caused an idle interval. The benchmark does not include native runs at the lower render resolutions, nor direct GPU pass times. The requested CNN/Transformer names are settings and log labels; NGX does not expose the selected model. A profiler trace and lower-resolution native controls are needed for attribution.

## NVIDIA's official execution cost

The [NVIDIA DLSS Super Resolution Programming Guide, revision 310.6.0, pages 5 to 8](https://github.com/NVIDIA/DLSS/blob/main/doc/DLSS_Programming_Guide_Release.pdf) measures the DLSS library alone, without a 3D renderer. Its timings below use **Performance mode input**: 960x540 for 1080p output, 1280x720 for 1440p, and 1920x1080 for 4K. Numbers are milliseconds. E/F are CNN; J/K and L/M are Transformer. They are guidance, not a guarantee for a game or a different input size.

| GPU | CNN E/F 1080 / 1440 / 4K | Transformer J/K 1080 / 1440 / 4K | Transformer L 1080 / 1440 / 4K | Transformer M 1080 / 1440 / 4K |
| --- | --- | --- | --- | --- |
| RTX 2080 Ti | 0.41 / 0.62 / 1.30 | 1.16 / 1.80 / 3.50 | 3.51 / 5.45 / 10.60 | 1.95 / 3.41 / 7.51 |
| RTX 3060 | 0.60 / 0.98 / 2.08 | 1.45 / 2.39 / 5.05 | 3.96 / 6.53 / 13.81 | 2.81 / 5.05 / 11.10 |
| RTX 3070 | 0.43 / 0.68 / 1.40 | 0.93 / 1.53 / 3.17 | 2.43 / 4.01 / 8.30 | 1.74 / 3.03 / 6.56 |
| RTX 3080 Ti | 0.30 / 0.44 / 0.87 | 0.65 / 1.02 / 2.06 | 1.67 / 2.63 / 5.32 | 1.19 / 2.04 / 4.35 |
| RTX 4070 Ti | 0.26 / 0.45 / 1.02 | 0.56 / 0.91 / 1.97 | 0.90 / 1.46 / 3.18 | 0.68 / 1.18 / 2.74 |
| RTX 4080 | 0.20 / 0.33 / 0.75 | 0.47 / 0.71 / 1.49 | 0.79 / 1.19 / 2.51 | 0.54 / 0.92 / 2.08 |
| RTX 4090 | 0.17 / 0.25 / 0.53 | 0.38 / 0.54 / 1.06 | 0.59 / 0.85 / 1.67 | 0.41 / 0.64 / 1.39 |
| **RTX 5070** | **0.27 / 0.43 / 0.93** | **0.64 / 1.02 / 2.11** | **1.17 / 1.87 / 3.87** | **0.75 / 1.33 / 2.98** |
| RTX 5080 | 0.20 / 0.30 / 0.62 | 0.46 / 0.68 / 1.31 | 0.78 / 1.16 / 2.24 | 0.49 / 0.80 / 1.74 |
| RTX 5090 | 0.15 / 0.22 / 0.40 | 0.36 / 0.49 / 0.87 | 0.56 / 0.77 / 1.37 | 0.35 / 0.51 / 1.05 |

**No official DLAA timing table appears in this guide.** Its table tests Performance mode only. The RTX 5070's 0.27 ms and 0.64 ms are useful reference points for F and K at 1080p, but applying them to Quality or DLAA is an optimistic proxy, not an official result for those modes. In this code, [`DlssModel.CNN`](../src/client/java/com/bambooz/reflexium/upscale/dlss/DlssModel.java) selects preset E (numeric value 5) and `TRANSFORMER` selects K. [`rx_create`](../native/reflexium_ngx.cpp) sends one preset hint to every quality mode, including Performance and Ultra Performance. NVIDIA currently recommends K for DLAA, Quality and Balanced, M for Performance, and L for Ultra Performance. Preset F is marked for deprecation in the next SDK cycle. The guide says integration and extra buffer copies can change final performance.

### Frame rate calculation

The requested equation is `best FPS = 1000 / (native render time in ms at the mode's render resolution + DLSS time in ms)`. Only the 1080p native time was measured. For a transparent illustration, the table estimates render time as `0.391 ms x render pixels / 1920x1080 pixels` and reuses NVIDIA's *1080p Performance-input* 0.27 ms F and 0.64 ms K library timings. This assumes every part of native rendering scales with pixel count and that DLSS costs the same at every input size. Neither assumption is established, so these are optimistic planning estimates rather than expected FPS targets. The DLAA row uses the actual native 0.391 ms, but its DLSS cost is still a proxy.

| Mode | Render size | Estimated native render ms | F cost proxy, best FPS | K cost proxy, best FPS | Observed CNN / Transformer FPS |
| --- | --- | ---: | ---: | ---: | ---: |
| DLAA | 1920x1080 | 0.391 | 1,513 | 970 | 1,065 / 584 |
| Quality | 1280x720 | 0.174 | 2,253 | 1,229 | 1,355 / 608 |
| Balanced | 1114x626 | 0.131 | 2,491 | 1,296 | 1,400 / 616 |
| Performance | 960x540 | 0.098 | 2,719 | 1,355 | 1,446 / 626 |
| Ultra Performance | 640x360 | 0.043 | 3,190 | 1,463 | 1,347 / 640 |

At a fixed 0.391 ms native render time, the official 1080p Performance-input library result alone would cap RTX 5070 throughput near 1,513 FPS with F and 970 FPS with K. Upscaling can beat those only if rendering at a smaller resolution saves enough time. Actual Quality and DLAA DLSS costs may differ. The measured total minus the measured 1080p native time is 0.347 ms for CNN Quality, 1.253 ms for Transformer Quality, 0.547 ms for CNN DLAA, and 1.323 ms for Transformer DLAA. Those differences mix lower-resolution render savings with all added work; they are **not** direct DLSS execution timings.

## Why Transformer may leave the GPU idle

There is a plausible CPU submission bottleneck: the 600 to 640 FPS Transformer cluster corresponds to 1.56 to 1.71 ms per frame regardless of input size, while GPU utilization stays near 65%. At this cadence, a few hundred microseconds of serial Java, JNI/FFM, NGX parameter setup, command recording, queue synchronization, or presentation work matters. NVIDIA's [Vulkan dos and don'ts](https://developer.nvidia.com/blog/vulkan-dos-donts/) says command recording work remains on the application's thread and urges precise barriers and parallel recording where possible. Its [advanced API CPU guidance](https://developer.nvidia.com/blog/advanced-api-performance-cpus/) warns that resource creation and fine-grained GPU queries can add CPU cost. This is a hypothesis, not proof that NGX itself consumes the missing time.

Code inspection narrows several hypotheses:

- [`UpscalingRenderer.build`](../src/client/java/com/bambooz/reflexium/upscale/UpscalingRenderer.java) creates the NGX feature only on a mode, model, or size change. [`rx_evaluate`](../native/reflexium_ngx.cpp) reuses `g_dlss` and `g_params`. The normal frame path does **not** recreate or re-register the feature.
- `rx_evaluate` calls `NVSDK_NGX_Create_ImageView_Resource_VK` four times, but the [official helper](https://github.com/NVIDIA/DLSS/blob/main/include/nvsdk_ngx_helpers_vk.h) is an inline initializer of a small descriptor struct. It does **not** call `vkCreateImageView` or allocate Vulkan images. Caching these wrappers cannot remove a real Vulkan allocation.
- The same official `NGX_VULKAN_EVALUATE_DLSS_EXT` helper sets many NGX parameters on every call before `NVSDK_NGX_VULKAN_EvaluateFeature_C`. If the CPU `evaluate` span is large, compare it with a direct low-level call that sets constant fields once and dynamic fields each frame, while retaining the same parameter object and validating image quality. NVIDIA does not publish a Vulkan-specific switch that makes EvaluateFeature free. The helper path is the supported baseline.
- [`UpscalingRenderer.evaluate`](../src/client/java/com/bambooz/reflexium/upscale/UpscalingRenderer.java) puts RenderPearl's generic full memory barrier before and after every DLSS or FSR evaluation. Barriers can stall GPU work, but GPU idle might also come from a late CPU submission. The barrier spans and submit span must be measured before changing synchronization.
- `SharpBlockOutlineRenderer.render` (removed in 2963038) upscales full-screen depth **before** checking whether any outline was captured. This pass runs on every upscaled frame, including when no block is targeted. That is a concrete removable pass, independent of the NGX hypothesis.
- The code uses explicit color/depth texture swaps for the main scene, so the normal DLSS path already avoids a full-screen scene color copy. It still copies world depth to preserve it across vanilla's hand-depth clear. FSR additionally copies opaque color for the reactive mask. These are separate costs.

NVIDIA's guide advises minimizing extra buffer copies. AMD's [FSR 3.1 upscaler manual](https://gpuopen.com/manuals/fidelityfx_sdk/techniques/super-resolution-upscaler/) advises placing suitable effects before upscaling at render resolution, putting film grain and several other post effects after, and using FP16 on supported Vulkan hardware. It states FSR 3.1 is slightly slower than 3.0 for improved quality. These recommendations do not justify deleting depth or velocity data that temporal reconstruction needs.

For a real engine comparison, [Unreal Engine exposes velocity writes during its depth pass, base pass, or a later pass](https://dev.epicgames.com/documentation/unreal-engine/API/Runtime/Engine/EVelocityOutputPass__Type), and [Epic notes the later pass can incur extra draw calls](https://dev.epicgames.com/documentation/unreal-engine/rendering-settings-in-the-unreal-engine-project-settings). That supports investigating motion output in Minecraft's main rendering path, but it does not prove that RenderPearl can add an attachment without compatibility costs. Unreal's choice is a design precedent, not a measured Reflexium saving.

## Prioritized experiments for the implementation agent

Expected savings are **targets to measure**, not predictions of observed FPS. Use the existing `FrameProfiler` (`DebugServer` `profile=on/reset`) to compare CPU and GPU spans for depth copy, camera motion, object motion, evaluate, outline depth, barriers and submit. Also record whole-frame CPU time, `nvidia-smi` GPU utilization, and a paired native control in the same session. Profiling itself can perturb this very high FPS workload, so compare profiled modes under the same instrumentation and then retest headline FPS without it.

1. **Skip unused outline depth.** In `SharpBlockOutlineRenderer.render`, test `capturedOutline` and `capturedCamera` before `upscaleDepth`; clear capture on either path. This should remove one full-screen depth pass from every frame without a block outline. Confirm with `outline_depth` GPU span and a screenshot of an outlined block. Potential: the measured outline-depth pass time plus its command-recording cost, likely a small but guaranteed saving on ordinary frames.
2. **Time and narrow the broad barriers.** In `UpscalingRenderer.evaluate` and `captureWorldDepth`, inspect RenderPearl's `memoryBarrier` stage/access masks and the NGX/FSR resource layout contract. Replace only barriers proven overbroad with image-specific producer/consumer dependencies. Preserve write visibility and cross-queue ownership. Potential: the measured `barrier_before` and `barrier_after` GPU stalls, possibly hundreds of microseconds, but zero if those spans already overlap or are cheap.
3. **Separate NGX CPU work from GPU work.** `FrameProfiler` already times both sides of `UpscalingRenderer.evaluate`. Record F versus K at the same 960x540 input, with FG off. If `evaluate.cpu_us` is high but `evaluate.gpu_us` resembles NVIDIA's 0.27 / 0.64 ms reference, investigate `NGX_VULKAN_EVALUATE_DLSS_EXT` parameter setters and the low-level `EvaluateFeature_C` path. If GPU time is high, investigate resource state, barriers and whether the official timing's assumptions hold. Potential: only the measured CPU excess; do not promise an NGX shortcut.
4. **Reduce motion and depth passes only where valid.** `MotionVectorPass.render` always draws a full-screen camera reprojection pass after `captureWorldDepth` copies depth; `ObjectVelocityPass.render` already returns without a draw when there are no moving vertices. For vanilla integration, investigate writing camera velocity in the main depth/geometry path or retaining a valid depth view through the hand clear, with Vitrail compatibility and motion-image checks. Skipping velocity entirely during camera motion would be wrong; camera-only motion still needs vectors. Potential: one render-resolution depth copy or one full-screen pass if engine integration makes it safely redundant. This is a larger change.
5. **Keep allocations off the per-frame path and tune DLAA as a quality choice.** Maintain the current NGX feature reuse in `UpscalingRenderer.build` and `rx_create`; avoid mode churn and repeated `waitForGpu`. Cache or reuse only real per-frame Vulkan objects if profiling finds any. DLAA must run at 1920x1080, so lower render resolution changes the mode. Offer CNN F only as a clearly labeled faster legacy model while supported; NVIDIA's recommended/default DLAA K costs more. For Auto, map per-mode recommended presets rather than forcing a single hint on all modes if image quality is the goal. Potential: no performance claim for preset mapping; a measured F/K DLAA tradeoff is already visible in Set A.

The next decisive data is a profiled F/K pair with the same input size, plus native controls at 1280x720 and 960x540. Those numbers will replace the optimistic render-time model and identify which pass deserves the first engineering change.
