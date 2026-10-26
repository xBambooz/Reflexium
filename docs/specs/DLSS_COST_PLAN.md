# DLSS cost plan (one bounded task, not a validation round)

Written 2026-10-25 from the GPT Astra consult. Branch `round6-validation`, start at `9cfc757`.
This is a single task with three steps and fixed stop conditions. Do not add steps, matrices or
"follow-up rounds". When a step's stop condition is reached, write the result and move on or stop.

## Facts already established (do not re-measure or re-argue)

- Round 16's Mellow 972.7 / 516.5 pair was 2560x1440 on the moving route, not 1080p. It is not
  comparable with the 23 Oct smoke-platform numbers.
- Commit `9efe477` changed the default model from CNN to Auto (Transformer). Round 10 measured Quality
  evaluate at 264 us CNN (E) vs 628 us Transformer (K) at 1080p. NVIDIA's RTX 5070 table lists CNN
  0.27/0.43/0.93 ms vs Transformer 0.64/1.02/2.11 ms at 1080p/1440p/4K (DLSS Programming Guide, pp. 5 to 7).
  The Transformer evaluate cost is the model's cost, not an integration bug.
- The CPU completion waits come from Minecraft's `FogRenderer -> MappableRingBuffer -> awaitCompletion`,
  not from a Reflexium wait on DLSS. They overlap GPU work. Do not touch fences or frames in flight.
- `native/reflexium_sl.cpp` `rx_sl_dlss_evaluate` tags input color, output and motion as
  `eOnlyValidNow` through the global `slSetTagForFrame`. In Streamline 2.14.16
  (`sl.common/resourceTaggingForFrame.cpp` L153-207) global volatile tags are cloned and copied for
  evaluation. That predicts unnecessary copies every frame, even with FG off. This is the one known
  integration defect.

## Fixed test setup (use for every number in this task)

- Ryan's Benchmark Mod route (`benchmark-route`), NOT the dev test world. Use the existing copies
  `%APPDATA%\ModrinthApp\profiles\Fabric 26.3 RxRound17` and `Documents\Benchmark Mod RxRound17` from
  round 17 (install the build under test into that copy only). Never touch `Fabric 26.3` itself.
- 1920x1080 window on the SECONDARY monitor only. Render distance 8, Mellow v3.4.1a, DH on (as Ryan
  plays), FG off, Reflex off, VSync off, uncapped, sound 0.
- Settle 9 s after any pack reload or mode change before starting the route.
- Each configuration: two full route passes, second pass in reverse configuration order. Accept if the
  average FPS pair agrees within 3%; if not, one more pass; if still off, report it as noisy and move on.
  No further retries.
- Mode proof per OBJECTIVES.md (render_size, evaluated counter rising, no last_error) and a content check
  (one 640 px crop per configuration, not blank: round 17's DH-off Mellow crops were blank and invalid).
- For Step 2 copy proof and per-pass GPU us, use a separate short frozen capture at a fixed route pose
  with pass timing on. Profiling stays OFF during the route passes used for FPS.

## DH must not generate during a measured pass (mandatory gate)

Background LOD generation steals CPU/GPU and invalidates the data. Before measuring:
1. Pre-generate: run the route once (unmeasured) so the corridor and its DH LODs exist, or confirm the
   round 17 save already covers it.
2. Set DH distant generation off in the copied profile's DH config (`generatorPlan=DISABLED`, or the
   current DH option that disables world generation), keeping cached LOD rendering on.
3. Prove it per pass: record DH's generation/queue state (DH debug/F3 task counts, its log lines, or the
   DH API task count, whichever exists in this DH version) at the start and end of every measured pass.
   It must report zero queued/running generation tasks and no new LOD files written under the save's
   `data/DistantHorizons` folder during the pass (compare file count and total size before/after).
4. Also check title-screen idle CPU/GPU is flat before each launch (OBJECTIVES.md load proof).
Any pass where generation was active is invalid and is re-run, not reported as a result.

## CapFrameX output (deliverable for Ryan)

