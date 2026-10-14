<# Build the native reference using MSVC, local Vulkan headers and the installed Windows loader. #>
param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\build\reflex-reference'),
    [string]$HeaderDirectory = (Join-Path $PSScriptRoot '..\native\deps\Vulkan-Headers\include')
)
$ErrorActionPreference = 'Stop'
$headers = (Resolve-Path -LiteralPath $HeaderDirectory).Path
if (!(Test-Path -LiteralPath (Join-Path $headers 'vulkan\vulkan.h'))) { throw 'Vulkan headers missing; specify -HeaderDirectory.' }
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
$vs = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (!$vs) { throw 'MSVC x64 tools missing.' }
$vcvars = Join-Path $vs 'VC\Auxiliary\Build\vcvars64.bat'
$out = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $out | Out-Null
$source = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot 'reflex-reference.cpp')).Path
# Import only the core/WSI functions actually called. Extension entry points use vkGetDeviceProcAddr.
# This removes the obsolete dependency on an SDK directory in a deleted investigation worktree.
$calls = [regex]::Matches([IO.File]::ReadAllText($source), '\bvk[A-Za-z0-9_]+(?=\()') | ForEach-Object Value | Sort-Object -Unique
[IO.File]::WriteAllLines((Join-Path $out 'vulkan-1.def'), @('LIBRARY vulkan-1','EXPORTS') + $calls)
$batch = @"
@echo off
call "$vcvars" >nul
if errorlevel 1 exit /b 1
lib /nologo /def:"$out\vulkan-1.def" /machine:x64 /out:"$out\vulkan-1.lib"
if errorlevel 1 exit /b 1
cl /nologo /O2 /EHsc /std:c++17 /I"$headers" "$source" /Fo"$out\reflex-reference.obj" /Fe"$out\reflex-reference.exe" /link /NOLOGO "$out\vulkan-1.lib" user32.lib winmm.lib
"@
$script = Join-Path $out 'build.cmd'
[IO.File]::WriteAllText($script, $batch)
& $script
if ($LASTEXITCODE -ne 0) { throw "Native reference build failed ($LASTEXITCODE)." }
Get-FileHash -LiteralPath (Join-Path $out 'reflex-reference.exe') -Algorithm SHA256
