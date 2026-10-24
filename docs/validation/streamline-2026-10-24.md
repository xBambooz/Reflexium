# Streamline migration and release objective matrix, 2026-10-24

This round moved DLSS Super Resolution and Reflex/PCL to NVIDIA Streamline on Minecraft's own
Vulkan device, rebuilt the performance and latency picture on the Streamline path, and re-profiled
the BSL forest 1440p Quality loss. Frame generation stays on the raw NGX path. The release
objectives are in [OBJECTIVES.md](../OBJECTIVES.md); the migration plan is
[STREAMLINE_MIGRATION_SPEC.md](../specs/STREAMLINE_MIGRATION_SPEC.md). Nothing below redefines an
objective to make it pass.

## Environment

- Windows 11, RTX 5070 12 GB, NVIDIA 617.42, Java 25, Minecraft 26.3, Vulkan backend.
- Harness window 1920x1080 on the secondary XF250Q 240 Hz display, then 2560x1440 for the BSL
  forest row. VSync off, Max Framerate 260 (Unlimited). Bloom off, sound muted.
- Iris `1.12.0-snapshot+mc26.3-local`, BSL v10.1.8, Sodium 0.9.2, Distant Horizons 3.3.5-dev.
  Render distance 31, simulation distance 12.
- Streamline SDK 2.14.16, release `v2.14.16`, asset
  `streamline-sdk-v2.14.16.zip`, SHA-256
  `32041e51db0cab709a271b72a352281b690c2abbd669a827809401a5dd23956b`.
  Production DLLs: `sl.interposer`, `sl.common`, `sl.dlss`, `sl.dlss_g`, `sl.reflex`, `sl.pcl`,
  `NvLowLatencyVk`. Every DLL reports a valid NVIDIA Authenticode signature. The vendored files and
  the three NVIDIA license texts are under `native/streamline/`.
- Launch flag: `-Dreflexium.streamline=true` (the path is off by default until the remaining gates
  pass). Raw NGX remains selectable without the flag. The client was launched on Vulkan before every
  measurement; a crash can reset `run/options.txt` to `preferredGraphicsBackend:"default"`, and an
  OpenGL launch silently reports `needs the Vulkan renderer`.

## Step gates

| Gate | Result | Evidence |
| --- | --- | --- |
| 1. SDK and ABI | **Pass** | A native probe initialized Streamline 2.14.16 with `eUseManualHooking` and loaded DLSS, DLSS-G, Reflex and PCL. Each reports Vulkan support. DLSS needs `VK_KHR_push_descriptor`, `VK_NVX_binary_import`, `VK_NVX_image_view_handle`, `VK_EXT_buffer_device_address`, `timelineSemaphore`, `descriptorIndexing`, `bufferDeviceAddress`. Reflex needs `VK_NV_low_latency2` and `VK_NV_low_latency`. DLSS-G additionally needs one graphics, two compute and one optical-flow queue. Version returned by the game: DLSS 310.9.1, Streamline 2.14.16. |
| 2. Manual attachment | **Pass, partial** | The game created its instance and device, then called `slSetVulkanInfo`. The swapchain was created through Streamline's `vkCreateSwapchainKHR` proxy, `vkGetSwapchainImagesKHR` and `vkAcquireNextImageKHR`, and every present went through the `vkQueuePresentKHR` proxy. Swapchain: 8 images, IMMEDIATE, 1920x1080. The window was resized to 2560x1440 and back with a new proxy swapchain. Surface loss and device loss were not exercised. |
| 3. SR evaluate | **Pass, with a caveat** | DLSS Quality/Performance evaluated through Streamline in the range and forest scenes, first frame through steady state, with no evaluate error after the fixes below. The checked Streamline range frame showed correct composition (HUD, hand, gates, foliage). A matched native-versus-DLSS frame A/B was not run. Raw NGX was not run in the same JVM, so the frame-rate comparison is cross-session. |
| 4. Camera and motion | **Partial** | Streamline receives the same unjittered projection, previous/current reprojection, pixel jitter, camera basis, near/far/FOV/aspect, depth and render-pixel motion vectors as the NGX path. A visual walking/pan A/B and the `objectMotionCheck` matched capture were not rerun this round. |
| 5. Reflex/PCL | **Pass for cost, partial for latency** | One `slReflexSleep` per real frame, PCL markers mapped from the existing marker placement, no timeouts and no unpaced frames. Streamline Reflex On cost 1.3% at 554 FPS versus 7.3% on the raw low-latency2 path in the previous session. The driver frame report was not populated (`latencyReportAvailable` false), so input-to-present latency could not be compared. |
| 6. Frame generation | **Not migrated** | See "Frame generation" below. Raw NGX FG remains the shipping FG path. |
| 7. Dynamic MFG | **Blocked by the SDK** | The 2.14.16 DLSS-G guide states that Dynamic MFG is limited to D3D12; Vulkan is explicitly unsupported. `sl::DLSSGMode::eDynamic` cannot be the Vulkan Auto mode at this pin. |
| 8. Removal | **Not done** | The raw path stays, as the spec requires while FG is unmigrated. |
| 9. Pack and platform | **Partial** | BSL tested. Complementary Reimagined, Mellow and Solas plus AMD/Intel FSR smoke tests were not rerun. |

