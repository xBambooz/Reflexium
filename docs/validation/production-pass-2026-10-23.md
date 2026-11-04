# Upscaling and frame generation pass, 2026-10-23

Purpose: verify and finish the FSR, DLSS and frame generation paths after the Reflex cleanup, with
measured frame rates, walking captures and a smaller renderer.

## Environment

- Windows 11, RTX 5070 12 GB, NVIDIA 617.42, Java 25, Minecraft 26.3, Vulkan.
- Minecraft window 1920x1080 on the secondary XF250Q 240 Hz monitor unless a row says 4K. The 4K rows
  use a 3840x2160 borderless window on the same display, so the swapchain and render targets were
  3840x2160 even though the panel is 1920x1080.
- Iris 1.12.0-snapshot+mc26.3-local, BSL v10.1.8, Sodium 0.9.2, render distance 12, simulation
  distance 12. Reflex Off for the upscaler tables; Reflex On only where frame generation requires it.
- Scene: the smoke world platform at camera `-24.5, 131, 10.5`, yaw 0, pitch 0, facing the leaf wall.
  The player stood on the platform for every measurement and capture.
- FPS is the mean of the harness frame intervals (`frame_mark`, then `frame_stats_from` after about
  3 seconds). It counts rendered application frames. Frame generation rows report submitted presents
  separately.

## Upscaling, shaders on, 1920x1080

| Mode | FPS | Change vs native |
| --- | ---: | ---: |
| Native | 388.9 | |
| DLSS CNN DLAA | 333.4 | -14% |
| DLSS CNN Quality | 447.2 | +15% |
| DLSS CNN Balanced | 462.4 | +19% |
| DLSS CNN Performance | 508.0 | +31% |
| DLSS CNN Ultra Performance | 538.9 | +39% |
| FSR Quality | 438.0 | +13% |
| FSR Balanced | 447.1 | +15% |
| FSR Performance | 495.5 | +27% |
| FSR Ultra Performance | 553.6 | +42% |

This is the expected GPU-bound shape: Quality/Balanced/Performance all beat native, Ultra
Performance gains more, and DLAA pays for reconstruction at full input resolution.

## Upscaling, shaders on, 3840x2160

| Mode | FPS | Change vs native |
| --- | ---: | ---: |
| Native | 175.4 | |
| DLSS CNN Quality | 217.8 | +24% |
| DLSS CNN Balanced | 240.4 | +37% |
| DLSS CNN Performance | 273.6 | +56% |
| FSR Quality | 194.6 | +11% |
| FSR Balanced | 219.7 | +25% |
| FSR Performance | 252.8 | +44% |

The 4K scene is the closest match to a production game benchmark: the native renderer is the limit
and upscaling turns that limit into real frame-rate gains.

## Upscaling, no shader pack, 1920x1080

| Mode | FPS | Change vs native |
| --- | ---: | ---: |
| Native | 992.2 | |
| DLSS CNN DLAA | 772.4 | -22% |
| DLSS CNN Quality | 853.2 | -14% |
| DLSS CNN Balanced | 871.3 | -12% |
| DLSS CNN Performance | 869.1 | -12% |
| DLSS CNN Ultra Performance | 859.4 | -13% |
| FSR Quality | 801.8 | -19% |
| FSR Balanced | 822.7 | -17% |
| FSR Performance | 848.1 | -15% |
| FSR Ultra Performance | 883.8 | -11% |

No-shader vanilla in this scene is CPU bound. A native 960x540 window measured 634 us of world
timestamp GPU time against 798 us at 1920x1080, so resolution barely changes the frame time. At the
same time DLSS CNN Performance costs about 270 to 300 us of evaluation and FSR costs a similar
amount. Rendering fewer pixels therefore cannot pay for reconstruction here. This is a property of
the workload, not an upscaler bug: a CPU-bound frame has no GPU work to remove. The same modes show
clear gains as soon as BSL or 4K makes the GPU the limit. The README recommends Off for this case.

## Frame generation, shaders on, 1920x1080

Base mode: DLSS CNN Performance, Reflex On, 240 Hz display with a 223 FPS present budget. Multiplier
forced to keep the requested fixed count above the automatic budget.

| Mode | Real FPS | Submitted presents per second | Evaluations | Generated presents | Generated per real |
| --- | ---: | ---: | ---: | ---: | ---: |
| FG off | 482.6 | 490 | | | |
| DLSS FG 2x | 111.5 | 223 | 358 | 358 | 1 |
| DLSS MFG 3x | 74.3 | 223 | 239 | 478 | 2 |
| DLSS MFG 6x | 37.2 | 223 | 120 | 600 | 5 |

