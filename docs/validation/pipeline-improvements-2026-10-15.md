# Pipeline improvements, 2026-10-15

Status: implementation candidate, **acceptance incomplete**. This continues the interrupted Opus and Sol changes from baseline `4df62671ab4860a2003462339e05f8a076b87cad`, preserving the separate Iris research commit `96c3a19`. Stage 1's validation fixes are implemented and checked offline. Stage 2 adds diagnostic attribution without changing synchronization. Stage 3 has functional smoke but an open pacing gate. Stage 4 has an actual opt-in HDR/resource/motion route, with limited numerical and visual evidence, not general temporal-quality acceptance. No new performance improvement is claimed.

## Build identity and checks

| Artifact | SHA-256 |
| --- | --- |
| Final `build/perf-jars/pipeline-candidate.jar` | `C3DD630A9AD229D77B33C8652ADCE8353351068F9CCD1830B609E12184E9F92C` |
| Retained `build/perf-jars/shaderless-final.jar` | `CF8379169AE71666FF6796550BFB5276B664CA28CB495FD39EF70A3B96F440F6` |
| Older `build/perf-jars/shaderless-c0.jar` | `9942B5F0DB1E0764AA50EA796D65EBFC1CC985F9602B8F08A5F2D6EABFEEA8BE` |
| Separate `reflexium-bm-driver.jar` | `E273468A4374F6A8481358D403C3D869F1F366FD2AE1042D3EC5A04B20B15F82` |
| Benchmark Mod `benchmark-mod-1.0.0.jar` | `2226ABB00736974A52A17AE9B0DD19B2885A940EDE355B27034DDC87BA01AE66` |
| Bundled FSR bridge | `4A9C81D50DFD4B0022FAAF52D99306004F1E0F117CEE13F3735F1F3E674ADF82` |
| Bundled NGX bridge | `81CB16A00D96FF4DE4DCC733DA13A2E0788E75F90A24BC9B95300A6CFAB85792` |

The installed Modrinth Reflexium jar matches the final candidate hash. Native bridges were rebuilt with `native/build.cmd`, including the changed FSR ABI; the Java build does not rebuild native code. The same pinned DLSS/Vulkan SDKs documented in `native/README.md` were used. Java is GraalVM 25.0.4, Minecraft 26.3, Sodium 0.9.2, Vitrail 0.12.0-beta+mc26.3, Complementary Reimagined r5.9.3, RTX 5070 / NVIDIA 617.42. Pack archive SHA-256 is `fed6c879e732cf7fc7bc6570ebb1def43235aa7e96f62859fbdbdbfa0b484b38`; Vitrail jar is `0823ba8ff806e9a1cc8520b7410783683d3a5f2213576212cecdeecc869bcfc8`.

Passed commands:

```powershell
$env:JAVA_HOME='C:/Program Files/GraalVM/graalvm-jdk-25.0.4+7.1'
.\native\build.cmd
.\gradlew.bat build bmDriverJar -I tools/vitrail-check.gradle vitrailCompatCheck --console=plain
powershell -NoProfile -ExecutionPolicy Bypass -File tools/bm-quick-bench.ps1 -SelfCheck
& "$env:JAVA_HOME/bin/java.exe" tools/TraceCheck.java self-check
& "$env:JAVA_HOME/bin/java.exe" tools/CaptureAnalysis.java self-check
# In sibling Benchmark Mod:
.\gradlew.bat build --console=plain
& "$env:LOCALAPPDATA/Bambooz/McTest/python/python.exe" tools/mctest/test_upscaling.py
```

Logs: `build/pipeline-final-build.log`, `build/pipeline-native-build.log`, sibling `build/pipeline-final-build.log`. Gradle includes frame delivery/ledger, Reflex timeout recovery, object motion, upscaling contracts and FSR native ABI checks. The Vitrail check reads the installed pack and tests exact-source rejection, unchanged original settings and the installed reflective ABI. Deprecation warnings, stale Vulkan layer-manifest registrations from another checkout and dev Realms authentication errors are present. No new Vulkan VUID was observed, but core/synchronization validation was **not enabled**; absence of a VUID is not validation-layer acceptance.

