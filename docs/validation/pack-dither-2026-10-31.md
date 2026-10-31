# Complementary effect dithering, 2026-10-31

Worker B, baseline b96b20c. Shared ai-brain was read only. No real profile was launched or changed. ComplementaryHdr and ComplementaryMotionSource are unchanged.

## Source finding

Confirmed in the copied `ComplementaryReimagined_r5.9.3 + EuphoriaPatches_1.10.5/shaders`:

- `lib/common.glsl:606-608` defines TAA when TAA_DEFINE is 1; `630-633` enables its automatic mode at sufficient detail quality.
- `program/gbuffers_terrain.glsl:331-334`: Bayer64 screen-space noise only advances by frameCounter under TAA. This dither feeds lighting/shadow sampling.
- `program/deferred1.glsl:165-170,371-374`: TAA controls frame-dependent trace offsets and noisetex dither, including SSAO.
- `program/composite1.glsl:173-176`: TAA controls temporal fog sampling.
- Baseline IrisCompat.adapt accepts only the exact base Complementary name or BSL; Euphoria therefore falls through to temporalOptionOverrides, disabling TAA and TAA_DEFINE along with jitter and filters.

That establishes the frozen-sampling mechanism by source inspection. Its contribution to Ryan's exact visual report needs the live comparison below. At 853x480 to 2560x1440 each input pixel covers approximately 3x3 display pixels.

## Gate and ownership

Kept exact-source hashes plus the existing unique exact-line check. Added only the Euphoria 1.10.5 folder name and hashes, rather than accepting arbitrary family forks. This avoids bypassing an unknown temporal implementation because it happens to contain the same call. Base r5.9.3 remains supported. Unknown or modified resolve files retain the fallback option-based TAA disable, including the base pack now if its resolve is unreviewed.

Vitrail reader SHA-256 convention: UTF-8, CRLF normalized to LF, join split lines with LF, preserve final empty line:

| Euphoria file | SHA-256 |
| --- | --- |
| program/composite6.glsl | d14e6985efa147b588e19a87d82d8aa02d27e4b6af92c4a43c5a22affde8ebd6 |
| program/composite7.glsl | bc789de027f0c3c76c09a8eebe84d0a272aa55eb00a4d35083555ee5275f3ad0 |
| program/final.glsl | f33a40aedbca1341dd07c67e730d2e2290fac96beba94d7524171c51e398f969 |
| lib/antialiasing/jitter.glsl | 60872f102a808d3f5a6e8a5c0391bb1fb149bb08abb262047cb27e05577d877b |

Once the reviewed resolve edit applies, preserve saved TAA and TAA_DEFINE so effect dithers can animate. TAA_JITTER remains 0, the reviewed jitter source is bypassed, FXAA remains off, and IMAGE_SHARPENING remains 0. The full-scene DoTAA call becomes temp = color. IrisCompat.temporalVerified remains true with both jitter and resolve source edits applied. Native compilation preserves saved options. This does not force-enable TAA against a user's explicit saved disable.

Installed Iris SHA-256: 566BD7515CC1CB41320A6761932093C1C3EE813FC8CBFD2077E3D128746DC1C2. `javap -c -p` of ShaderPack constructor: IncludeGraph constructor at bytecode 395, ShaderPackOptions constructor at 485. IncludeGraph constructor calls readFile synchronously at 209. IncludeGraphMixin adapts at readFile RETURN. PackOptionsMixin overrides MutableOptionValues constructor arguments within ShaderPackOptions. Therefore the applied flag exists before option overrides. IrisCompatCheck also asserts the ShaderPack constructor ordering against the installed jar.

## Quick verification

Build command: `gradlew.bat build -x installReflexium --no-daemon --console=plain`.

Final build: BUILD SUCCESSFUL in 14s, 24 actionable tasks: 20 executed, 4 up-to-date. Iris compatibility checks passed, including Euphoria exact sources, modified-source rejection, unknown resolve fallback, effect options preserved only after bypass, native options unchanged, saved options unchanged, and pack reload evidence reset. Other registered checks passed. Build log will be retained beside this document.

Runtime completed on the requested primary PG27AQDP, 2560x1440, borderless=true, exclusive_fullscreen=false, foreground=true, NVIDIA RTX 5070 / 617.42. Exact Iris hash matched. Baseline jar built from b96b20c: 4BCACBEEAFD91483E92917FB0E09735271F84F5EA765F1BFDC06ED60D1F51526. Fixed jar: 3E64FBB89B70D37F372E9EA12448CE2FA9179E7797B8A6B2A3B2A3A871DB83D2. Launcher session 25721 used rxBenchJar to exclude current project outputs and run the baseline artifact. Session 25723 ran the fixed project outputs. Session 25724 supplied the final native reference. All three clients exited; foreground restoration returned true to HWND 1446568 after each, and the GPU lock was released only after the owned PID disappeared. No build ran during an owned client session.

