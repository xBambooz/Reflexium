# Reflex cleanup validation, 2026-10-23

Build under test: working tree on `main` at HEAD `8ff1966d7a258383e0d085f4048b488d60fd4201` (uncommitted cleanup). Jar `build/libs/Reflexium-1.2.0-alpha-fabric-26.3.jar`, 3315717 bytes, SHA-256 `45F7481BA85F7CAC0684229F4E6543EFE97310495DA27B6DC93B5A231D6169E6`. The dev client ran from source (`runClient`), not from that jar.

## Verdict

Not a clean pass. Checks 1, 2, 4 and 7 pass. Check 3 passes on status text but fails on FPS (VSync On is 19.9% below VSync Off in scene B, one pair). Check 5 is inconclusive (On is -4.1% in scene B, at NVIDIA's ~4% guidance, with a 4.8% run-to-run spread). Check 6 passes on the screenshots but the HUD latency line was not verified. Check 8 passes. Check 9 exits the process but writes a shutdown watchdog crash report.

## Per-check results

| # | Check | Result | Numbers |
|---|---|---|---|
| 1 | Status schema: no `smart_reflex`, `reflex_pacing_paused`, no "paused" in reflex text | PASS | 26 harness `plugins.reflexium` snapshots, 0 forbidden keys, 0 "paused" text. Source: only a comment in `ReflexiumConfig.java:165` mentions the removed key. |
| 2 | Off: `reflex_sleeping=true`, no cap line | PASS | `Reflex: Off (requested Off)`, `reflex_sleeping=True` in all Off snapshots (scene A, scene B, shots, toggles). Off avg FPS: A 788.62 and 825.10; B 712.06 and 703.59. |
| 3 | VSync on, unlimited Max Framerate: no cap or headroom text, FPS not below VSync behavior | FAIL on FPS, PASS on text | Text: `Reflex: On (requested On)` and `Reflex: On + Boost (requested On + Boost)`, no cap, no "headroom". FPS with VSync on: A On 674.87, A On+Boost 611.27 (no VSync-off Reflex-off partner in A). B Off 672.73 vs B On 538.76 (-19.9%; 1% low 130.49 vs 69.98). Single pair. |
| 4 | Explicit cap `reflex_interval_us 16667` gives about 60 FPS | PASS | On avg 60.00, low1 55.38. On+Boost avg 59.99, low1 56.41. Status: `cap 59.0 FPS` (floor of 1e6/16667). Restored to -1. |
| 5 | FPS A/B, Off vs On vs On+Boost, ABBA with 3 s warmup | INCONCLUSIVE (within noise, at the 4% boundary) | See the scene tables and deltas below. |
| 6 | Screenshots (Off, On, On+Boost; HUD) | PARTIAL | Six 1920x1080 PNGs, none black (mean luma 13.8, 97% of samples above luma 8). On vs Off PSNR 51.5 to 57.8 dB. HUD latency line NOT verified (F3 overlay did not appear; see notes). |
| 7 | `reflex_timings` markers, present IDs, log errors | PASS | 6 sets of 60 reports (3 modes x 2 scenes): 0 marker-order violations, 0 zero driver/GPU fields, 0 `gpu_end < gpu_start`, 0 `driver_end < driver_start`. Log: 0 "Reflex marker order regression", 0 "timed out". Status counters: timeouts 0, failures 0, recoveries 0. |
| 8 | Toggle robustness and resize | PASS | Off, On, On+Boost, Off twice: `reflex_sleeping=True` after all 8 switches, no cap or stale text, `last_error` empty. Window 1920x1080 to 1600x900 and back: swapchain extent followed (1600x900, then 1920x1080), no crash. Fullscreen not tried (harness suppresses fullscreen requests). |
| 9 | Clean shutdown, PID verified | PARTIAL | `rxdbg quit exit` returned `{"exiting": true, "ok": true}`. PID 7288 was gone within about 2 s of the quit (checked at 06:37:01). Other PIDs untouched. But the exit wrote a watchdog crash report and Gradle reported `runClient FAILED`. |

## Scene tables (ABBA, one 10 s window per row)

Latency column is the `reflex_latency` field at the end of each window. `sleeping` is `reflex_sleeping`.

Scene A: native, upscaler off, 1920x1080.

| Run | Mode | avg FPS | 1% low | 0.1% low | mean ms | reflex_status | sleeping | latency line |
|---|---|---|---|---|---|---|---|---|
| 1 | Off | 788.62 | 351.09 | 225.72 | 1.268 | Reflex: Off (requested Off) | True | Reflex render latency: 2.4 ms, sim 0.0, submit 1.6, GPU 0.9 |
| 2 | On | 795.75 | 326.26 | 204.97 | 1.257 | Reflex: On (requested On) | True | Reflex render latency: 2.2 ms, sim 0.0, submit 1.0, GPU 1.2 |
| 3 | On + Boost | 814.84 | 329.51 | 212.46 | 1.227 | Reflex: On + Boost (requested On + Boost) | True | Reflex render latency: 1.9 ms, sim 0.0, submit 0.9, GPU 1.0 |
| 4 | On | 792.91 | 310.96 | 219.40 | 1.261 | Reflex: On (requested On) | True | Reflex render latency: 2.0 ms, sim 0.0, submit 1.0, GPU 1.1 |
| 5 | Off | 825.10 | 345.91 | 214.34 | 1.212 | Reflex: Off (requested Off) | True | Reflex render latency: 2.0 ms, sim 0.0, submit 0.9, GPU 1.1 |

Scene B: DLSS Performance, CNN (render 960x540 to display 1920x1080), shader pack Mellow v3.4.1a.

| Run | Mode | avg FPS | 1% low | 0.1% low | mean ms | reflex_status | sleeping | latency line |
|---|---|---|---|---|---|---|---|---|
| 1 | Off | 712.06 | 328.52 | 205.96 | 1.404 | Reflex: Off (requested Off) | True | Reflex render latency: 2.2 ms, sim 0.0, submit 1.3, GPU 1.2 |
| 2 | On | 694.80 | 276.33 | 160.25 | 1.439 | Reflex: On (requested On) | True | Reflex render latency: 2.6 ms, sim 0.0, submit 1.5, GPU 1.1 |
| 3 | On + Boost | 673.86 | 271.76 | 196.21 | 1.484 | Reflex: On + Boost (requested On + Boost) | True | Reflex render latency: 2.6 ms, sim 0.0, submit 1.4, GPU 1.3 |
| 4 | On | 662.17 | 299.04 | 199.59 | 1.510 | Reflex: On (requested On) | True | Reflex render latency: 2.6 ms, sim 0.0, submit 1.4, GPU 1.2 |
| 5 | Off | 703.59 | 326.31 | 204.67 | 1.421 | Reflex: Off (requested Off) | True | Reflex render latency: 2.5 ms, sim 0.0, submit 1.3, GPU 1.2 |

## FPS deltas (check 5)

| Scene | Off mean (2 runs) | On mean (2 runs) | On vs Off | On+Boost (1 run) | On+Boost vs Off | Off run spread | On run spread |
|---|---|---|---|---|---|---|---|
| A native | 806.86 | 794.33 | -1.6% | 814.84 | +1.0% | 4.5% | 0.4% |
| B DLSS Perf | 707.82 | 678.49 | -4.1% | 673.86 | -4.8% | 1.2% | 4.8% |

The ABBA order cancels linear drift in the means. Scene B's On pair spans 4.8%, which is the same size as the delta. So scene B is at NVIDIA's ~4% guidance but not separable from noise with two runs per mode. On+Boost has one run per scene. No delta is large (above 5%) in these runs, so there is no evidence of a large regression from removing Smart Reflex. The VSync residual (check 3) is a separate and larger effect.

## Shared-GPU contention

The Modrinth javaw clients (PIDs 17548, 20276, 5472, 15736) were alive during the captures and were not touched. They were still running at 06:37:01 (after the quit), and none was running at 06:40:16. This session issued no stop or kill to them. The cause of their exit is not established. No crash report appeared in the Modrinth profiles folder in the 45 minutes before the check. They were not on the GPU process list at the 06:37 snapshot. Other GPU users were present: OBS (`obs64.exe` and its browser page), two NVIDIA Broadcast processes, ShareX, Firefox, Steam, T3 Code, dwm. GPU utilization sampled by `nvidia-smi` was 70 to 90% before and after the captures (`gpu_before` and `gpu_after` in `fps.jsonl`). Those samples cannot separate our client's load from the others. OBS was not checked for an active recording, so its contribution is unknown.

## Notes and deviations

- Launch: used the harness launcher (`mc-agent-harness/tools/launch.ps1`, harness port 25910, `-PrxDebug=25599`, secondary monitor) instead of `tools/run-debug.ps1`. Reason: the brain's standing rule forbids `run-debug.ps1` on the main screen. The debug server answered on 25599. Focus was unchanged.
- `rxdbg raw /reflexium ...` does not work on the debug port. It returns the endpoint help list with `ok: true`. `frame_mark`, `frame_stats_from` and `reflex_timings` were POSTed to the harness `/reflexium` endpoint (bearer auth). The first attempt produced no data and was discarded (see `errors.txt`).
- Pose: `camera -25.5 69 23.5 0 0` settled at y 65.0 (eye 66.62). Same pose for every capture. The view is a dim close-up block wall (mean luma 13.8/255), so the parity check is weak evidence.
- HUD latency line: not verified. The F3 overlay holds the Reflex latency line. The harness rejects scancode keys unless a screen is open. Opening chat and sending F3 (scancode 60, keycode 1073741884) did not show the overlay in `screenshots/A-on_boost-hud-gui.png`. A first attempt used keycode 1073741888, which is F7.
- Shutdown: the dump is `Client shutdown from post-main` with two `HTTP-Dispatcher` threads from `jdk.httpserver`, and no Render thread. The brain documents this pattern for the harness and debug servers (embedded HTTP dispatchers not stopped on quit). It is not a Vulkan wait per that dump, but the quit was not clean.
- Startup log noise, not steady state: NGX `error 126` while loading `nvngx.dll` from the JDK bin path (DLSS later built and evaluated); authlib 401 and Realms errors (offline account).
- Resize to 1600x900 produced 2 `SurfaceException: swapchain out of date` warnings, followed by `Swapchain created ... 1600x900`. Transient.
- Settings restored after the run to the values in `run/config/reflexium.properties` before the test: upscaler off, quality performance, dlss_model transformer, reflex_mode on_boost. VSync off, `reflex_interval_us -1`, `reflex_submission 0`. The rewritten `reflexium.properties` no longer contains the leftover `smart_reflex=false` line.

## Leftover Smart Reflex or VRR strings

None found in source or status. Only the comment in `ReflexiumConfig.java:165`. The status object has no `smart_reflex`, `reflex_pacing_paused`, or VRR/headroom text in the Reflex status text.

## Remaining risks and recommended next steps

1. Repeat the VSync-on test as an ABBA in scene B (Off, On, Off, On) with `reflex_timings` and `reflex_latency` captured under VSync. This is the largest open question (-19.9% single pair). The current `reflex_timings` fields do not expose driver sleep as its own value. In the three scene B captures (180 reports), input to sim_start has median 0 µs and max 1 µs, so the sleep cannot be read from that interval.
2. Compare against a pre-cleanup build (for example HEAD before this working tree) to isolate any effect of removing Smart Reflex. No pre-cleanup jar was run here.
3. Verify the HUD latency line by a path that does not depend on harness key injection, or add a debug-port toggle for the F3 overlay.
4. Use more runs for On+Boost (n=1 per scene now) and for scene B On (n=2).
5. The quit watchdog: the debug server's `HttpServer` has no explicit stop on quit. That is the cause the brain documents. Not changed here (read-only test).

## Evidence files

- `protocol.json`: commands, PIDs, jar hash, pose, settings, shutdown.
- `protocol.log.txt`, `launch-log.txt`, `launch-output.json`: timestamped steps and the launcher output.
- `fps.jsonl`: 16 capture rows (A check 3 x2, A check 4 x2, A ABBA x5, B ABBA x5, B VSync baseline x2).
- `status.jsonl`: 26 harness `plugins.reflexium` snapshots.
- `timings-{A,B}-{off,on,on_boost}.json`: 6 `reflex_timings` captures (60 reports each).
- `screenshots/`: `{A,B}-{off,on,on_boost}.png` (1920x1080), `A-on_boost-hud-gui.png` (HUD capture, latency line not visible).
- `compare/{A,B}/metrics.json`: `tools/compare-frame-images.ps1` output (On and On+Boost vs Off).
- `errors.txt`: raw error text.
- `lib.ps1`: helper functions used for the timed windows.
- Crash report (outside this folder): `run/crash-reports/crash-2026-10-23_06.36.59-client.txt`.
