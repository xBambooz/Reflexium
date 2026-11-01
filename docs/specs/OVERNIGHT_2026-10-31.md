# Overnight handoff, 2026-10-31

Ryan cut the 2026-10-31 continuation to quick verification ("we can later do an overnight run for the
full on testing"). This file is the entry point for that overnight run. Integration owner: the lead.
Unattended rules apply: secondary monitor, focus only while holding the GPU lock, restore the previous
foreground window, OLED overlay (`tools\oled-overlay.ps1`) on both screens.

## State at `round6-validation` (after merging `2ba970f`, `723c47b` and lead fixes)

- Signs and name tags: scoped `TextFeatureRenderer.Submit` nodes from `AbstractSignRenderer.submitSignText`
  and `SubmitNodeCollection.submitNameTag` are removed from the scene, drawn once at display resolution
  against resolved scene depth (`upscale/DeferredWorldText.java`), composited after the FG hudless snapshot
  and into the FG UI-alpha layer. Vanilla see-through phase and submission order are preserved.
  Unsupported paths (OIT, vanilla post effects, unsupported pack, pack depth missing, FG with UI
  recomposition off) keep vanilla text and report `world_text_limitation` in `/status`.
  Quick checks passed (8/16 blocks, Quality/Ultra, shaderless/pack, static; one moving pig, one wall,
  focused MFG 3x counters). Lead fix `06626a8`: an unresolved depth (failed evaluation) drops that frame's
  text instead of throwing out of the render wrapper.
- Hand: `ddec204`/`bd9a742` from 2026-10-30. Only a single-sided after capture exists this round.
- Shimmer: quick mip pair at Ultra + pack, offset 0 vs +1: static residual 0.990 vs 0.722, registered pan
  residual 1.979 vs 1.873, sharpness 11.220 vs 9.177. Default stays 0 pending the full A/B.
- MiniHUD: FG-off line and MiniHUD-absent Vulkan startup verified. Live `displayed (real)` text unverified.
- 1440p performance: no FPS sample yet. Two quick attempts failed before measurement: the installed harness
  has no `bm` endpoint (replaced by the Reflexium debug `benchmark` bridge, `DebugServer.java`), then
  `Title load did not settle` on the idle gate. RxRound17 is staged (snapshot Iris, DH disabled).
- Build passes all checks; Testing Instance jar SHA-256 `4F5CCE82...1183` matches the build.

## Overnight work, in order

1. Lead: launch `26.3 Testing Instance` once (final acceptance per OBJECTIVES): jar hash, join a world,
   `/status` clean, a sign and a name tag visible, then quit. Not run this round.
2. Performance (worker B): first diagnose the title-idle gate failure from `b3/` logs before retrying.
   Then follow "Overnight test plan" in `docs/validation/dlss-1440p-perf-2026-10-30.md` unchanged.
   For the MiniHUD FG-on check, launch the client focusable (the B template sets
   `-Dagentharness.focusable=false`; activation cannot focus a window created non-focusable).
3. Image quality (worker A): follow "Overnight test plan" in `docs/validation/iq-issues-2026-10-30.md`
   (full sign/name tag matrices with perspectivenametag off/on, mip A/B, `6df62db` white-hand baseline,
   generated-frame visuals with presentation capture).

Both workers share `C:\Users\Ryan\source\repos\Reflexium-gpu.lock`. A held lock means wait and retry.
Purpose `timed` means no builds or heavy CPU work by anyone else.

## Morning follow-up, 2026-10-31 07:30 (runs before the overnight list)

Ryan ran the installed `4F5CCE82` jar in his real profile from 03:33 to 03:35 (Complementary r5.9.3 +
Euphoria Patches 1.10.5, Ultra Performance 2560x1440, transformer preset 12, DLSS-G 3x) and reported three
bugs. Lead diagnosis from his log and source (read only):

- Dots on block faces, shadows and AO corners: the Euphoria folder name misses the exact-source r5.9.3
  adapter, so `IrisCompat.temporalOptionOverrides` sets `TAA` false and `TAA_DEFINE=0`. That freezes the pack's
  `#ifdef TAA` dither animation (gbuffers_terrain Bayer64, deferred1/composite1 noisetex) into a static
  screen pattern that DLSS keeps. Worker B (codex_codex_ryan): keep TAA defined, bypass `DoTAA`, keep pack
  jitter, FXAA and sharpening off. Doc: `docs/validation/pack-dither-2026-10-31.md`.
- Own name tag appearing: Iris `ShadowRenderer` has its own `SubmitNodeStorage` and extracts the local
  player; perspectivenametag drops the camera-entity check, so the shadow pass submits the player's tag and
  `WorldTextOrderMixin` registered every storage. Worker A (codex): defer only `LevelRenderer.submitNodeStorage`.
- Sign text "still weird": unconfirmed. Hypotheses for worker A: FG judder from sign text in the UI layer
  (move sign text before the hudless snapshot, keep name tags in UI), depth self-occlusion from the 3x3
  nearest depth at Ultra Performance, pack shading mismatch, distance aliasing.

Ryan is asleep after this; he asked for Sol high workers to do all implementation and research, the lead to
verify, and to wait for Codex usage to return rather than switching providers. After both fixes are merged,
built and installed to the Testing Instance, run the overnight list above with the new jar.

Later the same message thread, Ryan added two requirements:

- Tonight's test clients run on his main monitor (DISPLAY1, primary, 2560x1440) in borderless fullscreen,
  replacing the secondary-monitor rule: harness `-Monitor primary -Width 2560 -Height 1440 -Fullscreen`, then
  the `fullscreen` endpoint with fullscreen=1 and activate=1, holding the GPU lock for the whole session. The
  OLED overlay runs with `-SkipPrimary`, covering only the secondary display.
- Reflexium must be compatible with Meshelium (his profile: meshelium 1.6.2+mc26.3 with Sodium 0.9.2). No
  earlier Reflexium test included it. Worker C (codex_codex_ryan) researches jitter, render resolution, mip bias,
  depth, temporal occlusion, Iris interplay and the Streamline device path, then fixes and quick-tests. Doc:
  `docs/validation/meshelium-2026-10-31.md`. The overnight matrices should include Meshelium once C lands.

Worker results so far (lead-reviewed):

- A `288ec4f` (worktree t3-c29af7ed): main-storage-only deferral, sign text before the hudless snapshot, name
  tags in the UI layer. Pack text shading mismatch confirmed and left (deferred dark glyph luma about 2 vs pack
  42). Follow-up running: storage reuse, skip empty layers, H2 depth discriminator, dark F5 head check.
- B `5c70d80` (worktree t3-fec5c761): Euphoria 1.10.5 hashes, TAA kept defined after the resolve bypass; AO
  stipple visibly reduced. Blocking finding: Iris `loadExternalShaderpack` writes in-use option values back to
  the pack .txt, so Reflexium's overrides were persisted. Ryan's real file now holds `TAA=false TAA_DEFINE=0
  TAA_JITTER=0 FXAA_DEFINE=-1 IMAGE_SHARPENING=0` (03:35:02), disabling the pack's TAA even with DLSS off.
  Follow-up running: never persist overrides, repair files matching the full override set, fix the log text.
- C `4704251` (worktree t3-c5bf5f33): Meshelium 1.6.2 pushes raw Vulkan descriptors, so the atlas sampler missed
  Reflexium's LOD bias (fixed, 3/3 biased binds shaderless). Jitter reaches Meshelium (Sodium copies the jittered
  projection after Reflexium's hook, verified in transformed bytecode). Meshelium has no Iris guard and draws pack
  terrain with its own fragment path; C made it delegate to Sodium/Iris whenever a pack is on. Open: a severe
  whole-terrain smear after tick freeze plus jitter/FG toggles with Meshelium only (`run/evidence/mesh-up-frozen.png`).
  Follow-up running: isolate the smear toggle by toggle, and justify the pack delegation with Meshelium-drawn
  versus delegated pack captures and FPS (remove it if Meshelium-drawn pack terrain is correct).

## Integrated state, 2026-10-31 08:20

Merged into `round6-validation`: A `c73d62d` (storage reuse, empty layers skipped, H2 depth self-occlusion
rejected at 65 degrees with identical 326-pixel coverage, dark F5 head is native), C `505e617` (smear was the
jitter-off debug mode with and without Meshelium; Meshelium delegates pack terrain by itself, so forced
delegation is diagnostic-only `meshelium_delegation`; pack FPS 309 vs 310), B `fcb3f5b` (overrides restored at
Iris's shared `tryUpdateConfigPropertiesFile`, leaked files with the complete override set repaired to pack
defaults, bookkeeping failures never stop a pack load). Merge head `907a143`; `build installReflexium` passed all
checks; Testing Instance jar SHA-256 `6B40061CBA29FC5E874621FE13F5FDE1E6D8BC41B9D3A62D3C82F0D1B10748DA`.
Ryan's real profile still has `4F5CCE82`; he installs the new jar himself. Main checkout `run/mods` now holds the
snapshot Iris (old `DE1ECDEB` jar moved to `run/mods-disabled`) and `run/shaderpacks` the Euphoria fixture.

Overnight workers (all on primary DISPLAY1 borderless fullscreen, shared lock):
- P (codex): Testing Instance acceptance launch, idle-gate fix, full 1440p FPS matrix, MiniHUD FG-on.
- A (codex): sign, name tag, white-hand and generated-frame matrices, plus Meshelium present pass.
- C (codex_codex_ryan): IrisCompatCheck fixture skip, mip shimmer A/B, full dither checks, live persistence,
  Mellow dither, Meshelium sequences/culling/FPS, sky-only reload attribution.

- 09:30: P stopped on the Codex usage limit of the `codex` account (resets 12:41 PM) before any committed result; no client or lock left behind. The lead resumes P (and A if it stops the same way) after the reset, per Ryan's instruction to wait rather than switch providers.
- 09:55: A and C also stopped on usage limits (resets 12:41 and 12:42 PM). Correction: P had committed acceptance tooling (`f33a45e`) and A a checkpoint (`6ec8139`) before stopping. Their already-started runner scripts keep using the GPU through the shared lock: C's mip pass 2 session (auto-quits at 38 minutes), A's `tools/iq-overnight-batch-A.ps1` (11 jobs, commits evidence per session) and P's `run.mjs b3 25770 validate`. The lead lets them run and resumes all three agents at 12:44 PM.
- 12:52: all three workers resumed after the usage reset. P's first native FPS row (142.63 avg) was rejected because a 30 s API timeout during unload lost its final proof; tools fixed in `c8b31c1`. GPU turn order set by the lead: P next after C's 12:50 session, then P, A, P, C, P, A, so the FPS matrix (no accepted data yet) progresses while A and C keep turns.
- 13:20: P verified MiniHUD with FG 3x (`84d9663`): the render-target capture reads `319 (106) fps`, status 318/106, 2 generated per real frame; lead viewed the crop. P's full pack-forward matrix session is running. C (`c7d0feb`): mip A/B complete, keep offset 0 (pack Ultra +1 lowers static residual 13 percent and pan 11 percent but sharpness 24 percent); dither with Meshelium and 3x passes; live option recovery and partial-signature refusal pass; Mellow freeze confirmed live but its dither is embedded in expanded programs, so no safe adapter; Meshelium shaderless medians native 642.6 vs Ultra Performance 544.6 FPS under the overlay. A: generated sign glyphs still double; discrimination against neighboring static edges requested.
- Correction (C, raw log audit): the Mellow SSAO plus dynamic-shadows fixture did not load. Iris threw UnsupportedOperationException because RenderPearl cannot represent the pack's R3_G3_B2 shadow target format (ShadowRenderTargets, 13:11:26), so `mellow/effects-*` is not pack evidence and the live Mellow AO/PCF freeze is unverified. C retries SSAO only with shadows off in a later slot.
- 13:40: A `4f250e62`: generated-frame sign doubling is general DLSS-G edge warping (spatial AC3 generated minus real: glyphs +0.13 to +0.26, bookshelf +0.49 to +0.55, glass +0.25 to +0.53; all 72 paired deltas positive), so no world-text change. Follow-up candidate outside world text: why DLSS-G repeats static high-contrast edges during turns (MV precision, dilation or depth inputs). A `60d7ae70`: full real-mod-set pass (Iris + Meshelium + Sodium) valid for signs, thin iron-bar occlusion, name walks and fallbacks; full-profile FG retry with verified foreground requested. P traced the native-row timeout to `Minecraft.disconnect` dropping queued tasks (`BlockableEventLoop.dropAllTasks`), so a debug-bridge call queued just before route end never runs; P retries state reads and continues SR rows.
- 14:13: P full forward pass done (nine standard rows valid, raw: native 143.15, q-k 146.01, b-k 151.06, p-m 156.26, up-l 183.06, q-e 156.32, b-e 163.17, p-e 178.03, up-e 201.27 avg FPS; not accepted until reverse repeats within 3 percent). native-qsize was invalid because the runner never applied 1707x960; fixed in `91c805aa` as a focused 1707x960 window. Reverse pass acquired 14:13. A stopped on the `codex` usage limit again (resets 5:41 PM); its gated controller `run/resume-A-after-mesh.ps1` keeps queuing A sessions. Lead wake-up set for 5:44 PM.
- 17:45: run stopped at Ryan's request (he started his real Fabric 26.3 client at 17:21 with jar `6B40061C`). Race: after the 17:42 usage reset, A's gated controller acquired the lock at 17:42:40 and began launching an isolated test client; A sent an authenticated quit and the lead stopped the remaining owned Gradle process by exact PID; no measurement ran. No worker turn, runner, client or lock remains. Unmerged worker branches (evidence, tools, one test fix): P `t3/overnight-dlss-validation` (t3-79390f11), A `t3/overnight-iq-validation` (t3-de619d04), C `t3/overnight-validation` (t3-ce6c7b5a, includes the IrisCompatCheck fixture skip). Pending for the next unattended run: FPS reverse q-k/native and the shaderless passes plus frozen-span profiler rows, Testing Instance focused acceptance, A's mob baseline/current, focused full-mod FG and eight white-hand sessions, C's Meshelium-absent comparisons, absent dither arms, SSAO-only Mellow and the no-Reflexium reload control. Rule for next time: every GPU acquisition must first check that no real-profile client is running.
- 2026-11-01: Ryan said continue and reported a world smear during vertical view bob (not hand bob), asking to consult rtx-remix-minecraft and Radiance. Lead merged P, A and C into `round6-validation` (`1c488065`). Lead diagnosis from bytecode: under a pack, Iris `MixinModelViewBobbing` applies bob to `viewRotationMatrix` at the `LevelRenderer.render` call, after Reflexium's `CameraHistory.recordLevel` read it at `ProjectionMatrixBuffer.getBuffer`, so camera motion vectors and DLSS-G constants miss the bob. Unproven live; this contradicts part of `pack-fg-bob-2026-10-23.md`. Worker V (codex) owns the fix and repo research; worker T (codex_codex_ryan) owns deferred sign text shading (dark glyph 1.83 vs pack 51, glow 255 vs 222). Test clients: secondary monitor, behind, muted, checking for a real-profile client before each acquisition. FPS repeats and the other overnight matrices stay for a separate unattended run.
- 2026-11-01 06:30: V's fix `1107bae4` reviewed and merged (`86b4336a`), diagnosis confirmed live: vector error 0.2391 to 0.0128 px at Ultra Performance, 0.3324 to 0.0125 at Quality, shaderless unchanged. Build passes; Testing Instance jar SHA-256 `4CD4CBEEE04AE91309F45625CB12781CDF54532E4F3F2E3EFD638466D918A3C1`. Ryan asked for the overlay on both monitors (tool now re-asserts topmost ten times a second). V follow-up running: focused FG 3x before and after, natural-hill shimmer. T found no safe sign text correction (`21f9da81`, native dark 54 vs deferred 1.84); T follow-up running: offline fit of a local-levels model first, implementing only if held-out error is 8 luma or less.
- 2026-11-01 07:15: merged V's FG and hill results (`dd553198`), T's opt-in sign levels (`036b5b0f`, switch `world_text_pack_levels`, default off because raw Laplacian variance fell in all 16 groups, a criterion fixed before the run that is contrast dependent; waiting on Ryan's decision) and V's Reflex settings fix (`18d01f93`: with NVIDIA FG the option stays enabled offering On and On + Boost, strikethrough only when unsupported). Build passes; Testing Instance jar SHA-256 `8B0B4207846DC3DD1AD71A05EFE4FF115B04A42F77308E6EC056711B3199EB1C`. Lead acceptance (`tools/investigations/lead-acceptance-20261101.mjs`, `docs/validation/lead-acceptance-20261101/`, secondary monitor, unfocused): Testing Instance hash matches, joins, upscaling active, no error; dev client with Complementary + Euphoria: sign text deferred (8 nodes, no limitation), `drawn_view_has_view_effects` true while walking, source verified, levels not applied, no error. Not accepted this round: 1440p, FG in the merged jar at normal frame rate, Ryan's real profile (he installs the jar himself). Still open for a separate unattended run: FPS repeats of native and Quality K, and the matrices listed at 17:45.
- Lead summary of P's FPS data (`b8366ea9`, pack 1440p, under the overlay): seven SR rows repeat within 1 percent on average FPS (b-k 151.2, p-m 157.0, up-l 183.7, q-e 156.8, b-e 163.2, p-e 177.9, up-e 201.6). Single-pass: native 143.15, q-k 146.01, native rendered at 1707x960 169.43. Provisional attribution: Quality K costs 1/146.0 minus 1/169.4 = 0.95 ms per frame and Quality CNN 0.47 ms, matching NVIDIA's RTX 5070 1440p evaluate costs (K 1.02, CNN 0.43 ms). At about 6 ms frames the transformer evaluate consumes most of Quality's resolution saving; that is why only Performance and lower help here. Needs the native and q-k repeats to be accepted.
