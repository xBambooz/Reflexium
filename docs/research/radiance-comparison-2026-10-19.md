# Radiance vs Reflexium: Architectural Comparison and Adoptable Improvements

Date: 2026-10-19
Reference Project: Radiance (Minecraft-Radiance/Radiance, MCVR, Shaderpack-Advanced)
Target Project: Reflexium (Fabric Minecraft 26.3)

---

## Executive Summary

Radiance is a standalone C++ Vulkan hardware ray tracing engine (MCVR) coupled to a Java Fabric mod for Minecraft 1.20.1 to 1.21.4. It replaces the entire world renderer with a custom path tracer, utilizing raw NGX for DLSS Super Resolution (SR), Ray Reconstruction (RR), and Frame Generation (FG), while retaining OpenGL for UI rendering via Vulkan-OpenGL shared memory interop.

Reflexium is a native Fabric client mod for Minecraft 26.3 targeting Mojang's native Vulkan backend (RenderPearl) and modern shader mods (Sodium, Iris, Vitrail). Reflexium integrates DLSS SR, DLSS FG/MFG up to 6x via raw NGX, AMD FSR 3.1.4 upscaling and frame generation, and NVIDIA Reflex via VK_NV_low_latency2.

While Radiance excels at ray-traced lighting, HDR integration, and per-vertex entity motion history inside its own C++ engine, Reflexium is vastly superior in frame pacing (hardware VK_NV_present_metering), latency reduction (full VK_NV_low_latency2 Reflex integration), swapchain acquire synchronization, and native Vulkan 26.3 integration without OpenGL interop overhead.

---

## Feature Comparison Matrix

### 1. Minecraft Version and Render Backend

| Aspect | Radiance Approach | Reflexium Approach | Comparison and Evaluation |
| --- | --- | --- | --- |
| **Minecraft Version** | 1.20.1, 1.21.1, 1.21.4 (26.x port listed in progress in README.md). | Minecraft 26.3 client. | Reflexium natively targets modern Minecraft 26.3. Radiance remains on 1.21.4. |
| **Render Backend** | Custom C++ Vulkan 1.4 ray tracer (MCVR). Minecraft UI renders in OpenGL; interop bridges UI into Vulkan. | Native Mojang Vulkan backend (RenderPearl), with Sodium, Iris, and Vitrail hooks. | Reflexium is far lighter and integrates directly with Minecraft 26.3's native Vulkan engine. Radiance requires an entire C++ game engine replacement and complex OpenGL-Vulkan interop. |
| **Renderer Architecture** | Standalone path tracer replacing vanilla WorldRenderer with ray tracing pipelines (BVH, hit shaders). | Hooked rasterizer modifying vanilla / Sodium / Iris pipelines in-place. | Radiance owns all geometry and rays. Reflexium operates within standard Minecraft rendering and shader packs. |
| **Relevant Files** | `radiance/src/main/java/com/radiance/client/proxy/vulkan/RendererProxy.java`<br>`mcvr/src/core/render/opengl_ui/opengl_ui_surface.cpp`<br>`mcvr/src/core/render/render_framework.cpp` | `src/client/java/com/bambooz/reflexium/vulkan/VulkanDevice.java`<br>`docs/DLSS.md`<br>`gradle.properties` | **Reflexium is better** for standard Minecraft modding, compatibility, and overhead. Radiance is only applicable if replacing Minecraft with a pure path tracer. |

---

### 2. SDK Integration (Streamline vs Raw NGX)

