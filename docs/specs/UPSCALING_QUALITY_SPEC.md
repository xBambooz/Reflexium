# Upscaling quality audit and implementation specification

Date: 2026-10-14. Status: implementation and recovery checks recorded in docs/validation/upscaling-quality-2026-10-14.md; requirements 10 and 11 remain partial. Initial implementer: Claude Sonnet 5.5; recovery and review: Codex, authorized after Sonnet rate limits.

## Objective and boundaries

Reflexium is a Minecraft 26.3 Fabric client mod using Java 25, Mojang's renderpearl Vulkan renderer, native NGX DLSS SR and AMD FSR 3.1.4. Ryan reports severe loss of texture patterns and especially poor detail in motion, with shaders both disabled and enabled. The target is the correctness and image-quality discipline of an engine-native integration. Merely advancing NGX counters, avoiding exceptions, or making a screenshot appear sharper is insufficient. Do not claim a hypothetical Mojang implementation is known or that all native detail can always be recovered from arbitrarily low input resolutions.

Implement every concrete correction below, with meaningful checks and live evidence. Architectural shader issues have explicit evidence gates: investigate and implement the supported solution, but do not hide remaining limitations or replace a rendering effect with a lower-quality approximation to declare success. Keep an itemized outcome for every numbered item in the validation report.

Project: C:/Users/Ryan/source/repos/Reflexium. Build with .\\gradlew.bat build. JAVA_HOME is C:/Program Files/GraalVM/graalvm-jdk-25.0.4+7.1. Gradle automatically installs the built jar into the Modrinth 26.3 Testing Instance. Standalone checks are existing Java main classes run by Gradle check, not JUnit. Read the global AGENTS instructions, ai-brain INDEX.md, preferences/workflow.md, preferences/communication-style.md, minecraft/26x-fabric-api.md, minecraft/agent-harness.md, and minecraft/vulkan-frame-generation.md first. Read docs/DLSS.md, docs/VITRAIL.md, docs/DEBUGGING.md, docs/PERFORMANCE.md and native/README.md. Applicable skills are ai-brain, ponytail, fabric-modding, and handoff-review. No repository-local AGENTS.md or CLAUDE.md was present during the audit; recheck for additions.

There are unrelated pre-existing edits to CompatMixinPlugin.java, VitrailCompat.java (version accessor correction), FrameGenerationRenderer.java (GUI blur guards), GuiRendererMixin.java, MinecraftMixin.java, reflexium.compat.mixins.json, reflexium.mixins.json, and new FpsText.java, DebugEntryFpsMixin.java and BetterF3FpsMixin.java. Preserve those changes. The parent has inspected the dirty tree and assigned this implementation to you. Do not stop merely because those changes exist. Work in this checkout. Do not use git reset, restore or checkout to erase them. Do not launch more agents. Parent handles the final repository-wide commit after review. No repository push was requested.

## References already researched

1. NVIDIA DLSS Programming Guide, current local SDK 310.9.1, guide dated 31 March 2026: https://github.com/NVIDIA/DLSS/blob/main/doc/DLSS_Programming_Guide_Release.pdf . The PDF and SDK headers are available in native/deps/DLSS. Sections 2.2 (jitter coverage), 3.1 (pipeline placement), 3.5 (mip bias), motion/jitter conventions, 3.12 (presets), and 3.13 (scene transitions) are relevant.
2. NVIDIA Streamline SR guide: https://github.com/NVIDIA-RTX/Streamline/blob/main/docs/ProgrammingGuideDLSS.md . Use its integration requirements, but do not transplant normalized Streamline MV scaling into our raw NGX pixel-space ABI.
3. AMD FSR 3.1.4 integration: https://gpuopen.com/manuals/fidelityfx_sdk/techniques/super-resolution-upscaler/ . Pay attention to camera planes, pixel vectors, reactive/composition masks, jitter, mip bias and frame placement.
4. Modrinth-first research was performed on Radiance: https://modrinth.com/mod/radiance-mod-windows links https://github.com/Minecraft-Radiance/Radiance and its native renderer https://github.com/Minecraft-Radiance/MCVR . The reviewed source paths are src/core/render/modules/world/dlss/dlss_wrapper.cpp and src/shader/world/ray_tracing/internal/advanced/scene/motion.glsl. MCVR retains previous geometry and object transforms rather than relying on camera-only motion. This is architectural reference material, not code to copy into this MIT project from a GPL renderer.
5. Vitrail source and API scope are described in docs/VITRAIL.md. Installed optional jars are in run/mods. ComplementaryReimagined_r5.9.3.zip is in run/shaderpacks. Do not edit users' saved pack files. Inspect the actual installed classes with javap when signatures matter.