All captures use x=399.1, y=161.2, z=36.1, yaw=150, pitch=12, the copied world's smooth-sandstone wall/corner fixture, clear weather, time 9000 with advance_time=false. Before/after both use transformer, requested preset 12, Ultra Performance (853x480 to 2560x1440), MFG 3x. Generated presents advanced during both sequences. Native uses upscaler=off, FG=off, TAA_DEFINE=1 and default TAA_JITTER=1. `native-pack-options.properties` retains the last saved pack options, where default jitter=1 is omitted. The pack source's default is 1 at lib/common.glsl:128.

Runtime log evidence in `pack-dither-2026-10-31/after-client.log`: all four reviewed source edits applied. The resulting option line is exactly `[IMAGE_SHARPENING=0, FXAA_DEFINE=-1, TAA_JITTER=0]`. TAA and TAA_DEFINE are absent. `after-status.json` publishes resolve_bypass_applied=true and the edit paths. `before-status.json` has `[TAA, IMAGE_SHARPENING=0, FXAA_DEFINE=-1, TAA_JITTER=0, TAA_DEFINE=0]` and no resolve bypass. Iris reports Reflexium jitter/verified on the fixed path; IrisCompatCheck independently asserts temporalVerified(). Production implementation points: IrisCompat.java:306,348,357,364; ComplementaryTemporalSource.java:22,47.

Each ROI is 256x256 display pixels. AO ROI origin=(940,500); flat shaded face ROI origin=(1450,550). Luma is Rec.709 weighted 8-bit RGB without linearization. Temporal SD is the mean per-pixel population standard deviation across eight screenshot samples. Spatial high pass is pixel minus four-neighbor mean. Residual RMS compares that high pass in the first frame with native's first frame. These are descriptive image metrics, not a perceptual score.

| ROI / mode | Mean temporal SD | High-pass residual RMS vs native | High-pass RMS |
| --- | ---: | ---: | ---: |
| AO native | 0.26973 | 0.00000 | 0.89463 |
| AO baseline | 0.22750 | 1.15597 | 0.76471 |
| AO fixed | 0.36819 | 0.96556 | 0.44039 |
| Face native | 0.01096 | 0.00000 | 0.80181 |
| Face baseline | 0.04193 | 0.83370 | 0.25693 |
| Face fixed | 0.05012 | 0.83478 | 0.25711 |

The AO stipple is visibly reduced in the first-frame crops. AO residual vs native falls 16.5%, and AO high-frequency RMS falls 42.4%. Temporal SD increases by 0.14069/255 instead of decreasing: expected for animated sampling replacing a frozen spatial pattern, but this is a measured tradeoff, not proof of perfect temporal stability. No obvious large boiling is apparent in these samples; subtle moving grain remains possible. The flat-face ROI shows no material benefit, so this run does not establish elimination of every surface/shadow artifact. The selected corner is shadowed, with differently lit faces, rather than an isolated sunlight penumbra sweep.

Evidence paths relative to docs/validation/pack-dither-2026-10-31:

- before-ao-crop.png, after-ao-crop.png, native-ao-crop.png.
- before-shadow-crop.png, after-shadow-crop.png, native-shadow-crop.png.
- before-full.png, after-full.png, native-full.png retain the first full viewport.
- roi-ao/{before,after,native}/frame_000.png through frame_007.png and roi-shadow equivalents retain all measured pixels.
- before-sequence.json, after-sequence.json, native-sequence.json retain per-sample status and camera evidence. Full-frame raw sequences remain locally in the gitignored run/pack-dither-raw/{before,after,native} directories.
- metrics.txt and tools/PackDitherMetrics.java provide reproducible numbers. Recheck with `java tools/PackDitherMetrics.java docs/validation/pack-dither-2026-10-31/roi-ao 0 0 256 256 ao`, and likewise roi-shadow / shadow. The tool also emits crops.

Capture limitations: requested 50 ms sampling interval did not yield consecutive rendered frames. Actual before render-frame indices are 10134,10198,10271,10344,10416,10490,10563,10634; after indices are 11836,11913,11987,12059,12138,12218,12294,12366; native indices are 1037,1269,1492,1684,1868,2046,2223,2399. Camera pose stays identical across all eight samples per mode. Metrics measure eight sparse samples over a few seconds and exclude generated frames. Short video/true consecutive-frame stability remains unverified.

