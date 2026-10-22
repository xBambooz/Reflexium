# FPS regression implementation and verification

2026-10-22. Baseline: 5389634. Fabric 26.3, GraalVM Java 25.0.4+7.1, RTX 5070, Iris 1.12.0-snapshot+mc26.3-local, Mellow v3.4.1a, Distant Horizons 3.3.5.

## Changes

1. aae5a70: Preserve the refresh target for policy selection, but do not apply its Reflex cap while FG is bypassed.
2. 6d1738e: Treat requested 2x to 8x as upper bounds. Add persistent config/debug `fg_force_multiplier`, default false, to recover the old forced behavior.
3. 6322682: Select from successful real present rate after two gameplay windows. Retain the bypass measurement during generation; GPU cost minus sampled FG cost is only a throughput bound. Use five-second dwell, 10% upshift headroom, 5% downshift tolerance, and probes no more often than ten seconds. Expose probe count/state.
4. f8fb5a4: Wait messages require over 30 consecutive pending world frames, include the ledger reason, and expose `fg_resets_per_s` with a nonzero F3 suffix.
5. 44e9cd6: Compare native and DLSS over two seconds each after one second of settling. Tag existing sampled world GPU timestamps so delayed results cannot cross probe phases. Use frame intervals when GPU samples are insufficient. Auto falls back unless DLSS wins by 5%; explicit DLSS retains the comparison and stays enabled.
6. This commit: Validate the integrated behavior, preserve user real-frame limits by translating them into output limits during generation, and constrain GPU throughput bounds by known base limits so a deliberate limiter does not trigger repeated probes. Fix an observed Iris probe loop by excluding internal temporal-owner reloads from the pack configuration epoch. Update multiplier tooltip and add this evidence report. No NGX native code changes.

## Builds

Every item passed `$env:JAVA_HOME='C:\Program Files\GraalVM\graalvm-jdk-25.0.4+7.1'; .\gradlew build`, including frameDeliveryCheck and the existing contract checks. Logs: `build/item1-build.log` through `build/item6-build.log`. The existing frame delivery executable check now covers measured selection, dwell, re-probes, safe fixed requests, forced requests, guard thresholds and probe phase transitions.

`git diff --check` passed. The build-installed JAR in the 26.3 Testing Instance matches `build/libs/Reflexium-1.2.0-alpha-fabric-26.3.jar`: SHA-256 `5BC0C493A065BC85B9D8E560A4956BD38239CCAA9D8A7C7E94620B9BE270B288`. Only that Reflexium version remains in the profile's mods directory.

## Runtime protocol and results

Launches used only mc-agent-harness/tools/launch.ps1, secondary 1920x1080, hidden launcher, nonactivating visible game window, with `soundCategory_master:0.0` in run/options.txt. Launch session records confirm focus_unchanged=true. One game at a time, authenticated PID/directory and command-line ownership checks before quit exit=true. Ports: baseline 25631, final 25635. Intermediate ports 25632 to 25634 were closed before their successors.

World: Reflexium Vitrail Smoke, spectator, time set 6000, x=0 y=110 z=0 yaw=-45 pitch=15. VSync off, unlimited FPS for the main matrix, Reflex On+Boost, Transformer Performance 960x540 to 1920x1080. Eight-second setting warmup then ten-second unpolled app-frame capture, using tools/mc.sh and the frame_mark/frame_stats_from protocol used by tools/ab.sh.

Avg real FPS is the measured application-frame average over the capture. Output/real is the endpoint's separate one-second successful submission-rate snapshot after capture. These are not physical scanout FPS or an averaged output measurement.

| Mode | Before avg real FPS | Before submitted output / real | After avg real FPS | After submitted output / real |
| --- | ---: | ---: | ---: | ---: |
| native | 497.482 | 518 / 518 | 506.536 | 500 / 500 |
| dlss | 425.247 | 418 / 418 | 421.58 | 433 / 433 |
| auto | 222.574 | 223 / 223 | 423.086 | 428 / 428 |
| fixed2 | 111.483 | 223 / 111 | 425.112 | 420 / 420 |
| fixed4 | 55.739 | 223 / 56 | 423.998 | 422 / 422 |

FG Auto bypass is within 1% of explicit DLSS with FG off (423.086 vs 421.580). Requested 2x and 4x also bypass instead of cutting real FPS in this scene.

Auto upscaler status: `Upscaling: native (DLSS not faster here: 2.15 vs 1.80 ms)`, effective upscaler off. Explicit DLSS stays enabled and reports its measured comparison.

With `reflex_interval_us=10000`: FG off averaged 99.990 real FPS. Auto selected 2x, averaged 99.765 real FPS and reported 200 submitted output / 100 real FPS, with zero policy probes and zero reset rate at the final snapshot. The 100 FPS real-frame limit maps to a 200 FPS output budget at 2x, below the 223 FPS refresh ceiling. With force enabled and request 4x, the old behavior returned: 55.741 average real FPS, 223 output / 56 real FPS. Force and the interval override were restored afterward.

## Screenshots and raw evidence

All listed screenshots were opened and visually inspected. Terrain is present, and Mellow lighting, shadows and clouds are visible. Settings and FG claims were checked against the adjacent status JSON files.

- Before: `build/fps-regression/before/native.png`, `dlss.png`, `auto.png`, `fixed2.png`, `fixed4.png`.
- After: `build/fps-regression/verified/native.png`, `dlss.png`, `auto.png`, `fixed2.png`, `fixed4.png`.
- Auto guard: `build/fps-regression/verified/guard.png`.
- Low-FPS checks: `build/fps-regression/verified/low-off.png`, `low-auto.png`, `force4.png`.
- Each mode has adjacent `-stats.json`, `-status.json`, `-start.json` and screenshot metadata. Local measurement scripts are retained under build/measure-fps.sh and build/low-fps.sh.
- Launch evidence: `build/launch-25631.log`, `build/launch-25635.log`. Authenticated harness session files remain outside version control; do not publish their tokens.

Discarded intermediate evidence: after-initial had missing terrain, so its FPS is not comparable. The after directory captured the subsequently fixed Iris reload loop. after-final is the matched pre-final-limiter-validation pass; the final results above use verified exclusively.

## Limits and checks not run

- Builds ran after each item. Fresh runtime matrices cover baseline and integrated states, not each individual intermediate commit.
- Status was verified through JSON. The F3 overlay itself was not enabled or visually checked.
- Persistent failure messages for forced Reflex failure, surface loss, GUI blur and failed present were not injected live. Existing delivery contract checks cover failure accounting; live evidence here covers stable bypass and active generation.
- No native workload naturally limited to 100 FPS was tested. The explicit base limiter test delivered its effective 200 FPS output budget; exact 223 FPS output with safe Auto in a naturally slow workload remains unverified.
- The user's primary-monitor 480 Hz geometry, other shader packs, runtime resize/pack-switch invalidation, long-duration threshold changes and physical scanout pacing were not tested. Probe/camera logic has executable checks, but those are not substitutes for these GPU scenarios.
- Output rates are one-second submission snapshots; average output FPS was not captured. Do not interpret the table as scanout measurements or a statistical performance study.

## Reference consulted

Modrinth Reflex AntiLag links to Tythee/Minecraft-Reflex. Its multi-version `ReflexScheduler.java` was read for measured GPU timing and limiter handling. This patch reuses Reflexium's existing FrameProfiler and present delivery paths and adds no dependency: https://github.com/Tythee/Minecraft-Reflex/blob/multi-version/src/main/java/io/tythee/ReflexScheduler.java