The audit saved javap evidence in ignored build/upscaling-audit, including GameRenderer, LevelRenderer, SamplerCache, FrontendRenderPass, VulkanGpuSampler, SodiumDefaultChunkRenderer and VitrailGeometryProgram. The cached Minecraft client jar is .gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-clientOnly-7e9a32a5b8/26.3/minecraft-clientOnly-7e9a32a5b8-26.3.jar. Use the GraalVM bin/javap.exe. Do not expect a system Python or Node installation; see mc-agent-harness/README.md for the existing Electron-as-Node runtime.

## Implementation order

Capture a reproducible baseline before changing rendering. Implement items 1-3 together because bias coverage and the formula must be tested together. Then 4-7. Add the debug evidence path early enough to verify actual inputs. Implement hand motion and stage ordering after those foundations. Investigate the supported pack path with those baselines established. Run checks and live acceptance, inspect the diff, and report exact remaining gaps. Do not stop after the easiest fixes.

## 1. Sodium terrain bypasses material mip bias (high priority, confirmed bug)

Files: src/client/java/com/bambooz/reflexium/mixin/MaterialSamplerMixin.java:12-16; mixin/SamplerCacheMixin.java:15-18; dlss/MipBiasSamplers.java:14-25; dlss/UpscalingRenderer.java:374-377.

Current MaterialSamplerMixin only handles a texture uniform named Sampler0. Sodium 0.9.2 DefaultChunkRenderer.render binds terrain using RenderPass.setUniform("u_BlockTex", atlas, sampler). LevelRenderer creates and retains chunkLayerSampler directly through GpuDevice.createSampler in lambda$addMainPass$0. That sampler does not come from SamplerCache.getSampler, so the generic cache mixin does not rescue this path. This is a real missing material path, not merely the milder formula in item 2.

Fix at the shared binding boundary: explicitly handle both known material uniform names Sampler0 and u_BlockTex, with a mipmapped color-material texture check. Retain the Vitrail atlas path, which may bind via descriptor construction rather than FrontendRenderPass. Avoid a mandatory Sodium dependency. Confirm opaque, cutout and translucent terrain all reach the correction. Preserve original filtering, max anisotropy, addressing and max LOD.

Verify with a runnable selection check for both names and excluded names, plus live bound-sampler evidence under Sodium. A debug field displaying only the requested global bias is not proof that terrain used it. Native AA and disabled upscaling must leave native sampling intact. Check the material path with Sodium disabled as well as enabled.

## 2. Shared mip bias omits the vendor baseline's extra level (quality improvement, confirmed discrepancy)

File: src/client/java/com/bambooz/reflexium/dlss/DlssMath.java:10-11; call at UpscalingRenderer.java:642; assertions in src/test/java/com/bambooz/reflexium/dlss/DlssMathCheck.java:96-98.

Our formula is log2(render/display). NVIDIA recommends NativeBias + log2(render/display) - 1 + epsilon; AMD recommends log2(render/display) - 1. The current lower-detail sampling can destroy patterns before reconstruction. The source comparison alone does not establish that this is the entire cause of blur.

Use log2(render/display) - 1 as the upscaling baseline, retaining zero additional bias at native resolution and with upscaling off. Expected additional bias: Quality approximately -1.5849625, Performance -2, Ultra Performance approximately -2.5849625. Preserve original native bias if present. Check invalid dimensions at any external/debug boundary. Both vendors permit less aggressive bias for high-frequency content, so compare motion stability and moire before treating the baseline as universally optimal. Add a bounded dev-only runtime bias offset or legacy/vendor switch if needed for matched A/B, not a collection of new player settings. Changing it must invalidate the correct sampler cache safely; never free resources still referenced by the GPU.

Extend the existing numerical check for ratios and native behavior. Live validation must include stone/wood/grass texture patterns and thin cutouts, not only a flat-colored scene. Record both detail gain and any new shimmer.

## 3. Sampler correction is global in one path and non-idempotent (correctness and scope)

Files: SamplerCacheMixin.java, MaterialSamplerMixin.java, MipBiasSamplers.java and mixin/compat/vitrail/GeometryProgramMixin.java.

