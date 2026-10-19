# DLSS image quality validation, 2026-10-19

Base: `92c928c`. Branch: `t3/improve-dlss-image-quality`.

Verified improvement: the repeating Bayer grain in BSL clouds is visibly removed. This is a targeted fix, not acceptance of the requested practically lossless quality bar for every material in motion. BSL waving foliage, particles and blended layers still need better temporal inputs.

## Cause and change

BSL v10.1.8 uses `TAA` both to select its own temporal reconstruction and to animate ray-march sample positions. Reflexium already removed BSL TAA, FXAA and the negative automatic sharpening filter, but that also froze the cloud, AO, light-shaft, reflection and LOD-shadow dither. The resulting stationary screen-space Bayer pattern became reconstruction detail instead of converging over time.

`IrisCompat` now preserves only the dither sampling blocks in five reviewed source files. Each file must match its normalized SHA-256. It does not restore BSL geometry jitter, its temporal resolve, FXAA or pre-upscale sharpening. Unknown or edited sources are untouched. The existing source checks now exercise those files and modified-source rejection.

The debug `shaders` setting now routes to Iris when installed. It uses Iris's set/save/reload sequence, with reload queued outside the current render frame. Previously the setting only addressed retired Vitrail. The initial implementation omitted save; live status caught Iris restoring the file's enabled state. That implementation was corrected and the final build passed on/off/on verification. Captures from that failed shaderless attempt are retained locally with `rejected-bsl-` prefixes and excluded from results.

`CaptureAnalysis.java metrics` now also prints the MSE it already calculated.

## Method and provenance

Windows, RTX 5070, Java 25.0.4, supported experimental Iris Vulkan build, Sodium 0.9.2, BSL v10.1.8. No performance measurements were taken or compared.

The source `run` directory was copied into this worktree's `run`. All game/save/config writes used the copy. Launched only with `mc-agent-harness/tools/launch.ps1`, project and run directory pointing at this worktree, port 25611, secondary monitor, 1920x1080, `rxDebug=25612`. Port 25601 and its client were not touched. Owned client PIDs across the three launches were 5480, 12184, and 2796. The final process and port were confirmed closed. Every shutdown used authenticated `quit` with `exit=true`, after checking the session PID, directory and injected harness jar against the Windows process command line.

Save: `Reflexium Vitrail Smoke`. Player position -24.5,131,11.5; camera yaw/pitch 0/0. Server ticks frozen for comparisons. Shader time and waving foliage still advance independently, so dynamic sky/leaf MSE is not an aligned quality score. FG was disabled. DLSS transformer, preset K for Quality/Balanced, sharpening setting 20, default mip bias and +1/+1 jitter signs.

Native render/output/present: 1920x1080. Quality render: 1280x720. Balanced render: 1114x626. Both DLSS modes output/present at 1920x1080. HUD is composed afterward. Same-frame input/output/depth/motion captures confirm LDR finished-scene input, pre-exposure 1, auto-exposure off, low-resolution pixel vectors, reverse-Z depth and no jitter in the primary vectors.

Motion captures used `capture_sequence` with count 12, interval_ms 100, dyaw 12, ticks 24. Static sequences used eight frames at 100 ms. Full local sequences and same-frame captures are in `build/validation/iq`, under this worktree. Baseline sequences were collected before source edits were loaded. Final on/off/on verification and an additional pan capture used the final rebuilt code.

## Evidence

Committed still images and crops are in [iq-evidence-2026-10-19](iq-evidence-2026-10-19/). Before and after full-size images are named `before-bsl-{native,quality,balanced}.png` and `after-bsl-{native,quality,balanced}.png`. Shaderless images are `vanilla-{native,quality,balanced}.png`.

Direct visual comparison: [before sky](iq-evidence-2026-10-19/before-sky-crop.png), [after sky](iq-evidence-2026-10-19/after-sky-crop.png). These are nearest-neighbor 2x crops of x=0,y=0,w=600,h=300. The repeating cloud checkerboard is visible before and absent after. No general blur filter was added.

Ground crop: x=300,y=760,w=800,h=200, luma values on the 0..255 scale:

| Path | MSE vs native | PSNR | Detail correlation |
| --- | ---: | ---: | ---: |
| BSL before Quality | 4.718 | 41.39 dB | 0.9159 |
| BSL after Quality | 4.715 | 41.40 dB | 0.9146 |
| BSL before Balanced | 5.227 | 40.95 dB | 0.9051 |
| BSL after Balanced | 5.143 | 41.02 dB | 0.9055 |
| Shaders off Quality | 8.027 | 39.09 dB | 0.8585 |
| Shaders off Balanced | 8.222 | 38.98 dB | 0.8523 |

Sky crop mean absolute Laplacian response falls from 2.486 to 1.585 in Quality (36.2%) and 2.192 to 1.337 in Balanced (39.0%). Native response was 1.553 before and 1.578 after. Lower energy alone can reward blur; the visual removal of the Bayer pattern and unchanged ground detail are the supporting evidence. Clouds move independently between captures, so sky pixel differences in [metrics.txt](iq-evidence-2026-10-19/metrics.txt) must not be interpreted as a matched temporal error measurement.

Inspected full-size native/DLSS stills, sky crops, and before/after motion samples at frames 0,5,11. [Motion contact sheet](iq-evidence-2026-10-19/motion-contact.png) has before above after, frames 0,5,11 left to right. Chest/ground detail stays readable in camera pans. Shaderless motion crops for native, Quality and Balanced are also committed. DLSS reduces native alpha-edge aliasing; BSL's animated leaves still change shape and show temporal softness. No missing bottom row, right column, corner, black output band or HUD blur was observed. This does not establish lossless animated-foliage or particle reconstruction.

Shaderless ground mean frame-to-frame luma change: Quality 0.1208, Balanced 0.1479 across eight static frames. An exploratory +1 mip-bias offset gave no useful ground improvement: Balanced PSNR 39.08 versus 39.11 dB in the paired default capture, correlation 0.8558 versus 0.8570. The vendor baseline remains unchanged.

Opposite jitter signs were tested in BSL Quality. In a leaf crop, static-sequence luma change increased from 8.7738 with +1 to 12.2613 with -1; the negative-sign image visibly had fine striping. Animated foliage prevents treating this as a pure jitter-isolation measurement. It supplies no basis for changing the existing sign. The two diagnostic sequences are `bsl-sign1` and `bsl-sign-1` locally.

Static and panning same-frame vector captures were decoded against their own independently projected metadata. Static after: 6594 samples, p99 error 0.0001 render pixels. Pan after: 6584 samples, p99 0.0001 pixels. All sampled opaque pixels were within 0.25 pixels. These validate camera reprojection on valid depth, not missing deformation or translucent vectors; sky pixels are excluded by this numerical check. Final steady history counters showed one feature-rebuild reset and zero gap/rotation/camera/loading/menu resets.