Two capture corrections were necessary. The copied pack initially had TAA=false, TAA_DEFINE=0, TAA_JITTER=0. Setting them in the copied run before baseline launch did not survive Iris's subsequent pack-option rewrite, so the initial native and fixed samples were invalid and replaced. Restored TAA_DEFINE=1, reloaded and rejoined before fixed capture, then used a fresh native launch with jitter default=1. The observed file rewrite contradicts the existing log text claiming saved options unchanged; this task does not diagnose or repair that separate persistence behavior. Existing installations whose saved TAA_DEFINE is already 0 need it reset to 1 or automatic mode to animate samples. Also, in-world reload temporarily left only sky visible until rejoining the copied world. Both issues are test-environment observations, not attributed to this patch without a separate control.

Unverified: Ryan's actual world/pose; all pack options/profiles beyond this copied configuration; arbitrary Euphoria/family versions or modified source; a dedicated sunlight shadow-edge test; motion and disocclusion; true consecutive rendered-frame or generated-frame video stability; long-duration boiling; performance impact; Meshelium terrain rendering; Mellow live behavior. No real profile was launched, modified or installed into.

## Mellow source-only finding

Mellow v3.4.1a has the same risk, unmodified in this task. `shaders/lib/noise.glsl:39-47` selects ign(Pos,true) only for TAA_MODE != 0; otherwise ign(Pos,false). `32-34` adds frameCounter only for Animate=true. These are interleaved-gradient noise samples, despite a comment describing Bayer noise. Expanded `program/deferred1.fsh:1457` obtains this dither and `1491,1494` passes it to SSAO. `global/ssao.glsl:21-25` rotates its sample disk with Dither. `global/lighting/shadows.glsl:30-42,87` uses Dither to rotate the PCF disk. Expanded `program/gbuffers_terrain.fsh:1757,1770` obtains the same dither and feeds tweak_lightmap, with its PCF call at 965. Thus TAA_MODE=0 freezes AO and shadow sample rotations. No live Mellow test or fix was attempted.

## Meshelium boundary

Copied runtime includes Sodium 0.9.2 and no Meshelium. The patch operates on Iris shader sources and option parsing, not terrain submission hooks. It should preserve the same dither guards if Meshelium consumes those Iris sources, but Meshelium's actual source integration and terrain execution are not established by this task. That compatibility remains for worker C.

## Overnight additions

- Repeat matched close-up scenes at Quality, Performance and Ultra Performance, with and without MFG.
- Add slow camera pans, camera cuts, moving sunlight and AO/shadow motion to check boiling and history rejection.
- Repeat with Meshelium 1.6.2 and Sodium 0.9.2 after worker C's integration.
- Investigate Mellow's static dither path separately with matched source and live tests.



## Follow-up: prevent persisted compile overrides

Lead review identified the saved-option rewrite as a Reflexium bug, superseding the earlier unresolved attribution. The installed Iris loadExternalShaderpack constructs ShaderPack using the incoming user values, obtains effective optionValues.mutableCopy, converts its boolean and string entries to Properties, and invokes tryUpdateConfigPropertiesFile. Before this follow-up, compile overrides therefore became saved values on every load. Evidence: persistence/save-path-bytecode.txt and the read-only real-profile file copied to persistence/before.properties (mtime 2026-10-31 03:35:02).

IrisLoadMixin now sanitizes Properties at the HEAD of the shared tryUpdateConfigPropertiesFile writer. ShaderPackScreen.applyChanges and Done use IrisApiConfig.setShadersEnabledAndApply, which reloads the pack and reaches the same writer. HeaderEntry Export reads that sanitized saved file; it does not extract effective options. IrisConfig.save writes global Iris configuration, not pack option values. The remaining Properties writers found in IdMap and OrderBackedProperties do not serialize ShaderPackOptions. No separate screen save hook is needed.

IrisOptionPersistence records the pre-override user values under gameDirectory/reflexium/iris-options/<SHA-256 of pack name>.properties before compilation can proceed. override.<key> records the affected key/signature, original.<key> records a present user value, and missing original.<key> means the user had no entry. Writes replace a temporary record atomically where supported. An I/O failure aborts before overrides are returned. At the shared Iris writer, each protected key is restored to its original value or removed if originally absent; unrelated keys stay unchanged. Native loads use the same boundary so repair works even if DLSS is initially off.

For an unrecorded file, repair requires every key in the pack's full option override set to have its forced value. A complete match drops those keys to defaults and logs the pack and repaired keys once per process. Partial matches are unchanged. If a record exists, a complete signature restores its exact recorded originals instead. Screen-queued changes and Reset are captured before Iris clears the queue; these explicit user actions are not treated as a legacy signature, even if they deliberately select all disabled values. Each pack load clears the previous save snapshot.