SamplerCacheMixin currently biases every mipmapped sampler returned while the world is swapped, regardless of whether it samples a material, a composite image, or some other resource. This contradicts the material-only contract. Meanwhile, an already-replaced sampler passed through another hook becomes another cache key and creates another replacement because the cache only maps original to replacement. This does not necessarily double the numeric bias, since creation starts from native bias zero, but it is unnecessary replacement chaining and masks whether the right resource is being handled.

Once item 1 covers actual material binding, remove the indiscriminate SamplerCache interception and its registration if it is no longer needed. Read every caller before removing it, including Vitrail PBR material maps. Move any genuinely missing material correction to its actual material bind site. Do not leave PBR normal/specular material maps missing the appropriate LOD while albedo is corrected. Do not bias shadow depth, scene depth, screen-space composites, lightmaps or GUI samplers.

Make applying the same bias to an already corrected sampler idempotent. Preserve original settings and ownership. Cache replacement keys must not cause duplicate close calls. Cache changes, reload and native/off transitions must not return closed samplers or retain obsolete bias. Prefer a small identity-map solution over a new sampler abstraction layer.

For evidence, capture the actual mipLodBias argument applied inside SamplerLodBiasMixin/VulkanGpuSampler construction and connect that provenance to bound material samplers. A small purpose-specific metadata interface is acceptable if needed for that verification. Expose per-frame material bind counts and actual applied bias through the dev status, without production frame logging or allocations at every bind.

## 4. DLAA jitter sequence is shorter than NVIDIA's minimum (confirmed contract mismatch)

Files: DlssMath.java:14-17; UpscalingRenderer.java:821-835; DlssMathCheck.java:92-95.

The shared sequence uses max(8, ceil(8 * scale^2)), so DLAA has only 8 phases. NVIDIA section 2.2 requires at least 16 and prefers 32 or more. That is distinct from FSR's documented 8-phase native baseline.

Keep FSR's ratio-dependent sequence. For DLSS use at least 32 phases, increasing to the existing ratio-dependent requirement when larger (Ultra Performance remains 72). Carry the effective upscaler into the phase decision explicitly rather than globally changing FSR. Off remains unjittered. Preserve the current measured +1,+1 ABI convention pending an actual counterexample. Do not copy Radiance's negative signs without considering its projection convention.

Test phase counts, sequence bounds, repetition and stationary-camera zero velocity. Extend the numerical test beyond a self-consistent formula: asymmetric jitter, camera translation on all axes, rotation, changed FOV and aspect, and a visible point's known previous/current projected pixel displacement. Runtime overlay and capture checks remain mandatory because algebra alone cannot establish the buffers used the same matrices.

## 5. Explicit Transformer pins preset K at every quality (confirmed outdated quality policy)

Files: dlss/DlssModel.java:9-13; UpscalingRenderer.java:807-809; DebugServer.java:489-492; native/deps/DLSS/include/nvsdk_ngx_defs.h:83-85.

Transformer currently always returns 11 (K), including Performance and Ultra Performance. The current SDK recommends K for DLAA/Quality/Balanced, M=13 for Performance, and L=12 for Ultra Performance. L/M target better reconstruction at the lower input ratios. Existing Auto must continue to pass zero so the runtime chooses its default. CNN remains E=5; do not silently turn the user's CNN selection into a Transformer.

Make explicit Transformer selection quality-aware, update every caller and diagnostic override validation, and preserve configuration serialization. Keep the existing manual preset K override for fair A/B. Model selection status must say it is the requested hint, not claim NGX reported its actual selected model. There is a real performance cost to newer models, so preserve a way to compare K and report measured cost without inventing an FPS gain.

Extend existing checks to cover all mode/model mappings and overrides. Verify the feature actually rebuilds on quality and effective preset changes and still handles an existing user-supplied runtime gracefully. Do not overwrite user DLLs as a shortcut.

## 6. History reset misses camera-mode and large angular cuts (confirmed omission)

Files: UpscalingRenderer.java:469-475 and 535-538; currentOrientation/previousOrientation fields at approximately 126-127. docs/DLSS.md currently claims cuts are handled more broadly than the implementation.

Reset currently considers first frame, world identity, translation beyond 8 blocks and a 250 ms gap. It does not handle switching camera mode or an abrupt angular cut. These can retain a fundamentally incompatible view even with little translation.