| Aspect | Radiance Approach | Reflexium Approach | Comparison and Evaluation |
| --- | --- | --- | --- |
| **Integration Route** | Raw NGX C++ headers (`nvsdk_ngx_helpers_vk.h`, `nvsdk_ngx_helpers_dlssg_vk.h`) linked to `nvsdk_ngx_s.lib`. | Raw NGX via lightweight C++ bridge DLL (`reflexium_ngx.dll`) linked to `nvsdk_ngx_s.lib`, loaded through Java FFM. | Both projects correctly bypass NVIDIA Streamline in favor of raw Vulkan NGX calls. |
| **DLL Discovery** | Scans `<instance>/radiance/dlss/` or driver locations for feature DLLs. | Scans `<instance>/reflexium/`, driver path, or downloads pinned NVIDIA signed DLLs if missing. | Reflexium provides automated verified downloading with SHA-256 integrity checks. |
| **Relevant Files** | `mcvr/src/core/render/modules/world/dlss/dlss_wrapper.cpp`<br>`mcvr/src/core/render/dlss_frame_generation.cpp` | `native/src/reflexium_ngx.cpp`<br>`src/client/java/com/bambooz/reflexium/dlss/DlssBridge.java`<br>`docs/DLSS.md` | **Reflexium is better**: Java 25 Foreign Function and Memory (FFM) binding keeps native code minimal and maintainable. |

---

### 3. Motion Vectors and Coverage

| Aspect | Radiance Approach | Reflexium Approach | Comparison and Evaluation |
| --- | --- | --- | --- |
| **Static Geometry** | Camera translation offset added back in primary ray discovery: `position + (worldUBO.cameraPos - lastWorldUBO.cameraPos)`. Re-projected to previous clip space. | Reconstructed from scene depth buffer via inverse view-projection and 6DOF camera translation (`DlssMath.reprojection`). | Tie for static blocks. Both correctly re-inject camera world translation into camera-relative clip coordinates. |
| **Moving Entities** | Exact per-vertex history: previous index buffer and vertex positions stored per primitive; interpolated via barycentrics and transformed by previous instance matrix. | Screen-space / bounding-box entity matrix reprojection via `ObjectMotion.java` / `ObjectHistory.java`. | **Radiance is better** for entities: exact per-vertex mesh history accounts for skeletal animations without ghosting. |
| **Translucent Blocks** | Traced directly as transmission ray hits in primary trace; linear depth and motion vectors generated for first solid hit. | Uses pre-translucent depth (`RenderTargets.getDepthTextureView(1)`) to establish background correspondence behind glass/water. | Radiance handles ray transmission natively. Reflexium uses pre-translucent depth pass to prevent foreground water from corrupting background terrain. |
| **Particles and Weather** | Deferred to `PostRenderModule`, rendered at display resolution *after* upscaling against upscaled depth. No motion vectors needed. | Rendered into main world target before upscaling; receives camera-only motion vectors. | **Radiance is better**: Deferring particles to a post-render pass avoids DLSS particle smearing and eliminates reactive mask tuning. |
| **Shader Deformation (Foliage)** | Foliage is static in chunk mesh; wind is simulated in ray tracer or material shaders. | Shader patch injects previous camera-relative contact bending and wind wave offset into BSL shader pack. | Reflexium has dedicated shader pack patch logic for vegetation deformation. |
| **Sky and Clouds** | Procedural atmosphere and volumetric ray-marched clouds. Screen-space reprojection in volume modules. | Camera-only motion vectors; clouds remain in upscaler input. | Radiance has specialized volumetric reprojection. |
| **Relevant Files** | `mcvr/src/shader/world/ray_tracing/internal/advanced/scene/motion.glsl`<br>`shaderpack-advanced/path/primary/discover.rgen`<br>`mcvr/src/core/render/modules/world/post_render/post_render_module.cpp` | `src/client/java/com/bambooz/reflexium/dlss/DlssMath.java`<br>`src/client/java/com/bambooz/reflexium/dlss/ObjectMotion.java`<br>`docs/validation/iq-overnight-2026-10-19.md` | **Radiance is better** on entity deformation and particle separation; **Reflexium is better** on shader pack integration without an engine rewrite. |

---

### 4. Jitter and Projection Handling

