# Benchmark validation and fresh FG2 trace

The runner now rejects requested/applied Reflex mismatches, inactive pacing, and captures with a new timeout, suspension or failure. It checks before/after status, timeout-counter changes, and only the capture's appended client log. Historical recovered timeouts do not invalidate later healthy captures. Explicit Off and On + Boost remain supported; NVIDIA FG's forced On state is recognized.

Whole-capture FG acceptance now requires at least 90% of the expected generated/real ratio instead of 60%. This is a conservative coarse gate, not exact measured-route coverage: menus/loading contribute to these counters, and legacy builds can only provide the BM-frame upper-bound denominator. Exact capture-boundary accounting remains required before certifying complete route coverage. Invalid rows retain their data and reasons and are excluded from summaries. Protocol version 2 prevents old runs from silently resuming under the new rules.

`-Diagnostics -Trace` records the existing bounded 32-frame marker/submit/present trace after the timing profile. Trace requires Diagnostics. The trace contains submit batches, not one entry per Vulkan submission call, and does not include sleep API calls, GPU correlation or physical scanout.

## Verification

- `powershell -NoProfile -ExecutionPolicy Bypass -File tools/bm-quick-bench.ps1 -SelfCheck`: passed, including failed/disabled/missing Reflex, recovered and log-suppressed timeouts, historical recovery, Off, Boost, forced On, and insufficient FG ratios.
- Fresh `-Explore -Quick -Diagnostics -Trace`, modes native, tf-performance, fg2: completed, all three passed the gates, frozen world hashes unchanged, owned client shut down.
- Runtime remained `build/perf-jars/shaderless-final.jar`, SHA-256 `CF8379169AE71666FF6796550BFB5276B664CA28CB495FD39EF70A3B96F440F6`. No runtime code or quality settings changed.
- Raw run: `build/bm-bench/validation-trace-20261015-A`. Retained profiles, status, driver reports, sanitized trace and summary: `docs/benchmarks/bm/validation-trace-20261015/`.

## Findings

All three modes have 32 complete traced frames with consistent marker order. Native and Transformer each show two submit batches and one real present per frame. FG2 shows four submit batches across its two renderer submits, one generated present and one real present, with successful present results. FG2's two steady-window ratios are exactly 1.0; the whole-capture ratio is 0.93597.

| Sampled cost | Native | Transformer Performance | FG2 |
| --- | ---: | ---: | ---: |
| Reflex driver call, us/real frame | 61.113 | 1004.062 | 4.152 |
| Reflex semaphore wait, us/real frame | 5.952 | 6.340 | 172.234 |
| Acquire reuse wait, us/real frame | 0.169 | 0.090 | 0.165 |
| Submit including wait, us/real frame | 194.044 | 213.902 | 2553.419 |
| SR evaluation, GPU us | unmeasured | 748.693 | 638.568 |
| FG evaluation, GPU us | not active | not active | 1400.148 |

These scopes overlap; do not add them into a frame-time decomposition. Profiles cover five seconds and precede the trace, so their frame IDs are not a correlated API/GPU trace. Diagnostic FPS is excluded from performance acceptance.

FG2's semaphore wait is 0.172 ms here versus about 0.65 ms in prior runs of the retained build. The prior value is not a fixed per-frame cost. This does not resolve its cause or establish a speedup. Transformer still shows roughly 1 ms inside the Reflex driver call, a separate phenomenon from FG2's semaphore wait.

Next: exact BM measurement-boundary counters, then repeated matched diagnostics with timestamped sleep/submit/GPU correlation. Do not change resource-lifetime waits based on these overlapping averages. Full uninstrumented ABBA, interaction smoke, early HDR placement and translucent/deformation motion remain open.
