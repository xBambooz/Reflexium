# DLSS in Reflexium: findings and plan

Status (1.1.0-alpha, 2026-10-09): DLSS prerequisites are enabled and reported. No upscaling yet.

## How Radiance does it

Radiance (Java mod) + MCVR (its C++ renderer, GPLv3) ship DLSS Super Resolution, Ray Reconstruction and Frame Generation.

- **Native NGX.** MCVR links NVIDIA's static `nvsdk_ngx` library and calls NGX from C++ through JNI (`dlss_wrapper.cpp`, based on NVIDIA's Apache-2.0 `vk_denoise_dlssrr` sample). Feature DLLs (`nvngx_dlss*.dll`) are not shipped; players drop them into `<instance>/radiance/`.
- **Motion vectors from its own renderer.** MCVR replaces Minecraft's world rendering with a ray tracer. Every hit keeps the previous frame's vertex positions (per-geometry previous index/vertex buffers) and previous object-to-world matrices (`shader/world/ray_tracing/internal/advanced/scene/motion.glsl`). Motion = current clip position minus previous clip position with the full previous view-projection, so camera translation, camera rotation and moving entities are all exact.
- **Inputs.** Render-resolution color, linear depth converted to device depth, motion vectors, `MVLowRes`, jitter passed negated, `InReset` on camera cuts (more than 1 block or 30 degrees between frames).
- **Frame generation** uses raw NGX DLSS-G (`NGX_VK_CREATE_DLSSG` / `EVALUATE_DLSSG`) with camera matrices, HUD-less color and UI alpha, and paces generated frames itself. It does not use Reflex.

## Why the old Reflexium DLSS had broken motion

From the earlier attempt's notes: motion vectors were written by a fullscreen reprojection pass using `getViewRotationProjectionMatrix`, which is **rotation only**. Any camera translation (walking, falling, riding) produced wrong vectors, so DLSS smeared and ghosted whenever the player moved. Entities had no motion at all. Radiance avoids both problems because it owns the geometry.

## What was tried this time

Calling the driver's `_nvngx.dll` exports directly from Java (FFM), to avoid a native build:

- `NVSDK_NGX_VULKAN_GetFeatureDeviceExtensionRequirements` before init: access violation (null read) inside `_nvngx.dll`.
- `NVSDK_NGX_VULKAN_Init_ProjectID` after device creation: access violation (`0xFFFFFFFFFFFFFFFF`) inside `_nvngx.dll`.

The export signatures match the public SDK (confirmed against OptiScaler's proxy), so the static library must pass state the exports rely on (most likely `FeatureCommonInfo.InternalData`). Conclusion: DLSS needs a native bridge linked against `nvsdk_ngx_s.lib`, like MCVR. That requires MSVC (Visual Studio Build Tools), which is not installed on the dev machine.

## What 1.1.0 already does

- Enables `VK_NVX_binary_import` and `VK_NVX_image_view_handle` on Minecraft's VkDevice when supported (`VK_KHR_push_descriptor` is already vanilla).
- Locates the driver's NGX core the same way NVIDIA's loader does.
- Reports which feature DLLs are present in `<instance>/reflexium/`.

## Plan for vanilla Vulkan (26.3)

Facts from the 26.3 jar:

- `GameRenderer.render()` draws the world into `mainRenderTarget`, applies post effects, clears depth, then draws the GUI into the same target. DLSS slots in after `applyPostEffects()` and before `GuiRenderer.render()`.
- `VulkanGpuTexture.vkImage()` and `VulkanGpuTextureView.vkImageView()` expose the handles NGX needs. `VulkanCommandEncoder` holds the current `VkCommandBuffer` and synchronises with global memory barriers.

Steps:

1. **Native bridge** (`reflexium_ngx.dll`, MSVC + `nvsdk_ngx_s.lib`, JNI or FFM): init on Minecraft's device, create/evaluate/release SR, shutdown. Start from NVIDIA's Apache-2.0 `vk_denoise_dlssrr` wrapper, not MCVR's GPL changes.
2. **Render resolution split**: world into a render-resolution target, DLSS output into the display-resolution `mainRenderTarget`, GUI drawn after at full resolution.
3. **Jitter**: Halton(2,3) sub-pixel offsets in the level projection matrix, negative texture LOD bias of `log2(render/display)`.
4. **Motion vectors**, the part that broke last time:
   - Camera motion from depth with the **full** previous and current view-projection matrices, including camera translation (camera-relative rendering means the previous camera position must be added back).
   - Entity motion needs per-object previous transforms. On vanilla this means a velocity output on entity/block-entity pipelines with last frame's pose. Without it, DLSS ghosts moving mobs; acceptable for a first version, flagged in F3.
   - Reset on teleport, dimension change, resize and camera cuts.
5. **Frame generation** only after SR is solid: needs HUD-less color and UI alpha, which the render split in step 2 provides. Reflex is already in place.

## What Aperture needs to expose

Aperture packs own their targets and final combination pass. The public pack API (`IrisShaders/Aperture-Example-Pack`, `iris.d.ts`, Jan 2026) has no jitter, motion vector or upscaler hook. For DLSS under Aperture, Reflexium needs either Aperture-native upscaling or a contract such as: a render scale, a per-frame jitter offset Aperture applies to `WorldState.projection()`, a pack-written motion vector texture and depth texture by name, and a hook between the pack's last world pass and the GUI. Reflex needs nothing from Aperture.
