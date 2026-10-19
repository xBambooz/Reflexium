# Nightly Code Review Round 2: October 19, 2026

Correctness review of commits `96c7ba4` (post-upscale vanilla particles and rain), `1bd032b` (cloud vectors), `f50c9d6` (display depth jitter), `845aba3` (BSL foliage coverage clear), and `42ffea3` (particle replay after FG).

## Findings

### Finding 1: Shared depth texture mutation across generated outputs during particle replay
- Location: `src/client/java/com/bambooz/reflexium/dlss/DeferredEffects.java:70-84`
- Failure scenario: In `DeferredEffects.draw()`, replaying particle passes across outputs (`for (int i = -1; i < fg.generatedCount(); i++)`) used the same `target.getDepthTextureView()` with `OptionalDouble.empty()`. In Minecraft 26.3, quad particle pipelines (`RenderPipelines.OPAQUE_PARTICLE` and `RenderPipelines.TRANSLUCENT_PARTICLE`) inherit `PARTICLE_SNIPPET`, which sets `DepthStencilState.DEFAULT` (with `writeDepth = true`). When the real frame (`i = -1`) was rendered, particle draws wrote their depth into `target.getDepthTexture()`. On subsequent passes for generated frames (`i >= 0`), particles tested against this mutated depth buffer instead of the original world scene depth. Closer particles drawn in earlier iterations occluded overlapping particles drawn in subsequent iterations, causing missing particles and occlusion artifacts on generated frames.
- Confidence: High
- Status: Fixed in commit `4dd2aaf` (isolated per-output depth target copying display depth to each generated target prior to replay).

### Finding 2: Unconditional completeScene deferred particle replay when frame generation is inactive
- Location: `src/client/java/com/bambooz/reflexium/mixin/GameRendererMixin.java:93-98`
- Failure scenario: In commit `42ffea3`, `reflexium$upscale` guarded particle drawing with `if (!FrameGenerationRenderer.get().wantsMotion() || !DebugSettings.replayFgParticles)`. However, `reflexium$completeScene` invoked `DeferredEffects.draw(...)` unconditionally without checking if frame generation was active. When frame generation was OFF, `DeferredEffects.draw(...)` ran twice per frame. While `clear()` in `DeferredEffects.draw()` normally emptied pending state after the first execution, if display depth resolve failed or was delayed during upscale, `clear()` in the upscale call discarded pending effects before scene completion could run. Redundant invocations on non-FG frames violated the intended lifecycle where effects replay either before post-processing or after frame generation.
- Confidence: High
- Status: Fixed in commit `78ad57d` (guarded `completeScene` deferred effects draw with `wantsMotion() && replayFgParticles`).

### Finding 3: Wasted frame generation evaluation and particle replay passes before menu blur detection
- Location: `src/client/java/com/bambooz/reflexium/mixin/GameRendererMixin.java:91-97`, `src/client/java/com/bambooz/reflexium/mixin/GuiRendererMixin.java:24-28`
- Failure scenario: When the pause menu is opened, `reflexium$completeScene` executes frame generation evaluation (`sceneReady`) and deferred particle replay across all outputs. Only later in `GuiRenderer.render()` is menu blur detected (`draws.size() > firstDrawIndexAfterBlur`), which calls `FrameGenerationRenderer.get().skipGeneratedFrames()`. The generated frames are subsequently discarded to avoid the known `MappableRingBuffer` wrap crash in `processBlurEffect()`. While `skipGeneratedFrames()` prevents the crash, GPU time is spent evaluating interpolation and replaying particles into targets that are immediately dropped.
- Confidence: Medium
- Status: Open (pipeline sequencing: blur presence is determined during GUI preparation which occurs after scene completion).

### Finding 4: Unchecked null fog buffer binding in vanilla cloud motion pass
- Location: `src/client/java/com/bambooz/reflexium/dlss/VanillaCloudMotion.java:44`
- Failure scenario: `VanillaCloudMotion.capture()` passes `DeferredEffects.worldFog()` directly to `RenderSystem.setShaderFog()`. If `DeferredEffects.worldFog()` were null (for example, if `renderLevel()` bypassed fog setup or if `clear()` ran prematurely), `RenderSystem.setShaderFog(null)` sets internal shader fog to null. While `RenderSystem.bindDefaultUniforms()` guards against null slices, under Vulkan, rendering clouds without binding the expected uniform group would trigger validation errors if the active pipeline requires fog UBO descriptors.
- Confidence: Medium
- Status: Analyzed (in practice, `reflexium$particleFog` reliably populates `DeferredEffects.worldFog()` during `renderLevel` prior to `afterLevel()`).

### Finding 5: Reverse-Z display depth unjitter sampling alignment
- Location: `src/client/resources/assets/reflexium/shaders/core/dlss_depth_resolve.fsh:22`, `src/client/java/com/bambooz/reflexium/dlss/DepthResolvePass.java:53`
- Failure scenario: Potential coordinate mismatch when offsetting UV coordinates by `DepthEncoding.zw` (`jitterNdcX * 0.5f, jitterNdcY * 0.5f`). In Vulkan, viewport coordinates and NDC coordinates must maintain consistent sign conventions. Analysis confirms the translation `(uv + DepthEncoding.zw) * Sizes.xy` accurately converts display UV into the corresponding jittered render sample position.
- Confidence: Low (Verified Correct)
- Status: Verified Correct.

### Finding 6: BSL foliage motion target clearing on shader pack toggle
- Location: `src/client/java/com/bambooz/reflexium/compat/BslFoliageMotion.java:99-106`
- Failure scenario: When `clearFoliageSurface` is enabled, `beginFrame()` clears Iris target 15 via a dedicated render pass. If shaders are disabled or switched mid-session, calling `surface()` might throw reflective errors. Analysis confirms `ready()` explicitly guards with `IrisCompat.isDrawingPack()` and pack name equality, returning null safely during transitions without throwing exceptions.
- Confidence: Low (Verified Correct)
- Status: Verified Correct.
