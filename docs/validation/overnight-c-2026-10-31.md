# Worker C overnight validation, 2026-10-31

Baseline: 907a143. Branch-local work only. Shared ai-brain read only, no push or merge. Integration owner: Claude lead.

## Item 1: optional Euphoria fixture

`IrisCompatCheck` skips the Euphoria source checks with a clear message when its default third-party fixture is absent. Supplying `-Preflexium.euphoriaFixture=<shaders directory>` makes that fixture required. Gradle forwards the property to assertion checks. Present fixtures still undergo every existing exact-source and option-ownership assertion.

Evidence in `overnight-c-2026-10-31/`:

- `build-fixture.log`: requested full build, all checks passed, 15 seconds, 25 actionable tasks.
- `build-absent.log`: requested full build after moving only this worktree's Euphoria directory aside, all checks passed, explicit skip message, 11 seconds.
- `explicit-missing.err.log`: deliberate negative test rejected `run/missing-euphoria-fixture` with the expected assertion.
- `explicit-present.log`: explicitly supplied real shaders directory passed, 7 seconds.
- `build-clean.err.log`: initial no-run-directory attempt failed at the pre-existing mandatory Iris ABI jar check before reaching Euphoria. No claim that a checkout without an Iris jar builds successfully. The requested Euphoria omission was independently checked after copying the exact Iris fixture.

Iris jar SHA-256: 566BD7515CC1CB41320A6761932093C1C3EE813FC8CBFD2077E3D128746DC1C2.

## Live work in progress

An isolated run directory was copied from t3-c5bf5f33, including its world, options, packs, Meshelium and Sodium. No real or Testing Instance profile was changed. At 08:21 EDT worker A acquired the shared GPU lock for IQ preparation/session. C has not launched a client or acquired that lock yet. C builds ran only while the foreign purpose was non-timed.

The mip default remains zero. No new live measurement or default recommendation is claimed yet. Items 2 through 7 remain pending. The prepared `tools/overnight-c-mip.ps1` is adapted from the existing mip runner, stores separate passes and enforces C ownership. It has not run live yet.

Preparation at 08:37 EDT: copied the real leaked pack-options file read only into `leaked-input.properties` and the isolated run. `leaked-input-hash.json` records its source SHA-256. The source timestamp remains 03:35:02 and its complete five-key override signature is present. This is an input fixture, not a recovery result. Both C PowerShell runners parse without syntax errors. The existing WGC capture tool built Release with zero warnings and errors. It has not captured a client yet. Worker A continues to hold the GPU lock; C remains queued.

Lead update at 08:49 EDT: the existing topmost, non-activating OLED overlay covers BOTH monitors. Leave it in place. Continue primary-monitor borderless fullscreen with the game focused underneath. Capture render targets or the owned window, never the monitor/desktop. Any FPS result must explicitly record the covered-window condition. C has not altered the overlay.

## First owned session, invalid for DLSS A/B

Lock acquired 08:58:30 EDT; harness 25781, client PID 197772. The copied run omitted the runtime cache, so startup installed DLSS 310.9.1 and required a restart. `invalid-runtime-status.json` proves configured DLSS had zero evaluations, inactive upscaling, native render dimensions, and the restart-required status. The first input capture timed out. No DLSS comparison from this session is accepted. Native sequences and the rejected first-arm status are under `invalid-first-mip/`; raw files remain under `run/overnight-c/invalid-first-mip/`. The runner now rejects inactive SR before requesting an input capture.

`session-borderless.json` records display 1, borderless true, exclusive false. The harness outer window width is 2561; the actual render target and PNG were independently checked as 2560x1440. Setting the outer width to 2560 produced a 2559-wide render target, so the outer width was restored. This geometry distinction is retained rather than claiming every reported window field equals 2560. The screenshot showed complete terrain, not the OLED overlay.