## What was integrated

- New `native/reflexium_sl.cpp`, a C ABI over Streamline that loads the signed interposer from
  `reflexium/streamline`, initializes with manual Vulkan hooking, sets the Vulkan device and queue,
  creates one frame token per real frame, writes `sl::Constants`, tags depth/motion/input/output and
  evaluates `sl::kFeatureDLSS`, and drives `slReflexSetOptions`, `slReflexSleep` and
  `slPCLSetMarker`.
- New `StreamlineSupport` and `StreamlineBridge` Java classes, a `VulkanInstanceMixin` that
  initializes Streamline before instance creation and adds its instance extensions, and
  `DeviceExtensions` additions for its device extensions and Vulkan 1.2/1.3 features.
- The swapchain, acquire and present calls are routed through Streamline's proxies in
  `VulkanGpuSurfaceMixin` while the flag is on. Raw `VK_NV_low_latency2` sleep, markers, present IDs
  and swapchain latency mode are skipped on that path; the raw path is unchanged when the flag is
  off.
- `UpscalingRenderer` uses `StreamlineSupport` for the DLSS render size, `slDLSSSetOptions`, the
  per-frame constants and `slEvaluateFeature`. FSR stays on `FsrBridge`/`FsrSwapchain`.

Two real integration bugs were found and fixed before the gates passed:

1. `slGetFeatureFunction` cannot be called before the device and swapchain exist. Feature functions
   are now resolved immediately after `slSetVulkanInfo`.
2. `slDLSSSetOptions` takes `(const ViewportHandle&, const DLSSOptions&)`, not only the options. The
   first binding corrupted the argument list and crashed inside `sl.dlss.dll`.
