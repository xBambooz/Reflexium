# Range pans, moving mobs, banding and objective 1, 2026-10-24

This round measured the range pan and mob scenes for objective 5 and added the missing shaderless
resolution matrix for objective 1. The release objectives are in [OBJECTIVES.md](../OBJECTIVES.md).
The report states pass or fail per objective; nothing is redefined to make a miss pass.

## Environment

- Windows 11, RTX 5070 12 GB, NVIDIA 617.42, Java 25, Minecraft 26.3, Vulkan backend.
- Harness window 1920x1080 on the secondary XF250Q 240 Hz display. Output was also measured at
  2560x1440 and 3840x2160 by resizing the swapchain; the 4K window spills off the 1080p panel, so the
  swapchain extent is 3840x2160 but physical scanout is not 4K. VSync off. Max Framerate 260, which
  Minecraft 26.3 treats as Unlimited (title screen measured 2285 to 3560 FPS).
- Iris `1.12.0-snapshot+mc26.3-local`, BSL v10.1.8, Sodium 0.9.2, Distant Horizons 3.3.5-dev.
  Render distance 31, simulation distance 12.
- FPS is the render-thread frame-stamp mean from `frame_mark` and `frame_stats_from`. FG rows report
  real frames and submitted presents separately. These are application counters, not scanout.
- The test client was launched with `preferredGraphicsBackend:"vulkan"`. A launch with `"default"` came
  up on OpenGL, where Reflexium reports `Upscaling: needs the Vulkan renderer` and every DLSS row is
  silently native. That trap is recorded in the brain harness notes.

## Objective status

| Objective | Status | Evidence |
| --- | --- | --- |
| 1. DLSS beats native, including without shaders | **Fail** | Shaders on at 4K pass with +26 to +56 percent. Shaderless fails at every resolution: native 410 to 783 FPS and DLSS Quality/Balanced/Performance lose 2 to 14 percent because the scene is geometry-bound and the DLSS evaluate cost is not offset by fewer rendered pixels. BSL forest 1440p Quality also loses 11.5 percent. |
| 2. Proper implementation, no band-aids | **Not changed this round** | No heuristics, probes or dead options were added. No runtime code changed. The raw NGX path remains the shipping path until the Streamline migration round. |
| 3. No refresh-rate lock | **Pass** | Fixed 3x and 4x submitted 528 and 621 presents per second at 173 and 155 real FPS during the slow pan, far above the 240 Hz panel. Round 2 removed the fixed-multiplier refresh budget; this round confirms it live. |
| 4. Reflex in CPU-bound play | **Not measured this round** | Previous round evidence stands. Status during the pan run showed issued sleeps equal to completed sleeps and no timeouts, but no latency comparison was rerun. |
| 5. Upscaled image close to native | **Partial** | Slow pans and moving pen/flyer mobs at DLSS Performance and FG 2x show no ghost trails, bar doubling or limb/wing doubling. FG generated frames lose the pack dither and make sky/cloud banding more visible, and the round-2 BSL foliage softening remains. |

## 1. Objective 1: native versus DLSS at three resolutions

Shaders off, Reflex off, frame generation off. BSL off. Three scenes: the range pad, the smoke
platform, and a ground forest view at `200.5, 67, 150`, yaw 90, pitch 0. Render distance 31.

| Scene | Resolution | Native FPS | Q | Balanced | Performance |
| --- | --- | ---: | ---: | ---: | ---: |
| Range | 1920x1080 | 684.2 | 614.5 (-10.2%) | 623.7 (-8.8%) | 633.1 (-7.5%) |
| Platform | 1920x1080 | 749.5 | 658.1 (-12.2%) | 668.2 (-10.8%) | 674.4 (-10.0%) |
| Forest | 1920x1080 | 782.8 | 688.2 (-12.1%) | 699.7 (-10.6%) | 708.0 (-9.6%) |
| Range | 2560x1440 | 586.4 | 514.0 (-12.3%) | 533.4 (-9.0%) | 539.4 (-8.0%) |
| Platform | 2560x1440 | 633.4 | 542.6 (-14.3%) | 563.4 (-11.1%) | 574.2 (-9.3%) |
| Forest | 2560x1440 | 646.5 | 567.8 (-12.2%) | 593.1 (-8.3%) | 603.9 (-6.6%) |
| Range | 3840x2160 | 410.9 | 367.5 (-10.6%) | 381.9 (-7.1%) | 393.3 (-4.3%) |
| Platform | 3840x2160 | 443.8 | 384.3 (-13.4%) | 397.8 (-10.4%) | 410.8 (-7.4%) |
| Forest | 3840x2160 | 432.5 | 390.7 (-9.7%) | 407.5 (-5.8%) | 422.9 (-2.2%) |

