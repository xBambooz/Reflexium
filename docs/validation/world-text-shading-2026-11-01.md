# Deferred world text shading, 2026-11-01

No rendering fix is shipped. The color mismatch is reproduced and its material path is identified, but no generic correction was validated safely. The goal remains unmet. Existing sharpness, main-storage filtering, sign-before-hudless ordering, name-tag UI ordering and shaderless rendering are unchanged. There is no fix commit hash; the evidence commit contains this report and the capture driver.

## Cause

Verified against the local Complementary Reimagined r5.9.3 + Euphoria Patches 1.10.5 fixture and Iris snapshot SHA-256 `566BD7515CC1CB41320A6761932093C1C3EE813FC8CBFD2077E3D128746DC1C2`.

Iris `ShaderKey.TEXT_BE` and `TEXT_INTENSITY_BE` select `ProgramId.BlockTrans`, which names `gbuffers_block_translucent`. That pack wrapper defines GBUFFERS_BLOCK and includes `program/gbuffers_block.glsl`. This is block-entity text, not the particle `gbuffers_textured` path. Ordinary entity text uses EntitiesTrans. The bytecode excerpts are in the evidence directory.

In `gbuffers_block.glsl:228`, `isSign(blockEntityId)` selects `lib/materials/specificMaterials/others/signText.glsl` when the vertex color or fullbright light coordinate identifies text. This material sets the normal to upVec, disables highlights and direct shadow contribution, and:

- Normal text multiplies RGB by **5** before `DoLighting`.
- Glowing text with `lmCoord.x > 0.99` clears the modified light coordinates, sets emission to 1, and multiplies RGB by `length(color.rgb) + 0.5`.

The common path then applies the emission multiplier and `DoLighting` with sky/block lighting and material flags. `mainLighting.glsl:863` combines block, scene, minimum and emissive lighting before multiplying the albedo. Composite1 converts the scene with `pow(color, vec3(2.2))`; composite5 performs color adjustments and the selected tonemap. `lib/colors/tonemaps.glsl` includes exposure, nonlinear curves and output encoding. Fog and other enabled composite effects also precede final output. These are verified source operations, not a claim that every optional feature was active. Source hashes are recorded.

`DeferredWorldText.drawLayer` instead replays the original text Submit nodes through vanilla feature rendering into RGBA8_UNORM after the pack has finished. Minecraft 26.3 `assets/minecraft/shaders/core/text.vsh` multiplies vertex color by the vanilla lightmap for depth-tested world text; `text.fsh` multiplies atlas, vertex color and ColorModulator and applies vanilla fog. It does not apply the pack sign material, pack light colors or tonemap. `world_text_composite.fsh` copies the premultiplied layer unchanged into the display-referred scene. RGBA8_UNORM does not add an sRGB transfer. The capture metadata explicitly identifies the finished input as RGBA8 display-referred with pre-exposure 1.

Therefore this is both a material/lighting omission and a missing pack color transform. A single gamma conversion does not fix it: any ordinary transfer keeps white at white, whereas native glowing cores are about 217 to 222 rather than 255. The night normal glyphs reach zero in the deferred layer, so multiplying those final pixels cannot restore the native nonzero result.

Before research, searched Modrinth for comparable upscaling mods and read [Super Resolution's display-size HandRenderTarget](https://github.com/IReallyWantToSleep/superresolution/blob/8c91dbb912f80014bd3a2279e93812e9dc2b0369/modules/common/src/main/java/io/homo/superresolution/common/minecraft/HandRenderTarget.java). It confirms the separate-layer pattern but provides no pack text color calibration. No reference implementation was copied. RTX Remix and Radiance were not needed or run.

## Options assessed

1. Correct encoding plus pack lighting: encoding is necessary if replaying a linear material path, but insufficient here. Reproducing just the factor 5 skips emissive classification, colored lighting, exposure and nonlinear tonemapping. A pack adapter would need the original material inputs and the active pack transform, not a global multiplier. No partial adapter was shipped.
2. Resolved neighborhood sampling: the sign board is a different albedo/material, with its own lighting and possible highlights. Its display RGB does not uniquely identify glyph irradiance, emissive response or tonemap. An occluder, board edge or colored lamp can dominate a sample. This was rejected as an unvalidated estimator, not experimentally proved impossible.
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
