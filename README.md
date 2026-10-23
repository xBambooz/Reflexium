# Reflexium

NVIDIA Reflex, DLSS, AMD FSR 3.1 and frame generation for Minecraft Java's Vulkan renderer. Client-side Fabric mod for Minecraft 26.3.

## What it does

- **Reflex low latency** through `VK_NV_low_latency2`: the driver sleeps the game just before input is read, so each frame starts as late as the GPU allows. Modes: Off, On, On + Boost.
- **Frame pacing**: while Reflex is on, Minecraft's Max Framerate (including the 60 FPS menu cap) is enforced by the Reflex sleep instead of vanilla's post-present sleep. No Reflex cap is inferred from the refresh rate; frame generation keeps its own output budget.
- **Latency readout** in F3: input-to-present time plus simulation, render submit and GPU time, straight from the driver. Works with Reflex off too, for A/B comparisons.
- **DLSS and FSR 3.1 upscaling**: renders the world below display resolution, reconstructs camera motion from depth, and applies NVIDIA DLSS or AMD FSR 3.1 before drawing the full-resolution HUD. Presets include DLAA (Native AA for FSR), Quality, Balanced, Performance, and Ultra Performance. See [setup, limitations and validation](docs/DLSS.md).
- **Frame generation (experimental)**: standard DLSS FG (2x), DLSS Multi Frame Generation (runtime-limited, up to 6x on the tested RTX 5070), and FSR FG (2x). Select Auto or the matching upscaler; turning upscaling off disables FG. Minecraft's prepared GUI is drawn onto every output frame, preserving sharp HUD text and background-dependent crosshair blending. See [implementation and validation](docs/FRAME_GENERATION.md).

## Requirements

