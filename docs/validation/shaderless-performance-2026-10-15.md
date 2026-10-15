# Shaderless performance regression: localization and fix (2026-10-15)

Brief: `SHADERLESS_PERFORMANCE_SPEC.md` (170012a). Implementation: Opus 5.5. GPU operation: Haiku 5.5, one owner at a time, three delegated rounds. All runs are BM Quick on the frozen FPS Lab scene unless marked diagnostic. Quick results are provisional; no full ABBA overnight run was done.

## Result

Root cause of the shaderless native regression, high confidence: 08381e6 replaced the forced 8-image swapchain floor with the surface's default count while fixing a validity bug (VUID minImageCount-01272). Restoring the floor of 8, still clamped to the surface's min/max limits, recovers native from about 1730 to about 1900 FPS. That matches ac1e339, 7b16467 and aa46710. The fix also cuts the Reflex driver sleep call by about 32 us per native frame.

The FG2 Reflex semaphore wait (about 640 to 730 us per real frame) is not caused by marker placement or by acquire reuse. It is present on the current baseline and unchanged by the fix. It remains unexplained (see Limits).

## Code changes

- `vulkan/VulkanLifecycle.java`: `minImageCount = validImageCount(max(requested, PREFERRED_IMAGE_COUNT=8), surface.min, surface.max)`. A surface whose max is below 8 still gets a valid count, and `FrameDeliveryCheck` still covers the clamping.
- `debug/FrameProfiler.java` and `mixin/VulkanGpuSurfaceMixin.java`: new CPU-only `acquire_reuse_wait` span around the acquire semaphore completion wait. It sits outside `ACQUIRE`, so `acquire` still means the API call alone. Default-off profiling only.

## Jars (build/perf-jars)

| Jar | SHA-256 | Content |
| --- | --- | --- |
| reflex-recovery-050d799.jar | B9560554713A5D5352E2AAB46489CA147299EE14EA1A5AF5A2AE906896C7B049 | previous runtime |
| shaderless-c0.jar | 9942B5F0DB1E0764AA50EA796D65EBFC1CC985F9602B8F08A5F2D6EABFEEA8BE | 050d799 + acquire_reuse_wait span |
| shaderless-v1-markers.jar | 8570A1403221F14E5FB5A5DD2FE3E0F68E4CDCE0FB8ABFEB080B132E45030F4A | diagnostic: ac1e339 FG batch marker layout (patch alongside) |
| shaderless-v2-images8.jar | F18823950E3DBF1CC208F912C56AFA0924A2530AE461A231C097690D51FA1EF3 | c0 + image floor 8 |
| shaderless-v3-zeroid.jar | 1E27938B1D6170532CB70853AC70AEBE31B2111B1AB1BC11B15423F8C88EAE5B | diagnostic only: ac1e339 zero present-ID chaining on every submit |
| 7b16467.jar | 1F32E0EB5873B01C662E7457FC305A1FCEB6B1FA01BC7935FFCFF31EBBDB269C | bisect point before quality commit |
| aa46710.jar | 1BE077B3D144721CBA02195D7FB13D84E4979D61FC0E5BCDF3AE03D0167F6392 | quality commit |
| shaderless-final.jar | CF8379169AE71666FF6796550BFB5276B664CA28CB495FD39EF70A3B96F440F6 | retained code (c0 + floor 8) |

## Evidence (build/bm-bench)

### Round 1: leads tested and rejected

| Run | summary.json SHA-256 | Finding |
| --- | --- | --- |
| shaderless-r1-diag-c0-A | CED0F4956B99BE5C60EE960F33DDC60ACA320AC07B752F35EA5ECACE0DAE00E2 | acquire_reuse_wait is 0.10 to 0.18 us per real frame in native, tf-performance and fg2. The acquire completion fence is not the regression. |
| shaderless-r1-diag-v1-A | FCB66116FE94415AB9D9D5C4A7FD4F4BF250D1A6B31120EB90AF89EF813CE79F | fg2 reflex_semaphore_wait 641 (c0) vs 649 us (v1). Markers do not cause the wait. |
| shaderless-r1-v1-A / -B | 43BF6985...1178 / 571DBA3B...4FA29 | Marker variant within 0.7% on native, tf-performance, fg2, fg6 in both sets. Rejected. |
| shaderless-r1-bisect1-A | 8B0772C0...7DCB | ac1e339 1894.5 vs 7b16467 1902.5 native |
| shaderless-r1-bisect2-A | 2AB68098...BCE0 | 7b16467 1899.6 vs aa46710 1901.8 native. Quality commit is not the cause. |
| shaderless-r1-bisect3-A | F3F3EFA6...D1D5 | INVALID: 08381e6 arm latched Reflex off (`pacing failed: 2`). The runner still marked it valid. Kept, not used. |

