# Worker C overnight validation, 2026-10-31

Baseline: 907a143. Branch-local work only. Shared ai-brain read only, no push or merge. Integration owner: Claude lead.

## Item 1: optional Euphoria fixture

`IrisCompatCheck` skips the Euphoria source checks with a clear message when its default third-party fixture is absent. Supplying `-Preflexium.euphoriaFixture=<shaders directory>` makes that fixture required. Gradle forwards the property to assertion checks. Present fixtures still undergo every existing exact-source and option-ownership assertion.

Evidence in `overnight-c-2026-10-31/`:

- `build-fixture.log`: requested full build, all checks passed, 15 seconds, 25 actionable tasks.
- `build-absent.log`: requested full build after moving only this worktree's Euphoria directory aside, all checks passed, explicit skip message, 11 seconds.
- `explicit-missing.err.log`: deliberate negative test rejected `run/missing-euphoria-fixture` with the expected assertion.
- `explicit-present.log`: explicitly supplied real shaders directory passed, 7 seconds.
- `build-clean.err.log`: initial no-run-directory attempt failed at the pre-existing mandatory Iris ABI jar check before reaching Euphoria. No claim that a checkout without an Iris jar builds successfully. The requested Euphoria omission was independently checked after copying the exact Iris fixture.

Iris jar SHA-256: 566BD7515CC1CB41320A6761932093C1C3EE813FC8CBFD2077E3D128746DC1C2.

## Live work in progress

An isolated run directory was copied from t3-c5bf5f33, including its world, options, packs, Meshelium and Sodium. No real or Testing Instance profile was changed. At 08:21 EDT worker A acquired the shared GPU lock for IQ preparation/session. C has not launched a client or acquired that lock yet. C builds ran only while the foreign purpose was non-timed.

The mip default remains zero. No new live measurement or default recommendation is claimed yet. Items 2 through 7 remain pending. The prepared `tools/overnight-c-mip.ps1` is adapted from the existing mip runner, stores separate passes and enforces C ownership. It has not run live yet.

Preparation at 08:37 EDT: copied the real leaked pack-options file read only into `leaked-input.properties` and the isolated run. `leaked-input-hash.json` records its source SHA-256. The source timestamp remains 03:35:02 and its complete five-key override signature is present. This is an input fixture, not a recovery result. Both C PowerShell runners parse without syntax errors. The existing WGC capture tool built Release with zero warnings and errors. It has not captured a client yet. Worker A continues to hold the GPU lock; C remains queued.

Lead update at 08:49 EDT: the existing topmost, non-activating OLED overlay covers BOTH monitors. Leave it in place. Continue primary-monitor borderless fullscreen with the game focused underneath. Capture render targets or the owned window, never the monitor/desktop. Any FPS result must explicitly record the covered-window condition. C has not altered the overlay.

## First owned session, invalid for DLSS A/B

Lock acquired 08:58:30 EDT; harness 25781, client PID 197772. The copied run omitted the runtime cache, so startup installed DLSS 310.9.1 and required a restart. `invalid-runtime-status.json` proves configured DLSS had zero evaluations, inactive upscaling, native render dimensions, and the restart-required status. The first input capture timed out. No DLSS comparison from this session is accepted. Native sequences and the rejected first-arm status are under `invalid-first-mip/`; raw files remain under `run/overnight-c/invalid-first-mip/`. The runner now rejects inactive SR before requesting an input capture.

`session-borderless.json` records display 1, borderless true, exclusive false. The harness outer window width is 2561; the actual render target and PNG were independently checked as 2560x1440. Setting the outer width to 2560 produced a 2559-wide render target, so the outer width was restored. This geometry distinction is retained rather than claiming every reported window field equals 2560. The screenshot showed complete terrain, not the OLED overlay.

Authenticated ownership-checked quit completed. `exit-25781.json` confirms PID exit and successful foreground restoration. The controller released the GPU lock at 09:02:01. Worker A acquired it at 09:02:15; no C client remains. `first-client.log` retains startup/runtime and Meshelium diagnostics. This was not an FPS measurement. A restart is required before resuming item 2.
