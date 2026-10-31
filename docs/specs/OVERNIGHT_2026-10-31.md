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
