# Meshelium compatibility, worker C, 2026-10-31

## Scope and artifacts

Baseline branch in this checkout is `t3/meshelium-compatibility`, rather than the brief's `round6-validation`. No shared brain edits, profile edits, pushes or merges. No edits to worker A or B's owned render files.

Primary references: [Modrinth](https://modrinth.com/mod/meshelium), [Meshelium source at e4d0a3f71ac764c87e3b092f3a4104c084b5cfa0](https://github.com/ded811/Meshelium/tree/e4d0a3f71ac764c87e3b092f3a4104c084b5cfa0). Inspected source is a reference, with the installed jar's bytecode used for ABI verification. No LGPL implementation code was copied into Reflexium.

Isolated `run/` copied from `t3-b5ddc25e/run`. Jars copied from Ryan's profile, read only:

| Component | SHA-256 |
| --- | --- |
| Meshelium 1.6.2+mc26.3 | F39419AE68E7EDB9B336A52F4CE94CCA2DFB8C4A443652E6A9EF8FB63641C400 |
| Sodium 0.9.2+mc26.3 | 87A5BB39F7E6E41106E77F122F82FC93F9D3BC0B6EBCB746B314172A446A287A |
| Iris 1.12.0-snapshot+mc26.3-local | 566BD7515CC1CB41320A6761932093C1C3EE813FC8CBFD2077E3D128746DC1C2 |

The copied Iris snapshot is named `iris-fabric-experimental-build-26.3.jar` in the isolated directory because the existing Iris ABI check defaults to that path. The copied older jar was moved out of mods. Hash, not filename, identifies this artifact.

Local evidence root: `C:/Users/Ryan/.t3/worktrees/Reflexium/t3-c5bf5f33/run/evidence/`. It is gitignored and must be preserved for lead verification. `meshelium-javap.txt` contains `javap -p -c` for ChunkRendererBase, SodiumTerrainDrawer and TerrainOcclusion. `sodium-projection-javap.txt` contains Sodium's GameRendererMixin. `jar-hashes.json` records copied jars. `profile-excerpts.txt` records line-numbered excerpts of the real profile log, never a test result.

## Research answers

1. **Projection jitter:** Sodium's `mixin.core.render.world.GameRendererMixin.sodium$setProjection` copies the argument of `ProjectionMatrixBuffer.getBuffer(Matrix4f)` using Matrix4f.set, then calls the original. Reflexium's GameRendererMixin modifies that argument with jitterLevelProjection. MesheliumChunkRendererBase passes `matrices.projection()` to SodiumTerrainDrawer, which uploads it in uploadProjection (source lines 1484-1487 and owned path 3865). It does not reconstruct a separate unjittered terrain projection. Runtime injection ordering must still be verified from the transformed GameRenderer or an image diagnostic. Adding another jitter at the drawer would risk double jitter.

2. **Render resolution:** ChunkRendererBase.draw obtains pass.getTarget and passes its color/depth views (lines 202-227, owned draw lines 445-446). SodiumTerrainDrawer creates its RenderPearl pass with those attachments (line 3922), without overriding viewport. Its subpixel cull takes Minecraft main target width/height (4361-4362). OcclusionHalfResTarget sizes from mainDepth.texture().getWidth/getHeight and allocates ceil(w/2), ceil(h/2) (215-237), not window dimensions. Phase-B skip key includes depth attachment extent (3882 and 4504-4507). Source supports render-scale correctness; this is not a measured viewport capture.

3. **Mip bias:** Real incompatibility. SodiumTerrainDrawer.imageInfo (1499-1506) extracts VulkanGpuSampler.vkSampler directly; pushDescriptors and pushDescriptorsTask write that sampler (1417, 4273). This bypasses FrontendRenderPass.setUniform and Reflexium's MaterialSamplerMixin, which normally handles u_BlockTex. The new ModifyVariable on ChunkRendererBase.draw replaces its atlasSampler argument with existing UpscalingRenderer.materialSampler using MaterialBinding.BLOCK_TEX. This covers owned solid, owned cutout and rung-1 draws at their common entry, preserving idempotence and disabled-upscaler behavior.

4. **Depth and motion:** Shaderless mesh draws attach pass.getTarget().getDepthTextureView; TerrainDrawPipeline enables depth writes (678), and mesh terrain projects with the uploaded projection. No separate mesh-only depth target feeds DLSS. Reflexium sceneDepth and camera reprojection consume the main scene depth. Under a pack, Meshelium's own fragment path cannot populate pack-specific gbuffers merely by writing main depth. The new pack guard returns false before MainPassSuspension, leaving Sodium/Iris to draw its normal terrain and pack depth. Camera motion is suitable for static terrain; terrain is not an ObjectMotion entity replay.

5. **Temporal occlusion:** Cause of logged dips remains provisional. Real profile log line 19318 reports 248 -> 225 -> 249 drawn sections while input list stays 3889 in 38 regions, Phase B recorded, no missing buffer and no declines. It does not prove jitter caused the dip. Source phaseBSkipDecide compares raw model-view and projection plus extent every frame (4502-4507); jitter/resize therefore invalidates that optimization. Half-resolution occlusion independently validates pre-bob projection, so jitter can affect arm selection. No evidence establishes that Reflex pacing or DLSS-G causes a culling defect: generated presentations do not call the terrain draw path. No speculative global occlusion disable was added.

6. **Iris pack active:** Meshelium's wantsDraw/draw have no Iris pack guard. It suspends Sodium's RenderPass and records a custom TerrainDrawPipeline and fragment shader, so the pack's gbuffers_terrain is bypassed. Ryan's profile log confirms Meshelium draws in this configuration. The fix intercepts wantsDraw before suspension whenever ShaderPacks.isDrawingPack() is true, including native-resolution pack use. The existing delegate renderer executes instead. This retains IrisCompat's pack projection, depth, material, and motion paths. It costs Meshelium mesh acceleration for pack terrain; no performance delta is claimed. Shaderless mesh rendering is restored when the pack is disabled, with transition logs.

7. **Vulkan device:** VulkanBackendMixin extends existing FeatureSet and wraps the existing VkDeviceCreateInfo; DeviceExtensions unions feature sets rather than replacing them. Streamline reserveQueues adjusts queue requirements on the existing create info, preserving pNext/features. Real profile log line 1412 reports DLSS ready, DLSS-G ready, max 6x, Reflex ready, PCL ready on RTX 5070, with subsequent Meshelium mesh terrain pipeline creation and draw. Thus mesh features survived that profile's Streamline startup. This is historical runtime evidence, not a fresh Vulkan validation run.

8. **Other hooks:** GameRendererMixin projection/target swap, UpscalingRenderer scene depth and camera motion, DeferredEffects, FG hudless/UI capture and Reflex markers bracket scene/frame boundaries rather than individual terrain draws. Meshelium resumes the suspended main pass before entity/UI work. ObjectMotion captures moving entities; static chunk terrain only requires camera vectors. The identified terrain-specific exception is the sampler boundary in item 3. Pack-specific terrain buffers require item 6's fallback. No changes to UpscalingRenderer or GameRendererMixin were needed.

## Implementation and checks

Optional mixin is gated in CompatMixinPlugin on Meshelium exactly `1.6.2+mc26.3` plus Sodium presence. @Pseudo and string target avoid optional class linkage without Meshelium. Unknown versions receive no assumed-compatible hooks. ShaderPacks.isDrawingPack distinguishes Iris installed from pack active.

MesheliumCompatCheck is a plain main check registered in build. It reads the actual jar using ASM, verifies the sampler ABI, wantsDraw guard before suspension, and the delegate render call. It does not claim to execute a mixin or render an image.

Command: `gradlew.bat build -x installReflexium --no-daemon --console=plain`.
Final code build: `BUILD SUCCESSFUL in 12s`, 25 actionable tasks (19 executed, 6 up-to-date), including `Meshelium 1.6.2 sampler and pre-suspension delegation ABI checks passed`. Evidence: `run/evidence/build.txt`. Initial attempt failed solely because the existing Iris check expected its default filename; corrected the copied artifact name and reran all checks successfully. The ABI check skips when the optional jar is absent, but explicitly supplied missing jars fail. Override with `-Dreflexium.mesheliumJar=<path>`.

## Runtime checks

Two quick sessions completed through mc-agent-harness, with atomic worker-C-meshelium GPU ownership throughout each client lifetime. Session 1 port 25731, PID 196744, Meshelium installed; session 2 port 25733, PID 113784, Meshelium jar moved outside mods. Both exact PIDs exited after authenticated ownership-checked quit, previous foreground restoration was requested and locks released (`stop1.txt`, `stop2.txt`). No process-name termination. Real profile remained read only. Copied Meshelium jar was restored afterward. Tools added: meshelium-launch.ps1, meshelium-stop.ps1, meshelium-crops.ps1.

The copied options initially selected exclusive fullscreen. Used the Reflexium exclusive_fullscreen setting to disable it, then the harness fullscreen endpoint with fullscreen=1 and activate=1. `fullscreen-borderless.json` and session 2 `fullscreen2.json` report borderless=true, exclusive_fullscreen=false, display_id=target_display_id=1, display 2560x1440, covers_display=true. This is the primary DISPLAY1. Launch arguments included -Monitor primary -Width 2560 -Height 1440 -Fullscreen.

Four session-1 status captures (`status-pack-up.json`, `status-pack-quality.json`, `status-mesh-up.json`, `status-mesh-quality.json`) all report upscaler_effective=dlss, upscaling_active=true, last_error empty. Ultra Performance is 853x480 and Quality is 1707x960, output 2560x1440. Transformer preset 12. Shaderless debug material_binds.u_BlockTex reports binds=3, with_requested_bias=3, applied_bias=-2.585526 (Ultra Performance) and -1.5846808 (Quality). Pack-mode material counters were zero in these status snapshots, so this run does not independently verify pack atlas bias; it verifies delegation into the existing Iris path.

**Jitter ordering verified in the actual transformed class:** `transformed-gamerenderer.txt` has renderLevel bytecode offset 372 calling `modify$...reflexium$jitterLevel`, followed by offset 389 calling `wrapOperation$...sodium$setProjection`. The latter copies that argument into Sodium's projection. Meshelium consumes that captured matrix. This resolves item 1's injection-order uncertainty without a second jitter hook. `transformed-meshelium.txt` also records the applied compatibility mixin. Jitter-off screenshot exists, but it was taken after tick-freeze and is not a controlled jitter A/B metric.

`session1.log` line 1185 logs pack delegation. Lines 1534-1542 log restoration after shaders off, active mesh terrain and the box-raster temporal occlusion path. Streamline and mesh features coexist in this fresh run. Re-enabling shaders returns to delegation. No VUID or Validation Error found in either saved log. No explicit Vulkan layer registrations were found in HKLM/HKCU Khronos ExplicitLayers (including WOW6432Node), and no C:/VulkanSDK install was found. A validation-layer-enabled run remains unverified.

Terrain camera: x=200.5, y=110, z=150, eye_y=111.62, yaw=94, pitch=10, render distance 20. Session 2 explicitly teleported to that pose; session 1 camera yaw status was 93.99994 at the native harness precision, while Reflexium rounded it to 94. Time set to noon and clear weather for later captures. Screenshots include a tutorial toast outside the chosen crop. Crop script uses output-pixel ROI x=80,y=650,width=640,height=480 and samples every other pixel for RGB MAE on 0..255:

| Pair with Meshelium vs removed | RGB MAE |
| --- | --- |
| mesh-up.png vs no-mesh-up.png | 2.3083 |
| mesh-quality.png vs no-mesh-quality.png | 2.2310 |
| pack-up-frozen.png vs no-mesh-pack-up.png | 2.8430 |
| pack-quality.png vs no-mesh-pack-quality.png | 3.3340 |
| mesh-up-frozen.png vs no-mesh-up.png, disturbed history | 6.2818 |

`crop-metrics.json` and corresponding `*-crop.png` files preserve these comparisons. These are quick same-pose comparisons, not bit-exact controls: jitter phase, animation and pack exposure/history were not locked between JVMs. Visual inspection of stable crops found no mesh-only dot/stipple pattern; pack foliage has visible fine texture in both cases. **The later mesh-up-frozen capture shows conspicuous terrain erosion/smearing after tick-freeze plus jitter and FG toggles, absent in the removed-mod reference.** Earlier mesh-up and mesh-quality images do not show it. This could be history settling, tick-freeze interaction or a mesh-specific defect. It remains provisional and must be isolated before claiming full visual compatibility. Do not substitute the stable captures for this negative evidence.

DLSS-G was briefly selected in session 1. `status-mesh-fg.json` reports streamline_dlssg_status=0 and no error, but effective fg_multiplier=0 and `Frame generation: rendered only (frame target 415 FPS, Auto output budget)`. This does not verify generated 3x presentation. Both normal captures and removed-mod references used FG off.

## Remaining unverified

- Cause and reproducibility of the disturbed-history terrain image and real-profile one-frame visibility dips. Compatibility is not fully visually certified.
- Actual DLSS-G 3x generated presentations, long temporal stability, motion and frame pacing under this combination.
- Independent pack atlas bias measurement, numeric depth attachment capture and GPU viewport inspection. Source paths and clean DLSS status support these boundaries but do not replace instrumented captures.
- Vulkan validation layers, other GPUs/versions, Meshelium without Sodium, other shader packs and sustained performance impact of pack delegation.

## Overnight additions

- Repeated static-camera sequences at Ultra Performance and Quality, shaderless and Euphoria, with Meshelium on/off. Compare stable terrain crops, jitter-on/off, dot/stipple presence and temporal luma difference at identical pose/time/weather.
- Vulkan validation layer run if installed, including pack toggles, world rejoin and render-size transitions while occlusion history is populated.
- Long culling continuity trace with mesh occlusion enabled/disabled, DLSS jitter enabled/disabled and DLSS-G off/3x. Count one-frame dips and correlate with scene mutations, raw projection, half-resolution arm and GPU pass stats before attributing a cause.
- Measure pack fallback performance against Meshelium removed. Test high render distances where mesh acceleration's loss matters.
- Meshelium without Sodium uses a separate TerrainDrawer and is outside this patch's exact profiled integration. Validate and add a separate interop path if that configuration is required.
- Other Meshelium versions, other GPUs, shadow passes and packs beyond the exact Euphoria profile remain unverified.