| Aspect | Radiance Approach | Reflexium Approach | Comparison and Evaluation |
| --- | --- | --- | --- |
| **Jitter Sequence** | Halton sequence centered around zero: `halton(++sequenceIndex) - vec2(0.5)`. | Halton(2,3) sequence with ratio-dependent phase counts (minimum 32 phases for DLSS). | Tie. Both conform to NVIDIA guidelines. |
| **Projection Injection** | Ray tracer shifts primary ray pixel center: `pixelCenter = flatPixel + 0.5 + cameraJitter`. Rasterized hand shifts clip space: `clipPosition.xy -= jitterNdc * clipPosition.w`. | Jitter matrix multiplied into camera perspective projection matrix. | Tie. Both correctly offset sub-pixel sampling in their respective pipelines. |
| **Evaluation Argument** | Passed negated to NGX: `InJitterOffsetX = -jitter.x`, `InJitterOffsetY = -jitter.y`. | Passed negated to NGX: `-jitterX`, `-jitterY`. | Tie. Both correctly invert jitter coordinates as required by the NGX specification. |
| **Relevant Files** | `mcvr/src/core/render/buffers.cpp`<br>`shaderpack-advanced/path/primary/hand.vert`<br>`mcvr/src/core/render/modules/world/dlss/dlss_wrapper.cpp` | `src/client/java/com/bambooz/reflexium/dlss/DlssMath.java`<br>`src/client/java/com/bambooz/reflexium/dlss/UpscalingRenderer.java` | **Tie**: Identical adherence to NVIDIA specifications. |

---

### 5. Depth Conventions

| Aspect | Radiance Approach | Reflexium Approach | Comparison and Evaluation |
| --- | --- | --- | --- |
| **Depth Format** | Linear view depth (R16F/R32F distance) converted via compute shader (`linear_to_device_depth.comp`) to device depth [0.0 = near, 1.0 = far]. | Native Minecraft 26.3 reverse-Z depth [1.0 = near, 0.0 = far] directly from Vulkan depth-stencil attachment. | Reflexium uses native hardware reverse-Z depth without an intermediate compute shader pass. |
| **NGX Inversion Flag** | `depthInverted = false` (standard depth 0 near, 1 far). | `depthInverted = true` (reverse-Z depth 1 near, 0 far). | Both configure NGX properly for their respective depth layouts. |
| **Precision** | Standard depth with float precision conversion. | Reverse-Z floating point gives superior precision at distant ranges. | **Reflexium is better**: Reverse-Z avoids near-plane bias and eliminates an unnecessary full-screen depth conversion pass. |
| **Relevant Files** | `mcvr/src/shader/world/upscaler/linear_to_device_depth.comp`<br>`mcvr/src/core/render/modules/world/dlss/dlss_sr_module.cpp` | `src/client/java/com/bambooz/reflexium/dlss/DlssBridge.java`<br>`src/client/java/com/bambooz/reflexium/dlss/DlssMath.java` | **Reflexium is better**: Higher depth precision and zero conversion compute overhead. |

---

### 6. HDR vs LDR Input and Exposure

| Aspect | Radiance Approach | Reflexium Approach | Comparison and Evaluation |
| --- | --- | --- | --- |
| **Color Stage** | Pre-tonemapped HDR color (`R16G16B16A16_SFLOAT`) with `NVSDK_NGX_DLSS_Feature_Flags_IsHDR`. | Post-tonemapped LDR color (`R8G8B8A8_UNORM`). Pre-exposure 1.0, auto-exposure off. | Radiance runs DLSS SR before tone mapping, following NVIDIA's recommended reference architecture. |
| **Exposure Pipeline** | Custom compute auto-exposure pipeline: `luminance_distribution.comp`, `luminance_measurement.comp`, and `tone_curve.comp` (AgX/filmic). | Consumes shader pack's internal exposure / tone mapping; lacks dedicated pre-exposure stage for DLSS. | Radiance provides full exposure adaptation and HDR tone mapping after upscaling. |
| **Relevant Files** | `radiance/src/main/resources/modules/dlss_sr.yaml`<br>`mcvr/src/core/render/modules/world/tone_mapping/tone_mapping_module.cpp` | `docs/DLSS.md`<br>`UPSCALING_QUALITY_SPEC.md`<br>`docs/validation/iq-overnight-2026-10-19.md` | **Radiance is better**: True HDR input preserves specular highlights and avoids color banding during reconstruction. |