Authenticated ownership-checked quit completed. `exit-25781.json` confirms PID exit and successful foreground restoration. The controller released the GPU lock at 09:02:01. Worker A acquired it at 09:02:15; no C client remains. `first-client.log` retains startup/runtime and Meshelium diagnostics. This was not an FPS measurement. A restart is required before resuming item 2.

## Item 2: accepted capture session in progress

Second owned session: harness 25782, client PID 183628. `session2-start.json` verifies active DLSS Ultra Performance, 853x480 to 2560x1440, 4,852 evaluations at preflight, display 1, borderless true and exclusive false. The game is OS foreground underneath both OLED overlays. This is image-quality capture, not an FPS sample.

Pass 1 completed all 16 DLSS arms: shaderless and Euphoria, Quality and Ultra Performance, offsets 0,+1,+1,0. Each arm has 24 static and 24 full-turn samples, input-capture metadata, before/after status, and a 4-degree/100-tick pan. Native references have the same sequences. Files are under `overnight-c-2026-10-31/mip/`; full images and original sequence JSON are under `run/overnight-c/mip/`. Pass 2 started at 09:41 EDT and remains in progress. No stability or default conclusion is claimed before analysis.

Observed shaderless first-arm values: Quality renders 1707x960 and applies -1.5846808 bias (offset 0) and -0.5846808 (+1); Ultra renders 853x480 and applies -2.585526 at offset 0. All three observed u_BlockTex binds received the requested bias in these Meshelium-present arms. Quality offset-0 evaluation delta was 16,773 across capture, physical vertical FOV 1.3439034 radians (77 degrees), pre-exposure 1, capture history_reset=false. The first sampled pan yaw was 90.04 and the last 94.00009; the separate arm screenshot is at yaw 90. Thus the requested turn executed completely, with its first sequence sample after the first client tick. Do not describe sparse readback samples as consecutive rendered frames.

A live server query at 09:35:54 confirms advance_time=false and minecraft:day at tick 9000. Pack native terrain was visually inspected and complete. The copied leaked options file was repaired on first pack load to its AAA pack marker only (`after-first-pack.properties`), and the log reports default recovery. This is incidental first-load evidence, not completion of restart, recorded-nondefault, partial-signature or screen-operation checks.

## Resumed audit, 12:45 EDT

Both capture passes completed, but raw `capture.pack_drawing` rejects the eight `p0-*-pass2` arms as shaderless: the pack was drawing. Keep them as mislabeled observations, never pool them with shaderless data. Pass 1 p0 is shaderless and both p1 passes have the pack drawing. Cause of the unexpected pack state is unverified. The runner now stores input metadata before checking pack state and aborts on a mismatch; mode and pack changes are separated by waits. A replacement shaderless pass must use a new pass number to preserve rejected data.

`mip/arm-summary.json` records all 32 original arms, actual material bind counters, FOV, exposure, reset flag and evaluation deltas. All capture-time resets are false, exposure is 1 and FOV is 1.3439034 radians. Evaluation deltas range 8770 through 17727. This does not prove absence of resets between captures. Image registration and native detail metrics are still pending; no new default recommendation is accepted.

Session 25782 exited (PID 183628), foreground restoration succeeded and C released its lock (`exit-25782.json`). On resumption worker P holds a timed lock started 16:42:24Z. No C client, build or image analysis is running. Session controller PID 20292 queues atomic GPU acquisition every ten seconds for port 25783/debug 25883; it must retain ownership until its exact client exits. Next: validate fullscreen, replace shaderless pass with pass3, then pack dither with Meshelium and options persistence. Items 3 through 7 remain unverified. Prepared dither runner syntax passed, but has not run live.

## Session 3 and analysis audit, 12:57 EDT

Latest scheduling instruction: after current controller PID 140380 exits, P gets the next slot. Subsequent order is P, A, P, C, P, A. No C acquire-loop controller may remain after this session; a new C acquisition requires observing a P session start and end and honoring A's intervening turn. Only controller 140380 is active. Workload PID 214936 exits through authenticated quit in finally; controller restores foreground and releases the lock after client exit.

