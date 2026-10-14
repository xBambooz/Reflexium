# Official frame-generation reference

Research requested from Gemini 3.8, reviewed against the pinned vendor SDKs and runtime probes on
2026-10-13. This file records API evidence. Current implementation and acceptance results are in
[FRAME_GENERATION.md](FRAME_GENERATION.md); isolated capability results are in
[FRAME_GENERATION_PROBE.md](FRAME_GENERATION_PROBE.md).

## Evidence rules

- **Documented** means supported by a linked vendor or Khronos source.
- **Local SDK inspection** means verified in the installed, pinned headers or sample source.
- **Measured** means a named local probe or Minecraft test actually ran.
- A capability flag, context creation, successful dispatch, image readback and physical scanout are
  separate checks. One does not establish the others.

## Hardware and runtime distinctions

| Feature | Documented hardware boundary | Integration decision |
| --- | --- | --- |
| DLSS Super Resolution | RTX GPUs | Independent of FG selection |
| DLSS standard FG | Supported RTX 40/50 series | Query FrameGeneration.Available on Minecraft's initialized NGX device |
| DLSS Multi Frame Generation | Supported RTX 50 series | Query MultiFrameCountMax; never infer the multiplier from the GPU name |
| FSR 3.1.4 FG | Cross-vendor Vulkan, including supported Radeon RX 5000+ and RTX 20+ GPUs | Validate context creation and presentation support; GPU family alone is insufficient |

