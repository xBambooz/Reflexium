# Nightly Code Review: October 19, 2026

Review of merged commits `d9e7398..HEAD` focusing on Vulkan resource lifetime, null safety, synchronization, layout correctness, config migration, Reflex markers, and shader compatibility.

## Findings

### Finding 1: Unbounded per-frame Reflex marker regression log spam
- Location: `src/client/java/com/bambooz/reflexium/reflex/ReflexController.java:532-536`
- Failure scenario: `ReflexController.marker()` compares `rank <= lastMarkerRank` for the same frame. When a marker regression occurs, `ReflexiumClient.LOGGER.warn(...)` is invoked every time without rate limiting or count capping. At high framerates (144 to 300+ FPS), this emits hundreds of log messages per second, saturating disk logs and causing severe CPU render thread hitching.
- Confidence: High
- Status: Fixed

### Finding 2: Render pass binds depth attachment without pipeline depth target state
- Location: `src/client/java/com/bambooz/reflexium/dlss/SharpenPass.java:59`
- Failure scenario: `SharpenPass.render` creates a render pass passing `destination.getDepthTextureView()`. However, `PIPELINE` only specifies `ColorTargetState` and omits depth target state. Under Vulkan dynamic rendering, invoking a render pass with a depth attachment format when the pipeline was compiled with `depthAttachmentFormat = VK_FORMAT_UNDEFINED` violates VUID-vkCmdDraw-pipeline-06180. This triggers validation layer errors and potential GPU crashes on driver execution. RCAS is purely a color-space pass and should not bind the depth attachment.
- Confidence: High
- Status: Fixed

### Finding 3: Crash on null pipeline when Iris shaders are off or reloading
- Location: `src/client/java/com/bambooz/reflexium/compat/BslFoliageMotion.java:87-95, 77`
- Failure scenario: `BslFoliageMotion.surface()` reflects on `Iris.getPipelineManager().getPipelineNullable()`. When shaders are disabled, reloaded, or during pipeline transitions, `getPipelineNullable()` returns null. Calling `pipeline.getClass()` throws a `NullPointerException`. Even if caught by reflective handlers, it re-throws `IllegalStateException`, crashing the game during `UpscalingRenderer.afterLevel()`. In addition, `currentTime()` re-throws `IllegalStateException` on timer query failures, crashing `endFrame()` and debug telemetry.
- Confidence: High
- Status: Fixed

### Finding 4: Frame generation Auto multiplier normalizes DLSS MFG to DLSS FG
- Location: `src/client/java/com/bambooz/reflexium/config/ReflexiumConfig.java:95, 102, 114, 153` and `src/client/java/com/bambooz/reflexium/dlss/FrameGeneration.java:11`
- Failure scenario: `FrameGeneration.withMultiplier(int multiplier)` evaluated `multiplier > 2 ? DLSS_MULTI : DLSS`. When `multiplier == 0` (Auto default), it returned `DLSS`. On config load, `config.frameGeneration = config.frameGeneration.withMultiplier(config.frameGenerationMultiplier)` turned any saved `DLSS_MULTI` setting into `DLSS`. In the settings GUI, setting the multiplier to Auto immediately changed the dropdown from `DLSS MFG` to `DLSS FG`. In addition, `setFrameGeneration(DLSS)` forced multiplier to 0 instead of 2x, and `limitFrameGenerationMultiplier` failed to normalize `DLSS_MULTI` to `DLSS` on RTX 40 series hardware when multiplier was Auto.
- Confidence: High
- Status: Fixed

### Finding 5: Acquire completion fences leaked across swapchain recreation
- Location: `src/client/java/com/bambooz/reflexium/mixin/VulkanGpuSurfaceMixin.java:136`
- Failure scenario: Fences allocated in `reflexium$acquireCompletion` to guard acquire semaphore reuse are not closed or cleared when `destroySwapchain` is called during window resize or display configuration change. Uncompleted or dangling fence handles leak native Vulkan resources.
- Confidence: High
- Status: Fixed
