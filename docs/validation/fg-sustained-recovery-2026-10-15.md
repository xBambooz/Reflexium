# Sustained frame generation recovery, 2026-10-15

Implements `FG_SUSTAINED_RECOVERY_SPEC.md`. RTX 5070, NVIDIA 617.42, Minecraft 26.3, FPS Lab frozen `benchmark-route` scene (scene.json SHA-256 `720F539F...8316`), BM Quick (one launch per jar, one pass, 5 s warmup, no conditioning).

## Diagnosis

The spec's hypothesis (seeded `-1` survives unwritten and trips the latch) is wrong on this driver. Raw feedback captured with the new sentinel (`0x7fffffff`):

- The driver writes `0x1` into `presentConfigFeedback` synchronously for every `VkSetPresentConfigNV`, including the first batch and one-frame resets, with `vkQueuePresentKHR` returning `VK_SUCCESS` (`build/bm-bench/fgdiag-fixed-20261015-A/client-0.log`: `seeded=0x7fffffff, feedback=0x1, result=0`).
- 08381e6 treated any nonzero value as failure, so the first batch latched `failed=true`. Hence one generated present per mode change in the overnight run.
- ac1e339 (the working baseline) never read feedback and sustained generation. With feedback 0x1 ignored, the new jar sustains generation too (below). The meaning of `0x1` is not documented by the Khronos reference page; it is recorded as "status", not success.

A first correction (`fg-recovery-20261015a.jar`, SHA-256 `AEA5F870...5AF1C`) treated every nonzero value as a transient rejection. It looped: one batch, then a rejected reset on every present (35077 rejections, 1 generated present per fg2 capture). The runner gate rejected both captures. This intermediate jar is evidence only.

## Change

- `FrameGenerationRenderer`: sentinel `FEEDBACK_UNREPORTED = Integer.MAX_VALUE`; separate unreported, accepted (0), status (positive, e.g. 0x1) and rejected (negative) counters. A negative value abandons the batch, invalidates history and queues a one-frame reset without setting the NGX failure latch; the problem stays in the status line. Reset feedback is read on every present; a failed reset stays pending; a pending reset at a batch start cuts that batch to its real image instead of being consumed as the batch config. Batch started/completed counters. First four configs and a bounded number of rejections are logged.
- `DebugServer`: `fg_metering_*`, `fg_batches_*`, `fg_failed`, `fg_problem`.
- `bm-quick-bench.ps1`: per-capture before/after FG snapshots and deltas, invalid captures retained (`valid=false`, reason) and excluded from summaries. Gate: generated/real delta ratio at least 0.6 x (multiplier - 1) over the whole capture (denominator includes load, menu and cuts), negative-feedback rejections at most 10% of batch configs, and with `-Diagnostics` two steady windows (at +36/+42/+48 s) each at least 0.9 x expected. Builds without `fg_real_presents` (ac1e339) use BM measured frames as a coarse denominator, labelled in `fg_denominator`.

## Checks

- `.\gradlew.bat build --console=plain`: passed, including `Frame delivery lifecycle checks passed` (sentinel, 0, 0x1 status, -1 and 0xFFFFFFFE rejection, first/mid-batch errors, SUBOPTIMAL, reset feedback in and out of batches, failed reset pending, pNext preservation, repeated rejection, recovery without `retry()`).
- `tools/bm-quick-bench.ps1 -SelfCheck`: passed (zero and one generated frame rejected, 2x and 6x accepted, 2x ratio rejected for 6x, mid-capture stop rejected by coarse ratio and by steady window, persistent rejection, missing and old-build fields).
- Installed jar hash equals built jar. Pinned `build/perf-jars/fg-recovery-20261015b.jar`, SHA-256 `C1519D1A3BA8A64FF4BC42765D47E562118C3E23E44BB8F3D7EBA1B6CEA0229C`. Preserved `08381e6.jar` (`72A34F52...740D`) and `ac1e339.jar` (`BB0EE641...7781`).

## Runs (operated by Haiku 5.5, raw evidence retained)