BSL on, same scenes and resolutions. The forest at 1440p Quality was rerun twice: native 224.7 and
222.3, Quality 196.8 and 197.7, Balanced 217.7 and 216.9, Performance 238.6 and 237.4. The 11.5
percent Quality loss is repeatable, not noise.

| Scene | Resolution | Native FPS | Q | Balanced | Performance |
| --- | --- | ---: | ---: | ---: | ---: |
| Range | 1920x1080 | 251.5 | 249.1 (-1.0%) | 254.5 (+1.2%) | 276.1 (+9.8%) |
| Platform | 1920x1080 | 280.2 | 275.3 (-1.7%) | 274.5 (-2.0%) | 296.4 (+5.8%) |
| Forest | 1920x1080 | 219.1 | 245.5 (+12.0%) | 268.3 (+22.5%) | 284.1 (+29.7%) |
| Range | 2560x1440 | 203.9 | 224.9 (+10.3%) | 218.7 (+7.3%) | 242.2 (+18.8%) |
| Platform | 2560x1440 | 224.9 | 248.1 (+10.3%) | 241.0 (+7.2%) | 264.1 (+17.4%) |
| Forest | 2560x1440 | 223.5 | 197.8 (-11.5%) | 217.2 (-2.8%) | 235.9 (+5.5%) |
| Range | 3840x2160 | 123.6 | 164.1 (+32.8%) | 177.4 (+43.5%) | 192.8 (+56.0%) |
| Platform | 3840x2160 | 136.8 | 175.1 (+28.0%) | 192.5 (+40.7%) | 207.6 (+51.8%) |
| Forest | 3840x2160 | 136.6 | 176.9 (+29.5%) | 168.4 (+23.3%) | 172.2 (+26.1%) |

Interpretation:

- With BSL at 4K, all three modes give a clear gain in all scenes. That is the intended GPU-bound case.
- With BSL at 1440p, Quality fails only in the forest. The forest is not fill-bound enough for the
  Quality network cost, while Balanced and Performance save enough pixels to pay for evaluation.
- With BSL at 1080p, the scene runs at 220 to 280 FPS and Quality/Balanced are within about 2 percent
  of native on the range and platform. They are ties, not clear gains.
- Without shaders, native runs at 410 to 783 FPS at every resolution, including 4K. The world is
  geometry and submission bound, not fill bound, so rendering 960x540 instead of 1920x1080 or 3840x2160
  saves little GPU time. The DLSS evaluate then adds 0.27 to 0.97 ms. This is why every shaderless row
  loses FPS.

### Fixed-cost profile

`POST /reflexium {"profile":"reset"}` then `off`, range pad, profiler values in microseconds. Parent
`world_including_upscaling` includes `evaluate`; the other spans are its children or later passes.

| Scene | Span | 1080p native | 1080p DLSS P | 4K native | 4K DLSS P |
| --- | --- | ---: | ---: | ---: | ---: |
| Range | World CPU / GPU | 428.8 / 1364.7 | 533.2 / 1461.9 | 507.2 / 2283.4 | 636.4 / 2290.2 |
| Range | Evaluate CPU / GPU | 0 / 0 | 48.1 / 289.0 | 0 / 0 | 52.4 / 966.0 |
| Range | Object motion CPU / GPU | 0 / 0 | 36.1 / 4.7 | 0 / 0 | 80.7 / 4.9 |
| Range | Camera motion CPU / GPU | 0 / 0 | 4.2 / 21.4 | 0 / 0 | 4.6 / 51.4 |
| Range | Display depth CPU / GPU | 0 / 0 | 4.3 / 27.9 | 0 / 0 | 4.9 / 105.5 |
| Range | Final blit CPU / GPU | 3.2 / 10.6 | 3.2 / 10.7 | 4.6 / 53.2 | 3.9 / 41.2 |
| Range | Barriers CPU / GPU | 0 / 0 | 1.0 / 7.0 | 0 / 0 | 1.1 / 3.2 |