Reproduce numerical comparisons with Java 25:

```powershell
$w = 'C:\Users\Ryan\.t3\worktrees\Reflexium\t3-a804e13f'
$e = "$w\docs\validation\iq-evidence-2026-10-19"
java "$w\tools\CaptureAnalysis.java" metrics "$e\after-bsl-native.png" "$e\after-bsl-quality.png" 300 760 800 200
java "$w\tools\CaptureAnalysis.java" metrics "$e\before-bsl-native.png" "$e\before-bsl-quality.png" 0 0 600 300
java "$w\tools\CaptureAnalysis.java" metrics "$e\after-bsl-native.png" "$e\after-bsl-quality.png" 0 0 600 300
```

## Checks and remaining scope

`gradlew.bat build --console=plain` passed twice, including the final save/reload correction, all existing numerical/ABI checks and the expanded real-BSL source checks. Final log: `build/validation/iq/build-final.log`. `git diff --check` passed. Iris on/off/on worked in the final client. Native/DLSS and Quality/Balanced transitions worked. No native bridge or preset change was needed. Reference strategy remains the repo's existing Radiance/MCVR camera/object-motion comparison and NVIDIA SDK documentation under `gpu-sdk-reference/streamline`, plus direct inspection of the installed BSL sources.

Still incomplete: BSL HDR insertion, independent vectors for shader-deformed geometry, blended water/glass, particles, volumetric cloud motion and an exhaustive dynamic entity/hand acceptance matrix. Missing-DLL/backend-failure and resize were not retested in this targeted pass. Ultra Performance's preset L and aggressive vendor mip bias were reviewed but not accepted through a new matched-motion matrix. No claim of practically lossless Ultra Performance, lossless motion on every renderable class, or certification is made.

## Round 2

Base `b8d6502`, branch `t3/fix-motion-artifacts`, worktree `C:\Users\Ryan\.t3\worktrees\Reflexium\t3-3379e392`.
This round closes two producer gaps. It does not establish the owner's near-lossless motion-quality target.

### Causes and fixes

- `5071aed`: the bare arm uses `entityTranslucent`, even for opaque skin texels. Opaque-only submission attribution and blanket blend rejection excluded it. Both feature phases now attribute submissions, and depth-writing blended hand geometry can enter capture. The existing hand-depth equality test still limits visible writes. `ObjectMotionCheck` now uses the real skin render type rather than bypassing the capture filter.
- `4334bd3`: BSL leaves/vines changed vertex positions without independent vectors. The exact reviewed terrain and waving sources now evaluate `WavingBlocks` at the current and previous frame times, project both positions with unjittered matrices, and write an independent RGBA32F attachment. A depth-matched merge overwrites only visible leaf/vine vectors, preserving hand/object coverage and background vectors. Unknown/edited sources do not activate the merge. Grass/contact bending, water, world-time animation and clouds are deliberately outside this producer's coverage.
- `6ecb388`: the numerical capture checker forgot Minecraft's screenshot row inversion. It now converts PNG Y back to GPU Y before reprojection and world-space fixture selection. A decoder self-check guards the convention. Packed vector component signs are unchanged.

### Captures and isolation

All game captures came from the worktree copy of the main repository's `run`, through `mc-agent-harness/tools/launch.ps1`, port **25612**, primary PG27AQDP, **2560x1440**, unfocused. `rxDebug=25613` avoided a second listener on the harness port. The overlay was left alone. Port 25621 was never addressed, and no process-name shutdown or desktop input injection was used. Each owned shutdown checked authenticated PID/directory and the process command line's harness jar/directory before `quit {exit:true}`. Owned PIDs were 17288, 6876, 28008, 20888 and 29004. Final PID 29004 and port 25612 closed.

Baseline sources were unchanged in the first JVM. Native, Quality, Balanced and Performance each have 12-frame fixed-camera, yaw-pan and strafe sequences before and after, with shaders on and off. Pan: `dyaw=12,ticks=24`; strafe: `dx=1,ticks=24`; interval 100 ms. Pose: -24.5,131,11.5, yaw/pitch 0/0. Server ticks were frozen, but shader waves and effect time continued. FG was off. Transformer hints K/K/M were used for Quality/Balanced/Performance; sharpening stayed 20 and vendor mip bias stayed unchanged.

Render sizes: native 2560x1440, Quality 1707x960, Balanced 1485x835, Performance 1280x720. Output/present stayed 2560x1440. Same-frame captures still report finished-scene LDR input, pre-exposure 1 and auto-exposure off. HDR insertion was not implemented.

Full images and metadata live under `build/validation/round2` in this worktree. Before/after paths are `before-{bsl,vanilla}-{native,quality,balanced,performance}-{static,pan,strafe}/frame_NNN.png` and corresponding `after-*` paths. Committed selections are in [round2 evidence](iq-evidence-2026-10-19/round2/):

- BSL Balanced pan: [before](iq-evidence-2026-10-19/round2/before-bsl-balanced-pan-contact.png), [after](iq-evidence-2026-10-19/round2/after-bsl-balanced-pan-contact.png); strafe: [before](iq-evidence-2026-10-19/round2/before-bsl-balanced-strafe-contact.png), [after](iq-evidence-2026-10-19/round2/after-bsl-balanced-strafe-contact.png).
- BSL Performance pan: [before](iq-evidence-2026-10-19/round2/before-bsl-performance-pan-contact.png), [after](iq-evidence-2026-10-19/round2/after-bsl-performance-pan-contact.png). Native and shaderless Performance pan/strafe selections are alongside them.
- Fixed-camera water: [BSL](iq-evidence-2026-10-19/round2/water-bsl.png), [shaderless](iq-evidence-2026-10-19/round2/water-vanilla.png), native/Quality/Balanced/Performance rows, frames 0 and 7. Full eight-frame sequences are `water-{bsl,vanilla}-{mode}`. Pose -37.5,132,30, yaw/pitch 0/35. These are coverage observations after the change, not a water-motion fix or a before/after water claim.
- Actual equip motion: [BSL](iq-evidence-2026-10-19/round2/equip-bsl.png), [shaderless](iq-evidence-2026-10-19/round2/equip-vanilla.png). Scripted stone/air swaps with ticks unfrozen produced 24 drawn hand vertices in 7/24 BSL samples and 8/24 shaderless samples with vectors enabled; disabled samples drew zero. These sequences have different equip phases, so they are not pixel-aligned quality scores. The added skin unit check covers the arm's capture filter; exhaustive live bare-arm swing acceptance remains open.