## Stage 1: exact measured-pass validation

| Acceptance item | Status and evidence |
| --- | --- |
| Actual inclusion boundaries | Implemented. Optional BM `FrameCapture.setBoundaryListener` observes the same decision that stores each interval. The separate driver snapshots at first and last measured frame starts, excluding the final frame in flight. BM's generic result format and metrics remain unchanged. No HTTP polling or per-frame logging in measured spans. Live boundary acceptance is unmeasured. |
| Pass tuples and aborted captures | Implemented. `bm-pass-v1` includes interval count, application IDs, start/end snapshots, early/late deltas, end phase, completion state and telemetry schema. Partial, missing and counter-reset cases fail offline checks. |
| Generation coverage and skip reasons | Implemented. Lifetime `GenerationLedger` closes every real-present attempt, accounting for expected generation and explicit missing reasons. Eligible coverage excludes only history reset; all-frame coverage remains separate. Zero eligible work cannot pass. 2x uses one generated frame regardless of the stored multiplier; MFG uses the effective supported count. |
| 99% and early/late checks | Implemented and checked offline for 2x/6x, startup-only generation, stop-mid-pass, wrong multiplier, missing boundary frames and zero generation. No live certification yet. |
| Metering acceptance | Partial-review finding 3 fixed. Reject any measured metering rejection and unequal started/completed batches independently of generation coverage. Missing counters remain unverified. Regression fixtures keep generated counts intact, proving this is a separate gate; `CombineValidity` cannot bypass the rejection. |
| Reflex throughout measured span | Implemented. Completed sleep count must match measured intervals; unpaced/suspended frames, timeout/failure/mode transitions invalidate a pass. Historical recovered errors outside the span do not poison it. Off/Boost and NVIDIA-forced On remain covered offline. |
| Provenance and resumes | Implemented. Runner protocol v4 (v3 added measured-pass semantics; v4 adds explicit visual export acceptance), driver/BM hashes, `bm-pass-v1` and `reflexium-measurement-v1` recorded. Unknown full telemetry versions cannot certify a pass. Incompatible resumes are refused. |
| Every validity consumer | Partial-review finding 2 fixed. Importer and Quick archiver require verified FG, verified Reflex and combined measured-pass validity. Legacy counters remain unverified and emit no FPS/gain fields. Quick and instrumented campaigns remain prohibited in the overnight importer. Protocol v4 also requires still/sequence evidence hashes and a reviewer before charting. |
| Live high-priority case | Unmeasured. No new benchmark was launched. Offline synthetic cases do not establish real pass-boundary accuracy or sustained physical display delivery. FSR proxy counters remain unverified under NVIDIA's rules. |

## Stage 2: FG2 pacing attribution

| Acceptance item | Status and evidence |
| --- | --- |
| Bounded trace extension | Implemented, default off. Monotonic CPU timestamps, sleep signal/call/wait/result, requested/applied mode, interval, Vulkan call identity and batch index, encoder completion index/waits and metered generated/real roles augment the existing trace. |
| Driver report correlation | Offline checker joins by frame ID and rejects incomplete/inconsistent reports. CPU and GPU clock domains remain distinct. No calibrated GPU/CPU trace was captured. |
| Marker/sleep/submission checks | `TraceCheck self-check` rejects missing/duplicate marker pairs, sleep counts, unattributed frames and ambiguous legacy call/batch counts. Retained `docs/benchmarks/bm/validation-trace-20261015/` is historical, not a new attributed trace. |
| Explain variable FG2 wait | Unresolved. Retained same-jar 0.172 ms versus earlier roughly 0.65 ms wait observations are not proof of a fixed regression. Profiles preceded their trace and cannot decompose the frame. The next required evidence is matched frame-ID sleep/completion/GPU work, not another average. |
| Resource lifetime and correction | Existing completion protection retained. No speculative queue, semaphore, marker-placement, arbitrary interval or synchronization optimization was applied. No established driver root cause or performance fix is claimed. |

## Stage 3: runtime and performance acceptance

