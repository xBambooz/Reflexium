# Iris Vulkan b3 compatibility

Verified 2026-10-22 on Minecraft 26.3, RTX 5070, DH 3.3.5-dev and Mellow v3.4.1a
at 1920x1080. Change starts from Reflexium `16a8ef3`.

User artifact: `iris-fabric-1.12.0-b3+mc26.3-local.jar`.
SHA-256: `DE1ECDEBC3E1ADE79A9F2DCC4010D6AA4B9BA23C01DCF2258B79BD017D732C3B`.
The development copy at `run/mods/iris-fabric-experimental-build-26.3.jar` matches.
Its internal version matches the earlier snapshot, so version strings alone cannot
identify this artifact.

## Fix

Enabling either DLSS or FSR reproduced a native crash in the DH depth resize/clear
path while terrain rendering had an open pass. `DhDepthResizeMixin` now invokes the
existing Iris resize method at the end of `beginLevelRendering`, after main-target
dimensions are set and before terrain rendering. Iris returns immediately when the
depth size already matches. The hook requires supported Iris and DH versions.

## Checks

- `gradlew.bat build -Preflexium.irisJar=C:/Users/Ryan/Downloads/iris-fabric-1.12.0-b3+mc26.3-local.jar`:
  all 11 checks passed and `installReflexium` completed.
- `gradlew.bat irisCompatCheck distantHorizonsCheck -Preflexium.irisJar=backup/pre-iris-b3/iris-fabric-experimental-build-26.3.jar`:
  earlier Iris ABI checks passed.
- Live DLSS Quality: 1,962 evaluated frames, 1280x720 to 1920x1080, no reported error.
- Live FSR Quality: 10,725 evaluated frames, same dimensions, no reported error.
- Live DLSS MFG: effective 3x, no reported error.
- Live DLSS Performance after returning to native: 4,072 evaluated frames,
  960x540 to 1920x1080, no reported error.
- DLSS and FSR screenshots inspected: scene, terrain, hand and HUD rendered.
- Built and installed Reflexium JAR SHA-256 match.

Raw local evidence: `build/validation/iris-b3/`, including `final-build.log`,
`old-abi.log`, `dlss.json`, `fsr.json`, `mfg.json`, `performance.json`, screenshots,
pre-fix crash logs and `javap` output. Backups are under `backup/pre-iris-b3/`.

These are compatibility smoke checks, not an exhaustive image-quality or performance
audit. Other shader packs, distant water, window resizing and the earlier Iris runtime
were not revalidated in this update.