Inspection included full-size output edges/corners and native/DLSS motion crops at frames 0,5,11. BSL leaf detail remains softer than native, particularly at Performance, with thin bright alpha-edge patterns still visible. Ground/chest detail stays readable during these modest pans and strafes. No black edge bands or reduced-size HUD were observed. Water reflections remain visibly time-dependent and unsupported by independent motion. The captures support added motion coverage, not a large proven visual-quality gain or official-DLSS-grade acceptance.

### Metrics and controls

[Metrics JSON](iq-evidence-2026-10-19/round2/metrics.json) comes from `python -I tools/iq-round2.py analyze`, using numpy/Pillow for analysis only. Ground crop x=400,y=1100,w=1000,h=180, RGB 0..255, final static frame versus that run's native frame:

| Mode | BSL before MSE / PSNR | BSL after MSE / PSNR | Shaderless before MSE / PSNR | Shaderless after MSE / PSNR |
| --- | --- | --- | --- | --- |
| Quality | 2.838 / 43.60 dB | 2.941 / 43.45 dB | 3.672 / 42.48 dB | 3.650 / 42.51 dB |
| Balanced | 3.260 / 43.00 dB | 3.371 / 42.85 dB | 3.948 / 42.17 dB | 3.861 / 42.26 dB |
| Performance | 4.338 / 41.76 dB | 4.284 / 41.81 dB | 5.015 / 41.13 dB | 4.829 / 41.29 dB |

Ground detail is approximately preserved. Animated leaf/cloud/equip phases are unmatched, so their MSE and frame-difference values in the JSON are descriptive only. Lower temporal difference is not accepted as less ghosting, and high-frequency energy is not accepted as better detail.

`foliage-third.json` records a stationary-camera real evaluation with `bsl_foliage_motion=true`, previous/current times 2.4840002/2.4890003. Decoded motion has 41,743 nonzero pixels, confined visually to leaves; the [amplified diagnostic](iq-evidence-2026-10-19/round2/foliage-motion-visible.png) shows the coverage. This establishes nonzero deformation input, not independent accuracy of every vector.

The stronger camera control temporarily set `ANIMATION_SPEED=0.00`, reloaded BSL, and captured during an actual yaw change. The temporary option file was restored afterward. `python -I tools/iq-vector-metrics.py <worktree>\build\validation\round2\no-wave-pan.json` passes assertions for an active foliage dispatch, nonzero camera motion and p99 error below 0.05 render pixels in both known regions:

| Region | Samples | Expected mean motion | Mean error | p99 error |
| --- | ---: | ---: | ---: | ---: |
| Leaf wall | 3,945 | 0.18644 px | 0.00706 px | 0.01787 px |
| Ground | 32,007 | 0.27572 px | 0.01156 px | 0.02031 px |

Both regions have 100% of samples within 0.25 pixels. [Control results](iq-evidence-2026-10-19/round2/no-wave-pan-metrics.json). The generic Java camera checker includes hand pixels and reports p99 0.3350; it cannot validate screen-fixed hands as world surfaces. Region selection avoids that invalid comparison.

An exploratory CPU reproduction of BSL's noise did **not** beat zero-deformation error and was rejected as independent animated-vector acceptance. Its code/output remain locally in `exploratory-noise-reference.py` and `exploratory-noise-reference-failed.txt`. CPU/GPU noise arithmetic, fixture matching and frame-time correspondence need further isolation. This failure is not relabeled as a passing quality check.

Initial frozen hand sequences and later unfocused attack-key sequences had zero animated hand vertices and were excluded from hand-motion evidence. Two experimental BSL launches fell back to vanilla because of directive parsing/compilation errors; `foliage-first` and `foliage-second` are also excluded. The final BSL build compiled and drew the pack successfully.

### Checks and remaining work

`gradlew.bat build --console=plain` passed with the real BSL source and Iris ABI checks, skin-hand capture check and existing numerical/ABI checks. Final log: `build/validation/round2/build-final.log`. `java tools/CaptureAnalysis.java self-check` passed. `git diff --check` passed. The existing build hook also copied the built jar to the configured Modrinth testing profile; future strictly isolated builds should pass `-PreflexiumInstallDir=<worktree-local-path>`.

Iris shader on/off and native/DLSS/quality switches were exercised repeatedly. Primary-window resize 2560x1440 to 2432x1368 and back recovered. A brief BSL FG run advanced to 297 evaluations and 296 generated presents without a renderer error; it was then disabled. This checks functional compatibility only. No performance or physical-scanout claims were made. Final live log: `build/validation/round2/final-live.log`.

Remaining: independent animated BSL leaf-vector accuracy; grass/contact bending; water/glass and other blended layers; particles; volumetric cloud motion; HDR insertion; a moving-entity acceptance matrix and bare-arm swings; long/fast-pan ghosting and mip-bias shimmer acceptance. The current evidence does not meet the requested near-lossless Quality/Balanced or comprehensive acceptable-in-motion Performance bar. Missing-runtime/backend failures and world rejoin were not retested after the final producer change.

Reproduction driver: `tools/iq-round2.py` modes `before`, `after`, `water`, `equip`, `control`, `analyze`, `evidence`. Use it only against this task's owned port/directory. The local isolated interpreter is `<worktree>\build\validation\python\python.exe` (Python 3.14.7, numpy 2.5.4, Pillow 13.0.0), invoked with `-I`. No Python dependency was added to the mod.

## Round 3

Base `7825952`, branch `t3/dlss-round-three-quality`, worktree `C:\Users\Ryan\.t3\worktrees\Reflexium\t3-d0c50193`.
The missing grass/plant producer and incorrect primary translucent depth are corrected. **The owner's near-lossless motion target is still not accepted.** Water surface shading, reflections/refraction, particles and volumetric clouds remain incomplete temporal inputs.

### Causes and fixes

- `a8edf1f`: BSL's previous-deformation attachment marked only leaves/vines. It now covers BSL IDs 100 through 108 and 157: grass, crops, plants, tall plants, leaves, vines, hanging vegetation and lilypads. It evaluates the pack's real wave function twice. Previous contact bending uses the previous camera-relative eye position and camera displacement, rather than bending both samples around the current player. The actual current-frame geometry and animation options are preserved. Vanilla has no added waving.
- `77bfb9c`: primary camera vectors and DLSS depth previously used final scene depth containing transparent surfaces. The supported Iris adapter now reads `RenderTargets.getDepthTextureView(1)`, its pre-translucent copy, and validates its current dimensions/lifetime. Object and foliage depth matching use that same background depth. This keeps terrain behind water/glass on its own correspondence. The change also affects shared FG inputs; it does not invent independent water reflection vectors.
- `b064f9d`: keep the injected bend variable declared if a user edits the waving include, while the exact-source guard disables its unverified merge. The shared include also compiles in shadow programs with a zero offset. Depth-source diagnostics now report the captured path rather than inferring it from the selected pack.

