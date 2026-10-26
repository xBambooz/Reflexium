# Partial implementation review

Opus 5.5 medium task stopped with `Claude API rate limit reached. Try again later.` No completion report or implementation commit was returned. Baseline: `4df62671ab4860a2003462339e05f8a076b87cad`. Changes remain uncommitted in Reflexium and Benchmark Mod. This is a partial review, not acceptance.

## Findings

1. **Blocking scope gap:** Stage 4's early HDR route and transparent/deformed motion coverage are not implemented in the current diff. The shader changes bypass exact-source FXAA and sharpening, but reconstruction placement, HDR resource/ABI contracts, and object/deformation motion remain unchanged. Do not substitute these filter edits for Stage 4 or claim the motion problem is fixed. Matched temporal quality evidence is still required.
2. **High: unverified FG can chart.** `../Benchmark Mod/tools/mctest/build_upscaling.py`, `fg_row_problem`, expressly permits verification strings beginning `unverified: legacy counters`. Both the full importer and Quick archiver can then emit status `ok` with FPS gains. The spec forbids incomplete telemetry becoming a verified/charted FG win. Preserve legacy evidence as unverified and suppress gain fields; add a regression check that rejects this case rather than blessing it.
3. **High: measured-pass metering validation is incomplete.** `PassAcceptance` in `tools/bm-quick-bench.ps1` records but does not inspect `fg_metering_rejected`, `fg_batches_started`, or `fg_batches_completed`. `CombineValidity` replaces the coarse FG verdict when exact pass status exists. Consequently, metering rejection with otherwise sufficient generated-present counts can bypass the prior rejection guard. Define and enforce measured-span metering acceptance separately from generated coverage and test rejection with intact generation counts. Existing rejection fixtures reduce generation too, so they exercise coverage rather than this failure.

## Checks performed by parent reviewer

- `gradlew.bat check -I tools/vitrail-check.gradle vitrailCompatCheck --console=plain`: passed, including frame delivery, Reflex timing, object motion, upscaling contracts and Vitrail checks.
- `powershell -NoProfile -ExecutionPolicy Bypass -File tools/bm-quick-bench.ps1 -SelfCheck`: passed.
- `java tools/TraceCheck.java self-check`: passed.
- FPS Lab portable Python running `tools/mctest/test_upscaling.py`: passed. Its FG2 fixture currently expects a charted gain with legacy unverified baseline telemetry, reinforcing finding 2 rather than resolving it.
- No live benchmark was launched by the parent reviewer. Child benchmark consumption must be recovered before any resumed campaign; do not assume the eight-minute budget is fresh.

## Resume requirements

Resume with the requested Opus 5.5 medium when available, unless Ryan authorizes another implementer. Preserve the partial changes. Address the findings, complete the remaining spec, provide the required validation report and evidence, and then obtain an independent final review. Haiku 5.5 owns all benchmarks, Quick only, under eight minutes total including startup and shutdown. No final performance or visual acceptance is established by the checks above.
