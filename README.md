# Reflexium

NVIDIA Reflex, DLSS, AMD FSR 3.1 and frame generation for Minecraft Java's Vulkan renderer. Client-side Fabric mod for Minecraft 26.3.

## What it does

- **Reflex low latency** through `VK_NV_low_latency2`: the driver sleeps the game just before input is read, so each frame starts as late as the GPU allows. Modes: Off, On, On + Boost.
- **Frame pacing**: while Reflex is on, Minecraft's Max Framerate (including the 60 FPS menu cap) is enforced by the Reflex sleep instead of vanilla's post-present sleep. No Reflex cap is inferred from the refresh rate. A fixed frame-generation multiplier never throttles real rendering to refresh / N: output is real x N unless VSync, a user FPS limit or a lower refresh target applies. Only Auto targets the display refresh rate.
- **Latency readout** in F3: input-to-present time plus simulation, render submit and GPU time, straight from the driver. Works with Reflex off too, for A/B comparisons.
- **DLSS and FSR 3.1 upscaling**: renders the world below display resolution, reconstructs camera motion from depth, and applies NVIDIA DLSS or AMD FSR 3.1 before drawing the full-resolution HUD. Presets include DLAA (Native AA for FSR), Quality, Balanced, Performance, and Ultra Performance. See [setup, limitations and validation](docs/DLSS.md).
- **Frame generation**: standard DLSS FG (2x), DLSS Multi Frame Generation (runtime-limited, up to 6x on the tested RTX 5070), and FSR FG (2x). FG also runs at native resolution when the upscaler is Off. Generation stops while a menu, loading screen or other GUI screen is open. DLSS-G runs through Streamline's present proxy and tags hudless colour and UI alpha so generated frames recompose the HUD and keep invert blends such as the crosshair correct. The raw NGX path remains the fallback for launches without Streamline. See [implementation and validation](docs/FRAME_GENERATION.md).

## Requirements

- Minecraft 26.3, Fabric Loader 0.19.5+, Java 25
- Windows x64 for the bundled native runtimes. Reflex requires an NVIDIA driver exposing `VK_NV_low_latency2`; DLSS requires an RTX GPU. FSR also supports AMD and Intel Vulkan GPUs, which were not part of this acceptance run.
- Graphics API set to **Vulkan** (Options, Video Settings, Graphics API). On OpenGL the mod does nothing.
- Optional: [Mod Menu](https://modrinth.com/mod/modmenu) for the settings screen

Fabric API is not required.

## Upscaling

- DLSS: requires an NVIDIA RTX GPU. A missing `nvngx_dlss.dll` is downloaded from NVIDIA, hash-checked and enabled in the same session. Existing DLLs are preserved. The native NGX and Streamline bridges are bundled. DLSS Super Resolution, Reflex/PCL and fixed 2x/3x/4x frame generation use Streamline 2.14.16 by default; `-Dreflexium.streamline=false` selects the raw NGX fallback. Vulkan Dynamic MFG is D3D12-only in Streamline 2.14.16, so Auto stays the application policy.
- FSR 3.1: works on any Vulkan GPU (AMD, Intel, NVIDIA).
- Auto picks DLSS when available, otherwise FSR 3.1. It never switches to native on its own. Without a shader pack, keep the upscaler Off (or Auto): vanilla Minecraft at 1080p and a low render distance is limited by the CPU and block geometry, so upscaling usually costs a few FPS there. Use upscaling when a shader pack, a high resolution or another GPU-heavy scene makes rendering the limit.
- Vitrail 0.12.x shader upscaling is supported, with Complementary Reimagined r5.9.3 tested. Reflexium owns render scale and jitter while active.
- Opaque moving entities and animated block entities receive per-vertex motion vectors. Shader-only deformation and translucent objects still have limitations; see [validation](docs/DLSS.md).

Measured on 23 October 2026, RTX 5070 at 1920x1080 with BSL 10.1.8: native 389 FPS, DLSS Quality 447, Balanced 462, Performance 508, Ultra Performance 539, FSR Performance 496. At 4K with the same pack: native 175 FPS, DLSS Quality 218, Balanced 240, Performance 274. DLAA is the one mode that can cost frame rate at 1080p because it evaluates at full resolution. Without shaders this scene is CPU bound, so native stays faster even though the world renders below output resolution. The 24 October clean-load round measured BSL 4K range at native 159.2 versus Quality 200.8 (+26.2%), Balanced 216.1 (+35.8%) and Performance 233.0 (+46.4%), with Quality still losing 14.6% in the 1440p forest and shaderless Quality losing 30.8% to 51.2% from 1080p to 4K. See [measured tables, per-pass timings and remaining limits](docs/PERFORMANCE.md) and the [round 6 report](docs/validation/round6-2026-10-24.md). FSR sharpening runs inside FSR's dispatch at the `fsr_sharpness` strength; DLSS output is not post-sharpened.

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
| `fsr_sharpness` | `0` to `100`; FSR 3.1 only | `0` |
| `post_upscale_particles` | `true`, `false`: draw vanilla particles at display resolution after upscaling | `true` |
| `frame_generation` | `off`, `dlss`, `dlss_multi`, `fsr` | `dlss` (only runs on supported GPUs with VSync off) |
| `frame_generation_multiplier` | `0` (Auto) or `2` to `8`, capped by runtime support | `0` |
| `betterf3_module_placed` | `true`, `false` (set after Reflexium adds its BetterF3 module once) | `false` |

Settings from the old `nvidia-reflex-mod.properties` are migrated automatically.

With [BetterF3](https://modrinth.com/mod/betterf3), which hides other mods' F3 lines, Reflexium adds a Reflexium module to the right column once. Move, recolor or remove it in BetterF3's module settings.

For bug reports, launch with `-Dreflexium.logLatency=true` to log the Reflex status and latency every 5 seconds.

## Shaders

- **[Vitrail Shaders](https://github.com/avpbynf/Vitrail-Shaders)**: verified with Vitrail `0.12.0-beta+mc26.3`, Sodium `0.9.2+mc26.3` and Complementary Reimagined `r5.9.3` on Windows/NVIDIA. Reflex sleep, frame limiting and driver latency readings work through the shared Vulkan backend. Install Vitrail's Minecraft 26.3 jar and its Sodium/Fabric API dependencies. See [the compatibility check](docs/VITRAIL.md) for evidence and repeat steps.
- DLSS and FSR run after the pack's final world pass and before the full-resolution GUI. Under Iris, depth comes from the loader's pre-translucent target, hand coverage comes from the hand draw, and the pack's own post-AA/sharpening options are switched off generically by option name (`TAA`, `TAA_DEFINE`, `TAA_MODE`, `TAA_JITTER`, `FXAA`, `SMAA`, `IMAGE_SHARPENING`). BSL and Complementary Reimagined additionally keep exact-source reviewed edits. Packs with fixed jitter/TAA in their source and no option to disable it are not certifiable. Unsupported Vitrail versions pause shader upscaling.
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
