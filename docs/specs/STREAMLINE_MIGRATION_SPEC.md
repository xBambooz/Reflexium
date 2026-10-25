# Streamline migration specification

Status: completed 2026-10-24. DLSS Super Resolution, Reflex/PCL and fixed 2x/3x/4x DLSS-G run only
through Streamline 2.14.16. The raw NGX bridge, the raw `VK_NV_low_latency2` integration, the
`-Dreflexium.streamline` switch and the `VK_NV_present_metering` path were deleted in round 10.
Streamline SR and DLSS-G share one constants block and mirrored inputs. Gate evidence is in
[../validation/round10-2026-10-24.md](../validation/round10-2026-10-24.md) and
[../validation/round9-2026-10-24.md](../validation/round9-2026-10-24.md).

Pinned SDK finding: DLSS-G is supported on Vulkan at 2.14.16 with fixed multipliers and hudless/UI
alpha tagging, but Dynamic MFG is D3D12-only and DLSS-G does not accept FP16 output. Auto therefore
stays the application policy and cannot be Streamline Dynamic MFG at this pin. Streamline allows one
common-constants block per frame; DLSS-G needs the mirrored presented backbuffer while DLSS SR needs
the unmirrored scene, so when Streamline FG is active SR uses the raw NGX feature. DLSS-G pauses
interpolation while the window is unfocused, which is NVIDIA's documented behaviour. Gate evidence is
in [../validation/round5-2026-10-24.md](../validation/round5-2026-10-24.md).

## Objective

Move DLSS Super Resolution, DLSS Frame Generation/Multi Frame Generation and Reflex/PCL from the
current raw NGX bridge and hand-written `VK_NV_low_latency2` calls to NVIDIA Streamline, while keeping
AMD FSR 3.1 on its existing path and keeping the Minecraft Vulkan swapchain working. Streamline
should own the things a shipping game gets from the vendor: frame tokens, present pacing, Dynamic
MFG selection, resource tagging validation and the menu/loading state contract.

## Why

Reflexium already calls NVIDIA's DLLs (`nvngx_dlss.dll`, `nvngx_dlssg.dll`) and the low-latency2
driver entry points. The bugs found in this project have been input and pacing bugs: motion vectors,
jitter, depth, camera matrices, present budgets and frame tagging. Streamline is the supported
integration path for DLSS-G and Reflex and ships the validation and pacing contracts that raw NGX
does not. It also gives Dynamic MFG a supported mode instead of the application-side policy in
`FrameGenerationRefreshPolicy`.

The migration is not a quick swap. Streamline's current SDK must be checked for Vulkan support of
each feature at the pinned version. If the pinned SDK's DLSS-G guide still excludes Vulkan, only SR
and Reflex/PCL can move in the first stage and FG must stay on raw NGX until the Vulkan path is
available. Record the checked SDK version and guide commit in the acceptance evidence.

## Current integration map

| Concern | Current owner | Source |
| --- | --- | --- |
| DLSS SR feature and evaluate | `NgxBridge` + `rx_create`/`rx_evaluate` | `src/client/java/com/bambooz/reflexium/upscale/dlss/NgxBridge.java`, `native/reflexium_ngx.cpp` |
| DLSS FG/MFG | `NgxBridge` + `rx_fg_*`, metering batch in the present loop | `FrameGenerationRenderer.java`, `native/reflexium_ngx.cpp` |
| Reflex sleep and markers | `ReflexController` + `VK_NV_low_latency2` | `reflex/ReflexController.java`, `mixin/MinecraftMixin.java`, `mixin/VulkanQueueSubmissionMixin.java` |
| Camera history and jitter | `CameraHistory`, `JitterSequence` | `upscale/CameraHistory.java` |
| Motion vectors | depth reprojection, object and hand passes | `motion/MotionVectorPass.java`, `motion/ObjectVelocityPass.java` |
| FG camera constants | `FrameGenerationConstants` | `framegen/FrameGenerationConstants.java` |
| Present budget | `ReflexController.frameGenerationCap`, `FrameGenerationRefreshPolicy` | `reflex/ReflexController.java` |
| FSR SR/FG | `FsrBridge`, `FsrSwapchain` | `upscale/fsr/` |
| Vulkan objects | `VulkanSession`, `VulkanLifecycle`, `VulkanGpuSurfaceMixin` | `vulkan/`, `mixin/Vulkan*` |

