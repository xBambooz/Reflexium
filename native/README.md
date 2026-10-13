# NGX bridge build

`reflexium_ngx.cpp` exposes nine C functions to Java 25 FFM and links NVIDIA's static NGX library. The bridge DLL is bundled in the mod; NVIDIA's feature DLL is installed separately in the game directory's `reflexium/` folder.

Build on Windows x64 with Visual Studio 2022 C++ Build Tools and the Windows SDK:

```powershell
$env:NGX_SDK = 'C:\path\to\DLSS'
.\native\build.cmd
.\gradlew.bat build
```

Without `NGX_SDK`, the script clones NVIDIA/DLSS into ignored `native/deps/DLSS`. It also clones Khronos Vulkan-Headers when missing. Java builds use the checked-in bridge DLL and do not require MSVC.

Validated SDK: DLSS 310.9.1, NVIDIA/DLSS commit `374959484e79a640feaba44c93ac8cfb0a03f5b5`. Vulkan-Headers commit `c46850864f4661461b0f6cb9922c058ffea4915e`. Compiler: MSVC 14.44, static release CRT. When updating the SDK, update the included NVIDIA license and retest create/evaluate/release, all modes and resizing.

The Java/native image structure is 32 bytes: two 64-bit Vulkan handles, then four 32-bit values (format, width, height, aspect). A C++ static assertion protects its size. Feature release requires submitted GPU commands to complete; Java flushes the encoder and waits for the device before releasing resources.

References: [NVIDIA DLSS SDK](https://github.com/NVIDIA/DLSS), [Radiance](https://github.com/Minecraft-Radiance/Radiance). Reflexium uses its own bridge over NVIDIA's SDK helpers, not Radiance's renderer source.