Source and ABI checks cover the reviewed terrain/wave edits, edited-source rejection, uniform APIs, eye-position access and the pre-translucent depth accessor. World-time animation remains excluded. The existing independent animated-noise accuracy objection from Round 2 remains open; evaluating the actual function is stronger coverage, but is not an independent numerical proof of every animated vector.

### Method and images

All captures used the worktree copy of the main repo's `run`, authenticated harness **25613**, debug listener **25614**, primary PG27AQDP **2560x1440**, unfocused. The black overlay was left alone. Port 25631 and its performance client were never addressed. No desktop input injection, process-name shutdown or performance comparison was used.

Baseline and changed clients each captured 12-frame pans for native, Quality, Balanced and Performance, with BSL on and shaders off, in both the grass and water views. Pan is `dyaw=12,ticks=24`, screenshots every 100 ms. Grass pose is -24.5,131,11.5, yaw/pitch 0/0; water pose -37.5,132,30, yaw/pitch 0/35. Server ticks are frozen; shader waves, cloud shading and reflections continue. FG is off for image comparisons. Native is 2560x1440; DLSS inputs are 1707x960, 1485x835 and 1280x720, all outputting 2560x1440. Input remains finished-scene LDR, pre-exposure 1, auto-exposure off; HDR insertion is still open.

Full sequences and frame metadata are under `build/validation/round3/{before,after}-{bsl,vanilla}-{plants,water}-{mode}`. Committed native-pixel crops show frames 0,5,11 in columns and native/Quality/Balanced/Performance in rows:

- Grass: [BSL before](iq-evidence-2026-10-19/round3/before-bsl-plants-pan.png), [BSL after](iq-evidence-2026-10-19/round3/after-bsl-plants-pan.png), [shaders off after](iq-evidence-2026-10-19/round3/after-vanilla-plants-pan.png).
- Water: [BSL before](iq-evidence-2026-10-19/round3/before-bsl-water-pan.png), [BSL after](iq-evidence-2026-10-19/round3/after-bsl-water-pan.png), [shaders off after](iq-evidence-2026-10-19/round3/after-vanilla-water-pan.png).
- Glass: [pan comparison](iq-evidence-2026-10-19/round3/glass-pan.png), [same-frame control](iq-evidence-2026-10-19/round3/glass-control.png). The owned save contains a glass plane at z=103 and brick wall at z=108; these are different layers, five blocks apart.

Visual inspection included these comparisons, full output/corners and the same-frame controls. Ground, glass-background brick and submerged block texture stay readable. Grass edges retain temporal softness relative to native, particularly at Performance. No black output band, missing final row/column or reduced-size HUD was observed. The modest pans do not establish long/fast-pan ghosting acceptance. Water reflection phases differ between runs, so no large before/after visual gain or lossless water claim is made.

### Metrics and defaults

[Metrics JSON](iq-evidence-2026-10-19/round3/metrics.json) includes RGB MSE/PSNR, crop and final-frame camera-yaw difference. Ground region x=400,y=1100,w=1000,h=180 in the grass view, final frame against each run's native final frame, 0..255 RGB; all listed yaw differences are zero:

| Mode | BSL before MSE / PSNR | BSL after MSE / PSNR | Shaders off before MSE / PSNR | Shaders off after MSE / PSNR |
| --- | --- | --- | --- | --- |
| Quality | 2.214 / 44.68 dB | 2.306 / 44.50 dB | 3.685 / 42.47 dB | 3.724 / 42.42 dB |
| Balanced | 2.610 / 43.96 dB | 2.681 / 43.85 dB | 3.816 / 42.31 dB | 3.843 / 42.28 dB |
| Performance | 3.864 / 42.26 dB | 3.792 / 42.34 dB | 4.630 / 41.48 dB | 4.562 / 41.54 dB |

Animated plants/water/cloud phases are unmatched. Their MSE values are descriptive differences, not aligned quality or temporal-accuracy scores. Lower high-frequency energy or frame difference is not accepted as better reconstruction. No bilinear baseline was captured.

The settings A/B repeated the grass pan at all three modes with sharpening 20, 0 and 40; mip-bias offset +0.5; and the alternate K/M hint. Inspected [Quality](iq-evidence-2026-10-19/round3/defaults-quality.png), [Balanced](iq-evidence-2026-10-19/round3/defaults-balanced.png) and [Performance](iq-evidence-2026-10-19/round3/defaults-performance.png). None clearly improved overall motion/detail. Zero sharpening softens texture; 40 gives stronger edges without recovering lost thin detail. The less-negative bias does not resolve grass softness. Alternate preset captures also have different wave phases. Defaults therefore remain sharpening **20**, vendor mip baseline, **K/K/M**. These remain requested hints, not a claim that NGX reported its internal selected model.

No-wave camera controls temporarily set `ANIMATION_SPEED=0.00` in the owned copy and restored the original option file afterward. [Vector metrics](iq-evidence-2026-10-19/round3/vector-metrics.json) independently decode packed motion/depth, invert PNG row Y and reproject sampled surfaces using each capture's matrices:

| Region | Samples | Expected mean displacement | Mean error | p99 error |
| --- | ---: | ---: | ---: | ---: |
| Grass, y=132.05..133.1 | 10,716 | 0.24481 px | 0.01159 px | 0.02039 px |
| Submerged floor | 1,955 | 0.22984 px | 0.01086 px | 0.02051 px |
| Brick behind glass | 5,980 | 0.18526 px | 0.00888 px | 0.01861 px |

The submerged crop reconstructs median y=129.999995, the pool floor, and the glass crop median z=108.000077, the brick wall rather than glass at z=103. Assertions require nonzero camera motion, active foliage merge, the pre-translucent source, over 100 samples and p99 below 0.05 render pixels. These establish camera/layer correspondence, not independent animated-wave accuracy. Controls: [grass](iq-evidence-2026-10-19/round3/grass-control.png), [water](iq-evidence-2026-10-19/round3/water-control.png).

### Transparency decision and remaining scope

Particles and volumetric clouds remain in the SR input with background/camera vectors. No FSR reactive buffer is presented as a DLSS K/M reactive mask. BSL integrates clouds, fog, lighting and blended geometry into its composite chain; extracting and rendering them after SR at display resolution requires a separate layer/composition contract, unlike the standalone HUD crosshair. This round accepts that limitation rather than adding an unvalidated final-color subtraction/replay. The same issue applies to animated water normals, reflection rays and refraction: pre-translucent depth fixes background ownership, but a single background vector cannot describe both optical layers. Independent water-surface/shading reconstruction remains unmet.