---

### 7. Block Outline, HUD, and Crosshair Handling

| Aspect | Radiance Approach | Reflexium Approach | Comparison and Evaluation |
| --- | --- | --- | --- |
| **Block Outline** | Intercepted in Java (`EntityProxy.java`), passed to `PostRenderModule`, rasterized at display resolution against upscaled depth. | Rendered during main world render pass before DLSS SR; upscaled with world geometry. | **Radiance is better**: Rasterizing block selection outline at display resolution eliminates upscaler edge smearing and jagged lines. |
| **HUD / GUI Render** | Rendered in OpenGL at native display resolution, then composited onto Vulkan swapchain. | Rendered in Vulkan via Minecraft's `GuiRenderer` at display resolution after DLSS SR. | Reflexium is cleaner (pure Vulkan, no OpenGL bridge). |
| **Frame Generation UI** | Passes `pHudless` (world only) and `pUI` (UI texture with alpha) to NGX DLSS-G. NGX composites UI internally on GPU. | Replays CPU `GuiRenderer` draw commands onto every generated frame target. | **Radiance is better**: Native `pHudless` and `pUI` inputs avoid CPU draw call replay on every generated frame. |
| **Relevant Files** | `radiance/src/main/java/com/radiance/client/proxy/world/EntityProxy.java`<br>`mcvr/src/core/render/modules/world/post_render/post_render_module.cpp`<br>`mcvr/src/core/render/dlss_frame_generation.cpp` | `src/client/java/com/bambooz/reflexium/gui/GuiRenderer.java`<br>`docs/FRAME_GENERATION.md` | **Radiance is better** on block outline placement and FG `pUI` hardware composition. |

---

### 8. Mip Bias

| Aspect | Radiance Approach | Reflexium Approach | Comparison and Evaluation |
| --- | --- | --- | --- |
| **Method** | Ray cones (`ray_cone.glsl`) calculate analytical footprint in world space: `coneSpreadFromFov` based on render resolution. | Explicit negative sampler LOD bias: `log2(render / display) - 1.0` applied to all texture samplers. | Ray cones are suited for ray tracing; sampler LOD bias is suited for rasterization. |
| **Relevant Files** | `shaderpack-advanced/util/ray_cone.glsl` | `src/client/java/com/bambooz/reflexium/dlss/DlssMath.java` | **Tie**: Each mod uses the mathematically correct method for its respective rendering paradigm. |

---

### 9. Presets and Ray Reconstruction

| Aspect | Radiance Approach | Reflexium Approach | Comparison and Evaluation |
| --- | --- | --- | --- |
| **Architecture Presets** | Four modular pipeline presets: `RT_DLSSRR` (Ray Reconstruction), `RT_NRD_DLSS` (NRD denoiser + DLSS SR), `RT_NRD_FSR`, `RT_NRD_XeSS`. | Settings for upscaler mode (Off, DLSS, FSR), quality mode (DLAA to Ultra Performance), and frame generation. | Radiance structures its pipeline into distinct denoiser/upscaler combinations. |
| **DLSS Neural Presets** | Leaves NGX preset hints at 0 (default). | Explicitly sets NVIDIA Transformer preset hints (Preset E for CNN; K, L, M per quality mode for Transformer). | **Reflexium is better**: Fine-grained preset control ensures modern Transformer models run without relying on driver heuristics. |
| **Ray Reconstruction** | Fully implemented in `dlss_rr_module.cpp` using diffuse/specular albedo, normals, roughness, and hit distance. | Not supported. | Radiance supports DLSS RR because it has a full path tracer. DLSS RR is inapplicable to Reflexium's rasterized pipeline. |
| **Relevant Files** | `radiance/src/main/java/com/radiance/client/pipeline/Presets.java`<br>`mcvr/src/core/render/modules/world/dlss/dlss_rr_module.cpp` | `src/client/java/com/bambooz/reflexium/dlss/DlssModel.java`<br>`src/client/java/com/bambooz/reflexium/dlss/UpscalingRenderer.java` | **Reflexium is better** for DLSS model control. Radiance has RR only because of its path tracer. |

