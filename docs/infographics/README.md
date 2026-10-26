# Reflexium rendering pipeline infographics

Visual reference diagrams illustrating how the Reflexium Minecraft Fabric mod integrates NVIDIA Reflex latency reduction, DLSS neural upscaling, AMD FidelityFX FSR 3.1, and sub-pixel motion vectors with Minecraft 26.3's Vulkan renderer.

---

### [01-frame-timeline.png](file:///C:/Users/Ryan/source/repos/Reflexium/docs/infographics/01-frame-timeline.png)
**Reflexium frame timeline**
This diagram maps the sequential execution order of a single rendered frame from driver sleep to swapchain presentation. It details each NVIDIA Reflex latency marker injection point and traces the eight sub-steps of the world render submission pass.

---

### [02-reflex.png](file:///C:/Users/Ryan/source/repos/Reflexium/docs/infographics/02-reflex.png)
**How Reflex lowers latency**
This infographic explains how just-in-time CPU pacing eliminates the GPU command backlog that causes input delay in un-paced pipelines. It outlines the operating modes and breaks down the driver telemetry metrics reported in the F3 overlay. The VRR auto-cap formula shown in the older image is no longer implemented.

---

### [03-upscaling.png](file:///C:/Users/Ryan/source/repos/Reflexium/docs/infographics/03-upscaling.png)
**Upscaling pipeline and render-resolution swapping**
This chart shows how Reflexium swaps internal render targets so the 3D world renders at reduced resolutions while preserving native pixel resolution for the user interface. It lists the four essential inputs required by temporal upscalers and compares DLSS versus FSR 3.1 scaling presets and neural model architectures.

---

### [04-jitter-and-motion-vectors.png](file:///C:/Users/Ryan/source/repos/Reflexium/docs/infographics/04-jitter-and-motion-vectors.png)
**Sub-pixel jitter and motion vector reconstruction**
This graphic illustrates Halton temporal sampling, camera-relative depth reprojection, vanilla and Vitrail hand masks, and the implemented second pass that replays animated entity and block-entity geometry into visible surface velocity. It includes the transparent, unmatched topology and shader-deformation limits.

---

### [05-components-map.png](file:///C:/Users/Ryan/source/repos/Reflexium/docs/infographics/05-components-map.png)
**Reflexium system architecture**
This architectural map connects Minecraft 26.3's Vulkan engine, Reflexium Java mixins and controllers, native C++ bridges, and the GPU driver. It includes Vitrail scale/TAA/jitter ownership, same-session DLSS runtime installation, and the reusable standalone MCP Minecraft test harness.