| Acceptance item | Status and evidence |
| --- | --- |
| Final pin/install | Passed build/hash match. Actual swapchain-created count is exposed separately from requested count. Smoke observed 8 created images in Immediate and FIFO modes. |
| Resize/VSync | Functional smoke passed: 960x540 -> 1280x720 -> 960x540, input dimensions updated, FIFO on VSync and Immediate when disabled. `pipeline-evidence-2026-10-15/transitions.json`. |
| Fullscreen | Requested but unverified. The harness reapplies its background window placement; saved dimensions remained 960x540. Do not count an option toggle as proof of an actual fullscreen transition. |
| FG off/on, 2x/6x and pause blur | Functional smoke completed without crash or reconstruction error; menu image retained in ignored `build/validation/pipeline-final/menu-fg2.png`. **Pacing gate open:** transition log recorded 14 timeout counter increments and 7 recoveries, with signals arriving after about 104 ms. This is not a clean pacing pass and must not be charted as one. No timeout workaround was added. |
| Reload, shaders off/on, world leave/rejoin | Functional smoke passed and reconstruction resumed. Depth/target recreation produced no observed stale image or hang. This is not synchronization-validation certification. |
| Hand and entities | Swing/equip sequences and animated campfire/water scenes captured. Opaque/hand unit checks pass; no independent live per-pixel hand/entity correspondence certification. |
| Final Quick comparison | Deferred. Quality/pacing gates remain incomplete. No current FPS, 1% low, variation or improvement claim. Prior 2.4% average / 16.8% low noise estimates are historical and not reused as new acceptance. |
| Cadence/latency | Unmeasured: no FrameView/cross-domain GPU/scanout/physical input-to-photon capture. Software driver reports are not physical latency. |

## Stage 4: actual early HDR route

Enable the experimental route with `-PrxPackHdr=true` for dev launch (JVM `-Dreflexium.pack.hdr=true`). It is intentionally off by default until the remaining visual/lifecycle gates pass. The production fallback retains the reviewed late route. This is implemented resource/insertion work, not merely an FXAA bypass or telemetry label.

| Actual stage | Color, depth, dimensions and ownership |
| --- | --- |
| Geometry and hand | Input-size pack world targets; original opaque object/hand vector paths preserved. Pack terrain/water additionally write independent RGBA16F surface velocity, reverse depth and contribution at colortex14/15. Hand mask prevents overwriting hand vectors. |
| Composite through composite4 | Original input-size effects/histories retained, including reflection accumulation in colortex7/1 and volumetric data in colortex5. Composite3 optional DOF and composite4 packed bloom generation remain upstream. This is a scoped pre-tone-map route, not a complete reorganization of all optional effects. |
| Before composite5 | Actual `PackPass.draw` hook, reviewed binding identifies current colortex0 side. Change packed R11G11B10 scene target to RGBA16F and copy into input-sized HDR target. Aligned scene depth and render-pixel previous-minus-current motion feed SR. Linear input uses pre-exposure 1 and vendor auto exposure. |
| DLSS/FSR | Independent display-sized RGBA16F output. DLSS HDR + auto-exposure create flags; FSR HDR/auto-exposure create flags and `rx_fsr_evaluate_v2` reactive/composition ABI. Feature recreation tracks pack epoch, format and dimensions. Native ABI null/non-null mask calls checked offline. |
| Composite5 | Display-size output/viewport and viewWidth/viewHeight; reconstructed HDR colortex0. Bloom atlas stays input-size with its own texture dimensions. Low-resolution auxiliary depth/data texel reads use their actual sizes. Bloom, exposure, Complementary tone mapping and grading occur once here. |
| Composite6/7/final | Separate display-sized post attachments, tracked by original view/side. Published writes feed subsequent samplers. Exact-source final full-scene TAA, FXAA and sharpening bypasses retain pack effect sampling and one Reflexium jitter owner. Unknown sources keep fallback. |
| Completed scene / FG / HUD | Pack final display image restored before vanilla post effects. FG receives completed post-effect color through existing completion path. HUD remains display-sized. |

