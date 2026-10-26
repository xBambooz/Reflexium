# Shader pack support, uncapped frame generation and the walking smear, 2026-10-23

Follow-up to [the production pass](production-pass-2026-10-23.md). Three changes were requested:
support arbitrary Iris packs without per-pack assumptions, stop fixed frame-generation multipliers from
throttling to the display refresh rate, and fix the walking view-bob smear.

## Environment

- Windows 11, RTX 5070, NVIDIA 617.42, Java 25, Minecraft 26.3, Vulkan.
- Harness window 1920x1080 on the secondary XF250Q 240 Hz display. VSync off. Game muted.
- Iris `1.12.0-snapshot+mc26.3-local`, Sodium 0.9.2, Distant Horizons 3.3.5-dev. Render distance 12,
  simulation distance 12.
- Scene: the smoke platform at `-24.5, 131, 10.5`, yaw 0, pitch 0, standing on the platform. The pack
  loaded for each row is shown in the pack column.
- FPS is the render-thread frame-stamp mean from `frame_mark` and `frame_stats_from` after a settle.
  Native frames are measured with `upscaler=off` and `frame_generation=off`; generation rows use
  submitted presents from status. These are application frame rates, not physical scanout.

## 1. Shader packs

Installed families tested: BSL, Complementary Reimagined, Complementary Unbound (same family and
source policy as Reimagined), Mellow, Solas and Bliss. Time allowed live runs of four distinct
families (BSL, Complementary Reimagined, Mellow, Solas); Bliss and Unbound were not measured live.

Generic changes made for arbitrary Iris packs:

- The scene depth is taken from the generic Iris pre-translucent target (index 1) with fallback to
  index 2 and index 0 if a loader or pack does not allocate it. No pack name is involved.
- Pack temporal options are switched off by option name, not by pack identity: boolean `TAA`,
  `FXAA`, `SMAA` variants and value options `TAA_DEFINE=0`, `TAA_MODE=0`, `TAA_JITTER=0`,
  `FXAA_DEFINE=-1` and sharpening `0`. Mellow logged `pack options off: IMAGE_SHARPENING, SMAA,
  TAA_MODE=0`; Solas logged `FXAA, TAA`. BSL and Complementary Reimagined retain their exact-source
  reviewed edits as extras.
- Camera, object, hand, jitter and depth hooks are loader hooks. No reviewed pack source is required
  for the upscaler to run.

| Family | Pack | Loads | Native FPS | DLSS Q | DLSS P | FSR P | FG 2x submitted / real | Walking (bob on) | Hand |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |
| BSL | BSL v10.1.8 | yes | 239.3 | 298.0 (+25%) | 348.1 (+45%) | 343.5 (+44%) | 455 / 227 | Slight leaf/grass softening, no wall doubling | Renders, sharp |
| Complementary | ComplementaryReimagined r5.9.3 | yes | 340.7 | 422.6 (+24%) | 490.6 (+44%) | 492.9 (+45%) | 572 / 286 | Clean after the hand-mask fix | Renders, sharp |
| Mellow | Mellow v3.4.1a | yes | 825.6 | 750.7 (-9%) | 803.2 (-3%) | 799.8 (-3%) | 756 / 378 | Waving foliage ghosts; static scene clean | Renders, sharp |
| Solas | rxtest-solas-shader | yes | 284.8 | 371.8 (+31%) | 440.6 (+55%) | 416.4 (+46%) | 536 / 268 | Clean, mild plant wave softening | Renders, sharp |

Notes:

- Absolute native FPS varied between repeated measurements of the same BSL scene (239 to 390). The
  percentage gains above come from same-window pairs. Do not compare absolute FPS across sessions.
- Mellow is a light pack. The scene is not GPU bound enough for reconstruction to pay, so DLSS and FSR
  are a small loss. The same pack on a heavier scene or higher output resolution can still gain; this
  run does not establish a gain for it.
- Mellow's animated foliage has no exact-source deformation vectors. Camera-only vectors leave a
  visible double edge on waving leaves while the camera moves. That is a material-motion limitation,
  not a depth or jitter failure.
- Complementary and Solas were clean in the walking A/B after the hand-mask fix. BSL retained a
  slight softening on the closest leaves; its exact-source foliage path is the remaining suspect.
- Bliss and Complementary Unbound were confirmed installed but not run. They use the same generic
  option path and Iris hooks.

## 2. Frame generation is not capped by the refresh rate

The old application policy applied `refreshHeadroom` (223 FPS on the 240 Hz panel) to total presents
for every FG mode, including a fixed multiplier. The NVIDIA Vulkan interval limits presented frames,
so 2x at a 482 FPS base cut real rendering to 111 and displayed 223. Fixed multipliers now bypass that
budget entirely. Only Auto targets the refresh rate; Auto still bypasses generation when the measured
real rate cannot fit two frames under it.

BSL, DLSS Performance, Reflex On, 1920x1080:

| Mode | Real FPS (frame stamps) | Submitted presents | Real presents | Output cap | Effective multiplier |
| --- | ---: | ---: | ---: | ---: | ---: |
| Off | 462.8 | 475 | 475 | 2147483647 | 1 |
| 2x fixed | 288.6 | 578 | 289 | 2147483647 | 2 |
| 3x fixed | 233.0 | 699 | 233 | 2147483647 | 3 |
| 4x fixed | 184.8 | 739 | 185 | 2147483647 | 4 |
| Auto | 461.9 | 466 | 466 | 223 (stored, bypassed) | 1 |

Output follows real x N exactly and is no longer held at 223. Real FPS falls as N rises because the
present path and FG evaluation become the limit, which is expected. Auto measured a 462 FPS real rate,
could not fit 2x under 223, stayed rendered-only and was uncapped. `fg_refresh_cap` is MAX_VALUE for
fixed modes and the bypass check is visible in `fg_refresh_bypass`.

## 3. Walking smear

### Reproduction

Two 6-frame walking sequences from the same start pose with identical timing, one with
`bob_view=1` and one with `bob_view=0`, at DLSS Performance and BSL. Frame 1 is the same camera z in
both sequences. `build/bob-pass/ab-bobon/frame_001.png` and `ab-boboff/frame_001.png` are the raw
captures; the crops are `cmp-ab-bobon.png` and `cmp-ab-boboff.png`. The bob-on wall has a clear
vertical double/streak under every leaf and on the flowers; the bob-off wall is sharp.

The same test reproduced on Complementary Reimagined (`pack-comp-bobon` versus `pack-comp-boboff`), so
this is not a BSL-only source path. Third person (no first-person hand, F1-equivalent camera) with bob
on was clean (`cmp-comp-third.png`). Disabling only the hand mask fixed the wall
(`cmp-comp-nomask.png`), which localized the cause to hand coverage.

The world projection did not contain view bob during these frames. Status
`drawn_projection_has_view_effects` was false, the projection translation column was zero, and
`capture-check` matched the scene depth against that unjittered projection. The camera matrices were
therefore not the source, and reconstructing bob into them made the image worse (reverted).

### Root cause

The Iris hand coverage mask is a depth difference over the hand draw. It is valid hand coverage, but
it was also passed to the world motion pass. Under view bob the hand's own projection changes every
frame; the difference mask then classifies some static world pixels as hand. The base motion shader
writes zero motion and the hand depth for those pixels, so DLSS keeps stale history on static
terrain. With bobbing off the mask difference stays confined to the hand, which is why the symptom
appeared only while walking and only with a pack.

### Fix

- World motion and object motion now use a cleared coverage buffer, so no static pixel can be
  classified as hand.
- The hand velocity pass keeps the real pack mask, so visible hand quads still receive their own
  animation vectors and depth match.
- `pack_hand_mask=0` is retained as an A/B: it also removes the mask from the hand pass.
- Two diagnostic status fields were added so this cannot silently regress: `camera_bob`,
  `camera_walk_distance`, `bob_view_state`, `drawn_projection_has_view_effects`, `scene_depth_index`
  and `primary_depth_source` (for example `iris_target_1`).

After the fix, Complementary Reimagined is clean under bob (`comp-fixed-bobon/frame_002.png`). Solas
is clean. BSL has only a slight leaf softening. Mellow still shows foliage double edges because its
animated plant vertices have camera-only vectors; that is a missing deformation source, not the hand
mask. A generic solution for arbitrary pack vertex animation still does not exist; exact-source
vectors remain the only way to remove foliage ghosting for packs like Mellow.

### Evidence paths

All under `build/bob-pass/` in the working tree:

- `ab-bobon/`, `ab-boboff/`, `cmp-ab-bobon.png`, `cmp-ab-boboff.png` (BSL before).
- `pack-comp-bobon/`, `pack-comp-boboff/`, `cmp-pack-comp-bobon.png`, `cmp-pack-comp-boboff.png`
  (Complementary before).
- `cmp-comp-third.png` (third person, clean) and `cmp-comp-nomask.png` (mask disabled, clean).
- `comp-fixed-bobon/`, `cmp-comp-fixed.png`, `bsl-fixed-bobon/`, `cmp-bsl-fixed.png` (after fix).
- `pack-mellow-bobon/`, `pack-mellow-boboff/`, `cmp-mellow.png`, `cmp-mellow-off.png` (Mellow).
- `pack-solas-bobon/`, `cmp-solas.png` (Solas).
- `pack-comp-hand.png`, `pack-mellow-hand.png`, `pack-solas-hand.png` (hand checks).

## Range scene spot check

The 512-block strip was rebuilt by the agent harness after the first run failed at
`minecraft:chain`; 26.3 renamed the block to `minecraft:iron_chain`. The corrected strip adds two mob
pens and a glass cage over the lane (`function reflexium:spawn_mobs`, all tagged `rx_range`). Camera
`200.5, 151, 0.5`, yaw 0, pitch 3, render and simulation distance 32, BSL, 2560x1440. The static crop
is `640 100 640 450` in full-frame coordinates.