The FG2 sleep-wait rise reported in the spec compared ac1e339 with 050d799. In these paired runs, 050d799 and c0 show the same wait (673 and 639 us), so the wait is a pre-existing property of the current FG path, not something introduced between them.

### Round 2: one-variable localization inside 08381e6

| Run | summary.json SHA-256 | native | Other |
| --- | --- | --- | --- |
| shaderless-r2-v2-A | 0D7F66DF8FEB6206AD1364D1275D25981733BEB605ED05CD3821EA226A52780A | 1726.9 -> 1904.9, +10.31% | tf-perf +0.21, fg2 +0.68, fg6 -0.16 |
| shaderless-r2-v2-B (shader control) | 57C3CEB85820F04255967F465F30A675A8186DB9FA023ECFBC71BECEC5353AAF | -0.39% | all within 0.5% |
| shaderless-r2-v3-A / -B | 40549DDD...5115 / EC62929E...0FED | -0.61% / -0.33% | zero-ID attribution rejected |
| shaderless-r2-diag-v2-A (instrumented) | 4EB4121BC626857484C6C3FE7836D2EA4A0CD26A9042F9CB84EFF5DF379D00A0 | reflex_driver_call 149.2 -> 116.9 us/frame | fg2 semaphore wait 724 -> 651 |

### Round 3: retained build, provisional acceptance

| Mode | Forward c0 -> final | Reverse order, final vs c0 | Set B control |
| --- | --- | --- | --- |
| native | 1734.9 -> 1897.9, +9.4% | 1908.0 vs 1732.3, +10.1% | -0.8% |
| cnn-performance | +2.2% | +3.3% | not run |
| tf-performance | +0.6% | -0.4% | -0.5% |
| tf-dlaa | +0.0% | -0.2% | not run |
| fsr-performance | +2.8% | +2.2% | -0.4% (1% low -8.7%, inside 16.8% band) |
| fg2 | +0.8% | +0.8% | -0.0% |
| fg6 | -0.3% | -1.0% | not run |

Run hashes: shaderless-r3-fwd-A `88B9641E4B661DC17ED49EDDEB4B82D8E0C0D0201E11F659084691F9C9151D55`, shaderless-r3-rev-A `513068FFFE213197C7AC334A375C4287B9EC7AB263C7A4F51377D781A6D51A76`, shaderless-r3-ctlB-B `CEEC628961C1E69F578DB9FAE3229B8B21E3E798DF4DA81E91BD4558E2E08288`, shaderless-r3-diagfg-A `9DCCB0531339E9D53F9E89B8E079A4C0B459D8C30197A95887A47D8B9E8E688D`.

Sustained FG (diagfg, steady windows): fg2 1.000/1.000 and fg6 5.000/5.000 in both arms, zero rejected metering, no FG failure. All round 3 client logs have zero `pacing failed` or `Reflex sleep disabled` lines. Status reads `Reflex: On (requested On)`, smart Reflex off, extension revision 3.

## Wins

- Shaderless native: +9.4% and +10.1% in opposite orders, outside the 2.4% Quick band. This fully recovers the ac1e339 level.
- CNN Performance and FSR Performance: +2.2% to +3.3%, at the band edge. Not claimed.
- No regression in DLSS Transformer, DLAA, FG2, FG6 or the shader control.

## Limits and open items

- Quick evidence only; the full ABBA method has not been run. Final acceptance still needs it.
- The actual created image count is not logged. The gain is consistent with the floor taking effect, but the count is not directly observed.
- An 8-image FIFO swapchain can add queued latency if a user runs VSync without a Reflex cap. This was not measured; FrameView is required for any latency or scanout claim.
- The FG2 Reflex semaphore wait (about 0.65 ms per real frame) remains unexplained. Marker layout, acquire reuse and zero-ID attribution are excluded. The next candidates are driver pacing against the two-present batch and the FG completion path. This needs an API trace with driver latency reports, not another variant.
- Transformer upscaling being slower than native in this light scene is a runtime cost boundary (spec Finding 3). Not changed.
- The runner marks Reflex-latched captures valid and accepts uninstrumented fg6 whole-capture ratios of 3.7 to 4.6. Both gaps are recorded in the brain (fps-lab.md). The runner itself was not changed.
- Transient Reflex timeouts with recovery were observed in round 1 (diag-c0 new arm, diag-v1 old arm, v1-A new arm), with no latch. That is live evidence that the 050d799 recovery path fires and resumes.
- Image and interaction smoke (hand or entity motion, rejoin, resize, pause, FG toggles) was not run interactively. The fix only touches swapchain creation, which every recreation exercises.
