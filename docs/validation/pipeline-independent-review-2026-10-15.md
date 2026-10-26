# Independent review of candidate 11a4387, 2026-10-15

Scope: Reflexium `11a4387` against baseline `4df6267` and `PIPELINE_IMPROVEMENT_SPEC.md`, plus Benchmark Mod `05424b8`. Review only: no runtime edits, GPU launches or benchmarks. The 480 s Haiku Quick budget is still unspent.

The first reviewer (Codex Astra) opened the retained stills and turn frames, then stopped at its usage limit before writing a report. Claude Opus re-checked each of its leads against the source. Each item below is marked as a confirmed fact or an open hypothesis.

## Verdict

Not accepted. The candidate's own report already leaves the quality, pacing and fullscreen gates open. Two defects confirmed here must be fixed before any HDR-route quality claim or Quick chart is made.

## Confirmed defects (priority order)

### 1. Transparent "background motion" is actually the front transparent surface's motion (HDR route)

- `UpscalingRenderer.captureWorldDepth` (`UpscalingRenderer.java:554-556`) uses `directWorldDepth = main.getDepthTextureView()` for packs ("Packs retain their final scene depth"). Complementary's final scene depth includes water and glass.
- `motionPass.render` (`:613-614`) reprojects that depth, and `reconstructPackHdrInputs` (`:683-686`) copies the result into `packMotionBase` as `BaseMotion`/`BaseDepth`.
- In `pack_surface_motion.fsh:21-22`, behind water or glass, `opaque.z` is the terrain depth but `depth` is the water depth. The opaque match therefore fails, and the pack's per-vertex terrain velocity (foliage, waving blocks) is discarded.
- On `:30-31`, a blended layer keeps `velocity` from the base, which is camera reprojection of the transparent surface, not of the background the comment says it preserves.
- Failure scenario: swaying kelp or grass seen through water or stained glass while the camera strafes. The vectors follow the water plane, so DLSS ghosts or smears the background. This is the exact "blurry in motion" symptom the user reported.
- Minimal fix: give the opaque match, and the blended-layer fallback, a pre-translucent depth. Either capture main depth before the pack's translucent gbuffers (Complementary `depthtex1` equivalent), or use the opaque surface's own depth (colortex14 `.z`) as the base when `opaque.a` is about 1, then reproject camera motion from that depth for blended pixels.

### 2. Quick archiver applies the first folder's protocol to every folder in a set (Benchmark Mod)

- `tools/mctest/build_upscaling.py:148` `protocols.setdefault(set_id, protocol)` keeps only the first folder's `protocol.json`. Line `:172` judges all captures in the set by it.
- Failure scenario: folder A is protocol v3 and folder B is v4 for the same set. `visual_row_problem` skips the v4 still/sequence/reviewer requirement for B (`:32-33`), so an unreviewed v4 capture can chart under v3 rules. The reverse order wrongly rejects legitimate v3 evidence.
- Minimal fix: store the protocol per capture (`capture["protocol"] = protocol`) and judge each capture by its own protocol. Alternatively, raise `ValueError` when folders in one set disagree on `validation_version`, `pass_schema` or `fg_pass_fraction`. Add a regression fixture with mixed v3/v4 folders.

## Leads checked and not reproduced

- **Aborted pass receives a verified verdict.** Not reproduced. `PassAcceptance` (`tools/bm-quick-bench.ps1`) adds every incomplete or `delta`-less tuple to `$fail`, which forces both `fg_pass_verification` and `reflex_pass_verification` to `invalid`. A missing tuple fails the tuple-count check. The original reviewer's reproduction was lost with its run. Keep this open until a fixture shows the path; if `complete` can arrive as a JSON string, `!"false"` is `$false` in PowerShell, so coerce with `[bool]::Parse`.
- **Partial-review findings 2 and 3 (exporter legacy FG, measured metering).** Fixed in the current source: `fg_row_problem` requires `measured_pass_verified`, and metering rejection and batch mismatches are separate `$fgFail` entries.

## Parent-scan items still unverified (need a second review pass)