The migration must not move FSR behind Streamline. FSR stays on `FsrBridge` and `FsrSwapchain`, and
the existing rule holds: FSR and NVIDIA FG/SR selections remain mutually exclusive.

## Attachment strategy

Two options were considered.

1. Interposer (`sl.interposer.dll` ahead of `vulkan-1.dll`). This is closest to a native game and
   gets Streamline-owned swapchain wrapping automatically. It is also the highest risk here: the game
   is a Java process loading LWJGL, Sodium, Iris, Distant Horizons and the OBS Vulkan hook; the
   interposer must not break their `vkGetInstanceProcAddr` chains, and a DLL substitution in the Java
   classpath is hard to make optional and easy to break on driver updates.
2. Manual device and swapchain hooks through `slSetVulkanInfo`, `sl::DeviceVulkan`, the swapchain
   proxy and explicit `sl::FrameToken` creation. This is the recommended first attempt. It keeps the
   world's existing swapchain code in control, makes the fallback to raw NGX a plain runtime branch,
   and avoids replacing `vulkan-1.dll` for every user.

Decision: implement manual hooks behind a runtime flag, measure, and only consider the interposer if
manual hooks cannot deliver Dynamic MFG or the required present ownership.

## Device, instance and swapchain hooks

- Acquire the Vulkan instance/device/physical device from `VulkanSession` after RenderPearl creates
  them. Call `slSetVulkanInfo` with the exact instance, physical device, device and queue family
  indexes. Do not create a second device for Streamline.
- Enable the device extensions Streamline lists for the selected features. Keep the existing NGX
  extension query as the source of truth for SR, and add Streamline's required feature/extension
  query. Reject unsupported extension/feature combinations with a clear log line and fall back to
  the current path.
- Create the swapchain through the Streamline Vulkan swapchain proxy when the flag is on. The proxy
  must preserve Minecraft's requested image count, format, present mode (IMMEDIATE), extent,
  composite alpha and Y flip behaviour. Keep `VulkanLifecycle.validImageCount` clamping.
- Recreate the proxy swapchain on resize and surface loss with the existing lifecycle hooks
  (`onSwapchainCreated`, `onSwapchainDestroyed`). Do not recreate it from a render-thread callback
  inside a frame.
- Keep Reflexium's `VulkanCommandEncoder` and the RenderPearl submission path. Streamline must hook
  the queue/present calls, not replace the application's command recording.

## Per-frame data mapping

Streamline expects the same inputs we already produce, in its own structs.

| Streamline input | Reflexium source | Rule |
| --- | --- | --- |
| Color | main render target after pack final or vanilla post chain | render size, no GUI, current frame |
| Depth | Iris pre-translucent depth or vanilla world depth | reverse-Z preserved; no hand depth in the world depth |
| Motion vectors | `motionTarget` | unjittered current to previous, render pixels; excluded jitter and hand velocity separately |
| Jitter | `JitterSequence` | unjittered projection plus camera jitter, sign and phase must match the projection the world rasterized with |
| Camera view to clip | drawn level projection | include the transform the world actually rendered with, not a reconstructed physical lens |
| Clip to previous clip | `DlssMath.reprojection` | camera delta and the previous unjittered matrix, same as today |
| Camera position, up, right, forward | `CameraHistory` | the real camera, without bob |
| Frame token | one per real frame | create at frame start, destroy after the real present and every generated present |
| Near/far/FOV/aspect | physical camera metadata | unmodified camera projection, not the view-effect matrix |
| Reset/history | `HistoryResets` and `CameraHistory.cutReason` | same cut reasons; loading, menu, resize, world change |