What was checked and what was not cut:

- There is no extra colour copy, readback or sync in the steady path. `depth_copy` is zero because
  DLSS uses the Iris pre-translucent depth directly (`primary_depth_source=iris_target_1`).
- `display_depth` runs only when deferred particles or weather need display-resolution depth. The
  range has torch particles, so the resolve is used. Disabling post-upscale particles removes it but
  changes a user-visible feature; it is not dead work.
- `object_motion` is required for limbs and wings on moving entities. It was 36 to 81 us CPU and is
  the second largest added cost after evaluation.
- `evaluate` is the dominant added GPU cost (289 us at 1080p, 966 us at 4K for Performance) and scales
  with output size. It cannot be removed without removing DLSS.
- The final blit and barriers exist in both paths and are noise.

No runtime code change was made for objective 1. The remaining loss is structural for geometry-bound
scenes: DLSS reduces fill work, and this scene does not have enough fill work to pay for the network.
Cutting the remaining passes would break deferred particles or moving-object quality.

## 2. Pans and moving mobs

BSL on, 1920x1080, Reflex off for SR rows and Reflex On for FG rows. All rows started from
`200.5, 151, 0.5`, yaw 0, pitch 3. The slow pan was 90 degrees over 60 ticks; the flick was 120
degrees over 8 ticks; walking added `key.forward` to a 45 degree turn. Captures were 12 frames at
80 ms.

Slow pan, real FPS and 1 percent low:

| Mode | Real FPS | 1% low | Submitted presents |
| --- | ---: | ---: | ---: |
| Native | 244.3 | 99.4 | 244 |
| DLAA | 216.4 | 114.8 | 216 |
| DLSS Quality | 261.1 | 154.0 | 261 |
| DLSS Balanced | 263.9 | 167.2 | 264 |
| DLSS Performance | 289.9 | 174.6 | 290 |
| FSR Performance | 285.5 | 159.1 | 286 |
| DLSS FG 2x | 205.3 | 133.5 | 416 |
| DLSS MFG 3x | 173.4 | 101.5 | 528 |
| DLSS MFG 4x | 155.0 | 111.1 | 621 |

The flick and walk pans gave the same ordering: DLSS Performance 294.2 and 288.7 real FPS, FG 2x
209.5 and 204.9 real FPS with 420 and 422 submitted, MFG 3x 176.3 and 174.7 with 526 submitted, MFG
4x 157.1 and 155.9 with 629 submitted.

Motion sampling limit: `capture_sequence` did not keep an 8-tick turn in motion for the 80 ms frames.
The flick showed yaw 0 in frame 0 and yaw 120 in frame 1, so only the first frame sampled the turn.
The slow pan and walk sampled 8 and 5 moving frames. The flick images are therefore a static-pose
stress check at 120 degrees of history change, not a sampled 8-tick trail.

Visual result:

- Slow pan at DLSS Performance and FG 2x: the far gate bars, chain, tower grid and near fence stay
  single-edged. No ghost trail behind the gates or torches, no bar or chain breakup, no checkerboard
  shimmer. `pan-slow-dlss-p.png` and `pan-slow-fg2.png` are the same frame index, 3 degrees apart.
- Moving mobs: the pen pig and the cage flyers stay sharp at DLSS Performance and FG 2x. No doubled
  legs, heads or wings were found in the static or pan sequences. `mobs-pen-fg2.png` shows a moving
  pig with separate legs; `mobs-cage-sheet.png` shows the flyers and glass grid at native, DLSS P and
  FG2.