Exact archive, pack version, source path/hash and Vitrail ABI guards restrict the adapter. Unknown/changed archives do not receive the HDR or motion source edits. Original shader archive and saved pack options are not rewritten. World/upstream histories never receive display-size viewport values. Owned post targets are released with feature resources after GPU completion. The pack's retained stochastic sampling branches are not proof of a complete independent lighting denoiser.

### Surface coverage

| Surface | Implemented strategy | Limits |
| --- | --- | --- |
| Opaque entities/block entities and solid hand | Existing CPU previous-geometry replay and depth/hand matching preserved. | Topology changes reset; independently animated block-entity/hand pixels not certified here. |
| Terrain cutouts and waving foliage | Actual pack deformation evaluated with current and previous time, rain, snow, eye-light and camera state; current/previous actual projections and jitter subtraction. Separate non-blended surface attachment merged only at matching visible depth. | Mutable voxel-dependent vine state has no historical snapshot and is explicitly invalidated. Dense canopy light-map interpolation limits the independent approximate checker. |
| Pack water / transparent blocks | Separate nearest raster contribution/depth/motion. Only an opaque contribution may replace visible depth/vector; blended layers retain background motion and provide material-alpha reactive/composition inputs to FSR. | One velocity cannot represent multiple blended layers. This does not certify OIT, refraction, particles or arbitrary translucent entities. |
| Modern DLSS transparency | Background motion retained for blended layers; no FSR mask passed as a fake DLSS reactive input. | K/L/M have no equivalent legacy current-color-bias input; remaining blended-layer quality is unresolved. |
| Particles / translucent entities / translucent hand | Conservative existing fallback. | Independent vectors and comprehensive composition coverage remain unfinished engineering, not an external blocker. |

One discovered root cause was FP16 surface-depth quantization: the former D32-scale equality tolerance rejected the matching surface. The merge now allows binary16 rounding plus conversion error. The analysis gate also requires nonzero measured motion and improvement over a zero-vector control, so the earlier camera-only capture no longer passes just because its residual is below a quarter pixel.

### Numerical and visual evidence

`pipeline-evidence-2026-10-15/isolated-leaf-{2,3,4}.json` and their images retain same-frame input/output/depth/motion. Run from the repo root:

```powershell
java tools/CaptureAnalysis.java pack-leaf-check docs/validation/pipeline-evidence-2026-10-15/isolated-leaf-2.json isolated
```

The controlled fixture is one persistent oak leaf block at (-43,137,24), with spectator camera (-42.5,136,28.5), default wave settings and open sky. Independent reconstruction solves undeformed world position, reevaluates the previous pack wave and projects it. It approximates fragment interpolation; it is not an exact vertex/material oracle.

| Capture | Samples | Nonzero expected | Median residual px | p99 px | Within 0.25 px |
| --- | ---: | ---: | ---: | ---: | ---: |
| isolated-leaf-2 | 172 | 171 | 0.0348 | 0.1622 | 100% |
| isolated-leaf-3 | 178 | 176 | 0.0163 | 0.0860 | 100% |
| isolated-leaf-4 | 171 | 171 | 0.0196 | 0.0652 | 100% |

Negative control `run/screenshots/pipeline/leaf-resume-2.json` (before the merge fix) now fails despite 100% of errors being below 0.25 px: measured mean 0.00154 px versus expected 0.09865 px. Dense cluster capture `leaf-final-current` fails the numerical gate (92.54% within 0.25 px); keep it failed, not selected away. It mixes interior lighting assumptions the isolated fixture avoids. Exact dense-canopy/water correspondences remain open.

Opened native, retained-jar, DLSS and FSR stills and selected turn/hand sequence frames. The scene-wide blur is absent in candidate images; thin ladder and foliage detail remains softer than native. Initial creative-mode captures changed camera height by falling and changed held item between native and candidate, so they are functional smoke only, not quantitative paired quality evidence. The later `build/validation/pipeline-matched` spectator series holds camera height; animation phase and exposure history still require controls before claiming a motion-quality gain. No native equivalence, zero ghosting, stable bright/dark adaptation or complete denoising acceptance is claimed. Retained image filenames and SHA-256 values are in `pipeline-evidence-2026-10-15/manifest.json`.