Do not pass NGX row-vector matrices through unchanged. Streamline uses its own convention; add a
single conversion helper and unit tests with known points. Do not apply jitter twice: if Streamline
samples the jitter from the tag, the projection handed to the world must carry the same jitter and
the motion vector shader must keep subtracting it.

## Frame token and marker placement

Current marker order per real frame (`ReflexController`):

1. Sleep, then `INPUT_SAMPLE`.
2. `SIMULATION_START`.
3. `SIMULATION_END` before `renderFrame`.
4. `RENDERSUBMIT_START` before render-state extraction.
5. `RENDERSUBMIT_END` and `PRESENT_START` before the real present.
6. Generated presents use out-of-band markers; real presents carry the present ID.

Streamline mapping:

- Keep the same logical placement, but use `sl::PCLMarker` and `sl::ReflexMarker` calls for the
  marker set the SDK enables. One sleep per real frame, before input sampling. Do not sleep for every
  generated frame.
- Create a `FrameToken` at `RENDERSUBMIT_START` and pass it to `sl::slReflexSleep`,
  `sl::slPCLSetMarker`, SR evaluate and FG evaluate. Destroy it only after the last generated present
  for that real frame is submitted.
- Explicit submission IDs (`VkLatencySubmissionPresentIdNV`) and explicit present IDs
  (`VkPresentIdKHR`) are replaced by Streamline's token attribution. Preserve the existing diagnostic
  counters for a transition period, then remove the raw low-latency2 calls behind the same flag.
- Do not pass the FSR proxy swapchain handle to Streamline or to raw Reflex. Keep the FSR path on its
  own attribution state.
- Generated presents must still be tagged as out-of-band, not as new simulation frames.

Acceptance for markers is a stream of tokens with one sleep, one real present and N generated
presents per real frame, no marker order regressions, and no missing token. Physical cadence still
needs FrameView or PresentMon; app counters are not scanout.

## Dynamic MFG

Replace the current application policy with Streamline's dynamic multiplier support:

- `frame_generation_multiplier = 0` maps to Streamline Dynamic MFG. Request the maximum generated
  frame count from the query, and let the runtime choose the multiplier from real rate, refresh and
  present cost.
- A fixed 2..N maps to a fixed multiplier. It must remain uncapped: no refresh / N real-frame
  throttle. The current `targetsRefresh` rule stays as the contract until the Streamline path is the
  only path.
- Only Auto, VSync, VRR or a user FPS limit may target the refresh rate. Do not divide a present
  budget by the multiplier; the interval applies to total presents.
- Keep the current `GenerationLedger` reasons and map Streamline's states to them: menu, loading,
  reset, unavailable, not evaluated, unpresented, batch abandoned. Status must show requested and
  effective multiplier separately, as today.

## Menu and loading handling

- No SR or FG for GUI screens, pause or a loading frame. Keep the current early exits in
  `FrameGenerationRenderer.sceneReady` and the menu reason in the ledger.
- The menu blur path must remain safe when a generated target is replayed. The GUI replay contract
  (D32 depth clear, destination-dependent blends) is unchanged by Streamline.
- On a world change, resize or pack reload, reset the token, discard history, mark every temporal
  owner and wait for the previous generated outputs to complete before freeing resources.
- The first N frames after a load must not generate. Streamline's reset flag is not a substitute for
  the existing loading detection.

## FSR stays separate

- FSR SR and FSR FG continue on `FsrBridge` and `FsrSwapchain`. Streamline must not wrap or replace
  the FSR swapchain scheduler. If Streamline's swapchain proxy owns the real swapchain, FSR's fake
  backbuffer scheduler still needs its own path and cannot share Streamline's present ownership.
- NVIDIA modes and FSR modes remain mutually exclusive in `ReflexiumConfig`.
- A Streamline initialization failure must not disable FSR. A missing Streamline DLL or unsupported
  GPU falls back to the current raw NGX/FSR path with a logged reason and an unchanged status line.

