# Deferred world text shading, 2026-11-01

Latest disposition: experimental neutral separate-white correction implemented in **`6954841`**, behind `world_text_pack_levels`, **default off**. Live luma improves in every measured group, but raw Laplacian variance falls, failing criterion 3. Shaderless pixel identity was not established by the attempted control. No default-on change, coefficient refit, push or merge. Details are in the live trial section below.

Offline history: [neutral lift under the revised gate](world-text-neutral-levels-2026-11-01.md) failed. The lead subsequently authorized the fixed neutral separate-white model for a live trial without describing the offline result as a pass.

The following baseline investigation predates the opt-in implementation. Its before measurements and conclusions about the offline gates remain historical evidence.

## Cause

Verified against the local Complementary Reimagined r5.9.3 + Euphoria Patches 1.10.5 fixture and Iris snapshot SHA-256 `566BD7515CC1CB41320A6761932093C1C3EE813FC8CBFD2077E3D128746DC1C2`.

Iris `ShaderKey.TEXT_BE` and `TEXT_INTENSITY_BE` select `ProgramId.BlockTrans`, which names `gbuffers_block_translucent`. That pack wrapper defines GBUFFERS_BLOCK and includes `program/gbuffers_block.glsl`. This is block-entity text, not the particle `gbuffers_textured` path. Ordinary entity text uses EntitiesTrans. The bytecode excerpts are in the evidence directory.

In `gbuffers_block.glsl:228`, `isSign(blockEntityId)` selects `lib/materials/specificMaterials/others/signText.glsl` when the vertex color or fullbright light coordinate identifies text. This material sets the normal to upVec, disables highlights and direct shadow contribution, and:

- Normal text multiplies RGB by **5** before `DoLighting`.
- Glowing text with `lmCoord.x > 0.99` clears the modified light coordinates, sets emission to 1, and multiplies RGB by `length(color.rgb) + 0.5`.

The common path then applies the emission multiplier and `DoLighting` with sky/block lighting and material flags. `mainLighting.glsl:863` combines block, scene, minimum and emissive lighting before multiplying the albedo. Composite1 converts the scene with `pow(color, vec3(2.2))`; composite5 performs color adjustments and the selected tonemap. `lib/colors/tonemaps.glsl` includes exposure, nonlinear curves and output encoding. Fog and other enabled composite effects also precede final output. These are verified source operations, not a claim that every optional feature was active. Source hashes are recorded.

`DeferredWorldText.drawLayer` instead replays the original text Submit nodes through vanilla feature rendering into RGBA8_UNORM after the pack has finished. Minecraft 26.3 `assets/minecraft/shaders/core/text.vsh` multiplies vertex color by the vanilla lightmap for depth-tested world text; `text.fsh` multiplies atlas, vertex color and ColorModulator and applies vanilla fog. It does not apply the pack sign material, pack light colors or tonemap. `world_text_composite.fsh` copies the premultiplied layer unchanged into the display-referred scene. RGBA8_UNORM does not add an sRGB transfer. The capture metadata explicitly identifies the finished input as RGBA8 display-referred with pre-exposure 1.

These paths are omitted, but this alone does not establish which omitted operation causes the dark lift. Review correction: vanilla black sign text has RGB zero, so the material's factor 5 and multiplicative diffuse lighting cannot raise it. Screen-space bloom is a plausible contributor: composite5 mixes seven bloom levels into scene color before tonemapping. The exact causal contribution has not been isolated. A single gamma conversion does not fix it: any ordinary transfer keeps white at white, whereas native glowing cores are about 217 to 222 rather than 255. The night normal glyphs reach zero in the deferred layer, so multiplying those final pixels cannot restore the native nonzero result.

