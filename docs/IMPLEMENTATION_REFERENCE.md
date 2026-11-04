# Reflexium Implementation Reference

This is a working integration reference for Reflexium. Vendor requirements, API-specific conventions,
project choices and runtime evidence must be distinguished. Since round 10, Reflexium uses NVIDIA
Streamline 2.14.16 for DLSS SR, DLSS-G and Reflex/PCL; the raw NGX bridge and raw
`VK_NV_low_latency2` integration are deleted. Rows below that name `reflexium_ngx.cpp` or raw
`vkLatency*` calls describe the removed path and are historical. Current acceptance and remaining
limits are in [DLSS.md](DLSS.md), [FRAME_GENERATION.md](FRAME_GENERATION.md) and
[VITRAIL.md](VITRAIL.md). No vendor certification is claimed.

Primary Sources Cited:
* NVIDIA Reflex Vulkan Extension Specification (`VK_NV_low_latency2`): https://registry.khronos.org/vulkan/specs/latest/man/html/VK_NV_low_latency2.html
* NVIDIA Streamline Reflex Integration Guide (`ProgrammingGuideReflex.md`): https://github.com/NVIDIAGameWorks/Streamline/blob/main/docs/ProgrammingGuideReflex.md
* NVIDIA Streamline DLSS Integration Guide (`ProgrammingGuideDLSS.md`): https://github.com/NVIDIAGameWorks/Streamline/blob/main/docs/ProgrammingGuideDLSS.md
* NVIDIA RTX UI Developer Guidelines (`RTX UI Developer Guidelines.pdf`): local copy at `native/deps/DLSS/doc/RTX UI Developer Guidelines.pdf`
* NVIDIA DLSS SDK Programming Guide (`DLSS_Programming_Guide_Release.pdf`): local copy at `native/deps/DLSS/doc/DLSS_Programming_Guide_Release.pdf`
* AMD FidelityFX SDK 1.1 FSR 3.1 Upscaler Header (`ffx_upscale.h`): local copy at `native/deps/FidelityFX-SDK-v1.1.4/ffx-api/include/ffx_api/ffx_upscale.h`
* AMD GPUOpen FSR 3.1 Integration Manual: https://gpuopen.com/fidelityfx-super-resolution-3/

---

## 1. NVIDIA Reflex Specification

### 1.1 Architectural Purpose and Latency Pipeline
NVIDIA Reflex dynamically paces CPU simulation and render submission to synchronize with the GPU, preventing the CPU render thread from queueing work far ahead of GPU execution. In standard rendering pipelines without pacing, CPU execution can buffer multiple frames of draw calls in the driver/OS render queue, introducing significant presentation latency. Reflex eliminates this queueing delay while ensuring the CPU samples input at the latest possible timestamp before GPU submission begins.

### 1.2 Vulkan Interface: `VK_NV_low_latency2`
Reflex in Vulkan is standardized via the `VK_NV_low_latency2` device extension (revision 3). It supersedes the legacy `VK_NV_low_latency` extension and eliminates native NVAPI overhead.

The extension introduces four core functions:
1. `vkSetLatencySleepModeNV(device, swapchain, pSleepModeInfo)`
2. `vkLatencySleepNV(device, swapchain, pSleepInfo)`
3. `vkSetLatencyMarkerNV(device, swapchain, pMarkerInfo)`
4. `vkGetLatencyTimingsNV(device, swapchain, pTimingInfo)`

### 1.3 Latency Markers and Execution Sequence
Latency markers delimit application stages. Reflexium sleeps, marks simulation start and then marks
input immediately before SDL polling. Simulation ends before render submission starts. The marker
placement requirements are listed individually below; INPUT_SAMPLE need not precede SIMULATION_START.

1. `VK_LATENCY_MARKER_INPUT_SAMPLE_NV`:
   * Requirement: Must be invoked on the CPU immediately prior to gathering/polling input events (e.g., GLFW/SDL mouse and keyboard polling).
   * Primary citation: Khronos `VkLatencyMarkerNV(3)` specification: "VK_LATENCY_MARKER_INPUT_SAMPLE_NV should be called just before the application gathers input data."
2. `VK_LATENCY_MARKER_SIMULATION_START_NV`:
   * Requirement: Called at the start of game simulation/tick execution each frame, immediately following `vkLatencySleepNV`.
   * Primary citation: Khronos `VkLatencyMarkerNV(3)`: "VK_LATENCY_MARKER_SIMULATION_START_NV should be called at the start of the simulation execution each frame, but after the call to vkLatencySleepNV."