The Benchmark Mod writes CapFrameX-format captures to `Documents\CapFrameX\Captures\benchmark-*-capframex.csv`.
- Keep every accepted pass's CSV; rename/copy them to
  `docs/validation/dlss-cost-2026-10-26/capframex/<step>-<config>-pass<N>.csv` so they load directly in
  CapFrameX (Ryan can open them there).
- Produce one chart sheet image `docs/validation/dlss-cost-2026-10-26/capframex-sheet.png` in the
  CapFrameX comparison style, generated from those CSVs: a bar chart per configuration with Average FPS,
  1% low and 0.1% low (CapFrameX definitions: percentiles of frametimes), a frametime line graph overlay
  per configuration, and a footer table with per-row validity (mode proof, content check, DH idle proof,
  repeat agreement). Label it with build commit, resolution, pack, DH state, preset.
- Compute the metrics with a small script committed under `tools/` so the sheet is reproducible.
- Also write the same metrics into the validation report table. The sheet must match the table.

## Step 1: model cost on the current build (no code changes)

Configurations: native, DLSS Quality with `/set dlss_preset 5` (CNN E), DLSS Quality with
`/set dlss_preset 11` (Transformer K). Optional fourth: Performance with preset 5 vs 13.

Output: a 3 to 4 row table. Stop condition: table filled. No interpretation beyond the table and the
delta in us vs native.

## Step 2: remove the forced input copies

Change `rx_sl_dlss_evaluate` so SR inputs (color, depth, motion, output) are passed as local tags in the
`slEvaluateFeature` inputs array (ResourceTag entries next to the viewport), per the Streamline
programming guide section "Tagging resources" (local tagging), instead of global `slSetTagForFrame`
tags. FG keeps its own global tags exactly as now; do not change the FG path. No other changes.

Verify:
1. Build passes, `StreamlineBridgeCheck` and existing checks pass.
2. In a GPU capture or via the Streamline log / pass timing, the per-frame tag copies are gone.
   If you cannot prove they are gone, say so; do not claim a saving.
3. Re-run the Step 1 configurations (native, CNN Q, Transformer K Q) on the new build.
4. Quick sanity: FG on for 20 s (2x), confirm generated-present counter rises and no last_error.
   Image check: one crop native vs DLSS Q, no new artifacts.

Stop condition: Step 2 table filled and the copy question answered (gone / not gone / unproven).
If the fix breaks DLSS or FG and one attempt to correct it fails, revert the change and report.

## Step 3: only if needed

Run only if, after Step 2, DLSS Quality CNN costs more than about 0.15 ms over native at this scene.
Check out `03cd0a3` (last commit with the raw NGX path) into a separate worktree, build, and compare
`-Dreflexium.streamline=false` vs `=true` with preset 5, same Benchmark Mod route and DH gate. Report the evaluate us of each.
Do not port anything back in this task; just report the numbers.

## Deliverables

- One commit for the Step 2 code change (if it works), one commit with
  `docs/validation/dlss-cost-2026-10-26.md`, the CapFrameX CSVs, the chart sheet PNG and its script,
  holding the tables and a 5-line conclusion:
  CNN cost, Transformer cost, copy saving, whether Streamline adds overhead vs raw NGX (if Step 3 ran),
  and a one-line recommendation for the default model (do not change the default in this task).
- Push to `round6-validation`. Update the ai-brain only with new reusable facts.

## Hard rules

- Budget: this whole task, one session. No new "rounds", no broad pack/resolution matrices, no
  1440p/4K runs, no other packs. Only the configurations listed in Steps 1 to 3, on the Benchmark Mod
  route in the RxRound17 copy. Never launch Ryan's real `Fabric 26.3` profile.
- Do not change defaults, presets, fences, frames in flight, or anything outside `rx_sl_dlss_evaluate`
  and its Java caller (if the signature must change).
- Launch Modrinth/harness clients detached only (`tools/launch-modrinth-profile.ps1` / the harness
  launcher). Never wait on a java process. Secondary monitor only. Never touch the OLED overlay.
  Close clients only by /quit or the PID you launched. Never modify Ryan's real profiles.
- If something blocks you for more than two attempts, stop and report the blocker. Do not work around it.
- No em dash or en dash characters. No graphify. Commit messages end with
  `Co-Authored-By: DeepSeek <noreply@deepseek.com>`.
