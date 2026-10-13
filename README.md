# Reflexium

NVIDIA Reflex, DLSS and AMD FSR 3.1 for Minecraft Java's Vulkan renderer. Client-side Fabric mod for Minecraft 26.3.

## What it does

- **Reflex low latency** through `VK_NV_low_latency2`: the driver sleeps the game just before input is read, so each frame starts as late as the GPU allows. Modes: Off, On, On + Boost.
- **Reflex FPS cap**: while Reflex is on, Minecraft's Max Framerate (including the 60 FPS menu cap) is enforced by the Reflex sleep instead of vanilla's post-present sleep.
- **Latency readout** in F3: input-to-present time plus simulation, render submit and GPU time, straight from the driver. Works with Reflex off too, for A/B comparisons.
- **DLSS and FSR 3.1 upscaling**: renders the world below display resolution, reconstructs camera motion from depth, and applies NVIDIA DLSS or AMD FSR 3.1 before drawing the full-resolution HUD. Presets include Native AA, Quality, Balanced, Performance, and Ultra Performance. See [setup, limitations and validation](docs/DLSS.md).

## Requirements

- Minecraft 26.3, Fabric Loader 0.19.5+, Java 25
- Windows with an NVIDIA GPU and a driver that exposes `VK_NV_low_latency2` (GeForce 900 series or newer, current driver)
- Graphics API set to **Vulkan** (Options, Video Settings, Graphics API). On OpenGL the mod does nothing.
- Optional: [Mod Menu](https://modrinth.com/mod/modmenu) for the settings screen

Fabric API is not required.

## Upscaling

- DLSS: requires an NVIDIA RTX GPU and `nvngx_dlss.dll` in `<game>/reflexium/`. The native NGX bridge is bundled.
- FSR 3.1: works on any Vulkan GPU (AMD, Intel, NVIDIA).
- Auto mode picks DLSS when available, otherwise FSR 3.1.
- Paused while a Vitrail shader pack draws.
- Moving entities have no motion vectors yet (camera motion only).

## Settings

Settings appear in:

- Video Settings, below Graphics API, when the driver supports Reflex (vanilla screen).
- Sodium's Video Settings as a Reflexium page when Sodium is installed. Reese's Sodium Options and other Sodium screen replacements show the same page. Tested with Sodium 0.9.2 and Reese's Sodium Options 2.2.5 (which requires exactly Sodium 0.9.2).
- Mod Menu, and `config/reflexium.properties` (restart after editing).

| Key | Values | Default |
| --- | --- | --- |
| `reflex_mode` | `off`, `on`, `on_boost` | `on` |
| `reflex_frame_limiter` | `true`, `false` | `true` |
| `dlss_detection` | `true`, `false` (restart to apply) | `true` |
| `upscaler` | `off`, `auto`, `dlss`, `fsr` | `auto` |
| `upscale_quality` | `native_aa`, `quality`, `balanced`, `performance`, `ultra_performance` | `quality` |
| `dlss_model` | `auto`, `transformer`, `cnn` | `auto` |
| `fsr_sharpness` | `0` to `100` | `20` |
| `betterf3_module_placed` | `true`, `false` (set after Reflexium adds its BetterF3 module once) | `false` |

Settings from the old `nvidia-reflex-mod.properties` are migrated automatically.

With [BetterF3](https://modrinth.com/mod/betterf3), which hides other mods' F3 lines, Reflexium adds a Reflexium module to the right column once. Move, recolor or remove it in BetterF3's module settings.

For bug reports, launch with `-Dreflexium.logLatency=true` to log the Reflex status and latency every 5 seconds.

## Shaders

- **[Vitrail Shaders](https://github.com/avpbynf/Vitrail-Shaders)**: verified with Vitrail `0.12.0-beta+mc26.3`, Sodium `0.9.2+mc26.3` and Complementary Reimagined `r5.9.3` on Windows/NVIDIA. Reflex sleep, frame limiting and driver latency readings work through the shared Vulkan backend. Install Vitrail's Minecraft 26.3 jar and its Sodium/Fabric API dependencies. See [the compatibility check](docs/VITRAIL.md) for evidence and repeat steps.
- DLSS and FSR pause while Vitrail draws a shader pack, then resume when shaders are disabled. Moving entities also lack independent motion vectors and may trail. Reflex remains available with shaders.
- **Aperture** (the Iris team's Vulkan shader loader): shared Vulkan hooks are the intended integration point; runtime compatibility and DLSS integration remain unverified.
- **Iris** is OpenGL-only, so Reflex is unavailable while it is in use.

## How it works

| Hook | Purpose |
| --- | --- |
| `VulkanBackend.createDevice` | adds `VK_NV_low_latency2`, `VK_KHR_present_id` (+ `presentId` feature) and the DLSS NVX extensions when the GPU reports them |
| `VulkanGpuSurface.configure` | creates the swapchain with `VkSwapchainLatencyCreateInfoNV` and applies the sleep mode |
| `VulkanQueue.Submission.close` | tags every `vkQueueSubmit2` with `VkLatencySubmissionPresentIdNV` |
| `VulkanGpuSurface.present` | tags the present with `VkPresentIdKHR` |
| `Minecraft.run` | Reflex sleep before SDL event polling, then the input sample marker |
| `Minecraft.runTick` / `renderFrame` | simulation, render submit and present markers; skips vanilla's limiter when Reflex enforces the cap |

## Building

```
./gradlew build
```

The jar is written to `build/libs/Reflexium-<version>-fabric-<minecraft>.jar` and copied into the Modrinth profile `26.3 Testing Instance` when it exists. One release line per Minecraft version.

The build checks resource JSON and camera reprojection math before installing. To rebuild the bundled native bridge, use `native\build.cmd` with Visual Studio 2022 C++ Build Tools and the Windows SDK installed. `NGX_SDK` can point to an existing NVIDIA DLSS SDK checkout. See [native build notes](native/README.md).

## License

Reflexium source: MIT. The native bridge incorporates NVIDIA NGX under the NVIDIA RTX SDK license, included in `META-INF/licenses/NVIDIA-DLSS.txt`.
