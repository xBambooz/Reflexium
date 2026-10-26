# Plan: DLSS gains without shaders through Voxy LOD rendering

Status: planned 2026-10-24, scheduled to run after the Streamline round (round 4) finishes, because both
need the same client and run directory. Objective: [OBJECTIVES.md](../OBJECTIVES.md) objective 1.

## Hypothesis

Without shaders, DLSS loses 2 to 14 percent (see
[range-pans-mobs-2026-10-24.md](../validation/range-pans-mobs-2026-10-24.md)). Vanilla terrain at render
distance 31 is CPU/geometry bound: 4K native is about 2.4 ms per frame and 1080p is about 1.5 ms, so lower
resolution saves at most about 1 ms, which is roughly the DLSS evaluate cost. Voxy (LOD terrain to hundreds
or thousands of chunks) moves much more work onto the GPU. If a large part of that work is per-pixel
(fragment shading, overdraw, depth), the scene becomes GPU bound, and DLSS should pay back its cost the way
it does with shader packs. If Voxy's cost is mostly per-vertex or compute (meshlet culling, geometry), lower
resolution will help less. The test decides which.

## Gate 0: compatibility (stop and report if any fails)

1. Find a Voxy build for Minecraft 26.3 from its official Modrinth or GitHub release only. Put it in its
   own directory, check the hash against the release page, and record version and source.
2. Voxy renders through its own OpenGL path. Reflexium upscaling needs Minecraft's Vulkan backend. Check
   whether Voxy runs on the 26.3 Vulkan backend. If it is OpenGL-only, the test is blocked: report it, and
   check whether Distant Horizons 3.3.5-dev (already installed, and it already has Reflexium depth hooks) is
   a valid LOD substitute on Vulkan.
3. Confirm that Reflexium's depth, motion vectors and jitter cover the LOD terrain. LOD geometry must be
   drawn with the jittered projection and must write the depth Reflexium reads, otherwise DLSS will ghost or
   shimmer on the far terrain. See the existing DH late-depth handling in docs/validation/dh-late-depth-resize.md.

## Gate 1: integration (only if Gate 0 passes)

- Jitter: LOD terrain must use the same jittered projection as near terrain.
- Depth: LOD depth must be merged into the depth DLSS receives (reverse-Z and near/far conventions checked).
- Motion: static LOD terrain uses camera-only vectors from the same matrices. No separate heuristics.
- Mip bias: LOD textures/colours must get the same negative LOD bias as near terrain.
- History reset on LOD rebuilds is not allowed (it would cause flicker); LOD streaming must not trigger resets.

## Gate 2: measurement matrix

Shaderless, Reflex off, FG off, VSync off, Max Framerate unlimited, Vulkan backend.

| Variable | Values |
| --- | --- |
| LOD distance | Voxy off, 128, 256, 512 chunks (or DH equivalents) |
| Resolution | 1920x1080, 2560x1440, 3840x2160 |
| Mode | Native, DLAA, DLSS Q, B, P |
| Scene | Test range pad (200.5 151 0.5), smoke platform (-24.5 131 10.5), forest ground view (200.5 67 150 yaw 90) |

Record average FPS, 1 percent low, GPU frame time, and the per-pass GPU timestamps (Voxy pass, vanilla
terrain, DLSS evaluate, Reflexium passes). Each cell is measured twice; repeat any pair that differs by more
than 3 percent.

## Gate 3: image quality

On the test range with LOD on: static, walking with view bob, slow pan, fast flick, and moving mobs. Compare
native vs DLSS Q/P. Look specifically for LOD terrain ghosting, LOD seam shimmer, far terrain blur and
missing motion on LOD chunks. Crops at 960 px or smaller only.

## Pass criteria and outcome

- Pass: with LOD at a typical player setting (256 chunks or more), DLSS Q/B/P beat native at 1440p and 4K
  without shaders, with no LOD ghosting or shimmer. Then update the in-game tooltip to recommend DLSS when
  Voxy/LOD is enabled, and document the setup.
- Fail: report the measured floor and why (vertex- or compute-bound LOD), and keep the recommendation to use
  Off without shaders.
- In both cases: no band-aid paths; any LOD support goes through the same depth/jitter/motion hooks as the
  rest of the renderer.