Session 25783 client PID 184512 reports display 1, borderless true, exclusive false, correct copied run directory. Preflight full build passed all 25 tasks in 10 seconds (`build-resume.log`, separate stderr log). Initial preflight attempts exposed PowerShell stderr termination and missing exit-code caching; controller now uses Start-Process -Wait with separate stdout/stderr. No client ran during those build attempts. Analysis compilation completed before this client launched. Subsequent analyzer calls use that compiled class.

Analysis reuses `CaptureAnalysis.java` rotation registration with physical FOV, yaw and pitch. Its decoder/rotation self-check passed. The earlier quick-run ROI (1435,1110,35,60) covers water in this dataset and is rejected. A first rock ROI at the far left leaves the image during the full turn and is also rejected. Accepted ROI is (710,934,18,25), visually inspected in native shaderless and pack captures: a small stone/block edge with no water or foliage. This small crop limits generalization. Registered residual is mean absolute luma change between sparse readbacks, not consecutive rendered frames. Correspondence does not remove pack exposure changes or texture animation elsewhere in the image.

`mip/registered-metrics.json` and CSV currently contain the 24 accepted original arms only (pass1 p0, passes1/2 p1). Rejected p0 pass2 is excluded. In the pack Ultra arms, mean offset0/+1 correlation is 0.49225/0.487675, Laplacian energy 3.62875/2.75825, static residual 0.354045/0.306508 and pan residual 0.457571/0.408827. Lower residual accompanies lower sharpness; this does not establish a universal default improvement. Default remains zero. Replacement shaderless pass3 is running and must be added before the final recommendation.

`after-restart.properties` contains only the Euphoria marker after restart, supporting persistence of default recovery. Recorded nondefault recovery, partial-signature refusal and screen Reset/Import/Export remain untested. No real profile was edited.

## Item 2 result: recommend retaining offset 0

The accepted matrix now has two independently settled passes per mode: shaderless passes 1 and 3, pack passes 1 and 2. Each pass has ABBA 0,+1,+1,0, two qualities, a native reference, 24 static readbacks and 24 full-turn readbacks per arm. Meshelium is present for every shaderless arm, including Ultra Performance. Each input reports the expected pack state. Pose is (200.5,110,150), yaw90/pitch10, RD20, 2560x1440 target. The native reference and DLSS screenshot share yaw90. Rotation registration uses each pan frame's actual yaw and the recorded physical FOV; no consecutive-rendered-frame claim.

Mean of four captures per offset/content/quality in the inspected stone/block-edge ROI (710,934,18,25):

| Content / quality | Offset | Native detail correlation | Laplacian sharpness | Static residual | Registered pan residual | Native MSE |
| --- | --- | --- | --- | --- | --- | --- |
| Shaderless Quality | 0 | 0.7159 | 11.583 | 0.376612 | 0.821160 | 9.376 |
| Shaderless Quality | +1 | 0.8331 | 6.703 | 0.199306 | 0.517138 | 5.109 |
| Shaderless Ultra | 0 | 0.6736 | 7.409 | 0.389100 | 0.715850 | 11.755 |
| Shaderless Ultra | +1 | 0.7205 | 5.616 | 0.222944 | 0.553177 | 10.267 |
| Pack Quality | 0 | 0.5010 | 5.774 | 0.376394 | 0.411460 | 4.662 |
| Pack Quality | +1 | 0.5541 | 3.773 | 0.325965 | 0.344511 | 3.853 |
| Pack Ultra | 0 | 0.4922 | 3.629 | 0.354045 | 0.457571 | 5.486 |
| Pack Ultra | +1 | 0.4877 | 2.758 | 0.306508 | 0.408827 | 5.138 |

Actual u_BlockTex biases are -1.5846808/-0.5846808 at Quality offset0/+1 and -2.585526/-1.585526 at Ultra offset0/+1. Every accepted arm reports an applied bias in `registered-metrics.json`; raw bind counts, reset metadata, exposure and FOV remain in before/after/input JSON. All input captures have exposure1 and history_reset=false. Changes before the capture are separately visible in observed resets_by_reason; capture-time false does not prove no reset during a whole arm.

