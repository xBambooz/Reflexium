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

## DH LOD overlap isolation (2026-10-22)

Base 40b6536, same Iris/Mellow/DH versions and camera as above. This investigation adds diagnostic switches, not a verified terrain fix. The reported brown/grey hanging columns were reproduced and opened for visual inspection.

Startup controls (restart required):

- `-Dreflexium.disableDhHooks=true` disables both `mixin/compat/dh/*` mixins, Iris `DhProjectionMixin`, DH AA API overrides and both depth capture routes. It permits SR for diagnostic comparison without DH input integration; it is not a supported quality mode for distant depth/motion.
- `-Dreflexium.disableDhMixin.BlazeDhTerrainMixin=true`, `-Dreflexium.disableDhMixin.IrisDhTerrainMixin=true` and `-Dreflexium.disableDhMixin.DhProjectionMixin=true` disable each injection independently.
- Loom forwards matching Gradle properties to the game JVM, for example `-Preflexium.disableDhHooks=true`. The all-off run's actual JVM command line is saved in `build/dh-investigation/off-commandline.txt`. The existing `distantHorizonsCheck` now exercises all-off mixin rejection and no-op capture/API paths without a game.

Discriminating results:

1. Native only with every DH hook disabled stayed clean beyond three minutes (`hooks-off-3min.png`, world joined 05:51:01, capture 05:54:16). This does not reproduce the previous settings matrix.
2. With hooks enabled, DLSS followed by native reproduced the columns (`hooks-on-100s.png`). The native setting response confirms both upscaler and FG were off after the first transition (`on-native-set.json`).
3. Repeating DLSS then native with every DH hook disabled reproduced the same columns (`hooks-off-transition.png`, `hooks-off-transition-status.json`). Therefore none of the listed DH injections or `DistantHorizonsCompat` side effects is necessary for the bug. A DH-hook fix is not justified by these results.
4. DH's own `client.advanced.graphics.culling.overdrawPrevention` was changed from `"-1.0"` to `"1.0"`, with a complete restart and hooks enabled. Columns remained in native after more than three minutes (`overdraw1-native-3min.png`) and in the DLSS-requested/FG-requested captures (`overdraw1-dlss-late.png`, `overdraw1-fg-active-late.png`). The latter filename is historical: its counters show zero evaluated/generated FG frames, so it is not evidence of active generation. The log records an invalid DH projection/depth input and fallback (`overdraw-runtime.log`). This config is rejected and the original TOML was restored.
5. A stronger independent control removed Reflexium entirely through the existing `rxBenchJar` classpath filter and an empty JAR. The loader list confirms no Reflexium mod. Ordinary window resize and a title-screen shader toggle stayed clean. A second control JAR, containing only a harness endpoint that reflectively invokes `Iris.reload()` on the render thread, also stayed clean after an in-world reload and more than three minutes in world (`control-final-3min.png`, joined about 06:10:26, captured after 06:14:00). Control source and JAR are retained under `build/dh-investigation/control/` and `dh-reload-control.jar`; loader/runtime evidence is `control-runtime.log`.

All screenshot paths above are relative to `build/dh-investigation/`. Each has adjacent screenshot metadata. Additional inspected images: the supplied `build/haiku-verify/native.png` and `baseline-fg-off.png`, `hooks-off-early.png`, `hooks-off-90s.png`, `hooks-on-dlss.png`, `hooks-on-3min-dlss-fg.png`, `overdraw1-native-transition.png`, `absent-early.png`, `absent-60s.png`, `absent-after-resize.png`, `absent-reloaded.png` and `control-after-reload.png`. The absent-early image still had loading holes and is not a settled comparison. Filenames from exploratory captures do not establish duration or effective FG state; use timestamps and adjacent status JSON.

The supported conclusion is a DH/Iris/Mellow overlap exposed by Reflexium's SR/native transition, with the dedicated DH hooks ruled out. The independent native control is clean, so this does **not** prove that DH or Mellow alone is faulty. A general Reflexium target/transition interaction remains possible. Elapsed LOD generation alone did not discriminate the cause. Exact ownership within that transition remains unresolved; no speculative renderer fix was applied.

Final acceptance check with the original DH config and hooks restored: world joined about 06:15:05, final captures after 06:18:10. Opened `final-dlss-fg-requested-3min.png` and `final-native-3min.png`; both retain the columns. `final-dlss-fg-status.json` and `final-runtime.log` show the first startup DH capture failed validation, SR evaluated zero frames and FG generated zero frames. Requested DLSS Performance plus FG is therefore **not** a passing active-SR/FG test. The earlier `hooks-on-3min-dlss-fg.png` has clean terrain and 39,215 SR evaluations, but FG bypassed at 1x. Clean native after an SR transition and clean actively generated frames remain unmet. Original upscaler/FG off and interval override -1 were restored, and the final owned client was shut down.

Next investigation: isolate the general render-target swap/version notifications and restoration path (`UpscalingRenderer.swap`, `restoreDisplaySize`, `IrisCompat.syncTargetVersions`) from temporal pack reloads, using the same hook-disabled transition reproduction. Separately inspect the actual invalid startup DH matrix/depth fields before changing the validator; accepting invalid inputs or removing DH rendering would not fix the reported overlap.

Launches used the harness `tools/launch.ps1`, secondary 1920x1080, muted (`soundCategory_master:0.0`), hidden launchers and nonactivating visible windows. HTTP calls use the same authenticated session contract as `tools/mc.sh`; the PowerShell helper avoids Windows native-argument JSON quoting loss. Shutdown used authenticated PID/directory plus command-line ownership checks and `quit exit=true`, never process-name termination. The first restart began while the prior JVM was completing asynchronous shutdown (a log-file lock was recorded); subsequent restarts explicitly waited for the owned PID to disappear before launching. No two clients opened the save together.

Build and installation: GraalVM 25.0.4+7.1 `gradlew build` passed, including the diagnostic check. Log: `build/dh-investigation/build.log`. Built and installed test-profile JAR SHA-256 both equal `33813F93651A93ABDA9607438B1098122DEC45E6C0FAD78B6C1BD0C3CD3C5609`. `git diff --check` passed. No shaderpack source, LOD database or permanent DH configuration changes are included in this patch.