- Limitation: captures of different modes are not simultaneous, and the animals move between modes,
  so the mob check is a per-frame quality check rather than a matched A/B. Matched object-vector
  verification remains the `objectMotionCheck` and the round-2 captures.

The only new image-quality defect found in this part is FG banding, below.

## 3. Sky and cloud banding

`run/screenshots/range-mobs.png`, the reported image, is BSL, DLSS Performance, FG 2x. A luma-stretched
crop of its right sky shows horizontal bands. The same view was then compared at native, DLSS without
FG, and FG 2x with tick freeze, and an existing same-frame FG capture (`fg_20261023_224058_010`) was
used for a real-versus-generated pair at `200.5, 151, 0.5`, yaw 9, pitch 3.

Same-frame real versus generated sky, 1920x1080:

| Region | PSNR | Flat pixel fraction, real | Flat pixel fraction, generated | Mean flat run, real / generated |
| --- | ---: | ---: | ---: | ---: |
| Left sky | 50.6 dB | 0.396 | 0.567 | 1.60 / 2.36 |
| Gap above the lane | 50.4 dB | 0.403 | 0.524 | 1.69 / 2.10 |
| Right sky with clouds | 29.6 dB | 0.446 | 0.528 | 2.08 / 2.83 |

Static-pose alternation, native versus DLSS without FG and FG 2x, 350x220 sky patch:

- Native versus DLSS Performance: 50.1 to 51.0 dB across four cycles, 41 unique luma levels in both.
- Native versus FG 2x: 40.5 to 41.0 dB, 37 to 38 unique levels in the generated image.
- The generated frame is smoother and has fewer distinct levels. That is the banding mechanism.

Cause: the generated frame is written into an `RGBA8_UNORM` target and NGX is called with
`VK_FORMAT_R8G8B8A8_UNORM`. The format matches the target, so this is not a format mismatch. NGX's
temporal reconstruction averages the pack's 8-bit dither and then writes 8-bit values, so smooth sky
gradients lose the dither that hides quantization. DLSS without FG does not add banding. The right sky
also differs by cloud motion because vanilla clouds have no motion vectors
(`vanilla_cloud_motion=false`), which is a separate ghosting issue.

Not fixed this round. The correct fix is a higher-precision FG output (16F) with a dithered final
present, and that belongs with the Streamline/HDR precision work in the next round. Adding a dither
pass after 8-bit FG output would add a full-screen pass per generated frame and is a band-aid, so it
was not done. The evidence crops are `banding-real-16x9.png` and `banding-fg2-16x9.png`.

## 4. Build and evidence

- `gradlew.bat build --no-daemon --console=plain`: BUILD SUCCESSFUL in 16 s, including
  `objectMotionCheck`, `reflexTimingCheck` and `upscalingContractCheck`. No source change was made
  after the round-2 tree; the only repository edit outside this report is the corrected `shaders`
  debug-key description in `docs/DEBUGGING.md`.
- Raw captures, CSVs and cameras: `build/range-pans/` (not committed). Committed small evidence in
  this folder:
  - `obj1-noshader.csv`, `obj1-bsl.csv`: the two objective 1 matrices.
  - `mobs.csv`: the mob FPS rows.
  - `pan-slow-dlss-p.png`, `pan-slow-fg2.png`: same-index slow-pan crops.
  - `mobs-pen-fg2.png`, `mobs-cage-sheet.png`: moving mob crops and the flyer sheet.
  - `banding-real-16x9.png`, `banding-fg2-16x9.png`: stretched same-frame sky pair.

## 5. Remaining work

- Objective 1: make the shaderless case at least neutral. That needs less geometry/submit work or a
  cheaper evaluation path, not another copy removal. Streamline alone will not make a geometry-bound
  scene faster.
- Objective 1: DLSS Quality at 1440p in the forest. The network cost is not paid back until the scene
  is fill-bound. Recheck with the Transformer model and with the Streamline path.
- Objective 5: 16F FG output plus dithered present to stop sky banding.
- Objective 5: cloud motion vectors for FG, so cloud edges do not move against the generated frame.
- Objective 5: BSL residual leaf softening under view bob, and a matched numeric foliage vector score.
