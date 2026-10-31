# Continuation handoff, 2026-10-30

Ryan asked for research and plans now, with implementation and long tests deferred until he says
"continue the rest" (sent when he is about to leave the PC, so the test window may then take focus on
the secondary monitor per the OBJECTIVES.md decision log). Integration owner: the Claude lead.

## State at `31d03e4` (branch `round6-validation`)

- Hand white blobs, partly fixed: `ddec204` keys motion history by the persistent model root because
  26.3 `submitModelPart` wraps it in a new `Model.Simple` every frame; `bd9a742` writes zero velocity for
  a stationary hand instead of leaving world reprojection under it. Lead checked the diff, the pixel
  stats (hand ROI 0 moving px, world ROI 9600) and the before/after pan frames.
- MiniHUD FPS (`9d23fd3`): `InfoLine.translate` hook rewrites `minihud.info_line.fps` to
  `<displayed> (<real>) fps` when FG adds frames. FG-off smoke in the RxRound17 copy shows the line
  unchanged with no mixin error; `fpsTextCheck` passes. Live FG-on text and the MiniHUD-absent launch
  are unverified.
- Sign text, name tags, distant shimmer: not fixed. Research and plans only.
- 1440p DLSS performance: research, pack extent audit and prepared tooling only. No local FPS numbers.
- Build passes; Testing Instance jar SHA-256 `4f09b0b6...c065` matches the build.

## Deferred work

Image quality (worker A, Codex `codex` account): follow "Executable continuation plan" in
`docs/validation/iq-issues-2026-10-30.md`, in this order:
1. Discriminating sign-text test (no default changes before it).
2. World-text display-depth and FG UI contract for name tags and sign text. It needs a small UI-alpha
   capture addition in `framegen/FrameGenerationRenderer.java`; worker A owns that change in this round.
3. Mip-bias A/B (`/set mip_bias_offset` 0 vs 1, Quality and Ultra Performance, pack and shaderless).
4. Exact white-skin walking repeat against a hash-verified `6df62db` artifact.

Performance (worker B, Codex `codex_codex_ryan` account): follow "Continuation plan" in
`docs/validation/dlss-1440p-perf-2026-10-30.md`. Prerequisites before any timed pass:
- Align `Fabric 26.3 RxRound17` Iris to the real profile's `iris-fabric-1.12.0-snapshot+mc26.3-local.jar`
  (SHA-256 `566bd751...1c2`); the copy still runs `1.12.0-b3` (`de1ecdeb...c3b`). The runner refuses a mismatch.
- Disable the copy's DH jar. Install the current `round6-validation` jar there.
- Run `tools/dlss-1440p-b2/*.mjs` with `--execute` (they default to dry run). Planned 100 to 130 minutes.
- Live MiniHUD FG-on check and the MiniHUD-absent dev launch are step 1.

Both workers share `C:\Users\Ryan\source\repos\Reflexium-gpu.lock`. A held lock means wait and retry
(every 3 to 5 minutes), not stop. Timed batches need the GPU exclusively; worker A can implement while
worker B measures and capture once the lock is free.

## Facts not to re-derive

- NVIDIA guide (31 March 2026) RTX 5070 1440p evaluate: CNN 0.43 ms, K 1.02, M 1.33, L 1.87. Ryan's
  Ultra Performance uses L. Mapping is in `upscale/dlss/DlssModel.java`.
- SR already uses local Streamline tags (`native/reflexium_sl.cpp` `rx_sl_dlss_evaluate`).
- Mip bias and jitter phases already follow the guide (Ultra Performance -2.585, 73 phases).
- Font atlases have one mip with nearest sampling; terrain mip bias does not touch them.
- TechSpot 2026-01-23, RTX 5070 Ti 1440p, DLSS 4 Quality vs native: Cyberpunk RT +69.6% (46 FPS base),
  Mafia +36.8% (57), Horizon Zero Dawn Remastered +14.6% (123). Gains shrink as the native base rises.