Reproduce analysis with the worktree-local Python interpreter and `-I tools/iq-round3.py analyze` or `vector-metrics`. Capture modes are `before`, `after`, `defaults`, `controls`, `smoke`, restricted to this worktree and port. No Python runtime/dependency is shipped with the mod. Build and final runtime results follow below.

### Final checks

`gradlew.bat build --console=plain -PreflexiumInstallDir=<worktree>/build/install` passed both before and after final source review, including real-pack source/ABI and existing numerical checks. Final log: `build/validation/round3/build-final.log`. No native C++/ABI change was made. `git diff --check` and Python syntax/analysis checks pass.

The final fixed spectator [wheat capture](iq-evidence-2026-10-19/round3/crop-motion.png) has a stationary camera with expected camera-only displacement 0.000012 render pixels. Of 10,186 sampled crop-surface pixels, 2,263 contain nonzero packed vectors. This establishes an actual independent deformation contribution on crops, not correctness of its full animated correspondence. An earlier creative-player crop attempt fell after tick stepping and showed the forest below; it was rejected and replaced, not counted as crop evidence. The smoke driver now selects spectator before freezing/teleporting.

Final runtime checks passed native/DLSS switching, Iris off/on, 2560x1440 to 2432x1368 and back, and a brief DLSS FG 2x compatibility run followed by FG off. Cumulative FG counters advanced, with empty `last_error`; they include earlier diagnostic multipliers and are not a frame-ratio/performance result. Final status is `build/validation/round3/final-status.json`. The final log contains no mixin or shader compilation failure or active reconstruction error; startup Realms/authentication errors are unrelated dev-session failures. Missing-runtime/backend fallbacks and world rejoin were not retested in this round.

Owned client PIDs were 3552, 21108 and 19048. Every shutdown verified authenticated PID/directory and the exact Windows command line's harness jar/directory, then used `quit {exit:true}`. The final owned process and port were confirmed closed. No merge to main or project push was performed.

## Round 4

Base `74df710`, branch `t3/post-upscale-particles-clouds`, worktree `C:\Users\Ryan\.t3\worktrees\Reflexium\t3-857e0740`.
Vanilla classic-transparency particles and precipitation now bypass SR at display resolution. Vanilla clouds have camera and time motion, including a surface-depth producer for OIT. **BSL effects, water optics and comprehensive fast-motion acceptance remain incomplete.**

### Causes and fixes

- `f50c9d6`: display depth was sampled without removing projection jitter. The resolve now samples the jittered source at display UV plus half the NDC jitter, clamps coordinates, and retains the existing inverse vendor-depth conversion into Minecraft's reverse-Z range. Depth is preserved for world effects, unlike the F3 crosshair's intentional clear. The old post-upscale outline implementation remains removed.
- `96c7ba4`: vanilla particle quads and precipitation had background/camera vectors despite their independent motion. With classic transparency, their render state is retained, omitted from the SR input, then submitted and prepared again through the vanilla feature dispatcher after reconstruction. They use the unjittered world projection, world fog, display-size globals and resolved world/hand depth, before post effects and GUI. Projection, fog and model-view state are restored. The original dispatcher's prepared buffers are not retained past `PreparedFrame.close`. The new **Native-resolution particles** option defaults on, is persisted as `post_upscale_particles`, and is exposed in both existing config screens. A runtime `particles_defer=0` override provides the pre-upscale control.
- `1bd032b`: vanilla cloud geometry moves along world -X at 0.030000001 blocks per game tick. OIT also stores cloud depth separately from the main depth. A render-resolution replay of the prepared cloud faces supplies their actual surface depth; the primary motion pass projects those surfaces to the previous camera and cloud position. Nearer terrain, DH and hand depth retain priority. Time discontinuities and history resets discard cloud drift history. GPU resources are released after the existing idle wait. `cloud_motion=0` is the dev negative control.

Particle deferral is deliberately bypassed for Iris/shader packs and improved transparency/OIT. Their blended ordering and lighting require a separate display-size composition contract. OIT clouds receive vectors, but OIT particles/precipitation retain their original path. No new dependency, native ABI change, generic history-reset heuristic or sharpening/preset change was introduced.