| Mode | Detail correlation vs native | PSNR | Laplacian |
| --- | ---: | ---: | ---: |
| DLSS CNN Quality | 0.7898 | 32.88 dB | 11.514 (native 11.136) |
| DLSS CNN Performance | 0.7177 | 31.70 dB | 10.328 |
| FSR Performance | 0.7345 | 30.08 dB | 8.925 |

These numbers are from the orchestrator-built strip with the padded camera and are the ones to quote.
The earlier pre-rebuild pass (Quality 0.756 / 33.56 dB, Performance 0.479 / 28.24 dB, FSR 0.592 /
29.49 dB) used a different build and is not comparable. Images and JSON are in `build/range-pass2/`;
the earlier pass is kept in `build/range-pass/`.

### Camera pans and flicks

Slow pan is 90 degrees over 60 ticks (12 frames at 90 ms); fast flick is 120 degrees over 8 ticks (10
frames at 60 ms). Every mode starts from yaw 0, pitch 3 at the range pad. The 1 percent lows include
the capture stall, so read them with the p99 frame time.

| Mode | Slow avg fps | Slow p99 | Slow 1% low | Flick avg fps | Flick p99 | Flick 1% low |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Native | 234.0 | 7.00 ms | 79.1 | 226.2 | 6.32 ms | 90.6 |
| DLSS DLAA | 207.8 | 6.87 ms | 77.6 | not run | | |
| DLSS CNN Quality | 247.6 | 5.69 ms | 99.6 | not run | | |
| DLSS CNN Balanced | 247.6 | 6.26 ms | 98.9 | not run | | |
| DLSS CNN Performance | 269.4 | 5.82 ms | 140.2 | 257.4 | 6.00 ms | 97.1 |
| FSR Performance | 269.3 | 5.59 ms | 142.1 | 270.5 | 5.57 ms | 94.0 |

Slow pans hold the gate bars, chains and the checkerboard together. DLSS Performance and FSR
Performance soften distant bars; no doubling was seen in the real frames. The fast flick keeps the
same ordering, with the far checkerboard crawling slightly more at Performance.

Side-by-side crops: `cmp-flick.png` (native, DLSS P, FSR P at the same flick frame),
`cmp-fg-pan.png` (FG 2x real versus generated during the slow pan), `cmp-sky.png` (native, DLSS P,
FG generated sky), and `cmp-mobs.png` (native versus DLSS P at the pens).

### Frame generation during pans

`fg2-pan` and `fg2-flick` captured real and generated targets at one authenticated boundary. In
`cmp-fg-pan.png` the generated frame doubles the tower top edge, ghosts the gate frame and smears the
cow at the lower right while the real frame is sharp. The flick generated frame shows the same class
of edge warp. This is rotation interpolation plus disocclusion with no synthetic object or pack
vectors; it is not a regression from the multiplier fix. Mob legs and the cow silhouette trail in
generated frames when the camera turns. Static generated frames are clean.

### Mobs and sky banding

The pens and cage were included in static native and DLSS Performance frames and in the FG pan. Static
native and DLSS Performance keep the horse, cow, llama, chicken and the caged bee, parrot and allay
legible with no trailing. In the FG pan, only the generated frame doubles the cow and the mob row;
the real frame stays clean. The earlier real-mode slow and flick sequences were captured before the
mobs were spawned, so they cover geometry only.

The reported banding in `run/screenshots/range-mobs.png` is reproduced by native BSL at the same
class of cloud edge: native and DLSS Performance both show stepped cloud and sky contours, and the FG
generated frame adds no new banding. BSL without its TAA dither exposes 8-bit tonemap contours, so the
color target is not the source. The exact time, cloud pose and downscale of the original shot were not
reproduced, so the severity comparison is approximate.

Not run in this pass: vertical pan, pan while walking, FG 3x and 4x pan captures, the full nine-mode
pan matrix, and Bliss or Complementary Unbound.

## Build and checks

- `gradlew.bat build --no-daemon --console=plain`: BUILD SUCCESSFUL, including the assertion checks
  and the frame delivery test. `FrameGenerationRefreshPolicy.targetsRefresh` is covered by
  `FrameDeliveryCheck`: Auto targets refresh, fixed 2x/4x never do.
- Every pack row loaded through Iris and evaluated DLSS/FSR without a `last_error`.
- FG rows used `fg_force_multiplier=1` with a fixed `fg_multiplier`; Auto used `fg_multiplier=0`.

## Remaining work

- Bliss and Complementary Unbound live runs.
- Exact or approximate deformation vectors for arbitrary pack foliage, so Mellow-class packs do not
  double on waving plants.
- BSL's residual leaf softening under bob, and a matched numeric foliage-vector score.
- Vertical pan, pan while walking, FG 3x and 4x pan captures, and the full nine-mode pan matrix.
- A numeric ghost and doubling score for generated frames during rotation, not only visual crops.
- A FrameView or PresentMon capture for physical cadence. Counters and frame stamps are not scanout.
- AMD/Intel FSR testing.
- Streamline migration plan: [STREAMLINE_MIGRATION_SPEC.md](../specs/STREAMLINE_MIGRATION_SPEC.md).