Recommendation: keep default 0. The +1 offset reduces static residual by about 13 to 47 percent and pan residual by about 11 to 37 percent in this crop, but removes about 24 to 42 percent of Laplacian energy. It improves correlation in three modes, while pack Ultra correlation slightly decreases (0.4922 to 0.4877). These measurements support +1 as a softness/stability tradeoff, not a universal reconstruction gain. A single small rock/block-edge crop does not settle distant foliage or the whole hill. No default was changed. Full images stay under `run/overnight-c/mip/`; the committed JSON/CSV provides per-arm values. Two discarded analysis attempts (water ROI and off-image left ROI) are not pooled into these results.

## Item 3: six post-fix dither arms, Meshelium present

Quality, Performance and Ultra Performance each completed FG-off and actual 3x at the same constructed sandstone AO corner, position (399.1,161.2,36.1), yaw150/pitch12. Static samples, a 4-degree/100-tick pan, a 45-degree camera cut and time-advancing sunlight samples are committed under `dither/`. Sunlight used tick rate100, unfreeze and advance_time=true, then restored tick rate20, frozen time9000 and advance_time=false. These are current-code observations, not a matched pre-fix comparison. Every arm includes Meshelium; a Meshelium-absent dither repeat remains unverified.

| Arm | Generated presents during arm | WGC CSV images | Adjacent WGC luma change | Static readback change | Registered pan change | Cut readback change |
| --- | --- | --- | --- | --- | --- | --- |
| Quality off | 0 | 834 | 0.088797 | 0.422171 | 0.304093 | 0.080786 |
| Quality 3x | 18140 | 938 | 0.047949 | 0.377568 | 0.304895 | 0.091234 |
| Performance off | 0 | 833 | 0.076917 | 0.282983 | 0.327541 | 0.078694 |
| Performance 3x | 16506 | 980 | 0.034258 | 0.277467 | 0.310314 | 0.103900 |
| Ultra off | 0 | 847 | 0.061615 | 0.375842 | 0.379598 | 0.072475 |
| Ultra 3x repeat | 17112 | 996 | 0.026900 | 0.368383 | 0.333292 | 0.081246 |

Real-frame ROI is (940,500,128,128); WGC captures that same window region and analyzes its interior (1,1,125,125). WGC's first and last crops are committed and visually show the game rather than the OLED overlay. The game stayed foreground on display1 beneath both overlays. WGC is successive capture callbacks, not verified consecutive application presents: gaps reach 43.75 to 125 ms. WGC's terminal frame/png counts can exceed CSV rows by one or two at shutdown; metrics use only the existing CSV-listed images. Preserve this capture-tool boundary. No claim of complete generated-frame image coverage. Lower 3x WGC residual also reflects presentation interpolation and sampling cadence, so it is not evidence of better SR reconstruction.

The initial Ultra 3x workload was interrupted before completion when C retained its remaining session for options/Mellow. It has no generation.json and is excluded. `mesh-ultra_performance-3x-repeat` is the complete accepted arm. FG-off status can retain a stale effective_multiplier=3; generation mode is off and generated delta is zero. Actual FG-on arms report effective_multiplier=3 and rising generated counters. Sparse cut readbacks start after endpoint latency, so immediate first-frame rejection and absence of boiling in every frame remain unverified. No render error was reported in the accepted snapshots.

## Item 4: live recovery, with UI limits

The copied leaked file was repaired to defaults on first pack load and stayed clean after a fresh JVM restart (`after-restart.properties`). Live `options/` checks then wrote explicit nondefaults in the isolated copy, loaded them to establish the record, replaced the copy with the complete leaked signature and reloaded. Recovery restored TAA=true, TAA_DEFINE=1, TAA_JITTER=1, FXAA_DEFINE=1 and IMAGE_SHARPENING=2 exactly (`recovered-nondefault.properties`). A four-key signature missing TAA_JITTER was preserved unchanged, including TAA=false and TAA_DEFINE=0; the missing key stayed absent (`partial-signature.properties`). The script restored the original copied file in finally, and `restored.properties` contains only the Euphoria marker. No real profile file was changed.