The [Super Resolution mod's renderer hooks](https://github.com/IReallyWantToSleep/superresolution/blob/dev/modules/common/src/main/java/io/homo/superresolution/common/mixin/core/GameRendererMixin.java) were inspected during implementation. Its level/hand boundaries and depth-clear handling do not provide a reusable native RenderPearl particle layer. Reference guidance: [NVIDIA DLSS programming guide](https://github.com/NVIDIA/DLSS/blob/main/doc/DLSS_Programming_Guide_Release.pdf). The concrete 26.3 call/lifetime contracts above were checked against this project's decompiled Minecraft sources.

### Captures and inspection

Minecraft MCP `mc_launch` was called through its stdio server because this agent's exposed tool catalog lacked the Minecraft tools. The copied main-repo `run` stayed inside this worktree. Harness port **25614**, debug port **25615**, primary PG27AQDP **2560x1440**, unfocused, with the black overlay untouched. Owned game PIDs were 2492, 19912, 16772, 29556 and 15300. Every shutdown used MCP's authenticated PID/directory/Windows-command-line ownership checks and `quit {exit:true}`. No other client was addressed or stopped.

Vanilla was captured first, followed by BSL, at native, Quality and Performance, with particle deferral off/on. FG was off for image comparisons. Native is 2560x1440, Quality 1707x960, Performance 1280x720; all output/present sizes are 2560x1440. Sharpening remains 20, vendor mip bias unchanged, transformer hints K/M. Input remains finished-scene LDR, fixed pre-exposure 1 and auto-exposure off. HDR insertion is unchanged.

The fixture at 0,151,100 contains two campfires, glass and a brick background. Rain uses the same camera. Explicit stone block-particle and flame bursts plus a placed torch cover debris and small emissive sprites. A separate brick pillar and stained-glass plane exercise occlusion. The command codec accepted `minecraft:block{block_state:{id:"minecraft:stone"}}`; these are actual particle draws, not inferred coverage.

Committed native-pixel contact sheets contain frames 0 and 3, with native, Quality pre/post and Performance pre/post in rows:

- Vanilla [smoke](iq-evidence-2026-10-19/round4/vanilla-smoke.png), [rain](iq-evidence-2026-10-19/round4/vanilla-rain.png), [block/flame bursts](iq-evidence-2026-10-19/round4/vanilla-burst.png).
- BSL [smoke](iq-evidence-2026-10-19/round4/bsl-smoke.png), [rain](iq-evidence-2026-10-19/round4/bsl-rain.png). The off/on rows intentionally use the same pack path; their different animation phases are not a BSL improvement claim.
- Occlusion full frames: [native](iq-evidence-2026-10-19/round4/occlusion-native.png), [Quality](iq-evidence-2026-10-19/round4/occlusion-quality.png), [Performance](iq-evidence-2026-10-19/round4/occlusion-performance.png).
- Fast-pan selections: [vanilla](iq-evidence-2026-10-19/round4/vanilla-fast.png), [BSL](iq-evidence-2026-10-19/round4/bsl-fast.png).

These images and full output/corners were opened and visually inspected. Vanilla post-upscale smoke keeps native-like sprite steps instead of softened history edges. Rain streaks and flame/debris edges remain more distinct, especially at Performance. The pillar occludes smoke at its edges; no smoke is drawn over its solid face, and the stained-glass/particle overlap follows the inspected native classic path. No black output bands, unwritten last row/column, reduced HUD or repeat of the old outline clipping failure was observed. This supports the default-on classic option; it does not establish arbitrary mod-particle/blended-water ordering.

Fast pans use **dyaw=90 over 10 ticks**, starting at yaw -45 in the existing grass/leaf arena. The final pass waits three client ticks before its four-frame sequence. [Composition/pose metadata](iq-evidence-2026-10-19/round4/composition-checks.json) records the actual sampled player yaws, render/output sizes and active deferral state for all 30 final sequences. First samples are commonly -18 or -9 degrees; subsequent samples often reach 45. Native/Quality/Performance were inspected, but readback misses much of the half-second turn, and player yaw metadata is not an exact rendered interpolation matrix. No obvious long terrain trails were visible in the sampled frames. Thin leaf detail remains softer than native, especially with BSL/Performance. No full continuous fast-pan acceptance or pixel-aligned moving-foliage score is claimed, and no speculative reset/preset change was made.

Raw sequences, statuses, controls and logs remain in `build/validation/round4`. Earlier `vanilla-*`/`bsl-*` sequences are particle-prototype captures; the committed final sheets use `final-*`. Earlier frozen-particle attempts had no persistent visible bursts and are excluded from occlusion evidence. The final continuously emitted campfire occlusion captures replace them. An early cloud off/on pan used different camera poses and is excluded as a matched visual A/B.

### Independent cloud-vector evidence

`java tools/CaptureAnalysis.java cloud-check <capture.json> 192` independently decodes packed depth/vectors, inverts screenshot Y, reconstructs world coordinates, selects the actual cloud slab at Y=192..196, advances the reconstructed point to its previous cloud position, and projects it through the previous camera matrix. It requires over 100 samples, nontrivial expected motion, measured motion energy and p99 error below 0.05 render pixels. The stationary-camera controls use a 30 FPS diagnostic interval and a camera just below the cloud layer to make time drift observable above capture quantization.

| Producer | Samples | Expected mean | Measured mean | p99 error |
| --- | ---: | ---: | ---: | ---: |
| Classic clouds | 10,698 | 2.90159 px | 2.89983 px | 0.01860 px |
| OIT clouds | 1,773 | 0.08620 px | 0.08774 px | 0.01555 px |

[Results](iq-evidence-2026-10-19/round4/cloud-vector-metrics.json), [classic metadata](iq-evidence-2026-10-19/round4/near-cloud-1.json), [OIT metadata](iq-evidence-2026-10-19/round4/oit-cloud.json), with portable input/output/motion/depth PNGs alongside them. These prove actual nonzero time correspondence. They do not prove a large perceptual cloud-quality gain or describe BSL's volumetric cloud shading. The build also runs a known-point cloud/camera translation sign check in `UpscalingContractCheck`.

Descriptive static floor metrics use RGB 0..255, x=400,y=1100,w=1000,h=180, final smoke frame versus native. [All values](iq-evidence-2026-10-19/round4/metrics.json). Vanilla Quality pre/post MSE is 4.2449/4.2550 (41.852/41.842 dB); Performance is 5.9297/6.4739 (40.401/40.019 dB). BSL Quality is 2.2141/2.2094; Performance 20.1392/23.2175. Animation, shader lighting, cloud and temporal phases are unmatched, so these are descriptive differences, not causal effect scores. BSL does not change composition between the two settings. No bilinear baseline or near-lossless acceptance is claimed.

### Final checks and remaining work

`gradlew.bat build --console=plain -PreflexiumInstallDir=<worktree>/build/install` passed after final runtime code review, including existing ABI/source checks and the added cloud check. Log: `build/validation/round4/build-final.log`. `git diff --check`, capture decoder self-check, the two cloud checks, and the 30-sequence composition checks pass. The configured external profile was not installed into.

The final client passed improved-transparency fallback (post particles false, cloud producer true), native/DLSS and Quality/Performance switching, Iris on/off, and resize 2560x1440 to 2432x1368 and back. A brief DLSS FG 2x compatibility check advanced from 182 to 578 evaluations and 181 to 577 generated presents with no reconstruction error, then returned FG off. This is functional compatibility, not scanout, pacing or particle-FG quality evidence. Missing-DLL, unsupported-backend and forced evaluate-failure fallbacks were not retested. No performance gain is claimed for either added draw path.

Remaining: BSL particle/precipitation and volumetric-cloud temporal inputs; OIT particle/precipitation motion or display composition; animated water normals, reflection/refraction correspondence; HDR insertion; independent particle FG motion; broader mod-particle and continuous fast-pan acceptance. Current evidence does not meet the owner's universal near-lossless-in-motion target.

Reproduction: `tools/iq-round4.mjs` modes `setup`, `final`, `burst`, `occlusion-final`, `cloud-control`, `smoke-check`, `fg-check`, `analyze`, `evidence`, restricted to this worktree's owned port/directory. Use the installed Electron runtime with `ELECTRON_RUN_AS_NODE=1` and `IQ_MODE=<mode>`. `java tools/Round4Evidence.java <absolute-worktree>` rebuilds the committed sheets/metrics. Run cloud checks from the repo root for the portable evidence paths. Final PID 15300 and port 25614 were confirmed closed. No project push or merge to main was performed.

## Round 5

Base `main` `df17485`, branch `t3/fix-bsl-foliage-streaking`. Renderer and reproduction tools: `845aba3`. No merge to main.

### Cause and changes

The BSL deformation attachment was not a current-frame coverage buffer. It is used only by terrain, allocated lazily, and absent when the supported Iris build constructs its clear-pass list. `colortex15Clear=true` therefore did not prevent old leaf silhouettes from accumulating. A later camera view still contained the earlier close leaf wall in screen space. Depth rejection hid much of this stale data, but could admit an old vector where another alpha-tested surface occupied the same depth. The Round 2/3 no-wave reprojection checks did not test attachment freshness or animated coverage.

The new `beginLevelRendering` return hook allocates the reviewed attachment before terrain and clears it to explicit RGBA zero every world frame. `observed.bsl_foliage_surface_cleared` reports an actual issued clear. This applies only when the exact-source BSL producer is ready. No native terrain, unknown pack, saved pack option, preset, mip-bias, shadow quality or reset policy changes.

A second defect discarded valid current foliage vectors. Iris presents traditional `gl_FragCoord.z` to pack shaders, so the previous `1 - gl_FragCoord.z` performed a precision-losing round trip from native reverse-Z. The producer now stores `rxCurrentClip.z / rxCurrentClip.w`, retaining reverse-Z precision. The visible-depth tolerance is unchanged. The reviewed near leaf region had 20,371 rejected and 106,346 accepted display pixels before; the corrected capture has zero rejected and 106,500 accepted. Wind phases differ, so these counts establish gate coverage, not pixel-aligned animation quality.

`foliage_depth_debug=1` draws the same gate after reconstruction: green accepted, red rejected, black no foliage. It does not substitute diagnostic vectors into DLSS. `foliage_clear=0` temporarily restores the missing-clear behavior for the same-client control. Both are dev-only runtime overrides. Normal `mv_debug` remains available.

### Evidence and visual result

[Coverage counts and runnable assertions](iq-evidence-2026-10-19/round5/coverage.json), [baseline near](iq-evidence-2026-10-19/round5/baseline-depth-near.png), [fixed near](iq-evidence-2026-10-19/round5/final-depth-near.png). The final same-session control moves from a close leaf wall to a distant view, with identical depth encoding in both arms: [clear disabled](iq-evidence-2026-10-19/round5/no-clear-far.png), [clear restored](iq-evidence-2026-10-19/round5/clear-restored-far.png). In a sky-region ROI x=400,y=150,w=500,h=450, disabling the clear leaves 165,362 stale red pixels; restoring it leaves zero red and zero green. These images were opened and inspected. This directly proves the stale attachment defect and its correction, independently of neural output quality.

[Grass pans](iq-evidence-2026-10-19/round5/grass-pan.png) show native, before/after CNN E Quality, and before/after Transformer K Quality. [Close leaf pans](iq-evidence-2026-10-19/round5/leaves-pan.png) include native and both models after the fix, plus the original K output. Full frames and the contact sheets were opened and inspected. After the fixes, cutout leaf detail is cleaner and the producer no longer contributes stale screen-space coverage. Native BSL also has stretched grass blades and horizontal gaps between independently waving leaf blocks. Those native geometry changes must not be counted as DLSS history trails. CNN remains softer than K. Thin foliage still has temporal softness/shimmer; these captures do **not** establish complete elimination of the requested visible-streaking problem or universal lossless motion.

The large input defect is established more strongly than the perceptual improvement: foliage wind, lighting and cloud phases are not locked between arms, and screenshot readback changes sampling times. No percentage ghosting reduction, phase-aligned foliage MSE, bilinear comparison, throughput gain or complete animated-vector accuracy claim is made. Far forest pixels still include some depth-rejected foliage and need independent layer/coverage attribution.

### Method and checks

Owned Minecraft MCP client, port 25616, debug 25617, isolated copy of the repository run directory in this worktree. Primary monitor 2560x1440, background/unfocused, black overlay untouched. RTX 5070, experimental Iris Vulkan, BSL v10.1.8. Save `Reflexium Vitrail Smoke`, spectator, frozen server ticks, noon and clear weather. Shader animation continues normally. FG off, Reflex On, Smart Reflex off, diagnostic 16,667 us interval, sharpness 20, shadow caps 0/0, original mip bias and +1/+1 jitter. Native input/output/present is 2560x1440. DLSS Quality input is 1707x960, output/present 2560x1440.

Grass camera starts at -24.5,131,11.5, yaw/pitch 0/0; `capture_sequence` count 12, interval_ms 100, dyaw 120 over 240 ticks. Close leaves start at -24.5,131,18, yaw -15, pitch 0; count 10, dyaw 60 over 240 ticks. [Sequence metadata/checks](iq-evidence-2026-10-19/round5/sequence-checks.json) records actual yaws, resolutions, models and reset counters. Each accepted sequence has at least six distinct sampled yaws. Pan poses are close but not exactly aligned across arms. The initial short 24-tick pans ended around frame 2 because readback costs hundreds of milliseconds; those frames are excluded from the committed pan sheets. Exploratory frozen-wind captures overlapped a prior capture and are excluded. The temporary `ANIMATION_SPEED=0.00` file was removed. A diagnostic clear prototype used JOML's default Vector4f alpha of one; its images are excluded. Final clear explicitly supplies four zero components.

`gradlew.bat build --console=plain -PreflexiumInstallDir=<absolute-worktree>/build/install` passed, including real BSL source and Iris ABI checks. Log `build/validation/round5/build-final.log`, Gradle success and exit 0. `java <worktree>/tools/Round5Evidence.java <absolute-worktree>` passes real-image stale-coverage and near-gate assertions and rebuilds the sheets. `node <worktree>/tools/iq-round5.mjs check` passes sustained-pan, dimensions, FG-off and empty-error assertions. The final build passed resize 2560x1440 to 2432x1368 and back, native/CNN/Transformer toggles, and observed issued-clear checks. Final statuses are alongside the evidence. No missing-runtime, unsupported-backend, vendor-evaluate-failure or FG acceptance retest is claimed.

Reproduce against only the owned copied run directory with `tools/iq-round5.mjs` modes `setup`, `capture <label> cnn|transformer|native`, `dense <label> ...`, `depth <label>`, `clear-control`, `smoke`, `check`, and `exit`. The driver calls the existing Minecraft MCP dispatcher; launch uses mc_launch with the ports/dimensions above. Raw captures and launch/build responses remain in `build/validation/round5`. Shutdown uses authenticated MCP `quit {exit:1}` and exact owned PID/port verification. No other client was addressed.

Reference: NVIDIA's [DLSS integration guide](https://github.com/NVIDIA-RTX/Streamline/blob/main/docs/ProgrammingGuideDLSS.md), its local SR validation checklist, and the supported Iris artifact's `ClearPassCreator`/`RenderTargets` bytecode. The existing [Super Resolution mod](https://modrinth.com/mod/superresolution) was checked as a related implementation; its generic algorithm-selection path does not own this Iris terrain-only attachment. No new dependency was needed.

Final compiled-code pans were also captured and opened after the shader-gate refactor: [CNN E crop](iq-evidence-2026-10-19/round5/final-cnn-pan.png), [K crop](iq-evidence-2026-10-19/round5/final-k-pan.png). [CNN status](iq-evidence-2026-10-19/round5/final-cnn-status.json) and [Transformer status](iq-evidence-2026-10-19/round5/final-transformer-status.json) confirm the issued clear, 1707x960 input and empty `last_error`. Owned final PID 16184 and listeners 25616/25617 were confirmed gone after authenticated exit. The project worktree is committed; no project push or merge was performed.

## Round 6

Base `59acbe5`, branch `t3/reduce-foliage-water-artifacts`. This round retains evidence and reproduction tools only. No renderer or default change passed the visual acceptance gate. No merge to main.

### Foliage experiments

Nineteen eight-frame sequences cover native, CNN E Quality, Transformer K Quality, a +1 mip-bias offset, 16/32/64 jitter phases and sharpness 20/60 with shaders off and BSL. The baseline Quality mip bias is -1.5846808; the relaxed arm requests -0.5846808. This is a global material-sampler experiment, not an isolated cutout-layer clamp or alpha-to-coverage implementation. Sampler telemetry does not independently prove which mip every shader instruction selected. Alpha coverage remains a hypothesis, not an established cause.

Opened and inspected: [shaderless native/CNN/bias/K](iq-evidence-2026-10-19/round6/foliage-initial.png), [BSL native/CNN/bias/K](iq-evidence-2026-10-19/round6/foliage-bsl-initial.png), [BSL jitter phases](iq-evidence-2026-10-19/round6/foliage-phases.png), [BSL sharpening](iq-evidence-2026-10-19/round6/foliage-sharpen.png), and [shaderless phase/sharpening controls](iq-evidence-2026-10-19/round6/foliage-vanilla-controls.png). Sheets show frames 0 and 3. CNN loses fine within-leaf texture; K retains more detail but does not establish elimination of shimmer. Relaxing mip bias does not visibly recover the missing leaf detail. Neither 16 nor 64 phases supplies a convincing improvement over 32. Raising sharpening adds edge contrast without recovering stable fine structure. Keep the existing defaults and existing selectable K model.

Camera poses and wind/lighting phases differ between arms. In particular, the later BSL arms include newly built water-fixture geometry behind the leaf wall, visible in the sheets. These are exploratory regional inspections, not phase-aligned causal comparisons. Sparse screenshots cannot measure continuous shimmer. No numerical ghosting reduction, MSE attribution, bilinear baseline or throughput improvement is claimed. Further cutout-specific sampling work needs an isolated fixture and matched poses before shipping.

### Water experiments

The fixture has a brick floor at y=145, water through y=149, and brick/leaf pillars above it. Camera starts at 0,153,91, yaw 0, pitch 25. [Shaderless native/CNN](iq-evidence-2026-10-19/round6/water-vanilla.png) retains the floor pattern but changes its fine surface appearance. It does not establish a large trail or a solved animated-texture path. [BSL native/background-depth/surface-depth](iq-evidence-2026-10-19/round6/water-bsl.png) shows different waves, reflected pillars and refracted floor structure. All sheets were opened and inspected.

Iris currently supplies pre-translucent depth view 1 for camera reprojection. The prototype substituted final depth view 0 globally, testing the inexpensive surface-depth alternative without introducing another draw pass. The inspected output did not show a convincing improvement. A depth switch cannot encode the correspondence of both the surface optics and the refracted floor, and animated normals/reflections need more than static surface camera motion. The global switch also affects other transparency. It was removed, along with the temporary jitter override. The exact [rejected prototype patch](iq-evidence-2026-10-19/round6/rejected-prototype.patch) is preserved for reproduction, not applied to production. No water mask or surface-vector fix is claimed.

### Method, checks and reproduction

Owned Minecraft MCP port 25618, debug 25619, copied run directory inside this worktree, primary monitor 2560x1440, background and unfocused. Existing black overlay untouched. RTX 5070, experimental Iris Vulkan, BSL v10.1.8. Frozen server ticks, noon, clear weather, spectator, shader animation not independently locked. FG off, Smart Reflex off, diagnostic interval 16,667 us, shadow caps 0/0. Native render/output/present is 2560x1440; DLSS Quality render is 1707x960 with output/present 2560x1440. Grass camera starts at -24.5,131,11.5, yaw/pitch 0/0. Capture count 8, interval 100 ms, dyaw 60 over 240 ticks; water uses dyaw 12. Readback changes actual sampling intervals and poses.

[Sequence checks](iq-evidence-2026-10-19/round6/sequence-checks.json) and individual metadata files alongside the sheets record actual yaws, models, preset requests, mip bias, sharpening, jitter phases and reset counters. `node tools/round6-check.mjs docs/validation/iq-evidence-2026-10-19/round6` passes all 19 sequences: at least six distinct yaws, expected active-mode dimensions, FG off, empty reconstruction error and no OS focus. Native retains a cached `upscaler_built=dlss`; the check correctly uses `upscaler_effective=off` to identify actual native rendering.

`tools/iq-round6.mjs` uses the existing Minecraft MCP dispatcher and checks owned game-directory identity before mutations. Modes: `launch`, `status`, `setup`, `set <key> <value>`, `capture <label> cnn|transformer|native <bias-offset>`, `water <label> ...`, `water-setup`, `exit`. Experimental `jitter_phases` and `iris_surface_depth` keys require applying the archived prototype and rebuilding. Restore phase 0, surface-depth 0, bias offset 0 and sharpness 20 between relevant arms. `java tools/Round6Evidence.java <absolute-worktree> <comma-separated-labels> <x> <y> <width> <height> <output-name>` rebuilds a crop sheet from local raw frames under `build/validation/round6`.

Final `gradlew.bat build --console=plain -PreflexiumInstallDir=<absolute-worktree>/build/install` passed with exit 0, including DLSS math, upscaling contracts, real Iris/BSL checks and other existing checks. Install was skipped because that isolated target did not exist; no external profile was modified. Renderer source matches the base after removing rejected experiments. Runtime fallback, resize, HDR placement, dynamic-vector accuracy and FG acceptance were not retested in this round. At recovery, prior PID 26096 was absent, ports 25618/25619 were closed, and MCP status returned `fetch failed`; no surviving owned client required shutdown. The remaining Java process was a verified Gradle daemon. No other client was addressed.

Remaining: thin cutout coverage and continuously sampled shimmer, proper animated water/reflection/refraction correspondence, and matched-scene acceptance. This round narrows the tested options but does not meet the near-lossless-in-motion goal.