- Minecraft 26.3, Fabric Loader 0.19.5+, Java 25
- Windows x64 for the bundled native runtimes. Reflex requires an NVIDIA driver exposing `VK_NV_low_latency2`; DLSS requires an RTX GPU. FSR also supports AMD and Intel Vulkan GPUs, which were not part of this acceptance run.
- Graphics API set to **Vulkan** (Options, Video Settings, Graphics API). On OpenGL the mod does nothing.
- Optional: [Mod Menu](https://modrinth.com/mod/modmenu) for the settings screen

Fabric API is not required.

## Upscaling

- DLSS: requires an NVIDIA RTX GPU. A missing `nvngx_dlss.dll` is downloaded from NVIDIA, hash-checked and enabled in the same session. Existing DLLs are preserved. The native NGX bridge is bundled.
- FSR 3.1: works on any Vulkan GPU (AMD, Intel, NVIDIA).
- Auto renders natively without a shader pack. With one, it picks DLSS when available, otherwise FSR 3.1, and returns to native when a measured comparison shows DLSS is not faster.
- Vitrail 0.12.x shader upscaling is supported, with Complementary Reimagined r5.9.3 tested. Reflexium owns render scale and jitter while active.
- Opaque moving entities and animated block entities receive per-vertex motion vectors. Shader-only deformation and translucent objects still have limitations; see [validation](docs/DLSS.md).

Upscaling has a fixed reconstruction cost. Without shaders, native rendering can be faster even at lower upscaling qualities; DLAA also pays the full-resolution network cost. DLSS uses transient motion uniforms and retained scene depth; both upscalers skip depth reconstruction for an invisible outline. See [per-pass timings, paired results and remaining limits](docs/PERFORMANCE.md). DLSS output gets the same RCAS sharpening as FSR, at the `fsr_sharpness` strength (0 disables it).

## Settings

Settings appear in:

- Video Settings, below Graphics API, when the driver supports Reflex (vanilla screen).
- Sodium's Video Settings as a Reflexium page when Sodium is installed. Reese's Sodium Options and other Sodium screen replacements show the same page. Tested with Sodium 0.9.2 and Reese's Sodium Options 2.2.5 (which requires exactly Sodium 0.9.2).
- Mod Menu, and `config/reflexium.properties` (restart after editing).

| Key | Values | Default |
| --- | --- | --- |
| `reflex_mode` | `off`, `on`, `on_boost` | `on` |
| `upscaler` | `off`, `auto`, `dlss`, `fsr` | `auto` |
| `upscale_quality` | `native_aa`, `quality`, `balanced`, `performance`, `ultra_performance` | `quality` |
| `dlss_model` | `auto`, `transformer`, `cnn` | `cnn` |
| `fsr_sharpness` | `0` to `100`; also the DLSS sharpening strength | `20` |
| `post_upscale_particles` | `true`, `false`: draw vanilla particles at display resolution after upscaling | `true` |
| `frame_generation` | `off`, `dlss`, `dlss_multi`, `fsr` | `dlss` (only runs on supported GPUs with VSync off) |
| `frame_generation_multiplier` | `0` (Auto) or `2` to `8`, capped by runtime support | `0` |
| `fg_force_multiplier` | `true`, `false`: keep a fixed multiplier even when it exceeds the refresh budget | `false` |
| `pack_shadow_resolution_cap` | `0` (pack value) to `8192` | `0` |
| `pack_shadow_distance_cap` | `0` (pack value) to `1024` | `0` |
| `betterf3_module_placed` | `true`, `false` (set after Reflexium adds its BetterF3 module once) | `false` |

Settings from the old `nvidia-reflex-mod.properties` are migrated automatically.

With [BetterF3](https://modrinth.com/mod/betterf3), which hides other mods' F3 lines, Reflexium adds a Reflexium module to the right column once. Move, recolor or remove it in BetterF3's module settings.

For bug reports, launch with `-Dreflexium.logLatency=true` to log the Reflex status and latency every 5 seconds.

## Shaders

- **[Vitrail Shaders](https://github.com/avpbynf/Vitrail-Shaders)**: verified with Vitrail `0.12.0-beta+mc26.3`, Sodium `0.9.2+mc26.3` and Complementary Reimagined `r5.9.3` on Windows/NVIDIA. Reflex sleep, frame limiting and driver latency readings work through the shared Vulkan backend. Install Vitrail's Minecraft 26.3 jar and its Sodium/Fabric API dependencies. See [the compatibility check](docs/VITRAIL.md) for evidence and repeat steps.
- DLSS and FSR run after Vitrail's final world pass and before the full-resolution GUI. Known Complementary packs have their own TAA disabled in memory while upscaling. Unsupported Vitrail versions pause shader upscaling.
- **Aperture** (the Iris team's Vulkan shader loader): shared Vulkan hooks are the intended integration point; runtime compatibility and DLSS integration remain unverified.
- **Iris**: the experimental Iris Vulkan build `1.12.0-snapshot+mc26.3-local` is supported, including Distant Horizons (see [Distant Horizons](docs/DISTANT_HORIZONS.md) and [shader loaders](docs/SHADER_LOADER.md)). Other Iris versions pause shader upscaling; stable OpenGL Iris never reaches the Vulkan path.

## How it works

| Hook | Purpose |
| --- | --- |
| `VulkanBackend.createDevice` | adds `VK_NV_low_latency2`, `VK_KHR_present_id` (+ `presentId` feature) and the DLSS NVX extensions when the GPU reports them |
| `VulkanGpuSurface.configure` | creates the swapchain with `VkSwapchainLatencyCreateInfoNV` and applies the sleep mode |
| `VulkanQueue.Submission.close` | tags every `vkQueueSubmit2` with `VkLatencySubmissionPresentIdNV` |
| `VulkanGpuSurface.present` | tags the present with `VkPresentIdKHR` |
| `Minecraft.run` | Reflex sleep, simulation-start and input-sample markers before SDL event polling |
| `Minecraft.runTick` / `renderFrame` | simulation, render submit and present markers; skips vanilla's limiter when Reflex enforces the cap |

## Project layout

Client code lives in `src/client/java/com/bambooz/reflexium`:

| Package | Contents |
| --- | --- |
| `reflex` | Reflex sleep, latency markers, frame cap and latency readout |
| `upscale` | Super resolution frame flow (`UpscalingRenderer`), jitter, camera history, mip bias, post-upscale passes |
| `upscale.dlss`, `upscale.fsr` | NGX and FSR native bridges, DLSS runtime download, FSR presentation scheduler |
| `framegen` | Frame generation renderer, present metering and the refresh-rate output policy |
| `motion` | Camera, entity, hand, cloud and shader pack motion vectors |
| `vulkan` | Device extensions, swapchain and queue hooks, native image handles |
| `compat` | Iris, Vitrail, Complementary, BSL, Distant Horizons, Sodium, Mod Menu and BetterF3 |
| `config`, `gui` | `config/reflexium.properties` and the settings screens |
| `debug`, `debug.server` | F3 lines, profiler, measurement counters, and the dev-only HTTP harness ([debugging](docs/DEBUGGING.md)) |
| `mixin` | Minecraft, RenderPearl and optional-mod hooks |

Native bridge sources are in `native/`, assertion checks in `src/test/java`, dev scripts in `tools/` ([index](tools/README.md)) and documentation in `docs/` ([index](docs/README.md)).

## Building

```
./gradlew build
```

The jar is written to `build/libs/Reflexium-<version>-fabric-<minecraft>.jar` and copied into the Modrinth profile `26.3 Testing Instance` when it exists. One release line per Minecraft version.

The build runs every assertion check before installing: resource JSON, native call signatures, camera reprojection, jitter phases, object-motion history, frame delivery, Reflex timing and the shader pack integrations. The list is in `build.gradle` (`assertionChecks`). To rebuild the bundled native bridge, use `native\build.cmd` with Visual Studio 2022 C++ Build Tools and the Windows SDK installed. `NGX_SDK` can point to an existing NVIDIA DLSS SDK checkout. See [native build notes](native/README.md).

## License

Reflexium source: MIT. The native bridge incorporates NVIDIA NGX under the NVIDIA RTX SDK license, included in `META-INF/licenses/NVIDIA-DLSS.txt`.
