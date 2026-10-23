# Tools

Dev scripts for driving a test client, benchmarking and checking captures. None ship in the mod jar. Most talk to a client started through the agent harness (`C:\Users\Ryan\source\repos\mc-agent-harness`) or Reflexium's own debug server ([docs/DEBUGGING.md](../docs/DEBUGGING.md)).

## Client control

| Script | Purpose |
| --- | --- |
| `run-debug.ps1` | Starts `runClient` with the debug server and waits for `/status` |
| `rxdbg.ps1` | CLI for the debug server: status, set, screenshot, move, turn, join, quit |
| `launch-keepalive.ps1` | Launches the dev client through the harness with the debug port |
| `join.ps1`, `capture.ps1` | Join the test save or take a screenshot through the harness on port 25601 |
| `mc.sh` | `mc.sh PORT endpoint 'json'`: one authenticated harness call |

## Benchmarks

| Script | Purpose |
| --- | --- |
| `bm-quick-bench.ps1` | Benchmark Mod ABBA comparison of two Reflexium jars on the FPS Lab scene |
| `ab.sh` | Quick A/B over upscaler, quality and model combinations with `frame_mark`/`frame_stats_from` |
| `benchmark-upscaling.ps1` | Upscaling and frame generation matrix (Set A no shaders, Set B Vitrail), see [docs/benchmarks](../docs/benchmarks/README.md) |
| `benchmark.ps1` | Older single-pass benchmark through the harness |
| `measure-per-frame.ps1` | Per-frame GPU/CPU pass timings, see [docs/PERFORMANCE.md](../docs/PERFORMANCE.md) |
| `run-overnight.ps1`, `launch-overnight.ps1`, `spawn-runner.ps1` | Unattended old/new jar comparison runner and its detached launchers |
| `export-upscaling-site.ps1` | Exports benchmark results to the FPS Lab site |
| `TraceCheck.java` | Checks Reflex/FG API traces produced by `bm-quick-bench.ps1` |

## Image quality and captures

| Script | Purpose |
| --- | --- |
| `CaptureAnalysis.java` | Decodes `capture` motion/depth PNGs and reprojects them (see [DEBUGGING.md](../docs/DEBUGGING.md)) |
| `upscaling-quality.ps1`, `compare-upscaling.ps1` | Matched-crop upscaling quality matrix and its metrics |
| `compare-frame-images.ps1` | Scene-only image comparison with a fixed crop |
| `eval-iq.ps1`, `ImageSharpness.java` | Ad hoc sharpness checks on distant terrain |
| `iq-vector-metrics.py` | Camera correspondence on a no-wave control capture |
| `check-frame-generation.ps1` | Frame generation configuration and status matrix |

## Native reference

| File | Purpose |
| --- | --- |
| `reflex-reference.cpp`, `build-reflex-reference.ps1` | Standalone Win32/Vulkan `VK_NV_low_latency2` reference, independent of Reflexium |
| `vitrail-check.gradle` | Source-contract check against an installed Vitrail jar, without launching Minecraft |

## investigations/

One-off scripts behind dated reports in `docs/validation` (image quality rounds 2 to 7, FG round 4, the 2026-10-19 defaults and overnight performance runs). Kept so those reports stay reproducible; they resolve paths from the repository root and expect the clients, ports and worktrees named in their reports.
