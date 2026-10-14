# Frame generation capability probe

Measured 2026-10-13 on Ryan's RTX 5070, Windows 11 build 26100.9278, NVIDIA driver 617.42.
This is capability/context-creation evidence, not a functioning Minecraft FG implementation.

## Test setup

An isolated `build/fg-probe.exe` created its own Vulkan 1.3 instance/device and graphics command
buffer. It used Reflexium's existing `rx_init` implementation, the unchanged Reflexium project ID
`4f6b2a0e-6d51-4c8a-9a7e-7c1f3d2b9e15`, SDK 310.9.1's static NGX library and the production DLLs in
`run/reflexium/`. No application-ID spoofing, DRS profile edit or developer DLL was used. The probe
did not acquire a swapchain or evaluate generated images. It did not modify or close a Minecraft
process. Raw diagnostic sources/binaries/logs are ignored under `build/fg-probe*`.

The device enabled its supported Vulkan 1.1/1.2/1.3 feature structs and the supported extensions
from this list: swapchain, NVX binary import, NVX image-view handle, push descriptor, external memory
Win32, external semaphore Win32, and NV CUDA kernel launch. Minecraft's actual enabled features
and extensions still need their own check before in-game FG initialization.

## NVIDIA results

Queries used the capability parameter map returned by `NVSDK_NGX_VULKAN_GetCapabilityParameters`.
NGX uses `0x00000001` for success. Reflexium's C bridge maps successful initialization to zero.

```text
NGX_init=0x00000000
SuperSampling.Available query=0x00000001 value=1
FrameGeneration.Available query=0x00000001 value=1
FrameGeneration.FeatureInitResult query=0x00000001 value=1
FrameGeneration.NeedsUpdatedDriver query=0x00000001 value=0
MultiFrameCountMax query=0x00000001 value=5
FG_create=0x00000001 handle=present
submit=0
```

`NGX_VK_CREATE_DLSSG` used 960x540 and `VK_FORMAT_R8G8B8A8_UNORM`. Its creation command buffer was
submitted and the queue completed before release. The SDK defines MultiFrameCountMax as the number
of generated frames, so 5 means up to 6x total frames. This does not prove successful evaluation at
every multiplier or driver pacing quality. It disproves a blanket claim that the installed production
runtime denies every unregistered executable or always reports a Vulkan maximum of one.

The SDK's `NVSDK_NGX_Result_FAIL_Denied` is `0xBAD00011`, not `0xBAD00004`. No Denied result was
observed in these runs. The test executable is not Java, so capability checks must also run on the
actual Minecraft device before exposing modes to players.

## AMD results

The probe dynamically loaded SDK 1.1.4's signed `PrebuiltSignedDLL/amd_fidelityfx_vk.dll` and queried
the frame-generation provider, then created an independent frame-generation context:

```text
FSR_FG_providers query=0 count=1
FSR_FG_create=0 handle=present
Probe exit: 0
```

Creation used a Vulkan backend descriptor, depth-inverted flag, 960x540 display/max render size,
and RGBA8 UNORM backbuffer format. The GDPA wrapper resolves promoted KHR/EXT function aliases to
core names when necessary, matching Reflexium's existing FSR bridge. The global version query left
`device` null: the header documents that field for DX12; it is not a Vulkan-device argument.

No FSR prepare/interpolate dispatch, UI composition, swapchain replacement or pacing was tested.

## Interpretation

Static DLSS MFG is a candidate for implementation on this machine, with runtime capability gating.
Do not confuse it with Streamline Dynamic MFG, whose current guide lists Vulkan as unsupported for
dynamic multiplier selection. FSR FG is also a candidate; successful context creation alone does not
establish a completed integration. Both require end-to-end evaluation and presentation tests.

Primary API evidence: [NVIDIA Vulkan FG helpers](https://github.com/NVIDIA/DLSS/blob/374959484e79a640feaba44c93ac8cfb0a03f5b5/include/nvsdk_ngx_helpers_dlssg_vk.h),
[NGX FG capability definitions](https://github.com/NVIDIA/DLSS/blob/374959484e79a640feaba44c93ac8cfb0a03f5b5/include/nvsdk_ngx_defs_dlssg.h),
[Streamline FG guide](https://github.com/NVIDIA-RTX/Streamline/blob/main/docs/ProgrammingGuideDLSS_G.md),
and the installed AMD `ffx_api.h`, `ffx_framegeneration.h` and `vk/ffx_api_vk.h` headers.