---

### 10. Sharpening

| Aspect | Radiance Approach | Reflexium Approach | Comparison and Evaluation |
| --- | --- | --- | --- |
| **DLSS Sharpening** | Not exposed or set in DLSS SR. Relies entirely on internal NGX defaults. (Sharpening only exposed in FSR3). | Configurable sharpening slider (0 to 100, default 20) with live validation against haloing and texture ringing. | Reflexium gives user control and validates edge energy. |
| **Relevant Files** | `mcvr/src/core/render/modules/world/dlss/dlss_sr_module.cpp` | `src/client/java/com/bambooz/reflexium/dlss/UpscalingRenderer.java`<br>`docs/validation/iq-overnight-2026-10-19.md` | **Reflexium is better**: User-tunable sharpness prevents over-soft or over-sharpened reconstruction. |

---

### 11. FG Pacing, Presentation Path, and Caps

| Aspect | Radiance Approach | Reflexium Approach | Comparison and Evaluation |
| --- | --- | --- | --- |
| **Presentation Loop** | Synchronous sequential loop: acquires swapchain image, copies interpolated image, calls `vkQueuePresentKHR` for each generated frame, then presents real frame. | Batched presentation on graphics queue with metered configs. FSR uses AMD signed Vulkan swapchain scheduler. | Reflexium avoids sequential acquire stalls. |
| **Hardware Metering** | None. No `VK_NV_present_metering` support. | Uses `VK_NV_present_metering` (`VkSetPresentConfigNV`) to schedule paced scanout directly in NVIDIA display hardware. | **Reflexium is vastly superior**: True hardware-paced display cadence without host timing jitters. |
| **Synchronization** | Relies on standard fences and CPU waits (`vkWaitForFences`). | Explicit completion fences (`reflexium$acquireCompletion`) tied to command submissions; 16 reserved binary acquire semaphores. | **Reflexium is vastly superior**: Eliminates pool stalls, command buffer overwrite hazards, and acquire semaphore reuse conflicts. |
| **Frame Rate Caps** | Host CPU sleep: `std::this_thread::sleep_until` in `Framework::limitFrameRate`. | Dynamic refresh headroom target (95% to 80% hysteresis), status telemetry, and user cap enforcement. | Reflexium manages FG activation headroom smoothly without introducing CPU sleep jitter. |
| **Relevant Files** | `mcvr/src/core/render/render_framework.cpp` (lines 530-580, 1340-1420) | `docs/FRAME_GENERATION.md`<br>`docs/validation/fg-overnight-2026-10-19.md` | **Reflexium is vastly superior** in pacing, sync, and hardware scheduling. |

---

### 12. Reflex Markers and Sleep Placement

| Aspect | Radiance Approach | Reflexium Approach | Comparison and Evaluation |
| --- | --- | --- | --- |
| **VK_NV_low_latency2** | Not implemented. Zero Reflex code or latency markers. | Fully integrated. Supports Reflex Off, On, and On + Boost. | Reflexium drastically lowers input latency. Radiance has no latency management. |
| **Latency Markers** | None. | Marks simulation start, simulation end, render submit start, and out-of-band markers for generated presents. | Reflexium gives the NVIDIA driver complete visibility into frame execution boundaries. |
| **Sleep Placement** | None. | Places `vkLatencySleepNV` prior to simulation start; isolates sleep from rendered-only throughput metrics. | Reflexium ensures minimal latency queue depth before input sampling. |
| **Relevant Files** | None (absent). | `src/client/java/com/bambooz/reflexium/reflex/ReflexController.java`<br>`docs/FRAME_GENERATION.md` | **Reflexium wins completely**. |

---

### 13. License