3. `VK_LATENCY_MARKER_SIMULATION_END_NV`:
   * Requirement: Called immediately after simulation/tick execution completes on the CPU.
4. `VK_LATENCY_MARKER_RENDERSUBMIT_START_NV`:
   * Requirement: Called at the beginning of render submission execution (recording command buffers and issuing `vkQueueSubmit`). Must not span asynchronous rendering tasks.
5. `VK_LATENCY_MARKER_RENDERSUBMIT_END_NV`:
   * Requirement: Called immediately when render submission commands have been queued to the GPU.
6. `VK_LATENCY_MARKER_PRESENT_START_NV`:
   * Requirement: Called immediately prior to invoking `vkQueuePresentKHR`.
7. `VK_LATENCY_MARKER_PRESENT_END_NV`:
   * Requirement: Called immediately after `vkQueuePresentKHR` returns control to the CPU.
8. `VK_LATENCY_MARKER_TRIGGER_FLASH_NV`:
   * Requirement: Optional marker emitted between `SIMULATION_START` and `SIMULATION_END` whenever a left mouse button click occurs, used for hardware latency analyzers (LDAT).

### 1.4 Driver Sleep and Input Sampling Placement
The pacing command `vkLatencySleepNV` is a non-blocking asynchronous dispatch that signals a timeline semaphore when the CPU is permitted to proceed:
```c
typedef struct VkLatencySleepInfoNV {
    VkStructureType sType; // VK_STRUCTURE_TYPE_LATENCY_SLEEP_INFO_NV
    const void* pNext;
    VkSemaphore signalSemaphore;
    uint64_t value;
} VkLatencySleepInfoNV;
```
Placement rule:
Reflexium calls `vkLatencySleepNV` and waits on its timeline semaphore at frame start, then emits
SIMULATION_START and INPUT_SAMPLE before SDL polling. INPUT_SAMPLE was previously after polling;
the 2026-10-13 correction follows the [Khronos placement requirement](https://docs.vulkan.org/refpages/latest/refpages/source/VkLatencyMarkerNV.html).

### 1.5 Present ID Requirements
To accurately attribute queue submissions to swapchain presentations across multi-threaded or deferred pipelines, an explicit 64-bit frame identifier must be propagated across three points:
1. `VkLatencyMarkerInfoNV.presentID`: Attached to all marker calls for that frame.
2. `VkLatencySubmissionPresentIdNV.presentID`: Chained into `pNext` of `VkSubmitInfo` or `VkSubmitInfo2` during queue submission. Supported by drivers advertising `VK_NV_low_latency2` revision 3 or higher.
3. `VkPresentIdKHR.pPresentIds`: Chained into `pNext` of `VkPresentInfoKHR` during `vkQueuePresentKHR`.

Identifiers must be monotonically increasing non-zero integers (`uint64_t`).

### 1.6 Low Latency Modes and Boost Semantics
Configured via `VkLatencySleepModeInfoNV`:
```c
typedef struct VkLatencySleepModeInfoNV {
    VkStructureType sType;
    const void* pNext;
    VkBool32 lowLatencyMode;
    VkBool32 lowLatencyBoost;
    uint32_t minimumIntervalUs;
} VkLatencySleepModeInfoNV;
```
* `lowLatencyMode = VK_FALSE`: Reflex pacing is inactive.
* `lowLatencyMode = VK_TRUE`: Reflex pacing actively paces CPU submissions to match GPU execution.
* `lowLatencyBoost = VK_TRUE`: Low Latency Boost mode. Instructs the GPU power management driver to maintain maximum GPU core clock frequencies even during CPU-bound scenarios or sudden GPU workload drops. This prevents latency spikes caused by dynamic frequency downclocking, at the expense of higher power draw.

### 1.7 Frame Limiter (`minimumIntervalUs`)
* `minimumIntervalUs`: Specifies the minimum interval in microseconds enforced by `vkLatencySleepNV` between subsequent `vkQueuePresentKHR` calls.
* Formula for integer FPS limit $N$: `minimumIntervalUs = 1000000 / N`.
* Critical specification detail: Even if `lowLatencyMode` is set to `VK_FALSE`, the driver still respects and enforces `minimumIntervalUs`. Setting `minimumIntervalUs = 0` disables the internal limiter.

### 1.8 Frame Limiter Ownership
Reflexium does not infer a Reflex cap from the refresh rate. The value sent as `minimumIntervalUs` is
the player's Minecraft Max Framerate, including the menu and AFK throttles, multiplied by the
presented-frame factor when frame generation is active. NVIDIA's refresh-headroom formula is display
vendor and driver policy, not part of Reflex; applying it in the game silently changes the rate the
player asked for. Frame generation still uses the refresh budget to choose its multiplier and output
ceiling.

### 1.9 UI Naming, Branding, and Certification Rules
According to NVIDIA RTX UI Developer Guidelines:
* Feature Label: "NVIDIA Reflex Low Latency" (or "NVIDIA Reflex").
* Option Values: "Off", "On", "On + Boost".
* Hardware Availability Gating: The Reflex UI option must only be visible or enabled when supported by the hardware and driver (`lowLatencyAvailable == true`). On non-NVIDIA GPUs, the setting must be hidden or disabled.
* Frame Generation Rule: If DLSS Frame Generation is enabled, NVIDIA Reflex Low Latency must be forced "On" and locked from user deactivation.

### 1.10 PC Latency (PCL) Stats Display Rules
* Streamline PCL Stats is vendor-agnostic. Raw `VK_NV_low_latency2` timings require extension support;
  Reflexium does not implement Streamline's cross-vendor PCL collection.
* On supported Vulkan devices, Reflexium dispatches markers with Reflex Off, On and On + Boost.
* Calculation:
  $$\text{PC Latency} = \text{gpuRenderEndTimeUs} - \text{inputSampleTimeUs}$$
* Polling: Applications obtain frame metrics by querying `vkGetLatencyTimingsNV` with `VkLatencyTimingsFrameReportNV`.

Reflexium's displayed total is currently input-to-present-end, not the input-to-GPU-end PCL formula
above. Treat its label as a driver timing diagnostic, not an NVIDIA-certified PC Latency result.

---

## 2. NVIDIA DLSS Super Resolution Specification

### 2.1 Required Inputs and Formats
DLSS Super Resolution (NGX SDK 3.x+) requires the following input buffers:
* Input Color: Low-resolution color buffer (HDR or LDR). Supported formats: `VK_FORMAT_R16G16B16A16_SFLOAT`, `VK_FORMAT_B10G11R11_UFLOAT_PACK32`, `VK_FORMAT_R8G8B8A8_UNORM`.
* Depth Buffer: Low-resolution depth buffer. Supported formats: `VK_FORMAT_D32_SFLOAT`, `VK_FORMAT_D24_UNORM_S8_UINT`, `VK_FORMAT_D32_SFLOAT_S8_UINT`.
* Motion Vectors: Low-resolution 2D motion vector buffer. Supported formats: `VK_FORMAT_R16G16_SFLOAT`, `VK_FORMAT_R32G32_SFLOAT`, `VK_FORMAT_R16G16B16A16_SFLOAT`.
* Output Color: Target display resolution color buffer with UAV / storage image write capability.
* Exposure (Optional): 1x1 32-bit float texture containing current scene exposure. If omitted, `NVSDK_NGX_DLSS_Feature_Flags_AutoExposure` must be provided.

### 2.2 Jitter Convention and Sign
Primary citation: NVIDIA DLSS Programming Guide Section 3.7.2 and 3.7.3:
"Each frame, update the ProjectionJitter values with those from the jitter sequence for the current Phase. Then offset the main projection matrix for the frame:
ProjectionMatrix.M[2][0] += ProjectionJitter.X;
ProjectionMatrix.M[2][1] += ProjectionJitter.Y;"

"The values must fall strictly between -0.5 and +0.5, as jitter should always result in subpixel movement within a single source pixel. Offsets are provided in pixel-space at the render target size (the low/source render resolution, not the output/target resolution)."

Sign convention:
When the camera projection matrix is translated by $+(\Delta x_{pixel} \cdot 2 / W_{render}, \Delta y_{pixel} \cdot 2 / H_{render})$, the rendered image shifts on screen. The subpixel offset parameter `InJitterOffsetX` and `InJitterOffsetY` in `NVSDK_NGX_VK_DLSS_Eval_Params` must communicate the exact subpixel displacement of the sample point within the render pixel. If projection translation shifts the camera position in positive NDC, `InJitterOffsetX` takes the corresponding signed pixel offset. A sign inversion between projection jitter and `InJitterOffset` results in extreme edge shimmering, fine line disintegration, and failure of temporal anti-aliasing.

### 2.3 Recommended Jitter Sequence and Phase Count
* Low-discrepancy sequence: Halton sequence with base 2 for X and base 3 for Y:
  $$H_2(i) - 0.5, \quad H_3(i) - 0.5$$
* Phase Count Formula (DLSS Guide Section 3.7):
  $$\text{Phases} = \text{BasePhaseCount} \times \left( \frac{\text{Target Resolution}}{\text{Render Resolution}} \right)^2$$
  where $\text{BasePhaseCount} = 8$.

Phase counts by mode:
* DLAA (1.0x): 8 phases
* Quality (1.5x): $8 \times 1.5^2 = 18$ phases
* Balanced (1.72x): $8 \times 1.72^2 = 24$ phases
* Performance (2.0x): $8 \times 2.0^2 = 32$ phases
* Ultra Performance (3.0x): $8 \times 3.0^2 = 72$ phases

### 2.4 Motion Vector Conventions
* Direction: DLSS strictly expects motion vectors pointing from the current frame pixel position to the previous frame pixel position:
  $$\vec{V} = \text{Position}_{prev} - \text{Position}_{curr}$$
* Units: When `NVSDK_NGX_DLSS_Feature_Flags_MVLowRes` is set, motion vectors are rendered at render resolution, and vector components represent raw render pixel units ($[-W_{render}, W_{render}]$).
* Scale Parameters:
  `InMVScaleX` and `InMVScaleY` in `NVSDK_NGX_VK_DLSS_Eval_Params` scale raw vector buffer values. If motion vectors already store delta in render pixels, both scale factors must be `1.0f`. If normalized UV offsets are stored ($[-1, 1]$), scale factors must be $W_{render}$ and $H_{render}$.
* Jitter Flag (`NVSDK_NGX_DLSS_Feature_Flags_MVJittered`):
  If the motion vector pass does not subtract camera jitter, `MVJittered` must be passed to DLSS so the neural network removes camera jitter internally. If motion vectors are generated from unjittered geometry or unjittered camera matrices, `MVJittered` must NOT be set.

### 2.5 Depth Buffer Conventions
* Hardware depth: Hardware non-linear depth buffer is required. Linear depth is not accepted.
* Reverse-Z Flag: When using inverted floating-point depth ($1.0$ at near plane, $0.0$ at infinite far plane), the creation flag `NVSDK_NGX_DLSS_Feature_Flags_DepthInverted` must be set.

### 2.6 History Reset Conditions
The parameter `InReset` in `NVSDK_NGX_VK_DLSS_Eval_Params` must be set to `1` whenever temporal continuity is broken:
* Scene transitions and cutscenes
* Player or camera teleportation (distance threshold $\ge 8$ blocks)
* Camera rotation discontinuity ($> 15$ to $30$ degrees in one frame)
* Window resizing or full-screen toggling
* Re-entering game world from pause menus

When `InReset = 1`, DLSS discards previous temporal history frames and performs spatial reconstruction only, preventing smearing artifacts across cuts.

### 2.7 Exposure and Pre-Exposure for LDR
* In standard LDR pipelines (e.g. vanilla Minecraft), color buffers are pre-tonemapped $[0, 1]$.
* If the engine does not provide a dynamic 1x1 exposure texture, set `InPreExposure = 1.0f` and `InExposureScale = 1.0f`.
* Do not enable `NVSDK_NGX_DLSS_Feature_Flags_AutoExposure` on pre-tonemapped LDR scenes unless strictly necessary, as internal auto-exposure heuristics can induce subtle luminance breathing on low dynamic range inputs.

### 2.8 Texture Mip LOD Bias Formula
Because internal rendering occurs at a lower resolution, standard anisotropic texture filtering calculates LOD based on downscaled pixel footprint, causing textures to appear blurry. DLSS requires biasing mip selection to sample sharper mip levels.

Primary citation: NVIDIA DLSS Programming Guide Section 3.8:
$$\text{DlssMipLevelBias} = \text{NativeBias} + \log_2\left(\frac{\text{Render Resolution}}{\text{Display Resolution}}\right) - 1.0 + \epsilon$$

When native engine anti-aliasing already applies a $-1.0$ bias:
$$\Delta\text{LOD} = \log_2\left(\frac{\text{Render Resolution}}{\text{Display Resolution}}\right)$$

Values:
* Quality (0.667x): $\log_2(0.667) \approx -0.585$ (total bias $-1.58$)
* Balanced (0.580x): $\log_2(0.580) \approx -0.786$ (total bias $-1.79$)
* Performance (0.500x): $\log_2(0.500) = -1.000$ (total bias $-2.00$)
* Ultra Performance (0.333x): $\log_2(0.333) \approx -1.585$ (total bias $-2.58$)

### 2.9 Pipeline Ordering: Pre-DLSS vs Post-DLSS
* Pre-DLSS Passes (Low Resolution):
  Depth pre-pass, Opaque geometry, Decals, Translucent geometry, Lighting, Shadows, Screen Space Reflections, Ambient Occlusion.
* DLSS Evaluation:
  Evaluates on pre-tonemapped color, depth, and motion vectors.
* Post-DLSS Passes (Target Display Resolution):
  Bloom (recommended post-DLSS to prevent edge aliasing), Tonemapping, Depth of Field, Motion Blur, Film Grain, Vignette, and all HUD/GUI rendering. UI text and icons must NEVER pass through DLSS.

### 2.10 DLAA (Deep Learning Anti-Aliasing)
DLAA operates DLSS at $1.0\times$ render scale ($W_{render} = W_{display}, H_{render} = H_{display}$). It provides state-of-the-art AI temporal reconstruction without spatial upscaling, serving as a replacement for TAA or MSAA.

### 2.11 Render Presets and Model Selection (CNN vs Transformer)
Starting in DLSS 3.10+, NVIDIA introduced Vision Transformer architectures alongside legacy CNN autoencoders:
* CNN-Based Models: Presets A, B, C, D, E, F, G.
  - Lower VRAM usage (~60 MB at 1080p, ~200 MB at 4K).
  - Fast execution on Turing/Ampere Tensor Cores.
  - Known limitations: Ghosting behind thin objects, trailing on fast particles.
* Transformer-Based Models: Presets J, K, L, M.
  - ViT attention mechanisms. Vastly improved temporal stability and reduced ghosting.
  - Higher VRAM usage and compute footprint.
  - Preset K: Recommended default for DLAA, Quality, and Balanced modes.
  - Preset M: Tuned specifically for Performance mode.
  - Preset L: Tuned specifically for Ultra Performance mode.
* NGX Configuration Parameters:
  - `NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_DLAA`
  - `NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Quality`
  - `NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Balanced`
  - `NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Performance`
  - `NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_UltraPerformance`

### 2.12 Quality Mode Scaling Ratios
* DLAA: $1.0\times$ ($100\%$ render scale)
* Quality: $1.5\times$ per dimension ($66.7\%$ render scale)
* Balanced: $1.7\times$ per dimension ($58.8\%$ render scale)
* Performance: $2.0\times$ per dimension ($50.0\%$ render scale)
* Ultra Performance: $3.0\times$ per dimension ($33.3\%$ render scale)

### 2.13 Required UI Naming
RTX UI Guidelines mandate:
* Group Header: "NVIDIA DLSS"
* Technology Name: "Super Resolution"
* Mode Choices: "Off", "DLAA", "Quality", "Balanced", "Performance", "Ultra Performance"

### 2.14 Redistribution and License Terms
* `nvngx_dlss.dll` is licensed by NVIDIA for redistribution alongside commercial and non-commercial titles.
* Over-the-air (OTA) updates: The NVIDIA NGX runtime contains an automated OTA updater that can upgrade `nvngx_dlss.dll` from NVIDIA servers when configured in driver application profiles.

---

## 3. AMD FidelityFX Super Resolution (FSR 3.1) Specification

### 3.1 Context Creation and Upscale Flags
Defined in `<ffx_api/ffx_upscale.h>`:
* `FFX_UPSCALE_ENABLE_HIGH_DYNAMIC_RANGE` (bit 0): Set if input color is HDR.
* `FFX_UPSCALE_ENABLE_DISPLAY_RESOLUTION_MOTION_VECTORS` (bit 1): Set if motion vectors are display resolution.
* `FFX_UPSCALE_ENABLE_MOTION_VECTORS_JITTER_CANCELLATION` (bit 2): Set if motion vectors contain camera jitter.
* `FFX_UPSCALE_ENABLE_DEPTH_INVERTED` (bit 3): Set if depth is reverse-Z $[1.0, 0.0]$.
* `FFX_UPSCALE_ENABLE_DEPTH_INFINITE` (bit 4): Set if projection uses infinite far plane.
* `FFX_UPSCALE_ENABLE_AUTO_EXPOSURE` (bit 5): Enables internal 1x1 luminance calculation.

### 3.2 Jitter Sign and Offset Convention
In `ffxDispatchDescUpscale.jitterOffset`:
* Coordinates represent subpixel offset in pixel coordinates $[-0.5, 0.5]$.
* When camera projection is shifted by $+(\Delta x, \Delta y)$, FSR expects:
  $$\text{jitterOffset.x} = -\Delta x, \quad \text{jitterOffset.y} = -\Delta y$$
  or positive depending on whether jitter is defined as sample offset vs matrix translation.

### 3.3 Motion Vector Direction and Scale
* Direction: Screen-space vectors pointing from current frame pixel to previous frame pixel ($\text{Position}_{prev} - \text{Position}_{curr}$).
* Scale (`motionVectorScale`):
  - Set to `{1.0f, 1.0f}` if motion vectors are rendered in render-pixel units.
  - Set to `{renderWidth, renderHeight}` if motion vectors are normalized UV coordinates $[0, 1]$.

### 3.4 Inverted Depth and Camera Plane Inversion
When `FFX_UPSCALE_ENABLE_DEPTH_INVERTED` is enabled:
* The near and far plane parameters passed to `ffxDispatchDescUpscale` must be swapped:
  ```c
  dispatch.cameraNear = farPlaneDistance;
  dispatch.cameraFar = nearPlaneDistance;
  ```
Passing standard near/far values with inverted depth corrupts FSR internal depth unpacking and depth disocclusion logic.

### 3.5 Reactive Mask and Transparency / Composition Mask
* Reactive Mask (`dispatch.reactive`):
  Specifies how much current frame pixels should override accumulated temporal history $[0.0, 1.0]$.
  - Required for alpha-blended geometry: fire, water surfaces, smoke, torch particles, spell effects.
  - Without a reactive mask, moving translucent surfaces leave severe ghost trails behind them.
* Transparency and Composition Mask (`dispatch.transparencyAndComposition`):
  Guides the upscaler on pixels where transparent objects overlap opaque backgrounds with conflicting motion vectors.
* Autogeneration: FSR provides `ffxDispatchDescUpscaleGenerateReactiveMask` to compute reactive masks automatically by comparing pre-transparency and post-transparency color buffers.

### 3.6 Sharpening (RCAS)
FSR 3.1 integrates AMD Robust Contrast Adaptive Sharpening (RCAS):
* Controlled via `dispatch.enableSharpening = true` and `dispatch.sharpness` ($[0.0, 1.0]$).

### 3.7 Recommended Quality Ratios
Defined in `FfxApiUpscaleQualityMode`:
* Native AA (Mode 0): $1.0\times$
* Quality (Mode 1): $1.5\times$
* Balanced (Mode 2): $1.7\times$
* Performance (Mode 3): $2.0\times$
* Ultra Performance (Mode 4): $3.0\times$

### 3.8 UI Naming and Branding
AMD GPUOpen guidelines require:
* Feature Label: "AMD FidelityFX Super Resolution" or "AMD FSR 3".
* Options: "Native AA", "Quality", "Balanced", "Performance", "Ultra Performance".

---

## 4. Reflexium Gap Analysis

The following table compares Reflexium implementation against the official specifications.
Status definitions:
* `OK`: Correctly matches official specification.
* `WRONG`: Implemented in conflict with official specification.
* `MISSING`: Required or strongly recommended feature is not implemented.
* `UNSURE`: Behavior cannot be verified without hardware/vendor validation tests.

| Feature / Requirement | Status | Reflexium Code Location | Explanation |
| :--- | :--- | :--- | :--- |
| Reflex Marker Sequence | OK (corrected) | `ReflexController.beginFrame`, `MinecraftMixin` | Sleep, SIMULATION_START and INPUT_SAMPLE before SDL polling. The previous after-poll marker omitted input collection from timing. |
| Reflex Sleep Placement | OK | `ReflexController.java:122, 251` | `vkLatencySleepNV` and timeline semaphore wait execute before input gathering. |
| Reflex Present ID Attribution | OK | `VulkanLifecycle.java:78, 101` | Present ID is chained via `VkLatencySubmissionPresentIdNV` on submit and `VkPresentIdKHR` on present. |
| Reflex Boost Mode | OK (Streamline) | `StreamlineSupport.setReflexMode` | `sl::ReflexOptions.mode` 1/2 for On/On + Boost. |
| Reflex Frame Limiter | OK (Streamline) | `StreamlineSupport.setReflexMode` | `sl::ReflexOptions.frameLimitUs` calculated from the requested FPS cap. |
| Reflex Frame Cap Policy | OK | `ReflexController.updateFrameLimit` | Uses only Minecraft's explicit Max Framerate. Automatic refresh-rate caps and adaptive pacing pauses were removed. |
| Reflex PCL Markers when Off | OK (Streamline) | `ReflexController.marker` | PCL markers use the current Streamline token and are not gated on the Reflex mode. |
| Reflex UI Naming | OK | `ReflexiumConfig.java`, UI screens | Uses "NVIDIA Reflex" with "Off", "On", "On + Boost". |
| DLSS Required Buffer Formats | OK | `UpscalingRenderer.java:441` | Passes RGBA8/D32_FLOAT/RG16F, valid under NGX Vulkan requirements. |
| DLSS Jitter Sign Convention | SMOKE CHECKED | `UpscalingRenderer.JITTER_SIGN` | Uses +1 on both axes, based on captured stability tests. Do not copy a sign from another renderer's projection convention. |
| DLSS Halton Phase Count | OK | `UpscalingRenderer.java:601` | Formula `ceil(8 * ratio^2)` matches DLSS Programming Guide Section 3.7. |
| DLSS Motion Vector Convention | OK | `dlss_motion.fsh:32`, `UpscalingRenderer.java:50` | `previous - current` in render pixels with `MVLowRes` and `MVScale = {1, 1}`. |
| DLSS Depth Inversion Flag | OK | `UpscalingRenderer.java:50` | `NVSDK_NGX_DLSS_Feature_Flags_DepthInverted` matches reverse-Z depth. |
| DLSS History Reset Trigger | OK | `UpscalingRenderer.java:323` | Sets `InReset` on $>8$ block teleport, $>15$ deg turn, or $>250$ ms frametime jump. |
| DLSS Exposure & Pre-Exposure | Superseded | Streamline `DLSSOptions.colorBuffersHDR` | The raw NGX pre-exposure row described the removed bridge; Streamline receives the SDR/HDR flag instead. |
| DLSS Texture Mip LOD Bias | IMPLEMENTED | `MipBiasSamplers`, `MaterialSamplerMixin` | Material-only `log2(render/display) - 1.0`, zero in Native AA; replacements close when resolution changes. |
| DLSS Pipeline Order & UI | OK | `UpscalingRenderer.java:264, 335` | Swaps world pass; DLSS evaluates before GUI rendering. |
| DLSS DLAA Mode Support | OK | `UpscaleQuality.java:10` | DLAA runs at $1.0\times$ render scale through DLSS neural network. |
| DLSS Render Presets Assignment | OK (Streamline) | `DlssModel`, `StreamlineSupport.setDlssOptions` | K for DLAA/Quality/Balanced, M for Performance, L for Ultra Performance; AUTO requests the runtime default. |
| DLSS Quality Mode Ratios | OK | `UpscaleQuality.java` | DLAA (1.0x), Quality (1.5x), Balanced (1.7x), Performance (2.0x), Ultra Perf (3.0x). |
| DLSS DLL Redistribution | OK | `DlssRuntimeInstaller.java:36` | Downloads licensed `nvngx_dlss.dll` from official NVIDIA release repository. |
| FSR 3.1 Jitter Offset Sign | OK | `UpscalingRenderer.java:521` | Subpixel pixel offset passed directly to `ffxDispatchDescUpscale`. |
| FSR 3.1 MV Scale & Direction | OK | `reflexium_fsr.cpp:115` | `motionVectorScale = {1.0f, 1.0f}` matches render-pixel velocity output. |
| FSR 3.1 Inverted Depth Planes | OK | `reflexium_fsr.cpp:124` | Swaps near/far planes when `FFX_UPSCALE_ENABLE_DEPTH_INVERTED` is active. |
| FSR 3.1 Reactive Mask | PARTIAL | `ReactiveCaptureMixin`, `rx_fsr_generate_reactive` | AMD's opaque/final-color mask is supplied on vanilla. Disabled under packs because the color pair is not comparable. |
| FSR 3.1 RCAS Sharpening | OK | `reflexium_fsr.cpp:118` | Dispatches internal RCAS sharpening with user slider value. |
| Moving Entities Motion Vectors | IMPLEMENTED WITH LIMITS | `ObjectMotion`, `ObjectVelocityPass` | Captures previous/current animated opaque geometry and writes depth-matched velocity. Translucency, shader-only deformation and unstable topology retain limitations. |

---

## 5. Moving Objects Handling in a Forward Renderer

### 5.1 The Moving Object Dilemma in Minecraft
The base pass (`dlss_motion.fsh`) generates camera vectors with depth reprojection. ObjectVelocityPass
then overwrites matched visible opaque object surfaces using previous/current animated positions.
Camera reprojection alone assumes world geometry is static relative to the world coordinate frame:
$$\vec{P}_{prev} = M_{prev} \cdot M_{curr}^{-1} \cdot \vec{P}_{curr}$$

While this accurately accounts for camera translation and rotation across static terrain blocks, it completely fails for dynamic objects:
1. Living Entities (players, mobs): Move across world space and execute limb swing animations.
2. Animated Block Entities: Pistons pushing blocks, chests opening/closing, bells ringing.
3. First-Person Hand / Held Items: Rendered with a distinct view bobbing/swaying transform and separate near plane.
4. Particles: Billboards moving independently of scene geometry.
5. Translucent Surfaces: Water flows, stained glass, nether portals.

Because depth reprojection treats moving entities as stationary world geometry, DLSS and FSR interpret their movement as disocclusion, creating severe smear, ghost trails, and jitter along entity contours.

### 5.2 Per-Object Motion Vector Generation
To eliminate entity ghosting, dynamic objects must write exact 2D screen motion vectors:
$$\vec{V} = \frac{\vec{P}_{prev\_clip}.xy}{\vec{P}_{prev\_clip}.w} - \frac{\vec{P}_{curr\_clip}.xy}{\vec{P}_{curr\_clip}.w}$$

In a forward rendering engine like Minecraft / VulkanMod, there are three architectural integration options:

#### Option A: Multiple Render Targets (MRT) during Opaque Entity Pass (Recommended)
* Architecture:
  Bind the motion vector target as Color Attachment 1 alongside the main Color Target (Color Attachment 0) during entity rendering (`RenderType.entityCutout`, `RenderType.entitySolid`).
* Implementation:
  - Uniform Buffer: Pass both `CurrentModelViewProjection` and `PreviousModelViewProjection` to entity shaders.
  - Vertex Shader: Calculate both current clip position and previous clip position for every vertex.
  - Fragment Shader: Output shaded color to `layout(location = 0) out vec4 outColor` and output render-pixel velocity to `layout(location = 1) out vec4 outMotion`.
* Advantages:
  Zero additional draw calls; exact per-vertex interpolation accounts for mob translations and body rotations.

#### Option B: Skinned / Hierarchical ModelPart Transform Tracking
* Architecture:
  Minecraft entities are composed of hierarchical cuboid `ModelPart` instances (head, torso, limbs).
* Implementation:
  - Cache each `ModelPart` matrix from frame $N-1$.
  - In vertex shaders or compute skinning passes, evaluate vertex positions using both frame $N$ and frame $N-1$ part matrices.
  - Write velocity into the motion vector buffer.

#### Option C: Depth Mask / Entity Velocity Pre-Pass
* Architecture:
  If modifying the main forward render pass shaders is prohibitive, execute a lightweight velocity pass immediately following the entity pass.
* Implementation:
  - Re-render entity meshes with a dedicated velocity shader writing only to the motion target, utilizing equal depth testing (`VK_COMPARE_OP_EQUAL`) against the captured entity depth.

### 5.3 First-Person Hand Handling
The first-person player hand moves synchronously with camera orientation but has unique view bobbing and swing animations:
* Current Reflexium approach: Sets velocity to $(0, 0)$ where hand depth is present (`dlss_motion.fsh:20`). This prevents world reprojection from tearing the hand, but causes trailing when swinging weapons or walking with view bobbing.
* Correct approach: Calculate hand motion vectors using `PreviousHandTransform` and `CurrentHandTransform`, writing non-zero screen velocity during hand animation.

### 5.4 Particle Handling via Reactive Mask
Particles (torches, smoke, splashes, potion swirls) are small, translucent, and numerous. Generating per-particle motion vectors is computationally expensive and geometrically complex due to billboard orientation.
* Recommended industry standard:
  Route particle alpha directly into the **Reactive Mask** (for FSR) and Dilated Motion/Bias (for DLSS).
* Effect:
  The upscaler reduces temporal accumulation on particle pixels, relying on spatial reconstruction. This immediately eliminates ghost trails behind particles without requiring vector calculation.

### 5.5 Transparent Geometry Handling
Translucent geometry (water, stained glass, ice):
1. Render opaque world geometry and generate opaque depth and motion vectors.
2. Render translucent geometry into color target.
3. Write translucent surface coverage/alpha into the Reactive Mask and Transparency/Composition Mask.
4. Pass combined buffers into the upscaler. This ensures clean reconstruction of water surfaces and underlying terrain without motion shearing.
