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