| Repository | License | Impact on Reflexium |
| --- | --- | --- |
| **Reflexium** | MIT License (`LICENSE`) | Permissive. |
| **Radiance (Java Mod)** | GNU General Public License v3 (`LICENCE`) | Incompatible copyleft. Code cannot be copied or ported directly. |
| **MCVR (C++ Engine)** | GNU General Public License v3 (`LICENSE.md`) | Incompatible copyleft. Modifications in `dlss_wrapper.cpp` are explicitly licensed under GPLv3. |
| **Shaderpack-Advanced** | GNU General Public License v3 (`LICENSE`) | Incompatible copyleft. |

---

## Prioritized List of Adoptable Improvements for Reflexium

### 1. Hardware UI Composition via pHudless and pUI in DLSS-G
- **Priority**: High (Performance and CPU overhead)
- **Radiance Reference**: `mcvr/src/core/render/dlss_frame_generation.cpp` (lines 214 to 230, 277 to 278)
- **Concept**:
  Currently, Reflexium replays Minecraft's `GuiRenderer` draw commands on the CPU for every generated frame (2x to 6x), resulting in multiple CPU draw passes and GPU state transitions per present.
  NGX DLSS-G natively accepts `evalParams.pHudless` (the world render before GUI) and `evalParams.pUI` (the GUI color buffer with alpha channel). When provided, the NGX frame generation pipeline automatically composites the UI onto the generated frame on the GPU.
- **Porting Strategy (Clean-Room)**:
  Reflexium already separates the world render target from the final presentation target. Rather than clearing and re-rendering `GuiRenderer` into every generated swapchain image, render the GUI once into a dedicated full-resolution RGBA8 target with alpha transparency. Pass the pre-GUI world target as `pHudless` and the UI target as `pUI` to `NVSDK_NGX_VK_DLSSG_Eval_Params`.
- **License Note**: Must be implemented directly from the NVIDIA NGX DLSS-G SDK specification (`nvsdk_ngx_defs_dlssg.h`), without copying MCVR code.

### 2. Explicit Sub-Rectangle Extents for DLSS-G Evaluation
- **Priority**: High (Robustness and Dynamic Resolution)
- **Radiance Reference**: `mcvr/src/core/render/dlss_frame_generation.cpp` (lines 272 to 278)
- **Concept**:
  In `NVSDK_NGX_DLSSG_Opt_Eval_Params`, Radiance explicitly populates:
  - `mvecsSubrectSize = {inputs.renderWidth, inputs.renderHeight}`
  - `depthSubrectSize = {inputs.renderWidth, inputs.renderHeight}`
  - `backbufferSubrectSize = {inputs.displayWidth, inputs.displayHeight}`
  - `hudLessSubrectSize = {inputs.displayWidth, inputs.displayHeight}`
  - `uiSubrectSize = {inputs.displayWidth, inputs.displayHeight}`
- **Porting Strategy (Clean-Room)**:
  Update `reflexium_ngx.cpp` to populate these sub-rectangle dimensions in the evaluate parameters. This prevents NGX from making driver-level assumptions about buffer padding or full-extent texture dimensions.
- **License Note**: Pure API parameter assignment from standard NGX headers.

### 3. Tighter Discontinuity and Camera Cut Reset Thresholds
- **Priority**: Medium (Temporal Image Quality)
- **Radiance Reference**: `mcvr/src/core/render/modules/world/dlss/dlss_sr_module.cpp` (lines 425 to 435)
- **Concept**:
  Reflexium's `DlssMath.java` uses `RESET_DISTANCE = 8.0` blocks and `CUT_ANGLE = 60.0` degrees before signaling `InReset = 1`. An 8-block jump or rapid snap-turn can allow severe ghosting trails across teleports, spectator flying, or respawns before triggering a reset.
  Radiance detects cuts when position delta exceeds 1.0 block (`positionDelta > 1.0f`) or view direction dot product drops below 0.866 (`directionDot < 0.866f`, representing a 30-degree rotation in a single frame).