Track the camera mode/view identity used for the rendered scene and reset on a mode/entity change, plus a conservative large per-frame quaternion rotation such as over 60 degrees. Use absolute quaternion dot or equivalent shortest-arc handling; q and -q are the same rotation. Ordinary mouse movement and smooth zoom/FOV animation must not constantly reset history. Reset all associated previous state atomically on a cut so camera, object and hand paths agree; retain existing teleport/world/resize behavior. Avoid resetting on any nonzero matrix change.

Add independent boundary checks including q/-q, ordinary turning, a large cut and identity changes. Live evidence must show stable reset counts during a smooth turn/strafe and exactly the expected reset after an abrupt mode/camera change. Propagate the same reset to FG without changing FG pacing.

## 7. FSR camera metadata is derived from an already bobbed projection (confirmed derivation bug)

File: UpscalingRenderer.java:347-360, jitterLevelProjection. GameRenderer.renderLevel applies bobHurt, bobView and portal/nausea transforms to a copy of CameraRenderState.projectionMatrix before invoking ProjectionMatrixBuffer.getBuffer, where our hook captures it.

The current m32/(m22+1), m32/m22 and m11 formulas assume a pure perspective projection. Extracting them from P multiplied by a view-bob matrix can change near/far/FOV metadata even though the underlying camera's perspective parameters did not change.

Extract near/far/vertical FOV from the unmodified CameraRenderState.projectionMatrix (or the source camera values) for the FSR dispatch. Retain the full actually drawn bobbed projection for depth reprojection and moving-object raster matching. Do not remove view bob, damage tilt or nausea to evade the issue. Distinguish the physical projection from view effects in FG constants as appropriate; do not change the ABI without updating native consumers and their check. Preserve the FSR reversed-depth near/far swap at the C boundary.

Test a known perspective projection with nontrivial bob translation/rotation: metadata stays the physical camera values while world reprojection still uses the actual drawn transform. Exercise FSR walking with view bob on and off and a damage tilt. Reject non-finite or invalid debug parameters rather than feeding NaNs into the SDK.

## 8. First-person hand and held-item animation has zero motion (confirmed missing input)

Files: dlss_motion.fsh:25-30, ObjectMotion.java, ObjectVelocityPass.java, GameRendererMixin.java:approximately 101-120, and mixin/compat/vitrail/HandDrawMixin.java. Existing code intentionally returns zero vectors for every hand pixel.

Zero motion is valid for a motionless screen-relative hand, but not for swinging, equipping, eating or other animated hand/item geometry. Engine-native integration needs those animated surfaces to carry their actual previous projected positions.

Reuse the current animated vertex capture/previous-position machinery where possible, with a separate hand history and the actual hand projection and view transform for each frame. GameRenderer.renderItemInHand creates hand feature submits and prepares their geometry in the same method; its PoseStack starts with inverse camera view rotation and then applies bob transforms. Captured hand vertices therefore need the corresponding actual GPU view/projection, not a world-camera approximation. Scope the hand capture to the exact hand submission/prepare window, keep world tracks independent, and respect the separate HUD depth target. Do not feed the world projection to the hand velocity pass. The hand has a different FOV.

Depth-test hand vectors against the hand-only depth/mask; world object vectors must continue to exclude it. Unmatched/new hand topology should use a conservative fallback, never stale geometry. Invalidate on item/model changes and camera cuts. Support both solid and translucent Vitrail hand halves without matching their slots incorrectly. Translucent material handling must respect item 11; do not overwrite world vectors across transparent holes.

Provide a deterministic projected hand-animation check and live still-camera swing/equip captures with independently moving hand vectors. Keep all previous object-motion tests passing. A counter advancing is not evidence that the hand vector matches its visible surface.

## 9. Vanilla reconstructs after post effects (confirmed ordering problem)

Files: GameRendererMixin.java:approximately 71-77; UpscalingRenderer.java:499-538. GameRenderer.applyPostEffects calls PostChain.process(mainRenderTarget, resourcePool), so its effects follow the target's actual size.

The vanilla hook currently evaluates after applyPostEffects. Place scene reconstruction before vanilla post effects, as the vendor pipeline requires, while keeping the GUI at display resolution. Split reconstruction, post-effects completion and FG sceneReady as necessary: FG must still receive the finished HUD-less scene with post effects, and previous-state promotion must occur exactly once after a real successful scene. A pack's internal composites are a different path covered by item 10.