| Run | Path |
| --- | --- |
| 08381e6 reproduction, fg2, Diagnostics | `build/bm-bench/fgdiag-08381e6-20261015-A` |
| fix a, fg2/fg6, Diagnostics | `build/bm-bench/fgdiag-fixed-20261015-A` |
| fix b, fg2/fg6, Diagnostics | `build/bm-bench/fgdiag-fixedb-20261015-A` |
| ac1e339 vs fix b, Quick, Set A | `build/bm-bench/fgrecover-quick-20261015-A` |
| ac1e339 vs fix b, Quick, Set B (Complementary) | `build/bm-bench/fgrecover-quick-20261015-B` |

Functional (fix b, instrumented, Set A): fg2 16454 generated / 19022 real, 16454 of 16454 batches completed, steady windows 1.0 and 1.0; fg6 44165 generated / 9781 real, 8833 of 8833 batches completed, steady windows 5.0 and 5.0. No rejections, no resets, `fg_failed=false`, feedback 0x1 on every batch.

Quick, real-frame (base) FPS from BM intervals. Generated/real ratio is the gate ratio; ac1e339 uses the coarse BM-frames denominator, so its ratio overstates.

| Set | Mode | ac1e339 FPS / 1% low | fix b FPS / 1% low | change | fix b gen/real | valid |
| --- | --- | --- | --- | --- | --- | --- |
| A | native | 1909.1 / 380.5 | 2179.9 / 730.2 | +14.2% | | both |
| A | fg2 | 325.1 / 184.0 | 889.7 / 451.9 | n/a | 0 | fix b invalid |
| A | fg6 | 155.8 / 105.0 | 884.9 / 451.7 | n/a | 0 | fix b invalid |
| B | native | 247.1 / 135.2 | 247.3 / 138.5 | +0.07% | | both |
| B | fg2 | 163.7 / 104.5 | 163.8 / 100.3 | +0.06% | 0.85 (exp. 1) | both |
| B | fg6 | 106.2 / 72.9 | 106.6 / 74.2 | +0.33% | 3.79 (exp. 5) | both |

Set B: FG is restored and base FPS matches the working baseline within the 2.4% Quick band (1% lows within 16.8%). Whole-capture ratios are below expected because the denominator includes load and warmup; the instrumented windows show exact 1 and 5.

Set A Quick new arm: FG never evaluated (evaluated 0, feedback never chained). Its log shows, during the 2180 FPS native capture, `vkWaitSemaphores failed (2); Reflex sleep disabled until the mode is changed` (VK_TIMEOUT). The pacing failure makes `ReflexController.isSleeping()` false for the rest of the JVM, and `sceneReady` then invalidates every frame for NVIDIA FG (gate added after ac1e339). The runner never changes `reflex_mode`, so it does not recover. Its 889 FPS rows are DLSS-only throughput, correctly marked invalid. The Set A native +14.2% is a single-launch session comparison and not a supported regression or gain claim.

## Comparison with live dated results

The live October 15 BM dataset was built from `overnight-08381e6-{A,B}` (full method, 3 passes, conditioning). There, ac1e339 fg2 was 326.6 (A) and 165.4 (B) base FPS, close to today's Quick 325.1 and 163.7. The 08381e6 FG rows (634.5 A, 272.2 B, 1 or 2 generated presents) were DLSS-only throughput and remain invalid; they are a regression reproduction only. The October 14 legacy matrix uses a different method and is not compared. Nothing was published or overwritten.

## Remaining limits

- Set A Quick FG is not validated for fix b: blocked by the Reflex sleep timeout latch above. That is a Reflex pacing issue outside this spec and is escalated, not worked around.
- The 08381e6 reproduction run itself showed FG off with zero evaluations (status "Frame generation: off" at the 30 s probe), not the overnight one-present pattern; that run is not explained further.
- `0x1` semantics are unknown; counters cannot establish driver pacing or scanout. FrameView displayed-frame captures are still required for metering acceptance.
- Quick evidence is one launch per arm; it is not final multi-block performance acceptance.