Before research, searched Modrinth for comparable upscaling mods and read [Super Resolution's display-size HandRenderTarget](https://github.com/IReallyWantToSleep/superresolution/blob/8c91dbb912f80014bd3a2279e93812e9dc2b0369/modules/common/src/main/java/io/homo/superresolution/common/minecraft/HandRenderTarget.java). It confirms the separate-layer pattern but provides no pack text color calibration. No reference implementation was copied. RTX Remix and Radiance were not needed or run.

## Options assessed

1. Correct encoding plus pack lighting: encoding is necessary if replaying a linear material path, but insufficient here. Reproducing just the factor 5 skips emissive classification, colored lighting, exposure and nonlinear tonemapping. A pack adapter would need the original material inputs and the active pack transform, not a global multiplier. No partial adapter was shipped.
2. Resolved neighborhood sampling: the initial rejection was theoretical and insufficient. Lead review requested an offline test, now recorded in [world-text-levels-offline-2026-11-01.md](world-text-levels-offline-2026-11-01.md). All twelve tested model/window combinations fail the held-out 8-luma MAE bar. The best overall variant improves dark luma but misses glowing luma and dark shade hue. No GPU work or implementation followed that gate failure.
3. Per-frame pack calibration: suppressed text supplies no pack-rendered glyph samples. Leaving low-resolution text in the scene reintroduces the blobs and temporal history being avoided. Drawing additional native probes or a second pack pass would need new capture/composition machinery and a measured cost bound. A generic material probe remains a possible future design, not a proven solution. No GPU readback, duplicate pack rendering or speculative per-pack constants were added.

Unknown pack paths keep their existing fallback behavior. This investigation does not expand the supported-pack contract or silently disable sharp text for the supported fixture.

## Measurements

Evidence directory: `docs/validation/world-text-shading-2026-11-01/`. Baseline code: `1c488065`. No rendering code changed, so no after-fix measurement exists.

First reran `tools/WorldTextEvidence.java alpha-luma` against the original `current-light` full captures in worktree `t3-de619d04`. Original ROIs: dark `(1330,668,120,60)`, bright `(1120,668,120,60)`. The four JSON results reproduce the report exactly:

| Original 1440p state | Pixels | Native pack | Deferred before | After fix |
| --- | ---: | ---: | ---: | --- |
| Day dark | 642 | 51.2421 | 1.8318 | Not implemented |
| Shade dark | 642 | 43.3480 | 1.8318 | Not implemented |
| Day glowing | 626 | 221.5785 | 254.7874 | Not implemented |
| Shade glowing | 626 | 221.4718 | 254.7874 | Not implemented |

New short run: secondary XF250Q, windowed 1920x1080 at (2560,0), behind=true, no activation, master sound 0, no overlay. Euphoria HIGH profile, Iris snapshot above, DLSS Ultra Performance 640x360 to 1920x1080, transformer preset hint 12. Same fixture, camera `(200.5,151,20)`, yaw/pitch 0. Noon without roof, noon with stone roof `(194,157,22)` to `(207,157,35)`, midnight without roof. Tick freeze with 60 ticks stepped after lighting changes. Native means upscaler effective off, FG off; an allocated `upscaler_built=dlss` can remain in native status, but deferred node count is zero. Deferred captures have 24 nodes, evaluated DLSS frames and no renderer error.

ROIs scale to dark `(997,501,90,45)`, glowing `(840,501,90,45)`, hanging dark `(1005,382,68,41)`. Same alpha-luma command, using `STATE-deferred_world_text_alpha.png` as mask. Luma is on the 0 to 255 scale.

| New 1080p state | Pixels | Native pack | Deferred before | After fix |
| --- | ---: | ---: | ---: | --- |
| Day standing dark | 351 | 54.2676 | 1.8365 | Not implemented |
| Shade standing dark | 351 | 45.1578 | 1.8365 | Not implemented |
| Night standing dark | 351 | 24.3293 | 0.0000 | Not implemented |
| Day standing glowing | 371 | 218.0103 | 254.7874 | Not implemented |
| Shade standing glowing | 371 | 216.7928 | 254.7874 | Not implemented |
| Night standing glowing | 371 | 216.7412 | 253.0000 | Not implemented |
| Day hanging dark | 542 | 49.7139 | 2.0000 | Not implemented |
| Shade hanging dark | 542 | 38.0956 | 2.0000 | Not implemented |
| Night hanging dark | 542 | 16.9127 | 0.0000 | Not implemented |

Shaderless noon control: standing dark native/deferred **1.8365/1.8365**, glowing **254.7874/254.7874**, hanging dark **2/2**, same respective pixel counts. This confirms that the replay matches vanilla luma for this fixed pose; it is not a complete spatial or motion equivalence test.

The masks select deferred-covered pixels and classify dark/bright by deferred output. They can include native edge or board pixels when coverage differs. These means are not independently registered glyph-only ground truth. They reproduce the established diagnostic and strongly discriminate the brightness mismatch, not glyph completeness.

## Cost, FG and build

No runtime change: added shipping frame cost **0 ms**. No correction algorithm was benchmarked or accepted. Existing profiler capture `profile.json` covers 182 frozen-world frames at the 60 FPS cap: world CPU **1.4030 ms/frame**, evaluate CPU **0.0975 ms/frame**, display-depth CPU **0.0080 ms/frame**. All GPU sample counts are zero, so GPU cost is **unmeasured**, not zero. The profiler has no separate deferred-text span in this baseline. These are baseline diagnostic costs, not a before/after performance claim.

`fg3.png`, `fg3-crop.png` and status/capture JSON record requested 3x with two configured generated frames and 435 FG evaluations. Generated presents stayed **zero** in the deliberately nonfocusable window. This is a configured-3x real-frame capture only. Generated-frame visual acceptance is untested; focus was not stolen to obtain it.

`gradlew.bat build --no-daemon --console=plain -PreflexiumInstallDir=build/install` passed all checks in 21 seconds before clients ran. The install task was skipped because the isolated target did not exist; no profile installation occurred. Build output is retained as `build.log`. First launch 25911 is rejected: runtime DLLs downloaded only after Streamline initialization, leaving DLSS unavailable and capture timing out. Retried once on port 25913 after authenticated quit; DLSS evaluated successfully. Owned PID 26056 exited through authenticated quit and its GPU lock was released. No real-profile client was running at either acquisition/launch check. No real profile was launched, modified or terminated.

## Crops and remaining coverage

Human-inspected PNG pairs, relative to the evidence directory:

- `original-shade0-native-crop.png`, `original-shade0-ultra_performance-crop.png` and corresponding `original-shade1-*`: original 1440p daylight/shade.
- `day-native-crop.png`, `day-deferred-crop.png`.
- `shade-native-crop.png`, `shade-deferred-crop.png`.
- `night-native-crop.png`, `night-deferred-crop.png`.
- `shaderless-native-crop.png`, `shaderless-deferred-crop.png`.
- `fg3-crop.png`.

The new crops contain the normal standing sign on the right, glowing standing sign on the left, and normal hanging sign above. Full new native/deferred images, alpha masks, capture metadata and status are committed. Extra depth, motion and input/output captures remain locally in `run/world-text-shading-raw/`.

Not tested: a corrected renderer, glowing hanging text, colored dyes, other packs/dimensions/weather/biomes, movement, new name-tag comparisons, generated-frame appearance or correction GPU timing. No full matrix was run. A follow-up should first define a pack material/probe contract with a cost ceiling; this report does not establish one.

## Live fixed neutral-model trial

Lead authorization: try the all-1080p-standing fit from the original-resolution holdout, without tuning against live results. Implementation commit **6954841**. Final default is **off** because the fixed live criteria were not all met.

### Read-only option comparison

Read Ryan's real `ModrinthApp/profiles/Fabric 26.3/shaderpacks/ComplementaryReimagined_r5.9.3 + EuphoriaPatches_1.10.5.txt` before implementation. It contains only:

```properties
AAA_THIS_IS_A_EUPHORIA_PATCHES_SETTINGS_FILE=true
AAB_FOR_EUPHORIA_PATCHES_VERSION_1_10_5=true
```

The fixture used for the fits has no adjacent .txt overrides. Therefore no bloom, tone-map, exposure, grading or profile-value overrides differ; both use pack source defaults, with fixture runtime reporting HIGH. The real option file was never written. Its read-only SHA-256 and the built jar hash are in `live-levels/artifacts.json`; the option comparison is in `live-levels/options-comparison.txt`. No additional isolated option arm was needed.

### Implementation

`world_text_composite.fsh` applies the exact authorized coefficients in linear light:

`pred = (1 minus T) (0.384083 Y minus 0.554335 Y squared) + 0.707341 T`

T is the unpremultiplied deferred text RGB, decoded from display encoding. Y is Rec.709 luminance of a linear-light scene blur, clamped to `[0,0.3464359241]`. Tint is exactly zero. Coefficients were not adjusted after the live captures.

The blur uses sixteen fixed stratified taps over a box of radius 8 at 1080p, scaled by full output/render height and rounded, giving radius 11 at 1440p. Each fetched color is decoded before averaging. This is a fixed-tap approximation of the offline full box average, not an assertion that sixteen taps equal all 289 pixels of a 17 by 17 box. Covered glyph pixels alone run the blur. Coverage and premultiplied alpha composition are unchanged.

`DeferredWorldText` copies the resolved text-free scene once into a persistent display-size color snapshot immediately before the sign composite. This prevents a sampled-color/attachment feedback loop and prevents glyphs from entering their own blur. All signs share that snapshot and their existing single composite. There is no extra render pass per sign and no runtime readback. The copy and sampling variant run only when the switch is on, a pack is drawing, and the fitted source evidence is complete.

`WorldTextPackSource` observes five normalized LF source hashes through the existing Iris IncludeGraph exact-source adapter hook: sign material, block program, main lighting, composite5 and tonemaps. This does not select by folder name. New source evidence is cleared on pack load; incomplete or modified matched sources fail closed. Unit checks exercise a renamed exact fixture, incomplete source set, edited sign material and reset. Other pack sources and shaderless retain the original composite pipeline even if the switch is set to 1.

The diagnostic command is `world_text_pack_levels 0|1`. Observed status fields are `world_text_pack_levels` (requested), `world_text_pack_levels_source_verified`, and `world_text_pack_levels_applied`. All names remain on the original, uncorrected UI/name-layer composite. Main-storage-only capture, own-name filtering and sign-before-hudless ordering remain unchanged. `GameRendererMixin` still calls sign composition after scene reconstruction and before completeScene/FG hudless capture.

### Live setup and measurements

Secondary XF250Q, 1920x1080 window at (2560,0), behind=true, no activation, master sound 0. The lead's overlay was not modified. Snapshot Iris SHA-256 566BD751, Euphoria HIGH defaults, preset hint 12, Ultra Performance 640x360 to 1920x1080. FG was off for these color checks. No real-profile client was running at acquisition or launch checks.

Harness port 25921, owned PID 27664. `tools/world-text-levels-live.ps1` records native, switch-off before and switch-on after at each identical frozen camera pose `(200.5,151,20)`, yaw/pitch zero. Native/SR transitions settle 10 seconds; switch toggles settle one second. Day, stone-roof shade and midnight match the prior fixture. Added the missing glowing hanging sign at `(201,154,28)`, under a stone support. The normal hanging sign remains at `(199,154,28)`. Both sides carry the same text.

One common deferred-alpha mask from the day-before capture is used across these identical sign poses. Later frames collect two additional name nodes; the existing capture endpoint chooses the name layer when one exists and can therefore return an empty sign alpha. The sign transforms and camera do not change across these captures, and before-color thresholds still classify the common mask. This is a shared fixed-pose mask, not a claim that those later name-layer masks measure signs. Native coverage/filter differences remain as in the offline investigation.

Standing dark/glow ROIs: `(997,501,90,45)` / `(840,501,90,45)`. Hanging dark/glow: `(1005,382,68,41)` / `(840,382,90,45)`. Luma is display-referred, 0 to 255. Errors are per-pixel MAE against native, not differences between means.

| State | Text | Native mean | Before mean | After mean | Before MAE | After MAE |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Day | Standing dark | 54.66 | 1.84 | 54.02 | 52.82 | 5.35 |
| Shade | Standing dark | 46.13 | 1.84 | 42.11 | 44.29 | 6.39 |
| Night | Standing dark | 24.44 | 0.00 | 23.80 | 24.44 | 3.15 |
| Day | Hanging dark | 51.71 | 2.00 | 54.86 | 49.71 | 6.48 |
| Shade | Hanging dark | 40.19 | 2.00 | 38.11 | 38.19 | 4.71 |
| Night | Hanging dark | 17.00 | 0.00 | 17.59 | 17.00 | 2.22 |
| Day | Standing glow | 217.83 | 254.79 | 218.79 | 36.96 | 13.28 |
| Shade | Standing glow | 217.31 | 254.79 | 218.79 | 37.48 | 13.77 |
| Night | Standing glow | 216.59 | 253.00 | 217.00 | 36.45 | 13.91 |
| Day | Hanging glow | 222.29 | 254.79 | 218.79 | 32.50 | 10.66 |
| Shade | Hanging glow | 221.67 | 254.79 | 218.79 | 33.12 | 10.80 |
| Night | Hanging glow | 220.88 | 253.00 | 217.00 | 32.15 | 11.33 |
| Dark cave | Standing dark | 4.39 | 0.00 | 3.99 | 4.39 | 0.97 |
| Dark cave | Hanging dark | 5.51 | 0.00 | 4.50 | 5.51 | 1.49 |
| Dark cave | Standing glow | 216.29 | 253.00 | 217.00 | 36.75 | 14.14 |
| Dark cave | Hanging glow | 221.00 | 253.00 | 217.00 | 32.02 | 11.17 |

Every group improves in MAE. The cave is a sealed stone chamber around the same signs and pose, with its interior cleared and no interior light sources, at midnight. Normal text stays near black after correction. Native has a glow/bloom surround around the emissive signs; this post-pack model does not recreate that omitted bloom.

### Fixed live acceptance criteria

| Criterion | Result |
| --- | --- |
| 1. Closer to native in every measured group | Pass for all 12 day/shade/night groups and 4 cave groups by MAE. |
| 2. No added visible halo or outline | Pass in inspected native/before/after crops for day, shade, night and cave. No surrounding gray rectangle. Existing glyph coverage and outlines remain. |
| 3. Laplacian sharpness does not fall | **Fail using raw Laplacian variance on each glyph ROI.** All 16 rows decrease. No default-on change. |
| 4. Dark cave remains near zero without haze | Pass in inspected cave crops. Normal glyph means after are 3.99 and 4.50 out of 255, with no visible box/haze. |
| 5. Shaderless on/off is pixel identical | **Not established.** Same frozen pose, no walking, but both off/off and off/on captures differ. See below. |
| 6. Cost reported and no per-sign extra pass/readback | Correction frame cost **unmeasured**. One scene copy per sign layer, shared by all signs; no runtime readback or extra pass per sign. |

Laplacian metric: variance of the four-neighbor luma Laplacian over the interior of each exact ROI. Some illustrative native / before / after values:

| State and text | Native | Before | After |
| --- | ---: | ---: | ---: |
| Day standing dark | 1970.42 | 9177.56 | 2113.52 |
| Day hanging dark | 2148.24 | 16873.78 | 3200.07 |
| Shade standing dark | 1883.55 | 5354.31 | 1462.81 |
| Night standing dark | 335.40 | 2027.83 | 455.79 |
| Day standing glow | 6868.00 | 25619.21 | 15835.63 |
| Cave standing dark | 69.79 | 126.53 | 52.98 |

Raw Laplacian depends on contrast, so changing black toward gray reduces it even with unchanged glyph coverage. This is a limitation of that metric, not permission to reinterpret the fixed criterion as a pass. No normalized-metric substitution or coefficient tuning was used to enable the default. All raw values are in `live-levels/live-luma.csv`.

Shaderless control used off/on/off at the same frozen pose, with jitter disabled and no walks. Relative to the first off capture, the second off capture differs in **1,656,993 pixels**, maximum channel delta 174, while the on capture differs in **1,498,115 pixels**, maximum channel delta 129. Therefore the scene/reconstruction was not pixel stable independently of the switch, and this control cannot demonstrate a switch-caused regression or pixel identity. Shaderless status correctly reports source_verified=false and applied=false. No extra retest was run after the decisive criterion-3 failure. Default remains off regardless.

The 60 FPS cap and lack of a dedicated composite GPU span mean the captures do not isolate correction cost. No zero-ms claim is made for the enabled correction. There is one additional display-size GPU color copy per corrected sign layer/frame and sixteen scene taps per covered glyph fragment, not per-scene-pixel blur or per-sign passes. Profiling that cost remains necessary before considering a default change.

### Build, artifacts and remaining limits

`gradlew.bat build --no-daemon --console=plain -PreflexiumInstallDir=build/install` passed all checks in **13 seconds** after the exact-source tests were added and before launch. `installReflexium` was skipped because the isolated install destination does not exist. No profile was installed into. `live-levels/build.log` and `artifacts.json` preserve build proof and jar SHA-256. No CameraHistory, view-bob or motion-vector code was edited.

Owned authenticated quit succeeded; PID 27664 exited and the lock with owner world-text-shading-19c0 was released. Final status has switch=false, applied=false, no renderer error, OS foreground=false and OS input focus=false. No client or lock was left owned by this worker.

Paths under `docs/validation/world-text-shading-2026-11-01/live-levels/`:

- `day-{native,before,after}-crop.png`.
- `shade-{native,before,after}-crop.png`.
- `night-{native,before,after}-crop.png`.
- `cave-{native,before,after}-crop.png`.
- Full screenshots and per-arm status, `live-luma.csv`, `shaderless-pixel-comparison.csv`, the shared `day-before_world_text_alpha.png`, build/options/artifact records.

To reproduce the committed numerical table without GPU work:

```powershell
& "$env:JAVA_HOME/bin/java.exe" tools/WorldTextEvidence.java levels-live docs/validation/world-text-shading-2026-11-01/live-levels docs/validation/world-text-shading-2026-11-01/live-levels
```

Additional scene/depth/input captures remain locally in `run/text-level-live/`. The live driver is `tools/world-text-levels-live.ps1`, requiring the already-owned isolated harness session and GPU lock. It is not a launcher.

Not tested: live 1440p, other pack sources/options, colored dyes, motion, generated frames, a pixel-stable shaderless SR A/B, or enabled correction GPU timing. Name tags deliberately remain unchanged. The implementation stays available for review behind its default-off switch; this is not acceptance of a default-on fix.