- **Porting Strategy (Clean-Room)**:
  Refine `DlssMath.java` to tighten `RESET_DISTANCE` to 1.5 to 2.0 blocks and reduce rotation threshold to 30 to 45 degrees for high-sensitivity cut detection.
- **License Note**: Mathematical threshold adjustment in existing Reflexium code.

### 4. Post-Upscale Rasterization for World-Space Overlays (Block Selection Outline)
- **Priority**: Medium (Visual Polish and Edge Stability)
- **Radiance Reference**: `radiance/src/main/java/com/radiance/client/proxy/world/EntityProxy.java` (lines 440 to 520), `mcvr/src/core/render/modules/world/post_render/post_render_module.cpp` (lines 40 to 70)
- **Concept**:
  Thin 1-pixel geometric lines like Minecraft's black block selection outline suffer from temporal shimmer, thickening, and ghosting when passed through DLSS SR at render resolution.
  Radiance intercepts block outline drawing in Java and routes it to `PostRenderModule`, rasterizing the outline at native display resolution *after* DLSS SR against the upscaled depth buffer.
- **Porting Strategy (Clean-Room)**:
  In Minecraft 26.3, separate `WorldRenderer.renderTargetBlockOutline` so that it renders after DLSS SR into the full-resolution target, performing depth testing against the upscaled depth attachment.
- **License Note**: Fabric mixin hook into Minecraft's `GameRenderer`/`WorldRenderer`.

### 5. Robust Depth Normalization and Linear Depth Conversion Utility
- **Priority**: Low (Future HDR / Custom Pipeline Preparation)
- **Radiance Reference**: `mcvr/src/shader/world/upscaler/linear_to_device_depth.comp`
- **Concept**:
  Radiance uses a clean compute pass to clamp and project linear depth into standard [0.0, 1.0] device depth while explicitly mapping invalid, NaN, and sky pixels to 1.0 (far plane).
- **Porting Strategy (Clean-Room)**:
  While Reflexium currently uses native reverse-Z depth directly from the Vulkan depth buffer, having a fallback compute conversion pass will be necessary when integrating pre-tonemapped HDR stages or custom shader pack depth targets that export linear depth.
- **License Note**: Standard perspective projection math (`(far / (far - near)) * (1.0 - near / depth)`).

---

## Non-Applicable Radiance Features

1. **DLSS Ray Reconstruction (RR)**:
   Radiance can use DLSS RR because MCVR is a hardware ray tracer that produces separate diffuse/specular albedo, normals, roughness, and hit distances. Minecraft 26.3 with Iris/Sodium is a rasterized deferred/forward renderer. Standard shader packs do not export the requisite ray-tracing hit-distance buffers; DLSS RR is fundamentally inapplicable without a custom path-tracing engine.
2. **Per-Vertex Entity History Buffers**:
   Radiance manages raw GPU vertex/index buffer allocations in C++, maintaining previous-frame vertex allocations for every mob and entity to perform per-vertex barycentric motion vector evaluation. In Minecraft 26.3 Java, duplicating and retaining all dynamic entity vertex buffers across frames on the CPU is impractical; matrix-based entity reprojection is the appropriate rasterization technique.
3. **OpenGL-Vulkan Interop Bridge (`OpenGLUiBridge`)**:
   Radiance requires an interop layer because it runs Vulkan world rendering alongside an OpenGL UI. Minecraft 26.3's RenderPearl backend is 100% native Vulkan, making OpenGL interop completely irrelevant.

---

## License Compatibility Verdict

- **Reflexium License**: MIT License (`LICENSE`).
- **Radiance License**: GNU General Public License v3 (`LICENCE` and `mcvr/LICENSE.md`).
- **Verdict**:
  Reflexium **must not** copy, port, or adapt source code directly from Radiance, MCVR, or Shaderpack-Advanced. Doing so would constitute a derivative work under GPLv3, legally requiring Reflexium to be re-licensed under GPLv3.
  All improvements identified above must be implemented as clean-room additions derived solely from the official NVIDIA NGX Programming Guide, Vulkan API specifications, and standard mathematical formulations.