The compile log now says original user values are restored at pack option save. The earlier text claiming saved options unchanged has been removed. Tests cover complete legacy repair, partial-match preservation, exact recorded restoration including absent entries, unrelated options, reloads, queued user edits, deliberate queued all-off settings, and snapshot reset.

Follow-up final build: BUILD SUCCESSFUL in 14s, 24 actionable tasks: 20 executed, 4 up-to-date. All registered checks passed, including Iris user option persistence checks. Built artifact SHA-256 2DE80928EE4BA81BE135BE8ABE0F594CB8F43C269B429524789423A2F773A0B5. Live client used this worktree's matching project outputs with the exact Iris snapshot already verified above.

Live follow-up used one client, PID 203252, harness 25725/debug 25825, on the primary PG27AQDP at 2560x1440. loaded-status.json verifies borderless=true, exclusive_fullscreen=false, foreground=true. The GPU lock was held for the complete session, no build ran while it was alive, authenticated quit closed it, process exit was checked, foreground restoration returned true to HWND 1446568, and the lock was released afterward.

Input was a direct copy of Ryan's real leaked file with no pre-existing recovery record. Before values (excluding the timestamp comment):

```properties
AAA_THIS_IS_A_EUPHORIA_PATCHES_SETTINGS_FILE=true
AAB_FOR_EUPHORIA_PATCHES_VERSION_1_10_5=true
FXAA_DEFINE=-1
IMAGE_SHARPENING=0
TAA=false
TAA_DEFINE=0
TAA_JITTER=0
```

After the first load, after a DLSS reload, and after the native reload, contents excluding timestamp comments were:

```properties
AAA_THIS_IS_A_EUPHORIA_PATCHES_SETTINGS_FILE=true
```

The five override keys are all absent. Iris's normal option serialization also drops the default-valued AAB version marker. The migration log appears exactly once: repaired leaked Reflexium pack options [FXAA_DEFINE, IMAGE_SHARPENING, TAA, TAA_DEFINE, TAA_JITTER] using pack defaults. The recovery-record.properties copy contains override.<key> entries with no original.<key> entries, correctly recording absence.

Debug-only logging resolved effective values from the compile map and Iris option defaults:

| Compile | TAA | TAA_DEFINE | TAA_JITTER | FXAA_DEFINE | IMAGE_SHARPENING |
| --- | --- | --- | --- | --- | --- |
| DLSS first load and reload | true | -1 | 0 | -1 | 0 |
| Native load and native reload | true | -1 | 1 | 1 | 5 |

Automatic TAA_DEFINE=-1 activates TAA at the pack's default detail quality. The reviewed resolve/jitter source edits applied on DLSS; native has no source edits or option overrides. This follow-up supersedes the earlier warning that Ryan must manually reset saved TAA_DEFINE=0: the full leaked signature is now repaired automatically on a fixed-version load.

Screen Apply was exercised through Pause, Options, Sodium Video Settings, Iris Shader Packs, Shader Pack Settings. Changing Visual Style from Reimagined to Unbound queued SHADER_STYLE=4. Clicking the actual Apply button triggered pack recompilation at 08:10:51, persisted SHADER_STYLE=4, and left all five protected keys absent. Then the same screen restored Reimagined/default SHADER_STYLE=1 and applied again, removing that entry. Done returned to video settings. Evidence: screen-before-apply.png, screen-after-apply.png, their status/property snapshots, and the effective compile log at 08:10:51. No screen reflection shortcut was used.

Native sky-only control: native.png shows terrain before reload, native-reload.png shows only sky after a same-pack in-world reload with upscaler=off. native-reload-status.json confirms native resolution, the identical camera pose and pack, no source edits, and no Reflexium error. Thus the behavior reproduces with DLSS off; it was not fixed or attributed further.

Evidence is under pack-dither-2026-10-31/persistence: before.properties, loaded.properties, reload.properties, screen-after-apply.properties, native.properties, native-reload.properties, matching status/screenshot files, client.log, build.log, recovery-record.properties, focus-restored.json, and save-path-bytecode.txt. tools/pack-dither-persistence.ps1 reproduces the scripted load/reload/native checks. IrisCompatCheck covers exact recorded recovery, partial signatures, absence, unrelated keys and queued all-off intent without a game.

Remaining unverified for persistence: live partial-signature and nondefault-original recovery beyond the runnable checks; live screen Reset, Import and Export (their save/read routes were inspected); other packs and Iris versions; disk-full/permission-failure behavior beyond code inspection; restart recovery beyond the filesystem-backed check; interactions with Meshelium; and the cause of native sky-only reload. The real profile remains unchanged and was never launched. No push or merge was performed.
