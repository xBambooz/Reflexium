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
