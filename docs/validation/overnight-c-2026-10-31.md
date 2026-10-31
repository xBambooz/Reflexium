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