- `PackPassMixin`: viewport/world selection keyed on `packHdrBuilt` while attachments use `packHdrThisFrame`, so a mid-frame toggle could mismatch.
- FP16 tolerance (`depth*5e-4 + 6e-8`) can match a different overlapping surface at distance. This is about 0.05% relative depth, which spans many blocks near the far plane.
- Transparent attachment raster selection versus the nearest layer when several blended layers overlap.
- Vendor auto-exposure combined with Complementary's own exposure stage (possible double adaptation).
- Changed default non-HDR filter bypasses; confirm the default route is byte-identical to `shaderless-final`.

## Visual observations (from the first reviewer's opened images)

- Candidate cutout stills show no scene-wide blur, but thin ladder detail remains softer than native.
- The retained and candidate turn frames differ in camera angle and animation phase, so they cannot show any motion-quality change. Matched-phase sequences (same seed, same tick, same yaw path) are required.

## Acceptance gaps (unchanged from the candidate report)

Reflex timeouts during transitions (14 increments, 7 recoveries), unverified fullscreen, unfinished particle and translucent coverage, no frame-ID correlation trace, no live measured-pass certification, no validation-layer run, and no proven quality improvement (static wall correlation: old .8709 vs DLSS .8608).

## Recommended next round

1. Fix defect 1, then capture matched-phase motion frames through water and glass, with and without the HDR route, and open them.
2. Fix defect 2 and add a mixed-protocol fixture.
3. Resolve the five unverified parent-scan items.
4. Only then spend the single Haiku Quick budget.

## Corrections applied

- Defect 1: `pack_surface_motion.fsh` now uses the pack's opaque surface velocity and depth behind a blended layer when that surface lies behind the layer. Build and offline checks pass. A live visual check through water and glass is still required (the shader compiles only at runtime).
- Defect 2: `archive_quick` refuses to mix validation_version, pass_schema or fg_pass_fraction within a set. A mixed v3/v4 regression fixture passes (Benchmark Mod).


## Second round, 2026-10-16

- Defect 1 live check (HDR route, DLSS Quality, light-blue stained glass in front of grass and leaves, strafing): the captured SR depth shows the grass blades, leaves and fence behind the glass, not a flat glass plane. The merged depth and vectors come from the background surface. Evidence in `pipeline-evidence-2026-10-16/`. Sub-pixel strafe speed at about 480 FPS means this proves routing, not a measured ghosting reduction.
- Parent-scan item, viewport/state mismatch: **confirmed and fixed.** Viewport and viewWidth/viewHeight used `packHdrBuilt` while attachments and samplers used `packHdrThisFrame`. On any frame with an HDR feature where the boundary did not run (loading frame, not swapped), composite5 through final drew a display-size viewport into render-size targets. All four now use `ComplementaryHdr.displaySized`. composite5 uses the boundary's own precondition (`packHdrBoundaryDue`), because HEAD injector order is not guaranteed. Build, Vitrail check and a live HDR frame pass.
- FP16 depth tolerance: dismissed. `depth*5e-4` is about one binary16 step of reverse-Z depth, a constant relative distance error. Only near-coplanar surfaces can alias.
- Overlapping blended layers: the transparent surface keeps the last drawn layer, which is the nearest for back-to-front translucent sorting. Behind any blended layer, vectors now come from the opaque surface, so the selected layer only sets depth ordering and the FSR mask.
- Auto-exposure: dismissed. The DLSS AutoExposure flag only affects internal history weighting; output stays linear and is exposed once by Complementary's composite5.
- Default non-HDR route: intentionally not identical to `shaderless-final`. It also removes the pack's FXAA and pre-upscale sharpen, only while an upscaler owns temporal sampling (`VitrailCompat.ownsTemporalSampling`), with exact-source hashes. Native rendering is unchanged. `DebugSettings.packPreFilters` restores them for an A/B comparison.

Remaining before acceptance: Reflex timeouts on transitions, fullscreen, particle coverage, frame-ID correlation trace, matched-phase motion A/B, and then the single Haiku Quick run.
