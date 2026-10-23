# Late Iris/DH depth resize, 2026-10-23

Base: Reflexium `e829e32`. Minecraft 26.3, Iris Vulkan b3 (SHA-256
`DE1ECDEBC3E1ADE79A9F2DCC4010D6AA4B9BA23C01DCF2258B79BD017D732C3B`),
DH 3.3.5-dev, Mellow 3.4.1a, RTX 5070, Zulu Java 25.0.4.1/Shenandoah.

The early `beginLevelRendering` resize hook remains the normal path. The new
guard checks DH depth before the shared `LodRenderer.renderTerrain(RenderParam,
IProfilerWrapper, boolean)` method, covering opaque and deferred rendering. A late
size change or closed depth input skips DH setup for that pass and rejects the
frame's SR/FG inputs. The next world frame can resize depth at the existing safe
point. Packs without DH terrain shaders retain their existing path.

## Reproduction and checks

The normal local-world launch did not reproduce the reported 23:21 server crash.
The test copy was also exercised with cached server resource packs and the older
mod versions from the 23:25 isolation log. This does not establish the cause of
the stackless 23:21 exit.

Disabling the early resize hook with
`-Preflexium.disableDhMixin.DhDepthResizeMixin=true` reproduced a native access
violation in the old JAR. The stack contains `vkCmdEndRenderPass`,
`WorldRenderPass.suspendForNativeWork`, `DHCompatInternal.clearDepth/resizeDepth/setup`
and the deferred terrain path. With the same hook disabled, the new JAR survived
and logged `Deferring Iris DH depth resize until the next world frame`. SR/FG
evaluations stayed zero in this deliberately degraded test. The guard does not
replace the early resize hook.

With the early hook restored:

- DLSS resumed after world join, with nonzero evaluations and no renderer error.
- FSR Quality rendered 1280x684 to 1920x1026 after a window resize. All inspected
  terrain/water/generic depth attachments still belonged to DH and matched that
  render size. The captured terrain, hand and HUD were visually inspected.
- A resource reload followed by DLSS Quality and 2x FG produced 158 FG evaluations
  and 156 generated presents at the recorded checkpoint, with no renderer error.
- Returning to native with shaders and FG off left no renderer error.
- All 11 executable checks passed. The DH check covers size mismatch, closed
  views/textures, rejection and next-frame acceptance, plus the exact hook ABI.

The test windows stayed muted and unfocused on the secondary monitor. The harness
mouse check passed, and an API camera turn moved exactly five degrees. All owned
rendering test clients were closed afterward. The live user session was preserved.

## Evidence and limits

Raw evidence: `build/validation/dh-server-crash/`, including `baseline.jar`,
`fixed-build.log`, `full-run/hs_err_pid23112.log`, `guard-final.json`,
`normal-dlss.json`, `normal-fsr.json`, `normal-fsr-targets.json`, `fixed-fsr.png`,
`normal-reload-fg.json`, `normal-native-no-shaders.json` and input-check JSON files.
Harness launch records: ports 25642 (old forced failure), 25644 (guard) and 25645
(normal rendering).

The live server join was not retested because Ryan requested that his connected
session remain connected. An attempted separate local-server test was rejected
by automatic approval review with only `blocked by policy`; the existing isolated
client provided the forced crash reproduction instead. These are functional
checks, not FPS benchmarks: the user's main game shared the GPU throughout.

For the separate shader-off FPS report, the observed main-instance DH configuration
used a 128-chunk LOD radius, HIGH horizontal/vertical quality and COMPLETE
transparency, versus 8 vanilla chunks. DH workers were limited to two threads at
0.5 duty. No main-instance DH setting was changed, and the reported 800 FPS loss
was not isolated from concurrent GPU use.

The exact DH JAR's `ThreadPresetConfigEventHandler` maps Minimal Impact to
ceil(10% of available processors) and runtime ratio 0.5. Thus two workers at 0.5
matches that preset on 16 logical processors. Balanced defaults to half the
available processors at ratio 1.0. These limits govern DH background workers,
not all Minecraft threads; increasing them is not a verified FPS improvement.

The fixed JAR was installed into both `Fabric 26.3/mods` and
`26.3 Testing Instance/mods`, with matching SHA-256
`66A07BE9DD61431F4D23C6D972769E88053DD704BDBEBCEECB626542605BDC59`.
The main instance's prior disabled JAR was backed up before replacement. Its
running session was not restarted, so it will use the new build on next launch.
