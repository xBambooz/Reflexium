# NGX bridge build

`reflexium_ngx.cpp` exposes DLSS SR and FG operations to Java 25 FFM and links NVIDIA's static NGX library. `reflexium_fsr.cpp` exposes independent FSR upscaling/FG contexts; `fsr_swapchain.h` uses AMD's signed presentation scheduler. Bridge DLLs are bundled. Missing NVIDIA feature DLLs are installed automatically in the game directory's `reflexium/` folder.

Build on Windows x64 with Visual Studio 2022 C++ Build Tools and the Windows SDK:

```powershell
$env:NGX_SDK = 'C:\path\to\DLSS'
.\native\build.cmd
.\gradlew.bat build
```

Without `NGX_SDK`, the script clones NVIDIA/DLSS into ignored `native/deps/DLSS`. It also clones Khronos Vulkan-Headers when missing. Java builds use the checked-in bridge DLL and do not require MSVC.

Validated SDK: DLSS 310.9.1, NVIDIA/DLSS commit `374959484e79a640feaba44c93ac8cfb0a03f5b5`. Vulkan-Headers commit `c46850864f4661461b0f6cb9922c058ffea4915e`. Compiler: MSVC 14.44, static release CRT. When updating the SDK, update the included NVIDIA license and retest create/evaluate/release, all modes and resizing.

The Java/native image structure is 32 bytes: two 64-bit Vulkan handles, then four 32-bit values (format, width, height, aspect). A C++ static assertion protects its size. Feature release requires submitted GPU commands to complete; Java flushes the encoder and waits for the device before releasing resources.

FSR SR now uses versioned `rx_fsr_create_v2` / `rx_fsr_evaluate_v2` exports: creation takes an HDR flag, evaluation takes optional reactive and transparency/composition `RxImage` pointers after output. Java and the bundled DLL must be rebuilt together. The experimental pack route supplies RGBA16F linear color with pre-exposure 1 and auto exposure; material contribution feeds the FSR masks, never DLSS K/L/M. `NativeBridgeCheck` (run by `gradlew check`) calls the actual native ABI with both null and non-null masks before a Vulkan context exists, expecting rejection before any resource access.

`frame_constants.h` and `FrameGenerationConstants.java` define the FG camera ABI: 99 packed floats,
including five row-vector matrices, pixel jitter, position/basis and projection/timing scalars.
JOML's column-major bytes represent the transposed matrix in row-major NGX form. `FrameGenerationConstantsCheck`
verifies the resulting row-vector multiplication. NGX converts pixel jitter to clip space and
pixel motion to normalized units; FSR receives pixel jitter/motion scale. See
[FG implementation and acceptance](../docs/FRAME_GENERATION.md).

References: [NVIDIA DLSS SDK](https://github.com/NVIDIA/DLSS), [Radiance](https://github.com/Minecraft-Radiance/Radiance). Reflexium uses its own bridge over NVIDIA's SDK helpers, not Radiance's renderer source.