Screen navigation reached Sodium Video Settings, Iris Shader Packs and its settings pane using harness clicks and render-target screenshots. `screen-reset.*` records an ATTEMPT, not an executed Reset: the version-matched HeaderEntry.resetButtonClicked bytecode requires Minecraft.hasShiftDown(), and the harness click does not establish native held Shift. Export/Import handlers explicitly refuse while Options.fullscreen is true, with options.iris.mustDisableFullscreen. This run retained the required borderless fullscreen and did not complete their file dialogs. Reset/Import/Export therefore remain unverified, not passed. All completed load/reload/save checks preserve user values or absence; that does not certify the unexercised export route.

## Item 5: Mellow live observation and source boundary

Initial default Mellow run: Ultra Performance 853x480 to 2560x1440, pack drawing, live compile overrides IMAGE_SHARPENING=false, SMAA=false, TAA_MODE=0. Pack defaults disable SSAO and dynamic shadows. The additional fixture enabling SSAO=true and DYNAMIC_SHADOWS=true FAILED and is REJECTED as pack evidence: session3-client.log at 13:11:26 records UnsupportedOperationException: RenderPearl cannot represent shader-pack format R3_G3_B2, from Iris InternalTextureFormat.toGpuFormat through ShadowRenderTargets. Effects-on status confirms no shader pack drawing despite upscaling_active=true and empty Reflexium last_error. Thus `mellow/effects-on.json`, `effects-options.properties` and `effects-static.json` preserve a failed control, not valid Mellow AO/PCF images. Initial default screenshots/input metadata remain under run/overnight-c/mellow*. Source selection at TAA_MODE=0 is verified; live AO and PCF freezing are not confirmed. An SSAO-only retry with dynamic shadows off may avoid the observed format limitation; it has not run yet.

Version-matched source confirms dither() selects ign(Pos,false) with TAA_MODE=0. Crucially, the zip embeds the entire dither function and settings into shader program files, including program/gbuffers_terrain.fsh, in addition to lib/noise.glsl. A lib/noise.glsl-only hash edit would leave those paths unchanged. A complete exact-source adapter needs reviewed hashes and coverage of these expanded programs, full-scene resolve and geometry jitter. No partial Mellow fix was added. The source mechanism and observed TAA_MODE=0 are verified; complete effect-specific live validation and a safe adapter remain open.

## Item 6: Meshelium-present timing half, comparison pending

Three five-second frame-mark intervals per mode, shaderless frozen world at mip pose, FG off, VSync off, primary borderless and foreground beneath the OLED overlays. C's lock was explicitly marked timed during this measurement. No image analysis/build ran during those intervals. Native average FPS: 642.648, 643.928, 642.250 (median642.648). Ultra: 544.601, 545.186, 543.537 (median544.601). See `meshelium/mesh-on/` for stamped intervals and status. These short static diagnostics are not P's 1440p matrix or a Meshelium acceleration comparison. The removed-mod timing half, removed-mod static sequences and a genuine one-frame culling-dip trace remain pending. Present-mod static Quality/Ultra shaderless/pack sequences already exist in the accepted mip matrix.

## Slot release, 13:14 EDT

Controller 140380 closed exact client PID184512 through authenticated ownership-checked quit, confirmed exit, restored previous foreground and released the GPU lock at 13:14:32.134. `exit-25783.json` reports restored=true. P acquired at 13:14:32.756, owner worker-P PID61304 purpose timed. No C controller remains in the acquire loop. This observes the start of the first P turn after C; C still owes A and another P turn before its next acquisition. Items 6 removed-mod/continuity, 7 no-Reflexium reload and the item4 UI checks remain outstanding. Integration owner stays the lead. No push, merge or brain edit.

## Continuity sampling check and next controls, 13:27 EDT