Preserve the existing reduced-world/display-target split and finally-based restoration. If a post effect reads depth after the color target is restored to display resolution, provide a matching display-resolution scene depth image with nearest depth sampling (including hand depth where appropriate). Do not reintroduce the removed post-upscale block outline renderer. Avoid a permanent depth upscale cost when no consumer needs it. Check resourcePool/post-chain target sizing rather than assuming changing hook order is enough. Loading frames must remain passthrough with history reset.

Verify at least one actual vanilla post chain with depth usage, effect toggling and a window resize; GUI and screenshots remain display size. Test native/off, DLAA, Quality and FSR. Verify FG sees the post-effect image and no duplicate evaluation/state promotion occurs. If any new native code is required, rebuild and include the bundled bridge; a Java-only build does not rebuild C++.

## 10. Supported shader packs stack full-scene TAA and late SR (confirmed architectural gap)

Files: compat/VitrailCompat.java:77-103; mixin/compat/vitrail/PackSessionMixin.java; shader integration mixins; docs/VITRAIL.md. Installed Complementary r5.9.3 has shaders/program/composite5.glsl (tone mapping), shaders/program/composite6.glsl (DoTAA), and shaders/lib/antialiasing/taa.glsl:247 (mix current color with previous full-scene color). These sources were inspected directly from the zip.

Current overrides force TAA_DEFINE=1, TAA_JITTER=0. This avoids a second projection jitter but keeps a second full-scene temporal filter. The pack's final tone-mapped color is then fed to SR. Earlier TAA_DEFINE=0 attempts exposed dithered lighting/shadow noise. Do not repeat that switch blindly, and do not describe the existing full-scene TAA as only a lighting denoiser.

Investigate and implement the smallest supported-pack integration that supplies scene color before destructive final filtering, gives SR ownership of scene reconstruction, and preserves required lighting/effect denoising. Prefer existing Vitrail targets/stage hooks and exact known-pack adapters over a renderer rewrite. Any in-memory shader-source adaptation must be tightly version/source guarded, preserve original files/options, and leave unknown packs untouched. Do not use brittle global string replacements across arbitrary GLSL. If the correct stage exposes HDR, match its format, color space, flags and exposure; do not simply set the HDR bit on the existing RGBA8 final image. Remaining pack post processing must use valid target sizes/uniforms after reconstruction.

Acceptance requires side-by-side noise and motion inspection. Removing TAA while introducing grain, reducing shadow/lighting quality, disabling foliage movement, or silently disabling upscaling does not resolve this item. If an exact early-stage integration cannot be completed through the supported renderer hooks, document the missing contract and the retained fallback as an unmet engine-native requirement, with evidence. Do not label it implemented, do not invent a generic integration API with no functioning producer, and do not claim the entire requested quality target is achieved.

## 11. Shader deformation, transparency and history validity need an explicit coverage audit

Files: ObjectMotion.java:approximately 223-235 excludes blending and non-depth-writing geometry; shader dlss_motion.fsh only reconstructs static surfaces from camera/depth; UpscalingRenderer.java:395-399 disables generated FSR reactive masks for packs. The current opaque replay also cannot reproduce shader-side vertex deformation.

Inventory actual coverage for terrain, opaque/cutout entities, block entities, hand, particles, water, scrolling/animated textures, reflections and shader-waved foliage. Compare the geometry/depth actually rasterized with the geometry used by velocity replay. Geometry transformed in a pack shader cannot be matched by replaying undeformed Java positions and calling it correct.

For a supported producer that supplies correct vectors/depth or material reactivity, consume those inputs at the matching stage; otherwise implement the needed producer for the supported path, not an unused configuration hook. Known Complementary deformation is in shaders/lib/materials/materialMethods/wavingBlocks.glsl, driven by frameTimeCounter and cameraPosition; obtaining previous positions requires evaluating the same deformation with the proper previous state and preserving material/vertex identity. Dynamic surface normal/reflection/shading changes need history handling even when the mesh does not move. Keep unknown packs conservative and accurately describe their coverage.

FSR reactive/composition masks must compare colors at the same exposure/color stage. Do not re-enable the known-invalid shader opaque/final pair. Check coverage under both classic transparency and OIT and provide material-derived masks where possible rather than relying only on a threshold heuristic. Modern DLSS Transformer does not accept FSR's mask as an equivalent hint: NVIDIA guide section 3.15 says the legacy current-color-bias mask is only for preset F, not the latest models. Do not wire that buffer into K/L/M and claim it fixes transparency.

