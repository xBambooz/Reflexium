# Remix and Radiance camera notes, 2026-11-01

Read-only clones outside Reflexium. No reference code copied, built or run.
Remix `72e5dcef`: Apache-2.0 (`LICENSE` and source SPDX).
Radiance `948b29a3`: GPL-3.0 (`LICENCE`), MCVR `6719d74e`: primarily GPL-3.0
with listed exceptions (`LICENSE.md`), Shaderpack-Advanced `bf26252f`: GPL-3.0 (`LICENSE`).
The older `t3/13489586:docs/research/radiance-comparison-2026-10-19.md` was read;
its raw-NGX comparison predates Reflexium's current Streamline default.

## View bob and camera

- [Remix CameraCapture](https://github.com/NVIDIAGameWorks/rtx-remix-minecraft/blob/72e5dcefd468dcd20623615d26234181c18bd1e8/forge-1.12.2/src/main/java/com/nvidia/rtxremix/forge/client/CameraCapture.java)
  reads actual world-pass GL modelview and projection, then inverts the modelview for position
  and basis, explicitly including bob and third-person offsets. Useful principle: capture the
  transform used to draw, rather than reconstructing effects from player movement.
- Radiance `src/main/java/com/radiance/mixins/vulkan_render_integration/GameRendererMixins.java`
  captures the stack effect `B` and passes `B * viewMatrix` to the world renderer. MCVR
  `src/core/render/buffers.cpp:462` retains inverses of both `cameraViewMat` and
  `cameraEffectedViewMat`. Shaderpack-Advanced `path/primary/discover.rgen:41` measures depth
  in the effected view, and MCVR `src/shader/world/ray_tracing/internal/advanced/scene/camera.glsl:30`
  projects previous points through the previous effected view. This independently supports using
  the effected view in Reflexium history. It does not establish live improvement in Reflexium.

## Applicable open issues

- Distant shimmer and turn doubling: `discover.rgen` restores camera-relative translation before
  previous projection. `camera.glsl` uses homogeneous w=0 for sky direction. Reflexium already
  has corresponding translation and sky treatment. Correcting its missing bob precedes any new
  filtering or precision change; these references do not prove a mip or FG depth fix.
- Moving surfaces: MCVR `scene/motion.glsl` loads previous vertex positions and instance transforms.
  This is useful for animation history, but is not a solution to static world bob or a reason to
  replace Reflexium's existing ObjectMotion pass.
- Hand: Remix `src/main/java/com/nvidia/rtxremix/minecraft/entity/RemixFirstPersonPass.java`
  captures the vanilla hand separately and corrects its modelview/FOV. Radiance's same
  `GameRendererMixins` passes a separate hand/world FOV scale. Preserve Reflexium's separate
  hand history, rather than inserting world bob into hand vectors.
- World text: neither inspected camera path supplies a drop-in fix for Iris pack shading of
  deferred glyphs. No text change follows from these references.

The [Modrinth Radiance listing](https://modrinth.com/mod/radiance-mod-windows) was consulted as
the similar-mod entry point. The implementation observations above come from the pinned sources.