For the matched static wall crop (270,90,330,230 at 960x540), native-reference detail correlation is 0.8709 retained jar, 0.8608 candidate DLSS and 0.8655 candidate FSR; MAE is 3.094, 3.553 and 3.184 respectively. Laplacian is 10.330, 12.168 and 14.049 versus native 14.506. These observations do **not** establish a quality improvement over the retained jar. Shader wave phase is not controlled, so this metric is restricted to the static wall; it does not certify foliage or motion. Both retained and candidate images were opened for inspection.

The final retained-jar visual client produced `crash-2026-10-15_22.45.57-client.txt` after main shutdown: `Client shutdown from post-main`, with only the non-daemon `HTTP-Dispatcher` holding the JVM open. This matches the documented harness HTTP-server lifetime limitation, not a rendering stack. All owned game processes have exited. Clean authenticated shutdown remains an acceptance limitation; the watchdog report is retained under ignored `run/crash-reports/`.

HDR readback is explicitly RGB log2(1+linear)/16 in UNORM8, decoded by the analysis tool. Motion is RGB-packed 12-bit XY at 1/32 render-pixel; depth is RGB UNORM24 reverse-Z. These are diagnostic encodings, not raw FP16 archives. Both HDR previews use the same Reinhard/sRGB transform. Precision/clipping limits are explicit; raw linear FP16 export remains absent.

| Remaining quality acceptance | Status |
| --- | --- |
| Stationary animation, turn/strafe, hand, cutouts, water/particles | Captured functional sequences; isolated foliage numerical check only. Full matched temporal acceptance remains open. |
| Detail/trailing/shimmer/noise together | No accepted combined quantitative before/after verdict. Laplacian alone is not used as proof. |
| Bright/dark exposure transitions, clipping and double tone mapping | Stage ownership audited; full controlled dynamic exposure/lighting tests unfinished. |
| Optional-dependency fallback and all pack variants | Exact-source/version rejection checked offline; shaders-off runtime smoke captured. Separate no-Sodium/no-Vitrail process and other pack variants untested. |
| Full quality promotion | Not accepted; HDR/motion remains opt-in. This is unfinished validation/coverage engineering, not a claim that external API access prevents it. |

## Benchmark budget, publication and handoff

Total new benchmark wall-clock spent: **0 seconds**. Remaining shared limit: **480 seconds**, with any future benchmark required to stop strictly before the limit including shutdown. Haiku 5.5's initial audit delegation failed with `Claude API rate limit reached`; its resumed read-only audit found zero original Opus benchmark launches. This continuation ran only offline checks and image/lifecycle smoke, with no BM performance capture, profiling or latency measurement. No substitute model ran a benchmark. Do not spend the remaining budget until candidate quality gates pass.

FPS Lab publication is validation-only, not a Quick measurement: archive `pipeline-validation-20261015.json`, both sets with unverified rows, no FPS/gain fields. Existing history preserved. Only `upscaling.json` uploaded to incoming then atomically renamed; HTTPS bytes match local SHA-256 `83F1EA8B7F30FF33AB3156FD4165362D3868A2A6F2C370F5CA6FA8D31564FCFF`. The shared browser displayed the acceptance-pending note and no-number rows at [FPS Lab](https://mctest.bambooz.dev/#upscaling). No unrelated site data, session credentials or raw private logs were published.

The Iris research report is corrected for the supplied experimental native Vulkan artifact, SHA-256 `f16cc85cfb253ac0a0ca0f08fda3363842ec870e21e6240f6fa5c2ae02032ade`. Its integration hooks are candidates, not tested compatibility; no renderer replacement was made.

Next review should prioritize the numerical interpolation limits, downstream pack option coverage, particle/translucent composition gaps, failed pacing transitions and actual fullscreen lifecycle. Then complete controlled native/retained/candidate motion and exposure tests before one bounded Haiku Quick comparison. Stage 2 still needs a supported correlated trace before any scheduling correction. Full performance and physical scanout acceptance remain outside this pass.
