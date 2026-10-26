# Iris and native Vulkan, 2026-10-15

Corrected after inspection of the user-supplied experimental artifact. The earlier conclusion that no Iris Vulkan build exists was wrong: inspection of stable Iris 1.11.7 established only that release's OpenGL dependency.

## Verified artifacts

| Artifact | Verified contract | Reflexium compatibility |
| --- | --- | --- |
| Stable Iris 1.11.7 for 26.3 | OpenGL-specific IrisRenderSystem, LWJGL GL calls and GlDevice cast. Sodium 0.9.x, LGPL-3.0-only. [Modrinth release](https://modrinth.com/mod/iris/version/vTN4NRGW). | Does not supply the native Vulkan route. |
| User-supplied C:/Users/Ryan/Downloads/iris-fabric-experimental-build-26.3.jar | SHA-256 f16cc85cfb253ac0a0ca0f08fda3363842ec870e21e6240f6fa5c2ae02032ade; metadata 1.12.0-snapshot+mc26.3-local, Loader >=0.19.0, Sodium 0.9.x, LGPL-3.0-only. No Minecraft dependency constraint declared. Bytecode confirms native RenderPearl Vulkan selection in GraphicsBackend and the game Vulkan device/encoder in VulkanCommandBridge. | Candidate for a separate optional adapter. No Reflexium runtime, lifecycle, visual or performance acceptance yet. |
| Vitrail 0.12.0-beta+mc26.3 | Existing tested native Vulkan path, SHA-256 0823ba8ff806e9a1cc8520b7410783683d3a5f2213576212cecdeecc869bcfc8. | Retained for the current pipeline work. |

The [official Iris announcement](https://x.com/IrisShaders/status/2110779396181360739) describes the Discord experimental Vulkan build. Its [follow-up](https://x.com/IrisShaders/status/2110779397246722167) confirms existing shader-pack support, with most expected to work, and reserves built-in DLSS and RT for Aperture. This supersedes the earlier claim that existing-pack compatibility was absent. The parent's announcement inspection is retained as supplied evidence; direct retrieval during this continuation returned HTTP 403. Local artifact inspection independently establishes executable native Vulkan code, not successful Reflexium integration.

## Candidate integration boundaries

| Concern | Inspected entry point | Verification still needed |
| --- | --- | --- |
| Linear scene and post chain | IrisRenderingPipeline.finalizeLevelRendering runs composites then FinalPassRenderer.renderFinalPass. | Exact pack HDR attachment, exposure, tone mapping, input/output sizes and pass lifetimes. A final-pass hook alone does not prove early HDR insertion. |
| Color conversion | finalizeGameRendering applies color-space conversion. | One conversion, correct HUD/hand ordering and completed final color for FG. |
| Depth | RenderTargets exposes depth, pre-translucent depth and pre-hand depth as GpuTexture. | Formats, reverse-Z convention, resource layouts and lifetime through SR/FG. |
| Vulkan execution | GraphicsBackend reads RenderPearl DeviceInfo.backendName; VulkanCommandBridge exposes device and encoder. | Queue/submission ownership, synchronized image access and recreation behavior. |
| Temporal ownership | Existing packs supported by experimental loader. | Projection/previous-projection timing, one jitter source, pack TAA bypass, shader-deformation vectors and source guards. Vitrail mixins do not transfer automatically. |

Retain Vitrail while the current pipeline task is unfinished. Evaluate the pinned Iris artifact separately before any renderer replacement, including Complementary visual sequences, fallback, resize/reload/rejoin and FG final-color handoff. No GPU benchmark or runtime test was performed for this research. Announcement compatibility is not a measured performance gain.