The generated count is exact for every mode. Output is paced to the 223 FPS presentation budget while
the base rate falls as more generated frames are requested. Submitted presents are not a scanout
measurement.

## Walking and image checks

- Camera history records the projection Minecraft actually draws, after view bob, damage tilt,
  nausea and portal effects are multiplied in. That is the same matrix the jitter hook receives, so
  the previous and current matrices used for reprojection include the bob. The hand has its own
  modelview and projection history.
- Walking captures were taken at 1920x1080 with the secondary monitor at about 60 to 70 GDI frames
  per second: DLSS CNN, DLSS Transformer, FSR without shaders, FSR with BSL, DLSS with BSL and FG 2x
  with BSL. Consecutive captures around the wall, ground, hand and distant foliage showed no trails,
  doubled edges or bob-cycle ghosting. `build/walk-*` holds the JPEG sequences from this run.
- The F3 axis crosshair, HUD and menus are drawn after reconstruction; frame generation stops while a
  GUI screen is open. An open creative inventory advanced evaluations only during the transition
  frame, then stopped for the rest of the observation window and resumed on close.
- World quit and rejoin with FG 2x active completed without a renderer error and FG resumed after
  terrain loaded. Teleport and resize history resets remain covered by the assertion checks and the
  earlier validation reports.

## Changes in this pass

- Native-resolution frame generation no longer swaps Minecraft's window-sized targets or copies
  colour and depth every frame. It renders into the main target, builds motion vectors in a separate
  render-size target and evaluates FG directly on the native image.
- Frame generation is skipped while any GUI screen is open, with the menu reason recorded in the
  generation ledger. Previously only the menu blur skipped it.
- Deferred display-resolution particle and weather layers are only registered when they contain
  actual geometry, so an empty state no longer forces the display-depth resolve every frame.
- Removed the unused FSR reactive-mask output image and five unused profiler span names.
- The earlier part of the session removed the automatic native-versus-DLSS probe loop, the default
  full-resolution DLSS sharpen pass and shader, the FSR per-frame opaque copy and reactive mask, and
  always-on profiling. FSR sharpness now defaults to 0.

## Verification

- `gradlew.bat build --no-daemon --console=plain` passed all assertion checks and `test`.
- The client was launched through the harness, joined the smoke world, and every mode above evaluated
  with an empty `last_error`.
- Menus, FG counts, native FG, shader and no-shader paths, resize and world rejoin were exercised in
  this build. Build artifacts are in `build/final-*.tsv`; captures are under `build/walk-*`.

## Remaining issues

- No-shader vanilla at 1080p and 1440p remains CPU bound on this machine, so upscaling is a small
  loss there. More render distance, a heavier world or 4K can move the limit to the GPU, where the
  gains above apply. Do not expect a CPU-bound scene to improve from DLSS or FSR.
- DLAA costs frame rate at 1080p because it evaluates the network at full input resolution.
- Shader pack water, translucency and pack-generated deformation still depend on the pack's own
  masks or the reviewed BSL/Complementary source paths. General packs are not certified.
- Submitted presents and frame intervals are not physical scanout. A FrameView or PresentMon capture
  is still needed for vendor cadence acceptance.
- AMD and Intel GPUs were not tested. The FSR route is implemented and checked for ABI and lifecycle,
  not certified on that hardware.

## Handoff

- Build state: `gradlew.bat build --no-daemon --console=plain` passes all assertion checks and tests.
  Changes are uncommitted in the working tree by request. Do not revert the earlier deletions of
  `SharpenPass`, `UpscalerPerformanceGuard` or the DLSS sharpen shader.
- To repeat: quit the game first, build with `--no-daemon`, launch the harness on the secondary
  monitor at 1920x1080, join `Reflexium Vitrail Smoke`, camera to `-24.5, 131, 10.5`, and use
  `/reflexium?key=...&value=...` with `POST /reflexium {"frame_mark":1}` then
  `{"frame_stats_from":N}`. Turn shaders on or off with `key=shaders`, and wait about 6 seconds for
  Iris to reload.
- Next useful work: a FrameView or PresentMon scanout capture for the FG rows, AMD/Intel FSR testing,
  and a heavier vanilla scene (higher render distance or 4K) to show where the no-shader workload
  crosses from CPU bound to GPU bound.
