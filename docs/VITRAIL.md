# Vitrail compatibility

Reflex compatibility was checked in game on 2026-10-10. DLSS and FSR shader upscaling were checked
on 2026-10-13 with the same Vitrail/Sodium/Complementary versions. The integration uses optional
Vitrail implementation mixins. Vitrail remains an optional dependency.

## DLSS and FSR integration

The integration targets Vitrail `0.12.x` on Minecraft 26.3. The source was reviewed at
[`f42e5489`](https://github.com/avpbynf/Vitrail-Shaders/tree/f42e5489c6abff655781b40bebc70338bada3039)
after following Vitrail's [Modrinth source link](https://modrinth.com/mod/vitrail-shaders).
The installed `0.12.0-beta+mc26.3` jar's method descriptors were checked with `javap`.
Future Vitrail versions are allowed to run with Reflex and vanilla rendering, but shader upscaling
pauses until their implementation hooks have been checked. This integration does not implement
Aperture's separate private renderer API.

While Reflexium upscales a frame:

- Its reduced textures are installed inside the existing `mainRenderTarget` before `renderLevel`.
  Vitrail's pack targets, depth copies, `viewWidth` and `viewHeight` already follow that target's
  dimensions, so the whole pack chain runs at the chosen DLSS/FSR render resolution.
- Vitrail's own Render Scale reads 100% for that frame. Its ordinary stand-down path still runs,
  while its saved percentage stays intact. With Reflexium off, Vitrail resumes its own scale and
  optional Temporal Fold. The two texture swaps and upscalers never nest.
- The pack's current and previous projection uniforms include Reflexium's projection jitter.
  The bob-validation comparison receives the same offset so walk bob, damage tilt, portal and
  nausea keep Vitrail's normal split between projection and model view. A captured fallback
  projection already contains the offset and is not jittered twice.
- Vitrail's first-person hand gets the same subpixel offset in its published and drawn matrices.
  Raw world-depth copies around each hand submission identify the pixels it actually covered;
  their motion is zeroed instead of reprojecting them through the world camera. The solid and
  translucent halves accumulate into the same frame mask. This uses actual depth changes rather
  than a near-depth cutoff that would also select nearby world geometry.
- Mipmapped material atlas samplers receive Reflexium's render-scale mip bias. Pack composite,
  shadow-depth and other non-material samplers retain their own states.
- DLSS/FSR receives the finished, tone-mapped LDR pack color before the GUI. Vanilla reverse-Z
  world depth is captured before the always-on-top gizmo clear when present, or at level end
  before the later HUD clear. The interface stays at display
  resolution.

Complementary Reimagined and Unbound packs whose names begin `ComplementaryReimagined_` or
`ComplementaryUnbound_` and declare `TAA_DEFINE` are loaded with an in-memory `TAA_DEFINE=0`
override. That removes their own jitter and TAA resolve while DLSS/FSR owns temporal sampling.
No pack or settings file is changed. Enabling or disabling an available upscaler reloads the
current pack once; disabling restores the user's own settings. Shader toggles and world changes
read the same override through the pack's normal load path.

Vitrail's warmup wrapper skips the entire `renderLevel()` invocation while the pack compiles,
and also on a frame where `PackChain.beforeLevel()` reloads it. The caller still reaches post
effects, so these frames legitimately have no captured world depth. Reflexium passes them through
without evaluating the upscaler or treating the missing scene as a permanent failure, then resets
temporal history for the next actual world frame. A warmup check at frame start alone would miss
the reload that occurs inside Vitrail's wrapper.

Other packs retain their own temporal options. Their TAA must be disabled in their own settings
if it applies a second jitter or resolve. Pack names and option semantics are not standardized,
so this integration does not guess how an unknown option works. Material animations driven only
inside the pack, including waving leaves and water, do not have independent object vectors.
The automatic FSR reactive mask is disabled while a pack draws because the opaque pre-composite
image and final tone-mapped image are not a valid matched color pair.

## Repeating the upscaling check

1. Use the exact Vitrail/Sodium/Complementary setup below, with Vulkan confirmed in the live log.
2. Choose DLSS Quality, load a world and wait for the pack's compilation to finish. Check the log
   for `Reflexium owns TAA` and a pack target size matching Reflexium's render resolution. The
   upscaler frame counter must advance while the pack is drawing.
3. Capture a still camera sequence, then turn and walk. Check for jitter wobble, history trails,
   stable post-effect alignment and a sharp GUI. Repeat with FSR and each quality mode.
4. Set Vitrail Render Scale below 100%, toggle Reflexium off and on, and confirm one scale takes
   effect at a time. With Reflexium off the pack's saved TAA choice must return.
5. Reload, disable and re-enable shaders, resize the window, change worlds and visit a menu.
   Check current target dimensions and resumed upscaler frames after each transition.

The 2026-10-13 run passed both upscalers at all five quality modes, DLSS Auto/CNN/Transformer,
960x540 to 1280x720 resizing, shader disable/re-enable, reload, upscaler off/on and leaving/rejoining
the test world. With Vitrail's saved scale at 75%, its targets were 720x405 while Reflexium was off
and DLSS Quality targets were 640x360 while active, at a 960x540 display. Restoring the saved 100%
value and reloading succeeded. The independent check below verifies original TAA options remain
unchanged. Frame counters advanced and `last_error` stayed empty after each transition.

Still, turn and translation sequences were inspected, including the motion-vector overlay: static
terrain was black with a still camera, moving mob surfaces produced independent vectors, camera
turns produced a smooth field and the hand remained black. These are functional smoke checks on one
RTX 5070, not proof of zero ghosting in every scene. Transparent particles, shader-only deformation,
other packs, animated block-entity visuals and vendor certification remain outside this acceptance.
Raw captures and JSON snapshots are under ignored `run/screenshots/continuation/` and `build/`.

The in-memory override and preservation of the original settings have an independent runnable check:

```powershell
.\gradlew.bat -I tools\vitrail-check.gradle vitrailCompatCheck
```

It finds the supported jar under `run/mods/` with either `.jar` or `.jar.disabled`; pass
`-PvitrailCheckJar=<path>` to use another location. This check passed on 2026-10-13. It exercises
the real Vitrail `PackSession` record and option values without starting Minecraft.

## Tested setup

- Reflexium `1.1.0-alpha`, source commit `530bb40`.
- Minecraft `26.3`, Fabric Loader `0.19.5`, Fabric API `0.162.0+26.3`.
- Vitrail `0.12.0-beta+mc26.3`, Modrinth version `Ul13RHiD`.
- Sodium `0.9.2+mc26.3`, Modrinth version `bAZQdGpg`.
- Complementary Reimagined `r5.9.3`, default HIGH profile.
- Windows 11, Java `25.0.4`, NVIDIA GeForce RTX 5070, driver `617.42`.
- Reflex On, DLSS detection enabled.

Vitrail jar SHA-256: `0823ba8ff806e9a1cc8520b7410783683d3a5f2213576212cecdeecc869bcfc8`.

## Source review

Vitrail was researched through [Modrinth](https://modrinth.com/mod/vitrail-shaders) and its [source repository](https://github.com/avpbynf/Vitrail-Shaders). Source reviewed at `f42e5489c6abff655781b40bebc70338bada3039`; runtime testing used the released jar above.

- Both mods wrap `VulkanBackend.createDevice(FeatureSet, VulkanPhysicalDevice)` with MixinExtras `WrapOperation`. Each composes its additional features onto the incoming FeatureSet and calls the original operation. The wrappers preserve each other's extensions and features.
- Vitrail's `VulkanQueueSubmitMixin` injects a submission counter before `vkQueueSubmit2KHR`. Reflexium's `ModifyArg` adds the current frame ID to that call's submission buffer, preserving existing `pNext` structures. Neither replaces the submission call.
- Vitrail's rendering stays on the game's Vulkan device and queue. Its shader work therefore passes through Reflexium's existing submission tagging, swapchain and present hooks.
- Each mod's F3 entries use its own namespace. Both sets of entries displayed together.

## Runtime evidence

`./gradlew build` passed and installed the release jar into the configured Modrinth testing profile. `./gradlew runClient` loaded Reflexium, Vitrail and Sodium together without a mixin failure. The test used the separate repository `run/` directory and a new creative world named `Reflexium Vitrail Smoke`.

The Vulkan device started with both feature sets enabled:

```text
(Vitrail) Vulkan device features: vertexPipelineStoresAndAtomics, fragmentStoresAndAtomics, shaderStorageImageExtendedFormats, shaderStorageImageWriteWithoutFormat, independentBlend, geometryShader, shaderFloat16, shaderInt8, shaderInt16, shaderSubgroupExtendedTypes, storageBuffer16BitAccess, uniformAndStorageBuffer16BitAccess, storageBuffer8BitAccess, uniformAndStorageBuffer8BitAccess
(Reflexium) Enabling device extensions: VK_NV_low_latency2, VK_KHR_present_id, VK_NVX_image_view_handle, VK_NVX_binary_import
(Reflexium) Vulkan device NVIDIA GeForce RTX 5070 (1.4.351 NVIDIA 617.42): low_latency2=true, present_id=true
```

Title-screen rendering produced driver latency data with the 60 FPS cap. In the world, Vitrail loaded Complementary through its shader reload key and drew terrain, shadows and composite passes. F3 showed the loaded pack alongside Reflex On and driver timings. These are functional readings, not a latency benchmark:

```text
20:39:18 (Reflexium) Reflex: On, cap 60 FPS | Reflex latency: 0.9 ms, sim 0.0, submit 0.7, GPU 0.1
20:40:24 (Minecraft) [System] [CHAT] Shaders Reloaded!
20:40:30 (Reflexium) Reflex: On, cap 120 FPS | Reflex latency: 2.2 ms, sim 0.0, submit 2.0, GPU 1.6
20:40:46 (Vitrail) Colour targets of ComplementaryReimagined_r5.9.3 sized for 984x611: 11 targets, 45 MiB
20:40:55 (Reflexium) Reflex: On | Reflex latency: 3.6 ms, sim 0.0, submit 3.5, GPU 5.4
```

Resizing the window recreated the swapchain and Vitrail's render targets; rendering and Reflex timing continued. Sodium's Video Settings screen also opened successfully. Dev-launch Realms authentication errors were unrelated to rendering.

## Repeating the check

1. Install the Minecraft 26.3 Vitrail jar, Sodium 0.9.2 and Fabric API alongside Reflexium. For a dev launch, put these optional test dependencies in `run/mods/`; they are not bundled into Reflexium.
2. Select Vulkan and restart. Confirm the log actually reports Vulkan and `low_latency2=true, present_id=true`.
3. Load a world with Complementary Reimagined r5.9.3. Open F3 and check that both Vitrail's pack entry and Reflexium's mode/timing entries are visible.
4. Reload the shader pack with R and resize the window. Verify the pack still renders and Reflex latency keeps updating.
5. Open Video Settings: Sodium's screen lists a Reflexium page next to Vitrail's pages (both register through Sodium's config API).

This historical check establishes Reflex compatibility for the versions and Reflex On configuration above. Other packs, other Vitrail/Sodium versions, Reflex Off/On + Boost, and OpenGL fallback were not covered by that run. It did not evaluate DLSS output. Vitrail's own render-scale setting does not enable DLSS.