Leave itemized evidence for inputs that remain camera-only or approximated, including capture-budget overflow and topology mismatch. Implement corrections possible with the real supported renderer paths; mark any remaining input contract as unresolved, not silently complete. Do not claim that handling a moving pig establishes complete material-motion coverage.

## 12. Add a practical input-quality capture and verification path (required evidence)

Files: debug/DebugServer.java (registerHarnessEndpoints at approximately 70; existing screenshot/capture at 600-682), DebugSettings.java, and UpscalingRenderer.evaluate at 711-755. docs/DEBUGGING.md documents the authenticated external harness.

Add an opt-in capture request exposed through the existing authenticated harness endpoint that saves the actual SR input color at render resolution and its corresponding SR output at display resolution from the same evaluated frame, with exact camera/matrix/jitter/frame metadata. Existing Screenshot.takeScreenshot schedules a texture copy immediately and can be called before evaluation and after output becomes available. Do not take input and output on unrelated HTTP frames. Do not wait for a callback on the render thread. Close NativeImages and temporary readback resources, handle timeout/resize/cancel, and keep the normal production path free of readback work when no capture is requested.

Include depth/motion debug views or numeric/raw samples sufficient to validate known projected correspondences, using a conversion/visualization pass when the attachment is not RGBA8. Do not send RG16_FLOAT motion through a screenshot decoder that assumes color bytes. Captures are debug artifacts, not player-facing controls. Reuse existing command infrastructure and one small driver script; no new test framework, image package or general automation framework.

Metadata should include display/render sizes, effective upscaler, requested model/preset, actual bound material bias evidence, jitter phase/count/sign, world/hand matrix identities, reset count/reason and supported pack identity/stage. Distinguish requested state from observed inputs.

## Live validation and delivery

Use C:/Users/Ryan/source/repos/mc-agent-harness and read its README before launching. Launch an isolated owned client on the secondary monitor, visible and unfocused, at 1920x1080 or the user's real display size. Earlier 960x540 smoke tests are inadequate for native-like detail acceptance. No active Java game process was present at the audit's process check, but recheck immediately before a build/launch. Never close another client by process name. Verify session PID, game directory and injected harness jar before shutdown. Session tokens stay private. Reuse an existing isolated smoke save only when no other client owns it. Preserve saved game/settings files with a backup and restore test overrides afterward.

Before changes, capture native, DLAA, DLSS Quality and Performance, with FG off, both shaders off and supported Complementary on, including stationary, turning and strafing views. Use repeated camera positions and matched exposure/world state. Include texture-heavy opaque blocks, diagonal ground, leaves/cutouts, a moving entity, an animated block entity, swing/equip hand, particles and water. Verify real terrain is loaded before any conclusion. Use the same scenes after changes. Do not relabel unrelated historical captures as a baseline.

Run the existing build/check suite, plus .\\gradlew.bat -I tools\\vitrail-check.gradle vitrailCompatCheck when changing pack policies. Extend existing assertion mains for new nontrivial logic. Rebuild native/build.cmd only if C++ or its ABI changed, then run the Java build to package the actual updated DLL. Check the full live log for mixin failures, shader compilation problems, native errors and validation messages. Test quality/model switches, native/off toggles, resize, shader reload, world rejoin, camera cuts and no persistent per-frame history reset. Briefly regress FG independently after changing its shared depth/motion/stage inputs; do not use generated FPS as SR-quality evidence.

Write docs/validation/upscaling-quality-2026-10-14.md with the numbered outcome of every item, commands/results, artifact paths, actual observed detail/motion/noise differences, and unresolved objections. Keep the image comparison honest: absolute Laplacian energy can reward shimmer/wobble; single still-image sharpness cannot prove motion quality. Matched crops and sequences at native pixel dimensions matter. State unmeasured claims explicitly. Update docs/DLSS.md, docs/VITRAIL.md and docs/DEBUGGING.md to reflect the implemented behavior and remaining limits; do not leave the current broad acceptance prose implying complete quality validation.

Return an implementation summary with changed files, checks, baseline/after evidence, and unresolved requirements. The parent will review the diff and perform final verification/commit. Do not stop merely to ask permission to execute this already-authorized spec. Do not claim the user's blur is solved without inspecting the resulting images and motion sequences.
