# Documentation

## Features

- [DLSS](DLSS.md): setup, runtime download, limitations and validation
- [Frame generation](FRAME_GENERATION.md): implementation and acceptance
- [Upscaling performance](PERFORMANCE.md): per-pass timings and paired results

## Integrations

- [Vitrail](VITRAIL.md)
- [Distant Horizons](DISTANT_HORIZONS.md)
- [Late Iris/DH depth-resize verification](validation/dh-late-depth-resize.md)
- [Shader loader direction](SHADER_LOADER.md)

## Development

- [Debugging without menus](DEBUGGING.md): debug server, harness endpoint, captures
- [Implementation reference](IMPLEMENTATION_REFERENCE.md)
- [Frame generation reference](FRAME_GENERATION_REFERENCE.md) and [capability probe](FRAME_GENERATION_PROBE.md)
- [Infographics](infographics/README.md)

## Research

- [Upscaling performance research](PERFORMANCE_RESEARCH.md)
- [Reflex FPS cap research](REFLEX_FPS_CAP_RESEARCH.md)
- [Iris and native Vulkan](research/iris-vulkan-2026-10-15.md)
- [Radiance comparison](research/radiance-comparison-2026-10-19.md)

## Records

- `validation/`: dated verification reports and their evidence. They describe the code at the commits they name. [Reflex cleanup, 2026-10-23](validation/reflex-cleanup-2026-10-23/report.md), [upscaling and frame generation pass, 2026-10-23](validation/production-pass-2026-10-23.md), [shader packs, uncapped FG and walking smear, 2026-10-23](validation/pack-fg-bob-2026-10-23.md), [range pans, mobs and shaderless matrix, 2026-10-24](validation/range-pans-mobs-2026-10-24.md), [Streamline SR/Reflex migration and objective status, 2026-10-24](validation/streamline-2026-10-24.md), [Streamline DLSS-G, banding and round 5 matrix, 2026-10-24](validation/round5-2026-10-24.md), [clean load, BSL shadow cost, Streamline default and the round 6 matrix, 2026-10-24](validation/round6-2026-10-24.md).
- `benchmarks/`: benchmark protocols and raw results ([README](benchmarks/README.md)).
- `specs/`: implementation specs and reviews, kept for history. File and line references point at the commits they were written against. [Streamline migration](specs/STREAMLINE_MIGRATION_SPEC.md) is implemented through stage 2 and Streamline is now the default; the raw NGX path remains the fallback, and SR still uses it while Streamline FG is active.

- [FPS regression verification](FPS_REGRESSION_VERIFICATION.md): the latest dated verification log for the 2026-10-22 FPS work.