3. `slEvaluateFeature` needs the viewport in its input array. Passing no inputs returned
   `eErrorMissingInputParameter` (`0x78` after the bridge's `100 + result` offset).

## Objective status

| Objective | Status | Evidence |
| --- | --- | --- |
| 1. DLSS beats native, including without shaders | **Fail** | BSL 1080p range and forest gain 7.5% and 10.8%. BSL forest 1440p Quality loses 12.4%. Shaderless 1080p Performance loses 12.3%. Absolute FPS differ from the 2026-10-24 raw-path round, so only the deltas are comparable. |
| 2. Proper implementation, no bloat or band-aids | **Partial** | DLSS SR and Reflex/PCL now use NVIDIA's supported path, with no probes or heuristics. FG remains on raw NGX because the Vulkan Dynamic MFG and UI-alpha requirements are not met. No dead option was added; the flag is a documented migration gate. |
| 3. No refresh-rate lock | **Pass** | No pacing change. Streamline SR has no refresh cap. The fixed-multiplier FG evidence from 2026-10-24 (528 and 621 presents/s at 173 and 155 real FPS on a 240 Hz panel) remains valid because the FG path is unchanged. |
| 4. Reflex in CPU-bound play | **Partial** | SL Reflex On cost 1.3% at 554 FPS and Boost cost 0.0%, versus 7.3% and 12% on the raw path in the previous session. That matches NVIDIA's negligible-cost behaviour. The PCL/driver latency report was unavailable, so the latency reduction itself is not proven. |
| 5. Upscaled image close to native | **Partial, fail on FG banding** | Streamline DLSS Performance at the range keeps single-edged gates, chains, foliage and the HUD in the checked frame. The FG generated-frame dither loss measured on 2026-10-24 is unchanged. The proposed 16F FG output is not supported by DLSS-G; see below. |

## Streamline SR frame rate

FPS is the render-thread frame-stamp mean from `frame_mark` and `frame_stats_from`. Each row is one
JVM. Native and DLSS alternate in the range rows; the forest rows are single samples after warmup.

| Scene | Resolution | Native FPS | DLSS mode | DLSS FPS | Delta |
| --- | ---: | ---: | --- | ---: | ---: |
| Range, BSL | 1920x1080 | 188.3 | Performance | 200.6 | +6.5% |
| Range, BSL | 1920x1080 | 184.3 | Performance | 197.8 | +7.3% |
| Range, BSL | 1920x1080 | 182.5 | Performance | 198.4 | +8.7% |
| Forest, BSL | 1920x1080 | 190.6 | Quality | 211.3 | +10.8% |
| Forest, BSL | 2560x1440 | 185.6 | Quality | 162.6 | -12.4% |
| Forest, no shaders | 1920x1080 | 553.8 | Performance | 485.7 | -12.3% |

The range mean is native 185.0, DLSS Performance 199.0, +7.5%. The raw NGX round measured +9.8% for
Performance on the same range pose and +12.0% for Quality on the 1080p forest; the Streamline path
is within about two points of those deltas. The BSL forest 1440p loss is the same defect as before,
not a Streamline regression.

## Reflex and PCL

Shaderless 1080p, native rendering, uncapped, forest pose, Streamline path:

| Reflex mode | FPS | Delta vs Off |
| --- | ---: | ---: |
| Off | 553.8 | - |
| On | 546.8 | -1.3% |
| On + Boost | 553.8 | 0.0% |

The earlier raw `VK_NV_low_latency2` session measured 508 FPS Off, 471 On (7.3% cost) and 447 Boost
(12% cost) at roughly the same workload. Streamline's single `slReflexSleep` removes most of that
cost. PCL markers were emitted with no `slPCLSetMarker` errors in the game log, but
`slReflexGetState` returned `latencyReportAvailable = false`, so `reflex_timings` was empty and no
latency table is claimed. This is an open validation gap, not a pass.

The status line still says "Reflex: On (Streamline)" and reports the raw extension revision only for
the raw path. It no longer prints "no present id" on the Streamline path.

## Objective 1: BSL forest 1440p Quality

Per-pass GPU means in microseconds from `profile reset` then `profile off`, 6 s windows, sparse
timestamp samples (36 to 43 per span). Native and Streamline Quality ran in the same JVM and pose
(200.5, 67, 150, yaw 90, pitch 0).

| Span | Native GPU | DLSS Quality GPU |
| --- | ---: | ---: |
| World (includes children) | 5877.7 | 7229.6 |
| DLSS evaluate | 0 | 559.4 |
| Camera motion | 0 | 73.3 |
| Object motion | 0 | 4.9 |
| Iris shadows and prepare | 1206.5 | 1440.4 |
| Iris deferred | 821.7 | 297.7 |
| Iris composite | 973.5 | 505.1 |
| Iris final | 81.0 | 24.8 |
| Terrain solid | 2269.1 | 3473.4 |
| Sky | 31.2 | 11.4 |
| Final blit | 48.8 | 19.0 |

Reflexium's own added GPU work is 0.56 ms evaluate plus 0.08 ms camera/object motion. The pack's
display-resolution composite and defer passes shrink as expected. The terrain and shadow spans grow
at the lower render size, which is inside the pack, not a Reflexium full-resolution copy. The
remaining hypotheses are that BSL's terrain/shadow work is not fill-bound in this view and the
negative mip bias adds texture cost, or that the sparse timestamps are noisy. This was not fixed
this round. The correct next step is per-pack-pass instrumentation (shadow, volumetric, terrain)
rather than a Reflexium workaround.

Objective 1 without shaders: native 553.8 FPS, DLSS Performance 485.7 FPS. The per-pass floor was
profiled on the native row (world 497 us CPU and 1589 us GPU at 1080p, no evaluate); the
Streamline DLSS shaderless per-pass profile was not rerun this round. The 2026-10-24 raw-path
profile measured evaluate at 289 us GPU and object motion at 36 us CPU for 1080p Performance, with
no extra colour copy, readback or steady-state barrier; the display-depth resolve runs only when a
post effect samples depth. The Streamline path uses the same motion passes and one evaluate. DLSS
cannot make a geometry-bound scene faster without removing geometry work, so the objective remains
unmet. The floor is not zero, but it is the required evaluation plus the inputs moving objects and
camera motion need, not avoidable Reflexium passes.

## Frame generation and banding

Frame generation was not moved to Streamline. The pinned guide says:

> Multi frame generation is not supported ... The application is using Vulkan (support is currently
> limited to D3D12 only).

and separately:

> DLSS-G currently does NOT support FP16 pixel format and scRGB color space because it is too
> expensive in terms of compute and bandwidth cost.

The 2026-10-24 banding note proposed an RGBA16F generated target with a dithered present. That
proposal is invalid for DLSS-G at this pin: FP16 output is unsupported. NVIDIA's HDR guidance is
RGB10 for a 10-bit swapchain; the measured pipeline is an 8-bit SDR present, so the dither the pack
writes into its real frame is averaged away by the FG reconstruction and then quantized back to
8 bits. The remaining correct options are a UI-alpha aware present path and a deliberate final
dither, neither of which was implemented. Objective 5 therefore still fails on FG banding.

The raw NGX FG path remains unchanged and still delivers 2x/3x/4x with uncapped output. Dynamic MFG
(Auto) cannot be Streamline Dynamic on Vulkan; the existing application-side Auto policy continues
to own that mode.

## Not run this round

- The full objective matrix: 4K, DLAA, Balanced, platform scene, and FG 2x/3x/4x/Dynamic on both
  paths.
- Range pans, walking with view bob, and moving mob captures on the Streamline path.
- Complementary Reimagined, Mellow and Solas, plus AMD and Intel FSR smoke tests.
- A same-JVM raw NGX versus Streamline image and frame-rate A/B.
- A populated Reflex latency report. `slReflexGetState` did not publish frame timings.
- Surface loss, device loss and fullscreen toggle with the Streamline swapchain.

## Build

`.\gradlew.bat build --no-daemon --console=plain` returned `BUILD SUCCESSFUL in 11s` on the final
tree, including `dlssRuntimeInstallerCheck`, `nativeBridgeCheck`, `frameDeliveryCheck`,
`frameProfilerCheck`, `reflexTimingCheck` and `upscalingContractCheck`. The packaged jar contains
`natives/windows-x86_64/reflexium_sl.dll` and the signed `streamline/` runtime with its licenses.
`native/build.cmd` built `reflexium_ngx.dll`, `reflexium_sl.dll` and `reflexium_fsr.dll` with MSVC
14.44 against the checked-in Streamline headers and Vulkan-Headers.

Integration quality: production candidate for DLSS SR and Reflex/PCL on the tested BSL scope, not
reference quality. The missing matrix rows, the unpopulated Reflex latency report and the untested
surface-loss paths keep it below reference quality.

## Reproduce

```powershell
# Build the native bridges (MSVC, Windows SDK, Vulkan headers in native/deps)
.\native\build.cmd

# Build and install the mod
.\gradlew.bat build --no-daemon --console=plain

# Launch on Vulkan with Streamline, secondary monitor, harness port 25890
$env:MC_HARNESS_JVM_ARGS='["-Dreflexium.streamline=true"]'
powershell -File C:\Users\Ryan\source\repos\mc-agent-harness\tools\launch.ps1 `
  -Project C:\Users\Ryan\source\repos\Reflexium -Port 25890 -Width 1920 -Height 1080 `
  -RunDirectory C:\Users\Ryan\source\repos\Reflexium\run `
  -GradlePropertiesJson '{"rxDebug":"25599"}'
```

Then join `Reflexium Vitrail Smoke`, use `POST /camera` for the pose, and
`POST /reflexium {"frame_mark":1}` followed by `{"frame_stats_from":<frames>}` for FPS. Set
`shaders`, `upscaler`, `quality` and `reflex_mode` on `http://127.0.0.1:25599/set`. Read
`streamline` in `/status` before trusting any DLSS row.

## Evidence

- `range-sl-dlss-p-1080p.png`: range at BSL, Streamline DLSS Performance, 1080p, 960-wide.
- `range-gates-sl-dlss-p.png`: 480x270 crop of the gate and chain.
- Raw screenshots and the JVM crash from the first `slDLSSSetOptions` binding are under `run/`
  and are not committed.
