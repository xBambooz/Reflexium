@echo off
rem Builds reflexium_ngx.dll with MSVC (Visual Studio 2022 Build Tools, C++ workload).
rem NGX_SDK defaults to a local clone of https://github.com/NVIDIA/DLSS; Vulkan-Headers is cloned into deps\.
setlocal
set HERE=%~dp0
if "%NGX_SDK%"=="" set NGX_SDK=%HERE%deps\DLSS
if not exist "%NGX_SDK%\include\nvsdk_ngx.h" (
	git clone --depth 1 https://github.com/NVIDIA/DLSS.git "%HERE%deps\DLSS" || exit /b 1
	set NGX_SDK=%HERE%deps\DLSS
)
if not exist "%HERE%deps\Vulkan-Headers" git clone --depth 1 https://github.com/KhronosGroup/Vulkan-Headers.git "%HERE%deps\Vulkan-Headers" || exit /b 1

set VCVARS=
for %%r in ("%ProgramFiles%" "%ProgramFiles(x86)%") do for %%e in (BuildTools Community Professional Enterprise) do (
	if exist "%%~r\Microsoft Visual Studio\2022\%%e\VC\Auxiliary\Build\vcvars64.bat" set "VCVARS=%%~r\Microsoft Visual Studio\2022\%%e\VC\Auxiliary\Build\vcvars64.bat"
)
if "%VCVARS%"=="" (echo Visual Studio 2022 C++ tools not found & exit /b 1)
call "%VCVARS%" >nul || exit /b 1

set OUT=%HERE%..\src\client\resources\natives\windows-x86_64
if not exist "%OUT%" mkdir "%OUT%"
if not exist "%HERE%build" mkdir "%HERE%build"
cl /nologo /O2 /MT /EHsc /std:c++17 /W3 /DNDEBUG /DVK_USE_PLATFORM_WIN32_KHR ^
	/I"%HERE%deps\Vulkan-Headers\include" /I"%NGX_SDK%\include" ^
	/Fo"%HERE%build\\" "%HERE%reflexium_ngx.cpp" ^
	/LD /Fe"%OUT%\reflexium_ngx.dll" ^
	/link /NOLOGO /LIBPATH:"%NGX_SDK%\lib\Windows_x86_64\x64" nvsdk_ngx_s.lib advapi32.lib user32.lib ^
	/IMPLIB:"%HERE%build\reflexium_ngx.lib" || exit /b 1
echo Built %OUT%\reflexium_ngx.dll

rem Streamline bridge: headers are vendored in native\streamline (SDK 2.14.16). The runtime DLLs are
rem loaded by name at runtime, so no import library is linked.
if not exist "%HERE%streamline\include\sl.h" (echo Streamline SDK headers missing in native\streamline & exit /b 1)
cl /nologo /O2 /MT /EHsc /std:c++17 /W3 /DNDEBUG /DVK_USE_PLATFORM_WIN32_KHR ^
	/I"%HERE%deps\Vulkan-Headers\include" /I"%HERE%streamline\include" ^
	/Fo"%HERE%build\\" "%HERE%reflexium_sl.cpp" ^
	/LD /Fe"%OUT%\reflexium_sl.dll" ^
	/link /NOLOGO wintrust.lib crypt32.lib advapi32.lib user32.lib ^
	/IMPLIB:"%HERE%build\reflexium_sl.lib" || exit /b 1
echo Built %OUT%\reflexium_sl.dll

set FFX=%HERE%deps\FidelityFX-SDK-v1.1.4
if not exist "%FFX%\ffx-api\include\ffx_api\ffx_upscale.h" (echo Extract FidelityFX SDK v1.1.4 into %FFX% first & exit /b 1)
cl /nologo /O2 /MT /EHsc /std:c++17 /W3 /DNDEBUG ^
	/I"%HERE%deps\Vulkan-Headers\include" /I"%FFX%\ffx-api\include" ^
	/Fo"%HERE%build\\" "%HERE%reflexium_fsr.cpp" ^
	/LD /Fe"%OUT%\reflexium_fsr.dll" ^
	/link /NOLOGO /LIBPATH:"%FFX%\PrebuiltSignedDLL" amd_fidelityfx_vk.lib ^
	/IMPLIB:"%HERE%build\reflexium_fsr.lib" || exit /b 1
copy /y "%FFX%\PrebuiltSignedDLL\amd_fidelityfx_vk.dll" "%OUT%\amd_fidelityfx_vk.dll" >nul