The existing CaptureAnalysis tool now has a fixed-camera continuity command. It marks an isolated sample only when more than 15 percent of ROI pixels change luminance by over20 in the same direction against both neighbors, while the neighbors' mean absolute luma difference stays below1.5. Its self-check accepts a synthetic isolated excursion, rejects small noise and rejects a persistent scene cut. This is a coarse sampled discontinuity detector, not a proof of terrain culling or coverage of uncaptured frames.

Six Meshelium-present shaderless traces (native, Quality and Ultra, two accepted passes each; 24 samples each) have zero candidates in terrain ROI (80,650,640,480). `meshelium/mesh-on/continuity.json` records every trace and explicitly sets all_rendered_frames_verified=false. Genuine one-frame terrain-dip counting remains unverified because of sparse capture. No source change to rendering or culling was made.

New control launch flags in overnight-c-session.ps1 are prepared but not yet exercised: NoMesh moves exactly one jar out of the isolated mods directory after build and restores it after client exit; DisableReflexium now launches the existing mc-agent-harness/examples/empty-project fixture (Minecraft26.3, Loader0.19.5, Loom1.18.2), with the isolated run directory and no Reflexium jar or source classpath. The earlier loader-disable-property plan was superseded before any live control. This empty-project mode aliases the copy's chat binding to Iris's R reload binding and enables the copy's Iris debug keys so the harness can dispatch a shared-key click despite its key.* lookup restriction. Original copied bindings/config are restored after exit. Actual reload and disabled-mod evidence must still be checked live before accepting that control.

Full build after session3 passed all checks in25 seconds (`build-runners-final.log`). Subsequent analyzer continuity source compiled and its extended self-check passed; all PowerShell runner syntax parses. Next owned session performs another full preflight build. The real leaked-options file is unchanged, SHA-256 matches its copied input (`real-options-readonly-check.json`). Note: this matrix's input metadata requests transformer preset hint11; do not pool its absolute numbers with the earlier quick pair's preset12 captures.

## Rejected NoMesh startup, 14:10 EDT

P released at14:08:13 after the required P,A,P turns. C controller76024 launched NoMesh on25784, exact client174824. Full preflight build passed15 seconds (`build-25784.log`). Workload requested fullscreen before joining a world and received TimeoutException: No new frame rendered. No comparison data accepted. Authenticated exact-client quit completed, foreground restored, lock released14:09:28.627 (`exit-25784.json`). The workload now joins and settles the world ten seconds before fullscreen. This is a startup-order correction, not a claim of a live-tested fix yet.

Cleanup audit found the controller had moved the backup into mods while retaining its .disabled filename. No client remained. Renamed that exact isolated file back to meshelium-1.6.2+mc26.3.jar and verified SHA-256 F39419AE68E7EDB9B336A52F4CE94CCA2DFB8C4A443652E6A9EF8FB63641C400, matching the original. Future cleanup uses the saved exact original filename. Source logs: failed-25784-client.log and failed-25784-workload.log. C did not reacquire after this failed slot; P is next under the lead schedule. No other acquire-loop controller remains.

## Next reservation preparation, 14:24 EDT

P reverse started14:13:13.327, PID191812, purpose timed. C's second read-only observer logs the next P,A,P sequence in turn-order.log and never acquires. No C client or acquire-loop controller is running.

Use overnight-c-session.ps1 -RemainingChecks with fresh harness/debug ports after the observer reports C ready. This keeps consecutive owned client restarts inside one C reservation, like A's reservation protocol. Stages: NoMesh comparison/full absent dither matrix/SSAO-only retry; one startup retry only if no native FPS result was produced; then the physically absent Reflexium empty-project control. It does not start another client after30 minutes, and ownership-checked quit enforces the total38-minute cutoff. The lock is released only after all started clients exit and copied jar/binding/config restoration completes. These orchestration changes parse but are not yet exercised live. Every owned reservation runs the required full build before its first client; new per-port build logs preserve previous preflight evidence.