Sources: [NVIDIA DLSS](https://developer.nvidia.com/rtx/dlss),
[NVIDIA FG integration guide](https://github.com/NVIDIA-RTX/Streamline/blob/main/docs/ProgrammingGuideDLSS_G.md),
[AMD FSR3](https://gpuopen.com/fidelityfx-super-resolution-3/).
NVIDIA documents Windows hardware-accelerated GPU scheduling as a prerequisite. Runtime capability
checks must still decide availability. AMD's recommendation of about 60 base FPS is a quality and
latency recommendation, rather than a mandatory context-creation gate.

Static MFG and Streamline Dynamic MFG are distinct. The current Streamline guide excludes Vulkan
from Dynamic MFG support. Its current VSync-with-FG support is also D3D12-only. Reflexium uses
static raw-NGX MFG and requires VSync off on the NVIDIA metering route.

Newer AMD ML frame-generation offerings are distinct from the pinned FSR 3.1.4 Vulkan provider.
Do not label the latter FSR 4/Redstone or infer unimplemented ML features from a product name.

## NVIDIA raw NGX API, local SDK inspection

Pinned SDK: NVIDIA/DLSS commit `374959484e79a640feaba44c93ac8cfb0a03f5b5`, version 310.9.1.
Relevant files: `nvsdk_ngx_defs_dlssg.h`, `nvsdk_ngx_helpers_dlssg_vk.h`, `nvsdk_ngx_defs.h`.

- `NGX_VK_CREATE_DLSSG` creates `NVSDK_NGX_Feature_FrameGeneration` on a Vulkan command buffer.
  `NGX_VK_EVALUATE_DLSSG` accepts input color, depth, motion and interpolated-output resources.
- `MultiFrameCountMax` counts generated frames, excluding the real frame. A value of five permits
  a total 6x output. Older runtimes lacking MFG support can still expose standard 2x FG.
- `MultiFrameCount` is the number of generated frames for the real-frame pair;
  `MultiFrameIndex` is one-based within that count. Evaluate each requested output index.
- `BackbufferFrameID` advances once per rendered backbuffer, including menus and frames with FG off.
- Camera matrices exclude temporal jitter. The optional constants include view/clip transforms,
  clip-to-previous-clip and inverse, camera position/basis, near/far planes, vertical FOV and aspect.
  NGX's FG jitter constants are in clip space. Its motion scale normalizes supplied vector units.
  These differ from the SR helper's pixel-jitter arguments.
- HUDless/UI/alpha inputs support vendor UI recomposition. An application can instead interpolate
  the scene and draw its current GUI on every output using the game's own GUI pipeline.
- Separate SR and FG contexts/parameter maps prevent per-feature state from being overwritten.
  Keep image resources valid until their recorded GPU work completes.
- NGX success is `0x00000001`; Reflexium's C ABI maps successful operations to zero.
  `FAIL_Denied` is `0xBAD00011`; `0xBAD00004` is FeatureNotFound.

Primary headers: [Vulkan FG helpers](https://github.com/NVIDIA/DLSS/blob/374959484e79a640feaba44c93ac8cfb0a03f5b5/include/nvsdk_ngx_helpers_dlssg_vk.h),
[FG parameters](https://github.com/NVIDIA/DLSS/blob/374959484e79a640feaba44c93ac8cfb0a03f5b5/include/nvsdk_ngx_defs_dlssg.h).

**Measured:** production NGX, Reflexium's unchanged project GUID, RTX 5070 and driver 617.42 report
FG available, no driver update needed, and a maximum of five generated frames on the actual Java
Minecraft device. Standard FG and all static multipliers through 6x evaluate and present in the
local debug integration. No application-ID spoofing or DRS profile edit was used. This is evidence
for this configuration; it does not promise availability on every application/driver combination.

## Presentation pacing, documented API

[NVIDIA present metering](https://docs.vulkan.org/refpages/latest/refpages/source/VkSetPresentConfigNV.html)
spaces a batch of up to eight presents in the driver. Enable the supported device feature and
provide the configuration on the batch's first present, rather than on every present. Capture
its feedback. The extension is provisional and needs driver/display validation.

[VK_NV_low_latency2](https://docs.vulkan.org/refpages/latest/refpages/source/VK_NV_low_latency2.html)
provides real-frame latency markers and out-of-band markers. Generated frames must not consume the
real simulation frame's present ID. Do not infer cross-vendor Streamline PC-latency support from
raw NVIDIA Vulkan markers.

The AMD Vulkan scheduler is a separate presentation route. Keep SDK worker submissions externally
synchronized with the application: dedicated queues avoid sharing a queue with unsynchronized
Minecraft submissions. Check physical queue counts and surface presentation support before use.

Issuing presents back-to-back and multiplying Minecraft's FPS counter does not establish smooth
output. Submission timestamps are also insufficient to certify NVIDIA hardware metering. NVIDIA's
FG guide calls for suitable FrameView displayed-frame timing. Current local scanout/latency
acceptance remains pending; see the implementation report.

## AMD FSR 3.1.4 Vulkan API, local SDK inspection

Pinned SDK: FidelityFX SDK v1.1.4, signed `amd_fidelityfx_vk.dll`.
Relevant files: `ffx_api.h`, `ffx_framegeneration.h`, `vk/ffx_api_vk.h`, and the official
`samples/fsrapi/fsrapirendermodule.cpp`.

- Create an independent frame-generation context with a Vulkan backend descriptor, display/max
  render size, backbuffer format and depth convention. Descriptor storage survives context use.
- Configure frame generation, prepare depth/motion/camera information, then dispatch interpolation.
  Use the same frame ID across configure, prepare and dispatch; advance it by exactly one per frame.
- Prepare camera basis/position through the camera-info chained descriptor, recommended by the
  installed header for best 3.1.4 quality. Supply the actual render resolution and pixel motion units.
- `NO_SWAPCHAIN_CONTEXT_NOTIFY` permits app-managed interpolation, independent of the vendor swapchain.
  The provider used here generates one intermediate frame. An outputs array of length four does not
  establish MFG support.
- The swapchain provider replaces create/destroy, image enumeration, acquisition and queue present.
  Its intermediate images use GENERAL layout, rather than a real presentation-image layout.
- The scheduler accepts a frame-generation callback, presentation callback and optional UI resource.
  A native callback can transfer an app-generated frame into its interpolation target. Keep queue
  ordering, image orientation/format and resource lifetime correct across both stages.
- Drain outstanding presentation work before destroying scheduler-referenced contexts/resources.
- The global provider-version query's `device` field is documented for DX12. Leave it null for the
  Vulkan global query. The Vulkan backend receives its physical/logical device separately.
- Promoted Vulkan entry points requested by KHR/EXT alias may require resolving the core name when
  the corresponding extension was not enabled on Minecraft's device.

Official documentation: [FSR 3.1.4 manual](https://gpuopen.com/manuals/fidelityfx_sdk/techniques/super-resolution-interpolation/),
[FidelityFX SDK](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK).

**Measured:** the signed Vulkan provider created and evaluated an independent FG context. Its
swapchain scheduler was also forced on the RTX 5070 to exercise that route: SDK present counts
advance at approximately twice the evaluated-frame count, with resize and world rejoin tested.
This does not replace testing on actual AMD/Intel hardware.

## Reference mods and source boundaries

Modrinth research followed [Radiance](https://modrinth.com/mod/radiance-mod-windows) to
[Minecraft-Radiance/Radiance](https://github.com/Minecraft-Radiance/Radiance), and
[Salt's Anti Aliasing](https://modrinth.com/mod/salts-anti-aliasing) to its linked source.
These demonstrated the strategies of decoupled interpolation, swapchain interception, resource
capture and private presentation queues. No measured judder or driver-denial claim is made for
those projects. Their creative implementation code was not copied into Reflexium. Vendor headers,
official sample/API behavior and Reflexium's existing resource/FFM helpers define the implementation.
