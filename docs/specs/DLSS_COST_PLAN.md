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

- Build under test, Testing harness (dev client) on the SECONDARY monitor only, 1920x1080 window.
- Scene: smoke platform `(-24.5, 131, 10.5)`, yaw 0, pitch 0, frozen (no movement), render distance 12.
- Mellow v3.4.1a, DH off, FG off, Reflex off, VSync off, uncapped, sound 0.
- Settle 9 s after any pack reload or mode change, then 10 s capture with GPU pass timing on.
- Record: average FPS, DLSS evaluate GPU us, total GPU frame us, and (step 2) tag/copy GPU us.
- Each configuration twice, second pass in reverse order. Accept if the pair agrees within 3%;
  if not, repeat once more; if it still disagrees, report it as noisy and move on. No further retries.
- Mode proof per OBJECTIVES.md (render_size, evaluated counter rising, no last_error). One 640 px crop
  per configuration at most.

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
`-Dreflexium.streamline=false` vs `=true` with preset 5, same scene. Report the evaluate us of each.
Do not port anything back in this task; just report the numbers.

## Deliverables

- One commit for the Step 2 code change (if it works), one commit with
  `docs/validation/dlss-cost-2026-10-26.md` holding the tables and a 5-line conclusion:
  CNN cost, Transformer cost, copy saving, whether Streamline adds overhead vs raw NGX (if Step 3 ran),
  and a one-line recommendation for the default model (do not change the default in this task).
- Push to `round6-validation`. Update the ai-brain only with new reusable facts.

## Hard rules

- Budget: this whole task, one session. No new "rounds", no broad pack/resolution matrices, no
  Benchmark Mod route runs, no 1440p/4K runs, no Fabric 26.3 profile runs.
- Do not change defaults, presets, fences, frames in flight, or anything outside `rx_sl_dlss_evaluate`
  and its Java caller (if the signature must change).
- Launch Modrinth/harness clients detached only (`tools/launch-modrinth-profile.ps1` / the harness
  launcher). Never wait on a java process. Secondary monitor only. Never touch the OLED overlay.
  Close clients only by /quit or the PID you launched. Never modify Ryan's real profiles.
- If something blocks you for more than two attempts, stop and report the blocker. Do not work around it.
- No em dash or en dash characters. No graphify. Commit messages end with
  `Co-Authored-By: DeepSeek <noreply@deepseek.com>`.