## Binaries and licensing

- Streamline redistributes `sl.interposer`, `sl.common`, `sl.dlss`, `sl.dlss_g`, `sl.reflex`,
  `sl.pcl` and the NGX runtime DLLs. Confirm the current binary redistribution terms in the SDK
  license and preserve the required notices. NVIDIA's DLSS and Streamline licenses differ; do not
  copy the NGX license text over the Streamline files or vice versa.
- Keep the same optional download and SHA-256 verification model as `DlssRuntimeInstaller`. Never
  override an existing user DLL silently.
- Record the exact Streamline SDK version, source commit and file hashes in the validation evidence.
- Keep the raw NGX bridge in the jar during the migration. It is the fallback and the FSR path does
  not depend on it.

## Risks to the finished Reflex work

- The low-latency2 work includes hard-won attribution and sleep behavior on NVIDIA 617.42. Streamline
  may sleep in a different place or use a different token path. Preserve the existing measured
  behavior and compare one-sleep-per-frame counters before removing any code.
- Streamline's swapchain proxy can change the present mode or image count, invalidating the current
  FG present-batch and metering acceptance. Re-run the FG delivery and metering gates on the proxy
  swapchain before enabling it by default.
- Streamline may not expose the same raw driver reports. The F3 latency readout depends on
  `VK_NV_low_latency2` reports; keep the raw path available until an equivalent report source is
  proven.
- The hand mask fix must survive the migration. The hand is a separate coverage/depth source; do not
  let Streamline's tag validation reclassify world pixels as hand.
- A DLL-level interposer can break Sodium, Iris, Distant Horizons or the OBS hook. Test with the full
  production mod set, not a minimal client.

## Step order and test gates

1. **SDK and ABI gate.** Pin a Streamline SDK version and record its Vulkan support for SR, FG/MFG,
   Reflex and PCL. Gate: a native probe creates the device and a frame token on the real Minecraft
   Vulkan device. If DLSS-G Vulkan is not supported by the pinned version, stop FG migration here.
2. **Manual attachment gate.** Add the runtime flag, `slSetVulkanInfo`, the extensions query and the
   swapchain proxy. Gate: the game starts, resizes, loses and recreates the surface, and renders the
   vanilla scene with the proxy, FSR and raw NGX both still available.
3. **SR gate.** Replace only SR evaluate with Streamline behind the flag. Gate: DLSS Quality and
   Performance match the raw path in image and frame rate on the smoke platform and the range scene,
   with no history reset regression.
4. **Camera and motion gate.** Convert the matrices and jitter into Streamline inputs. Gate: the
   capture check still matches depth against the metadata, and the walking A/B has no double edges.
5. **Reflex/PCL gate.** Move sleep and markers to Streamline tokens. Gate: one sleep per real frame,
   no timeout increase, latency reports valid, and no regression on the Reflex on/off throughput
   comparison.
6. **FG gate.** Move FG present ownership and generated presents. Gate: fixed 2x/3x/4x uncapped, Auto
   dynamic, menu and loading stop generation, counts exact, one token per real frame and no leaked
   generated resources across toggles.
7. **Dynamic MFG gate.** Enable Auto dynamic selection. Gate: the selected multiplier changes only
   after the dwell window, real and total presents stay consistent, and the refresh target is honored
   only when Auto, VSync, VRR or a user cap asks for it.
8. **Removal gate.** Done in round 10: the raw NGX and raw low-latency2 paths are deleted, not just
   disabled. FSR remains on its own bridge and swapchain. Keep it behind the flag for one release.
9. **Pack and platform gate.** Re-run BSL, Complementary Reimagined, Mellow and Solas on the smoke
   platform and the range scene, plus AMD and Intel FSR smoke tests on non-NVIDIA hardware.

Each gate needs a written result, the exact SDK/commit, status JSON and screenshots or captured
frames. Do not publish a gain from a gate that ran with a GUI open, a competing GPU client or a
different pack.
